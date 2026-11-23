package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.LogRecord;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stamping what a line is about onto it as labels.
 *
 * <p>It runs on the redacted record, after the card data is out, so nothing it reads can carry a
 * card number into a label. It only adds: a label the record already carries is the container's
 * own statement and is never overwritten, and a record with nothing to add comes back as the same
 * object.
 */
public final class Tagger {

    private final ForecourtConfig config;
    private long tagged;

    /** A tagger reading roles from {@code config}. */
    public Tagger(ForecourtConfig config) {
        this.config = config;
    }

    /** The roles it reads by. */
    public ForecourtConfig config() {
        return config;
    }

    /** Records that came out with at least one new label. */
    public long tagged() {
        return tagged;
    }

    /**
     * The labels this record should gain, before anything already on it is considered. A pump state
     * is only read off a line that also names its fuelling point: "AUTHORISED" on its own could be
     * about anything.
     */
    public Map<String, String> labelsFor(LogRecord record) {
        Map<String, String> found = new LinkedHashMap<>();
        String message = record.message();
        switch (config.roleOf(record.unit())) {
            case DISPENSER ->
                FuellingPoint.find(message).ifPresent(point -> {
                    found.put(FuellingPoint.LABEL, point);
                    PumpState.find(message).ifPresent(state -> found.put(PumpState.LABEL, state.value()));
                });
            case PAYMENT -> FuellingPoint.find(message).ifPresent(point -> found.put(FuellingPoint.LABEL, point));
            default -> {}
        }
        return found;
    }

    /** The record with whatever it gained. */
    public LogRecord tag(LogRecord record) {
        Map<String, String> fresh = new LinkedHashMap<>(labelsFor(record));
        fresh.keySet().removeAll(record.labels().keySet());
        if (fresh.isEmpty()) {
            return record;
        }
        tagged++;
        return record.withLabels(fresh);
    }
}
