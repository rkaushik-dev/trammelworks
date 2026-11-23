package io.github.rkaushikdev.trammelworks.forecourt;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading which fuelling point a line is about.
 *
 * <p>The forecourt controller numbers its dispenser sides as fuelling points, and every line a
 * dispute is decided on mentions one: {@code FP03 AUTHORISED}, {@code pump=3}, {@code fp:3}. The
 * payment kernel names the same number when it authorises a pump. Reading it once, here, and
 * carrying it as the {@code pump} label is what lets a filter put the payment and the delivery
 * for the same sale side by side.
 *
 * <p>Numbers run 1 to 99, which is what IFSF addresses; a line saying {@code pump 0} or
 * {@code pump 120} is about something else.
 */
public final class FuellingPoint {

    /** The label a fuelling point is carried under. */
    public static final String LABEL = "pump";

    /** The most fuelling points a controller addresses. */
    public static final int MAX_POINT = 99;

    static final Pattern MENTION = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:fp|fuelling[ _]point|pump|dispenser)\\s*[=:#]?\\s*0*(\\d{1,3})(?![0-9A-Za-z])");

    private FuellingPoint() {}

    /** The first fuelling point a line names, without leading zeros. */
    public static Optional<String> find(String message) {
        Matcher match = MENTION.matcher(message);
        while (match.find()) {
            String digits = match.group(1);
            int point = Integer.parseInt(digits);
            if (point >= 1 && point <= MAX_POINT) {
                return Optional.of(Integer.toString(point));
            }
        }
        return Optional.empty();
    }
}
