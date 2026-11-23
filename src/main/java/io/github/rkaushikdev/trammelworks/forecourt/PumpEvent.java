package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.NanoTime;

/**
 * Something on a fuelling point that a site manager has to answer for.
 *
 * @param pump the fuelling point
 * @param at when it happened, the corrected time where there was one
 * @param kind what it was
 * @param from the state the point was in before, or {@code null} at the start of what was read
 * @param to the state it went to
 */
public record PumpEvent(String pump, NanoTime at, Kind kind, PumpState from, PumpState to) {

    /** What went wrong on the point. */
    public enum Kind {
        /** Product flowed without the point having been authorised: a free flow, or a bypass. */
        UNAUTHORISED_DELIVERY,
        /** An authorisation that ended with no product delivered. */
        AUTHORISATION_LAPSED,
        /** The point stopped answering or faulted. */
        INOPERATIVE
    }

    @Override
    public String toString() {
        String before = from == null ? "unknown" : from.value();
        return "pump " + pump + " " + kind.name().toLowerCase(java.util.Locale.ROOT) + " at " + at.isoformat() + " ("
                + before + " to " + to.value() + ")";
    }
}
