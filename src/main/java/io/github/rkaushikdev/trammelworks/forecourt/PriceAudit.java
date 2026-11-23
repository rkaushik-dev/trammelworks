package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replaying sign and dispenser prices and finding where they disagreed for longer than a price
 * change takes to reach both.
 */
public final class PriceAudit {

    /** How long a sign and a pump may disagree while a change propagates. */
    public static final Duration GRACE = Duration.ofMinutes(5);

    private final Map<Grade, BigDecimal> sign = new LinkedHashMap<>();
    private final Map<String, BigDecimal> pumps = new LinkedHashMap<>();
    private final Map<String, PriceMismatch> open = new LinkedHashMap<>();
    private final List<PriceMismatch> closed = new ArrayList<>();

    private static String key(String pump, Grade grade) {
        return pump + "/" + grade.value();
    }

    /**
     * Replay archived records in time order. A record with a grade and a price is the sign's when
     * it names no pump, and that pump's when it does.
     */
    public static PriceAudit replay(List<StoredRecord> records) {
        PriceAudit audit = new PriceAudit();
        records.stream().sorted(Comparator.comparing(StoredRecord::bestAt)).forEach(record -> {
            Map<String, String> labels = record.labels();
            Optional<Grade> grade = Optional.ofNullable(labels.get(Grade.LABEL)).flatMap(Grade::fromValue);
            String price = labels.get(FuelPrice.LABEL);
            if (grade.isEmpty() || price == null) {
                return;
            }
            FuelPrice reading = new FuelPrice(grade.get(), new BigDecimal(price));
            String pump = labels.get(FuellingPoint.LABEL);
            if (pump == null) {
                audit.signShows(reading, record.bestAt());
            } else {
                audit.pumpCharges(pump, reading, record.bestAt());
            }
        });
        return audit;
    }

    /** The sign now shows this price. */
    public void signShows(FuelPrice reading, NanoTime at) {
        sign.put(reading.grade(), reading.price());
        for (String key : List.copyOf(pumps.keySet())) {
            if (key.endsWith("/" + reading.grade().value())) {
                compare(key.substring(0, key.indexOf('/')), reading.grade(), at);
            }
        }
    }

    /** A pump now charges this price. */
    public void pumpCharges(String pump, FuelPrice reading, NanoTime at) {
        pumps.put(key(pump, reading.grade()), reading.price());
        compare(pump, reading.grade(), at);
    }

    private void compare(String pump, Grade grade, NanoTime at) {
        String key = key(pump, grade);
        BigDecimal shown = sign.get(grade);
        BigDecimal charged = pumps.get(key);
        boolean differs = shown != null && charged != null && shown.compareTo(charged) != 0;
        PriceMismatch standing = open.get(key);
        if (differs && standing == null) {
            open.put(key, new PriceMismatch(pump, grade, shown, charged, at, Optional.empty()));
        } else if (!differs && standing != null) {
            open.remove(key);
            closed.add(new PriceMismatch(
                    pump, grade, standing.sign(), standing.pumpPrice(), standing.since(), Optional.of(at)));
        }
    }

    /**
     * Every disagreement that outlasted {@link #GRACE}, closed ones first, then the ones still
     * open measured to {@code now}. Each carries the prices as they stood when it began.
     */
    public List<PriceMismatch> mismatches(NanoTime now) {
        List<PriceMismatch> out = new ArrayList<>(closed);
        out.addAll(open.values());
        return out.stream()
                .filter(item -> item.lasted(now).compareTo(GRACE) > 0)
                .toList();
    }
}
