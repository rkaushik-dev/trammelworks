package io.github.rkaushikdev.trammelworks.redact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RedactEngineTest {

    static final String VISA = "4111111111111111";
    static final String BARE2 = "4111111111111111=270310112345";
    static final Rule DRIVER = new Rule("driver_id", "driver=(\\d{4,10})");

    static LogRecord record(String message) {
        return new LogRecord(
                "US-TX-0417", "payment-kernel", Stream.STDOUT, NanoTime.parse("2025-03-04T12:00:00Z"), message);
    }

    @Test
    void aCleanLineComesBackUnchanged() {
        RedactionResult found = new Redactor().redactText("nozzle 1 lifted");
        assertThat(found.text()).isEqualTo("nozzle 1 lifted");
        assertThat(found.changed()).isFalse();
    }

    @Test
    void aPanIsMasked() {
        RedactionResult found = new Redactor().redactText("auth pan=" + VISA);
        assertThat(found.text()).isEqualTo("auth pan=411111******1111");
        assertThat(found.pans()).isEqualTo(1);
    }

    @Test
    void aStripeGoesBeforeThePanScanSeesIt() {
        RedactionResult found = new Redactor().redactText("swipe " + BARE2);
        assertThat(found.text()).isEqualTo("swipe [redacted]");
        assertThat(found.stripes()).isEqualTo(1);
        assertThat(found.pans()).isZero();
    }

    @Test
    void theExpiryDoesNotSurviveAStripe() {
        RedactionResult found = new Redactor().redactText("swipe " + BARE2);
        assertThat(found.text()).doesNotContain("2703").doesNotContain("411111");
    }

    @Test
    void aPanBesideAStripeIsStillMasked() {
        RedactionResult found = new Redactor().redactText("raw=" + BARE2 + " pan=" + VISA);
        assertThat(found.stripes()).isEqualTo(1);
        assertThat(found.pans()).isEqualTo(1);
        assertThat(found.text()).contains("411111******1111");
    }

    @Test
    void siteRulesRunAfterTheCardRules() {
        RedactionResult found = new Redactor(List.of(DRIVER)).redactText("pan=" + VISA + " driver=88213");
        assertThat(found.text()).isEqualTo("pan=411111******1111 driver=[redacted]");
    }

    @Test
    void aSiteRuleSeesTextWithTheCardAlreadyOut() {
        // A rule written against the card number can only ever see the six digits the mask left.
        // The full PAN is gone before any site rule runs, which is the whole reason the order is
        // fixed.
        Rule catchAll = new Rule("anything", "4111\\d+");
        RedactionResult found = new Redactor(List.of(catchAll)).redactText("pan=" + VISA);
        assertThat(found.hits()).extracting(RuleHit::text).containsExactly("411111");
        assertThat(found.text()).isEqualTo("pan=[redacted]******1111");
    }

    @Test
    void theRulesRunInTheOrderTheyWereGiven() {
        Rule first = Rule.drop("first", "driver=(\\d+)", "<one>");
        Rule second = Rule.drop("second", "driver=(\\d+)", "<two>");
        RedactionResult found = new Redactor(List.of(first, second)).redactText("driver=88213");
        assertThat(found.text()).isEqualTo("driver=<one>");
    }

    @Test
    void theKeepLengthIsConfigurable() {
        RedactionResult found = new Redactor(List.of(), 0, 4).redactText("pan=" + VISA);
        assertThat(found.text()).isEqualTo("pan=************1111");
    }

    @Test
    void anEightDigitHeadIsKeptOnASixteenDigitCard() {
        RedactionResult found = new Redactor(List.of(), 8, 4).redactText("pan=" + VISA);
        assertThat(found.text()).isEqualTo("pan=41111111****1111");
    }

    @Test
    void anAmexFallsBackToSixWhenEightAreAskedFor() {
        RedactionResult found = new Redactor(List.of(), new PanMask(8, 4)).redactText("pan=378282246310005");
        assertThat(found.text()).isEqualTo("pan=378282*****0005");
    }

    @Test
    void aPassBuiltFromConfigCarriesItsMask() {
        Redactor built = Redactor.of(new RedactConfig(true, 8, 2, List.of()));
        assertThat(built.mask()).isEqualTo(new PanMask(8, 2));
        assertThat(new Redactor().mask()).isEqualTo(PanMask.STANDARD);
    }

    @Test
    void aRecordWithNothingToRemoveIsReturnedAsIs() {
        LogRecord original = record("nozzle 1 lifted");
        assertThat(new Redactor().redact(original)).isSameAs(original);
    }

    @Test
    void aRecordWithAPanIsReplacedAndLabelled() {
        LogRecord out = new Redactor().redact(record("pan=" + VISA));
        assertThat(out.message()).isEqualTo("pan=411111******1111");
        assertThat(out.labels().get("redacted")).isEqualTo("1");
    }

    @Test
    void theLabelCountsEverythingThatWent() {
        LogRecord out = new Redactor(List.of(DRIVER)).redact(record("raw=" + BARE2 + " pan=" + VISA + " driver=88213"));
        assertThat(out.labels().get("redacted")).isEqualTo("3");
    }

    @Test
    void existingLabelsSurviveRedaction() {
        LogRecord out = new Redactor().redact(record("pan=" + VISA).replaceLabels(Map.of("lane", "3")));
        assertThat(out.labels().get("lane")).isEqualTo("3");
    }

    @Test
    void theRestOfTheRecordIsUntouched() {
        LogRecord original = record("pan=" + VISA);
        LogRecord out = new Redactor().redact(original);
        assertThat(out.observedAt()).isEqualTo(original.observedAt());
        assertThat(out.unit()).isEqualTo(original.unit());
        assertThat(out.stream()).isEqualTo(original.stream());
    }

    @Test
    void theAuditCountsRecordsAndChanges() {
        Redactor redactor = new Redactor();
        redactor.redact(record("clean"));
        redactor.redact(record("pan=" + VISA));
        assertThat(redactor.audit().records()).isEqualTo(2);
        assertThat(redactor.audit().changed()).isEqualTo(1);
    }

    @Test
    void theAuditCountsByRuleName() {
        Redactor redactor = new Redactor(List.of(DRIVER));
        redactor.redact(record("pan=" + VISA + " driver=88213"));
        assertThat(redactor.audit().hits().get("card_pan")).isEqualTo(1L);
        assertThat(redactor.audit().hits().get("driver_id")).isEqualTo(1L);
    }

    @Test
    void theAuditCountsStripesUnderTheirOwnName() {
        Redactor redactor = new Redactor();
        redactor.redact(record("raw=" + BARE2));
        assertThat(redactor.audit().hits().get("card_stripe")).isEqualTo(1L);
    }

    @Test
    void theAuditNeverStoresWhatItRemoved() {
        Redactor redactor = new Redactor();
        redactor.redact(record("pan=" + VISA));
        assertThat(redactor.audit().asDict().toString()).doesNotContain(VISA);
        assertThat(redactor.audit().asDict().values()).allMatch(value -> value instanceof Long);
    }

    @Test
    void theAuditTotalAddsTheRulesUp() {
        Redactor redactor = new Redactor(List.of(DRIVER));
        redactor.redact(record("pan=" + VISA + " driver=88213"));
        assertThat(redactor.audit().totalHits()).isEqualTo(2);
    }

    @Test
    void aRuleThatNeverFiresIsAbsentFromTheAudit() {
        Redactor redactor = new Redactor(List.of(DRIVER));
        redactor.redact(record("clean line"));
        assertThat(redactor.audit().hits()).doesNotContainKey("driver_id");
    }

    @Test
    void redactAllKeepsTheOrder() {
        Redactor redactor = new Redactor();
        List<LogRecord> batch =
                IntStream.range(0, 3).mapToObj(index -> record("line " + index)).toList();
        assertThat(redactor.redactAll(batch))
                .extracting(LogRecord::message)
                .containsExactly("line 0", "line 1", "line 2");
    }

    @Test
    void redactionIsIdempotent() {
        Redactor redactor = new Redactor(List.of(DRIVER));
        LogRecord once = redactor.redact(record("pan=" + VISA + " driver=88213"));
        LogRecord twice = redactor.redact(once);
        assertThat(twice.message()).isEqualTo(once.message());
    }

    @Test
    void aMaskRuleIsAppliedToASiteField() {
        Rule loyalty = Rule.mask("loyalty", "loyalty=(\\d{11})", 0, 4);
        RedactionResult found = new Redactor(List.of(loyalty)).redactText("loyalty=98765432109");
        assertThat(found.text()).isEqualTo("loyalty=*******2109");
    }

    @Test
    void aTrackOneImageIsRemovedFromARecord() {
        String stripe = "%B4111111111111111^SMITH/JOHN^27031011?";
        LogRecord out = new Redactor().redact(record("reader dump " + stripe));
        assertThat(out.message()).doesNotContain("SMITH").doesNotContain("4111");
    }
}
