package io.github.rkaushikdev.trammelworks.report;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Four ways to write records out, for four different readers.
 *
 * <ul>
 *   <li><b>text</b> for a person at a terminal. Columns line up, long messages are cut, and the
 *       width is fixed rather than measured from the data, because a table whose columns move
 *       between runs cannot be read down.
 *   <li><b>jsonl</b> for another program. One object per line, so a partial file is still usable.
 *   <li><b>csv</b> for the spreadsheet a dispute team works in. A log message routinely contains
 *       commas, quotes and newlines, and quoting them wrong shifts every column after it.
 *   <li><b>markdown</b> for the ticket: the same table, pasted into a case.
 * </ul>
 *
 * <p>Every format writes both timestamps where it has room. A report that showed only the corrected
 * time would be asserting a conclusion; showing both lets the reader see how far the box was out.
 */
public final class Render {

    /** How wide the time column is in the text table. */
    public static final int TIME_WIDTH = 30;

    /** How wide the unit column is in the text table. */
    public static final int UNIT_WIDTH = 16;

    /** How wide the class column is in the text table. */
    public static final int CLASS_WIDTH = 9;

    /** How wide the stream column is in the text table. */
    public static final int STREAM_WIDTH = 6;

    /** The narrowest a label column is in the text table. */
    public static final int LABEL_WIDTH = 8;

    private Render() {}

    /**
     * One column of the tables: its header, its width in the text table, and how a record fills it.
     * The message is always the last column and is not in this list.
     *
     * @param name the header
     * @param width how wide the text table pads it
     * @param value what a record puts in it
     */
    record Column(String name, int width, BiFunction<StoredRecord, RenderOptions, String> value) {}

    /**
     * The columns before the message, in order. A label the site asked for gets a column of its
     * own after the fixed ones, empty where a record does not carry it; its width is fixed by its
     * name, never by the data, for the same reason the others are.
     */
    static List<Column> columns(RenderOptions options, boolean withConfidence) {
        List<Column> out = new ArrayList<>(List.of(
                new Column(
                        "time", TIME_WIDTH, (record, opts) -> when(record, opts).isoformat()),
                new Column("unit", UNIT_WIDTH, (record, opts) -> record.unit()),
                new Column("class", CLASS_WIDTH, (record, opts) -> record.klass()),
                new Column("stream", STREAM_WIDTH, (record, opts) -> record.stream()
                        .value())));
        if (withConfidence) {
            out.add(new Column("confidence", 0, (record, opts) -> record.confidence()));
        }
        for (String label : options.labels()) {
            out.add(new Column(label, Math.max(LABEL_WIDTH, label.length()), (record, opts) -> record.labels()
                    .getOrDefault(label, "")));
        }
        return out;
    }

    /** Render with the default options. */
    public static String render(List<StoredRecord> records) {
        return render(records, new RenderOptions());
    }

    /** Render in whichever format was asked for. */
    public static String render(List<StoredRecord> records, RenderOptions options) {
        return switch (options.format()) {
            case TEXT -> renderText(records, options);
            case JSONL -> renderJsonl(records);
            case CSV -> renderCsv(records, options);
            case MARKDOWN -> renderMarkdown(records, options);
        };
    }

    /** The terminal table. */
    public static String renderText(List<StoredRecord> records, RenderOptions options) {
        List<Column> columns = columns(options, false);
        List<String> lines = new ArrayList<>();
        if (options.header()) {
            lines.add(textRow(columns, columns.stream().map(Column::name).toList(), "message"));
        }
        for (StoredRecord record : records) {
            lines.add(textRow(columns, values(columns, record, options), cut(record.message(), options.width())));
        }
        return String.join("\n", lines);
    }

    private static List<String> values(List<Column> columns, StoredRecord record, RenderOptions options) {
        return columns.stream()
                .map(column -> column.value().apply(record, options))
                .toList();
    }

    private static String textRow(List<Column> columns, List<String> values, String message) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < columns.size(); index++) {
            out.append(pad(values.get(index), columns.get(index).width())).append(' ');
        }
        return out.append(message).toString();
    }

    /** One object per line, keys sorted, both times. */
    public static String renderJsonl(List<StoredRecord> records) {
        List<String> lines = new ArrayList<>();
        for (StoredRecord record : records) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("observed_at", record.observedAt().isoformat());
            payload.put("site", record.site());
            payload.put("unit", record.unit());
            payload.put("class", record.klass());
            payload.put("stream", record.stream().value());
            payload.put("confidence", record.confidence());
            payload.put("message", record.message());
            payload.put("labels", new LinkedHashMap<>(record.labels()));
            record.correctedAt().ifPresent(corrected -> payload.put("corrected_at", corrected.isoformat()));
            lines.add(Json.dumps(payload, Json.Style.compact()));
        }
        return String.join("\n", lines);
    }

    /**
     * The spreadsheet form: comma separated, a field quoted only when it holds a comma, a quote, a
     * carriage return or a newline, quotes inside doubled.
     */
    public static String renderCsv(List<StoredRecord> records, RenderOptions options) {
        List<Column> columns = columns(options, true);
        StringBuilder out = new StringBuilder();
        if (options.header()) {
            List<String> header =
                    new ArrayList<>(columns.stream().map(Column::name).toList());
            header.add("message");
            csvRow(out, header);
        }
        for (StoredRecord record : records) {
            List<String> row = new ArrayList<>(values(columns, record, options));
            row.add(record.message());
            csvRow(out, row);
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '\n') {
            end--;
        }
        return out.substring(0, end);
    }

    private static void csvRow(StringBuilder out, List<String> fields) {
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            String field = fields.get(index);
            boolean quote = field.chars().anyMatch(c -> c == ',' || c == '"' || c == '\n' || c == '\r');
            if (fields.size() == 1 && field.isEmpty()) {
                quote = true;
            }
            out.append(quote ? "\"" + field.replace("\"", "\"\"") + "\"" : field);
        }
        out.append('\n');
    }

    /** The table for a ticket. */
    public static String renderMarkdown(List<StoredRecord> records, RenderOptions options) {
        List<Column> columns = columns(options, false);
        List<String> lines = new ArrayList<>();
        lines.add(markdownRow(columns.stream().map(Column::name).toList(), "message"));
        lines.add(markdownRow(columns.stream().map(column -> "---").toList(), "---"));
        for (StoredRecord record : records) {
            List<String> cells = values(columns, record, options).stream()
                    .map(Render::markdownCell)
                    .toList();
            lines.add(markdownRow(cells, markdownCell(cut(record.message(), options.width()))));
        }
        return String.join("\n", lines);
    }

    private static String markdownRow(List<String> cells, String message) {
        StringBuilder out = new StringBuilder("|");
        cells.forEach(cell -> out.append(' ').append(cell).append(" |"));
        return out.append(' ').append(message).append(" |").toString();
    }

    private static String markdownCell(String text) {
        return text.replace("|", "\\|");
    }

    private static NanoTime when(StoredRecord record, RenderOptions options) {
        return options.rawTime() ? record.observedAt() : record.bestAt();
    }

    /** One line, at most {@code width} characters, newlines made visible. */
    static String cut(String text, int width) {
        String flat = text.replace("\n", "\\n").replace("\r", "");
        if (flat.codePointCount(0, flat.length()) <= width) {
            return flat;
        }
        return flat.substring(0, flat.offsetByCodePoints(0, width - 1)) + "\u2026";
    }

    private static String pad(String text, int width) {
        int length = text.codePointCount(0, text.length());
        return length >= width ? text : text + " ".repeat(width - length);
    }
}
