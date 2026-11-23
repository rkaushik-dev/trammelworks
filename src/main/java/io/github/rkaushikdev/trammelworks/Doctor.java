package io.github.rkaushikdev.trammelworks;

import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.RetentionPolicy;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.archive.SqlFailure;
import io.github.rkaushikdev.trammelworks.forecourt.Role;
import io.github.rkaushikdev.trammelworks.ingest.Discover;
import io.github.rkaushikdev.trammelworks.ingest.Discovery;
import io.github.rkaushikdev.trammelworks.ingest.SkipReason;
import io.github.rkaushikdev.trammelworks.redact.RedactionResult;
import io.github.rkaushikdev.trammelworks.redact.Redactor;
import io.github.rkaushikdev.trammelworks.ship.ShipSettings;
import io.github.rkaushikdev.trammelworks.ship.SigningKey;
import io.github.rkaushikdev.trammelworks.spool.Budget;
import io.github.rkaushikdev.trammelworks.spool.Frames;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import io.github.rkaushikdev.trammelworks.spool.Promotion;
import io.github.rkaushikdev.trammelworks.spool.SegmentInfo;
import io.github.rkaushikdev.trammelworks.spool.Segments;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import io.github.rkaushikdev.trammelworks.spool.Usage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/**
 * The self check an engineer runs before driving away.
 *
 * <p>Commissioning a forecourt takes an afternoon and the person doing it will not be back for
 * months. The question they need answered is not "did the config parse" but "will this box still be
 * collecting, redacting and shipping next Tuesday". So every check does the real thing rather than
 * inspecting a setting: it opens the directories, writes a byte and removes it, runs a card number
 * through the actual redaction pass, and asks the archive for a row. A check that reads a config
 * field and pronounces it fine is a check that passes on a box with a full disk.
 */
public final class Doctor {

    /** A number that is a card number, used to prove the pass actually fires. */
    public static final String PROBE_PAN = "4111111111111111";

    /**
     * Lines a real pass must clean, each carrying {@link #PROBE_PAN} the way a forecourt prints it:
     * plain, inside a track 2 image, and packed into an EMV tag. A pass that masks the first and
     * misses the others looks healthy to a check that only tries the first.
     */
    public static final Map<String, String> REDACTION_PROBES = Map.of(
            "a known test card number", "probe pan=" + PROBE_PAN,
            "a track 2 image", "swipe ;" + PROBE_PAN + "=27031011234567890?",
            "a card number in an EMV tag", "icc=5A08" + PROBE_PAN + "5F2403270331");

    /**
     * How long release detection records are kept. The underground storage tank rules ask for a
     * year of them, and the archive is where a site answers an inspector from.
     */
    public static final Duration RELEASE_DETECTION_RETENTION = Duration.ofDays(365);

    /** The roles a forecourt cannot be read without. */
    public static final List<Role> EXPECTED_ROLES = List.of(Role.DISPENSER, Role.PAYMENT, Role.TANK_GAUGE);

    /** Above this fraction of the free disk, the budgets are worth a warning. */
    public static final double CROWDED = 0.8;

    /** The checks, in the order they run: the build first, because it is the first thing worth knowing. */
    public static final List<BiConsumer<SiteConfig, DoctorReport>> CHECKS = List.of(
            Doctor::checkBuild,
            Doctor::checkLogRoot,
            Doctor::checkSpool,
            Doctor::checkRedaction,
            Doctor::checkForecourt,
            Doctor::checkArchive,
            Doctor::checkShip,
            Doctor::checkSigning,
            Doctor::checkSpace,
            Doctor::checkCompaction,
            Doctor::checkState);

    private Doctor() {}

    /** Run every check against a real site. */
    public static DoctorReport runChecks(SiteConfig config) {
        DoctorReport report = new DoctorReport();
        for (BiConsumer<SiteConfig, DoctorReport> check : CHECKS) {
            check.accept(config, report);
        }
        return report;
    }

