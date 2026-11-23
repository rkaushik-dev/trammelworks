package io.github.rkaushikdev.trammelworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliDisputeTest {

    static final String HEADER =
            "time,unit,class,stream,confidence,pump,pump_state,auth_stage,amount,ref,response,outcome,message";

    @TempDir
    Path tmp;

    Path config;

    @BeforeEach
    void setUp() {
        config = CliFixtures.forecourtConfig(tmp);
        CliFixtures.archiveTagged(
                tmp,
                List.of(
                        List.of("payment-kernel", "09:10:00", "preauth pump=3 amount=100.00 ref 551203"),
                        List.of("payment-kernel", "09:10:01", "auth response pump=3 rc=00 ref 551203"),
                        List.of("pump-bridge", "09:10:05", "FP03 AUTHORISED"),
                        List.of("pump-bridge", "09:10:30", "FP04 FUELLING"),
                        List.of("pump-bridge", "09:13:40", "FP03 COMPLETED 61.2 litres"),
                        List.of("payment-kernel", "09:14:00", "completion amount=102.35 ref 551203"),
                        List.of("pump-bridge", "11:00:00", "FP03 IDLE")));
    }

    static List<String> rows(CliRunner.Result result) {
        return result.stdout().lines().toList();
    }

    @Test
    void aReferenceGathersItsPumpAroundTheSale() {
        CliRunner.Result result = CliFixtures.invoke(config, "dispute", "--ref", "551203");
        assertThat(result.exitCode()).isZero();
        assertThat(rows(result))
                .containsExactly(
                        HEADER,
                        "2025-03-04T09:10:00.000000000Z,payment-kernel,standard,stdout,unknown,3,,preauth,100.00,551203,,,"
                                + "preauth pump=3 amount=100.00 ref 551203",
                        "2025-03-04T09:10:01.000000000Z,payment-kernel,standard,stdout,unknown,3,,,,,00,approved,"
                                + "auth response pump=3 rc=00 ref 551203",
                        "2025-03-04T09:10:05.000000000Z,pump-bridge,standard,stdout,unknown,3,authorised,,,,,,"
                                + "FP03 AUTHORISED",
                        "2025-03-04T09:13:40.000000000Z,pump-bridge,standard,stdout,unknown,3,completed,,,,,,"
                                + "FP03 COMPLETED 61.2 litres",
                        "2025-03-04T09:14:00.000000000Z,payment-kernel,standard,stdout,unknown,,,completion,102.35,"
                                + "551203,,,completion amount=102.35 ref 551203");
    }

    @Test
    void aPumpAndATimeGatherTheSameSaleWithoutTheReference() {
        CliRunner.Result result =
                CliFixtures.invoke(config, "dispute", "--pump", "3", "--at", "2025-03-04T09:12:00Z", "--window", "5m");
        assertThat(rows(result))
                .hasSize(5)
                .noneMatch(row -> row.contains("FP04"))
                .noneMatch(row -> row.contains("IDLE"));
        assertThat(rows(result).get(4)).endsWith("FP03 COMPLETED 61.2 litres");
    }

    @Test
    void theSheetCanBeAMarkdownTableForTheTicket() {
        CliRunner.Result result = CliFixtures.invoke(config, "dispute", "--ref", "551203", "--format", "markdown");
        assertThat(rows(result).get(0))
                .isEqualTo("| time | unit | class | stream | pump | pump_state | auth_stage | amount | ref | response"
                        + " | outcome | message |");
    }

    @Test
    void anUnknownReferenceExitsFour() {
        CliRunner.Result result = CliFixtures.invoke(config, "dispute", "--ref", "999999");
        assertThat(result.exitCode()).isEqualTo(DisputeCommand.EXIT_EMPTY);
        assertThat(result.stderr()).contains("no payment line carries reference 999999");
    }

    @Test
    void aQuietPumpExitsFour() {
        CliRunner.Result result = CliFixtures.invoke(config, "dispute", "--pump", "7", "--at", "2025-03-04T09:12:00Z");
        assertThat(result.exitCode()).isEqualTo(DisputeCommand.EXIT_EMPTY);
        assertThat(result.stderr()).contains("nothing on pump 7 within 15m of 2025-03-04T09:12:00.000000000Z");
    }

    @Test
    void aPumpWithNoTimeIsRefused() {
        CliRunner.Result result = CliFixtures.invoke(config, "dispute", "--pump", "3");
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_CONFIG);
        assertThat(result.stderr()).contains("give a reference, or a pump and a time");
    }

    @Test
    void aReferenceThatNamesNoPumpNeedsOne() {
        CliFixtures.archiveTagged(
                tmp, List.of(List.of("payment-kernel", "13:00:00", "completion amount=9 ref 777000")));
        assertThat(CliFixtures.invoke(config, "dispute", "--ref", "777000").stderr())
                .contains("names no pump");
        CliRunner.Result given = CliFixtures.invoke(config, "dispute", "--ref", "777000", "--pump", "3");
        assertThat(given.exitCode()).isZero();
        assertThat(rows(given)).hasSize(2);
        assertThat(rows(given).get(1)).endsWith("completion amount=9 ref 777000");
    }
}
