package io.github.rkaushikdev.trammelworks.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportSummaryTest {

    static final NanoTime NOW = NanoTime.parse("2025-03-04T23:59:00Z");
    static final String SITE = "US-TX-0417";

    static final Map<String, Class<? extends Record>> SECTION_KEYS = Map.of(
            "ingest", IngestSummary.class,
            "ship", ShipSummary.class,
            "spool", SpoolSummary.class,
            "clock", ClockSummary.class,
            "forecourt", ForecourtSummary.class);

    static Summary healthy() {
        Summary built = new Summary(SITE, NOW);
        built.ingest(new IngestSummary(4, 1200, 0, 0, 0));
        built.ship(new ShipSummary(6, 1200, 40_000, 0, 0, 100, 1000, 0));
        built.spool(new SpoolSummary(100, 1000, 2, 0));
        built.clock(new ClockSummary(1, 2, 40, 0));
        built.archived(1200);
        built.check();
        return built;
    }

    static String problems(Summary built) {
        return String.join(" ", built.check());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> section(Map<String, Object> payload, String name) {
        return (Map<String, Object>) payload.get(name);
    }

    @Test
    void aHealthySiteReportsOk() {
        Summary built = healthy();
        assertThat(built.healthy()).isTrue();
        assertThat(built.renderText().split("\n")[0]).contains("ok");
    }

    @Test
    void aHealthySiteHasNoProblems() {
        assertThat(healthy().check()).isEmpty();
    }

    @Test
    void aSiteFollowingNothingIsFlagged() {
        Summary built = healthy();
        built.ingest(built.ingest().withFilesFollowed(0));
        assertThat(problems(built)).contains("no container log files");
        assertThat(built.healthy()).isFalse();
    }

    @Test
    void aRefusedBatchIsFlagged() {
        Summary built = healthy();
        built.ship(built.ship().withTerminal(3));
        assertThat(problems(built)).contains("3 batches were refused");
    }

    @Test
    void aNearlySpentAllowanceIsFlagged() {
        Summary built = healthy();
        built.ship(built.ship().withMeterUsed(950));
        assertThat(problems(built)).contains("allowance is 95% spent");
    }

    @Test
    void anAllowanceUnderTheThresholdIsNotFlagged() {
        Summary built = healthy();
        built.ship(built.ship().withMeterUsed(800));
        assertThat(built.check()).noneMatch(item -> item.contains("allowance"));
    }

    @Test
    void anEvictionIsFlagged() {
        Summary built = healthy();
        built.spool(built.spool().withEvicted(2));
        assertThat(problems(built)).contains("2 spool segments were evicted");
    }

    @Test
    void aNearlyFullSpoolIsFlagged() {
        Summary built = healthy();
        built.spool(built.spool().withUsed(850));
        assertThat(problems(built)).contains("spool is 85% full");
    }

    @Test
    void aClockThatNeverSyncedIsFlagged() {
        Summary built = healthy();
        built.clock(built.clock().withSyncs(0));
        assertThat(problems(built)).contains("clock has not been corrected");
    }

    @Test
    void aLargeDriftMakesTheClockUnhealthy() {
        Summary built = healthy();
        built.clock(built.clock().withDriftMs(120_000));
        assertThat(built.clock().healthy()).isFalse();
    }

    @Test
    void bytesLostToARotationAreFlagged() {
        Summary built = healthy();
        built.ingest(built.ingest().withLostBytes(4096));
        assertThat(problems(built)).contains("4096 bytes were lost");
    }

    @Test
    void severalProblemsAreAllReported() {
        Summary built = healthy();
        built.ship(built.ship().withTerminal(1));
        built.spool(built.spool().withEvicted(1));
        assertThat(built.check()).hasSize(2);
    }

    @Test
    void theTextFormNamesTheSiteAndTime() {
        String line = healthy().renderText().split("\n")[0];
        assertThat(line).startsWith(SITE).contains("2025-03-04T23:59:00");
    }

    @Test
    void theTextFormHasALinePerSubsystem() {
        List<String> lines = List.of(healthy().renderText().split("\n")).stream()
                .map(String::strip)
                .toList();
        for (String name : List.of("ingest", "ship", "spool", "clock")) {
            assertThat(lines).anyMatch(item -> item.startsWith(name));
        }
    }

    @Test
    void theTextFormShowsSizesInHumanUnits() {
        Summary built = healthy();
        built.spool(built.spool().withUsed(1024).withLimit(1048576));
        assertThat(built.renderText()).contains("1KiB of 1MiB");
    }

    @Test
    void theTextFormListsTheProblems() {
        Summary built = healthy();
        built.spool(built.spool().withEvicted(1));
        assertThat(built.renderText()).contains("! 1 spool segments were evicted unsent");
    }

    @Test
    void anUnhealthySummarySaysAttention() {
        Summary built = healthy();
        built.spool(built.spool().withEvicted(1));
        assertThat(built.renderText()).contains("ATTENTION");
    }

    @Test
    void theRedactionTallyAppearsWhenThereIsOne() {
        Summary built = healthy();
        built.redacted(Map.of("card_pan", 4L, "driver_id", 2L));
        assertThat(built.renderText()).contains("6 removed across 2 rules");
    }

    @Test
    void theRedactionLineIsAbsentWhenNothingWasRemoved() {
        assertThat(healthy().renderText()).doesNotContain("redact");
    }

    @Test
    void theJsonFormRoundTrips() {
        Map<String, Object> payload = Json.loadsObject(healthy().renderJson());
        assertThat(payload.get("site")).isEqualTo(SITE);
        assertThat(section(payload, "ingest").get("records")).isEqualTo(1200L);
        assertThat(payload.get("healthy")).isEqualTo(true);
    }

    @Test
    void theJsonFormListsTheProblems() {
        Summary built = healthy();
        built.spool(built.spool().withEvicted(1));
        built.check();
        assertThat((List<?>) Json.loadsObject(built.renderJson()).get("problems"))
                .isNotEmpty();
    }

    @Test
    void theJsonFormIsSortedSoADiffIsReadable() {
        String rendered = healthy().renderJson();
        assertThat(rendered).isEqualTo(healthy().renderJson());
        assertThat(rendered.indexOf("\"archived\"")).isLessThan(rendered.indexOf("\"site\""));
    }

    @Test
    void buildingFromMetricsReadsWhatIsThere() {
        Summary built = Summary.fromMetrics(
                SITE,
                NOW,
                Map.of(
                        "ingest_files_followed", 3L,
                        "ingest_records", 90L,
                        "ship_batches", 2L,
                        "ship_records", 90L,
                        "ship_bytes", 4096L,
                        "spool_bytes", 512L,
                        "spool_limit", 4096L));
        assertThat(built.ingest().records()).isEqualTo(90);
        assertThat(built.ship().batches()).isEqualTo(2);
        assertThat(built.spool().ratio()).isEqualTo(0.125);
    }

    @Test
    void buildingFromAnEmptySnapshotDoesNotFail() {
        Summary built = Summary.fromMetrics(SITE, NOW, Map.of());
        assertThat(built.ingest().records()).isZero();
        assertThat(built.healthy()).isFalse();
    }

    @Test
    void anEmptySnapshotFlagsThatNothingIsFollowed() {
        assertThat(String.join(" ", Summary.fromMetrics(SITE, NOW, Map.of()).problems()))
                .contains("no container log files");
    }

    @Test
    void aMissingSpoolLimitDoesNotDivideByZero() {
        assertThat(Summary.fromMetrics(SITE, NOW, Map.of("spool_bytes", 4L))
                        .spool()
                        .ratio())
                .isPositive();
    }

    @Test
    void everySectionFieldReachesTheJson() {
        // ShipSummary.blocked was once counted, carried and then left out of the JSON, so the
        // number that says a site is hitting its allowance never left the box. Walking the record
        // components is what stops that happening again.
        Map<String, Object> payload = new Summary(SITE, NOW).asDict();
        SECTION_KEYS.forEach((name, kind) -> assertThat(section(payload, name).keySet())
                .as(name)
                .isEqualTo(new HashSet<>(Summary.sectionKeys(kind))));
    }

    @Test
    void theBlockedCountIsInTheSummary() {
        Summary summary = new Summary(SITE, NOW);
        summary.ship(summary.ship().withBlocked(4));
        assertThat(section(summary.asDict(), "ship").get("blocked")).isEqualTo(4L);
    }

    @Test
    void aSectionDictRenamesOnlyWhatItHasTo() {
        Map<String, Object> found = Summary.sectionDict(new IngestSummary(3, 9, 0, 0, 0));
        assertThat(found)
                .containsEntry("files", 3L)
                .containsEntry("records", 9L)
                .doesNotContainKey("files_followed");
    }

    @Test
    void theRenamedKeysAreTheOnesTheEstateParses() {
        Map<String, Object> payload = new Summary(SITE, NOW).asDict();
        assertThat(section(payload, "ingest")).containsKey("files");
        assertThat(section(payload, "ship")).containsKey("bytes");
        assertThat(section(payload, "clock")).containsKey("unknown");
    }

    @Test
    void aBlockedSiteStillRendersAsText() {
        Summary summary = new Summary(SITE, NOW);
        summary.ship(summary.ship().withBlocked(2));
        assertThat(summary.renderText()).contains(SITE);
    }

    static StoredRecord stored(String time, Map<String, String> labels) {
        return new StoredRecord(
                "k" + time + labels,
                SITE,
                "pump-bridge",
                "standard",
                io.github.rkaushikdev.trammelworks.Stream.STDOUT,
                NanoTime.parse("2025-03-04T" + time + "Z"),
                java.util.Optional.empty(),
                "unknown",
                "line",
                labels,
                NOW);
    }

    @Test
    void theForecourtSectionCountsTheDay() {
        List<StoredRecord> day = List.of(
                stored("10:00:00", Map.of("pump", "1", "pump_state", "authorised")),
                stored("10:00:30", Map.of("pump", "1", "pump_state", "fuelling")),
                stored("10:02:00", Map.of("pump", "1", "pump_state", "completed")),
                stored("10:05:00", Map.of("pump", "2", "pump_state", "calling")),
                stored("10:05:20", Map.of("pump", "2", "pump_state", "fuelling")),
                stored("10:06:00", Map.of("outcome", "declined", "response", "51")),
                stored("10:07:00", Map.of("outcome", "host_unavailable", "response", "91")),
                stored("10:08:00", Map.of("outcome", "approved", "response", "00")),
                stored("10:09:00", Map.of("saf_depth", "9")),
                stored("10:30:00", Map.of("saf_depth", "3")));
        List<StoredRecord> alarms = List.of(
                stored("01:00:00", Map.of("tank", "1", "alarm", "sudden_loss", "alarm_state", "active")),
                stored("02:00:00", Map.of("tank", "2", "alarm", "high_water", "alarm_state", "active")),
                stored("03:00:00", Map.of("tank", "3", "alarm", "leak", "alarm_state", "active")),
                stored("03:30:00", Map.of("tank", "3", "alarm", "leak", "alarm_state", "clear")));
        ForecourtSummary found = ForecourtSummary.fromRecords(day, alarms);
        assertThat(found).isEqualTo(new ForecourtSummary(1, 1, 1, 1, 2, 1, 3));
        assertThat(found.healthy()).isFalse();
        assertThat(found.problems())
                .containsExactly(
                        "1 environmental tank alarms are standing",
                        "1 deliveries started without an authorisation",
                        "the payment host did not answer 1 times",
                        "3 payments are still waiting in store and forward");
        assertThat(found.line()).isEqualTo("1 sales, 1 declines, 2 tank alarms standing, 3 in store and forward");
    }

    @Test
    void aDrainedQueueIsNotAProblem() {
        ForecourtSummary found = ForecourtSummary.fromRecords(
                List.of(stored("09:00:00", Map.of("saf_depth", "12")), stored("11:00:00", Map.of("saf_depth", "0"))),
                List.of());
        assertThat(found.safDepth()).isZero();
        assertThat(found.problems()).isEmpty();
        assertThat(found.line()).isEqualTo("0 sales, 0 declines, 0 tank alarms standing");
    }

    @Test
    void aQueueStillDeepIsAProblemButNotAnAttention() {
        Summary built = healthy();
        built.forecourt(new ForecourtSummary(10, 0, 0, 0, 0, 0, 4));
        assertThat(built.check()).containsExactly("4 payments are still waiting in store and forward");
        assertThat(built.forecourt().healthy()).isTrue();
    }

    @Test
    void aStandingHighWaterAlarmIsNotAProblem() {
        ForecourtSummary found = new ForecourtSummary(40, 3, 0, 0, 1, 0, 0);
        assertThat(found.healthy()).isTrue();
        assertThat(found.problems()).isEmpty();
    }

    @Test
    void aStandingLeakTurnsTheSummaryToAttention() {
        Summary built = healthy();
        built.forecourt(new ForecourtSummary(10, 0, 0, 0, 1, 1, 0));
        assertThat(built.healthy()).isFalse();
        assertThat(built.renderText())
                .contains("ATTENTION")
                .contains("  forecourt 10 sales, 0 declines, 1 tank alarms standing")
                .contains("! 1 environmental tank alarms are standing");
        assertThat(section(built.asDict(), "forecourt")).containsEntry("environmental_standing", 1L);
    }

    @Test
    void theSectionsAreListedInTheOrderAnOperatorReads() {
        assertThat(healthy().sections().keySet()).containsExactly("ingest", "ship", "spool", "clock", "forecourt");
        assertThat(healthy()
                        .renderText()
                        .lines()
                        .skip(1)
                        .map(line -> line.substring(0, 12))
                        .toList())
                .containsExactly(
                        "  ingest    ", "  ship      ", "  spool     ", "  clock     ", "  forecourt ", "  archive   ");
    }

    @Test
    void theSectionKeysAreSnakeCase() {
        assertThat(Summary.sectionKeys(ShipSummary.class))
                .containsExactly(
                        "batches",
                        "records",
                        "bytes",
                        "retryable",
                        "terminal",
                        "meter_used",
                        "meter_allowance",
                        "blocked");
        assertThat(Summary.sectionKeys(ClockSummary.class)).containsExactly("boots", "syncs", "drift_ms", "unknown");
    }
}
