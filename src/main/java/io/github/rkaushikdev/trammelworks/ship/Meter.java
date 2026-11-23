package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.ShipError;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The monthly byte allowance, and who is allowed to spend the last of it.
 *
 * <p>The billing period is anchored to a day of the month, because that is how the plans are sold.
 * Every priority class has a floor: a fraction of the allowance nothing at that class may touch.
 * Chatter stops once 70% is gone; payment traffic keeps going to the last byte. So the meter does
 * not decide "is there room", it decides "is there room for this".
 *
 * <p>The carrier bills the bytes on the radio, not the bytes in the batch. Every upload also costs
 * a TCP and TLS handshake, the certificate chain and the HTTP headers both ways, which on a link
 * that reconnects for each batch is several kilobytes and, with small batches, can be most of the
 * bill. A site that has measured its own overhead per request sets it, and the meter charges and
 * checks every request with it added.
 */
public final class Meter {

    /** The default allowance: two decimal gigabytes. */
    public static final long DEFAULT_ALLOWANCE = 2L * 1000 * 1000 * 1000;

    /** The default billing anchor. */
    public static final int DEFAULT_ANCHOR_DAY = 1;

    /** How much of the allowance each class leaves untouched. */
    public static final Map<String, Double> DEFAULT_FLOORS = defaultFloors();

    private final long allowanceBytes;
    private final int anchorDay;
    private final Map<String, Double> floors;
    private final long requestOverhead;
    private long usedBytes;
    private NanoTime startedAt = new NanoTime(0);
    private int rollovers;

    private static Map<String, Double> defaultFloors() {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("payment", 0.0);
        out.put("standard", 0.15);
        out.put("chatter", 0.3);
        return java.util.Collections.unmodifiableMap(out);
    }

    /** A meter with the default floors. */
    public Meter(long allowanceBytes, int anchorDay) {
        this(allowanceBytes, anchorDay, DEFAULT_FLOORS);
    }

    /** A meter with given floors, each in {@code [0, 1)}, and nothing charged per request. */
    public Meter(long allowanceBytes, int anchorDay, Map<String, Double> floors) {
        this(allowanceBytes, anchorDay, floors, 0);
    }

    /** A meter with given floors that charges {@code requestOverhead} bytes for every request. */
    public Meter(long allowanceBytes, int anchorDay, Map<String, Double> floors, long requestOverhead) {
        checkAnchor(anchorDay);
        if (requestOverhead < 0) {
            throw new ShipError("request overhead cannot be negative", Long.toString(requestOverhead));
        }
        if (allowanceBytes <= 0) {
            throw new ShipError("allowance must be positive", Long.toString(allowanceBytes));
        }
        for (Map.Entry<String, Double> floor : floors.entrySet()) {
            if (!(floor.getValue() >= 0.0 && floor.getValue() < 1.0)) {
                throw new ShipError("a class floor must be in [0, 1)", floor.getKey());
            }
        }
        this.allowanceBytes = allowanceBytes;
        this.anchorDay = anchorDay;
        this.floors = Map.copyOf(floors);
        this.requestOverhead = requestOverhead;
    }

    /** Refuse an anchor day that not every month has. */
    public static int checkAnchor(int day) {
        if (day < 1 || day > 28) {
            // Above 28 the anchor is not a day every month has.
            throw new ShipError("billing anchor day must be 1 to 28", Integer.toString(day));
        }
        return day;
    }

    /** The start of the billing period containing this instant. */
    public static NanoTime periodStart(NanoTime now, int anchorDay) {
        checkAnchor(anchorDay);
        OffsetDateTime stamp = now.toDateTime();
        OffsetDateTime start;
        if (stamp.getDayOfMonth() >= anchorDay) {
            start = stamp.withDayOfMonth(anchorDay).toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
        } else {
            start = stamp.withDayOfMonth(1)
                    .minusDays(1)
                    .withDayOfMonth(anchorDay)
                    .toLocalDate()
                    .atStartOfDay()
                    .atOffset(ZoneOffset.UTC);
        }
        return NanoTime.fromDateTime(start);
    }

    /** One period on from a start, which is the next anchor day. */
    public static NanoTime periodEnd(NanoTime start, int anchorDay) {
        OffsetDateTime stamp = start.toDateTime();
        OffsetDateTime next = stamp.toLocalDate()
                .withDayOfMonth(1)
                .plusMonths(1)
                .withDayOfMonth(anchorDay)
                .atStartOfDay()
                .atOffset(ZoneOffset.UTC);
        return NanoTime.fromDateTime(next);
    }

    /** The whole allowance. */
    public long allowanceBytes() {
        return allowanceBytes;
    }

    /** The billing anchor. */
    public int anchorDay() {
        return anchorDay;
    }

    /** Spent this period. */
    public long usedBytes() {
        return usedBytes;
    }

    /** When this period started. */
    public NanoTime startedAt() {
        return startedAt;
    }

    /** How many periods have started. */
    public int rollovers() {
        return rollovers;
    }

    /** What every request costs beyond its body. */
    public long requestOverhead() {
        return requestOverhead;
    }

    /** What sending a body of {@code size} bytes costs on the bill. */
    public long requestCost(long size) {
        return size + requestOverhead;
    }

    /** The floors, by class. */
    public Map<String, Double> floors() {
        return floors;
    }

    /** Put back what a stored meter had spent. */
    public void restore(long used, NanoTime started, int rolled) {
        usedBytes = used;
        startedAt = started;
        rollovers = rolled;
    }

    /** What is left of the allowance, never negative. */
    public long remaining() {
        return Math.max(0, allowanceBytes - usedBytes);
    }

    /** How much of the allowance is gone. */
    public double spentRatio() {
        return usedBytes / (double) allowanceBytes;
    }

    /** The fraction of the allowance a class leaves untouched. */
    public double floorFor(String klass) {
        return floors.getOrDefault(klass, 0.0);
    }

    /** Bytes this class may still spend, given its floor. */
    public long availableTo(String klass) {
        long reserved = (long) (allowanceBytes * floorFor(klass));
        return Math.max(0, remaining() - reserved);
    }

    /** True when a payment send of this size is inside what remains. */
    public boolean allows(long size) {
        return allows(size, "payment");
    }

    /** True when a send of this size is inside what the class may spend. */
    public boolean allows(long size, String klass) {
        if (size < 0) {
            throw new ShipError("cannot meter a negative size", Long.toString(size));
        }
        return size <= availableTo(klass);
    }

    /** Record a send. Returns the new total. */
    public long charge(long size) {
        if (size < 0) {
            throw new ShipError("cannot charge a negative size", Long.toString(size));
        }
        usedBytes += size;
        return usedBytes;
    }

    /** Start a new period if this instant is in one. Returns whether it did. */
    public boolean roll(NanoTime now) {
        NanoTime start = periodStart(now, anchorDay);
        if (startedAt.epochNs() != 0 && start.compareTo(startedAt) <= 0) {
            return false;
        }
        startedAt = start;
        usedBytes = 0;
        rollovers++;
        return true;
    }

    /** When this period ends. */
    public NanoTime endsAt() {
        return periodEnd(startedAt, anchorDay);
    }

    /** One line for the status report. */
    public String describe() {
        String spent = "meter " + usedBytes + "/" + allowanceBytes + " bytes (" + Repr.fixed(100 * spentRatio(), 1)
                + "%) this period";
        return requestOverhead == 0 ? spent : spent + ", " + requestOverhead + " per request";
    }
}
