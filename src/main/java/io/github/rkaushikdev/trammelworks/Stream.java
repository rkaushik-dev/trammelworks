package io.github.rkaushikdev.trammelworks;

import java.util.Locale;

/** Which of the two standard descriptors the container wrote to. */
public enum Stream implements Json.Valued {
    /** Standard output. */
    STDOUT("stdout"),
    /** Standard error. */
    STDERR("stderr");

    private final String value;

    Stream(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    /** Read a stream name, forgiving case and padding. */
    public static Stream parse(String raw) {
        return Json.Valued.lookup(Stream.class, Text.strip(raw).toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("unknown stream " + Repr.of(raw)));
    }

    @Override
    public String toString() {
        return value;
    }
}
