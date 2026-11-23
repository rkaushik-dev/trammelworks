package io.github.rkaushikdev.trammelworks.forecourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ForecourtTankTest {

    static LogRecord gauge(String message) {
        return new LogRecord(
                "US-TX-0417", "tank-gauge", Stream.STDOUT, NanoTime.parse("2025-03-04T12:00:00Z"), message);
    }

    @ParameterizedTest
    @CsvSource({
        "'T 1:SUDDEN LOSS ALARM', 1, sudden_loss, active",
        "'T 2:HIGH WATER ALARM CLEAR', 2, high_water, clear",
        "'tank 03 overfill alarm', 3, overfill, active",
        "'T4: DELIVERY NEEDED', 4, low_product, active",
        "'T 1:PERIODIC TEST FAIL', 1, test_fail, active",
        "'T 12:PROBE OUT cleared', 12, probe_out, clear",
        "'TANK 5  LEAK   ALARM', 5, leak, active",
        "'T 2:INVALID FUEL LEVEL', 2, invalid_level, active"
    })
    void anAlarmLineIsRead(String line, String tank, String alarm, String state) {
        TankAlarm.Reading reading = TankAlarm.read(line).orElseThrow();
        assertThat(reading.tank()).isEqualTo(tank);
        assertThat(reading.alarm().value()).isEqualTo(alarm);
        assertThat(reading.state()).isEqualTo(state);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "T 1: volume 31200 ml temp 14C",
                "SUDDEN LOSS ALARM",
                "T 0:LEAK ALARM",
                "T 100:LEAK ALARM",
                "probe 3 ok"
            })
    void aLineWithoutATankAndAnAlarmIsNotAnAlarm(String line) {
        assertThat(TankAlarm.read(line)).isEmpty();
    }

    @Test
    void theFirstAlarmNamedOnTheLineWins() {
        assertThat(TankAlarm.read("T 1:HIGH WATER ALARM after LEAK ALARM")
                        .orElseThrow()
                        .alarm())
                .isSameAs(TankAlarm.HIGH_WATER);
    }

    @Test
    void releaseDetectionAlarmsAreEnvironmental() {
        assertThat(TankAlarm.LEAK.severity()).isSameAs(AlarmSeverity.ENVIRONMENTAL);
        assertThat(TankAlarm.SUDDEN_LOSS.severity()).isSameAs(AlarmSeverity.ENVIRONMENTAL);
        assertThat(TankAlarm.TEST_FAIL.severity()).isSameAs(AlarmSeverity.ENVIRONMENTAL);
        assertThat(TankAlarm.INTERSTITIAL.severity()).isSameAs(AlarmSeverity.ENVIRONMENTAL);
        assertThat(TankAlarm.OVERFILL.severity()).isSameAs(AlarmSeverity.OPERATIONAL);
        assertThat(TankAlarm.PROBE_OUT.severity()).isSameAs(AlarmSeverity.EQUIPMENT);
        assertThat(AlarmSeverity.ENVIRONMENTAL).hasToString("environmental");
    }

    @Test
    void anAlarmIsFoundByItsLabelValue() {
        assertThat(TankAlarm.fromValue("sudden_loss")).contains(TankAlarm.SUDDEN_LOSS);
        assertThat(TankAlarm.fromValue("SUDDEN_LOSS")).isEmpty();
        assertThat(TankAlarm.OVERFILL.phrases()).contains("max product alarm");
        assertThat(TankAlarm.OVERFILL).hasToString("overfill");
    }

    @Test
    void aGaugeLineGainsTheTankAndTheAlarm() {
        assertThat(new Tagger(new ForecourtConfig())
                        .tag(gauge("T 1:SUDDEN LOSS ALARM"))
                        .labels())
                .containsEntry("tank", "1")
                .containsEntry("alarm", "sudden_loss")
                .containsEntry("alarm_state", "active")
                .containsEntry("alarm_severity", "environmental");
    }

    @Test
    void anInventoryLineGainsNothing() {
        LogRecord line = gauge("T 1: volume 31200 ml");
        assertThat(new Tagger(new ForecourtConfig()).tag(line)).isSameAs(line);
    }

    @Test
    void aDispenserLineIsNotReadForAnAlarm() {
        LogRecord line = new LogRecord(
                "US-TX-0417", "pump-bridge", Stream.STDOUT, NanoTime.parse("2025-03-04T12:00:00Z"), "T 1:LEAK ALARM");
        assertThat(new Tagger(new ForecourtConfig()).labelsFor(line)).isEmpty();
    }
}
