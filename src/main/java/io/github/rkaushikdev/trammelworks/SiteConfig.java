package io.github.rkaushikdev.trammelworks;

import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.forecourt.ForecourtConfig;
import io.github.rkaushikdev.trammelworks.redact.RedactConfig;
import io.github.rkaushikdev.trammelworks.ship.ShipSettings;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything one forecourt controller needs to know about itself.
 *
 * <p>One file per site, written at commissioning and then almost never touched. It has to fail
 * loudly at load rather than quietly at three in the morning, so the sections are checked against
 * each other here as well as on their own.
 */
public record SiteConfig(
        String site,
        IngestConfig ingest,
        SpoolSettings spool,
        ShipSettings ship,
        RedactConfig redact,
        ArchiveSettings archive,
        ForecourtConfig forecourt,
        Map<String, String> labels) {

    /** The top level sections a config may carry. */
    public static final Set<String> SECTIONS =
            Set.of("site", "ingest", "spool", "ship", "redact", "archive", "forecourt", "labels");

    /** Normalizes the site id and checks the sections against each other. */
    public SiteConfig {
        site = Identity.parseSite(site);
        redact.checkSite(site);
        archive.checkAgainstSpool(spool.directory());
        for (String key : labels.keySet()) {
            if (!Text.isAlnum(key.replace("_", "").replace("-", ""))) {
                throw new ConfigError("malformed label key", key);
            }
        }
        labels = Maps.frozen(labels);
    }

    /** A site with every section at its defaults. */
    public static SiteConfig of(String site) {
        return builder(site).build();
    }

    /** A builder for {@code site}, starting from the defaults. */
    public static Builder builder(String site) {
        return new Builder(site);
    }

    /** A builder starting from this config. */
    public Builder toBuilder() {
        return new Builder(site)
                .ingest(ingest)
                .spool(spool)
                .ship(ship)
                .redact(redact)
                .archive(archive)
                .forecourt(forecourt)
                .labels(labels);
    }

    /**
     * The config as plain values, in the shape the file is written in. The redact and forecourt
     * sections write themselves, since they hold nested blocks only they know the shape of.
     */
    public Map<String, Object> asDict() {
        return Maps.of(
                "site",
                site,
                "ingest",
                Maps.of(
                        "log_root", ingest.logRoot(),
                        "poll_interval", Units.totalSeconds(ingest.pollInterval()),
                        "read_chunk", ingest.readChunk(),
                        "max_line_bytes", ingest.maxLineBytes(),
                        "units", List.copyOf(ingest.units())),
                "redact",
                redact.asDict(),
                "spool",
                Maps.of(
                        "directory", spool.directory(),
                        "budget", spool.budgetBytes(),
                        "segment", spool.segmentBytes(),
                        "reserve_ratio", spool.reserveRatio(),
                        "sync_every", spool.syncEvery(),
                        "compress", spool.compress()),
                "ship",
                Maps.of(
                        "endpoint", ship.endpoint(),
                        "monthly_allowance", ship.monthlyAllowanceBytes(),
                        "anchor_day", ship.anchorDay(),
                        "batch", ship.batchBytes(),
                        "batch_records", ship.batchRecords(),
                        "attempt_timeout", Units.totalSeconds(ship.attemptTimeout()),
                        "interval", Units.totalSeconds(ship.interval()),
                        "delete_acknowledged", ship.deleteAcknowledged()),
                "archive",
                Maps.of(
                        "enabled", archive.enabled(),
                        "path", archive.path(),
                        "max_bytes", archive.maxBytes(),
                        "max_deletes", archive.maxDeletes(),
                        "sweep_every", Units.totalSeconds(archive.sweepEvery())),
                "forecourt",
                forecourt.asDict(),
                "labels",
                new LinkedHashMap<>(labels));
    }

    /** Build a config from an already parsed mapping. */
    public static SiteConfig siteFromMapping(Map<String, ?> raw) {
        Object site = Sections.require(raw, "site", "config", "site id");
        Sections.checkKeys(raw, SECTIONS, "config");
        Map<String, String> labels = new LinkedHashMap<>();
        Sections.section(raw, "labels", "config").forEach((key, value) -> labels.put(key, Coerce.text(value)));
        return builder(Coerce.text(site))
                .ingest(IngestConfig.ingestFromMapping(Sections.section(raw, "ingest", "config")))
                .spool(SpoolSettings.spoolSettingsFromMapping(Sections.section(raw, "spool", "config")))
                .ship(ShipSettings.shipSettingsFromMapping(Sections.section(raw, "ship", "config")))
                .redact(RedactConfig.redactFromMapping(Sections.section(raw, "redact", "config")))
                .archive(ArchiveSettings.archiveSettingsFromMapping(Sections.section(raw, "archive", "config")))
                .forecourt(ForecourtConfig.forecourtFromMapping(Sections.section(raw, "forecourt", "config")))
                .labels(labels)
                .build();
    }

    /** A site config built a section at a time, starting from the defaults. */
    public static final class Builder {
        private final String site;
        private IngestConfig ingest = new IngestConfig();
        private SpoolSettings spool = new SpoolSettings();
        private ShipSettings ship = new ShipSettings();
        private RedactConfig redact = new RedactConfig();
        private ArchiveSettings archive = new ArchiveSettings();
        private ForecourtConfig forecourt = new ForecourtConfig();
        private Map<String, String> labels = Map.of();

        private Builder(String site) {
            this.site = site;
        }

        /** How the runtime's logs are read. */
        public Builder ingest(IngestConfig value) {
            ingest = value;
            return this;
        }

        /** The on disk spool. */
        public Builder spool(SpoolSettings value) {
            spool = value;
            return this;
        }

        /** The uplink. */
        public Builder ship(ShipSettings value) {
            ship = value;
            return this;
        }

        /** The redaction pass. */
        public Builder redact(RedactConfig value) {
            redact = value;
            return this;
        }

        /** The local archive. */
        public Builder archive(ArchiveSettings value) {
            archive = value;
            return this;
        }

        /** Which container plays which part on the forecourt. */
        public Builder forecourt(ForecourtConfig value) {
            forecourt = value;
            return this;
        }

        /** Labels stamped on every record. */
        public Builder labels(Map<String, String> value) {
            labels = value;
            return this;
        }

        /** The validated config. */
        public SiteConfig build() {
            return new SiteConfig(site, ingest, spool, ship, redact, archive, forecourt, labels);
        }
    }
}
