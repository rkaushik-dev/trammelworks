package io.github.rkaushikdev.trammelworks;

import static io.github.rkaushikdev.trammelworks.LogFiles.criFull;
import static io.github.rkaushikdev.trammelworks.LogFiles.criLine;
import static io.github.rkaushikdev.trammelworks.SiteFixtures.delivered;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import io.github.rkaushikdev.trammelworks.forecourt.ForecourtConfig;
import io.github.rkaushikdev.trammelworks.forecourt.PumpTimeline;
import io.github.rkaushikdev.trammelworks.forecourt.Role;
import io.github.rkaushikdev.trammelworks.redact.RedactConfig;
import io.github.rkaushikdev.trammelworks.ship.FileTransport;
import io.github.rkaushikdev.trammelworks.ship.HttpTransport;
import io.github.rkaushikdev.trammelworks.ship.MemoryTransport;
import io.github.rkaushikdev.trammelworks.ship.ShipSettings;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SiteTest {

    static final String TS = "2025-03-04T12:00:00.000000000Z";
    static final String VISA = "4111111111111111";

    @TempDir
    Path tmp;

    SiteTree tree;
    final List<Site> built = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tree = new SiteTree(tmp.resolve("log"));
    }

    @AfterEach
    void tearDown() {
        built.forEach(Site::release);
    }

    SiteConfig configFor(UnaryOperator<SiteConfig.Builder> over) {
        return over.apply(SiteFixtures.config(tree, tmp)).build();
    }

    Site build(MemoryTransport transport) {
        return build(transport, UnaryOperator.identity());
    }

    Site build(MemoryTransport transport, UnaryOperator<SiteConfig.Builder> over) {
        return keep(SiteFixtures.site(configFor(over), transport));
    }

    Site keep(Site site) {
        built.add(site);
        return site;
    }

    Repository archiveOf(Site site) {
        return site.archive().orElseThrow();
    }

    List<StoredRecord> archivedOnTheDay(Site site) {
        return archiveOf(site).between(NanoTime.parse("2025-03-04T00:00:00Z"), NanoTime.parse("2025-03-05T00:00:00Z"));
    }

    byte[] spoolBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Path path : SiteFixtures.segments(tmp.resolve("spool"))) {
            out.write(Files.readAllBytes(path));
        }
        return out.toByteArray();
    }

    static SpoolSettings tinySpool(Path tmp) {
        // A spool too small to hold one framed record, so every write is refused.
        return SiteFixtures.spool(tmp).budgetBytes(200).segmentBytes(180).build();
    }

    @Test
    void theStateDirectorySitsBesideTheSpool() {
        assertThat(Site.stateDirectory("/var/lib/stanchion/spool")).isEqualTo(Path.of("/var/lib/stanchion/state"));
    }

    @Test
    void anHttpEndpointBuildsAnHttpTransport() {
        SiteConfig config = configFor(builder -> builder.ship(
                ShipSettings.builder().endpoint("https://c.invalid/v1").build()));
        assertThat(Site.buildTransport(config)).isInstanceOf(HttpTransport.class);
    }

    @Test
    void aFileEndpointBuildsAFileTransport() {
        String outbox = PosixPath.asPosix(tmp.resolve("outbox"));
        SiteConfig config = configFor(builder ->
                builder.ship(ShipSettings.builder().endpoint("file://" + outbox).build()));
        assertThat(Site.buildTransport(config)).isInstanceOf(FileTransport.class);
    }

    @Test
    void aLineTravelsFromTheLogFileToTheCollector() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "nozzle 1 lifted")));
        MemoryTransport transport = new MemoryTransport();
        Site site = build(transport);
        site.open();
        site.cycle();
        assertThat(delivered(transport)).containsExactly("nozzle 1 lifted");
    }

    @Test
    void theRecordCarriesTheSiteAndUnit() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        MemoryTransport transport = new MemoryTransport();
        Site site = build(transport);
        site.open();
        site.cycle();
        Map<String, Object> record = transport.received().get(0).records().get(0);
        assertThat(record).containsEntry("site", "US-TX-0417").containsEntry("unit", "pump-bridge");
    }

    @Test
    void aCardNumberNeverReachesTheSpool() throws IOException {
        tree.writeCri("payment-kernel", List.of(criFull(TS, "auth pan=" + VISA)));
        Site site = build(new MemoryTransport());
        site.open();
        site.collect();
        site.spool().sealAll();
        assertThat(new String(spoolBytes(), StandardCharsets.ISO_8859_1)).doesNotContain(VISA);
    }

    @Test
    void aCardNumberNeverReachesTheCollector() {
        tree.writeCri("payment-kernel", List.of(criFull(TS, "auth pan=" + VISA)));
        MemoryTransport transport = new MemoryTransport();
        Site site = build(transport);
        site.open();
        site.cycle();
        String body = new String(transport.received().get(0).encode(), StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(VISA).contains("411111******1111");
    }

    @Test
    void splitChunksArriveAsOneRecord() {
        tree.writeCri(
                "pump-bridge",
                List.of(criLine(TS, "stdout", "P", "first half "), criLine(TS, "stdout", "F", "second half")));
        MemoryTransport transport = new MemoryTransport();
        Site site = build(transport);
        site.open();
        site.cycle();
        assertThat(delivered(transport)).containsExactly("first half second half");
    }

    @Test
    void everyRecordGetsAClockConfidence() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        MemoryTransport transport = new MemoryTransport();
        Site site = build(transport);
        site.open();
        site.cycle();
        assertThat(transport.received().get(0).records().get(0)).containsEntry("clock_confidence", "unknown");
    }

    @Test
    void aChronyLineIsSeenAsASync() {
        tree.writeCri("chronyd", List.of(criFull(TS, "System clock wrong by -7.0 seconds")));
        Site site = build(new MemoryTransport());
        site.open();
        assertThat(site.collect().read()).isEqualTo(1);
    }

    @Test
    void theCollectReportCountsWhatItMoved() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one") + criFull(TS, "two")));
        Site site = build(new MemoryTransport());
        site.open();
        CollectReport report = site.collect();
        assertThat(report.read()).isEqualTo(2);
        assertThat(report.shippedToSpool()).isEqualTo(2);
        assertThat(report.redacted()).isZero();
    }

    @Test
    void aRedactedRecordIsCounted() {
        tree.writeCri("payment-kernel", List.of(criFull(TS, "pan=" + VISA)));
        Site site = build(new MemoryTransport());
        site.open();
        assertThat(site.collect().redacted()).isEqualTo(1);
    }

    @Test
    void aQuietSiteIsIdle() throws IOException {
        Files.createDirectories(tree.root());
        Site site = build(new MemoryTransport());
        site.open();
        assertThat(site.cycle().idle()).isTrue();
    }

    @Test
    void theMeterIsChargedAndSaved() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        Site site = build(new MemoryTransport());
        site.open();
        site.cycle();
        assertThat(site.meterStore().load(site.config().ship()).usedBytes()).isPositive();
    }

    @Test
    void theStateLandsBesideTheSpool() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        Site site = build(new MemoryTransport());
        site.open();
        site.cycle();
        Path state = Site.stateDirectory(site.config().spool().directory());
        assertThat(state.resolve("meter.json")).exists();
        assertThat(state.resolve("acks.json")).exists();
        assertThat(state.resolve("clock.json")).exists();
    }

    @Test
    void theStateIsNotInsideTheSpool() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        Site site = build(new MemoryTransport());
        site.open();
        site.cycle();
        assertThat(tmp.resolve("spool").resolve("meter.json")).doesNotExist();
    }

    @Test
    void aRestartDoesNotShipTheSameLineTwice() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "only once")));
        Site site = build(new MemoryTransport());
        site.open();
        site.cycle();
        site.close();
        MemoryTransport second = new MemoryTransport();
        Site again = build(second);
        again.open();
        again.cycle();
        assertThat(second.received()).isEmpty();
    }

    @Test
    void newLinesAfterARestartArePickedUp() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "before")));
        Site site = build(new MemoryTransport());
        site.open();
        site.cycle();
        site.close();
        tree.writeCri("pump-bridge", List.of(criFull(TS, "after")));
        MemoryTransport transport = new MemoryTransport();
        Site again = build(transport);
        again.open();
        again.cycle();
        assertThat(delivered(transport)).containsExactly("after");
    }

    @Test
    void closeFlushesAnOpenLine() {
        tree.writeCri("pump-bridge", List.of(criLine(TS, "stdout", "P", "never finished")));
        Site site = build(new MemoryTransport());
        site.open();
        site.collect();
        assertThat(site.close().read()).isEqualTo(1);
    }

    @Test
    void thePriorityTableReachesTheSpool() {
        SpoolSettings spool = SiteFixtures.spool(tmp)
                .priority(new PriorityTable(Map.of("payment-kernel", "payment")))
                .build();
        tree.writeCri("payment-kernel", List.of(criFull(TS, "auth")));
        Site site = build(new MemoryTransport(), builder -> builder.spool(spool));
        site.open();
        site.collect();
        site.spool().sealAll();
        assertThat(SiteFixtures.segments(tmp.resolve("spool")))
                .anyMatch(path -> path.getFileName().toString().contains("payment"));
    }

    @Test
    void aLeakFromAChatterGaugeIsSpooledAndArchivedAsPayment() {
        SpoolSettings spool = SiteFixtures.spool(tmp)
                .priority(new PriorityTable(Map.of("tank-gauge", "chatter")))
                .build();
        tree.writeCri("tank-gauge", List.of(criFull(TS, "T 1:SUDDEN LOSS ALARM")));
        Site site = build(new MemoryTransport(), builder -> builder.spool(spool));
        site.open();
        site.collect();
        site.spool().sealAll();
        assertThat(SiteFixtures.segments(tmp.resolve("spool")))
                .allMatch(path -> path.getFileName().toString().contains("payment"));
        assertThat(archivedOnTheDay(site)).extracting(StoredRecord::klass).containsExactly("payment");
    }

    @Test
    void aLabSiteMayRunWithRedactionOff() {
        tree.writeCri("payment-kernel", List.of(criFull(TS, "pan=" + VISA)));
        SiteConfig config = SiteFixtures.config(tree, tmp).build();
        SiteConfig lab = SiteConfig.builder("XX-LAB-0001")
                .ingest(config.ingest())
                .spool(config.spool())
                .ship(config.ship())
                .redact(RedactConfig.disabled())
                .archive(config.archive())
                .build();
        Site site = keep(Site.build(lab, new MemoryTransport(), new ManualClock(), null, null));
        site.open();
        assertThat(site.collect().read()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 30})
    void everyLineWrittenReachesTheCollectorOnce(int count) {
        tree.writeCri(
                "pump-bridge",
                IntStream.range(0, count)
                        .mapToObj(index -> criFull(TS, "line " + index))
                        .toList());
        MemoryTransport transport = new MemoryTransport();
        Site site = build(transport);
        site.open();
        site.cycle();
        site.close();
        site.ship();
        assertThat(delivered(transport))
                .containsExactlyInAnyOrderElementsOf(IntStream.range(0, count)
                        .mapToObj(index -> "line " + index)
                        .toList());
    }

    @Test
    void aRecordIsArchived() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "nozzle 1 lifted")));
        Site site = build(new MemoryTransport());
        site.open();
        assertThat(site.collect().archived()).isEqualTo(1);
        assertThat(archiveOf(site).count()).isEqualTo(1);
    }

    @Test
    void aDispenserLineIsArchivedWithItsFuellingPoint() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "FP03 AUTHORISED grade 2")));
        Site site = build(new MemoryTransport());
        site.open();
        site.collect();
        List<StoredRecord> found = archivedOnTheDay(site);
        assertThat(found.get(0).labels()).containsEntry("pump", "3");
        assertThat(site.snapshot()).containsEntry("forecourt_tagged", 1L);
    }

    @Test
    void aDispenserStateIsArchivedAndReplaysIntoATimeline() {
        tree.writeCri(
                "pump-bridge",
                List.of(
                        criFull(TS, "FP02 CALLING grade 1"),
                        criFull("2025-03-04T12:00:04.000000000Z", "FP02 FUELLING 3.10 l")));
        Site site = build(new MemoryTransport());
        site.open();
        site.collect();
        List<StoredRecord> found = archivedOnTheDay(site);
        assertThat(found)
                .extracting(record -> record.labels().get("pump_state"))
                .containsExactly("calling", "fuelling");
        assertThat(PumpTimeline.replay(found).unauthorised()).hasSize(1);
    }

    @Test
    void aSiteNamedRoleIsReadByTheTagger() {
        tree.writeCri("fcc", List.of(criFull(TS, "pump=7 nozzle lifted")));
        Site site = build(
                new MemoryTransport(), over -> over.forecourt(new ForecourtConfig(Map.of("fcc", Role.DISPENSER))));
        site.open();
        site.collect();
        List<StoredRecord> found = archivedOnTheDay(site);
        assertThat(found.get(0).labels()).containsEntry("pump", "7");
    }

    @Test
    void theArchivedRecordIsTheRedactedOne() {
        tree.writeCri("payment-kernel", List.of(criFull(TS, "pan=" + VISA)));
        Site site = build(new MemoryTransport());
        site.open();
        site.collect();
        List<StoredRecord> found = archivedOnTheDay(site);
        assertThat(found.get(0).message()).doesNotContain(VISA).contains("411111******1111");
    }

    @Test
    void theArchiveFileLandsWhereConfigSays() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        Site site = build(new MemoryTransport());
        site.open();
        site.collect();
        assertThat(tmp.resolve("archive").resolve("records.sqlite3")).exists();
    }

    @Test
    void aDisabledArchiveWritesNothing() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        Site site = build(
                new MemoryTransport(),
                builder ->
                        builder.archive(ArchiveSettings.builder().enabled(false).build()));
        site.open();
        assertThat(site.collect().archived()).isZero();
        assertThat(site.archive()).isEmpty();
    }

    @Test
    void theSameLineIsArchivedOnceAcrossRestarts() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "only once")));
        Site first = build(new MemoryTransport());
        first.open();
        first.collect();
        first.close();
        Site second = build(new MemoryTransport());
        second.open();
        second.collect();
        assertThat(archiveOf(second).count()).isEqualTo(1);
    }

    @Test
    void aSweepOnADisabledArchiveIsEmpty() {
        Site site = build(
                new MemoryTransport(),
                builder ->
                        builder.archive(ArchiveSettings.builder().enabled(false).build()));
        site.open();
        assertThat(site.sweep().deleted()).isZero();
    }

    @Test
    void aSweepLeavesAFreshRecordAlone() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        Site site = build(new MemoryTransport());
        site.open();
        site.collect();
        assertThat(site.sweep().deleted()).isZero();
        assertThat(archiveOf(site).count()).isEqualTo(1);
    }

    @Test
    void aRecordThatMissedTheSpoolIsStillArchived() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "a line long enough not to fit ".repeat(6))));
        Site site = build(new MemoryTransport(), builder -> builder.spool(tinySpool(tmp)));
        site.open();
        CollectReport report = site.collect();
        assertThat(report.refused()).isEqualTo(1);
        assertThat(report.shippedToSpool()).isZero();
        assertThat(report.archived()).isEqualTo(1);
    }

    @Test
    void aSpoolRefusalDoesNotStopThePass() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "x".repeat(200)), criFull(TS, "y".repeat(200))));
        Site site = build(new MemoryTransport(), builder -> builder.spool(tinySpool(tmp)));
        site.open();
        assertThat(site.collect().refused()).isEqualTo(2);
    }
}
