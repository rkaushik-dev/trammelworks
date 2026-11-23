package io.github.rkaushikdev.trammelworks.forecourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ForecourtAlarmLogTest {

    static final NanoTime NOON = NanoTime.parse("2025-03-04T12:00:00Z");

    static NanoTime at(int minutes) {
        return NOON.shifted(minutes * 60 * NanoTime.NS_PER_SECOND);
    }

    static StoredRecord stored(int minutes, String tank, String alarm, String state) {
        return ForecourtRecords.labelled(
                "tank-gauge", at(minutes), Map.of("tank", tank, "alarm", alarm, "alarm_state", state));
    }

    @Test
    void aRaiseAndAClearMakeOneSpan() {
        AlarmLog log = new AlarmLog();
        log.feed("2", TankAlarm.HIGH_WATER, at(0), false);
        log.feed("2", TankAlarm.HIGH_WATER, at(45), true);
        assertThat(log.spans())
                .containsExactly(new AlarmSpan("2", TankAlarm.HIGH_WATER, at(0), Optional.of(at(45)), 0));
        assertThat(log.spans().get(0).standing(at(90))).isEqualTo(Duration.ofMinutes(45));
        assertThat(log.open()).isEmpty();
    }

    @Test
    void repeatsOfAStandingAlarmAreFoldedIntoIt() {
        AlarmLog log = new AlarmLog();
        log.feed("1", TankAlarm.SUDDEN_LOSS, at(0), false);
        log.feed("1", TankAlarm.SUDDEN_LOSS, at(5), false);
        log.feed("1", TankAlarm.SUDDEN_LOSS, at(10), false);
        AlarmSpan span = log.open().get(0);
        assertThat(span.repeats()).isEqualTo(2);
        assertThat(span.raisedAt()).isEqualTo(at(0));
        assertThat(span.standing(at(30))).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aClearWithNoRaiseIsDropped() {
        AlarmLog log = new AlarmLog();
        log.feed("1", TankAlarm.LEAK, at(0), true);
        assertThat(log.spans()).isEmpty();
    }

    @Test
    void tanksAndAlarmsAreKeptApart() {
        AlarmLog log = new AlarmLog();
        log.feed("1", TankAlarm.LEAK, at(0), false);
        log.feed("2", TankAlarm.LEAK, at(1), false);
        log.feed("1", TankAlarm.HIGH_WATER, at(2), false);
        log.feed("1", TankAlarm.LEAK, at(3), true);
        assertThat(log.spans()).extracting(AlarmSpan::tank).containsExactly("1", "2", "1");
        assertThat(log.open()).extracting(AlarmSpan::alarm).containsExactly(TankAlarm.LEAK, TankAlarm.HIGH_WATER);
    }

    @Test
    void anAlarmRaisedAgainAfterClearingIsANewSpan() {
        AlarmLog log = new AlarmLog();
        log.feed("3", TankAlarm.OVERFILL, at(0), false);
        log.feed("3", TankAlarm.OVERFILL, at(2), true);
        log.feed("3", TankAlarm.OVERFILL, at(9), false);
        assertThat(log.spans()).hasSize(2);
        assertThat(log.spans().get(1).open()).isTrue();
    }

    @Test
    void archivedRecordsAreReplayedInTimeOrder() {
        List<StoredRecord> records = List.of(
                stored(30, "1", "leak", "clear"), stored(0, "1", "leak", "active"), stored(10, "1", "bogus", "active"));
        AlarmLog log = AlarmLog.replay(records);
        assertThat(log.spans()).containsExactly(new AlarmSpan("1", TankAlarm.LEAK, at(0), Optional.of(at(30)), 0));
    }
}
