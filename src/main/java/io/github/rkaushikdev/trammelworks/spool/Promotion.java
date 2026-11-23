package io.github.rkaushikdev.trammelworks.spool;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.LogRecord;

/**
 * A record lifted into a class by what it says rather than which unit said it.
 *
 * <p>Unit assignment is right for nearly everything: the payment kernel is payment, the price sign
 * is chatter. It is wrong for the one tank gauge line in fifty thousand that reports a leak. The
 * gauge is the loudest and least important unit on the box, and its alarm would be the first thing
 * evicted when the spool fills, during exactly the outage that makes it matter. A promotion moves a
 * record carrying a given label into a given class, wherever its unit sits.
 *
 * @param label the label to look at
 * @param value the value that promotes
 * @param klass the class it is promoted into
 */
public record Promotion(String label, String value, String klass) {

    /** Refuses an empty label or value. */
    public Promotion {
        if (label == null || label.isEmpty() || value == null || value.isEmpty()) {
            throw new ConfigError("a promotion needs a label and a value", label + "=" + value);
        }
        Segments.checkClass(klass);
    }

    /** A promotion written {@code label=value}. */
    public static Promotion parse(String written, String klass) {
        int equals = written.indexOf('=');
        if (equals <= 0 || equals == written.length() - 1) {
            throw new ConfigError("a promotion is written label=value", written);
        }
        return new Promotion(
                written.substring(0, equals).strip(),
                written.substring(equals + 1).strip(),
                klass);
    }

    /** Whether this record carries the label with the value. */
    public boolean matches(LogRecord record) {
        return value.equals(record.labels().get(label));
    }

    /** The promotion as the config file writes its key. */
    public String key() {
        return label + "=" + value;
    }
}
