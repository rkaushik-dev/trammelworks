package io.github.rkaushikdev.trammelworks;

import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.archive.Footprint;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.archive.Space;
import io.github.rkaushikdev.trammelworks.archive.SweepReport;
import io.github.rkaushikdev.trammelworks.archive.Sweeper;
import io.github.rkaushikdev.trammelworks.clockfix.BootIdentity;
import io.github.rkaushikdev.trammelworks.clockfix.ClockStore;
import io.github.rkaushikdev.trammelworks.clockfix.Reconciler;
import io.github.rkaushikdev.trammelworks.clockfix.SessionLedger;
import io.github.rkaushikdev.trammelworks.clockfix.SyncObserver;
import io.github.rkaushikdev.trammelworks.ingest.CheckpointStore;
import io.github.rkaushikdev.trammelworks.ingest.IngestEngine;
import io.github.rkaushikdev.trammelworks.ingest.MultilineRule;
import io.github.rkaushikdev.trammelworks.redact.Redactor;
import io.github.rkaushikdev.trammelworks.ship.AckStore;
import io.github.rkaushikdev.trammelworks.ship.Compaction;
import io.github.rkaushikdev.trammelworks.ship.CompactionResult;
import io.github.rkaushikdev.trammelworks.ship.Compactor;
import io.github.rkaushikdev.trammelworks.ship.FileTransport;
import io.github.rkaushikdev.trammelworks.ship.HttpTransport;
import io.github.rkaushikdev.trammelworks.ship.MeterStore;
import io.github.rkaushikdev.trammelworks.ship.ShipReport;
import io.github.rkaushikdev.trammelworks.ship.ShipSettings;
import io.github.rkaushikdev.trammelworks.ship.Shipper;
import io.github.rkaushikdev.trammelworks.ship.Signer;
import io.github.rkaushikdev.trammelworks.ship.Transport;
import io.github.rkaushikdev.trammelworks.spool.Spool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One forecourt, end to end.
 *
 * <p>This is the piece that says what order things happen in, and the order is the design:
 *
 * <pre>
 * read -&gt; reassemble -&gt; correct the clock -&gt; redact -&gt; spool -&gt; ship
 * </pre>
 *
 * <p><b>Redaction sits before the spool, not before the ship.</b> The spool is a file on a disk in a
 * cabinet under a shop counter, and that disk gets pulled, imaged and posted to a lab when a
 * controller dies. Cardholder data that reached the spool has left our control whatever the shipper
 * does afterwards.
 *
 * <p><b>The archive gets the same redacted record the spool does.</b> It is written after the
 * spool, not instead of it: the spool is what the estate will see, the archive is what this site can
 * still answer from six weeks later. A record that failed to reach the spool is still archived,
 * because a record we could not send is the one somebody will come asking about.
 *
 * <p><b>Clock correction sits before redaction.</b> Correction reads the time daemon's lines to find
 * out when the clock was fixed, and redaction rewrites message text, so running redaction first
 * could eat the very line the correction depends on.
 *
 * <p>A cycle is collect then ship, and it is safe to stop between the two. Records that reached the
 * spool are durable; anything that did not is still in the container's log files, and the checkpoint
 * has not moved past it.
 */
public final class Site {

    /** The state directory's name, beside the spool. */
    public static final String STATE_DIRNAME = "state";

    /** Where read positions are kept, inside the state directory. */
    public static final String CHECKPOINT_DIRNAME = "checkpoints";

    private final SiteConfig config;
    private final Transport transport;
    private final Clock clock;
    private final EventLog events;
    private final Registry metrics;
    private final IngestEngine engine;
    private final Spool spool;
    private final Shipper shipper;
    private final Redactor redactor;
    private final Reconciler reconciler;
    private final SyncObserver observer;
    private final ClockStore clockStore;
    private final MeterStore meterStore;
    private final AckStore ackStore;
    private Repository archive;
    private Sweeper sweeper;

