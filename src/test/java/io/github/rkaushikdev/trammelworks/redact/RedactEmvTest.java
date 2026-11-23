package io.github.rkaushikdev.trammelworks.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedactEmvTest {

    static final String PAN_TAG = "5A084111111111111111";
    static final String EXPIRY_TAG = "5F2403270331";
    static final String AMOUNT_TAG = "9F0206000000004000";
    static final String TRACK2_TAG = "57114111111111111111D27032011234567890";
    static final String DUMP = PAN_TAG + EXPIRY_TAG + AMOUNT_TAG + "9F360200A1";

    @Test
    void aDumpIsParsedIntoItsElements() {
        List<EmvData.Element> parsed = EmvData.parse(DUMP).orElseThrow();
        assertThat(parsed).extracting(EmvData.Element::tag).containsExactly("5A", "5F24", "9F02", "9F36");
        assertThat(DUMP.substring(parsed.get(0).valueStart(), parsed.get(0).valueEnd()))
                .isEqualTo("4111111111111111");
    }

    @Test
    void theDigitScanAloneMissesAPackedPan() {
        assertThat(Pans.findPans("icc=" + DUMP)).isEmpty();
    }

    @Test
    void thePanTagValueIsRemovedAndTheRestKept() {
        assertThat(Stripes.stripSecrets("icc=" + DUMP))
                .isEqualTo("icc=5A08[redacted]" + EXPIRY_TAG + AMOUNT_TAG + "9F360200A1");
    }

    @Test
    void aTrackTwoEquivalentWithItsDSeparatorIsRemoved() {
        List<SecretMatch> found = EmvData.findEmv("tap data " + TRACK2_TAG + AMOUNT_TAG);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).kind()).isSameAs(SecretKind.EMV_TAG);
        assertThat(found.get(0).text()).isEqualTo("4111111111111111D27032011234567890");
    }

    @Test
    void aTemplateIsOpenedToFindTheTagsInside() {
        String template = "7013" + TRACK2_TAG;
        assertThat(Stripes.stripSecrets("record " + template)).isEqualTo("record 70135711[redacted]");
    }

    @Test
    void aDumpWrittenWithSpacesBetweenBytesIsStillRead() {
        String spaced = "5A 08 41 11 11 11 11 11 11 11 9F 36 02 00 A1";
        assertThat(Stripes.stripSecrets("icc: " + spaced)).isEqualTo("icc: 5A 08 [redacted] 9F 36 02 00 A1");
    }

    @Test
    void aLongFormLengthIsRead() {
        String name =
                "SMITH/JOHN".chars().mapToObj(c -> String.format("%02X", c)).reduce("", String::concat);
        String dump = "5F2081" + String.format("%02X", name.length() / 2) + name;
        assertThat(EmvData.parse(dump).orElseThrow())
                .extracting(EmvData.Element::tag)
                .containsExactly("5F20");
        assertThat(Stripes.stripSecrets(dump)).isEqualTo("5F20810A[redacted]");
    }

    @Test
    void paddingBetweenElementsIsSkipped() {
        assertThat(EmvData.parse(AMOUNT_TAG + "0000" + PAN_TAG + "FF")).isPresent();
    }

    @Test
    void aDumpWithNoCardholderTagsIsLeftAlone() {
        String line = "gen ac " + AMOUNT_TAG + EXPIRY_TAG;
        assertThat(EmvData.findEmv(line)).isEmpty();
        assertThat(Stripes.stripSecrets(line)).isEqualTo(line);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "3f2a9c0e1b7d4a5e8c6f0a1b2c3d4e5f",
                "5A0841111111",
                "5A84",
                "9FFFFFFF01",
                "5A08411111111111111",
                "9F",
                "70045A084111"
            })
    void hexThatIsNotTlvEndToEndIsNotADump(String hex) {
        assertThat(EmvData.parse(hex)).isEmpty();
    }

    @Test
    void aBootIdOnALineIsNotTouched() {
        String line = "boot 3f2a9c0e1b7d4a5e8c6f0a1b2c3d4e5f ok";
        assertThat(Stripes.stripSecrets(line)).isEqualTo(line);
    }

    @Test
    void theRedactorCountsADumpAsAStripe() {
        RedactionResult found = new Redactor().redactText("chip read failed icc=" + DUMP);
        assertThat(found.stripes()).isEqualTo(1);
        assertThat(found.pans()).isZero();
        assertThat(found.text()).doesNotContain("4111111111111111");
    }

    @Test
    void everySensitiveTagIsNamed() {
        assertThat(EmvData.SENSITIVE_TAGS).containsKeys("56", "57", "5A", "5F20", "9F1F", "9F20", "9F6B", "99");
        assertThat(new EmvData.Element("9F02", 0, 2).sensitive()).isFalse();
    }
}
