package io.github.rkaushikdev.trammelworks.report;

import java.util.List;

/**
 * What the box believes about its own time.
 *
 * @param boots boots seen
 * @param syncs clock corrections in this boot
 * @param driftMs the drift last measured
 * @param unknownRecords records with no believable time
 */
public record ClockSummary(long boots, long syncs, long driftMs, long unknownRecords) implements SummarySection {

    /** Nothing counted yet. */
    public ClockSummary() {
        this(0, 0, 0, 0);
    }

    /** Whether the clock has been corrected and is within a minute. */
    @Override
    public boolean healthy() {
        return syncs > 0 && Math.abs(driftMs) < 60_000;
    }

    @Override
    public List<String> problems() {
        return syncs == 0 ? List.of("the clock has not been corrected in this boot") : List.of();
    }

    @Override
    public String line() {
        return syncs + " syncs, drift " + driftMs + " ms";
    }

    /** The same, with {@code value} corrections. */
    public ClockSummary withSyncs(long value) {
        return new ClockSummary(boots, value, driftMs, unknownRecords);
    }

    /** The same, with a drift of {@code value} milliseconds. */
    public ClockSummary withDriftMs(long value) {
        return new ClockSummary(boots, syncs, value, unknownRecords);
    }
}
