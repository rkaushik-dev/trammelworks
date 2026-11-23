package io.github.rkaushikdev.trammelworks.report;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Units;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import io.github.rkaushikdev.trammelworks.forecourt.AlarmLog;
import io.github.rkaushikdev.trammelworks.forecourt.AlarmSeverity;
import io.github.rkaushikdev.trammelworks.forecourt.AlarmSpan;
import io.github.rkaushikdev.trammelworks.forecourt.ForecourtConfig;
import io.github.rkaushikdev.trammelworks.forecourt.ForecourtDay;
import io.github.rkaushikdev.trammelworks.forecourt.Outcome;
import io.github.rkaushikdev.trammelworks.forecourt.PumpState;
import io.github.rkaushikdev.trammelworks.forecourt.PumpTimeline;
import io.github.rkaushikdev.trammelworks.forecourt.Role;
import io.github.rkaushikdev.trammelworks.forecourt.StoreAndForward;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What happened on the forecourt itself, read back out of the archive.
 *
 * <p>The other sections say whether stanchion is working. This one says whether the site is: an
 * environmental alarm still standing, or a delivery nobody authorised, is something the site
 * manager has to act on today whatever state the uplink is in.
 *
 * @param sales deliveries completed
 * @param declines payments the host or the card declined
 * @param hostUnavailable payments nobody answered
 * @param unauthorised deliveries that started without an authorisation
 * @param alarmsStanding tank alarms raised and not cleared
 * @param environmentalStanding of those, the release detection alarms
 * @param safDepth payments waiting in store and forward, as the kernel last reported
 * @param gaugeQuietMinutes how long since the tank gauge last said anything, in whole minutes
 */
public record ForecourtSummary(
        long sales,
        long declines,
        long hostUnavailable,
        long unauthorised,
        long alarmsStanding,
        long environmentalStanding,
        long safDepth,
        long gaugeQuietMinutes)
        implements SummarySection {

    /**
     * How long a tank gauge may go without a line before nothing is watching the tanks. A poller
     * reads the console every few minutes, so an hour of silence is a dead poller or a dead
     * console, and the site has no leak detection until somebody notices.
     */
    public static final long GAUGE_QUIET_LIMIT_MINUTES = 60;

    /** Nothing counted yet. */
    public ForecourtSummary() {
        this(0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Count what a forecourt day read out of the archive, as of {@code now}. */
    public static ForecourtSummary of(ForecourtDay read, ForecourtConfig roles, NanoTime now) {
        return fromRecords(read.day(), read.alarms())
                .withGaugeQuietMinutes(
                        read.quietFor(Role.TANK_GAUGE, roles, now).toMinutes());
    }

    /** The same, with the tank gauge quiet for {@code value} minutes. */
    public ForecourtSummary withGaugeQuietMinutes(long value) {
        return new ForecourtSummary(
                sales, declines, hostUnavailable, unauthorised, alarmsStanding, environmentalStanding, safDepth, value);
    }

    /** Whether the tank gauge has been quiet for longer than a poller ever is. */
    public boolean gaugeSilent() {
        return gaugeQuietMinutes > GAUGE_QUIET_LIMIT_MINUTES;
    }

    /**
     * Count a day's records, and the alarm records of however long the archive holds: an alarm
     * raised last week and never cleared is still standing tonight.
     */
    public static ForecourtSummary fromRecords(List<StoredRecord> day, List<StoredRecord> alarms) {
        long sales = count(day, PumpState.LABEL, PumpState.COMPLETED.value());
        long declines = count(day, Outcome.LABEL, Outcome.DECLINED.value());
        long unavailable = count(day, Outcome.LABEL, Outcome.HOST_UNAVAILABLE.value());
        // The last depth the kernel reported is the queue as it stands; an earlier, deeper one has
        // since drained.
        long safDepth = day.stream()
                .filter(record -> record.labels().containsKey(StoreAndForward.LABEL))
                .max(Comparator.comparing(StoredRecord::bestAt))
                .map(record -> Long.parseLong(record.labels().get(StoreAndForward.LABEL)))
                .orElse(0L);
        List<AlarmSpan> standing = AlarmLog.replay(alarms).open();
        long environmental = standing.stream()
                .filter(span -> span.alarm().severity() == AlarmSeverity.ENVIRONMENTAL)
                .count();
        return new ForecourtSummary(
                sales,
                declines,
                unavailable,
                PumpTimeline.replay(day).unauthorised().size(),
                standing.size(),
                environmental,
                safDepth,
                0);
    }

    private static long count(List<StoredRecord> records, String label, String value) {
        return records.stream()
                .filter(record -> value.equals(record.labels().get(label)))
                .count();
    }

    @Override
    public boolean healthy() {
        return unauthorised == 0 && environmentalStanding == 0 && !gaugeSilent();
    }

    @Override
    public List<String> problems() {
        List<String> found = new ArrayList<>();
        if (gaugeSilent()) {
            found.add("the tank gauge has been silent for "
                    + Units.formatDuration(Duration.ofMinutes(gaugeQuietMinutes)) + ", nothing is watching the tanks");
        }
        if (environmentalStanding != 0) {
            found.add(environmentalStanding + " environmental tank alarms are standing");
        }
        if (unauthorised != 0) {
            found.add(unauthorised + " deliveries started without an authorisation");
        }
        if (hostUnavailable != 0) {
            found.add("the payment host did not answer " + hostUnavailable + " times");
        }
        if (safDepth != 0) {
            found.add(safDepth + " payments are still waiting in store and forward");
        }
        return found;
    }

    @Override
    public String line() {
        String line = sales + " sales, " + declines + " declines, " + alarmsStanding + " tank alarms standing";
        return safDepth == 0 ? line : line + ", " + safDepth + " in store and forward";
    }
}
