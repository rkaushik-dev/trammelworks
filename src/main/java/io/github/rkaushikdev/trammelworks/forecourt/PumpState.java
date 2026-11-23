package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.Json;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The state of one fuelling point, in the words the forecourt controller logs it in.
 *
 * <p>These are the IFSF fuelling point states, with the words the common controller dialects use
 * for each folded in: a DOMS bridge writes {@code FINISHED} where an IFSF one writes {@code
 * COMPLETED}, and American firmware spells {@code AUTHORIZED} with a z. The rest of stanchion only
 * ever sees the state, never the dialect.
 */
public enum PumpState implements Json.Valued {
    /** Nozzle hung up, nothing pending. */
    IDLE("idle", List.of("idle")),
    /** Nozzle lifted, waiting for the site to authorise it. */
    CALLING("calling", List.of("calling", "nozzle lifted")),
    /** Released for a delivery, product not yet flowing. */
    AUTHORISED("authorised", List.of("authorised", "authorized", "released")),
    /** The pump motor is running. */
    STARTED("started", List.of("started")),
    /** Product is flowing. */
    FUELLING("fuelling", List.of("fuelling", "fueling", "delivering")),
    /** Stopped mid delivery by the cashier or the emergency stop. */
    SUSPENDED("suspended", List.of("suspended", "paused")),
    /** The delivery has ended and is waiting to be paid for or cleared. */
    COMPLETED("completed", List.of("completed", "finished", "end of delivery")),
    /** Closed by the site. */
    CLOSED("closed", List.of("closed")),
    /** Faulted or not answering. */
    INOPERATIVE("inoperative", List.of("inoperative", "offline", "pump error"));

    /** The label a fuelling point's state is carried under. */
    public static final String LABEL = "pump_state";

    /** The states in which product may legitimately be flowing. */
    public static final Set<PumpState> DELIVERING = Set.of(STARTED, FUELLING, SUSPENDED);

    private static final Pattern WORDS = words();

    private final String value;
    private final List<String> spellings;

    PumpState(String value, List<String> spellings) {
        this.value = value;
        this.spellings = spellings;
    }

    private static Pattern words() {
        StringBuilder alternatives = new StringBuilder();
        for (PumpState state : values()) {
            for (String spelling : state.spellings) {
                if (!alternatives.isEmpty()) {
                    alternatives.append('|');
                }
                alternatives.append(Pattern.quote(spelling));
            }
        }
        return Pattern.compile("(?i)(?<![A-Za-z])(" + alternatives + ")(?![A-Za-z])");
    }

    @Override
    public String value() {
        return value;
    }

    /** Every word this state is logged as. */
    public List<String> spellings() {
        return spellings;
    }

    /** The state with this label value. */
    public static Optional<PumpState> fromValue(String raw) {
        for (PumpState state : values()) {
            if (state.value.equals(raw)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }

    /** The first state a line reports. */
    public static Optional<PumpState> find(String message) {
        Matcher match = WORDS.matcher(message);
        if (!match.find()) {
            return Optional.empty();
        }
        String word = match.group(1).toLowerCase(Locale.ROOT);
        for (PumpState state : values()) {
            if (state.spellings.contains(word)) {
                return Optional.of(state);
            }
        }
        throw new IllegalStateException("a state word with no state: " + word);
    }

    @Override
    public String toString() {
        return value;
    }
}
