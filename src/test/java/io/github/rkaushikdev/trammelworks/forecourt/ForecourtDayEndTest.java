package io.github.rkaushikdev.trammelworks.forecourt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ForecourtDayEndTest {

    static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    static final LocalTime FIVE = LocalTime.of(5, 0);

    static StoredRecord line(String unit, String at, String message) {
        return ForecourtRecords.tagged(unit, NanoTime.parse(at), message);
    }

    @ParameterizedTest
    @CsvSource({
        "2025-03-04T11:00:00Z, 2025-03-04",
        "2025-03-05T08:00:00Z, 2025-03-04",
        "2025-03-05T10:59:59Z, 2025-03-04",
        "2025-03-05T11:00:00Z, 2025-03-05"
    })
    void aSaleInTheSmallHoursBelongsToTheDayBefore(String at, String date) {
        assertThat(BusinessDay.containing(NanoTime.parse(at), CHICAGO, FIVE).date())
                .isEqualTo(LocalDate.parse(date));
    }

    @Test
    void aDayRunsFromCloseToCloseInSiteTime() {
        BusinessDay day = new BusinessDay(LocalDate.parse("2025-03-04"), CHICAGO, FIVE);
        assertThat(day.start()).isEqualTo(NanoTime.parse("2025-03-04T11:00:00Z"));
        assertThat(day.end()).isEqualTo(NanoTime.parse("2025-03-05T11:00:00Z"));
        assertThat(day.contains(day.start())).isTrue();
        assertThat(day.contains(day.end())).isFalse();
        assertThat(day).hasToString("2025-03-04 (closing 05:00 America/Chicago)");
    }

    @Test
    void theDayTheClocksGoForwardIsAnHourShort() {
        BusinessDay day = new BusinessDay(LocalDate.parse("2025-03-08"), CHICAGO, FIVE);
        assertThat(day.end().epochNs() - day.start().epochNs()).isEqualTo(23L * 3600 * NanoTime.NS_PER_SECOND);
    }

    @Test
    void theLastClosedDayIsTheOneBeforeToday() {
        assertThat(BusinessDay.lastClosed(NanoTime.parse("2025-03-05T09:00:00Z"), CHICAGO, FIVE)
                        .date())
                .isEqualTo(LocalDate.parse("2025-03-03"));
        ForecourtConfig config = new ForecourtConfig(Map.of(), CHICAGO, FIVE);
        assertThat(config.lastClosedDay(NanoTime.parse("2025-03-05T12:00:00Z")).date())
                .isEqualTo(LocalDate.parse("2025-03-04"));
        assertThat(config.dayOf(NanoTime.parse("2025-03-05T12:00:00Z")).date())
                .isEqualTo(LocalDate.parse("2025-03-05"));
    }

    @Test
    void theDayCloseIsReadAsWrittenOrAsMinutes() {
        assertThat(ForecourtConfig.dayClose("05:30")).isEqualTo(LocalTime.of(5, 30));
        assertThat(ForecourtConfig.dayClose(330)).isEqualTo(LocalTime.of(5, 30));
        ForecourtConfig read =
                ForecourtConfig.forecourtFromMapping(Map.of("timezone", "America/Chicago", "day_close", 300));
        assertThat(read.timezone()).isEqualTo(CHICAGO);
        assertThat(read.dayClose()).isEqualTo(FIVE);
        assertThat(read.asDict()).containsEntry("timezone", "America/Chicago").containsEntry("day_close", "05:00");
    }

    @Test
    void aSiteThatSaysNothingClosesAtMidnightUtc() {
        ForecourtConfig config = ForecourtConfig.forecourtFromMapping(Map.of());
        assertThat(config.timezone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(config.dayClose()).isEqualTo(LocalTime.MIDNIGHT);
    }

    @Test
    void aBadZoneOrCloseIsRefused() {
        assertThatThrownBy(() -> ForecourtConfig.forecourtFromMapping(Map.of("timezone", "Mars/Olympus")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown time zone");
        assertThatThrownBy(() -> ForecourtConfig.dayClose("5pm"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("HH:MM");
        assertThatThrownBy(() -> ForecourtConfig.dayClose(1440))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("time of day");
    }

    @Test
    void aCompletedDeliveryCarriesItsLitres() {
        assertThat(line("pump-bridge", "2025-03-04T12:00:00Z", "FP3 COMPLETED nozzle replaced, 41.2 litres")
                        .labels())
                .containsEntry("litres", "41.2");
        assertThat(line("pump-bridge", "2025-03-04T12:00:00Z", "FP3 FUELLING 12.0 l")
                        .labels())
                .doesNotContainKey("litres");
        assertThat(DeliveredVolume.read("31 ltr")).contains(new BigDecimal("31"));
        assertThat(DeliveredVolume.read("31 lbs")).isEmpty();
    }

    @Test
    void theDayEndCountsOnlyWhatFellInsideTheDay() {
        BusinessDay day = new BusinessDay(LocalDate.parse("2025-03-04"), CHICAGO, FIVE);
        List<StoredRecord> records = List.of(
                line("pump-bridge", "2025-03-04T10:59:00Z", "FP1 COMPLETED 99 litres"),
                line("pump-bridge", "2025-03-04T12:00:00Z", "FP1 AUTHORISED"),
                line("pump-bridge", "2025-03-04T12:01:00Z", "FP1 FUELLING"),
                line("pump-bridge", "2025-03-04T12:04:00Z", "FP1 COMPLETED 40.5 litres"),
                line("pump-bridge", "2025-03-05T09:30:00Z", "FP1 COMPLETED 10 litres"),
                line("pump-bridge", "2025-03-04T15:00:00Z", "FP10 COMPLETED 22.25 litres"),
                line("pump-bridge", "2025-03-04T15:30:00Z", "FP2 CALLING"),
                line("pump-bridge", "2025-03-04T15:30:10Z", "FP2 FUELLING"),
                line("payment-kernel", "2025-03-04T16:00:00Z", "auth response pump=1 rc=51 ref 100001"),
                line("payment-kernel", "2025-03-04T16:05:00Z", "auth response pump=1 rc=91 ref 100002"),
                line("tank-gauge", "2025-03-04T20:00:00Z", "T 1:SUDDEN LOSS ALARM"),
                line("tank-gauge", "2025-03-04T21:00:00Z", "T 2:LOW PRODUCT ALARM"),
                line("tank-gauge", "2025-03-05T12:00:00Z", "T 3:LEAK ALARM"));
        DayEnd figures = DayEnd.of(day, records);
        assertThat(figures.pumps())
                .containsExactly(
                        new DayEnd.PumpDay("1", 2, new BigDecimal("50.5")),
                        new DayEnd.PumpDay("10", 1, new BigDecimal("22.25")));
        assertThat(figures.sales()).isEqualTo(3);
        assertThat(figures.litres()).isEqualByComparingTo("72.75");
        assertThat(figures)
                .extracting(
                        DayEnd::declines,
                        DayEnd::hostUnavailable,
                        DayEnd::alarmsRaised,
                        DayEnd::environmentalRaised,
                        DayEnd::unauthorised,
                        DayEnd::priceMismatches)
                .containsExactly(1L, 1L, 2L, 1L, 1L, 0L);
        assertThat(figures.lines())
                .containsExactly(
                        "business day 2025-03-04 (closing 05:00 America/Chicago)",
                        "  pump 1       2 sales       50.5 l",
                        "  pump 10      1 sales      22.25 l",
                        "  total        3 sales      72.75 l",
                        "  payments 1 declines, 1 unanswered",
                        "  alarms   2 raised, 1 environmental",
                        "  pumps    1 unauthorised deliveries, 0 off the sign price");
    }
}
