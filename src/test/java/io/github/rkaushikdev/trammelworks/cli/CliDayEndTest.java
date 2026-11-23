package io.github.rkaushikdev.trammelworks.cli;

import static io.github.rkaushikdev.trammelworks.cli.CliFixtures.posix;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliDayEndTest {

    @TempDir
    Path tmp;

    Path chicago() {
        return CliFixtures.writeConfig(
                tmp,
                "site: US-TX-0417\nspool:\n  directory: " + posix(tmp.resolve("spool")) + "\narchive:\n  path: "
                        + posix(CliFixtures.archiveFile(tmp)) + "\nforecourt:\n  timezone: America/Chicago\n"
                        + "  day_close: '05:00'\n");
    }

    @Test
    void aNamedDayIsCutAtTheSitesClose() {
        Path config = chicago();
        CliFixtures.archiveTagged(
                tmp,
                List.of(
                        List.of("pump-bridge", "10:30:00", "FP2 COMPLETED 12 litres"),
                        List.of("pump-bridge", "11:30:00", "FP2 COMPLETED 30.5 litres"),
                        List.of("pump-bridge", "11:45:00", "FP3 COMPLETED 8.25 litres")));
        CliRunner.Result result = CliFixtures.invoke(config, "dayend", "2025-03-04");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().lines().toList())
                .containsExactly(
                        "US-TX-0417 business day 2025-03-04 (closing 05:00 America/Chicago)",
                        "  pump 2       1 sales       30.5 l",
                        "  pump 3       1 sales       8.25 l",
                        "  total        2 sales      38.75 l",
                        "  payments 0 declines, 0 unanswered",
                        "  alarms   0 raised, 0 environmental",
                        "  pumps    0 unauthorised deliveries, 0 off the sign price");
    }

    @Test
    void theDayBeforeHoldsTheSmallHours() {
        Path config = chicago();
        CliFixtures.archiveTagged(tmp, List.of(List.of("pump-bridge", "10:30:00", "FP2 COMPLETED 12 litres")));
        assertThat(CliFixtures.invoke(config, "dayend", "2025-03-03").stdout()).contains("pump 2       1 sales");
    }

    @Test
    void withNoDateTheLastClosedDayIsWritten() {
        Path config = chicago();
        CliFixtures.archiveTagged(tmp, List.of(List.of("pump-bridge", "10:30:00", "FP2 COMPLETED 12 litres")));
        CliRunner.Result result = CliFixtures.invoke(config, "dayend");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("business day ").contains("total        0 sales          0 l");
    }

    @Test
    void aMalformedDateIsRefused() {
        CliRunner.Result result = CliFixtures.invoke(chicago(), "dayend", "4 March");
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_CONFIG);
        assertThat(result.stderr()).contains("YYYY-MM-DD");
    }
}
