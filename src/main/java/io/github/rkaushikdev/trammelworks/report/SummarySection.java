package io.github.rkaushikdev.trammelworks.report;

import java.util.List;

/**
 * One section of the nightly summary: what it tells an operator, and its line of the text form.
 *
 * <p>Each section owns its own problems. They were once all decided in the summary itself, which
 * meant a field added to a section could be carried for months before anything looked at it.
 */
public sealed interface SummarySection
        permits IngestSummary, ShipSummary, SpoolSummary, ClockSummary, ForecourtSummary {

    /** Whether there is nothing in this section to tell an operator. */
    boolean healthy();

    /** What an operator would want telling, in the order they would ask. */
    List<String> problems();

    /** The section's line of the text form, after its name. */
    String line();
}
