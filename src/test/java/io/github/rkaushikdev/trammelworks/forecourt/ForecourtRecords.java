package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.util.Map;
import java.util.Optional;

/**
 * Archived records for the forecourt tests, built the two ways those tests need: with the labels a
 * test wants to state outright, or from a container line put through the real tagger.
 */
public final class ForecourtRecords {

    /** The site every record belongs to. */
    public static final String SITE = "US-TX-0417";

    private ForecourtRecords() {}

    /** A record from {@code unit} at {@code at} carrying exactly {@code labels}. */
    public static StoredRecord labelled(String unit, NanoTime at, Map<String, String> labels) {
        return new StoredRecord(
                unit + "@" + at.epochNs() + labels,
                SITE,
                unit,
                "standard",
                Stream.STDOUT,
                at,
                Optional.empty(),
                "unknown",
                "line",
                labels,
                at);
    }

    /** A record from {@code unit} at {@code at} saying {@code message}, labelled by the tagger. */
    public static StoredRecord tagged(String unit, NanoTime at, String message) {
        LogRecord read = new Tagger(new ForecourtConfig()).tag(new LogRecord(SITE, unit, Stream.STDOUT, at, message));
        return new StoredRecord(
                unit + "@" + at.epochNs(),
                SITE,
                unit,
                "standard",
                Stream.STDOUT,
                at,
                Optional.empty(),
                "unknown",
                message,
                read.labels(),
                at);
    }
}
