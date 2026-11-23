package io.github.rkaushikdev.trammelworks.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.ShipError;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShipMeterTest {

    static NanoTime at(String raw) {
        return NanoTime.parse(raw);
    }

    static Meter meter() {
        return new Meter(1000, 14);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 14, 28})
    void aSaneAnchorDayIsAccepted(int day) {
        assertThat(Meter.checkAnchor(day)).isEqualTo(day);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 29, 31, -1})
    void anAnchorDayNoMonthHasIsRefused(int day) {
        assertThatThrownBy(() -> Meter.checkAnchor(day))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("anchor day must be 1 to 28");
    }

    @Test
    void thePeriodStartsOnTheAnchorDay() {
        assertThat(Meter.periodStart(at("2025-03-20T12:00:00Z"), 14)).isEqualTo(at("2025-03-14T00:00:00Z"));
    }

    @Test
    void aDateBeforeTheAnchorBelongsToThePreviousPeriod() {
        assertThat(Meter.periodStart(at("2025-03-05T12:00:00Z"), 14)).isEqualTo(at("2025-02-14T00:00:00Z"));
    }

    @Test
    void theAnchorDayItselfStartsTheNewPeriod() {
        assertThat(Meter.periodStart(at("2025-03-14T00:00:00Z"), 14)).isEqualTo(at("2025-03-14T00:00:00Z"));
    }

    @Test
    void januaryRollsBackIntoDecember() {
        assertThat(Meter.periodStart(at("2025-01-05T12:00:00Z"), 14)).isEqualTo(at("2024-12-14T00:00:00Z"));
    }

    @Test
    void thePeriodEndsOneMonthOn() {
        assertThat(Meter.periodEnd(at("2025-03-14T00:00:00Z"), 14)).isEqualTo(at("2025-04-14T00:00:00Z"));
    }

    @Test
    void decemberEndsInJanuary() {
        assertThat(Meter.periodEnd(at("2025-12-14T00:00:00Z"), 14)).isEqualTo(at("2026-01-14T00:00:00Z"));
    }

    @Test
    void aFreshMeterHasTheWholeAllowance() {
        assertThat(meter().remaining()).isEqualTo(1000);
    }

    @Test
    void aNonPositiveAllowanceIsRefused() {
        assertThatThrownBy(() -> new Meter(0, 14))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("allowance must be positive");
    }

    @Test
    void aFloorOutsideTheRangeIsRefused() {
        assertThatThrownBy(() -> new Meter(1000, 14, Map.of("payment", 1.0)))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("floor must be in");
    }

    @Test
    void chargingSpendsTheAllowance() {
        Meter built = meter();
        built.charge(300);
        assertThat(built.usedBytes()).isEqualTo(300);
        assertThat(built.remaining()).isEqualTo(700);
    }

    @Test
    void aRequestCostsItsBodyAndItsOverhead() {
        Meter built = new Meter(1000, 1, Meter.DEFAULT_FLOORS, 120);
        assertThat(built.requestOverhead()).isEqualTo(120);
        assertThat(built.requestCost(300)).isEqualTo(420);
        assertThat(new Meter(1000, 1).requestCost(300)).isEqualTo(300);
    }

    @Test
    void aNegativeOverheadIsRefused() {
        assertThatThrownBy(() -> new Meter(1000, 1, Meter.DEFAULT_FLOORS, -1))
                .isInstanceOf(io.github.rkaushikdev.trammelworks.ShipError.class)
                .hasMessageContaining("overhead");
    }

    @Test
    void chargingANegativeSizeIsRefused() {
        assertThatThrownBy(() -> meter().charge(-1))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("negative size");
    }

    @Test
    void remainingNeverGoesBelowZero() {
        Meter built = meter();
        built.charge(5000);
        assertThat(built.remaining()).isZero();
    }

    @Test
    void theSpentRatioFollowsTheCharges() {
        Meter built = meter();
        built.charge(250);
        assertThat(built.spentRatio()).isEqualTo(0.25);
    }

    @Test
    void paymentMaySpendTheWholeAllowance() {
        Meter built = meter();
        built.charge(900);
        assertThat(built.allows(100, "payment")).isTrue();
    }

    @Test
    void chatterStopsAtItsFloor() {
        Meter built = meter();
        built.charge(600);
        assertThat(built.availableTo("chatter")).isEqualTo(100);
        assertThat(built.allows(200, "chatter")).isFalse();
    }

    @Test
    void standardHasASmallerFloorThanChatter() {
        Meter built = meter();
        built.charge(600);
        assertThat(built.availableTo("standard")).isGreaterThan(built.availableTo("chatter"));
    }

    @Test
    void theFloorLeavesRoomForPaymentAfterChatterStops() {
        Meter built = meter();
        built.charge(750);
        assertThat(built.allows(100, "chatter")).isFalse();
        assertThat(built.allows(100, "payment")).isTrue();
    }

    @Test
    void anUnknownClassHasNoFloor() {
        Meter built = meter();
        built.charge(900);
        assertThat(built.allows(100, "brand-new")).isTrue();
    }

    @Test
    void aNegativeSizeIsRefusedByAllows() {
        assertThatThrownBy(() -> meter().allows(-1))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("negative size");
    }

    @Test
    void aSendExactlyTheSizeOfWhatIsLeftIsAllowed() {
        Meter built = meter();
        built.charge(900);
        assertThat(built.allows(100, "payment")).isTrue();
        assertThat(built.allows(101, "payment")).isFalse();
    }

    @Test
    void rollingStartsANewPeriod() {
        Meter built = meter();
        assertThat(built.roll(at("2025-03-20T12:00:00Z"))).isTrue();
        assertThat(built.startedAt()).isEqualTo(at("2025-03-14T00:00:00Z"));
    }

    @Test
    void rollingTwiceInOnePeriodDoesNothing() {
        Meter built = meter();
        built.roll(at("2025-03-20T12:00:00Z"));
        built.charge(500);
        assertThat(built.roll(at("2025-03-25T12:00:00Z"))).isFalse();
        assertThat(built.usedBytes()).isEqualTo(500);
    }

    @Test
    void crossingTheAnchorClearsTheUsage() {
        Meter built = meter();
        built.roll(at("2025-03-20T12:00:00Z"));
        built.charge(500);
        assertThat(built.roll(at("2025-04-14T00:00:01Z"))).isTrue();
        assertThat(built.usedBytes()).isZero();
    }

    @Test
    void theRolloverCountGrows() {
        Meter built = meter();
        built.roll(at("2025-03-20T12:00:00Z"));
        built.roll(at("2025-04-20T12:00:00Z"));
        assertThat(built.rollovers()).isEqualTo(2);
    }

    @Test
    void thePeriodEndFollowsTheStart() {
        Meter built = meter();
        built.roll(at("2025-03-20T12:00:00Z"));
        assertThat(built.endsAt()).isEqualTo(at("2025-04-14T00:00:00Z"));
    }

    @Test
    void aLateReadingDoesNotRollBackwards() {
        Meter built = meter();
        built.roll(at("2025-04-20T12:00:00Z"));
        built.charge(400);
        assertThat(built.roll(at("2025-03-20T12:00:00Z"))).isFalse();
        assertThat(built.usedBytes()).isEqualTo(400);
    }

    @Test
    void describeReadsAsOneLine() {
        Meter built = meter();
        built.charge(250);
        assertThat(built.describe()).isEqualTo("meter 250/1000 bytes (25.0%) this period");
    }

    @Test
    void describeNamesTheOverheadWhenThereIsOne() {
        Meter built = new Meter(1000, 1, Meter.DEFAULT_FLOORS, 120);
        built.charge(built.requestCost(130));
        assertThat(built.describe()).isEqualTo("meter 250/1000 bytes (25.0%) this period, 120 per request");
    }
}
