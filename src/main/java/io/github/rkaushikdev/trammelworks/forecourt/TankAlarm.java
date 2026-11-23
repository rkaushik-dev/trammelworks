package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.Json;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The alarms an automatic tank gauge raises, in the words the console prints them.
 *
 * <p>The console writes an alarm as the tank it is on followed by its name, {@code T 1:SUDDEN LOSS
 * ALARM}, and writes the same line with {@code CLEAR} when it goes away. The gauge poller passes
 * those lines through as they came, so they are read here into the tank, the alarm and whether it
 * is starting or ending. A tank is numbered by the console, 1 to 99.
 */
public enum TankAlarm implements Json.Valued {
    /** A leak test has found product leaving the tank. */
    LEAK("leak", AlarmSeverity.ENVIRONMENTAL, List.of("leak alarm")),
    /** Product level fell faster than any dispensing explains. */
    SUDDEN_LOSS("sudden_loss", AlarmSeverity.ENVIRONMENTAL, List.of("sudden loss alarm")),
    /** The periodic or annual precision test failed. */
    TEST_FAIL(
            "test_fail",
            AlarmSeverity.ENVIRONMENTAL,
            List.of("periodic test fail", "annual test fail", "gross test fail", "csld rate increase")),
    /** The interstitial sensor between the walls is wet with product. */
    INTERSTITIAL("interstitial", AlarmSeverity.ENVIRONMENTAL, List.of("fuel alarm", "interstitial alarm")),
    /** About to overfill during a delivery. */
    OVERFILL("overfill", AlarmSeverity.OPERATIONAL, List.of("overfill alarm", "max product alarm")),
    /** Above the high product limit. */
    HIGH_PRODUCT("high_product", AlarmSeverity.OPERATIONAL, List.of("high product alarm")),
    /** Running low. */
    LOW_PRODUCT("low_product", AlarmSeverity.OPERATIONAL, List.of("low product alarm", "delivery needed")),
    /** Water at the bottom of the tank above the limit. */
    HIGH_WATER("high_water", AlarmSeverity.OPERATIONAL, List.of("high water alarm", "high water warning")),
    /** The probe is not answering. */
    PROBE_OUT("probe_out", AlarmSeverity.EQUIPMENT, List.of("probe out", "sensor out alarm")),
    /** The probe answered with a level that cannot be right. */
    INVALID_LEVEL("invalid_level", AlarmSeverity.EQUIPMENT, List.of("invalid fuel level"));

    /** The label the alarm is carried under. */
    public static final String LABEL = "alarm";

    /** The label carrying whether the alarm is starting or ending. */
    public static final String STATE_LABEL = "alarm_state";

    /**
     * The label carrying how serious the alarm is. The spool's default promotion reads it, which is
     * why it is spelt out on the record rather than looked up from the alarm.
     */
    public static final String SEVERITY_LABEL = "alarm_severity";

    /** The label carrying the tank. */
    public static final String TANK_LABEL = "tank";

    /** The alarm state value of an alarm that has started. */
    public static final String ACTIVE = "active";

    /** The alarm state value of an alarm that has gone away. */
    public static final String CLEAR = "clear";

    /** The most tanks a console addresses. */
    public static final int MAX_TANK = 99;

    static final Pattern TANK = Pattern.compile("(?i)(?<![A-Za-z0-9])(?:t|tank)\\s*0*(\\d{1,3})\\s*:?(?![0-9])");

    static final Pattern CLEARED = Pattern.compile("(?i)(?<![A-Za-z])clear(?:ed)?(?![A-Za-z])");

    private final String value;
    private final AlarmSeverity severity;
    private final List<String> phrases;

    TankAlarm(String value, AlarmSeverity severity, List<String> phrases) {
        this.value = value;
        this.severity = severity;
        this.phrases = phrases;
    }

    @Override
    public String value() {
        return value;
    }

    /** Who has to hear about it. */
    public AlarmSeverity severity() {
        return severity;
    }

    /** The words the console prints it as. */
    public List<String> phrases() {
        return phrases;
    }

    /** The alarm with this label value. */
    public static Optional<TankAlarm> fromValue(String raw) {
        return Json.Valued.lookup(TankAlarm.class, raw);
    }

    /**
     * One alarm line, read.
     *
     * @param tank the tank, without leading zeros
     * @param alarm the alarm
     * @param cleared whether the line ends the alarm rather than raising it
     */
    public record Reading(String tank, TankAlarm alarm, boolean cleared) {

        /** The alarm state label value. */
        public String state() {
            return cleared ? CLEAR : ACTIVE;
        }
    }

    /** The alarm a tank gauge line reports, if it names a tank and an alarm. */
    public static Optional<Reading> read(String message) {
        String folded = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        TankAlarm found = null;
        int foundAt = Integer.MAX_VALUE;
        for (TankAlarm alarm : values()) {
            for (String phrase : alarm.phrases) {
                int at = folded.indexOf(phrase);
                if (at >= 0 && at < foundAt) {
                    found = alarm;
                    foundAt = at;
                }
            }
        }
        if (found == null) {
            return Optional.empty();
        }
        Matcher tank = TANK.matcher(message);
        while (tank.find()) {
            int number = Integer.parseInt(tank.group(1));
            if (number >= 1 && number <= MAX_TANK) {
                return Optional.of(new Reading(
                        Integer.toString(number),
                        found,
                        CLEARED.matcher(message).find()));
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return value;
    }
}
