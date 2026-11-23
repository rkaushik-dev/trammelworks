package io.github.rkaushikdev.trammelworks.cli;

import static io.github.rkaushikdev.trammelworks.LogFiles.criFull;
import static io.github.rkaushikdev.trammelworks.cli.CliFixtures.posix;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.SiteTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliDoctorTest {

    static final String TS = "2025-03-04T12:00:00.000000000Z";

    @TempDir
    Path tmp;

    SiteTree tree;

    @BeforeEach
    void setUp() {
        tree = new SiteTree(tmp.resolve("log"));
    }

    Path writeConfig(String endpoint) {
        return CliFixtures.writeConfig(
                tmp,
                "site: US-TX-0417\n"
                        + "ingest:\n"
                        + "  log_root: " + posix(tree.root()) + "\n"
                        + "spool:\n"
                        + "  directory: " + posix(tmp.resolve("spool")) + "\n"
                        + "archive:\n"
                        + "  path: " + posix(tmp.resolve("archive").resolve("records.sqlite3")) + "\n"
                        + "  retention:\n"
                        + "    payment: 365d\n"
                        + "ship:\n"
                        + "  endpoint: " + endpoint + "\n");
    }

    /** A forecourt with all three containers it is read by, keeping alarms for a year. */
    Path good() {
        for (String unit : List.of("pump-bridge", "payment-kernel", "tank-gauge")) {
            tree.writeCri(unit, List.of(criFull(TS, "up")));
        }
        return writeConfig("file://" + posix(tmp.resolve("outbox")));
    }

    Path warned() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        return writeConfig("http://10.0.0.9/v1");
    }

    Path broken() throws IOException {
        Files.createDirectories(tree.root());
        return writeConfig("file://" + posix(tmp.resolve("outbox")));
    }

    static CliRunner.Result invoke(Path config, String... args) {
        String[] full = new String[args.length + 1];
        full[0] = "doctor";
        System.arraycopy(args, 0, full, 1, args.length);
        return CliFixtures.invoke(config, full);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> findings(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("findings");
    }

    @Test
    void aGoodSiteExitsZero() {
        CliRunner.Result result = invoke(good());
        assertThat(result.exitCode()).isEqualTo(DoctorCommand.EXIT_HEALTHY);
        assertThat(result.output()).contains("0 failures, 0 warnings");
    }

    @Test
    void theSiteIsNamed() {
        assertThat(invoke(good()).output()).contains("checking US-TX-0417");
    }

    @Test
    void aWarnedSiteExitsTwo() {
        CliRunner.Result result = invoke(warned());
        assertThat(result.exitCode()).isEqualTo(DoctorCommand.EXIT_WARNED);
        assertThat(result.output()).contains("plain http");
    }

    @Test
    void strictTurnsAWarningIntoAFailure() {
        assertThat(invoke(warned(), "--strict").exitCode()).isEqualTo(DoctorCommand.EXIT_BROKEN);
    }

    @Test
    void aBrokenSiteExitsOne() throws IOException {
        CliRunner.Result result = invoke(broken());
        assertThat(result.exitCode()).isEqualTo(DoctorCommand.EXIT_BROKEN);
        assertThat(result.output()).contains("FAIL");
    }

    @Test
    void quietHidesTheNotes() {
        CliRunner.Result result = invoke(good(), "--quiet");
        assertThat(result.output()).doesNotContain("NOTE").contains("0 failures");
    }

    @Test
    void theNotesAreShownByDefault() {
        assertThat(invoke(good()).output()).contains("NOTE");
    }

    @Test
    void theJsonFormParses() {
        Map<String, Object> payload = Json.loadsObject(invoke(good(), "--json").output());
        assertThat(payload).containsEntry("site", "US-TX-0417").containsEntry("healthy", true);
        assertThat(findings(payload)).isNotEmpty();
    }

    @Test
    void theJsonFormCarriesEveryFinding() throws IOException {
        Map<String, Object> payload =
                Json.loadsObject(invoke(broken(), "--json").output());
        assertThat(payload).containsEntry("healthy", false);
        assertThat(findings(payload)).anyMatch(item -> "fail".equals(item.get("level")));
    }

    @Test
    void aMissingConfigExitsOne() {
        CliRunner.Result result =
                CliRunner.invoke("--config", tmp.resolve("nope.yaml").toString(), "doctor");
        assertThat(result.exitCode()).isEqualTo(DoctorCommand.EXIT_BROKEN);
        assertThat(result.output()).contains("stanchion.config");
    }

    @Test
    void theCheckCreatesWhatItProbes() {
        invoke(good());
        assertThat(tmp.resolve("spool")).isDirectory();
        assertThat(tmp.resolve("outbox")).isDirectory();
        assertThat(tmp.resolve("archive").resolve("records.sqlite3")).exists();
    }

    @Test
    void runningTwiceGivesTheSameAnswer() {
        Path config = good();
        CliRunner.Result first = invoke(config);
        CliRunner.Result second = invoke(config);
        assertThat(first.exitCode()).isEqualTo(second.exitCode());
        assertThat(first.output().split("FAIL", -1).length)
                .isEqualTo(second.output().split("FAIL", -1).length);
    }
}
