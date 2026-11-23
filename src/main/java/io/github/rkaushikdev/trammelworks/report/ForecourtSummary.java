package io.github.rkaushikdev.trammelworks.report;

import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import io.github.rkaushikdev.trammelworks.forecourt.AlarmLog;
import io.github.rkaushikdev.trammelworks.forecourt.AlarmSeverity;
import io.github.rkaushikdev.trammelworks.forecourt.AlarmSpan;
import io.github.rkaushikdev.trammelworks.forecourt.Outcome;
import io.github.rkaushikdev.trammelworks.forecourt.PumpState;
import io.github.rkaushikdev.trammelworks.forecourt.PumpTimeline;
import io.github.rkaushikdev.trammelworks.forecourt.StoreAndForward;
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
 */
public record ForecourtSummary(
        long sales,
        long declines,
        long hostUnavailable,
        long unauthorised,
        long alarmsStanding,
        long environmentalStanding,
        long safDepth)
        implements SummarySection {

    /** Nothing counted yet. */
    public ForecourtSummary() {
        this(0, 0, 0, 0, 0, 0, 0);
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
                safDepth);
    }

    private static long count(List<StoredRecord> records, String label, String value) {
        return records.stream()
                .filter(record -> value.equals(record.labels().get(label)))
                .count();
    }

    @Override
    public boolean healthy() {
        return unauthorised == 0 && environmentalStanding == 0;
    }

    @Override
    public List<String> problems() {
        List<String> found = new ArrayList<>();
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
