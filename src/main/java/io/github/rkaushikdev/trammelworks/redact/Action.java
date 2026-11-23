package io.github.rkaushikdev.trammelworks.redact;

import io.github.rkaushikdev.trammelworks.Json;

/** What happens to what a site rule matched. */
public enum Action implements Json.Valued {
    /** Replace the match with a fixed marker. Nothing survives. */
    DROP("drop"),
    /** Keep the leading and trailing characters the site asked for. */
    MASK("mask");

    private final String value;

    Action(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    /** The action spelt this way, if there is one. */
    public static java.util.Optional<Action> fromValue(String raw) {
        return Json.Valued.lookup(Action.class, raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
