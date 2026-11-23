package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import io.github.rkaushikdev.trammelworks.query.Execute;
import io.github.rkaushikdev.trammelworks.query.Query;
import io.github.rkaushikdev.trammelworks.query.Since;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * The records a picture of the forecourt is drawn from: the last day of everything, and every
 * alarm record the archive holds, since an alarm raised last week and never cleared is still
 * standing today.
 *
 * @param day every record of the last {@link #WINDOW}
 * @param alarms every tank alarm record
 */
public record ForecourtDay(List<StoredRecord> day, List<StoredRecord> alarms) {

    /** How far back "today" reaches. */
    public static final Duration WINDOW = Duration.ofHours(24);

    /** Copies the lists. */
    public ForecourtDay {
        day = List.copyOf(day);
        alarms = List.copyOf(alarms);
    }

    /** Read both from an open archive, as of {@code now}. */
    public static ForecourtDay read(ArchiveDb connection, NanoTime now) {
        Since dayStart = new Since(now.epochNs() - WINDOW.toNanos());
        return new ForecourtDay(
                Execute.search(connection, Query.of(dayStart)).records(),
                Execute.search(connection, Query.of(AlarmLog.filter())).records());
    }

    /**
     * How long since any unit playing {@code role} last said anything, as of {@code now}. A role
     * silent for the whole day reads as silent for the whole window, which is as far back as this
     * read can see.
     */
    public Duration quietFor(Role role, ForecourtConfig roles, NanoTime now) {
        return day.stream()
                .filter(record -> roles.roleOf(record.unit()) == role)
                .map(StoredRecord::bestAt)
                .max(Comparator.naturalOrder())
                .map(last -> Duration.ofNanos(Math.max(0, now.epochNs() - last.epochNs())))
                .orElse(WINDOW);
    }

    /** The state each pump was last seen in today. */
    public PumpTimeline pumps() {
        return PumpTimeline.replay(day);
    }

    /** The alarm spans, cleared and standing. */
    public AlarmLog alarmLog() {
        return AlarmLog.replay(alarms);
    }

    /** Today's sign and dispenser prices, replayed. */
    public PriceAudit prices() {
        return PriceAudit.replay(day);
    }
}
