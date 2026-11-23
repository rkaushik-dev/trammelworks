package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.NanoTime;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/**
 * A pump charging a different price for a grade than the street sign shows.
 *
 * <p>Weights and measures rules say the price on the sign is the price at the pump. A change goes
 * to the sign and the dispensers within moments of each other, so a mismatch that outlasts a few
 * minutes means one of them never took the change, and every sale on that pump until somebody
 * notices is either overcharged, which is the site's problem with the inspector, or undercharged,
 * which is its problem with the till.
 *
 * @param pump the fuelling point
 * @param grade the grade
 * @param sign what the sign showed
 * @param pumpPrice what the pump was charging
 * @param since when they stopped agreeing
 * @param until when they agreed again, empty while they still differ
 */
public record PriceMismatch(
        String pump, Grade grade, BigDecimal sign, BigDecimal pumpPrice, NanoTime since, Optional<NanoTime> until) {

    /** How long they disagreed, measured to {@code now} while they still do. */
    public Duration lasted(NanoTime now) {
        return Duration.ofNanos(Math.max(0, until.orElse(now).epochNs() - since.epochNs()));
    }
}
