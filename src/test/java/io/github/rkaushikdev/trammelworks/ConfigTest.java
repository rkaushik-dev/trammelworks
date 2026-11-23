package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.trammelworks.forecourt.ForecourtConfig;
import io.github.rkaushikdev.trammelworks.forecourt.Role;
import io.github.rkaushikdev.trammelworks.ingest.MultilineRule;
import io.github.rkaushikdev.trammelworks.redact.RedactConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigTest {

    @SuppressWarnings("unchecked")
    static Map<String, Object> block(Map<String, Object> payload, String name) {
        return (Map<String, Object>) payload.get(name);
    }

    @Test
    void defaultsBuildAUsableSite() {
        SiteConfig cfg = SiteConfig.of("US-TX-0417");
        assertThat(cfg.site()).isEqualTo("US-TX-0417");
        assertThat(cfg.ingest().logRoot()).isEqualTo("/var/log/containers");
        assertThat(cfg.spool().budgetBytes()).isEqualTo(512L * 1024 * 1024);
        assertThat(cfg.spool().directory()).isEqualTo("/var/lib/stanchion/spool");
        assertThat(cfg.ship().batchRecords()).isEqualTo(2000);
    }

    @Test
    void siteIdIsNormalized() {
        assertThat(SiteConfig.of("us-tx-0417").site()).isEqualTo("US-TX-0417");
    }

    @Test
    void badSiteIdIsRejectedAtConstruction() {
        assertThatThrownBy(() -> SiteConfig.of("shop 4"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("malformed site id");
    }

    @Test
    void ingestRejectsARelativeLogRoot() {
        assertThatThrownBy(() -> IngestConfig.at("var/log"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be an absolute path");
    }

    @Test
    void ingestRejectsAZeroReadChunk() {
        assertThatThrownBy(() -> IngestConfig.builder().readChunk(0).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("read_chunk must be positive");
    }

    @Test
    void ingestRejectsAPollIntervalOfZero() {
        assertThatThrownBy(
                        () -> IngestConfig.builder().pollInterval(Duration.ZERO).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("poll_interval must be positive");
    }

    @Test
    void ingestRejectsALineCapBelowTheReadChunk() {
        assertThatThrownBy(() -> IngestConfig.builder()
                        .readChunk(8192)
                        .maxLineBytes(4096)
                        .build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("at least read_chunk");
    }

    @Test
    void ingestNormalizesTheUnitFilter() {
        IngestConfig cfg = IngestConfig.builder()
                .units(List.of("Payment-Kernel", "pump-bridge"))
                .build();
        assertThat(cfg.units()).containsExactly("payment-kernel", "pump-bridge");
    }

    @Test
    void ingestRejectsAMalformedUnitFilter() {
        assertThatThrownBy(() ->
                        IngestConfig.builder().units(List.of("pump bridge")).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("malformed unit name");
    }

    @Test
    void labelsMustHaveSaneKeys() {
        assertThatThrownBy(() -> SiteConfig.builder("US-TX-0417")
                        .labels(Map.of("has space", "x"))
                        .build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("malformed label key");
    }

    @Test
    void labelsAllowDashesAndUnderscores() {
        SiteConfig cfg = SiteConfig.builder("US-TX-0417")
                .labels(Map.of("brand_code", "shell", "region-id", "gulf"))
                .build();
        assertThat(cfg.labels()).containsEntry("brand_code", "shell");
    }

    @Test
    void asDictRoundTripsThroughTheMappingBuilder() {
        SiteConfig cfg =
                SiteConfig.builder("US-TX-0417").labels(Map.of("brand", "x")).build();
        Map<String, Object> payload = cfg.asDict();
        Map<String, Object> ingest = block(payload, "ingest");
        Map<String, Object> ship = block(payload, "ship");
        ingest.put("poll_interval", Repr.of((Double) ingest.get("poll_interval")) + "s");
        ship.put("attempt_timeout", Repr.of((Double) ship.get("attempt_timeout")) + "s");
        ship.put("interval", Repr.of((Double) ship.get("interval")) + "s");
        assertThat(SiteConfig.siteFromMapping(payload)).isEqualTo(cfg);
    }

    @Test
    void forecourtRolesRoundTripThroughTheMappingBuilder() {
        SiteConfig cfg = SiteConfig.builder("US-TX-0417")
                .forecourt(new ForecourtConfig(Map.of("fcc", Role.DISPENSER)))
                .build();
        SiteConfig back = SiteConfig.siteFromMapping(
                Maps.of("site", "US-TX-0417", "forecourt", cfg.forecourt().asDict()));
        assertThat(back.forecourt()).isEqualTo(cfg.forecourt());
        assertThat(cfg.toBuilder().build().forecourt().roleOf("fcc")).isSameAs(Role.DISPENSER);
    }

    @Test
    void mappingBuilderReadsTheWrittenSpellings() {
        Map<String, Object> raw = Maps.of(
                "site", "GB-LDN-0009",
                "ingest", Maps.of("poll_interval", "5s", "read_chunk", "32KiB", "max_line_bytes", "2MiB"),
                "spool", Maps.of("budget", "1GiB", "segment", "16MiB"),
                "ship", Maps.of("monthly_allowance", "5GB", "batch", "512KiB", "attempt_timeout", "1m"));
        SiteConfig cfg = SiteConfig.siteFromMapping(raw);
        assertThat(cfg.ingest().pollInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(cfg.ingest().readChunk()).isEqualTo(32768);
        assertThat(cfg.spool().budgetBytes()).isEqualTo(1073741824L);
        assertThat(cfg.ship().monthlyAllowanceBytes()).isEqualTo(5_000_000_000L);
        assertThat(cfg.ship().attemptTimeout()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void mappingBuilderNeedsASite() {
        assertThatThrownBy(() -> SiteConfig.siteFromMapping(Map.of("spool", Map.of())))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("no site id");
    }

    @Test
    void mappingBuilderRejectsAnUnknownSection() {
        assertThatThrownBy(() -> SiteConfig.siteFromMapping(Map.of("site", "US-TX-0417", "spoool", Map.of())))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown config key");
    }

    @Test
    void mappingBuilderNamesEveryUnknownSection() {
        ConfigError caught = catchThrowableOfType(
                ConfigError.class,
                () -> SiteConfig.siteFromMapping(Maps.of("site", "US-TX-0417", "zebra", 1, "alpha", 2)));
        assertThat(caught.detail()).hasValue("alpha, zebra");
    }

    @Test
    void mappingBuilderStringifiesLabelValues() {
        SiteConfig cfg = SiteConfig.siteFromMapping(Map.of("site", "US-TX-0417", "labels", Map.of("lane", 3)));
        assertThat(cfg.labels()).isEqualTo(Map.of("lane", "3"));
    }

    @Test
    void aWindowsDrivePathCountsAsAbsolute() {
        assertThat(IngestConfig.at("C:/logs").logRoot()).isEqualTo("C:/logs");
    }

    @Test
    void aBareNameIsStillRelative() {
        assertThatThrownBy(() -> IngestConfig.at("logs/here"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be an absolute path");
    }

    @Test
    void theRedactSectionDefaultsToOn() {
        assertThat(SiteConfig.of("US-TX-0417").redact().enabled()).isTrue();
    }

    @Test
    void aDisabledPassIsRefusedAtSiteConstruction() {
        assertThatThrownBy(() -> SiteConfig.builder("US-TX-0417")
                        .redact(RedactConfig.disabled())
                        .build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("only be disabled on a lab site");
    }

    @Test
    void aBenchSiteMayDisableThePass() {
        SiteConfig built = SiteConfig.builder("XX-LAB-0001")
                .redact(RedactConfig.disabled())
                .build();
        assertThat(built.redact().enabled()).isFalse();
    }

    @Test
    void theRedactSectionIsReadFromAMapping() {
        SiteConfig built = SiteConfig.siteFromMapping(Map.of(
                "site",
                "US-TX-0417",
                "redact",
                Maps.of("keep_first", 0, "rules", Map.of("driver", Map.of("pattern", "driver=(x)")))));
        assertThat(built.redact().keepFirst()).isZero();
        assertThat(built.redact().ruleNames()).containsExactly("driver");
    }

    @Test
    void theRedactSectionAppearsInAsDict() {
        Map<String, Object> redact = block(SiteConfig.of("US-TX-0417").asDict(), "redact");
        assertThat(redact.get("enabled")).isEqualTo(true);
        assertThat(redact.get("rules")).isEqualTo(Map.of());
    }

    @Test
    void multilineRulesAreReadIntoIngest() {
        SiteConfig built = SiteConfig.siteFromMapping(Map.of(
                "site",
                "US-TX-0417",
                "ingest",
                Map.of("multiline", Map.of("payment-kernel", Map.of("continuation", "^ +")))));
        assertThat(built.ingest().multiline()).extracting(MultilineRule::unit).containsExactly("payment-kernel");
    }

    @Test
    void twoMultilineRulesForOneUnitAreRefused() {
        List<MultilineRule> twice =
                List.of(MultilineRule.continuing("pump-bridge", "^ +"), MultilineRule.starting("pump-bridge", "^x"));
        assertThatThrownBy(() -> IngestConfig.builder().multiline(twice).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("same unit");
    }

    @Test
    void anUnknownTopLevelSectionIsStillRefused() {
        assertThatThrownBy(() -> SiteConfig.siteFromMapping(Map.of("site", "US-TX-0417", "redactt", Map.of())))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown config key");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/var/log/containers",
                "C:/logs",
                "c:\\x",
                "\\\\server\\share",
                "\\\\server\\share\\x",
                "//server/share",
                "\\\\?\\UNC\\srv\\share",
                "\\\\.\\dev\\x"
            })
    void pathsRootedUnderEitherConventionAreAbsolute(String raw) {
        assertThat(IngestConfig.isAbsolutePath(raw)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"logs", "C:logs", "\\logs", "\\\\server", "1:x", "./logs"})
    void pathsWithoutADriveAndARootAreRelative(String raw) {
        assertThat(IngestConfig.isAbsolutePath(raw)).isFalse();
    }
}
