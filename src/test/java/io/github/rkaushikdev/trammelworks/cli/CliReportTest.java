package io.github.rkaushikdev.trammelworks.cli;

import static io.github.rkaushikdev.trammelworks.cli.CliFixtures.posix;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliReportTest {

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
                "site: US-TX-0417\n"
                        + "spool:\n"
                        + "  directory: " + posix(tmp.resolve("spool")) + "\n"
                        + "  budget: 1MiB\n"
                        + "  segment: 16KiB\n"
                        + "archive:\n"
                        + "  path: " + posix(archiveFile) + "\n");
    }

    void fill() {
        try (ArchiveDb connection = Schema.openArchive(archiveFile)) {
            Repository repo = new Repository(connection);
            for (List<String> line : List.of(
                    List.of("payment-kernel", "11:00:00", "auth approved"),
                    List.of("payment-kernel", "11:15:00", "auth declined, lane 4"),
                    List.of("pump-bridge", "11:30:00", "nozzle 1 lifted"))) {
                repo.add(
                        new LogRecord(
                                "US-TX-0417",
                                line.get(0),
                                Stream.STDOUT,
                                NanoTime.parse("2025-03-04T" + line.get(1) + "Z"),
                                line.get(2)),
                        NOW);
            }
        }
    }

    @Test
    void aDisputeReportCarriesThePumpAndTheResponseCode() {
        try (ArchiveDb connection = Schema.openArchive(archiveFile)) {
            new Repository(connection)
                    .add(
                            new LogRecord(
                                            "US-TX-0417",
                                            "payment-kernel",
                                            Stream.STDOUT,
                                            NanoTime.parse("2025-03-04T11:00:00Z"),
                                            "auth response pump=4 rc=51")
                                    .withLabels(Map.of("pump", "4", "response", "51")),
                            NOW);
        }
        CliRunner.Result result = invoke("report", "--format", "csv", "--labels", "pump,response");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().lines().toList())
                .containsExactly(
                        "time,unit,class,stream,confidence,pump,response,message",
                        "2025-03-04T11:00:00.000000000Z,payment-kernel,standard,stdout,unknown,4,51,auth response pump=4 rc=51");
    }

    CliRunner.Result invoke(String... args) {
        return CliFixtures.invoke(config, args);
    }

    static Path noArchive(Path tmp) {
        return CliFixtures.writeConfig(tmp, "site: US-TX-0417\narchive:\n  enabled: false\n");
    }

    /** Split csv text into rows of fields, honouring quotes. */
    static List<List<String>> readCsv(String text) {
        List<List<String>> rows = new java.util.ArrayList<>();
        List<String> row = new java.util.ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted && character == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                field.append('"');
                index++;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (!quoted && character == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (!quoted && character == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new java.util.ArrayList<>();
            } else {
                field.append(character);
            }
        }
        row.add(field.toString());
        rows.add(row);
        return rows;
    }

    @Test
    void aReportPrintsATable() {
        fill();
        CliRunner.Result result = invoke("report");
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("auth approved");
        assertThat(result.output().lines().findFirst().orElseThrow()).startsWith("time");
    }

    @Test
    void aReportCanBeFiltered() {
        fill();
        assertThat(invoke("report", "unit = pump-bridge").output())
                .contains("nozzle 1 lifted")
                .doesNotContain("auth approved");
    }

    @Test
    void aReportOfNothingExitsFour() {
        fill();
        assertThat(invoke("report", "unit = absent").exitCode()).isEqualTo(ReportCommands.EXIT_EMPTY);
    }

    @Test
    void theCsvFormatIsReadableByACsvReader() {
        fill();
        List<List<String>> rows =
                readCsv(invoke("report", "--format", "csv").output().strip());
        assertThat(rows.get(0).get(0)).isEqualTo("time");
        assertThat(rows.get(2).get(5)).isEqualTo("auth declined, lane 4");
    }

    @Test
    void theJsonlFormatIsOneObjectPerLine() {
        fill();
        List<String> lines =
                invoke("report", "--format", "jsonl").output().strip().lines().toList();
        assertThat(lines).hasSize(3);
        assertThat(Json.loadsObject(lines.get(0))).containsEntry("unit", "payment-kernel");
    }

    @Test
    void theMarkdownFormatHasASeparator() {
        fill();
        assertThat(invoke("report", "--format", "markdown")
                        .output()
                        .lines()
                        .toList()
                        .get(1))
                .startsWith("| ---");
    }

    @Test
    void anUnknownFormatIsRefused() {
        fill();
        CliRunner.Result result = invoke("report", "--format", "pdf");
        assertThat(result.exitCode()).isNotIn(Cli.EXIT_OK, ReportCommands.EXIT_EMPTY);
        assertThat(result.output()).contains("unknown report format");
    }

    @Test
    void theHeaderCanBeDropped() {
        fill();
        assertThat(invoke("report", "--no-header").output().lines().findFirst().orElseThrow())
                .doesNotStartWith("time");
    }

    @Test
    void aReportCanBeWrittenToAFile() throws IOException {
        fill();
        Path target = tmp.resolve("case-41.csv");
        CliRunner.Result result = invoke("report", "--format", "csv", "--out", target.toString());
        assertThat(target).exists();
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).contains("auth approved");
        assertThat(result.output()).contains("wrote 3 records");
    }

    @Test
    void aWrittenReportEndsWithANewline() throws IOException {
        fill();
        Path target = tmp.resolve("case-41.txt");
        invoke("report", "--out", target.toString());
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).endsWith("\n");
    }

    @Test
    void theMessageWidthIsHonoured() {
        fill();
        assertThat(invoke("report", "--width", "8", "unit = pump-bridge").output())
                .doesNotContain("nozzle 1 lifted")
                .contains("…");
    }

    @Test
    void aSiteWithNoArchiveSaysSo() {
        assertThat(CliFixtures.invoke(noArchive(tmp), "report").output()).contains("keeps no archive");
    }

    @Test
    void aSummaryNamesTheSite() {
        fill();
        CliRunner.Result result = invoke("summary");
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).startsWith("US-TX-0417");
    }

    @Test
    void aSummaryCountsTheArchive() {
        fill();
        assertThat(invoke("summary").output()).contains("3 records kept");
    }

    @Test
    void aSummaryReportsTheSpool() {
        fill();
        assertThat(invoke("summary").output()).contains("spool   0B of 1MiB");
    }

    @Test
    void aSummaryFlagsASiteFollowingNothing() {
        fill();
        assertThat(invoke("summary").output()).contains("ATTENTION").contains("no container log files");
    }

    @Test
    void theJsonSummaryParses() {
        fill();
        Map<String, Object> payload =
                Json.loadsObject(invoke("summary", "--json").output());
        assertThat(payload).containsEntry("site", "US-TX-0417").containsEntry("archived", 3L);
    }

    @Test
    void aSummaryWithoutAnArchiveStillRuns() {
        CliRunner.Result result = CliFixtures.invoke(noArchive(tmp), "summary");
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("0 records kept");
    }

    @Test
    void aMissingConfigFails() {
        assertThat(CliRunner.invoke("--config", tmp.resolve("nope.yaml").toString(), "summary")
                        .output())
                .contains("stanchion.config");
    }
}
