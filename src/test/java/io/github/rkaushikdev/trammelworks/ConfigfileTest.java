package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.cli.CliRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigfileTest {

    static final String SITE_YAML =
            """

            site: US-TX-0417
            ingest:
              log_root: /var/log/pods
              poll_interval: 5s
            spool:
              budget: 1GiB
              segment: 16MiB
            ship:
              endpoint: https://collector.example.net/v1/ingest
              monthly_allowance: 5GB
            labels:
              brand: bluebird
            """;

    /** The dump of {@link #SITE_YAML}, byte for byte: sorted keys, block style, decimal floats. */
    static final String SITE_DUMP =
            """
            archive:
              enabled: true
              max_bytes: 2147483648
              max_deletes: 50000
              path: /var/lib/stanchion/archive/records.sqlite3
              sweep_every: 21600.0
            forecourt:
              day_close: 00:00
              roles: {}
              timezone: UTC
            ingest:
              log_root: /var/log/pods
              max_line_bytes: 1048576
              poll_interval: 5.0
              read_chunk: 65536
              units: []
            labels:
              brand: bluebird
            redact:
              enabled: true
              keep_first: 6
              keep_last: 4
              rules: {}
            ship:
              anchor_day: 1
              attempt_timeout: 30.0
              batch: 262144
              batch_records: 2000
              delete_acknowledged: true
              endpoint: https://collector.example.net/v1/ingest
              interval: 300.0
              monthly_allowance: 5000000000
            site: US-TX-0417
            spool:
              budget: 1073741824
              compress: true
              directory: /var/lib/stanchion/spool
              reserve_ratio: 0.1
              segment: 16777216
              sync_every: 0
            """;

    @TempDir
    Path tmp;

    Path write(String text) throws IOException {
        Path target = tmp.resolve("site.yaml");
        Files.writeString(target, text, StandardCharsets.UTF_8);
        return target;
    }

    @Test
    void parseYamlReturnsAPlainDict() {
        assertThat(ConfigFile.parseYaml("site: US-TX-0417")).isEqualTo(Map.of("site", "US-TX-0417"));
    }

    @Test
    void parseYamlRejectsABrokenDocument() {
        assertThatThrownBy(() -> ConfigFile.parseYaml("site: [unclosed"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("not valid yaml");
    }

    @Test
    void parseYamlRejectsAnEmptyDocument() {
        assertThatThrownBy(() -> ConfigFile.parseYaml("\n\n# just a comment\n"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("config file is empty");
    }

    @Test
    void parseYamlRejectsATopLevelList() {
        assertThatThrownBy(() -> ConfigFile.parseYaml("- site: US-TX-0417"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be a mapping");
    }

    @Test
    void parseYamlWillNotConstructArbitraryObjects() {
        assertThatThrownBy(() -> ConfigFile.parseYaml("site: !!python/object/apply:os.system ['echo hi']"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("not valid yaml");
    }

    @Test
    void loadReadsEverySection() throws IOException {
        SiteConfig cfg = ConfigFile.loadSiteConfig(write(SITE_YAML), Map.of());
        assertThat(cfg.site()).isEqualTo("US-TX-0417");
        assertThat(cfg.ingest().logRoot()).isEqualTo("/var/log/pods");
        assertThat(cfg.ingest().pollInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(cfg.spool().budgetBytes()).isEqualTo(1073741824L);
        assertThat(cfg.ship().monthlyAllowanceBytes()).isEqualTo(5_000_000_000L);
        assertThat(cfg.labels()).isEqualTo(Map.of("brand", "bluebird"));
    }

    @Test
    void loadReportsAMissingFile() {
        assertThatThrownBy(() -> ConfigFile.loadSiteConfig(tmp.resolve("absent.yaml"), Map.of()))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("config file not found");
    }

    @Test
    void loadReportsADirectoryAsUnreadable() {
        assertThatThrownBy(() -> ConfigFile.loadSiteConfig(tmp, Map.of()))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("config file unreadable");
    }

    @Test
    void envOverlayReplacesAScalar() throws IOException {
        SiteConfig cfg = ConfigFile.loadSiteConfig(write(SITE_YAML), Map.of("STANCHION_SPOOL_BUDGET", "64MiB"));
        assertThat(cfg.spool().budgetBytes()).isEqualTo(64L * 1024 * 1024);
        assertThat(cfg.spool().segmentBytes()).isEqualTo(16L * 1024 * 1024);
    }

    @Test
    void envOverlayCanMoveTheSite() throws IOException {
        SiteConfig cfg = ConfigFile.loadSiteConfig(write(SITE_YAML), Map.of("STANCHION_SITE", "GB-LDN-0009"));
        assertThat(cfg.site()).isEqualTo("GB-LDN-0009");
    }

    @Test
    void envOverlayRejectsAnUnknownVariable() {
        assertThatThrownBy(() ->
                        ConfigFile.applyEnv(Map.of("site", "US-TX-0417"), Map.of("STANCHION_SPOOL_BUDGT", "1MiB")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown environment override");
    }

    @Test
    void envOverlayIgnoresUnrelatedVariables() {
        Map<String, Object> merged =
                ConfigFile.applyEnv(Map.of("site", "US-TX-0417"), Map.of("PATH", "/usr/bin", "HOME", "/root"));
        assertThat(merged).isEqualTo(Map.of("site", "US-TX-0417"));
    }

    @Test
    void envOverlayCreatesAMissingSection() {
        Map<String, Object> merged =
                ConfigFile.applyEnv(Map.of("site", "US-TX-0417"), Map.of("STANCHION_ENDPOINT", "http://10.0.0.9/v1"));
        assertThat(merged.get("ship")).isEqualTo(Map.of("endpoint", "http://10.0.0.9/v1"));
    }

    @Test
    void envOverlayDoesNotMutateTheInput() {
        Map<String, Object> raw = Maps.of("site", "US-TX-0417", "spool", Maps.of("budget", "1GiB"));
        ConfigFile.applyEnv(raw, Map.of("STANCHION_SPOOL_BUDGET", "64MiB"));
        assertThat(raw.get("spool")).isEqualTo(Map.of("budget", "1GiB"));
    }

    @Test
    void envOverlayStillValidates() {
        assertThatThrownBy(() -> ConfigFile.loadSiteConfig(write(SITE_YAML), Map.of("STANCHION_SITE", "nope")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("malformed site id");
    }

    @Test
    void loadRejectsAnUnknownSection() {
        assertThatThrownBy(() -> ConfigFile.loadSiteConfig(write("site: US-TX-0417\nshipp: {}\n"), Map.of()))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown config key");
    }

    @Test
    void dumpRoundTripsThroughLoad() throws IOException {
        SiteConfig original = ConfigFile.loadSiteConfig(write(SITE_YAML), Map.of());
        String rendered = ConfigFile.dumpSiteConfig(original);
        assertThat(rendered).contains("site: US-TX-0417").contains("budget: 1073741824");
    }

    @Test
    void dumpIsDeterministic() throws IOException {
        SiteConfig cfg = ConfigFile.loadSiteConfig(write(SITE_YAML), Map.of());
        assertThat(ConfigFile.dumpSiteConfig(cfg)).isEqualTo(ConfigFile.dumpSiteConfig(cfg));
    }

    @Test
    void dumpUsesBlockStyleSoSupportCanReadIt() throws IOException {
        // An empty mapping still renders as {}, which is fine. What must not appear
        // is a flow mapping with anything in it, because that is the form that runs
        // a whole section onto one unreadable line.
        SiteConfig cfg = ConfigFile.loadSiteConfig(write(SITE_YAML), Map.of());
        String rendered = ConfigFile.dumpSiteConfig(cfg);
        assertThat(rendered).contains("{}");
        assertThat(rendered.replace("{}", "")).doesNotContain("{");
    }

    @Test
    void aMinimalConfigOnlyNeedsASite() throws IOException {
        SiteConfig cfg = ConfigFile.loadSiteConfig(write("site: US-TX-0417\n"), Map.of());
        assertThat(cfg.ingest().logRoot()).isEqualTo("/var/log/containers");
        assertThat(cfg.ship().batchRecords()).isEqualTo(2000);
    }

    @Test
    void theDumpKeepsItsExactLayout() throws IOException {
        SiteConfig cfg = ConfigFile.loadSiteConfig(write(SITE_YAML), Map.of());
        assertThat(ConfigFile.dumpSiteConfig(cfg)).isEqualTo(SITE_DUMP);
    }

    @Test
    void aDumpedConfigLoadsBackToTheSameConfig() throws IOException {
        SiteConfig cfg = ConfigFile.loadSiteConfig(write(SITE_YAML), Map.of());
        assertThat(ConfigFile.loadSiteConfig(write(ConfigFile.dumpSiteConfig(cfg)), Map.of()))
                .isEqualTo(cfg);
    }

    /** A site whose labels hold non-ASCII text, including a character beyond the basic plane. */
    static final String UNICODE_YAML = "site: US-TX-0417\n"
            + "spool:\n"
            + "  directory: /var/lib/stanchion/spool\n"
            + "labels:\n"
            + "  brand: Caf\u00E9 \u00C9t\u00E9\n"
            + "  price: \u20AC5 a litre\n"
            + "  smile: pump \uD83D\uDE00 ok\n"
            + "  r\u00E9gion: sud\n"
            + "  gap: \"a\u00A0b\u2028c\u0085d\"\n"
            + "  quoted: '\u00E9 says \"hi\" \\\\ back'\n";

    /** Its dump, byte for byte: every non-ASCII character escaped with upper case hex digits. */
    static final String UNICODE_DUMP = "archive:\n"
            + "  enabled: true\n"
            + "  max_bytes: 2147483648\n"
            + "  max_deletes: 50000\n"
            + "  path: /var/lib/stanchion/archive/records.sqlite3\n"
            + "  sweep_every: 21600.0\n"
            + "forecourt:\n"
            + "  day_close: 00:00\n"
            + "  roles: {}\n"
            + "  timezone: UTC\n"
            + "ingest:\n"
            + "  log_root: /var/log/containers\n"
            + "  max_line_bytes: 1048576\n"
            + "  poll_interval: 2.0\n"
            + "  read_chunk: 65536\n"
            + "  units: []\n"
            + "labels:\n"
            + "  brand: \"Caf\\xE9 \\xC9t\\xE9\"\n"
            + "  gap: \"a\\_b\\Lc d\"\n"
            + "  price: \"\\u20AC5 a litre\"\n"
            + "  quoted: \"\\xE9 says \\\"hi\\\" \\\\\\\\ back\"\n"
            + "  \"r\\xE9gion\": sud\n"
            + "  smile: \"pump \\U0001F600 ok\"\n"
            + "redact:\n"
            + "  enabled: true\n"
            + "  keep_first: 6\n"
            + "  keep_last: 4\n"
            + "  rules: {}\n"
            + "ship:\n"
            + "  anchor_day: 1\n"
            + "  attempt_timeout: 30.0\n"
            + "  batch: 262144\n"
            + "  batch_records: 2000\n"
            + "  delete_acknowledged: true\n"
            + "  endpoint: https://collector.invalid/v1/ingest\n"
            + "  interval: 300.0\n"
            + "  monthly_allowance: 2000000000\n"
            + "site: US-TX-0417\n"
            + "spool:\n"
            + "  budget: 536870912\n"
            + "  compress: true\n"
            + "  directory: /var/lib/stanchion/spool\n"
            + "  reserve_ratio: 0.1\n"
            + "  segment: 8388608\n"
            + "  sync_every: 0\n";

    @Test
    void nonAsciiIsEscapedWithUpperCaseHex() throws IOException {
        SiteConfig cfg = ConfigFile.loadSiteConfig(write(UNICODE_YAML), Map.of());
        assertThat(ConfigFile.dumpSiteConfig(cfg)).isEqualTo(UNICODE_DUMP);
    }

    @Test
    void theConfigCommandPrintsTheSameEscapes() throws IOException {
        CliRunner.Result result =
                CliRunner.invoke("--config", write(UNICODE_YAML).toString(), "config");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo(UNICODE_DUMP);
    }

    @Test
    void anEscapedDumpLoadsBackToTheSameLabels() throws IOException {
        SiteConfig cfg = ConfigFile.loadSiteConfig(write(UNICODE_YAML), Map.of());
        assertThat(cfg.labels())
                .isEqualTo(Maps.of(
                        "brand",
                        "Caf\u00E9 \u00C9t\u00E9",
                        "price",
                        "\u20AC5 a litre",
                        "smile",
                        "pump \uD83D\uDE00 ok",
                        "r\u00E9gion",
                        "sud",
                        "gap",
                        "a\u00A0b\u2028c d",
                        "quoted",
                        "\u00E9 says \"hi\" \\\\ back"));
        assertThat(ConfigFile.loadSiteConfig(write(ConfigFile.dumpSiteConfig(cfg)), Map.of())
                        .labels())
                .isEqualTo(cfg.labels());
    }

    @Test
    void aBackslashOutsideADoubleQuotedScalarIsLeftAlone() {
        // Only the double quoted scalar holds escapes; its escaped backslash before xcd is not one.
        String text = "pattern: 'driver=(\\d{4}) \\xab'\nplain: a\\xab\nquoted: \"\\xab \\\\xcd\"\n";
        assertThat(ConfigFile.upperCaseEscapes(text))
                .isEqualTo("pattern: 'driver=(\\d{4}) \\xab'\nplain: a\\xab\nquoted: \"\\xAB \\\\xcd\"\n");
    }
}
