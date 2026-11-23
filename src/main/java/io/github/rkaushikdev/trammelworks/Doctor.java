package io.github.rkaushikdev.trammelworks;

import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.archive.SqlFailure;
import io.github.rkaushikdev.trammelworks.ingest.Discover;
import io.github.rkaushikdev.trammelworks.ingest.Discovery;
import io.github.rkaushikdev.trammelworks.ingest.SkipReason;
import io.github.rkaushikdev.trammelworks.redact.RedactionResult;
import io.github.rkaushikdev.trammelworks.redact.Redactor;
import io.github.rkaushikdev.trammelworks.ship.ShipSettings;
import io.github.rkaushikdev.trammelworks.ship.SigningKey;
import io.github.rkaushikdev.trammelworks.spool.Budget;
import io.github.rkaushikdev.trammelworks.spool.Frames;
import io.github.rkaushikdev.trammelworks.spool.SegmentInfo;
import io.github.rkaushikdev.trammelworks.spool.Segments;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import io.github.rkaushikdev.trammelworks.spool.Usage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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

    /** Above this fraction of the free disk, the budgets are worth a warning. */
    public static final double CROWDED = 0.8;

    /** The checks, in the order they run: the build first, because it is the first thing worth knowing. */
    public static final List<BiConsumer<SiteConfig, DoctorReport>> CHECKS = List.of(
            Doctor::checkBuild,
            Doctor::checkLogRoot,
            Doctor::checkSpool,
            Doctor::checkRedaction,
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
        Optional<String> problem = checkWritable(settings.path());
        if (problem.isPresent()) {
            report.fail("spool", "cannot write to " + settings.directory() + ": " + problem.get());
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
        RedactionResult found = redactor.redactText("probe pan=" + PROBE_PAN);
        if (found.text().contains(PROBE_PAN)) {
            report.fail("redaction", "a known test card number survived the pass");
            return;
        }
        report.note(
                "redaction",
                "card numbers are masked, " + config.redact().rules().size() + " site rules");
    }

    /** Whether the archive opens and answers. */
    public static void checkArchive(SiteConfig config, DoctorReport report) {
        ArchiveSettings settings = config.archive();
        if (!settings.enabled()) {
            report.note("archive", "this site keeps no archive");
            return;
        }
        Optional<String> problem =
                checkWritable(settings.file().toAbsolutePath().getParent());
        if (problem.isPresent()) {
            report.fail("archive", "cannot write beside " + settings.path() + ": " + problem.get());
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
            Optional<String> problem = checkWritable(settings.fileDirectory());
            if (problem.isPresent()) {
                report.fail("ship", "cannot write to the outbox: " + problem.get());
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
        Optional<String> problem = checkWritable(state);
        if (problem.isPresent()) {
            report.fail("state", "cannot write to " + state + ": " + problem.get());
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
