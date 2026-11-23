package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.Json;
import java.util.List;
import java.util.Optional;

/** A fuel grade, in the words signs and dispensers name it by. */
public enum Grade implements Json.Valued {
    /** Regular unleaded. */
    UNLEADED("unleaded", List.of("unleaded", "regular", "ulp", "ulr")),
    /** The middle grade. */
    MIDGRADE("midgrade", List.of("midgrade", "plus")),
    /** Premium unleaded. */
    PREMIUM("premium", List.of("premium", "super", "sup")),
    /** Road diesel. */
    DIESEL("diesel", List.of("diesel", "dsl"));

    /** The label a grade is carried under. */
    public static final String LABEL = "grade";

    private final String value;
    private final List<String> spellings;

    Grade(String value, List<String> spellings) {
        this.value = value;
        this.spellings = spellings;
    }

    @Override
    public String value() {
        return value;
    }

    /** Every word this grade is written as, lower case. */
    public List<String> spellings() {
        return spellings;
    }

    /** The grade with this label value. */
    public static Optional<Grade> fromValue(String raw) {
        return Json.Valued.lookup(Grade.class, raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
