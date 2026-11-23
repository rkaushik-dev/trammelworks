package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.NanoTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * One trading day on a forecourt, which is not a calendar day.
 *
 * <p>A site closes its day at a fixed local time, usually in the small hours when the shop is
 * quietest, and every figure the back office reconciles is cut there: the fuel sold, the money
 * taken, the tank dips. The day named 4 March with a 05:00 close runs from 05:00 on the 4th to
 * 05:00 on the 5th, site time, so a sale at 02:00 on the 5th belongs to the 4th. Site time
 * includes daylight saving, which makes the spring and autumn days an hour short and long.
 *
 * @param date the date the day is named by
 * @param zone the site's time zone
 * @param close when the day closes, site time
 */
public record BusinessDay(LocalDate date, ZoneId zone, LocalTime close) {

    /** The business day an instant falls in. */
    public static BusinessDay containing(NanoTime at, ZoneId zone, LocalTime close) {
        ZonedDateTime local = at.toDateTime().atZoneSameInstant(zone);
        LocalDate date =
                local.toLocalTime().isBefore(close) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
        return new BusinessDay(date, zone, close);
    }

    /** The last business day to have closed by {@code now}. */
    public static BusinessDay lastClosed(NanoTime now, ZoneId zone, LocalTime close) {
        BusinessDay current = containing(now, zone, close);
        return new BusinessDay(current.date().minusDays(1), zone, close);
    }

    /** When the day opened. */
    public NanoTime start() {
        return NanoTime.fromDateTime(date.atTime(close).atZone(zone).toOffsetDateTime());
    }

    /** When the day closed, which is when the next one opened. */
    public NanoTime end() {
        return NanoTime.fromDateTime(date.plusDays(1).atTime(close).atZone(zone).toOffsetDateTime());
    }

    /** Whether an instant falls in the day: from the open, up to but not including the close. */
    public boolean contains(NanoTime at) {
        return at.compareTo(start()) >= 0 && at.compareTo(end()) < 0;
    }

    @Override
    public String toString() {
        return date + " (closing " + close + " " + zone.getId() + ")";
    }
}
