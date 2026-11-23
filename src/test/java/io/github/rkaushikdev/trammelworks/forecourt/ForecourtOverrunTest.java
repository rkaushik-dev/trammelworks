package io.github.rkaushikdev.trammelworks.forecourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ForecourtOverrunTest {

    static final NanoTime NOON = NanoTime.parse("2025-03-04T12:00:00Z");

    static StoredRecord stored(int minutes, String message) {
        return ForecourtRecords.tagged("payment-kernel", NOON.shifted(minutes * 60 * NanoTime.NS_PER_SECOND), message);
    }

    @ParameterizedTest
    @CsvSource({
        "'preauth pump=3 amount=100.00 ref 551203', preauth, 100.00, 551203",
        "'Pre-Authorization approved amount: 75 ref=A1B2C3', preauth, 75, A1B2C3",
        "'completion pump=3 amount=62.18 ref 551203', completion, 62.18, 551203",
        "'COMPLETION sent amount=4000 ref #889012', completion, 4000, 889012"
    })
    void eitherHalfOfASaleIsRead(String line, String stage, String amount, String reference) {
        assertThat(AuthStage.read(line)).contains(new AuthStage(stage, amount, reference));
    }

    @ParameterizedTest
    @ValueSource(strings = {"preauth pump=3 ref 551203", "completion amount=10.00", "auth request amount=40 ref 1234"})
    void aLineMissingTheAmountOrReferenceIsNotAHalf(String line) {
        assertThat(AuthStage.read(line)).isEmpty();
    }

    @Test
    void aPaymentLineGainsTheStageAmountAndReference() {
        assertThat(stored(0, "preauth pump=3 amount=100.00 ref 551203").labels())
                .containsEntry("auth_stage", "preauth")
                .containsEntry("amount", "100.00")
                .containsEntry("ref", "551203")
                .containsEntry("pump", "3");
    }

    @Test
    void aCompletionWithinItsHoldIsFine() {
        assertThat(Overrun.find(List.of(
                        stored(0, "preauth pump=3 amount=100.00 ref 551203"),
                        stored(4, "completion pump=3 amount=100.00 ref 551203"))))
                .isEmpty();
    }

    @Test
    void aCompletionAboveItsHoldIsAnOverrun() {
        List<Overrun> found = Overrun.find(List.of(
                stored(4, "completion pump=3 amount=102.35 ref 551203"),
                stored(0, "preauth pump=3 amount=100.00 ref 551203")));
        assertThat(found).hasSize(1);
        Overrun overrun = found.get(0);
        assertThat(overrun.pump()).isEqualTo("3");
        assertThat(overrun.authorised()).contains(new BigDecimal("100.00"));
        assertThat(overrun.excess()).isEqualByComparingTo("2.35");
    }

    @Test
    void aCompletionWithNoHoldIsAnOverrunOfItsWholeAmount() {
        List<Overrun> found = Overrun.find(List.of(stored(0, "completion amount=40.10 ref 771100")));
        assertThat(found.get(0).authorised()).isEmpty();
        assertThat(found.get(0).excess()).isEqualByComparingTo("40.10");
        assertThat(found.get(0).pump()).isEmpty();
    }

    @Test
    void aHoldIsSpentByItsCompletion() {
        List<Overrun> found = Overrun.find(List.of(
                stored(0, "preauth amount=50 ref 100001"),
                stored(1, "completion amount=20 ref 100001"),
                stored(2, "completion amount=20 ref 100001")));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).at()).isEqualTo(NOON.shifted(120 * NanoTime.NS_PER_SECOND));
    }

    @Test
    void recordsWithoutAStageAreIgnored() {
        assertThat(Overrun.find(List.of(stored(0, "auth response pump=3 rc=00 ref 551203"))))
                .isEmpty();
    }
}
