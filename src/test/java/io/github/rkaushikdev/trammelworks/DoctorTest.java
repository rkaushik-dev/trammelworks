package io.github.rkaushikdev.trammelworks;

import static io.github.rkaushikdev.trammelworks.LogFiles.criFull;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.archive.RetentionPolicy;
import io.github.rkaushikdev.trammelworks.forecourt.ForecourtConfig;
import io.github.rkaushikdev.trammelworks.forecourt.Role;
import io.github.rkaushikdev.trammelworks.redact.RedactConfig;
import io.github.rkaushikdev.trammelworks.redact.Redactor;
import io.github.rkaushikdev.trammelworks.ship.ShipSettings;
import io.github.rkaushikdev.trammelworks.spool.PriorityClass;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoctorTest {

    static final String TS = "2025-03-04T12:00:00.000000000Z";

    @TempDir
    Path tmp;

    SiteTree tree;

    @BeforeEach
    void setUp() {
        tree = new SiteTree(tmp.resolve("log"));
    }

    SiteConfig.Builder base(String site) {
        return SiteConfig.builder(site)
                .ingest(IngestConfig.at(PosixPath.asPosix(tree.root())))
                .spool(SpoolSettings.at(PosixPath.asPosix(tmp.resolve("spool"))))
                .archive(ArchiveSettings.at(
                        PosixPath.asPosix(tmp.resolve("archive").resolve("records.sqlite3"))))
                .ship(ShipSettings.builder()
                        .endpoint("file://" + PosixPath.asPosix(tmp.resolve("outbox")))
                        .build());
    }

    SiteConfig build(UnaryOperator<SiteConfig.Builder> over) {
        return over.apply(base("US-TX-0417")).build();
    }

    SiteConfig healthy() {
        return healthy(UnaryOperator.identity());
    }

    SiteConfig healthy(UnaryOperator<SiteConfig.Builder> over) {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        return build(over);
    }

    static List<Finding.Level> levels(DoctorReport report, String check) {
        return report.findings().stream()
                .filter(item -> item.check().equals(check))
                .map(Finding::level)
                .toList();
    }

    @Test
    void aGoodSitePasses() {
        DoctorReport report = Doctor.runChecks(healthy());
        assertThat(report.healthy()).isTrue();
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void checkWritableWritesAndCleansUp() throws IOException {
        assertThat(Doctor.checkWritable(tmp.resolve("fresh"))).isEmpty();
        try (Stream<Path> left = Files.list(tmp.resolve("fresh"))) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void checkWritableReportsAFileInTheWay() throws IOException {
        Path blocker = tmp.resolve("spool");
        Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);
        assertThat(Doctor.checkWritable(blocker)).isPresent();
    }

    @Test
    void aMissingLogRootIsAFailure() {
        DoctorReport report =
                Doctor.runChecks(build(builder -> builder.ingest(IngestConfig.at("/definitely/not/here"))));
        assertThat(levels(report, "log root")).contains(Finding.Level.FAIL);
        assertThat(report.healthy()).isFalse();
    }

    @Test
    void anEmptyLogRootIsAFailure() throws IOException {
        Files.createDirectories(tree.root());
        assertThat(levels(Doctor.runChecks(build(UnaryOperator.identity())), "log root"))
                .contains(Finding.Level.FAIL);
    }

    @Test
    void aFollowedUnitIsNamed() {
        assertThat(Doctor.runChecks(healthy()).findings())
                .anyMatch(item -> item.message().contains("pump-bridge"));
    }

    @Test
    void unrecognisedFilesUnderTheRootAreAWarning() throws IOException {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        Files.writeString(tree.root().resolve("messages"), "not ours", StandardCharsets.UTF_8);
        assertThat(levels(Doctor.runChecks(build(UnaryOperator.identity())), "log root"))
                .contains(Finding.Level.WARN);
    }

    @Test
    void aSpoolThatCannotBeWrittenIsAFailure() throws IOException {
        Files.writeString(tmp.resolve("spool"), "not a directory", StandardCharsets.UTF_8);
        assertThat(levels(Doctor.runChecks(healthy()), "spool")).contains(Finding.Level.FAIL);
    }

    @Test
    void theRedactionCheckActuallyRunsThePass() {
        DoctorReport report = Doctor.runChecks(healthy());
        assertThat(levels(report, "redaction")).contains(Finding.Level.NOTE);
        assertThat(report.findings()).noneMatch(item -> item.message().contains(Doctor.PROBE_PAN));
    }

    @Test
    void everyRedactionProbeComesOutCleanThroughTheRealPass() {
        Redactor pass = Redactor.of(healthy().redact());
        assertThat(Doctor.REDACTION_PROBES.values())
                .allSatisfy(line -> assertThat(pass.redactText(line).text()).doesNotContain(Doctor.PROBE_PAN));
        assertThat(Doctor.REDACTION_PROBES).containsKeys("a track 2 image", "a card number in an EMV tag");
    }

    @Test
    void aForecourtMissingItsTankGaugeIsWarned() {
        tree.writeCri("payment-kernel", List.of(criFull(TS, "up")));
        DoctorReport report = Doctor.runChecks(healthy());
        assertThat(report.findings())
                .filteredOn(item -> item.check().equals("forecourt"))
                .extracting(Finding::message)
                .contains("no tank gauge container is being followed", "dispenser pump-bridge; payment payment-kernel")
                .noneMatch(message -> message.contains("no dispenser"));
    }

    @Test
    void aSiteNamedRoleCountsAsFollowed() {
        tree.writeCri("atg", List.of(criFull(TS, "up")));
        tree.writeCri("payment-kernel", List.of(criFull(TS, "up")));
        DoctorReport report =
                Doctor.runChecks(healthy(over -> over.forecourt(new ForecourtConfig(Map.of("atg", Role.TANK_GAUGE)))));
        assertThat(report.findings())
                .filteredOn(item -> item.check().equals("forecourt"))
                .extracting(Finding::message)
                .noneMatch(message -> message.startsWith("no "))
                .anyMatch(message -> message.contains("tank_gauge atg"));
    }

    @Test
    void alarmsKeptLessThanAYearAreWarned() {
        DoctorReport report = Doctor.runChecks(healthy());
        assertThat(report.findings())
                .filteredOn(item -> item.check().equals("forecourt") && item.level() == Finding.Level.WARN)
                .extracting(Finding::message)
                .contains("tank alarms are archived as payment and kept 180d, release detection records are kept 365d");
    }

    @Test
    void alarmsKeptAYearAreNotWarned() {
        SiteConfig config = healthy(over -> over.archive(ArchiveSettings.builder()
                .path(PosixPath.asPosix(tmp.resolve("archive").resolve("records.sqlite3")))
                .retention(new RetentionPolicy(Map.of("payment", Duration.ofDays(400))))
                .build()));
        assertThat(Doctor.runChecks(config).findings())
                .noneMatch(item -> item.message().contains("release detection"));
    }

    @Test
    void aTableThatPromotesNothingIsWarned() {
        SpoolSettings spool = SpoolSettings.builder()
                .directory(PosixPath.asPosix(tmp.resolve("spool")))
                .priority(new PriorityTable(List.of(new PriorityClass("only", 5)), Map.of(), "only"))
                .build();
        assertThat(Doctor.runChecks(healthy(over -> over.spool(spool))).findings())
                .extracting(Finding::message)
                .contains("environmental tank alarms are not promoted, a full spool can evict them");
    }

    @Test
    void redactionTurnedOffIsAWarning() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        SiteConfig config = base("XX-LAB-0001").redact(RedactConfig.disabled()).build();
        assertThat(levels(Doctor.runChecks(config), "redaction")).contains(Finding.Level.WARN);
    }

    @Test
    void theArchiveCheckOpensIt() {
        DoctorReport report = Doctor.runChecks(healthy());
        assertThat(levels(report, "archive")).contains(Finding.Level.NOTE);
        assertThat(tmp.resolve("archive").resolve("records.sqlite3")).exists();
    }

    @Test
    void anUnopenableArchiveIsAFailure() throws IOException {
        Path target = tmp.resolve("archive").resolve("records.sqlite3");
        Files.createDirectories(target.getParent());
        Files.write(target, "not a database".repeat(20).getBytes(StandardCharsets.UTF_8));
        assertThat(levels(Doctor.runChecks(healthy()), "archive")).contains(Finding.Level.FAIL);
    }

    @Test
    void aDisabledArchiveIsOnlyANote() {
        SiteConfig config = healthy(builder ->
                builder.archive(ArchiveSettings.builder().enabled(false).build()));
        assertThat(levels(Doctor.runChecks(config), "archive")).containsExactly(Finding.Level.NOTE);
    }

    @Test
    void thePlaceholderEndpointIsAFailure() {
        SiteConfig config = healthy(builder -> builder.ship(new ShipSettings()));
        assertThat(levels(Doctor.runChecks(config), "ship")).contains(Finding.Level.FAIL);
    }

    @Test
    void theRequestOverheadIsNamedBesideTheEndpoint() {
        SiteConfig config = healthy(builder -> builder.ship(ShipSettings.builder()
                .endpoint("https://collector.example.net/v1/ingest")
                .requestOverheadBytes(6144)
                .build()));
        assertThat(Doctor.runChecks(config).findings())
                .extracting(Finding::message)
                .contains("shipping to https://collector.example.net/v1/ingest, 6KiB charged per request on top of each"
                        + " batch");
    }

    @Test
    void aPlainHttpEndpointIsAWarning() {
        SiteConfig config = healthy(builder -> builder.ship(
                ShipSettings.builder().endpoint("http://10.0.0.9/v1").build()));
        assertThat(levels(Doctor.runChecks(config), "ship")).contains(Finding.Level.WARN);
    }

    @Test
    void aFileEndpointIsCheckedForWritability() {
        DoctorReport report = Doctor.runChecks(healthy());
        assertThat(levels(report, "ship")).contains(Finding.Level.NOTE);
        assertThat(tmp.resolve("outbox")).isDirectory();
    }

    @Test
    void theStateDirectoryIsChecked() {
        assertThat(levels(Doctor.runChecks(healthy()), "state")).contains(Finding.Level.NOTE);
    }

    @Test
    void theReportCountsWhatItFound() {
        String rendered = Doctor.runChecks(healthy()).render();
        assertThat(rendered).contains("0 failures").contains("checks");
    }

    @Test
    void aFindingReadsAsOneLine() {
        DoctorReport report = new DoctorReport();
        report.fail("spool", "cannot write");
        assertThat(report.findings().get(0)).hasToString("FAIL spool              cannot write");
    }

    @Test
    void theRenderListsEveryFinding() {
        DoctorReport report = new DoctorReport();
        report.fail("a", "one");
        report.warn("b", "two");
        report.note("c", "three");
        assertThat(report.render().split("\n")).hasSize(4);
        assertThat(report.healthy()).isFalse();
    }

    @Test
    void warningsAloneLeaveTheSiteHealthy() {
        DoctorReport report = new DoctorReport();
        report.warn("a", "something to raise a ticket about");
        assertThat(report.healthy()).isTrue();
        assertThat(report.warnings()).hasSize(1);
    }
}
