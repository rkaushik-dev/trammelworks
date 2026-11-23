package io.github.rkaushikdev.trammelworks.redact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.Samples;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class RedactPanTest {

    static final String VISA = "4111111111111111";
    static final String MASTERCARD = "5500005555555559";
    static final String AMEX = "378282246310005";
    static final String FLEET = "7083280000000000000";

    @Test
    void aKnownVisaPassesLuhn() {
        assertThat(Luhn.luhnValid(VISA)).isTrue();
    }

    @Test
    void changingOneDigitBreaksLuhn() {
        assertThat(Luhn.luhnValid("4111111111111112")).isFalse();
    }

    @Test
    void luhnNeedsDigits() {
        assertThat(Luhn.luhnValid("41111111111111 1")).isFalse();
        assertThat(Luhn.luhnValid("")).isFalse();
    }

    @Test
    void luhnSumRefusesANonDigitString() {
        assertThatThrownBy(() -> Luhn.luhnSum("41a1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non empty digit string");
    }

    @Test
    void theCheckDigitCompletesABody() {
        String body = VISA.substring(0, VISA.length() - 1);
        assertThat(Luhn.luhnCheckDigit(body)).isEqualTo(VISA.substring(VISA.length() - 1));
        assertThat(Luhn.luhnValid(body + Luhn.luhnCheckDigit(body))).isTrue();
    }

    static Stream<String> bodies() {
        return Stream.concat(
                Stream.of("0", "9", "0".repeat(18), "9".repeat(18)),
                Samples.drawn(11, random -> Samples.text(random, "0123456789", 1, 18)));
    }

    @ParameterizedTest
    @MethodSource("bodies")
    void anyBodyCanBeCompleted(String body) {
        assertThat(Luhn.luhnValid(body + Luhn.luhnCheckDigit(body))).isTrue();
    }

    @Test
    void leadingZerosAreKept() {
        String body = "0000000000000";
        assertThat(Luhn.luhnValid(body + Luhn.luhnCheckDigit(body))).isTrue();
    }

    @Test
    void digitsOnlyStripsTheSeparatorsATerminalPrints() {
        assertThat(Luhn.digitsOnly("4111 1111-1111 1111")).isEqualTo(VISA);
    }

    @Test
    void plausibleLengthCoversThirteenToNineteen() {
        assertThat(Luhn.plausibleLength("4".repeat(12))).isFalse();
        assertThat(Luhn.plausibleLength("4".repeat(13))).isTrue();
        assertThat(Luhn.plausibleLength("4".repeat(19))).isTrue();
        assertThat(Luhn.plausibleLength("4".repeat(20))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        VISA + ", visa",
        MASTERCARD + ", mastercard",
        AMEX + ", amex",
        "6011000990139424, discover",
        "3530111333300000, jcb",
        "6759649826438453, maestro",
        "2221000000000009, mastercard",
        "2720990000000004, mastercard",
        "2200150000000002, mir",
        "6200000000000005, unionpay",
        "6221260000000000, discover"
    })
    void theBrandIsReadFromThePrefix(String digits, String brand) {
        assertThat(Pans.brandOf(digits)).contains(brand);
    }

    @Test
    void aFleetPrefixIsRecognised() {
        assertThat(Pans.brandOf(FLEET)).contains("fleet");
    }

    @Test
    void theLongestMatchingPrefixWins() {
        assertThat(Pans.brandOf("7081380000000000")).contains("fleet");
        assertThat(Pans.brandOf("7071380000000000")).contains("wex");
    }

    @ParameterizedTest
    @CsvSource({"2100000000000000", "2721000000000000", "3527000000000000", "3590000000000000"})
    void numbersJustOutsideARangeHaveNoBrand(String digits) {
        assertThat(Pans.brandOf(digits)).isEmpty();
    }

    @Test
    void aCoBadgedBlockIsNamedForTheSchemeThatCarvedIt() {
        assertThat(Pans.issuerOf("6221260000000000")).contains(new IssuerRange("discover", "622126", "622925"));
        assertThat(Pans.issuerOf("6229260000000000")).contains(new IssuerRange("unionpay", "62", "62"));
    }

    @Test
    void aMirCardIsMaskedLikeAnyOther() {
        String body = "220015000000000";
        String mir = body + Luhn.luhnCheckDigit(body);
        assertThat(Pans.maskPans("card " + mir)).isEqualTo("card 220015******" + mir.substring(12));
    }

    @Test
    void aRangeIsReadFromItsWrittenForm() {
        IssuerRange range = IssuerRange.of("jcb", "3528-3589");
        assertThat(range.width()).isEqualTo(4);
        assertThat(range.size()).isEqualTo(62);
        assertThat(range).hasToString("jcb 3528-3589");
        assertThat(IssuerRange.of("visa", "4")).hasToString("visa 4");
    }

    @Test
    void aRangeOnlyCoversNumbersLongEnoughToReach() {
        assertThat(IssuerRange.of("discover", "622126-622925").covers("6221")).isFalse();
    }

    @Test
    void theNarrowerOfTwoEqualWidthRangesIsMoreSpecific() {
        IssuerRange wide = IssuerRange.of("maestro", "56-58");
        IssuerRange narrow = IssuerRange.of("diners", "36");
        assertThat(narrow.moreSpecificThan(wide)).isTrue();
        assertThat(wide.moreSpecificThan(narrow)).isFalse();
        assertThat(IssuerRange.of("wex", "707138").moreSpecificThan(narrow)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "visa, 4a, written in digits",
        "visa, 4-50, same width",
        "visa, 55-51, runs low to high",
        "'', 4, needs a brand"
    })
    void aMalformedRangeIsRefused(String brand, String written, String message) {
        assertThatThrownBy(() -> IssuerRange.of(brand, written))
                .isInstanceOf(io.github.rkaushikdev.trammelworks.ConfigError.class)
                .hasMessageContaining(message);
    }

    @Test
    void anUnknownPrefixHasNoBrand() {
        assertThat(Pans.brandOf("9999999999999999")).isEmpty();
    }

    @Test
    void allThreeTestsMustPass() {
        assertThat(Pans.looksLikePan(VISA)).isTrue();
        assertThat(Pans.looksLikePan("4111111111111112")).isFalse();
        assertThat(Pans.looksLikePan("41111111111")).isFalse();
    }

    @Test
    void aLuhnValidNumberWithNoIssuerIsNotAPan() {
        String body = "999999999999";
        String number = body + Luhn.luhnCheckDigit(body);
        assertThat(Luhn.luhnValid(number)).isTrue();
        assertThat(Pans.looksLikePan(number)).isFalse();
    }

    @Test
    void aPlainPanIsFound() {
        List<PanMatch> found = Pans.findPans("auth request pan=" + VISA + " amount=4000");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).digits()).isEqualTo(VISA);
        assertThat(found.get(0).brand()).isEqualTo("visa");
    }

    @Test
    void aGroupedPanIsFound() {
        List<PanMatch> found = Pans.findPans("pan=4111 1111 1111 1111 approved");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).digits()).isEqualTo(VISA);
        assertThat(found.get(0).text()).isEqualTo("4111 1111 1111 1111");
    }

    @Test
    void aDashSeparatedPanIsFound() {
        assertThat(Pans.findPans("pan=4111-1111-1111-1111").get(0).digits()).isEqualTo(VISA);
    }

    @Test
    void theSpanCoversExactlyTheNumber() {
        String line = "pan=" + VISA + " end";
        PanMatch found = Pans.findPans(line).get(0);
        assertThat(line.substring(found.start(), found.end())).isEqualTo(VISA);
    }

    @Test
    void twoPansOnOneLineAreBothFound() {
        List<PanMatch> found = Pans.findPans("from " + VISA + " to " + MASTERCARD);
        assertThat(found).extracting(PanMatch::brand).containsExactly("visa", "mastercard");
    }

    @Test
    void aShortNumberIsNotAPan() {
        assertThat(Pans.findPans("terminal 123456789012")).isEmpty();
    }

    @Test
    void aTransactionReferenceIsLeftAlone() {
        assertThat(Pans.findPans("txn ref 9182736455647382")).isEmpty();
    }

    @Test
    void aTankVolumeIsLeftAlone() {
        assertThat(Pans.findPans("tank 3 volume 41200456 ml")).isEmpty();
    }

    @Test
    void anAmexOfFifteenDigitsIsFound() {
        assertThat(Pans.findPans("pan=" + AMEX).get(0).length()).isEqualTo(15);
    }

    @Test
    void aNineteenDigitFleetCardIsFound() {
        List<PanMatch> found = Pans.findPans("card " + FLEET + " accepted");
        assertThat(found).isNotEmpty();
        assertThat(found.get(0).digits()).isEqualTo(FLEET);
    }

    @Test
    void aPanRunTogetherWithAnExpiryIsStillFound() {
        List<PanMatch> found = Pans.findPans(VISA + "2703");
        assertThat(found).isNotEmpty();
        assertThat(found.get(0).digits()).isEqualTo(VISA);
    }

    @Test
    void theTrimmedSpanStopsBeforeTheExpiry() {
        String line = "track " + VISA + "2703 end";
        PanMatch found = Pans.findPans(line).get(0);
        assertThat(line.substring(found.start(), found.end())).isEqualTo(VISA);
    }

    @Test
    void maskKeepsTheFirstSixAndLastFour() {
        assertThat(Pans.maskDigits(VISA)).isEqualTo("411111******1111");
    }

    @Test
    void maskCanKeepNothing() {
        assertThat(Pans.maskDigits(VISA, 0, 0)).isEqualTo("*".repeat(16));
    }

    @Test
    void maskOfAShortNumberHidesEverything() {
        assertThat(Pans.maskDigits("41111111111", 6, 4)).isEqualTo("*".repeat(11));
    }

    @Test
    void aMaskThatWouldHideTooLittleHidesItAll() {
        // Thirteen digits keeping six and four leaves three hidden, which is not a mask, it is a
        // hint. Everything goes instead.
        assertThat(Pans.maskDigits("4".repeat(13))).isEqualTo("*".repeat(13));
    }

    @Test
    void fifteenDigitsStillKeepTheHeadAndTail() {
        assertThat(Pans.maskDigits(AMEX)).isEqualTo("378282*****0005");
    }

    @Test
    void maskRefusesANegativeKeep() {
        assertThatThrownBy(() -> Pans.maskDigits(VISA, -1, Pans.KEEP_LAST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative number of digits");
    }

    @ParameterizedTest
    @CsvSource({"13, 6", "15, 6", "16, 8", "19, 8"})
    void anEightDigitHeadOnlyAppliesFromSixteenDigits(int digits, int head) {
        assertThat(new PanMask(8, 4).headFor(digits)).isEqualTo(head);
        assertThat(PanMask.STANDARD.headFor(digits)).isEqualTo(6);
    }

    @Test
    void aMaskAskingForFewerThanSixKeepsWhatItAskedFor() {
        assertThat(new PanMask(4, 4).headFor(19)).isEqualTo(4);
        assertThat(new PanMask(4, 4).apply(VISA)).isEqualTo("4111********1111");
    }

    @Test
    void aNineteenDigitFleetCardKeepsEight() {
        assertThat(Pans.maskPans("card " + FLEET, new PanMask(8, 4))).isEqualTo("card 70832800*******0000");
    }

    @Test
    void theMaskDescribesItself() {
        assertThat(PanMask.STANDARD).hasToString("6+4");
        assertThat(new PanMask(8, 4)).hasToString("8+4 (6+4 under 16 digits)");
    }

    @Test
    void maskPansReplacesInPlace() {
        assertThat(Pans.maskPans("auth pan=" + VISA + " amount=4000"))
                .isEqualTo("auth pan=411111******1111 amount=4000");
    }

    @Test
    void maskPansDropsTheSeparatorsWithTheDigits() {
        assertThat(Pans.maskPans("pan=4111 1111 1111 1111 ok")).isEqualTo("pan=411111******1111 ok");
    }

    @Test
    void maskPansHandlesTwoOnOneLine() {
        assertThat(Pans.maskPans(VISA + " then " + MASTERCARD)).isEqualTo("411111******1111 then 550000******5559");
    }

    @Test
    void maskPansLeavesACleanLineAlone() {
        String line = "nozzle 1 lifted on pump 3";
        assertThat(Pans.maskPans(line)).isEqualTo(line);
    }

    @Test
    void maskPansLeavesAReferenceAlone() {
        String line = "txn ref 9182736455647382 settled";
        assertThat(Pans.maskPans(line)).isEqualTo(line);
    }

    @Test
    void maskingIsIdempotent() {
        String once = Pans.maskPans("pan=" + VISA);
        assertThat(Pans.maskPans(once)).isEqualTo(once);
    }
}
