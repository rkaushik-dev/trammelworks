package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.forecourt.FuellingPoint;
import io.github.rkaushikdev.trammelworks.forecourt.PumpState;
import io.github.rkaushikdev.trammelworks.forecourt.ResponseCode;
import io.github.rkaushikdev.trammelworks.ingest.CriLine;
import io.github.rkaushikdev.trammelworks.ingest.Discover;
import io.github.rkaushikdev.trammelworks.redact.Pans;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SimulateTest {

    @TempDir
    Path tmp;

    static Simulation sim(long seed) {
        return new Simulation(seed);
    }

    static List<String> lines(Map<String, List<String>> grouped, String unit) {
        return grouped.getOrDefault(unit, List.of());
    }

    static String sha256(byte[] data) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void theSameSeedGivesTheSameCorpus() {
        assertThat(sim(7).criLines(50)).isEqualTo(sim(7).criLines(50));
    }

    @Test
    void aDifferentSeedGivesADifferentCorpus() {
        assertThat(sim(1).criLines(50)).isNotEqualTo(sim(2).criLines(50));
    }

    @Test
    void everyUnitEventuallySpeaks() {
        assertThat(sim(3).criLines(2000).keySet()).isEqualTo(Simulation.WEIGHTS.keySet());
    }

    @Test
    void theTankGaugeIsTheLoudest() {
        Map<String, List<String>> found = sim(3).criLines(2000);
        assertThat(found.get("tank-gauge").size())
                .isGreaterThan(found.get("payment-kernel").size());
        assertThat(found.get("payment-kernel").size())
                .isGreaterThan(found.get("price-sign").size());
    }

    @Test
    void everyLineParsesAsCri() {
        for (List<String> group : sim(5).criLines(400).values()) {
            for (String line : group) {
                assertThat(CriLine.parse(line.getBytes(StandardCharsets.UTF_8)).message())
                        .isNotEmpty();
            }
        }
    }

    @Test
    void theTimestampsOnlyGoForwards() {
        List<NanoTime> stamps = new ArrayList<>();
        for (List<String> group : sim(5).criLines(400).values()) {
            for (String line : group) {
                stamps.add(CriLine.parse(line.getBytes(StandardCharsets.UTF_8)).timestamp());
            }
        }
        List<NanoTime> sorted = stamps.stream().sorted().toList();
        assertThat(stamps.equals(sorted) || stamps.stream().distinct().count() > 1)
                .isTrue();
    }

    @Test
    void thePaymentKernelWritesRealCardNumbers() {
        List<String> found = new ArrayList<>();
        for (String line : lines(sim(11).criLines(800), "payment-kernel")) {
            Pans.findPans(line).forEach(match -> found.add(match.digits()));
        }
        assertThat(found)
                .as("the corpus is meant to contain something to redact")
                .isNotEmpty();
    }

    @Test
    void thePaymentKernelAnswersWithAFieldThirtyNineAgainstAPump() {
        List<String> found = lines(sim(11).criLines(800), "payment-kernel");
        assertThat(found).anyMatch(line -> line.contains("rc=00") && line.contains("pump="));
        assertThat(found.stream().filter(line -> line.contains("auth response")))
                .allMatch(line -> ResponseCode.find(line).isPresent()
                        && FuellingPoint.find(line).isPresent());
    }

    @Test
    void thePumpBridgeReportsIfsfStates() {
        List<String> found = lines(sim(11).criLines(800), "pump-bridge");
        assertThat(found).anyMatch(line -> PumpState.find(line)
                .filter(PumpState.AUTHORISED::equals)
                .isPresent());
        assertThat(found)
                .anyMatch(line ->
                        PumpState.find(line).filter(PumpState.CALLING::equals).isPresent());
    }

    @Test
    void aStackTraceAppears() {
        List<String> found = lines(new Simulation(13, "2025-03-04T06:00:00Z", 4.0, 5).criLines(200), "pump-bridge");
        assertThat(found).anyMatch(line -> line.contains("at Pump.reset"));
    }

    @Test
    void chronydWritesAClockCorrection() {
        assertThat(lines(sim(17).criLines(1500), "chronyd")).anyMatch(line -> line.contains("System clock wrong by"));
    }

    @Test
    void thePumpBridgeUsesStderrSometimes() {
        assertThat(lines(sim(19).criLines(1500), "pump-bridge")).anyMatch(line -> line.contains(" stderr "));
    }

    @Test
    void aLongMessageIsSplitIntoChunks() {
        List<String> chunks = Simulation.splitCri("2025-03-04T12:00:00.000000000Z", "stdout", "x".repeat(100), 30);
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).split(" ", 4)[2]).isEqualTo("P");
        assertThat(chunks.get(chunks.size() - 1).split(" ", 4)[2]).isEqualTo("F");
    }

    @Test
    void aShortMessageIsOneChunk() {
        assertThat(Simulation.splitCri("2025-03-04T12:00:00.000000000Z", "stdout", "short", 30))
                .hasSize(1);
    }

    @Test
    void writingACorpusMakesFilesTheParserRecognises() {
        Map<String, Path> paths = sim(23).writeCri(tmp.resolve("log"), 300);
        assertThat(paths).isNotEmpty();
        paths.forEach((unit, target) -> assertThat(
                        LogPaths.parseLogPath(PosixPath.asPosix(target)).unit())
                .isEqualTo(unit));
    }

    @Test
    void aWrittenCorpusIsDiscovered() {
        sim(23).writeCri(tmp.resolve("log"), 300);
        assertThat(Discover.discover(tmp.resolve("log")).follow()).hasSize(Simulation.WEIGHTS.size());
    }

    @Test
    void writingTwiceAppends() throws IOException {
        Simulation generator = sim(29);
        Map<String, Path> paths = generator.writeCri(tmp.resolve("log"), 100);
        Map<Path, Long> sizes = new HashMap<>();
        for (Path path : paths.values()) {
            sizes.put(path, Files.size(path));
        }
        generator.writeCri(tmp.resolve("log"), 100);
        for (Map.Entry<Path, Long> entry : sizes.entrySet()) {
            assertThat(Files.size(entry.getKey())).isGreaterThan(entry.getValue());
        }
    }

    @Test
    void theJsonFileFormIsValidJson() throws IOException {
        Path target = sim(31).writeJsonfile(tmp.resolve("docker.log"), 100);
        for (String line : Files.readAllLines(target, StandardCharsets.UTF_8)) {
            Map<String, Object> payload = Json.loadsObject(line);
            assertThat((String) payload.get("log")).endsWith("\n");
            assertThat(NanoTime.parse((String) payload.get("time"))).isNotNull();
        }
    }

    @Test
    void theWrittenCountFollows() {
        Simulation generator = sim(37);
        generator.writeCri(tmp.resolve("log"), 40);
        assertThat(generator.written()).isEqualTo(40);
    }

    @Test
    void theClockMovesForwardBetweenEvents() {
        Simulation generator = sim(41);
        NanoTime first = generator.at();
        generator.criLines(10);
        assertThat(generator.at()).isGreaterThan(first);
    }

    @ParameterizedTest
    @CsvSource({
        "3, 2000, 60, ec3ef7dba17e44a0b66f4501e208bf7c3256362fbc2b0c0d6517559bf34d3c94, 2025-03-04T08:09:14.058082909Z",
        "101, 400, 60, aa450dbca6cac13741ba90e4595f10a79bce5cda78b722dfae016fb85564f8e1, 2025-03-04T06:25:18.854432125Z",
        "13, 200, 5, dcc6a670e94465d6eb1e3741f14072d2705fb2d6fb5748b99658659b0c119aea, 2025-03-04T06:13:15.594815466Z"
    })
    void aSeedAlwaysWritesTheSameCorpus(long seed, int count, int traceEvery, String digest, String last)
            throws NoSuchAlgorithmException {
        // Pinned so a seed named in a bug report reproduces the same corpus on any machine.
        Simulation generator = new Simulation(seed, "2025-03-04T06:00:00Z", 4.0, traceEvery);
        Map<String, List<String>> grouped = generator.criLines(count);
        StringBuilder blob = new StringBuilder();
        grouped.forEach((unit, group) -> blob.append(unit).append('\n').append(String.join("", group)));
        assertThat(sha256(blob.toString().getBytes(StandardCharsets.UTF_8))).isEqualTo(digest);
        assertThat(generator.at().isoformat()).isEqualTo(last);
    }

    @Test
    void theJsonFileCorpusIsFixedToo() throws IOException, NoSuchAlgorithmException {
        Path target = sim(31).writeJsonfile(tmp.resolve("docker.log"), 100);
        assertThat(sha256(Files.readAllBytes(target)))
                .isEqualTo("7ee83efedba8bbdbd86e954d2fe7609890944eae4a1caa2a9078bb1de37426c4");
    }

    @Test
    void theGeneratorDrawsTheFixedSequence() {
        SeededRandom random = new SeededRandom(41);
        assertThat(random.random()).isEqualTo(0.38102068999577143);
        assertThat(random.getrandbits(37)).isEqualTo(134134917504L);
        assertThat(random.randint(0, 9)).isEqualTo(2);
        assertThat(random.expovariate(0.25)).isEqualTo(9.291379669282556);
    }
}
