package io.github.rkaushikdev.trammelworks.report;

import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.Units;
import java.util.ArrayList;
import java.util.List;

/**
 * What went out.
 *
 * @param batches batches acknowledged
 * @param records records in them
 * @param bytesSent bytes sent
 * @param retryable attempts that will be retried
 * @param terminal batches refused outright
 * @param meterUsed bytes of the monthly allowance spent
 * @param meterAllowance the monthly allowance
 * @param blocked passes the meter stopped
 */
public record ShipSummary(
        long batches,
        long records,
        long bytesSent,
        long retryable,
        long terminal,
        long meterUsed,
        long meterAllowance,
        long blocked)
        implements SummarySection {

    /** Nothing counted yet, against an allowance of one byte. */
    public ShipSummary() {
        this(0, 0, 0, 0, 0, 0, 1, 0);
    }

    /** How much of the allowance is spent, as a fraction. */
    public double meterRatio() {
        return meterAllowance != 0 ? (double) meterUsed / meterAllowance : 0.0;
    }

    /** Whether nothing was refused and the allowance is not nearly gone. */
    @Override
    public boolean healthy() {
        return terminal == 0 && meterRatio() < 0.9;
    }

    @Override
    public List<String> problems() {
        List<String> found = new ArrayList<>();
        if (terminal != 0) {
            found.add(terminal + " batches were refused outright");
        }
        if (meterRatio() >= 0.9) {
            found.add("the monthly allowance is " + Repr.fixed(100 * meterRatio(), 0) + "% spent");
        }
        return found;
    }

    @Override
    public String line() {
        return records + " records in " + Units.formatBytes(bytesSent) + ", meter " + Repr.fixed(100 * meterRatio(), 1)
                + "%";
    }

    /** The same, with {@code value} batches refused outright. */
    public ShipSummary withTerminal(long value) {
        return new ShipSummary(batches, records, bytesSent, retryable, value, meterUsed, meterAllowance, blocked);
    }

    /** The same, having spent {@code value} bytes of the allowance. */
    public ShipSummary withMeterUsed(long value) {
        return new ShipSummary(batches, records, bytesSent, retryable, terminal, value, meterAllowance, blocked);
    }

    /** The same, against an allowance of {@code value} bytes. */
    public ShipSummary withMeterAllowance(long value) {
        return new ShipSummary(batches, records, bytesSent, retryable, terminal, meterUsed, value, blocked);
    }

    /** The same, with {@code value} passes blocked by the meter. */
    public ShipSummary withBlocked(long value) {
        return new ShipSummary(batches, records, bytesSent, retryable, terminal, meterUsed, meterAllowance, value);
    }
}
