package io.github.rkaushikdev.trammelworks.ship;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.ManualClock;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Registry;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.spool.Budget;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import io.github.rkaushikdev.trammelworks.spool.Spool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShipSessionTest {

    static final String SITE = "US-TX-0417";
    static final PriorityTable TABLE = new PriorityTable(Map.of("payment-kernel", "payment", "price-sign", "chatter"));

    @TempDir
    Path tmp;

    Path spool;

    @BeforeEach
    void setUp() {
        spool = tmp.resolve("spool");
    }

    static LogRecord record(String unit, String message) {
        return new LogRecord(SITE, unit, Stream.STDOUT, NanoTime.parse("2025-03-04T12:00:00Z"), message);
    }

    void fill(String unit, int count) {
        fill(unit, count, 4096);
    }

    void fill(String unit, int count, long segmentBytes) {
        Spool store = Spool.builder(spool, new Budget(1_000_000, 0.1))
                .table(TABLE)
                .segmentBytes(segmentBytes)
                .build();
        for (int index = 0; index < count; index++) {
            store.append(record(unit, "line " + index));
        }
        store.sealAll();
    }

    /**
     * A meter that has already been through a rollover and spent some. Building one and charging it
     * is not enough: the shipper rolls the period on every pass, and a meter that has never rolled
     * rolls on the first one and starts the period clean.
     */
    static Meter metered(long allowance, long used) {
        Meter meter = new Meter(allowance, 1);
        meter.roll(NanoTime.parse("2025-03-04T12:00:00Z"));
        meter.charge(used);
        return meter;
    }

    Shipper.Builder shipper(Transport transport) {
        return Shipper.builder(SITE, spool, transport)
                .table(TABLE)
                .meter(new Meter(10_000_000, 1))
                .clock(new ManualClock())
                .backoff(new Backoff(new BackoffPolicy(), new Random(3)));
    }

    List<Path> segments() throws IOException {
        if (!Files.exists(spool)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> found = Files.list(spool)) {
            return found.filter(path -> path.toString().endsWith(".spool")).toList();
        }
    }

    static List<Object> messages(MemoryTransport transport) {
        List<Object> out = new ArrayList<>();
        transport.received().forEach(batch -> batch.records().forEach(item -> out.add(item.get("message"))));
        return out;
    }

    @Test
    void anEmptySpoolShipsNothing() {
        ShipReport report = shipper(new MemoryTransport()).build().runOnce();
        assertThat(report.batchesSent()).isZero();
        assertThat(report.idle()).isTrue();
    }

    @Test
    void aSegmentIsShipped() {
        fill("pump-bridge", 3);
        MemoryTransport transport = new MemoryTransport();
        ShipReport report = shipper(transport).build().runOnce();
        assertThat(report.recordsSent()).isEqualTo(3);
        assertThat(transport.received()).hasSize(1);
    }

    @Test
    void everyRequestIsChargedItsOverheadAsWellAsItsBody() {
        fill("pump-bridge", 3);
        MemoryTransport transport = new MemoryTransport();
        Meter meter = new Meter(10_000_000, 1, Meter.DEFAULT_FLOORS, 6144);
        shipper(transport).meter(meter).build().runOnce();
        long body = transport.received().get(0).wireBytes();
        assertThat(meter.usedBytes()).isEqualTo(body + 6144);
    }

    @Test
    void aBatchThatFitsOnlyWithoutItsOverheadIsHeldBack() {
        fill("payment-kernel", 3);
        MemoryTransport probe = new MemoryTransport();
        shipper(probe).build().runOnce();
        long body = probe.received().get(0).wireBytes();
        fill("payment-kernel", 3);
        Meter meter = new Meter(body + 100, 1, Meter.DEFAULT_FLOORS, 500);
        ShipReport report = shipper(new MemoryTransport()).meter(meter).build().runOnce();
        assertThat(report.blockedByMeter()).hasSize(1);
        assertThat(meter.usedBytes()).isZero();
    }

    @Test
    void anAcknowledgedSegmentIsDeleted() throws IOException {
        fill("pump-bridge", 3);
        shipper(new MemoryTransport()).build().runOnce();
        assertThat(segments()).isEmpty();
    }

    @Test
    void aSecondPassHasNothingToDo() {
        fill("pump-bridge", 3);
        Shipper ship = shipper(new MemoryTransport()).build();
        ship.runOnce();
        assertThat(ship.runOnce().idle()).isTrue();
    }

    @Test
    void paymentShipsBeforeChatter() {
        fill("price-sign", 2);
        fill("payment-kernel", 2);
        MemoryTransport transport = new MemoryTransport();
        shipper(transport).build().runOnce();
        assertThat(transport.received().get(0).source()).contains("payment");
    }

    @Test
    void theOldestSegmentOfAClassGoesFirst() {
        fill("pump-bridge", 2, 256);
        MemoryTransport transport = new MemoryTransport();
        shipper(transport).build().runOnce();
        List<String> sources = transport.received().stream().map(Batch::source).toList();
        assertThat(sources).isSorted();
    }

    @Test
    void aBatchCarriesTheSegmentAsItsSource() {
        fill("pump-bridge", 2);
        MemoryTransport transport = new MemoryTransport();
        shipper(transport).build().runOnce();
        assertThat(transport.received().get(0).source()).endsWith(".spool");
    }

    @Test
    void theRecordsGoOutInOrder() {
        fill("pump-bridge", 4);
        MemoryTransport transport = new MemoryTransport();
        shipper(transport).build().runOnce();
        assertThat(messages(transport)).containsExactly("line 0", "line 1", "line 2", "line 3");
    }

    @Test
    void aBigSegmentIsCutIntoSeveralBatches() {
        fill("pump-bridge", 6);
        ShipReport report = shipper(new MemoryTransport())
                .limits(BatchLimits.records(2))
                .build()
                .runOnce();
        assertThat(report.batchesSent()).isEqualTo(3);
        assertThat(report.recordsSent()).isEqualTo(6);
    }

    @Test
    void aRetryableFailureStopsThePass() {
        fill("pump-bridge", 6);
        MemoryTransport transport = new MemoryTransport(List.of(new Response(Outcome.RETRYABLE, 503, "gateway")));
        ShipReport report =
                shipper(transport).limits(BatchLimits.records(2)).build().runOnce();
        assertThat(report.batchesSent()).isZero();
        assertThat(report.stopped()).isEqualTo("gateway");
        assertThat(report.waitSeconds()).isPositive();
    }

    @Test
    void aFailedSegmentIsNotDeleted() throws IOException {
        fill("pump-bridge", 3);
        shipper(new MemoryTransport(List.of(new Response(Outcome.RETRYABLE, 503))))
                .build()
                .runOnce();
        assertThat(segments()).isNotEmpty();
    }

    @Test
    void aPartlyAcknowledgedSegmentResumesWhereItStopped() {
        fill("pump-bridge", 6);
        MemoryTransport transport =
                new MemoryTransport(List.of(new Response(Outcome.OK, 200), new Response(Outcome.RETRYABLE, 503)));
        Shipper ship = shipper(transport).limits(BatchLimits.records(2)).build();
        ship.runOnce();
        transport.script(List.of());
        ship.runOnce();
        List<Object> messages = messages(transport);
        assertThat(messages.stream().filter("line 0"::equals).count()).isEqualTo(1);
        assertThat(messages).contains("line 5");
    }

    @Test
    void aThrottleHintIsUsedAsTheWait() {
        fill("pump-bridge", 2);
        MemoryTransport transport = new MemoryTransport(List.of(Response.throttled(429, 42.0)));
        assertThat(shipper(transport).build().runOnce().waitSeconds()).isEqualTo(42.0);
    }

    @Test
    void aTerminalRefusalStopsThatSegmentAndIsCounted() {
        fill("pump-bridge", 3);
        MemoryTransport transport = new MemoryTransport(List.of(new Response(Outcome.TERMINAL, 400, "bad body")));
        ShipReport report = shipper(transport).build().runOnce();
        assertThat(report.terminal()).isEqualTo(1);
        assertThat(report.batchesSent()).isZero();
    }

    @Test
    void aTerminalRefusalDoesNotPushTheLinkIntoBackoff() {
        fill("pump-bridge", 3);
        Shipper ship = shipper(new MemoryTransport(List.of(new Response(Outcome.TERMINAL, 400))))
                .build();
        ship.runOnce();
        assertThat(ship.backoff().failures()).isZero();
    }

    @Test
    void theMeterIsChargedForWhatWent() {
        fill("pump-bridge", 3);
        Shipper ship = shipper(new MemoryTransport()).build();
        ShipReport report = ship.runOnce();
        assertThat(ship.meter().usedBytes()).isEqualTo(report.bytesSent());
        assertThat(report.bytesSent()).isPositive();
    }

    @Test
    void theMeterIsChargedEvenForAFailedSend() {
        fill("pump-bridge", 3);
        Shipper ship = shipper(new MemoryTransport(List.of(new Response(Outcome.RETRYABLE, 503))))
                .build();
        ship.runOnce();
        assertThat(ship.meter().usedBytes()).isPositive();
    }

    @Test
    void aClassAtItsFloorIsBlockedAndNamed() {
        fill("price-sign", 3);
        ShipReport report =
                shipper(new MemoryTransport()).meter(metered(1000, 900)).build().runOnce();
        assertThat(report.batchesSent()).isZero();
        assertThat(report.blockedByMeter()).isNotEmpty();
    }

    @Test
    void paymentStillShipsWhenChatterIsBlocked() {
        fill("price-sign", 2);
        fill("payment-kernel", 2);
        MemoryTransport transport = new MemoryTransport();
        ShipReport report =
                shipper(transport).meter(metered(100_000, 75_000)).build().runOnce();
        assertThat(report.batchesSent()).isGreaterThanOrEqualTo(1);
        assertThat(transport.received()).allMatch(batch -> batch.source().contains("payment"));
    }

    @Test
    void theSegmentBeingShippedIsPinned() {
        fill("pump-bridge", 2);
        List<Integer> seen = new ArrayList<>();
        Shipper[] ship = new Shipper[1];
        MemoryTransport watcher = new MemoryTransport() {
            @Override
            public Response send(Batch batch) {
                seen.add(ship[0].pinned().size());
                return super.send(batch);
            }
        };
        ship[0] = shipper(watcher).build();
        ship[0].runOnce();
        assertThat(seen).containsExactly(1);
        assertThat(ship[0].pinned()).isEmpty();
    }

    @Test
    void theMetricsCountThePass() {
        fill("pump-bridge", 3);
        Registry metrics = new Registry();
        shipper(new MemoryTransport()).metrics(metrics).build().runOnce();
        Map<String, Long> snapshot = metrics.snapshot();
        assertThat(snapshot.get("ship_batches")).isEqualTo(1L);
        assertThat(snapshot.get("ship_records")).isEqualTo(3L);
        assertThat(snapshot.get("ship_segments_retired")).isEqualTo(1L);
    }

    @Test
    void aDamagedSegmentIsSkippedNotFatal() throws IOException {
        fill("pump-bridge", 3);
        Files.writeString(segments().get(0), "nonsense");
        assertThat(shipper(new MemoryTransport()).build().runOnce().batchesSent())
                .isZero();
    }

    @Test
    void keepingAcknowledgedSegmentsIsPossible() throws IOException {
        fill("pump-bridge", 3);
        shipper(new MemoryTransport()).deleteAcknowledged(false).build().runOnce();
        assertThat(segments()).isNotEmpty();
    }

    @Test
    void theBackoffRecoversAfterASuccess() {
        fill("pump-bridge", 3);
        MemoryTransport transport = new MemoryTransport(List.of(new Response(Outcome.RETRYABLE, 503)));
        Shipper ship = shipper(transport)
                .backoff(new Backoff(new BackoffPolicy(), new Random(1)))
                .build();
        ship.runOnce();
        assertThat(ship.backoff().failures()).isEqualTo(1);
        ship.runOnce();
        assertThat(ship.backoff().failures()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 20})
    void everyRecordReachesTheCollectorOnce(int count) {
        fill("pump-bridge", count);
        MemoryTransport transport = new MemoryTransport();
        shipper(transport).limits(BatchLimits.records(3)).build().runOnce();
        assertThat(messages(transport))
                .containsExactlyInAnyOrderElementsOf(IntStream.range(0, count)
                        .mapToObj(index -> (Object) ("line " + index))
                        .toList());
    }

    @Test
    void aFreshMeterRollsOnTheFirstPass() {
        fill("pump-bridge", 2);
        Shipper ship = shipper(new MemoryTransport()).build();
        ship.runOnce();
        assertThat(ship.meter().rollovers()).isEqualTo(1);
    }

    @Test
    void aMeterAlreadyInItsPeriodIsNotReset() {
        fill("pump-bridge", 2);
        ManualClock clock = new ManualClock();
        clock.setWall("2025-03-04T12:00:00Z");
        Shipper ship = shipper(new MemoryTransport())
                .meter(metered(100_000, 400))
                .clock(clock)
                .build();
        ship.runOnce();
        assertThat(ship.meter().usedBytes()).isGreaterThan(400);
    }
}