    private Site(SiteConfig config, Transport transport, Clock clock, EventLog events, Registry metrics) {
        this.config = config;
        this.transport = transport;
        this.clock = clock;
        this.events = events;
        this.metrics = metrics;
        Path state = stateDirectory(config.spool().directory());
        this.clockStore = new ClockStore(state);
        this.meterStore = new MeterStore(state);
        this.ackStore = new AckStore(state);
        BootIdentity boot = BootIdentity.current(state);
        SessionLedger ledger = clockStore.load();
        ledger.session(boot.bootId());
        this.observer = new SyncObserver(ledger, boot.bootId());
        this.reconciler = new Reconciler(ledger, boot.bootId(), clock);
        this.redactor = Redactor.of(config.redact());
        Map<String, MultilineRule> rules = new LinkedHashMap<>();
        for (MultilineRule rule : config.ingest().multiline()) {
            rules.put(rule.unit(), rule);
        }
        this.engine = IngestEngine.builder(config)
                .checkpoints(new CheckpointStore(state.resolve(CHECKPOINT_DIRNAME)))
                .events(events)
                .metrics(metrics)
                .rules(rules)
                .clock(clock)
                .build();
        this.spool =
                Spool.builder(config.spool()).events(events).metrics(metrics).build();
        this.shipper = Shipper.builder(config.site(), config.spool().path(), transport)
                .meter(meterStore.load(config.ship()))
                .acks(ackStore.load())
                .table(config.spool().table())
                .limits(config.ship().limits())
                .clock(clock)
                .events(events)
                .metrics(metrics)
                .deleteAcknowledged(config.ship().deleteAcknowledged())
                .build();
    }

    /** A site with the transport its config names, the system clock, no events and fresh metrics. */
    public static Site build(SiteConfig config) {
        return build(config, null, null, null, null);
    }

    /** A site with whichever collaborators are given; {@code null} means the default. */
    public static Site build(SiteConfig config, Transport transport, Clock clock, EventLog events, Registry metrics) {
        Clock chosen = clock != null ? clock : new SystemClock();
        return new Site(
                config,
                transport != null ? transport : buildTransport(config, chosen),
                chosen,
                events != null ? events : EventLog.nullLog(),
                metrics != null ? metrics : new Registry());
    }

    /**
     * State lives beside the spool, never inside it. Inside, an eviction sweep would be free to
     * delete the checkpoints and the meter, and the site would come back thinking it had never read
     * or sent anything.
     */
    public static Path stateDirectory(String spoolDirectory) {
        PosixPath parent = PosixPath.of(spoolDirectory).parent();
        return Path.of(parent.toString()).resolve(STATE_DIRNAME);
    }

    /** The signer this site's config asks for, if it asks for one. */
    public static Optional<Signer> buildSigner(SiteConfig config, Clock clock) {
        return config.ship().signingKey().map(key -> new Signer(key, clock != null ? clock : new SystemClock()));
    }

    /** The transport this site's endpoint asks for, signing with the system clock. */
    public static Transport buildTransport(SiteConfig config) {
        return buildTransport(config, null);
    }

    /** The transport this site's endpoint asks for. */
    public static Transport buildTransport(SiteConfig config, Clock clock) {
        ShipSettings settings = config.ship();
        Signer signer = buildSigner(config, clock).orElse(null);
        if (settings.toFile()) {
            return new FileTransport(settings.fileDirectory(), signer);
        }
        return new HttpTransport(
                settings.endpoint(), Units.totalSeconds(settings.attemptTimeout()), settings.token(), signer, null);
    }

    /** The config. */
    public SiteConfig config() {
        return config;
    }

    /** Where batches go. */
    public Transport transport() {
        return transport;
    }

    /** The clock. */
    public Clock clock() {
        return clock;
    }

    /** The event log. */
    public EventLog events() {
        return events;
    }

    /** The counters. */
    public Registry metrics() {
        return metrics;
    }

    /** The readers. */
    public IngestEngine engine() {
        return engine;
    }

