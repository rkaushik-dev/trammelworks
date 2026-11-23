package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.NanoTime;
import java.time.Duration;
import java.util.Optional;

/**
 * One alarm on one tank, from the line that raised it to the line that cleared it.
 *
 * @param tank the tank
 * @param alarm the alarm
 * @param raisedAt when the gauge raised it, or the first time it was seen raised
 * @param clearedAt when it cleared, empty while it is still standing
 * @param repeats how many more times the gauge reported it standing before it cleared
 */
public record AlarmSpan(String tank, TankAlarm alarm, NanoTime raisedAt, Optional<NanoTime> clearedAt, int repeats) {

    /** Whether the alarm is still standing at the end of what was read. */
    public boolean open() {
        return clearedAt.isEmpty();
    }

    /** How long it stood, measured to {@code now} when it has not cleared. */
    public Duration standing(NanoTime now) {
        long end = clearedAt.orElse(now).epochNs();
        return Duration.ofNanos(Math.max(0, end - raisedAt.epochNs()));
    }
}
