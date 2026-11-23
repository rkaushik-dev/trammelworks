package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The day end figures for one trading day: what each pump sold, what the payments did, and what
 * went wrong on the forecourt, cut at the site's own day close so they reconcile with the back
 * office's.
 *
 * @param day the trading day
 * @param pumps per pump, in pump order
 * @param declines payments declined
 * @param hostUnavailable payments nobody answered
 * @param alarmsRaised tank alarms raised during the day
 * @param environmentalRaised of those, release detection alarms
 * @param unauthorised deliveries nobody authorised
 * @param priceMismatches pumps that charged off the sign price for longer than the grace
 */
public record DayEnd(
        BusinessDay day,
        List<PumpDay> pumps,
        long declines,
        long hostUnavailable,
        long alarmsRaised,
        long environmentalRaised,
        long unauthorised,
        long priceMismatches) {

    /**
     * One pump's day.
     *
     * @param pump the fuelling point
     * @param sales deliveries completed
     * @param litres litres those deliveries reported
     */
    public record PumpDay(String pump, long sales, BigDecimal litres) {}

    /** Copies the pumps. */
    public DayEnd {
        pumps = List.copyOf(pumps);
    }

    /** Cut a day's figures out of the records, keeping only those inside the day. */
    public static DayEnd of(BusinessDay day, List<StoredRecord> records) {
        List<StoredRecord> inside =
                records.stream().filter(record -> day.contains(record.bestAt())).toList();
        Map<String, long[]> sales = new TreeMap<>(Comparator.comparingInt(Integer::parseInt));
        Map<String, BigDecimal> litres = new TreeMap<>(Comparator.comparingInt(Integer::parseInt));
        long declines = 0;
        long unavailable = 0;
        for (StoredRecord record : inside) {
            Map<String, String> labels = record.labels();
            String pump = labels.get(FuellingPoint.LABEL);
            if (pump != null && PumpState.COMPLETED.value().equals(labels.get(PumpState.LABEL))) {
                sales.computeIfAbsent(pump, key -> new long[1])[0]++;
                String volume = labels.get(DeliveredVolume.LABEL);
                litres.merge(pump, volume == null ? BigDecimal.ZERO : new BigDecimal(volume), BigDecimal::add);
            }
            String outcome = labels.get(Outcome.LABEL);
            declines += Outcome.DECLINED.value().equals(outcome) ? 1 : 0;
            unavailable += Outcome.HOST_UNAVAILABLE.value().equals(outcome) ? 1 : 0;
        }
        List<AlarmSpan> alarms = AlarmLog.replay(inside).spans();
        long environmental = alarms.stream()
                .filter(span -> span.alarm().severity() == AlarmSeverity.ENVIRONMENTAL)
                .count();
        List<PumpDay> pumps = new ArrayList<>();
        sales.forEach((pump, count) -> pumps.add(new PumpDay(pump, count[0], litres.get(pump))));
        return new DayEnd(
                day,
                pumps,
                declines,
                unavailable,
                alarms.size(),
                environmental,
                PumpTimeline.replay(inside).unauthorised().size(),
                PriceAudit.replay(inside).mismatches(day.end()).size());
    }

    /** Every pump's sales together. */
    public long sales() {
        return pumps.stream().mapToLong(PumpDay::sales).sum();
    }

    /** Every pump's litres together. */
    public BigDecimal litres() {
        return pumps.stream().map(PumpDay::litres).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The figures as a site manager reads them at the end of the day. */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add("business day " + day);
        for (PumpDay pump : pumps) {
            out.add(String.format(
                    "  pump %-3s %5d sales %10s l",
                    pump.pump(), pump.sales(), pump.litres().toPlainString()));
        }
        out.add(String.format("  total    %5d sales %10s l", sales(), litres().toPlainString()));
        out.add("  payments " + declines + " declines, " + hostUnavailable + " unanswered");
        out.add("  alarms   " + alarmsRaised + " raised, " + environmentalRaised + " environmental");
        out.add("  pumps    " + unauthorised + " unauthorised deliveries, " + priceMismatches + " off the sign price");
        return out;
    }
}