    /** The on disk spool. */
    public Spool spool() {
        return spool;
    }

    /** The uplink. */
    public Shipper shipper() {
        return shipper;
    }

    /** The redaction pass. */
    public Redactor redactor() {
        return redactor;
    }

    /** Clock correction. */
    public Reconciler reconciler() {
        return reconciler;
    }

    /** What watches for clock fixes. */
    public SyncObserver observer() {
        return observer;
    }

    /** Where the clock ledger is kept. */
    public ClockStore clockStore() {
        return clockStore;
    }

    /** Where the meter is kept. */
    public MeterStore meterStore() {
        return meterStore;
    }

    /** Where acknowledgements are kept. */
    public AckStore ackStore() {
        return ackStore;
    }

    /** The archive, once open, unless this site keeps none. */
    public Optional<Repository> archive() {
        return Optional.ofNullable(archive);
    }

    /** The retention sweeper, once the archive is open. */
    public Optional<Sweeper> sweeper() {
        return Optional.ofNullable(sweeper);
    }

    /** Recover anything an interrupted run left and pick up the files. */
    public void open() {
        List<Path> stray = Compaction.clearSidecars(config.spool().path());
        if (!stray.isEmpty()) {
            events.warn("compaction_sidecars_cleared", "count", stray.size());
        }
        spool.open();
        openArchive();
        engine.refresh();
        events.info(
                "site_open", "site", config.site(), "files", engine.pipelines().size());
    }

    private void openArchive() {
        ArchiveSettings settings = config.archive();
        if (!settings.enabled()) {
            return;
        }
        ArchiveDb connection = Schema.openArchive(settings.file());
        archive = new Repository(connection, config.spool().table());
        sweeper = new Sweeper(connection, settings.retention(), settings.maxDeletes());
        events.info("archive_open", "path", settings.file().toString(), "records", archive.count());
    }

    /** Read what is new and put it in the spool. */
    public CollectReport collect() {
        CollectReport report = new CollectReport();
        engine.refresh();
        List<LogRecord> records = engine.poll();
        report.read(records.size());
        absorb(records, report);
        return report;
    }

    /**
     * Put records that came from somewhere other than the tailer through. The backfill reads
     * rotated files and hands them here, so they go through exactly the same correction, redaction,
     * spool and archive path as the live ones. A second route into the spool that skipped redaction
     * is the one bug this system cannot afford.
     */
    public CollectReport absorb(List<LogRecord> records) {
        CollectReport report = new CollectReport();
        report.read(records.size());
        absorb(records, report);
        return report;
    }

    private void absorb(List<LogRecord> records, CollectReport report) {
        // The archive is fed the CLEANED records, not the ones that came in. Handing it the
        // originals would put cardholder data in the one file on the box designed to still be
        // there in six months.
        List<LogRecord> cleanedAll = new ArrayList<>();
        for (LogRecord record : records) {
            observer.look(record);
            LogRecord corrected = reconciler.reconcile(record);
            LogRecord cleaned = redactor.redact(corrected);
            if (cleaned != corrected) {
                report.redactedOne();
            }
            cleanedAll.add(cleaned);
            try {
                spool.append(cleaned);
            } catch (RuntimeException exc) {
                // Reported, never raised on: one refused record must not stop the pass.
                report.refusedOne();
                metrics.incr("site_spool_refused");
                events.exception("spool_refused", exc, "unit", record.unit());
                continue;
            }
            report.spooled();
        }
        report.archived(archiveAll(cleanedAll));
        report.syncsSeen(observer.filed());
    }

    /**
     * Keep a local copy. A failure here is reported, never raised on: the archive answers disputes
     * and nothing on the forecourt stops if it cannot be written.
     */
    private long archiveAll(List<LogRecord> records) {
        if (archive == null) {
            return 0;
        }
        try {
            return archive.addAll(records, clock.now());
        } catch (RuntimeException exc) {
            metrics.incr("site_archive_failed");
            events.exception("archive_write_failed", exc);
            return 0;
        }
    }