    /** Actually write into a directory, then remove what was written. Returns why not. */
    public static Optional<String> checkWritable(Path path) {
        try {
            Files.createDirectories(path);
            Path probe = Files.createTempFile(path, "tmp", ".probe");
            Files.delete(probe);
        } catch (IOException exc) {
            return Optional.of(JsonFiles.describe(exc));
        }
        return Optional.empty();
    }

    /** Whether a directory can be written, failing {@code check} with {@code refusal} and why when not. */
    static boolean writable(Path path, String check, String refusal, DoctorReport report) {
        Optional<String> problem = checkWritable(path);
        problem.ifPresent(why -> report.fail(check, refusal + ": " + why));
        return problem.isEmpty();
    }

    /** Whether the log root exists and holds files this build can read. */
    public static void checkLogRoot(SiteConfig config, DoctorReport report) {
        Path root = Path.of(config.ingest().logRoot());
        if (!Files.exists(root)) {
            report.fail("log root", root + " does not exist");
            return;
        }
        Discovery found = Discover.discover(root, config.ingest().units());
        if (found.follow().isEmpty()) {
            report.fail("log root", root + " holds no container log files we can read");
            return;
        }
        report.note("log root", "following " + found.follow().size() + " files: " + String.join(", ", found.units()));
        Integer unparseable = found.reasons().get(SkipReason.UNPARSEABLE);
        if (unparseable != null && unparseable > 0) {
            report.warn("log root", unparseable + " files under the root were not recognised");
        }
    }

    /** Whether the spool can be written and how full it is. */
    public static void checkSpool(SiteConfig config, DoctorReport report) {
        SpoolSettings settings = config.spool();
        if (!writable(settings.path(), "spool", "cannot write to " + settings.directory(), report)) {
            return;
        }
        Usage usage = Usage.measure(Segments.listSegments(settings.path()));
        Budget budget = settings.budget();
        double ratio = budget.fillRatio(usage);
        report.note("spool", usage.segments() + " segments, " + Repr.fixed(100 * ratio, 1) + "% of the budget");
        if (ratio >= 0.8) {
            report.warn("spool", "the spool is more than 80% full");
        }
    }

    /** Whether the redaction pass really masks a card number. */
    public static void checkRedaction(SiteConfig config, DoctorReport report) {
        if (!config.redact().enabled()) {
            report.warn("redaction", "redaction is turned off on this site");
            return;
        }
        Redactor redactor = Redactor.of(config.redact());
        for (Map.Entry<String, String> probe : new TreeMap<>(REDACTION_PROBES).entrySet()) {
            RedactionResult found = redactor.redactText(probe.getValue());
            if (found.text().contains(PROBE_PAN)) {
                report.fail("redaction", probe.getKey() + " survived the pass");
                return;
            }
        }
        report.note(
                "redaction",
                "card numbers are masked, " + config.redact().rules().size() + " site rules");
    }

    /**
     * Whether the containers a forecourt is read by are being followed, and whether the tank alarm
     * records the regulator can ask for will still be on the box in a year.
     */
    public static void checkForecourt(SiteConfig config, DoctorReport report) {
        Path root = Path.of(config.ingest().logRoot());
        if (Files.exists(root)) {
            Map<Role, List<String>> byRole = new EnumMap<>(Role.class);
            for (String unit : Discover.discover(root, config.ingest().units()).units()) {
                byRole.computeIfAbsent(config.forecourt().roleOf(unit), role -> new ArrayList<>())
                        .add(unit);
            }
            for (Role role : EXPECTED_ROLES) {
                if (!byRole.containsKey(role)) {
                    report.warn("forecourt", "no " + role.value().replace('_', ' ') + " container is being followed");
                }
            }
            List<String> named = new ArrayList<>();
            byRole.forEach((role, units) -> {
                if (role != Role.OTHER) {
                    named.add(role.value() + " " + String.join(", ", units));
                }
            });
            if (!named.isEmpty()) {
                report.note("forecourt", String.join("; ", named));
            }
        }
        Optional<Promotion> promoted = config.spool().table().promotions().stream()
                .filter(item -> item.label().equals(PriorityTable.SEVERITY_LABEL)
                        && item.value().equals(PriorityTable.ENVIRONMENTAL))
                .findFirst();
        if (promoted.isEmpty()) {
            report.warn("forecourt", "environmental tank alarms are not promoted, a full spool can evict them");
            return;
        }
        if (!config.archive().enabled()) {
            return;
        }
        Duration kept = config.archive().retention().windowFor(promoted.get().klass());
        if (kept.compareTo(RELEASE_DETECTION_RETENTION) < 0) {
            report.warn(
                    "forecourt",
                    "tank alarms are archived as " + promoted.get().klass() + " and kept "
                            + RetentionPolicy.formatRetention(kept) + ", release detection records are kept "
                            + RetentionPolicy.formatRetention(RELEASE_DETECTION_RETENTION));
        }
    }

