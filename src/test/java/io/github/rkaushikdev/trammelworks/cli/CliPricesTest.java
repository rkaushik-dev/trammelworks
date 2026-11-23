package io.github.rkaushikdev.trammelworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliPricesTest {

    @TempDir
    Path tmp;

    Path config;

    @BeforeEach
    void setUp() {
        config = CliFixtures.forecourtConfig(tmp);
    }

    void fill(List<List<String>> lines) {
        CliFixtures.archiveTagged(tmp, lines);
    }

    @Test
    void aPumpThatMissedAChangeIsListed() {
        fill(List.of(
                List.of("pump-bridge", "06:00:00", "FP01 price unleaded 1.459"),
                List.of("pump-bridge", "06:00:00", "FP02 price unleaded 1.459"),
                List.of("price-sign", "06:00:01", "price unleaded 1459"),
                List.of("price-sign", "09:00:00", "price unleaded 1479"),
                List.of("pump-bridge", "09:00:20", "FP01 price unleaded 1.479"),
                List.of("pump-bridge", "09:45:00", "FP02 price unleaded 1.479")));
        CliRunner.Result result = CliFixtures.invoke(config, "prices");
        assertThat(result.exitCode()).isEqualTo(PricesCommand.EXIT_MISMATCH);
        assertThat(result.stdout().lines().toList())
                .containsExactly("pump 2 unleaded sign 1.479 pump 1.459 from 2025-03-04T09:00:00.000000000Z"
                        + " until 2025-03-04T09:45:00.000000000Z (45m)");
    }

    @Test
    void aSiteWhosePumpsKeptUpSaysSo() {
        fill(List.of(
                List.of("price-sign", "06:00:00", "price diesel 1529"),
                List.of("pump-bridge", "06:00:30", "FP01 price diesel 1.529")));
        CliRunner.Result result = CliFixtures.invoke(config, "prices");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("every pump charged what the sign showed\n");
    }
}
