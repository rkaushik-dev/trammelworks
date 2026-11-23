package io.github.rkaushikdev.trammelworks.report;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.StanchionError;
import io.github.rkaushikdev.trammelworks.Text;
import java.util.Locale;

/** How a report is written. */
public enum ReportFormat implements Json.Valued {
    TEXT("text"),
    JSONL("jsonl"),
    CSV("csv"),
    MARKDOWN("markdown");

    private final String value;

    ReportFormat(String value) {
        this.value = value;
    }

    /** The name a command line gives the format. */
    @Override
    public String value() {
        return value;
    }

    /** The format named {@code raw}, ignoring case and surrounding space. */
    public static ReportFormat parse(String raw) {
        return Json.Valued.lookup(ReportFormat.class, Text.strip(raw).toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new StanchionError("unknown report format " + Repr.of(raw)));
    }

    @Override
    public String toString() {
        return value;
    }
}
