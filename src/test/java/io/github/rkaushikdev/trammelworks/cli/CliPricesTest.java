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

class CliPricesTest {

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
    void aPumpThatMissedAChangeIsListed() {
        fill(List.of(
                List.of("pump-bridge", "06:00:00", "FP01 price unleaded 1.459"),
                List.of("pump-bridge", "06:00:00", "FP02 price unleaded 1.459"),
                List.of("price-sign", "06:00:01", "price unleaded 1459"),
                List.of("price-sign", "09:00:00", "price unleaded 1479"),
                List.of("pump-bridge", "09:00:20", "FP01 price unleaded 1.479"),
                List.of("pump-bridge", "09:45:00", "FP02 price unleaded 1.479")));
        CliRunner.Result result = CliFixtures.invoke(config, "prices");
        assertThat(result.exitCode()).isEqualTo(PricesCommand.EXIT_MISMATCH);
        assertThat(result.stdout().lines().toList())
                .containsExactly("pump 2 unleaded sign 1.479 pump 1.459 from 2025-03-04T09:00:00.000000000Z"
                        + " until 2025-03-04T09:45:00.000000000Z (45m)");
    }

    @Test
    void aSiteWhosePumpsKeptUpSaysSo() {
        fill(List.of(
                List.of("price-sign", "06:00:00", "price diesel 1529"),
                List.of("pump-bridge", "06:00:30", "FP01 price diesel 1.529")));
        CliRunner.Result result = CliFixtures.invoke(config, "prices");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("every pump charged what the sign showed\n");
    }
}
