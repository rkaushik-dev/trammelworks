package io.github.rkaushikdev.trammelworks.forecourt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ForecourtTaggerTest {

    static LogRecord record(String unit, String message) {
        return new LogRecord("US-TX-0417", unit, Stream.STDOUT, NanoTime.parse("2025-03-04T12:00:00Z"), message);
    }

    @Test
    void aRoleIsReadIgnoringCase() {
        assertThat(Role.parse(" Tank_Gauge ")).isSameAs(Role.TANK_GAUGE);
        assertThat(Role.DISPENSER).hasToString("dispenser");
    }

    @Test
    void anUnknownRoleIsRefused() {
        assertThatThrownBy(() -> Role.parse("pumps"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown forecourt role");
    }

    @Test
    void theReferenceBuildNeedsNoSection() {
        ForecourtConfig config = new ForecourtConfig();
        assertThat(config.roleOf("pump-bridge")).isSameAs(Role.DISPENSER);
        assertThat(config.roleOf("emv-kernel")).isSameAs(Role.PAYMENT);
        assertThat(config.roleOf("tank-gauge")).isSameAs(Role.TANK_GAUGE);
        assertThat(config.roleOf("price-sign")).isSameAs(Role.PRICE_SIGN);
        assertThat(config.roleOf("loyalty-sync")).isSameAs(Role.OTHER);
    }

    @Test
    void aSiteRoleBeatsTheDefault() {
        ForecourtConfig config = new ForecourtConfig(Map.of("pump-bridge", Role.OTHER, "FCC", Role.DISPENSER));
        assertThat(config.roleOf("pump-bridge")).isSameAs(Role.OTHER);
        assertThat(config.roleOf("fcc")).isSameAs(Role.DISPENSER);
    }

    @Test
    void theSectionIsReadFromTheFile() {
        ForecourtConfig config =
                ForecourtConfig.forecourtFromMapping(Map.of("roles", Map.of("fcc-bridge", "dispenser")));
        assertThat(config.roles()).containsEntry("fcc-bridge", Role.DISPENSER);
        assertThat(config.asDict()).isEqualTo(Map.of("roles", Map.of("fcc-bridge", "dispenser")));
    }

    @Test
    void anUnknownSectionKeyIsRefused() {
        assertThatThrownBy(() -> ForecourtConfig.forecourtFromMapping(Map.of("role", Map.of())))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown forecourt key");
    }

    @Test
    void aMalformedUnitNameIsRefused() {
        assertThatThrownBy(() -> new ForecourtConfig(Map.of("Pump Bridge!", Role.DISPENSER)))
                .isInstanceOf(ConfigError.class);
    }

    @ParameterizedTest
    @CsvSource({
        "'FP03 AUTHORISED grade 2', 3",
        "'pump=12 nozzle 1 lifted', 12",
        "'fp:7 fuelling', 7",
        "'Fuelling point 4 suspended', 4",
        "'dispenser #9 offline', 9",
        "'pump 0 reset then pump 2 calling', 2",
        "'PUMP 01', 1"
    })
    void aFuellingPointIsRead(String line, String point) {
        assertThat(FuellingPoint.find(line)).contains(point);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pump 0 reset", "pump 120 calling", "pumped 3 litres", "nozzle 1 lifted", "fp3x"})
    void aLineWithNoFuellingPointHasNone(String line) {
        assertThat(FuellingPoint.find(line)).isEmpty();
    }

    @Test
    void aDispenserLineIsTagged() {
        Tagger tagger = new Tagger(new ForecourtConfig());
        LogRecord tagged = tagger.tag(record("pump-bridge", "FP03 AUTHORISED"));
        assertThat(tagged.labels()).containsEntry(FuellingPoint.LABEL, "3");
        assertThat(tagger.tagged()).isEqualTo(1);
    }

    @Test
    void aPaymentLineNamingAPumpIsTagged() {
        Tagger tagger = new Tagger(new ForecourtConfig());
        assertThat(tagger.tag(record("payment-kernel", "preauth pump=4 approved"))
                        .labels())
                .containsEntry("pump", "4");
    }

    @Test
    void aTankGaugeLineIsNotReadForAPump() {
        Tagger tagger = new Tagger(new ForecourtConfig());
        LogRecord line = record("tank-gauge", "delivery on pump 3 line");
        assertThat(tagger.tag(line)).isSameAs(line);
        assertThat(tagger.tagged()).isZero();
    }

    @Test
    void aLabelTheContainerWroteIsNeverOverwritten() {
        Tagger tagger = new Tagger(new ForecourtConfig());
        LogRecord line = record("pump-bridge", "FP03 AUTHORISED").withLabel("pump", "03");
        assertThat(tagger.tag(line)).isSameAs(line);
        assertThat(tagger.labelsFor(line)).containsEntry("pump", "3");
    }

    @Test
    void theTaggerKeepsItsConfig() {
        ForecourtConfig config = new ForecourtConfig();
        assertThat(new Tagger(config).config()).isSameAs(config);
    }
}
