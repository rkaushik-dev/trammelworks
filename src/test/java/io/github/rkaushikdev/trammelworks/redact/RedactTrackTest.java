package io.github.rkaushikdev.trammelworks.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedactTrackTest {

    static final String TRACK1 = "%B4111111111111111^SMITH/JOHN            ^27031011000000000000000?";
    static final String TRACK2 = ";4111111111111111=27031011234567890?";
    static final String BARE2 = "4111111111111111=270310112345";

    @Test
    void aTrackOneImageIsFound() {
        List<SecretMatch> found = Stripes.findTrack1("reader error raw=" + TRACK1);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).kind()).isSameAs(SecretKind.TRACK1);
    }

    @Test
    void theTrackOneSpanCoversTheWholeImage() {
        String line = "raw=" + TRACK1 + " end";
        SecretMatch found = Stripes.findTrack1(line).get(0);
        assertThat(line.substring(found.start(), found.end())).isEqualTo(TRACK1);
    }

    @Test
    void aLineWithoutAStripeHasNoTrackOne() {
        assertThat(Stripes.findTrack1("nozzle 1 lifted")).isEmpty();
    }

    @Test
    void aTrackTwoImageWithSentinelsIsFound() {
        List<SecretMatch> found = Stripes.findTrack2("swipe " + TRACK2);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).kind()).isSameAs(SecretKind.TRACK2);
    }

    @Test
    void aBareTrackTwoIsFound() {
        List<SecretMatch> found = Stripes.findTrack2("swipe " + BARE2 + " declined");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).text()).isEqualTo(BARE2);
    }

    @Test
    void aBareTrackTwoNeedsARealPan() {
        assertThat(Stripes.findTrack2("counter 1234567890123456789=27031011")).isEmpty();
    }

    @Test
    void aConfigLineWithAnEqualsSignIsNotAStripe() {
        assertThat(Stripes.findTrack2("max_line_bytes=1048576")).isEmpty();
    }

    @Test
    void aSentinelTrackIsNotAlsoReportedAsBare() {
        assertThat(Stripes.findTrack2("raw=" + TRACK2)).hasSize(1);
    }

    @Test
    void twoStripesOnOneLineAreBothFound() {
        assertThat(Stripes.findTrack2(BARE2 + " and " + BARE2)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "pinblock=1A2B3C4D5E6F7081",
                "PIN_BLOCK: 1A2B3C4D5E6F7081",
                "encrypted_pin=\"1A2B3C4D5E6F7081\"",
                "pinblk = 1A2B3C4D5E6F7081"
            })
    void aPinBlockIsFoundByItsKey(String line) {
        List<SecretMatch> found = Stripes.findKeyedSecrets(line);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).kind()).isSameAs(SecretKind.PIN_BLOCK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cvv=123", "CVC2: 456", "csc=789", "cav2=4321"})
    void aCardCodeIsFoundByItsKey(String line) {
        List<SecretMatch> found = Stripes.findKeyedSecrets(line);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).kind()).isSameAs(SecretKind.CARD_CODE);
    }

    @Test
    void theSpanCoversTheValueNotTheKey() {
        String line = "cvv=123 approved";
        SecretMatch found = Stripes.findKeyedSecrets(line).get(0);
        assertThat(line.substring(found.start(), found.end())).isEqualTo("123");
    }

    @Test
    void aQuotedValueHasItsQuotesLeftInPlace() {
        String line = "cvv=\"123\"";
        SecretMatch found = Stripes.findKeyedSecrets(line).get(0);
        assertThat(line.substring(found.start(), found.end())).isEqualTo("123");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "cardholder=SMITH/JOHN",
                "CARDHOLDER_NAME: SMITH/JOHN MR",
                "chname=\"John Smith\"",
                "card_name=OBRIEN"
            })
    void aCardholderNameIsFoundByItsKey(String line) {
        List<SecretMatch> found = Stripes.findKeyedSecrets(line);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).kind()).isSameAs(SecretKind.CARDHOLDER_NAME);
    }

    @Test
    void aStripeShapedNameIsTakenWhole() {
        assertThat(Stripes.stripSecrets("auth cardholder: SMITH/JOHN MR approved"))
                .isEqualTo("auth cardholder: [redacted] approved");
    }

    @Test
    void aQuotedNameIsTakenWithItsSpaces() {
        assertThat(Stripes.stripSecrets("chname=\"Mary Ann O'Neil\" rc=00")).isEqualTo("chname=\"[redacted]\" rc=00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"driver_pin=4821", "FLEET_PIN: 004821", "dpin = 98765432"})
    void aFleetDriverPinIsFoundByItsKey(String line) {
        List<SecretMatch> found = Stripes.findKeyedSecrets(line);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).kind()).isSameAs(SecretKind.FLEET_PIN);
    }

    @Test
    void aFleetPinOfThreeDigitsIsNotAPin() {
        assertThat(Stripes.findKeyedSecrets("driver_pin=482")).isEmpty();
    }

    @Test
    void aKeyInsideALongerWordIsNotThatKey() {
        assertThat(Stripes.findKeyedSecrets("acid=123 pump_cvv=456")).isEmpty();
    }

    @Test
    void keyedSecretsComeBackInLineOrder() {
        assertThat(Stripes.findKeyedSecrets("driver_pin=4821 cvv=123 pinblock=1A2B3C4D5E6F7081"))
                .extracting(SecretMatch::kind)
                .containsExactly(SecretKind.FLEET_PIN, SecretKind.CARD_CODE, SecretKind.PIN_BLOCK);
    }

    @Test
    void everyKindFoundByKeyIsInTheTable() {
        assertThat(Stripes.KEYED)
                .extracting(KeyedSecret::kind)
                .containsExactly(
                        SecretKind.PIN_BLOCK, SecretKind.CARD_CODE, SecretKind.CARDHOLDER_NAME, SecretKind.FLEET_PIN);
        assertThat(Stripes.KEYED.get(3).keys()).contains("driver_pin");
        assertThat(Stripes.KEYED.get(3).pattern().matcher("dpin=1234").find()).isTrue();
    }

    @Test
    void aKeyedSecretNeedsAKey() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new KeyedSecret(SecretKind.CARD_CODE, List.of(), KeyedSecret.HEX))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sixteenHexCharactersWithoutAKeyAreLeftAlone() {
        assertThat(Stripes.findKeyedSecrets("container 1a2b3c4d5e6f7081 started"))
                .isEmpty();
    }

    @Test
    void aKeyThatIsNotASecretIsLeftAlone() {
        assertThat(Stripes.findKeyedSecrets("pump=1A2B3C4D")).isEmpty();
    }

    @Test
    void findSecretsReturnsEverythingInOrder() {
        String line = "cvv=123 then " + BARE2;
        assertThat(Stripes.findSecrets(line))
                .extracting(SecretMatch::kind)
                .containsExactly(SecretKind.CARD_CODE, SecretKind.TRACK2);
    }

    @Test
    void overlappingMatchesKeepTheLongest() {
        List<SecretMatch> found = Stripes.findSecrets("raw=" + TRACK1);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).kind()).isSameAs(SecretKind.TRACK1);
    }

    @Test
    void stripReplacesATrackTwo() {
        assertThat(Stripes.stripSecrets("swipe " + BARE2 + " declined")).isEqualTo("swipe [redacted] declined");
    }

    @Test
    void stripReplacesATrackOne() {
        assertThat(Stripes.stripSecrets("raw=" + TRACK1)).isEqualTo("raw=[redacted]");
    }

    @Test
    void stripReplacesAPinBlockButKeepsTheKey() {
        assertThat(Stripes.stripSecrets("pinblock=1A2B3C4D5E6F7081")).isEqualTo("pinblock=[redacted]");
    }

    @Test
    void stripReplacesACvvButKeepsTheKey() {
        assertThat(Stripes.stripSecrets("auth cvv=123 amount=4000")).isEqualTo("auth cvv=[redacted] amount=4000");
    }

    @Test
    void stripLeavesACleanLineAlone() {
        String line = "nozzle 1 lifted on pump 3";
        assertThat(Stripes.stripSecrets(line)).isEqualTo(line);
    }

    @Test
    void stripTakesAReplacementString() {
        assertThat(Stripes.stripSecrets("cvv=123", "###")).isEqualTo("cvv=###");
    }

    @Test
    void stripHandlesTwoSecretsOnOneLine() {
        assertThat(Stripes.stripSecrets("cvv=123 raw=" + BARE2)).isEqualTo("cvv=[redacted] raw=[redacted]");
    }

    @Test
    void strippingIsIdempotent() {
        String once = Stripes.stripSecrets("raw=" + BARE2);
        assertThat(Stripes.stripSecrets(once)).isEqualTo(once);
    }

    @Test
    void aStripeIsRemovedWholeRatherThanMasked() {
        String stripped = Stripes.stripSecrets("raw=" + BARE2);
        assertThat(stripped).doesNotContain("4111").doesNotContain("2703");
    }
}
