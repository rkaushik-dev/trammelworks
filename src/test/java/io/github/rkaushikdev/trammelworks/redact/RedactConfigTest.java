package io.github.rkaushikdev.trammelworks.redact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Maps;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedactConfigTest {

    @Test
    void theDefaultsAreOnAndPciShaped() {
        RedactConfig built = new RedactConfig();
        assertThat(built.enabled()).isTrue();
        assertThat(built.keepFirst()).isEqualTo(6);
        assertThat(built.keepLast()).isEqualTo(4);
    }

    @Test
    void keepingMoreThanEightLeadingDigitsIsRefused() {
        assertThatThrownBy(() -> new RedactConfig(true, 9, 4, List.of()))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("more than eight leading");
    }

    @Test
    void anEightDigitBinHeadIsAllowed() {
        RedactConfig built = new RedactConfig(true, 8, 4, List.of());
        assertThat(built.mask()).isEqualTo(new PanMask(8, 4));
        assertThat(built.mask().usesLongBin()).isTrue();
    }

    @Test
    void keepingANegativeNumberIsRefused() {
        assertThatThrownBy(() -> new RedactConfig(true, -1, 4, List.of()))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("negative");
    }

    @Test
    void theSummarySaysWhenShortCardsFallBackToSix() {
        assertThat(RedactConfig.summarize(new RedactConfig(true, 8, 4, List.of()))
                        .toString())
                .isEqualTo("redaction on, keep 8+4 (6+4 under 16 digits), card rules only");
    }

    @Test
    void keepingMoreThanFourTrailingDigitsIsRefused() {
        assertThatThrownBy(() -> new RedactConfig(true, 6, 5, List.of()))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("more than four trailing");
    }

    @Test
    void keepingFewerIsAllowed() {
        assertThat(new RedactConfig(true, 0, 0, List.of()).keepFirst()).isZero();
    }

    @Test
    void aNegativeKeepIsRefused() {
        assertThatThrownBy(() -> new RedactConfig(true, -1, 4, List.of()))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("negative number of digits");
    }

    @Test
    void twoRulesMayNotShareAName() {
        List<Rule> twice = List.of(new Rule("a", "x"), new Rule("a", "y"));
        assertThatThrownBy(() -> RedactConfig.withRules(twice))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("share a name");
    }

    @Test
    void theRuleNamesAreListed() {
        RedactConfig built = RedactConfig.withRules(List.of(new Rule("driver", "x")));
        assertThat(built.ruleNames()).containsExactly("driver");
    }

    @Test
    void anEnabledPassIsFineAnywhere() {
        assertThatCode(() -> new RedactConfig().checkSite("US-TX-0417")).doesNotThrowAnyException();
    }

    @Test
    void disablingThePassOnARealSiteIsRefused() {
        assertThatThrownBy(() -> RedactConfig.disabled().checkSite("US-TX-0417"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("only be disabled on a lab site");
    }

    @ParameterizedTest
    @ValueSource(strings = {"XX-LAB-0001", "XX-DEV-0009"})
    void disablingThePassOnABenchIsAllowed(String site) {
        assertThatCode(() -> RedactConfig.disabled().checkSite(site)).doesNotThrowAnyException();
    }

    @Test
    void theRefusalNamesTheSite() {
        ConfigError caught = catchThrowableOfType(
                ConfigError.class, () -> RedactConfig.disabled().checkSite("GB-LDN-0009"));
        assertThat(caught.detail()).contains("GB-LDN-0009");
    }

    @Test
    void theSummaryOfTheDefaultMentionsTheCardRules() {
        assertThat(RedactConfig.summarize(new RedactConfig()).toString())
                .isEqualTo("redaction on, keep 6+4, card rules only");
    }

    @Test
    void theSummaryCountsSiteRules() {
        RedactConfig built = RedactConfig.withRules(List.of(new Rule("driver", "x")));
        assertThat(RedactConfig.summarize(built).toString()).isEqualTo("redaction on, keep 6+4, plus 1 site rules");
    }

    @Test
    void aDisabledSummaryShouts() {
        assertThat(RedactConfig.summarize(RedactConfig.disabled()).toString()).isEqualTo("redaction DISABLED");
    }

    @Test
    void theSummaryCarriesTheRuleNames() {
        RedactConfig built = RedactConfig.withRules(List.of(new Rule("driver", "x")));
        assertThat(RedactConfig.summarize(built).rules()).containsExactly("driver");
    }

    @Test
    void theMappingBuilderReadsAFullSection() {
        Map<String, Object> raw = Maps.of(
                "keep_first", 0, "keep_last", 4, "rules", Map.of("driver_id", Map.of("pattern", "driver=(\\d+)")));
        RedactConfig built = RedactConfig.redactFromMapping(raw);
        assertThat(built.keepFirst()).isZero();
        assertThat(built.ruleNames()).containsExactly("driver_id");
    }

    @Test
    void anEmptySectionGivesTheDefaults() {
        assertThat(RedactConfig.redactFromMapping(Map.of())).isEqualTo(new RedactConfig());
    }

    @Test
    void theMappingBuilderRejectsAnUnknownKey() {
        assertThatThrownBy(() -> RedactConfig.redactFromMapping(Map.of("keep_middle", 2)))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown redact key");
    }

    @Test
    void theMappingBuilderNeedsRulesToBeAMapping() {
        assertThatThrownBy(() -> RedactConfig.redactFromMapping(Map.of("rules", List.of("driver"))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("the rules section must be a mapping");
    }

    @Test
    void theMappingBuilderCanTurnThePassOff() {
        assertThat(RedactConfig.redactFromMapping(Map.of("enabled", false)).enabled())
                .isFalse();
    }

    @Test
    void aBrokenRuleInTheSectionIsRefused() {
        assertThatThrownBy(() ->
                        RedactConfig.redactFromMapping(Map.of("rules", Map.of("bad", Map.of("pattern", "(unclosed")))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("does not compile");
    }
}