    /** One pass of the uplink. */
    public ShipReport ship() {
        spool.sealAll();
        ShipReport report = shipper.runOnce();
        compact();
        persist();
        return report;
    }

    /**
     * Give back the disk that acknowledged records are still holding. After the pass, not before: a
     * segment the shipper just worked on is the one most likely to be part acknowledged, and doing it
     * here means the rewrite happens while nothing is reading.
     */
    public CompactionResult compact() {
        ShipSettings settings = config.ship();
        if (!settings.compact()) {
            return new CompactionResult();
        }
        Compactor compactor = new Compactor(
                config.spool().path(),
                shipper.acks(),
                settings.compactMinReclaimBytes(),
                settings.compactMinFraction(),
                events,
                metrics);
        CompactionResult result = compactor.run(spool.segments(), shipper.pinned());
        if (result.segments() != 0) {
            events.info("spool_compacted", "segments", result.segments(), "freed", result.reclaimed());
        }
        return result;
    }

    /** Collect, then ship. */
    public CycleReport cycle() {
        CollectReport collected = collect();
        return new CycleReport(collected, ship());
    }

    /** Apply retention to the archive. */
    public SweepReport sweep() {
        return housekeeper().map(Housekeeper::sweep).orElseGet(SweepReport::new);
    }

    /** One pass of retention, the cap and the vacuum, in that order. */
    public HousekeepReport housekeep() {
        return housekeeper().map(Housekeeper::run).orElseGet(HousekeepReport::new);
    }

    /** The archive housekeeper, or empty on a site that keeps no archive. */
    public Optional<Housekeeper> housekeeper() {
        if (archive == null || sweeper == null) {
            return Optional.empty();
        }
        return Optional.of(new Housekeeper(
                archive.db(), config.archive(), sweeper, config.spool().table(), clock, events, metrics));
    }

    /**
     * Every counter the site keeps, in one mapping, sorted by name. Gathered rather than stored, so
     * it is always the live numbers; the nightly summary and the end to end tests both read it.
     */
    public Map<String, Long> snapshot() {
        Map<String, Long> found = new TreeMap<>(Json::compareCodePoints);
        found.putAll(metrics.snapshot());
        found.put("spool_limit", config.spool().budgetBytes());
        found.put("spool_used", spool.usage().used());
        found.put("ship_meter_used", shipper.meter().usedBytes());
        found.put("ship_meter_allowance", shipper.meter().allowanceBytes());
        found.put("ship_outstanding", shipper.acks().outstanding());
        found.put("redact_records", redactor.audit().records());
        found.put("redact_changed", redactor.audit().changed());
        found.put("redact_removed", redactor.audit().totalHits());
        found.put("clock_syncs", (long) observer.filed());
        if (archive != null) {
            found.put("archive_records", archive.count());
            Footprint footprint = Space.measure(archive.db(), config.archive().file());
            found.put("archive_bytes", footprint.total());
            found.put("archive_used", footprint.used());
            found.put("archive_reclaimable", footprint.reclaimable());
            found.put("archive_limit", config.archive().maxBytes());
        }
        return new LinkedHashMap<>(found);
    }

    /** Write the state that must survive a power cut. */
    public void persist() {
        meterStore.save(shipper.meter());
        ackStore.save(shipper.acks());
        clockStore.save(observer.ledger());
    }

    /**
     * Shut down: flush the readers, seal the spool, save the state. The archive stays open, so the
     * counters can still be read; {@link #release()} lets go of it.
     */
    public CollectReport close() {
        CollectReport report = new CollectReport();
        List<LogRecord> records = engine.close();
        report.read(records.size());
        absorb(records, report);
        spool.sealAll();
        persist();
        events.info("site_closed", "site", config.site());
        return report;
    }

    /** Let go of the archive file. Nothing that reads the archive works afterwards. */
    public void release() {
        if (archive != null && !archive.db().isClosed()) {
            archive.db().close();
        }
    }
}
