package io.github.rkaushikdev.trammelworks.redact;

import java.util.List;

/**
 * A one line description of the redaction pass, for the doctor command.
 *
 * @param enabled whether the pass runs
 * @param mask what the card mask keeps
 * @param rules the site rules' names
 */
public record RedactSummary(boolean enabled, PanMask mask, List<String> rules) {

    /** Copies the names. */
    public RedactSummary {
        rules = List.copyOf(rules);
    }

    @Override
    public String toString() {
        if (!enabled) {
            return "redaction DISABLED";
        }
        String shape = "keep " + mask;
        if (rules.isEmpty()) {
            return "redaction on, " + shape + ", card rules only";
        }
        return "redaction on, " + shape + ", plus " + rules.size() + " site rules";
    }
}
