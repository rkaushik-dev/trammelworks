package io.github.rkaushikdev.trammelworks.cli;

import static io.github.rkaushikdev.trammelworks.cli.CliFixtures.posix;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.query.Group;
import io.github.rkaushikdev.trammelworks.query.Tally;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliCountTest {

    static final NanoTime NOW = NanoTime.parse("2025-06-10T12:00:00Z");
    static final long HOUR_NS = 60L * 60 * 1_000_000_000;
    static final PriorityTable TABLE = new PriorityTable(Map.of("payment-kernel", "payment", "price-sign", "chatter"));

    @TempDir
    Path tmp;

    Path archivePath;
    Path config;

    @BeforeEach
    void setUp() {
        archivePath = tmp.resolve("archive").resolve("records.sqlite3");
        config = CliFixtures.writeConfig(
                tmp,
                "site: US-TX-0417\n"
                        + "ingest:\n"
                        + "  log_root: " + posix(tmp.resolve("containers")) + "\n"
                        + "spool:\n"
                        + "  directory: " + posix(tmp.resolve("spool")) + "\n"
                        + "archive:\n"
                        + "  path: " + posix(archivePath) + "\n");
    }

    void fill() {
        try (ArchiveDb connection = Schema.openArchive(archivePath)) {
            Repository repo = new Repository(connection, TABLE);
            for (int index = 0; index < 6; index++) {
                add(repo, "pump-bridge", index, Map.of());
            }
            for (int index = 0; index < 2; index++) {
                add(repo, "price-sign", index, Map.of("lane", "2"));
            }
        }
    }

    static void add(Repository repo, String unit, int hoursAgo, Map<String, String> labels) {
        repo.add(
                new LogRecord(
                                "US-TX-0417",
                                unit,
                                Stream.STDOUT,
                                new NanoTime(NOW.epochNs() - hoursAgo * HOUR_NS),
                                unit + " at " + hoursAgo)
                        .replaceLabels(labels),
                NOW);
    }

    CliRunner.Result invoke(String... args) {
        String[] full = new String[args.length + 1];
        full[0] = "count";
        System.arraycopy(args, 0, full, 1, args.length);
        return CliFixtures.invoke(config, full);
    }

    @Test
    void aBarIsEmptyForNothing() {
        assertThat(CountCommand.bar(0, 10)).isEmpty();
    }

    @Test
    void aBarFillsTheColumnForTheBiggest() {
        assertThat(CountCommand.bar(10, 10)).hasSize(32);
    }

    @Test
    void aSmallGroupStillGetsAMark() {
        assertThat(CountCommand.bar(1, 10_000)).isEqualTo("#");
    }

    @Test
    void aBarIsInProportion() {
        assertThat(CountCommand.bar(5, 10)).hasSize(16);
    }

    @Test
    void aBarAgainstNothingIsEmpty() {
        assertThat(CountCommand.bar(3, 0)).isEmpty();
    }

    @Test
    void aBarNeverRunsPastItsWidth() {
        assertThat(CountCommand.bar(50, 10)).hasSize(32);
    }

    @Test
    void countingGroupsByUnitByDefault() {
        fill();
        CliRunner.Result result = invoke();
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("pump-bridge").contains("price-sign");
    }

    @Test
    void theCountsArePrinted() {
        fill();
        assertThat(invoke().output().lines().findFirst().orElseThrow().split("\\s+")[1])
                .isEqualTo("6");
    }

    @Test
    void theTotalIsPrinted() {
        fill();
        assertThat(invoke().output()).contains("8 records in 2 groups");
    }

    @Test
    void groupingByClass() {
        fill();
        assertThat(invoke("--by", "class").output()).contains("standard").contains("chatter");
    }

    @Test
    void responseCodesAreCountedWithWhatTheyMean() {
        try (ArchiveDb connection = Schema.openArchive(archivePath)) {
            Repository repo = new Repository(connection, TABLE);
            add(repo, "payment-kernel", 0, Map.of("response", "51", "outcome", "declined"));
            add(repo, "payment-kernel", 1, Map.of("response", "51", "outcome", "declined"));
            add(repo, "payment-kernel", 2, Map.of("response", "00", "outcome", "approved"));
            add(repo, "pump-bridge", 2, Map.of());
        }
        CliRunner.Result result = invoke("--by", "label.response", "unit = payment-kernel");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().lines().toList())
                .anyMatch(line -> line.startsWith("51 ") && line.endsWith("insufficient funds, declined"))
                .anyMatch(line -> line.startsWith("00 ") && line.endsWith("approved, approved"))
                .contains("3 records in 2 groups");
    }

    @Test
    void anOrdinaryLabelIsCountedPlain() {
        fill();
        CliRunner.Result result = invoke("--by", "label.lane");
        assertThat(result.stdout().lines().toList())
                .anyMatch(line -> line.startsWith("2 ") && line.endsWith(CountCommand.bar(2, 6)))
                .anyMatch(line -> line.startsWith("(none) ") && line.endsWith("#".repeat(CountCommand.BAR_WIDTH)));
    }

    @Test
    void anUnknownGroupIsRefused() {
        fill();
        CliRunner.Result result = invoke("--by", "message");
        assertThat(result.exitCode()).isNotEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("cannot group by");
    }

    @Test
    void aFilterNarrowsTheCount() {
        fill();
        CliRunner.Result result = invoke("unit = pump-bridge");
        assertThat(result.output()).doesNotContain("price-sign").contains("6 records in 1 groups");
    }

    @Test
    void aLabelFilterWorksToo() {
        fill();
        assertThat(invoke("label.lane = 2").output()).contains("2 records in 1 groups");
    }

    @Test
    void aFilterThatMatchesNothingSaysSo() {
        fill();
        assertThat(invoke("unit = absent").output()).contains("nothing matched");
    }

    @Test
    void aBadFilterIsRefused() {
        fill();
        assertThat(invoke("unit ==== 4").exitCode()).isNotEqualTo(Cli.EXIT_OK);
    }

    @Test
    void countingOverTime() {
        fill();
        CliRunner.Result result = invoke("--every", "1h");
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("2025-06-10T");
    }

    @Test
    void timeBucketsComeBackInTimeOrder() {
        fill();
        List<String> lines = invoke("--every", "1h")
                .output()
                .lines()
                .filter(line -> line.contains("T"))
                .toList();
        assertThat(lines).isSorted();
    }

    @Test
    void aWiderBucketGathersMore() {
        fill();
        String narrow = invoke("--every", "1h").output();
        String wide = invoke("--every", "24h").output();
        assertThat(wide).contains("in 1 groups");
        assertThat(narrow).doesNotContain("in 1 groups");
    }

    @Test
    void aBadBucketIsRefused() {
        fill();
        assertThat(invoke("--every", "a while").exitCode()).isNotEqualTo(Cli.EXIT_OK);
    }

    @Test
    void bothGroupingsAtOnceAreRefused() {
        fill();
        assertThat(invoke("--by", "unit", "--every", "1h").output()).contains("not both at once");
    }

    @Test
    void aSiteWithNoArchiveSaysSo() {
        Path target = CliFixtures.writeConfig(
                tmp,
                "site: US-TX-0417\n"
                        + "ingest:\n"
                        + "  log_root: " + posix(tmp.resolve("containers")) + "\n"
                        + "spool:\n"
                        + "  directory: " + posix(tmp.resolve("spool")) + "\n"
                        + "archive:\n"
                        + "  enabled: false\n");
        assertThat(CliFixtures.invoke(target, "count").output()).contains("keeps no archive");
    }

    @Test
    void aMissingConfigIsRefused() {
        assertThat(CliRunner.invoke("--config", tmp.resolve("nope.yaml").toString(), "count")
                        .exitCode())
                .isNotEqualTo(Cli.EXIT_OK);
    }

    @Test
    void theVerboseFlagSaysWhereItCounted() {
        fill();
        assertThat(CliRunner.invoke("--config", config.toString(), "--verbose", "count")
                        .output())
                .contains("counted in the archive");
    }

    @Test
    void aTallyWithNoGroupsRendersNothing() {
        assertThat(CountCommand.render(new Tally(), false)).isEmpty();
    }

    @Test
    void aGroupLineCarriesTheKeyAndTheCount() {
        String line = CountCommand.render(new Tally(List.of(new Group("pump-bridge", 4)), true), false)
                .get(0);
        assertThat(line).startsWith("pump-bridge").contains("4");
    }
}
