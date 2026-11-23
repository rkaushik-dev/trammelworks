package io.github.rkaushikdev.trammelworks.forecourt;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How much product a completed delivery dispensed, from the line the dispenser writes when the
 * nozzle goes back: {@code FP3 COMPLETED nozzle replaced, 41.2 litres}.
 */
public final class DeliveredVolume {

    /** The label the volume is carried under, in litres. */
    public static final String LABEL = "litres";

    static final Pattern VOLUME =
            Pattern.compile("(?i)(?<![\\d.])(\\d{1,5}(?:\\.\\d{1,3})?)\\s*(?:l|ltr|litres|liters)(?![A-Za-z])");

    private DeliveredVolume() {}

    /** The volume a line reports. */
    public static Optional<BigDecimal> read(String message) {
        Matcher match = VOLUME.matcher(message);
        return match.find() ? Optional.of(new BigDecimal(match.group(1))) : Optional.empty();
    }
}
