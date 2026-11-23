package io.github.rkaushikdev.trammelworks.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.QueryError;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryAggregateTest {

    static final NanoTime NOW = NanoTime.parse("2025-06-10T12:00:00Z");
    static final long HOUR_NS = 60L * 60 * 1_000_000_000;
    static final PriorityTable TABLE = new PriorityTable(Map.of("payment-kernel", "payment", "price-sign", "chatter"));

    @TempDir
    Path tmp;

    ArchiveDb connection;
    Repository repo;

    @BeforeEach
    void setUp() {
        connection = Schema.openArchive(tmp.resolve("archive").resolve("records.sqlite3"));
        repo = new Repository(connection, TABLE);
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    void add(String unit, int hoursAgo) {
        add(unit, hoursAgo, Map.of(), Stream.STDOUT);
    }

    void add(String unit, int hoursAgo, Map<String, String> labels, Stream stream) {
        repo.add(
                new LogRecord(
                                "US-TX-0417",
                                unit,
                                stream,
                                new NanoTime(NOW.epochNs() - hoursAgo * HOUR_NS),
                                "line " + unit + " " + hoursAgo)
                        .replaceLabels(labels),
                NOW);
    }

    void fill() {
        for (int index = 0; index < 6; index++) {
            add("pump-bridge", index);
        }
        for (int index = 0; index < 3; index++) {
            add("price-sign", index, Map.of("lane", "2"), Stream.STDOUT);
        }
        add("payment-kernel", 0, Map.of("lane", "2"), Stream.STDOUT);
    }

    static Query q(String text) {
        return Parser.parseQuery(text, NOW);
    }

    static List<String> keys(Tally tally) {
        return tally.groups().stream().map(Group::key).toList();
    }

    static Map<String, Long> asMap(Tally tally) {
        Map<String, Long> out = new LinkedHashMap<>();
        tally.groups().forEach(item -> out.put(item.key(), item.count()));
        return out;
    }

    void blankThePriceSignConfidence() {
        connection.transaction(() -> {
            connection.execute("UPDATE records SET confidence = '' WHERE unit = 'price-sign'");
        });
    }

    @Test
    void aGroupableFieldIsAccepted() {
        assertThat(Aggregate.checkGroup("Unit")).isEqualTo("unit");
    }

    @Test
    void groupingByMessageIsRefused() {
        assertThatThrownBy(() -> Aggregate.checkGroup("message"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("cannot group by");
    }

    @Test
    void groupingByALabelWithNoNameIsRefused() {
        assertThatThrownBy(() -> Aggregate.checkGroup("label."))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("a label needs a name");
        assertThat(Aggregate.checkGroup(" Label.Lane ")).isEqualTo("label.lane");
    }

    @Test
    void aCountByLabelIsTalliedInMemoryEvenForAPushedFilter() {
        fill();
        Tally tally = Aggregate.countBy(connection, q("unit = price-sign"), "label.lane");
        assertThat(asMap(tally)).isEqualTo(Map.of("2", 3L));
        assertThat(tally.scanned()).isEqualTo(3);
    }

    @Test
    void recordsWithoutTheLabelAreCountedAsNone() {
        fill();
        Map<String, Long> found = asMap(Aggregate.countBy(connection, new Query(), "label.lane"));
        assertThat(found).containsEntry("2", 4L).containsEntry(Aggregate.NO_VALUE, 6L);
    }

    @Test
    void aBucketStartsWhereTheOriginSays() {
        assertThat(Aggregate.bucketOf(HOUR_NS + 5, HOUR_NS)).isEqualTo(HOUR_NS);
    }

    @Test
    void anInstantOnABoundaryIsItsOwnBucket() {
        assertThat(Aggregate.bucketOf(2 * HOUR_NS, HOUR_NS)).isEqualTo(2 * HOUR_NS);
    }

    @Test
    void anInstantBeforeTheOriginFloorsDownwards() {
        assertThat(Aggregate.bucketOf(-1, HOUR_NS)).isEqualTo(-HOUR_NS);
    }

    @Test
    void aBucketOfNothingIsRefused() {
        assertThatThrownBy(() -> Aggregate.bucketOf(10, 0))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("narrower than a nanosecond");
    }

    @Test
    void aCounterKeepsTheFirstAndLastSeen() {
        GroupCounter counter = new GroupCounter();
        counter.add("pump-bridge", 500);
        counter.add("pump-bridge", 100);
        counter.add("pump-bridge", 900);
        Group group = counter.groups().get(0);
        assertThat(List.of(group.firstNs(), group.lastNs(), group.count())).containsExactly(100L, 900L, 3L);
    }

    @Test
    void aCounterPutsTheBiggestGroupFirst() {
        GroupCounter counter = new GroupCounter();
        counter.add("a", 1);
        counter.add("b", 1);
        counter.add("b", 2);
        assertThat(counter.groups()).extracting(Group::key).containsExactly("b", "a");
    }

    @Test
    void equalCountsComeBackInKeyOrder() {
        GroupCounter counter = new GroupCounter();
        counter.add("zebra", 1);
        counter.add("apple", 1);
        assertThat(counter.groups()).extracting(Group::key).containsExactly("apple", "zebra");
    }

    @Test
    void aGroupKnowsItsSpan() {
        Group group = new Group("pump-bridge", 2, 100, 900);
        assertThat(group.spanNs()).isEqualTo(800);
        assertThat(group.firstAt().epochNs()).isEqualTo(100);
    }

    @Test
    void aGroupReadsAsOneLine() {
        assertThat(new Group("pump-bridge", 4)).hasToString("pump-bridge 4");
    }

    @Test
    void aTallyTotalsItsGroups() {
        Tally tally = new Tally(List.of(new Group("a", 3), new Group("b", 2)), true);
        assertThat(tally.total()).isEqualTo(5);
        assertThat(tally.largest())
                .hasValueSatisfying(group -> assertThat(group.key()).isEqualTo("a"));
    }

    @Test
    void anEmptyTallyHasNoLargest() {
        assertThat(new Tally().largest()).isEmpty();
    }

    @Test
    void aTallySaysWhereItCounted() {
        assertThat(new Tally(List.of(), true).describe()).contains("counted in the archive");
        assertThat(new Tally(List.of(), false).describe()).contains("counted in memory");
    }

    @Test
    void countingByUnit() {
        fill();
        assertThat(asMap(Aggregate.countBy(connection, q(""), "unit")))
                .isEqualTo(Map.of("pump-bridge", 6L, "price-sign", 3L, "payment-kernel", 1L));
    }

    @Test
    void countingByClass() {
        fill();
        assertThat(asMap(Aggregate.countBy(connection, q(""), "class")))
                .isEqualTo(Map.of("standard", 6L, "chatter", 3L, "payment", 1L));
    }

    @Test
    void countingByStream() {
        add("pump-bridge", 0, Map.of(), Stream.STDERR);
        add("pump-bridge", 1);
        assertThat(asMap(Aggregate.countBy(connection, q(""), "stream"))).isEqualTo(Map.of("stderr", 1L, "stdout", 1L));
    }

    @Test
    void aCountIsNarrowedByTheFilter() {
        fill();
        assertThat(asMap(Aggregate.countBy(connection, q("unit = pump-bridge"), "unit")))
                .isEqualTo(Map.of("pump-bridge", 6L));
    }

    @Test
    void aFilterThatMatchesNothingGivesNoGroups() {
        fill();
        assertThat(Aggregate.countBy(connection, q("unit = nothing-here"), "unit")
                        .groups())
                .isEmpty();
    }

    @Test
    void aPushableFilterCountsInTheDatabase() {
        fill();
        assertThat(Aggregate.countBy(connection, q("unit = pump-bridge"), "unit")
                        .inDatabase())
                .isTrue();
    }

    @Test
    void aLabelFilterCountsInMemory() {
        fill();
        assertThat(Aggregate.countBy(connection, q("label.lane = 2"), "unit").inDatabase())
                .isFalse();
    }

    @Test
    void aLabelFilterStillCountsTheRightRecords() {
        fill();
        assertThat(asMap(Aggregate.countBy(connection, q("label.lane = 2"), "unit")))
                .isEqualTo(Map.of("price-sign", 3L, "payment-kernel", 1L));
    }

    @Test
    void theResidualIsAppliedBeforeAnythingIsCounted() {
        fill();
        Tally tally = Aggregate.countBy(connection, q("label.lane = 2"), "unit");
        assertThat(tally.postFiltered()).isEqualTo(6);
        assertThat(tally.total()).isEqualTo(4);
    }

    @Test
    void theTwoPathsAgree() {
        fill();
        Tally pushed = Aggregate.countBy(connection, q("unit in (price-sign, payment-kernel)"), "unit");
        Tally residual = Aggregate.countBy(connection, q("label.lane = 2"), "unit");
        assertThat(asMap(pushed)).isEqualTo(asMap(residual));
        assertThat(pushed.inDatabase()).isNotEqualTo(residual.inDatabase());
    }

    @Test
    void aNullAndAnEmptyValueGetTheSameLabel() {
        assertThat(Aggregate.groupKey(null)).isEqualTo(Aggregate.groupKey("")).isEqualTo("(none)");
    }

    @Test
    void aPresentValueKeepsItsOwnLabel() {
        assertThat(Aggregate.groupKey("measured")).isEqualTo("measured");
    }

    @Test
    void bothPathsLabelAMissingValueTheSame() {
        fill();
        blankThePriceSignConfidence();
        Tally pushed = Aggregate.countBy(connection, q("unit = price-sign"), "confidence");
        Tally residual = Aggregate.countBy(connection, q("label.lane = 2"), "confidence");
        assertThat(pushed.inDatabase()).isNotEqualTo(residual.inDatabase());
        assertThat(asMap(pushed)).containsKey("(none)");
        assertThat(asMap(residual)).containsKey("(none)");
    }

    @Test
    void aMissingValueGroupsAsNone() {
        fill();
        blankThePriceSignConfidence();
        assertThat(asMap(Aggregate.countBy(connection, q(""), "confidence"))).containsEntry("(none)", 3L);
    }

    @Test
    void countingOverTimeBuckets() {
        for (int index = 0; index < 5; index++) {
            add("pump-bridge", index);
        }
        Tally tally = Aggregate.countOverTime(connection, q(""), Duration.ofHours(1));
        assertThat(tally.groups()).hasSize(5);
        assertThat(tally.total()).isEqualTo(5);
    }

    @Test
    void aWiderBucketGathersMoreTogether() {
        for (int index = 0; index < 6; index++) {
            add("pump-bridge", index);
        }
        Tally tally = Aggregate.countOverTime(connection, q(""), Duration.ofHours(6));
        assertThat(tally.groups().size()).isLessThanOrEqualTo(2);
        assertThat(tally.total()).isEqualTo(6);
    }

    @Test
    void timeBucketsLineUpBetweenRuns() {
        for (int index = 0; index < 4; index++) {
            add("pump-bridge", index);
        }
        List<String> first = keys(Aggregate.countOverTime(connection, q(""), Duration.ofHours(1)));
        add("price-sign", 9);
        List<String> second = keys(Aggregate.countOverTime(connection, q(""), Duration.ofHours(1)));
        assertThat(second).containsAll(first);
    }

    @Test
    void aBucketNarrowerThanASecondIsRefused() {
        assertThatThrownBy(() -> Aggregate.countOverTime(connection, q(""), Duration.ofMillis(5)))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("at least a second");
    }

    @Test
    void theDefaultBucketIsAnHour() {
        assertThat(Aggregate.DEFAULT_BUCKET).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void countingOverTimeWithALabelFilterCountsInMemory() {
        fill();
        Tally tally = Aggregate.countOverTime(connection, q("label.lane = 2"), Duration.ofHours(1));
        assertThat(tally.inDatabase()).isFalse();
        assertThat(tally.total()).isEqualTo(4);
    }

    @Test
    void aTimeCountAndAFieldCountSeeTheSameRecords() {
        fill();
        Tally byUnit = Aggregate.countBy(connection, q(""), "unit");
        Tally overTime = Aggregate.countOverTime(connection, q(""), Duration.ofDays(1));
        assertThat(byUnit.total()).isEqualTo(overTime.total());
    }

    @Test
    void aCeilingOfNothingIsRefused() {
        fill();
        assertThatThrownBy(() -> Aggregate.countBy(connection, q("label.lane = 2"), "unit", 0))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void aLowCeilingTruncatesAndSaysSo() {
        fill();
        Tally tally = Aggregate.countBy(connection, q("label.lane = 2"), "unit", 2);
        assertThat(tally.truncated()).isTrue();
        assertThat(tally.scanned()).isEqualTo(2);
    }

    @Test
    void aCountOnAClosedArchiveIsRefused() {
        fill();
        connection.close();
        assertThatThrownBy(() -> Aggregate.countBy(connection, q(""), "unit"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("refused the count");
    }

    @Test
    void anInMemoryCountOnAClosedArchiveIsRefused() {
        fill();
        connection.close();
        assertThatThrownBy(() -> Aggregate.countBy(connection, q("label.lane = 2"), "unit"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("refused the count");
    }

    @Test
    void theGroupsCarryTheSpanTheyCover() {
        for (int index = 0; index < 4; index++) {
            add("pump-bridge", index);
        }
        Group group = Aggregate.countBy(connection, q(""), "unit").groups().get(0);
        assertThat(group.spanNs()).isEqualTo(3 * HOUR_NS);
    }

    @Test
    void theBucketRefusalNamesTheWidthAsAClock() {
        assertThatThrownBy(() -> Aggregate.countOverTime(connection, q(""), Duration.ofMillis(5)))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("0:00:00.005000");
    }
}
