package io.github.rkaushikdev.trammelworks.report;

import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.Units;
import java.util.ArrayList;
import java.util.List;

/**
 * What is waiting.
 *
 * @param used bytes on disk
 * @param limit the budget
 * @param segments segments sealed
 * @param evicted segments evicted unsent
 */
public record SpoolSummary(long used, long limit, long segments, long evicted) implements SummarySection {

    /** Nothing counted yet, against a budget of one byte. */
    public SpoolSummary() {
        this(0, 1, 0, 0);
    }

    /** How full the spool is, as a fraction. */
    public double ratio() {
        return limit != 0 ? (double) used / limit : 0.0;
    }

    /** Whether the spool has room and lost nothing. */
    @Override
    public boolean healthy() {
        return ratio() < 0.8 && evicted == 0;
    }

    @Override
    public List<String> problems() {
        List<String> found = new ArrayList<>();
        if (evicted != 0) {
            found.add(evicted + " spool segments were evicted unsent");
        }
        if (ratio() >= 0.8) {
            found.add("the spool is " + Repr.fixed(100 * ratio(), 0) + "% full");
        }
        return found;
    }

    @Override
    public String line() {
        return Units.formatBytes(used) + " of " + Units.formatBytes(limit) + " in " + segments + " segments";
    }

    /** The same, holding {@code value} bytes. */
    public SpoolSummary withUsed(long value) {
        return new SpoolSummary(value, limit, segments, evicted);
    }

    /** The same, against a budget of {@code value} bytes. */
    public SpoolSummary withLimit(long value) {
        return new SpoolSummary(used, value, segments, evicted);
    }

    /** The same, having evicted {@code value} segments. */
    public SpoolSummary withEvicted(long value) {
        return new SpoolSummary(used, limit, segments, value);
    }
}