    /** Whether the archive opens and answers. */
    public static void checkArchive(SiteConfig config, DoctorReport report) {
        ArchiveSettings settings = config.archive();
        if (!settings.enabled()) {
            report.note("archive", "this site keeps no archive");
            return;
        }
        if (!writable(
                settings.file().toAbsolutePath().getParent(),
                "archive",
                "cannot write beside " + settings.path(),
                report)) {
            return;
        }
        long count;
        try (ArchiveDb connection = Schema.openArchive(settings.file())) {
            count = new Repository(connection).count();
        } catch (StanchionError exc) {
            report.fail("archive", "the archive will not open: " + exc.getMessage());
            return;
        }
        report.note("archive", count + " records, " + settings.retention().describe());
    }

    /** Whether batches have somewhere real to go. */
    public static void checkShip(SiteConfig config, DoctorReport report) {
        ShipSettings settings = config.ship();
        if (settings.toFile()) {
            if (!writable(settings.fileDirectory(), "ship", "cannot write to the outbox", report)) {
                return;
            }
            report.note("ship", "writing batches to " + settings.fileDirectory());
            return;
        }
        if (settings.endpoint().startsWith("http://")) {
            report.warn("ship", "the collector endpoint is plain http");
        }
        if (settings.endpoint().contains("invalid")) {
            report.fail("ship", "the endpoint is still the placeholder: " + settings.endpoint());
            return;
        }
        report.note("ship", "shipping to " + settings.endpoint());
    }

    /** Whether the state directory beside the spool can be written. */
    public static void checkState(SiteConfig config, DoctorReport report) {
        Path state = Site.stateDirectory(config.spool().directory());
        if (!writable(state, "state", "cannot write to " + state, report)) {
            return;
        }
        report.note("state", "state directory is " + state);
    }

    /** A site that says it signs has to be able to. */
    public static void checkSigning(SiteConfig config, DoctorReport report) {
        ShipSettings settings = config.ship();
        if (!settings.signs()) {
            report.note("signing", "batches are not signed by this site");
            return;
        }
        Optional<SigningKey> key;
        try {
            key = settings.signingKey();
        } catch (StanchionError exc) {
            report.fail("signing", "the signing key will not load: " + exc.getMessage());
            return;
        }
        if (key.isEmpty()) {
            report.fail("signing", "signing is configured but no key was produced");
            return;
        }
        report.note("signing", "signing as " + key.get().keyId());
    }

    /**
     * Whether the two disk budgets can both be honoured on this card. The spool budget and the
     * archive cap are set separately, and a commissioning sheet that adds up to more than the card
     * holds only shows up months later on the night both fill.
     */
    public static void checkSpace(SiteConfig config, DoctorReport report) {
        long spool = config.spool().budgetBytes();
        long archive = config.archive().enabled() ? config.archive().maxBytes() : 0;
        long total = spool + archive;
        OptionalLong free = freeSpace(config.spool().path());
        report.note(
                "space",
                "spool " + Units.formatBytes(spool) + " plus archive " + Units.formatBytes(archive) + " is "
                        + Units.formatBytes(total));
        if (free.isEmpty()) {
            return;
        }
        if (total > free.getAsLong()) {
            report.fail(
                    "space",
                    "the budgets need " + Units.formatBytes(total) + " and the disk has "
                            + Units.formatBytes(free.getAsLong()));
        } else if (total > free.getAsLong() * CROWDED) {
            report.warn("space", "the budgets take most of the disk, " + Units.formatBytes(free.getAsLong()) + " free");
        }
    }

