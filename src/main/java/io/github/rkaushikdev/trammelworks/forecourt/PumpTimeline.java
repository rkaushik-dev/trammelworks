package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replaying the state labels of every fuelling point and calling out the transitions a forecourt
 * should never make.
 *
 * <p>The one that matters most is product flowing from a point that was never authorised. It is
 * what a free flow fault, a bypassed controller and a pump left in attendant mode all look like
 * from the logs, and every litre of it is unpaid. A point is allowed to start delivering only from
 * {@link PumpState#AUTHORISED} or from another delivering state; the first state seen for a point is
 * taken as given, because a replay that starts mid delivery has not seen the authorisation.
 *
 * <p>Records are replayed in corrected time order, so a box whose clock jumped back mid shift
 * does not appear to deliver before it authorised.
 */
public final class PumpTimeline {

    private final Map<String, PumpState> states = new LinkedHashMap<>();
    private final List<PumpEvent> events = new ArrayList<>();

    /** Replay archived records, in time order, ignoring any without a pump and a state. */
    public static PumpTimeline replay(List<StoredRecord> records) {
        PumpTimeline timeline = new PumpTimeline();
        records.stream().sorted(Comparator.comparing(StoredRecord::bestAt)).forEach(record -> {
            String pump = record.labels().get(FuellingPoint.LABEL);
            Optional<PumpState> state =
                    Optional.ofNullable(record.labels().get(PumpState.LABEL)).flatMap(PumpState::fromValue);
            if (pump != null && state.isPresent()) {
                timeline.feed(pump, record.bestAt(), state.get());
            }
        });
        return timeline;
    }

    /** One state report for one point. */
    public void feed(String pump, NanoTime at, PumpState state) {
        PumpState before = states.get(pump);
        if (before != null
                && PumpState.DELIVERING.contains(state)
                && !before.equals(PumpState.AUTHORISED)
                && !PumpState.DELIVERING.contains(before)) {
            events.add(new PumpEvent(pump, at, PumpEvent.Kind.UNAUTHORISED_DELIVERY, before, state));
        }
        if (state == PumpState.INOPERATIVE && before != PumpState.INOPERATIVE) {
            events.add(new PumpEvent(pump, at, PumpEvent.Kind.INOPERATIVE, before, state));
        }
        if (before == PumpState.AUTHORISED && (state == PumpState.IDLE || state == PumpState.CLOSED)) {
            events.add(new PumpEvent(pump, at, PumpEvent.Kind.AUTHORISATION_LAPSED, before, state));
        }
        states.put(pump, state);
    }

    /** The last state each point was seen in, by pump. */
    public Map<String, PumpState> states() {
        return java.util.Collections.unmodifiableMap(states);
    }

    /** Everything worth answering for, in the order it happened. */
    public List<PumpEvent> events() {
        return List.copyOf(events);
    }

    /** Only the deliveries nobody authorised. */
    public List<PumpEvent> unauthorised() {
        return events.stream()
                .filter(event -> event.kind() == PumpEvent.Kind.UNAUTHORISED_DELIVERY)
                .toList();
    }
}
