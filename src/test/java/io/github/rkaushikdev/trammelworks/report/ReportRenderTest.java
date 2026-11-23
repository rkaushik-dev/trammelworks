package io.github.rkaushikdev.trammelworks.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.StanchionError;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReportRenderTest {

    static final NanoTime NOW = NanoTime.parse("2025-03-04T12:00:00Z");

    static NanoTime at(String raw) {
        return NanoTime.parse(raw);
    }

    static StoredRecord record() {
        return record("auth approved");
    }

    static StoredRecord record(String message) {
        return record(message, Optional.empty());
    }

    static StoredRecord record(String message, Optional<NanoTime> corrected) {
        return new StoredRecord(
                "k",
                "US-TX-0417",
                "payment-kernel",
                "payment",
                Stream.STDOUT,
                at("2025-03-04T11:00:00Z"),
                corrected,
                "measured",
                message,
                Map.of("lane", "3"),
                NOW);
    }

    static RenderOptions options(ReportFormat format) {
        return RenderOptions.of(format);
    }

    static List<String> lines(String text) {
        return text.lines().toList();
    }

    /** A reader for the csv dialect the renderer writes, so a round trip is checked by parsing. */
    static List<List<String>> readCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else if (character == '"') {
                    quoted = false;
                } else {
                    field.append(character);
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (character == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(character);
            }
        }
        row.add(field.toString());
        rows.add(row);
        return rows;
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "jsonl", "csv", "markdown"})
    void everyFormatNameParses(String name) {
        assertThat(ReportFormat.parse(name).value()).isEqualTo(name);
    }

    @Test
    void aFormatNameIsCaseInsensitive() {
        assertThat(ReportFormat.parse(" CSV ")).isEqualTo(ReportFormat.CSV);
    }

    @Test
    void anUnknownFormatIsRefused() {
        assertThatThrownBy(() -> ReportFormat.parse("pdf"))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("unknown report format");
    }

    @Test
    void aWidthBelowOneIsRefused() {
        assertThatThrownBy(() -> new RenderOptions().withWidth(0))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("at least one");
    }

    static final RenderOptions WITH_LANE = new RenderOptions().withLabels(List.of("lane", "response_code"));

    @Test
    void aLabelColumnSitsBeforeTheMessageInTheText() {
        assertThat(lines(Render.render(List.of(record()), WITH_LANE)))
                .containsExactly(
                        "time                           unit             class     stream lane     response_code message",
                        "2025-03-04T11:00:00.000000000Z payment-kernel   payment   stdout 3                      auth approved");
    }

    @Test
    void aLabelColumnIsAddedAfterTheConfidenceInTheCsv() {
        List<List<String>> rows = readCsv(Render.render(List.of(record()), WITH_LANE.withFormat(ReportFormat.CSV)));
        assertThat(rows.get(0))
                .containsExactly("time", "unit", "class", "stream", "confidence", "lane", "response_code", "message");
        assertThat(rows.get(1))
                .containsExactly(
                        "2025-03-04T11:00:00.000000000Z",
                        "payment-kernel",
                        "payment",
                        "stdout",
                        "measured",
                        "3",
                        "",
                        "auth approved");
    }

    @Test
    void aLabelColumnIsEscapedInTheMarkdown() {
        StoredRecord piped = new StoredRecord(
                "k",
                "US-TX-0417",
                "pump-bridge",
                "standard",
                Stream.STDOUT,
                at("2025-03-04T11:00:00Z"),
                Optional.empty(),
                "measured",
                "FP03 IDLE",
                Map.of("lane", "a|b"),
                NOW);
        assertThat(lines(Render.render(List.of(piped), WITH_LANE.withFormat(ReportFormat.MARKDOWN))))
                .containsExactly(
                        "| time | unit | class | stream | lane | response_code | message |",
                        "| --- | --- | --- | --- | --- | --- | --- |",
                        "| 2025-03-04T11:00:00.000000000Z | pump-bridge | standard | stdout | a\\|b |  | FP03 IDLE |");
    }

    @Test
    void aLabelColumnSurvivesAChangeOfFormat() {
        assertThat(WITH_LANE
                        .withFormat(ReportFormat.CSV)
                        .withWidth(10)
                        .withHeader(false)
                        .withRawTime(true)
                        .labels())
                .containsExactly("lane", "response_code");
    }

    @Test
    void aLabelColumnNamedTwiceIsRefused() {
        assertThatThrownBy(() -> new RenderOptions().withLabels(List.of("pump", "pump")))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("named twice");
        assertThatThrownBy(() -> new RenderOptions().withLabels(List.of("")))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("needs a name");
    }

    @Test
    void theTextFormatHasAHeader() {
        assertThat(lines(Render.render(List.of(record()))).get(0)).startsWith("time");
    }

    @Test
    void theHeaderCanBeTurnedOff() {
        String out = Render.render(List.of(record()), new RenderOptions().withHeader(false));
        assertThat(lines(out).get(0)).doesNotContain("time");
    }

    @Test
    void theTextFormatShowsTheUnitAndMessage() {
        String out = Render.render(List.of(record()), new RenderOptions().withHeader(false));
        assertThat(out).contains("payment-kernel").contains("auth approved");
    }

    @Test
    void theTextFormatShowsTheCorrectedTimeByDefault() {
        String out = Render.render(
                List.of(record("x", Optional.of(at("2025-03-04T11:00:07Z")))), new RenderOptions().withHeader(false));
        assertThat(out).contains("11:00:07");
    }

    @Test
    void theRawTimeCanBeAskedFor() {
        String out = Render.render(
                List.of(record("x", Optional.of(at("2025-03-04T11:00:07Z")))),
                new RenderOptions().withHeader(false).withRawTime(true));
        assertThat(out).contains("11:00:00");
    }

    @Test
    void aLongMessageIsCutWithAnEllipsis() {
        String out = Render.render(
                List.of(record("z".repeat(200))),
                new RenderOptions().withHeader(false).withWidth(20));
        assertThat(out).contains("…").doesNotContain("z".repeat(100));
    }

    @Test
    void aNewlineInAMessageDoesNotBreakTheTable() {
        String out = Render.render(List.of(record("one\ntwo")), new RenderOptions().withHeader(false));
        assertThat(lines(out)).hasSize(1);
        assertThat(out).contains("one\\ntwo");
    }

    @Test
    void anEmptyListRendersToJustTheHeader() {
        List<String> found = lines(Render.render(List.of()));
        assertThat(found)
                .isEqualTo(found.stream().filter(line -> !line.isEmpty()).toList());
        assertThat(found).hasSize(1);
    }

    @Test
    void theJsonlFormatIsOneObjectPerLine() {
        String out = Render.render(List.of(record(), record("second")), options(ReportFormat.JSONL));
        List<String> found = lines(out);
        assertThat(found).hasSize(2);
        assertThat(Json.loadsObject(found.get(1)).get("message")).isEqualTo("second");
    }

    @Test
    void theJsonlFormatCarriesTheLabels() {
        String out = Render.render(List.of(record()), options(ReportFormat.JSONL));
        assertThat(Json.loadsObject(out).get("labels")).isEqualTo(Map.of("lane", "3"));
    }

    @Test
    void theJsonlFormatOmitsAnAbsentCorrection() {
        assertThat(Json.loadsObject(Render.render(List.of(record()), options(ReportFormat.JSONL))))
                .doesNotContainKey("corrected_at");
    }

    @Test
    void theJsonlFormatIncludesACorrectionWhenThereIsOne() {
        String out = Render.render(
                List.of(record("x", Optional.of(at("2025-03-04T11:00:07Z")))), options(ReportFormat.JSONL));
        assertThat((String) Json.loadsObject(out).get("corrected_at")).startsWith("2025-03-04T11:00:07");
    }

    @Test
    void theJsonlFormatKeepsANewlineInsideTheString() {
        String out = Render.render(List.of(record("one\ntwo")), options(ReportFormat.JSONL));
        assertThat(lines(out)).hasSize(1);
        assertThat(Json.loadsObject(out).get("message")).isEqualTo("one\ntwo");
    }

    @Test
    void theCsvFormatHasAHeaderRow() {
        String out = Render.render(List.of(record()), options(ReportFormat.CSV));
        assertThat(lines(out).get(0)).isEqualTo("time,unit,class,stream,confidence,message");
    }

    @Test
    void theCsvFormatQuotesACommaInAMessage() {
        String out =
                Render.render(List.of(record("a, b")), options(ReportFormat.CSV).withHeader(false));
        assertThat(out).isEqualTo("2025-03-04T11:00:00.000000000Z,payment-kernel,payment,stdout,measured,\"a, b\"");
    }

    @Test
    void theCsvFormatRoundTripsThroughAReader() {
        String out = Render.render(List.of(record("he said \"no\", loudly")), options(ReportFormat.CSV));
        assertThat(readCsv(out).get(1).get(5)).isEqualTo("he said \"no\", loudly");
    }

    @Test
    void aNewlineInAMessageSurvivesTheCsvRoundTrip() {
        String out = Render.render(List.of(record("one\ntwo")), options(ReportFormat.CSV));
        assertThat(readCsv(out).get(1).get(5)).isEqualTo("one\ntwo");
    }

    @Test
    void theMarkdownFormatHasASeparatorRow() {
        String out = Render.render(List.of(record()), options(ReportFormat.MARKDOWN));
        assertThat(lines(out).get(1)).startsWith("| ---");
    }

    @Test
    void theMarkdownFormatEscapesAPipe() {
        assertThat(Render.render(List.of(record("a | b")), options(ReportFormat.MARKDOWN)))
                .contains("a \\| b");
    }

    @Test
    void theMarkdownFormatHasARowPerRecord() {
        String out = Render.render(List.of(record(), record("second")), options(ReportFormat.MARKDOWN));
        assertThat(lines(out)).hasSize(4);
    }

    @Test
    void everyFormatRendersAnEmptyListWithoutFailing() {
        for (ReportFormat member : ReportFormat.values()) {
            assertThat(Render.render(List.of(), options(member))).isNotNull();
        }
    }

    @Test
    void everyFormatMentionsTheMessage() {
        for (ReportFormat member : ReportFormat.values()) {
            assertThat(Render.render(List.of(record("findme")), options(member)))
                    .contains("findme");
        }
    }

    @Test
    void everyFormatWritesTheExactBytesSupportScriptsRead() {
        List<StoredRecord> records = List.of(
                new StoredRecord(
                        "k",
                        "US-TX-0417",
                        "payment-kernel",
                        "payment",
                        Stream.STDOUT,
                        at("2025-03-04T11:00:00Z"),
                        Optional.of(at("2025-03-04T11:00:07.5Z")),
                        "measured",
                        "he said \"no\", | loudly\nthen région",
                        Map.of("lane", "3", "b", "x"),
                        NOW),
                new StoredRecord(
                        "k2",
                        "US-TX-0417",
                        "pump-bridge",
                        "standard",
                        Stream.STDERR,
                        at("2025-03-04T11:00:01Z"),
                        Optional.empty(),
                        "unknown",
                        "z".repeat(30),
                        Map.of(),
                        NOW));
        RenderOptions narrow = new RenderOptions().withWidth(20);
        assertThat(Render.render(records, narrow))
                .isEqualTo("time                           unit             class     stream message\n"
                        + "2025-03-04T11:00:07.500000000Z payment-kernel   payment   stdout he said \"no\", | lou…\n"
                        + "2025-03-04T11:00:01.000000000Z pump-bridge      standard  stderr zzzzzzzzzzzzzzzzzzz…");
        assertThat(Render.render(records, narrow.withFormat(ReportFormat.JSONL)))
                .isEqualTo("{\"class\":\"payment\",\"confidence\":\"measured\","
                        + "\"corrected_at\":\"2025-03-04T11:00:07.500000000Z\",\"labels\":{\"b\":\"x\",\"lane\":\"3\"},"
                        + "\"message\":\"he said \\\"no\\\", | loudly\\nthen r\\u00e9gion\","
                        + "\"observed_at\":\"2025-03-04T11:00:00.000000000Z\",\"site\":\"US-TX-0417\","
                        + "\"stream\":\"stdout\",\"unit\":\"payment-kernel\"}\n"
                        + "{\"class\":\"standard\",\"confidence\":\"unknown\",\"labels\":{},"
                        + "\"message\":\"zzzzzzzzzzzzzzzzzzzzzzzzzzzzzz\",\"observed_at\":\"2025-03-04T11:00:01.000000000Z\","
                        + "\"site\":\"US-TX-0417\",\"stream\":\"stderr\",\"unit\":\"pump-bridge\"}");
        assertThat(Render.render(records, narrow.withFormat(ReportFormat.CSV)))
                .isEqualTo(
                        "time,unit,class,stream,confidence,message\n"
                                + "2025-03-04T11:00:07.500000000Z,payment-kernel,payment,stdout,measured,"
                                + "\"he said \"\"no\"\", | loudly\nthen région\"\n"
                                + "2025-03-04T11:00:01.000000000Z,pump-bridge,standard,stderr,unknown,zzzzzzzzzzzzzzzzzzzzzzzzzzzzzz");
        assertThat(Render.render(records, narrow.withFormat(ReportFormat.MARKDOWN)))
                .isEqualTo(
                        "| time | unit | class | stream | message |\n"
                                + "| --- | --- | --- | --- | --- |\n"
                                + "| 2025-03-04T11:00:07.500000000Z | payment-kernel | payment | stdout | he said \"no\", \\| lou… |\n"
                                + "| 2025-03-04T11:00:01.000000000Z | pump-bridge | standard | stderr | zzzzzzzzzzzzzzzzzzz… |");
    }
}
