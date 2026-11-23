package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import io.github.rkaushikdev.trammelworks.query.InSet;
import io.github.rkaushikdev.trammelworks.query.Node;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tank alarms as intervals, which is how a compliance inspector asks about them: when did the
 * sudden loss alarm on tank 2 go off, and how long before somebody cleared it.
 *
 * <p>A console repeats a standing alarm every poll, so the repeats are folded into the span they
 * belong to rather than counted as new alarms. A clear with no raise before it in what was read is
 * dropped: the raise is older than the window and the alarm is not news.
 */
public final class AlarmLog {

    private final Map<String, Builder> standing = new LinkedHashMap<>();
    private final List<AlarmSpan> closed = new ArrayList<>();

    private static final class Builder {
        private final String tank;
        private final TankAlarm alarm;
        private final NanoTime raisedAt;
        private int repeats;

        Builder(String tank, TankAlarm alarm, NanoTime raisedAt) {
            this.tank = tank;
            this.alarm = alarm;
            this.raisedAt = raisedAt;
        }

        AlarmSpan build(Optional<NanoTime> clearedAt) {
            return new AlarmSpan(tank, alarm, raisedAt, clearedAt, repeats);
        }
    }

    /** The filter that finds alarm records: every tank gauge line that named an alarm carries a state. */
    public static Node filter() {
        return new InSet("label." + TankAlarm.STATE_LABEL, List.of(TankAlarm.ACTIVE, TankAlarm.CLEAR));
    }

    /** Replay archived records, in corrected time order, keeping only those carrying an alarm. */
    public static AlarmLog replay(List<StoredRecord> records) {
        AlarmLog log = new AlarmLog();
        records.stream().sorted(Comparator.comparing(StoredRecord::bestAt)).forEach(record -> {
            Map<String, String> labels = record.labels();
            String tank = labels.get(TankAlarm.TANK_LABEL);
            Optional<TankAlarm> alarm =
                    Optional.ofNullable(labels.get(TankAlarm.LABEL)).flatMap(TankAlarm::fromValue);
            String state = labels.get(TankAlarm.STATE_LABEL);
            if (tank != null && alarm.isPresent() && state != null) {
                log.feed(tank, alarm.get(), record.bestAt(), TankAlarm.CLEAR.equals(state));
            }
        });
        return log;
    }

    /** One alarm line. */
    public void feed(String tank, TankAlarm alarm, NanoTime at, boolean cleared) {
        String key = tank + "/" + alarm.value();
        Builder current = standing.get(key);
        if (cleared) {
            if (current != null) {
                closed.add(standing.remove(key).build(Optional.of(at)));
            }
            return;
        }
        if (current == null) {
            standing.put(key, new Builder(tank, alarm, at));
        } else {
            current.repeats++;
        }
    }

    /** Every span, cleared and standing, in the order they were raised. */
    public List<AlarmSpan> spans() {
        List<AlarmSpan> out = new ArrayList<>(closed);
        standing.values().forEach(builder -> out.add(builder.build(Optional.empty())));
        out.sort(Comparator.comparing(AlarmSpan::raisedAt).thenComparing(AlarmSpan::tank));
        return List.copyOf(out);
    }

    /** Only the alarms still standing. */
    public List<AlarmSpan> open() {
        return spans().stream().filter(AlarmSpan::open).toList();
    }
}
