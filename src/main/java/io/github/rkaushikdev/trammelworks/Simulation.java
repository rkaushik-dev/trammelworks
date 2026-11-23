package io.github.rkaushikdev.trammelworks;

import io.github.rkaushikdev.trammelworks.redact.Luhn;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writing a forecourt's worth of container logs, on demand.
 *
 * <p>Unit tests build traffic a line at a time, which is useless for the questions that only appear
 * at volume: does the spool roll, does eviction pick the right class, does a shift's worth of
 * records come out of the other end in order. So this writes a corpus, and it is deliberately not
 * random noise. A forecourt has a shape:
 *
 * <ul>
 *   <li>the tank gauge is the loudest thing on the box and says almost nothing
 *   <li>the payment kernel is quiet and every line of it matters
 *   <li>the pump bridge writes stack traces when a dispenser resets, several lines each
 *   <li>the price sign speaks twice a day
 *   <li>chronyd corrects the clock when the modem comes up
 * </ul>
 *
 * <p>Seeded, so a failure is reproducible. An unseeded generator finds a bug once and then cannot be
 * asked about it again.
 */
public final class Simulation {

    /** The longest chunk the container runtime writes before splitting a line. */
    public static final int CRI_CHUNK = 16 * 1024;

    /** Roughly how often each unit says anything, relative to the others. */
    public static final Map<String, Integer> WEIGHTS = weights();

    static final List<String> TANK_LINES =
            List.of("tank {tank} level {level} ml temperature {temp}C", "tank {tank} water 0 ml", "probe {tank} ok");

    static final List<String> PUMP_LINES = List.of(
            "FP{pump} CALLING nozzle lifted",
            "FP{pump} COMPLETED nozzle replaced, {litres} litres",
            "FP{pump} AUTHORISED",
            "FP{pump} reset requested");

    /** What the kernel writes: the request with the card, then the host's field 39 against the pump. */
    static final List<String> PAYMENT_LINES = List.of(
            "auth request pump={pump} pan={pan} amount={amount}",
            "auth response pump={pump} rc=00 ref {ref}",
            "auth response pump={pump} rc=51 ref {ref}",
            "settlement batch {ref} closed");

    static final List<String> LOYALTY_LINES =
            List.of("sync started", "sync sent {count} events", "loyalty={loyalty} points {points}");

    static final List<String> TRACE = List.of(
            "java.lang.IllegalStateException: dispenser {pump} did not answer",
            "    at Pump.reset(Pump.java:41)",
            "    at Bridge.poll(Bridge.java:118)",
            "    at Main.run(Main.java:9)");

    private static final long NS_PER_SECOND = 1_000_000_000L;

    private final long seed;
    private final double gapSeconds;
    private final int traceEvery;
    private final SeededRandom rng;
    private NanoTime at;
    private int written;

    /** The default generator: seed 41, starting at six in the morning, four seconds apart. */
    public Simulation() {
        this(41);
    }

    /** A generator with the default shape and a given seed. */
    public Simulation(long seed) {
        this(seed, "2025-03-04T06:00:00Z", 4.0, 60);
    }

    /** A generator with everything given; every {@code traceEvery}th event may be a stack trace. */
    public Simulation(long seed, String start, double gapSeconds, int traceEvery) {
        this.seed = seed;
        this.gapSeconds = gapSeconds;
        this.traceEvery = traceEvery;
        this.rng = new SeededRandom(seed);
        this.at = NanoTime.parse(start);
    }

