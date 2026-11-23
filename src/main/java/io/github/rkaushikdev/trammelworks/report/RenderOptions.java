package io.github.rkaushikdev.trammelworks.report;

import io.github.rkaushikdev.trammelworks.StanchionError;
import java.util.HashSet;
import java.util.List;

/**
 * What the caller wants the output to look like.
 *
 * @param format the format
 * @param width the widest a message may be in the text and markdown tables
 * @param header whether the text and csv forms start with a header
 * @param rawTime whether to show the time the box claimed rather than the corrected one
 * @param labels labels to show in columns of their own, such as the pump and the response code a
 *     dispute team reads a sale by
 */
public record RenderOptions(ReportFormat format, int width, boolean header, boolean rawTime, List<String> labels) {

    /** How wide a message may be by default. */
    public static final int DEFAULT_MESSAGE_WIDTH = 80;

    /** Refuses a width below one, or a label column named twice or not at all. */
    public RenderOptions {
        if (width < 1) {
            throw new StanchionError("message width must be at least one");
        }
        labels = List.copyOf(labels);
        if (labels.stream().anyMatch(String::isEmpty)) {
            throw new StanchionError("a label column needs a name");
        }
        if (new HashSet<>(labels).size() != labels.size()) {
            throw new StanchionError("a label column is named twice");
        }
    }

    /** No label columns. */
    public RenderOptions(ReportFormat format, int width, boolean header, boolean rawTime) {
        this(format, width, header, rawTime, List.of());
    }

    /** Text, eighty wide, with a header, showing corrected times. */
    public RenderOptions() {
        this(ReportFormat.TEXT, DEFAULT_MESSAGE_WIDTH, true, false);
    }

    /** The defaults in another format. */
    public static RenderOptions of(ReportFormat format) {
        return new RenderOptions().withFormat(format);
    }

    /** The same in another format. */
    public RenderOptions withFormat(ReportFormat value) {
        return new RenderOptions(value, width, header, rawTime, labels);
    }

    /** The same with another message width. */
    public RenderOptions withWidth(int value) {
        return new RenderOptions(format, value, header, rawTime, labels);
    }

    /** The same with or without a header. */
    public RenderOptions withHeader(boolean value) {
        return new RenderOptions(format, width, value, rawTime, labels);
    }

    /** The same with these label columns. */
    public RenderOptions withLabels(List<String> value) {
        return new RenderOptions(format, width, header, rawTime, value);
    }

    /** The same showing raw or corrected times. */
    public RenderOptions withRawTime(boolean value) {
        return new RenderOptions(format, width, header, value, labels);
    }
}
