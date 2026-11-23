package io.github.rkaushikdev.trammelworks.spool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Registry;
import io.github.rkaushikdev.trammelworks.SpoolError;
import io.github.rkaushikdev.trammelworks.Stream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpoolStoreTest {

    static final PriorityTable TABLE = new PriorityTable(Map.of("payment-kernel", "payment", "price-sign", "chatter"));

    @TempDir
    Path tmp;

    static LogRecord record() {
        return record("pump-bridge", "nozzle 1 lifted");
    }

    static LogRecord record(String unit, String message) {
        return new LogRecord("US-TX-0417", unit, Stream.STDOUT, NanoTime.parse("2025-03-04T12:00:00Z"), message);
    }

    Spool.Builder builder() {
        return Spool.builder(tmp.resolve("spool"), new Budget(1_000_000, 0.1))
                .table(TABLE)
                .segmentBytes(4096);
    }

    Spool spool() {
        return builder().build();
    }

    @Test
    void aRecordIsWrittenAndReceipted() {
        Receipt receipt = spool().append(record());
        assertThat(receipt.klass()).isEqualTo("standard");
        assertThat(receipt.offset()).isZero();
        assertThat(receipt.size()).isPositive();
    }

    @Test
    void aPaymentRecordGoesToThePaymentClass() {
        assertThat(spool().append(record("payment-kernel", "x")).klass()).isEqualTo("payment");
    }

    @Test
    void anEnvironmentalAlarmFromAChatterUnitGoesToThePaymentClass() {
        Spool store = builder()
                .table(new PriorityTable(Map.of("tank-gauge", "chatter")))
                .build();
        LogRecord leak = record("tank-gauge", "T 1:LEAK ALARM").withLabel("alarm_severity", "environmental");
        assertThat(store.append(leak).klass()).isEqualTo("payment");
        assertThat(store.append(record("tank-gauge", "T 1: volume 31200 ml")).klass())
                .isEqualTo("chatter");
    }

    @Test
    void twoClassesGetSeparateSegments() {
        Spool store = spool();
        store.append(record("payment-kernel", "x"));
        store.append(record("price-sign", "x"));
        assertThat(store.sealAll()).extracting(SegmentInfo::klass).containsExactlyInAnyOrder("chatter", "payment");
    }

    @Test
    void theRecordsReadBack() {
        Spool store = spool();
        store.append(record("pump-bridge", "one"));
        store.append(record("pump-bridge", "two"));
        List<SegmentInfo> sealed = store.sealAll();
        assertThat(SegmentReader.readSegment(sealed.get(0).path()).payloads())
                .extracting(item -> item.get("message"))
                .containsExactly("one", "two");
    }

    @Test
    void theRecordSurvivesTheRoundTripWhole() {
        Spool store = spool();
        LogRecord original = record("pump-bridge", "auth approved");
        store.append(original);
        Map<String, Object> payload = SegmentReader.readSegment(
                        store.sealAll().get(0).path())
                .payloads()
                .get(0);
        assertThat(payload.get("site")).isEqualTo(original.site());
        assertThat(payload.get("unit")).isEqualTo(original.unit());
        assertThat(payload.get("observed_at")).isEqualTo(original.observedAt().isoformat());
    }

    @Test
    void theOffsetAdvancesWithinASegment() {
        Spool store = spool();
        Receipt first = store.append(record());
        Receipt second = store.append(record());
        assertThat(second.offset()).isEqualTo(first.size());
    }

    @Test
    void aSegmentRollsWhenItIsFull() {
        Spool store = builder().segmentBytes(200).build();
        Set<Long> sequences = new HashSet<>();
        for (int index = 0; index < 6; index++) {
            sequences.add(store.append(record("pump-bridge", "x".repeat(40))).sequence());
        }
        assertThat(sequences).hasSizeGreaterThan(1);
    }

    @Test
    void aRolledSegmentIsSealedAndListed() {
        Spool store = builder().segmentBytes(200).build();
        for (int index = 0; index < 6; index++) {
            store.append(record("pump-bridge", "y".repeat(40)));
        }
        assertThat(store.segments()).isNotEmpty();
    }

    @Test
    void usageCountsTheOpenSegment() {
        Spool store = spool();
        Receipt receipt = store.append(record());
        assertThat(store.usage().used()).isEqualTo(receipt.size());
        assertThat(store.usage().openBytes()).isEqualTo(receipt.size());
    }

    @Test
    void usageCountsSealedSegments() {
        Spool store = spool();
        Receipt receipt = store.append(record());
        store.sealAll();
        assertThat(store.usage().sealedBytes()).isEqualTo(receipt.size());
        assertThat(store.usage().openBytes()).isZero();
    }

    @Test
    void aSegmentLargerThanTheBudgetIsRefused() {
        assertThatThrownBy(() -> Spool.builder(tmp.resolve("spool"), new Budget(1000))
                        .table(TABLE)
                        .segmentBytes(4096)
                        .build())
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("larger than the usable budget");
    }

    Spool bounded(long limit, Registry metrics) {
        return Spool.builder(tmp.resolve("spool"), new Budget(limit, 0.1))
                .table(TABLE)
                .segmentBytes(300)
                .metrics(metrics)
                .build();
    }

    @Test
    void aFullSpoolEvictsTheLowestClass() {
        Spool store = bounded(4000, new Registry());
        for (int index = 0; index < 20; index++) {
            store.append(record("price-sign", "z".repeat(60)));
        }
        for (int index = 0; index < 20; index++) {
            store.append(record("payment-kernel", "z".repeat(60)));
        }
        assertThat(store.segments()).extracting(SegmentInfo::klass).contains("payment");
    }

    @Test
    void evictionIsCounted() {
        Registry metrics = new Registry();
        Spool store = bounded(3000, metrics);
        for (int index = 0; index < 30; index++) {
            store.append(record("price-sign", "z".repeat(60)));
        }
        assertThat(metrics.snapshot().get("spool_evicted_segments")).isPositive();
    }

    @Test
    void aSpoolFullOfProtectedRecordsRefusesTheWrite() {
        Spool store = bounded(2000, new Registry());
        assertThatThrownBy(() -> {
                    for (int index = 0; index < 200; index++) {
                        store.append(record("payment-kernel", "q".repeat(60)));
                    }
                })
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("nothing more can be evicted");
    }

    @Test
    void aPinnedSegmentSurvivesEviction() {
        Spool store = bounded(4000, new Registry());
        for (int index = 0; index < 6; index++) {
            store.append(record("price-sign", "z".repeat(60)));
        }
        store.sealAll();
        SegmentInfo keeper = store.segments().get(0);
        store.pin(keeper.path());
        for (int index = 0; index < 20; index++) {
            store.append(record("price-sign", "z".repeat(60)));
        }
        assertThat(keeper.path()).exists();
    }

    @Test
    void unpinningLetsItGoAgain() {
        Spool store = bounded(4000, new Registry());
        for (int index = 0; index < 6; index++) {
            store.append(record("price-sign", "z".repeat(60)));
        }
        store.sealAll();
        SegmentInfo keeper = store.segments().get(0);
        store.pin(keeper.path());
        store.unpin(keeper.path());
        for (int index = 0; index < 30; index++) {
            store.append(record("price-sign", "z".repeat(60)));
        }
        assertThat(keeper.path()).doesNotExist();
    }

    @Test
    void openingRecoversAnInterruptedSegment() {
        Spool first = spool();
        first.append(record("pump-bridge", "before the power cut"));
        first.detach();
        List<Recovery> found = spool().open();
        assertThat(found).hasSize(1);
        assertThat(found.get(0).frames()).isEqualTo(1);
    }

    @Test
    void recoveryIsCounted() {
        Spool first = spool();
        first.append(record());
        first.detach();
        Registry metrics = new Registry();
        builder().metrics(metrics).build().open();
        assertThat(metrics.snapshot().get("spool_recovered_segments")).isEqualTo(1L);
    }

    @Test
    void openingACleanSpoolRecoversNothing() {
        assertThat(spool().open()).isEmpty();
    }

    @Test
    void sequenceNumbersAreNotReusedAcrossARestart() {
        Spool first = spool();
        first.append(record());
        first.sealAll();
        assertThat(spool().append(record()).sequence()).isEqualTo(1);
    }

    @Test
    void appendAllReturnsAReceiptEach() {
        List<Receipt> receipts = spool().appendAll(List.of(record(), record()));
        assertThat(receipts).hasSize(2);
        assertThat(receipts.get(0).offset()).isZero();
    }

    @Test
    void theMetricsCountRecordsAndBytes() {
        Registry metrics = new Registry();
        Receipt receipt = builder().metrics(metrics).build().append(record());
        Map<String, Long> snapshot = metrics.snapshot();
        assertThat(snapshot.get("spool_records")).isEqualTo(1L);
        assertThat(snapshot.get("spool_bytes")).isEqualTo((long) receipt.size());
    }

    @Test
    void sealingAnEmptySpoolGivesNothing() {
        assertThat(spool().sealAll()).isEmpty();
    }

    @Test
    void detachLeavesAPartFileForRecovery() throws IOException {
        Spool store = spool();
        store.append(record());
        store.detach();
        assertThat(store.segments()).isEmpty();
        try (java.util.stream.Stream<Path> found = Files.list(tmp.resolve("spool"))) {
            assertThat(found.filter(path -> path.toString().endsWith(".part"))).isNotEmpty();
        }
    }

    @Test
    void detachOnAnUntouchedSpoolIsANoOp() {
        Spool store = spool();
        store.detach();
        assertThat(store.writers()).isEmpty();
    }

    @Test
    void aDetachedSpoolWritesIntoAFreshSegment() {
        // The detached part file keeps its sequence number, so the next write does not reopen it.
        // Reopening would be worse: recovery expects to find that file exactly as the crash left it.
        Spool store = spool();
        Receipt first = store.append(record());
        store.detach();
        Receipt second = store.append(record());
        assertThat(second.sequence()).isEqualTo(first.sequence() + 1);
        assertThat(second.offset()).isZero();
    }
}