    private static Map<String, Integer> weights() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("tank-gauge", 50);
        out.put("pump-bridge", 25);
        out.put("payment-kernel", 12);
        out.put("loyalty-sync", 8);
        out.put("price-sign", 3);
        out.put("chronyd", 2);
        return java.util.Collections.unmodifiableMap(out);
    }

    /** The seed. */
    public long seed() {
        return seed;
    }

    /** The instant of the last event generated. */
    public NanoTime at() {
        return at;
    }

    /** Events generated so far. */
    public int written() {
        return written;
    }

    private NanoTime tick() {
        double step = rng.expovariate(1.0 / gapSeconds);
        at = at.shifted((long) (step * NS_PER_SECOND));
        return at;
    }

    private String unit() {
        List<String> names = List.copyOf(WEIGHTS.keySet());
        return rng.choice(names, names.stream().map(WEIGHTS::get).toList());
    }

    private String pan() {
        StringBuilder body = new StringBuilder("4");
        for (int index = 0; index < 14; index++) {
            body.append(rng.randint(0, 9));
        }
        return body + Luhn.luhnCheckDigit(body.toString());
    }

    private static String fill(String template, Map<String, Object> values) {
        String out = template;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return out;
    }

    /** One event from a unit, which may be several lines. */
    public List<String> messageFor(String unit) {
        return switch (unit) {
            case "tank-gauge" -> {
                String template = rng.choice(TANK_LINES);
                long tank = rng.randint(1, 4);
                long level = rng.randint(4000, 42000);
                long temp = rng.randint(4, 28);
                yield List.of(fill(template, Map.of("tank", tank, "level", level, "temp", temp)));
            }
            case "pump-bridge" -> {
                if (written % traceEvery == traceEvery - 1) {
                    List<String> lines = new ArrayList<>();
                    for (String line : TRACE) {
                        lines.add(fill(line, Map.of("pump", rng.randint(1, 8))));
                    }
                    yield lines;
                }
                String template = rng.choice(PUMP_LINES);
                long pump = rng.randint(1, 8);
                long litres = rng.randint(5, 70);
                yield List.of(fill(template, Map.of("pump", pump, "litres", litres)));
            }
            case "payment-kernel" -> {
                String template = rng.choice(PAYMENT_LINES);
                String pan = pan();
                long amount = rng.randint(500, 12000);
                long ref = rng.randint(100000, 999999);
                // The pump comes off the reference rather than its own draw, so a seed still writes
                // the same sequence of cards and amounts it always did.
                long pump = 1 + ref % 8;
                yield List.of(fill(template, Map.of("pan", pan, "amount", amount, "ref", ref, "pump", pump)));
            }
            case "loyalty-sync" -> {
                String template = rng.choice(LOYALTY_LINES);
                long count = rng.randint(1, 40);
                long loyalty = rng.randint(10_000_000_000L, 99_999_999_999L);
                long points = rng.randint(0, 4000);
                yield List.of(fill(template, Map.of("count", count, "loyalty", loyalty, "points", points)));
            }
            case "price-sign" -> List.of("price unleaded " + rng.randint(1200, 1800));
            default ->
                List.of("System clock wrong by -" + Repr.fixed(rng.random(), 6) + " seconds, adjustment started");
        };
    }

    /** Generate CRI lines for {@code count} events, grouped by unit in the order units first spoke. */
    public Map<String, List<String>> criLines(int count) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int event = 0; event < count; event++) {
            String unit = unit();
            NanoTime stamp = tick();
            String stream = unit.equals("pump-bridge") && rng.random() < 0.1 ? "stderr" : "stdout";
            for (String message : messageFor(unit)) {
                out.computeIfAbsent(unit, key -> new ArrayList<>())
                        .addAll(splitCri(stamp.isoformat(), stream, message, CRI_CHUNK));
            }
            written += 1;
        }
        return out;
    }

    /** Write {@code count} events into a CRI shaped log root, appending to what is there. */
    public Map<String, Path> writeCri(Path root, int count) {
        return writeCri(root, count, "forecourt");
    }

    /** Write {@code count} events into a CRI shaped log root under {@code namespace}. */
    public Map<String, Path> writeCri(Path root, int count, String namespace) {
        Map<String, Path> paths = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : criLines(count).entrySet()) {
            String unit = entry.getKey();
            Path folder = root.resolve("pods")
                    .resolve(namespace + "_" + unit + "-0_1f2e3d4c-aaaa")
                    .resolve(unit);
            Path target = folder.resolve("0.log");
            append(target, String.join("", entry.getValue()));
            paths.put(unit, target);
        }
        return paths;
    }

    /** Write {@code count} events in the docker json-file shape, appending to {@code target}. */
    public Path writeJsonfile(Path target, int count) {
        StringBuilder out = new StringBuilder();
        for (int event = 0; event < count; event++) {
            String unit = unit();
            NanoTime stamp = tick();
            for (String message : messageFor(unit)) {
                out.append(Json.dumps(Maps.of("log", message + "\n", "stream", "stdout", "time", stamp.isoformat())))
                        .append('\n');
            }
            written += 1;
        }
        append(target, out.toString());
        return target;
    }

    private static void append(Path target, String text) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            try (Writer handle = Files.newBufferedWriter(
                    target, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                handle.write(text);
            }
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    /** One application line as the runtime would write it, split into partial chunks if long. */
    public static List<String> splitCri(String stamp, String stream, String message, int chunk) {
        List<String> out = new ArrayList<>();
        String rest = message;
        while (rest.codePointCount(0, rest.length()) > chunk) {
            int cut = rest.offsetByCodePoints(0, chunk);
            out.add(stamp + " " + stream + " P " + rest.substring(0, cut) + "\n");
            rest = rest.substring(cut);
        }
        out.add(stamp + " " + stream + " F " + rest + "\n");
        return out;
    }
}
