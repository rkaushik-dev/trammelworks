package io.github.rkaushikdev.trammelworks.forecourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ForecourtPaymentTest {

    static LogRecord payment(String message) {
        return new LogRecord(
                "US-TX-0417", "payment-kernel", Stream.STDOUT, NanoTime.parse("2025-03-04T12:00:00Z"), message);
    }

    @ParameterizedTest
    @CsvSource({
        "'auth response pump=3 rc=00 ref 551203', 00, approved",
        "'host reply resp: 51', 51, declined",
        "'DE39=\"05\" do not honour', 05, declined",
        "'response_code=10 partial 20.00', 10, approved",
        "'field39=91 issuer down', 91, host_unavailable",
        "'rc=01 call issuer', 01, referral",
        "'rc=43 hold card', 43, pick_up",
        "'chip decision rc=z3', Z3, declined",
        "'chip decision rc=Y3', Y3, approved"
    })
    void aResponseCodeIsReadAndJudged(String line, String code, String outcome) {
        ResponseCode found = ResponseCode.find(line).orElseThrow();
        assertThat(found.code()).isEqualTo(code);
        assertThat(found.outcome().value()).isEqualTo(outcome);
        assertThat(found.known()).isTrue();
    }

    @Test
    void anUnknownCodeCountsAsADecline() {
        ResponseCode found = ResponseCode.of("Q7");
        assertThat(found.known()).isFalse();
        assertThat(found.outcome()).isSameAs(Outcome.DECLINED);
        assertThat(found.meaning()).isEqualTo("not in the table");
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth approved ref 551203", "src=00", "rc=000", "batch rc closed"})
    void aLineWithoutAFieldThirtyNineHasNone(String line) {
        assertThat(ResponseCode.find(line)).isEmpty();
    }

    @Test
    void aPaymentLineGainsItsResponseAndOutcome() {
        LogRecord tagged = new Tagger(new ForecourtConfig()).tag(payment("auth response pump=3 rc=51 ref 551203"));
        assertThat(tagged.labels())
                .containsEntry("pump", "3")
                .containsEntry("response", "51")
                .containsEntry("outcome", "declined");
    }

    @Test
    void aResponseWithNoPumpIsStillJudged() {
        LogRecord tagged = new Tagger(new ForecourtConfig()).tag(payment("store and forward upload rc=00"));
        assertThat(tagged.labels()).containsOnlyKeys("response", "outcome");
    }

    @Test
    void aDispenserLineIsNotReadForAResponse() {
        LogRecord line = new LogRecord(
                "US-TX-0417", "pump-bridge", Stream.STDOUT, NanoTime.parse("2025-03-04T12:00:00Z"), "rc=00");
        assertThat(new Tagger(new ForecourtConfig()).tag(line)).isSameAs(line);
    }

    @ParameterizedTest
    @CsvSource({
        "'SAF depth 14 oldest 2025-03-04T06:10:00Z', 14",
        "'saf_pending=3', 3",
        "'store and forward queue: 0', 0",
        "'saf queue depth=120 uploading', 120"
    })
    void theStoreAndForwardDepthIsRead(String line, long depth) {
        assertThat(StoreAndForward.depth(line)).contains(depth);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SAF upload ok", "safe depth 4", "saf depth", "SAF depth 1234567"})
    void aLineWithoutAQueueDepthHasNone(String line) {
        assertThat(StoreAndForward.depth(line)).isEmpty();
    }

    @Test
    void aPaymentLineGainsTheQueueDepth() {
        LogRecord tagged = new Tagger(new ForecourtConfig()).tag(payment("host down, SAF depth 7 rc=91"));
        assertThat(tagged.labels()).containsEntry("saf_depth", "7").containsEntry("outcome", "host_unavailable");
    }

    @Test
    void anOutcomeIsFoundByItsLabelValue() {
        assertThat(Outcome.fromValue("host_unavailable")).contains(Outcome.HOST_UNAVAILABLE);
        assertThat(Outcome.fromValue("maybe")).isEmpty();
        assertThat(Outcome.PICK_UP).hasToString("pick_up");
    }
}
