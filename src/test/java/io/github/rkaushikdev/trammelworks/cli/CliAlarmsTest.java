package io.github.rkaushikdev.trammelworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliAlarmsTest {

    @TempDir
    Path tmp;

    Path config;

    @BeforeEach
    void setUp() {
        config = CliFixtures.forecourtConfig(tmp);
    }

    void fill(List<List<String>> lines) {
        CliFixtures.archiveTagged(tmp, lines);
    }

    @Test
    void aClearedAlarmIsListedWithHowLongItStood() {
        fill(List.of(
                List.of("tank-gauge", "10:00:00", "T 2:HIGH WATER ALARM"),
                List.of("tank-gauge", "10:05:00", "T 2:HIGH WATER ALARM"),
                List.of("tank-gauge", "10:45:00", "T 2:HIGH WATER ALARM CLEAR"),
                List.of("tank-gauge", "10:50:00", "T 2: volume 31200 ml"),
                List.of("pump-bridge", "10:55:00", "FP01 IDLE")));
        CliRunner.Result result = CliFixtures.invoke(config, "alarms");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().strip().lines())
                .containsExactly("T2 high_water     operational   2025-03-04T10:00:00.000000000Z "
                        + "2025-03-04T10:45:00.000000000Z 45m");
    }

    @Test
    void aStandingEnvironmentalAlarmExitsThree() {
        fill(List.of(
                List.of("tank-gauge", "09:00:00", "T 1:SUDDEN LOSS ALARM"),
                List.of("tank-gauge", "09:30:00", "T 3:LOW PRODUCT ALARM"),
                List.of("tank-gauge", "09:40:00", "T 3:LOW PRODUCT ALARM CLEAR")));
        CliRunner.Result result = CliFixtures.invoke(config, "alarms", "--open");
        assertThat(result.exitCode()).isEqualTo(AlarmsCommand.EXIT_STANDING);
        assertThat(result.stdout())
                .contains("T1 sudden_loss")
                .contains("STANDING")
                .doesNotContain("low_product");
    }

    @Test
    void aFilterNarrowsWhatIsRead() {
        fill(List.of(
                List.of("tank-gauge", "09:00:00", "T 1:LEAK ALARM"),
                List.of("tank-gauge", "09:30:00", "T 4:OVERFILL ALARM")));
        CliRunner.Result result = CliFixtures.invoke(config, "alarms", "label.tank = 4");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("T4 overfill").doesNotContain("T1");
    }

    @Test
    void anArchiveWithNoAlarmsSaysSo() {
        fill(List.of(List.of("tank-gauge", "09:00:00", "T 1: volume 31200 ml")));
        CliRunner.Result result = CliFixtures.invoke(config, "alarms");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("no alarms\n");
    }

    @Test
    void aSiteWithNoArchiveIsRefused() {
        Path bare = CliFixtures.writeConfig(tmp, "site: US-TX-0417\narchive:\n  enabled: false\n");
        CliRunner.Result result = CliFixtures.invoke(bare, "alarms");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("nothing to list alarms from");
    }
}
