package io.github.rkaushikdev.trammelworks.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.NanoTime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShipConfigTest {

    @TempDir
    Path tmp;

    MeterStore store;

    @BeforeEach
    void setUp() {
        store = new MeterStore(tmp.resolve("state"));
    }

    @Test
    void theDefaultsBuild() {
        ShipSettings built = new ShipSettings();
        assertThat(built.endpoint()).startsWith("https://");
        assertThat(built.meter().allowanceBytes()).isEqualTo(2_000_000_000L);
    }

    @Test
    void aNonUrlEndpointIsRefused() {
        assertThatThrownBy(() ->
                        ShipSettings.builder().endpoint("collector.invalid").build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be an http or file url");
    }

    @Test
    void aFileEndpointIsAllowed() {
        ShipSettings built =
                ShipSettings.builder().endpoint("file:///var/spool/outbox").build();
        assertThat(built.toFile()).isTrue();
        assertThat(built.fileDirectory()).isEqualTo(Path.of("/var/spool/outbox"));
    }

    @Test
    void anHttpEndpointHasNoDirectory() {
        assertThatThrownBy(() -> new ShipSettings().fileDirectory())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void aZeroTimeoutIsRefused() {
        assertThatThrownBy(() ->
                        ShipSettings.builder().attemptTimeout(Duration.ZERO).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("attempt_timeout must be positive");
    }

    @Test
    void aZeroIntervalIsRefused() {
        assertThatThrownBy(() -> ShipSettings.builder().interval(Duration.ZERO).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("interval must be positive");
    }

    @Test
    void aBatchBiggerThanTheMonthIsRefused() {
        assertThatThrownBy(() -> ShipSettings.builder()
                        .monthlyAllowanceBytes(1000)
                        .batchBytes(4000)
                        .build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("cannot exceed the monthly allowance");
    }

    @Test
    void aBadAnchorDayIsRefusedAsAConfigError() {
        assertThatThrownBy(() -> ShipSettings.builder().anchorDay(31).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("anchor day must be 1 to 28");
    }

    @Test
    void aBadFloorIsRefusedAsAConfigError() {
        assertThatThrownBy(() ->
                        ShipSettings.builder().floors(Map.of("payment", 1.5)).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("floor must be in");
    }

    @Test
    void theLimitsComeFromTheSettings() {
        ShipSettings built =
                ShipSettings.builder().batchBytes(4096).batchRecords(40).build();
        assertThat(built.limits().maxBytes()).isEqualTo(4096);
        assertThat(built.limits().maxRecords()).isEqualTo(40);
    }

    @Test
    void theRequestOverheadIsReadInBytesAndReachesTheMeter() {
        ShipSettings settings = ShipSettings.shipSettingsFromMapping(Map.of("request_overhead", "6KiB"));
        assertThat(settings.requestOverheadBytes()).isEqualTo(6144);
        assertThat(settings.meter().requestCost(1000)).isEqualTo(7144);
        assertThat(new ShipSettings().requestOverheadBytes()).isZero();
    }

    @Test
    void anOverheadAsBigAsABatchIsRefused() {
        assertThatThrownBy(
                        () -> ShipSettings.shipSettingsFromMapping(Map.of("batch", "8KiB", "request_overhead", "8KiB")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("request_overhead must be under the batch size");
        assertThatThrownBy(() -> ShipSettings.builder().requestOverheadBytes(-1).build())
                .isInstanceOf(ConfigError.class);
    }

    @Test
    void theFloorsReachTheMeter() {
        assertThat(ShipSettings.builder()
                        .floors(Map.of("chatter", 0.5))
                        .build()
                        .meter()
                        .floorFor("chatter"))
                .isEqualTo(0.5);
    }

    @Test
    void theMappingBuilderReadsWrittenUnits() {
        ShipSettings built = ShipSettings.shipSettingsFromMapping(
                Maps.of("monthly_allowance", "5GB", "batch", "64KiB", "attempt_timeout", "1m", "interval", "10m"));
        assertThat(built.monthlyAllowanceBytes()).isEqualTo(5_000_000_000L);
        assertThat(built.batchBytes()).isEqualTo(65536);
        assertThat(built.attemptTimeout()).isEqualTo(Duration.ofMinutes(1));
        assertThat(built.interval()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void anEmptySectionGivesTheDefaults() {
        assertThat(ShipSettings.shipSettingsFromMapping(Map.of())).isEqualTo(new ShipSettings());
    }

    @Test
    void theMappingBuilderRejectsAnUnknownKey() {
        assertThatThrownBy(() -> ShipSettings.shipSettingsFromMapping(Map.of("endpiont", "https://x/y")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown ship key");
    }

    @Test
    void theMappingBuilderNeedsFloorsToBeAMapping() {
        assertThatThrownBy(() -> ShipSettings.shipSettingsFromMapping(Map.of("floors", List.of("payment"))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("the floors section must be a mapping");
    }

    @Test
    void theMappingBuilderReadsFloors() {
        assertThat(ShipSettings.shipSettingsFromMapping(Map.of("floors", Map.of("chatter", 0.4)))
                        .meter()
                        .floorFor("chatter"))
                .isEqualTo(0.4);
    }

    @Test
    void loadingWithNothingStoredGivesAFreshMeter() {
        Meter meter = store.load(new ShipSettings());
        assertThat(meter.usedBytes()).isZero();
        assertThat(meter.rollovers()).isZero();
    }

    @Test
    void aSavedMeterComesBack() {
        Meter meter = new ShipSettings().meter();
        meter.roll(NanoTime.parse("2025-03-04T12:00:00Z"));
        meter.charge(4096);
        store.save(meter);
        Meter restored = store.load(new ShipSettings());
        assertThat(restored.usedBytes()).isEqualTo(4096);
        assertThat(restored.startedAt()).isEqualTo(NanoTime.parse("2025-03-01T00:00:00Z"));
        assertThat(restored.rollovers()).isEqualTo(1);
    }

    @Test
    void saveCreatesTheDirectory() {
        MeterStore deep = new MeterStore(tmp.resolve("deep").resolve("state"));
        deep.save(new ShipSettings().meter());
        assertThat(deep.path()).exists();
    }

    @Test
    void saveLeavesNoTemporaryFile() throws IOException {
        store.save(new ShipSettings().meter());
        try (Stream<Path> found = Files.list(store.directory())) {
            assertThat(found.map(path -> path.getFileName().toString())).containsExactly("meter.json");
        }
    }

    @Test
    void theAllowanceAlwaysComesFromTheConfig() {
        Meter meter = ShipSettings.builder()
                .monthlyAllowanceBytes(500_000)
                .batchBytes(1024)
                .build()
                .meter();
        meter.roll(NanoTime.parse("2025-03-04T12:00:00Z"));
        meter.charge(500);
        store.save(meter);
        Meter restored = store.load(
                ShipSettings.builder().monthlyAllowanceBytes(9_000_000).build());
        assertThat(restored.allowanceBytes()).isEqualTo(9_000_000);
        assertThat(restored.usedBytes()).isEqualTo(500);
    }

    @Test
    void aCorruptMeterFileStartsFresh() throws IOException {
        store.save(new ShipSettings().meter());
        Files.writeString(store.path(), "{broken");
        assertThat(store.load(new ShipSettings()).usedBytes()).isZero();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void aMeterFileFromAnotherVersionStartsFresh() throws IOException {
        Files.createDirectories(store.directory());
        Files.writeString(store.path(), Json.dumps(Maps.of("version", 9, "used_bytes", 4)));
        assertThat(store.load(new ShipSettings()).usedBytes()).isZero();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void aMeterFileMissingAFieldStartsFresh() throws IOException {
        Files.createDirectories(store.directory());
        Files.writeString(store.path(), Json.dumps(Maps.of("version", 1, "used_bytes", 4)));
        assertThat(store.load(new ShipSettings()).usedBytes()).isZero();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void theSpendSurvivesARestartInsideOnePeriod() {
        ShipSettings settings = new ShipSettings();
        Meter meter = settings.meter();
        meter.roll(NanoTime.parse("2025-03-04T12:00:00Z"));
        meter.charge(1_000_000);
        store.save(meter);
        Meter restored = store.load(settings);
        assertThat(restored.roll(NanoTime.parse("2025-03-05T12:00:00Z"))).isFalse();
        assertThat(restored.usedBytes()).isEqualTo(1_000_000);
    }
}