    /** Whether anything will ever give the disk back. */
    public static void checkCompaction(SiteConfig config, DoctorReport report) {
        if (!config.ship().compact()) {
            report.warn("compaction", "part shipped segments are never compacted");
            return;
        }
        if (config.archive().enabled() && !config.archive().vacuum()) {
            report.warn("compaction", "the archive is never vacuumed, so deletions free nothing");
            return;
        }
        report.note("compaction", "the spool compacts and the archive vacuums");
    }

    /** What the filesystem says is left under the nearest existing ancestor, or empty. */
    public static OptionalLong freeSpace(Path path) {
        Path target = path.toAbsolutePath();
        while (!Files.exists(target) && target.getParent() != null) {
            target = target.getParent();
        }
        try {
            return OptionalLong.of(Files.getFileStore(target).getUsableSpace());
        } catch (IOException exc) {
            return OptionalLong.empty();
        }
    }

    /**
     * Whether what is on this disk is what this build writes. The version promise is about the two
     * format numbers: a site can be upgraded in place and still read what the previous build left. A
     * spool holding segments from a newer format is the dangerous case: they will not decode, the
     * shipper quarantines them one at a time, and the site looks like it is working while it drops a
     * shift.
     */
    public static void checkBuild(SiteConfig config, DoctorReport report) {
        Build build = Build.current();
        report.note("build", "stanchion " + build.version() + ", spool format " + build.spoolFormat());
        Set<Integer> found = spoolFormats(config.spool().path());
        Optional<Integer> ahead = found.stream()
                .filter(item -> item > Version.SPOOL_FORMAT)
                .sorted()
                .findFirst();
        ahead.ifPresent(format -> report.fail(
                "build",
                "the spool holds segments in format " + format + ", this build writes " + Version.SPOOL_FORMAT));
        if (!config.archive().enabled() || !Files.exists(config.archive().file())) {
            return;
        }
        int onDisk;
        // Connect rather than open: opening refuses an archive from a newer build outright, and this
        // check exists to say so in a sentence an engineer can act on.
        try (ArchiveDb connection = Schema.connect(config.archive().file(), true)) {
            onDisk = Schema.readVersion(connection);
        } catch (StanchionError | SqlFailure exc) {
            report.fail("build", "the archive will not say its schema: " + exc.getMessage());
            return;
        }
        if (onDisk > Schema.SCHEMA_VERSION) {
            report.fail("build", "the archive is schema " + onDisk + " and this build writes " + Schema.SCHEMA_VERSION);
        } else if (onDisk < Schema.SCHEMA_VERSION) {
            report.warn(
                    "build",
                    "the archive is schema " + onDisk + ", it upgrades to " + Schema.SCHEMA_VERSION
                            + " on the next open");
        } else {
            report.note("build", "archive schema " + onDisk + ", current");
        }
    }

    /**
     * Which frame formats the segments on disk were written in. Reads the header of each segment
     * rather than the whole file: the format byte is the third byte of the first frame.
     */
    public static Set<Integer> spoolFormats(Path directory) {
        Set<Integer> found = new TreeSet<>();
        List<SegmentInfo> segments;
        try {
            segments = Segments.listSegments(directory);
        } catch (UncheckedIOException exc) {
            // The spool path is not a directory at all; the spool check reports that.
            return found;
        }
        for (SegmentInfo info : segments) {
            byte[] header;
            try (InputStream handle = Files.newInputStream(info.path())) {
                header = handle.readNBytes(Frames.HEADER_SIZE);
            } catch (IOException exc) {
                continue;
            }
            if (header.length < Frames.HEADER_SIZE || !Arrays.equals(Arrays.copyOf(header, 2), Frames.MAGIC)) {
                continue;
            }
            found.add(header[2] & 0xff);
        }
        return found;
    }
}
