package io.github.rkaushikdev.trammelworks.cli;

import static io.github.rkaushikdev.trammelworks.cli.CliFixtures.posix;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.forecourt.ForecourtConfig;
import io.github.rkaushikdev.trammelworks.forecourt.Tagger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliSalesTest {

    static final NanoTime NOW = NanoTime.parse("2025-03-04T12:00:00Z");

    @TempDir
    Path tmp;

    Path archiveFile;
    Path config;

    @BeforeEach
    void setUp() {
        archiveFile = tmp.resolve("archive").resolve("records.sqlite3");
        config = CliFixtures.writeConfig(
                tmp,
                "site: US-TX-0417\nspool:\n  directory: " + posix(tmp.resolve("spool")) + "\narchive:\n  path: "
                        + posix(archiveFile) + "\n");
    }

    void fill(List<List<String>> lines) {
        Tagger tagger = new Tagger(new ForecourtConfig());
        try (ArchiveDb connection = Schema.openArchive(archiveFile)) {
            Repository repo = new Repository(connection);
            for (List<String> line : lines) {
                repo.add(
                        tagger.tag(new LogRecord(
                                "US-TX-0417",
                                line.get(0),
                                Stream.STDOUT,
                                NanoTime.parse("2025-03-04T" + line.get(1) + "Z"),
                                line.get(2))),
                        NOW);
            }
        }
    }

    @Test
    void aCleanShiftHasNothingToAnswerFor() {
        fill(List.of(
                List.of("payment-kernel", "10:00:00", "preauth pump=2 amount=80.00 ref 300001"),
                List.of("pump-bridge", "10:00:05", "FP02 AUTHORISED"),
                List.of("pump-bridge", "10:00:20", "FP02 FUELLING"),
                List.of("pump-bridge", "10:03:00", "FP02 COMPLETED 41.2 litres"),
                List.of("payment-kernel", "10:03:02", "completion pump=2 amount=61.30 ref 300001")));
        CliRunner.Result result = CliFixtures.invoke(config, "sales");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("nothing to answer for\n");
    }

    @Test
    void anUnauthorisedDeliveryAndAnOverrunAreBothListed() {
        fill(List.of(
                List.of("pump-bridge", "09:00:00", "FP04 CALLING"),
                List.of("pump-bridge", "09:00:09", "FP04 FUELLING"),
                List.of("payment-kernel", "09:10:00", "preauth pump=3 amount=100.00 ref 551203"),
                List.of("payment-kernel", "09:14:00", "completion pump=3 amount=102.35 ref 551203"),
                List.of("payment-kernel", "09:20:00", "auth response pump=3 rc=00 ref 551203")));
        CliRunner.Result result = CliFixtures.invoke(config, "sales");
        assertThat(result.exitCode()).isEqualTo(SalesCommand.EXIT_FOUND);
        assertThat(result.stdout().lines().toList())
                .containsExactly(
                        "unauthorised pump 4 at 2025-03-04T09:00:09.000000000Z, calling to fuelling",
                        "overrun ref 551203 pump 3 at 2025-03-04T09:14:00.000000000Z, held 100.00, completed 102.35,"
                                + " over by 2.35");
    }

    @Test
    void aCompletionWithNoHoldSaysSo() {
        fill(List.of(List.of("payment-kernel", "09:14:00", "completion amount=40 ref 771100")));
        CliRunner.Result result = CliFixtures.invoke(config, "sales");
        assertThat(result.stdout())
                .contains("overrun ref 771100 at")
                .contains("no preauth seen, completed 40, over by 40");
    }
}
