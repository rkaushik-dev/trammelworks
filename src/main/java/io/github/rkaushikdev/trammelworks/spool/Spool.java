package io.github.rkaushikdev.trammelworks.spool;

import io.github.rkaushikdev.trammelworks.EventLog;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.Registry;
import io.github.rkaushikdev.trammelworks.SpoolError;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The spool itself: a bounded, class partitioned, append only store of records.
 *
 * <p>One open segment per priority class, because a segment is deleted whole and one holding both
 * payment records and price sign chatter could not be deleted without taking the payment records
 * with it. A write frames the record (the framed size is what costs disk), makes room if it does
 * not fit, rolls the class's segment if it would overflow, then appends. Making room can fail, and
 * then the write is refused rather than accepted into a spool that has already overrun.
 */
public final class Spool {

    private final Path directory;
    private final Budget budget;
    private final PriorityTable table;
    private final long segmentBytes;
    private final int syncEvery;
    private final boolean compress;
    private final EventLog events;
    private final Registry metrics;
    private final Map<String, SegmentWriter> writers = new LinkedHashMap<>();
    private final Set<Path> pinned = new HashSet<>();

    private Spool(Builder from) {
        this.directory = from.directory;
        this.budget = from.budget;
        this.table = from.table;
        this.segmentBytes = from.segmentBytes;
        this.syncEvery = from.syncEvery;
        this.compress = from.compress;
        this.events = from.events;
        this.metrics = from.metrics;
        if (segmentBytes > budget.usableBytes()) {
            throw new SpoolError(
                    "a segment cannot be larger than the usable budget", segmentBytes + " > " + budget.usableBytes());
        }
    }

    /** A builder for a spool in {@code directory} kept inside {@code budget}. */
    public static Builder builder(Path directory, Budget budget) {
        return new Builder(directory, budget);
    }

    /** A spool built from a site's settings. */
    public static Builder builder(SpoolSettings settings) {
        return builder(settings.path(), settings.budget())
                .table(settings.table())
                .segmentBytes(settings.segmentBytes())
                .syncEvery(settings.syncEvery())
                .compress(settings.compress());
    }

    /** Where the segments live. */
    public Path directory() {
        return directory;
    }

    /** The disk budget. */
    public Budget budget() {
        return budget;
    }

    /** The priority table records are filed by. */
    public PriorityTable table() {
        return table;
    }

    /** The segments open for writing, by class. */
    public Map<String, SegmentWriter> writers() {
        return Collections.unmodifiableMap(writers);
    }

    /** Deal with anything an interrupted run left behind. */
    public List<Recovery> open() {
        List<Recovery> found = SegmentReader.recoverDirectory(directory);
        for (Recovery item : found) {
            metrics.incr("spool_recovered_segments");
            if (item.droppedBytes() != 0) {
                metrics.incr("spool_recovered_lost_bytes", item.droppedBytes());
            }
            events.warn(
                    "segment_recovered",
                    "path",
                    item.path().toString(),
                    "frames",
                    item.frames(),
                    "dropped",
                    item.droppedBytes(),
                    "removed",
                    item.removed());
        }
        return found;
    }

    /** Every sealed segment, oldest first. */
    public List<SegmentInfo> segments() {
        return Segments.listSegments(directory);
    }

    /** What the spool holds, open segments included. */
    public Usage usage() {
        long open = writers.values().stream().mapToLong(SegmentWriter::size).sum();
        return Usage.measure(segments(), open);
    }

    /** Frame a record and write it into its class's segment. */
    public Receipt append(LogRecord record) {
        PriorityClass klass = table.classFor(record);
        byte[] frame = Frames.encodeFrame(record.asDict(), compress);
        makeRoom(frame.length);
        SegmentWriter writer = writerFor(klass.name());
        if (writer.wouldOverflow(frame.length)) {
            seal(klass.name());
            writer = writerFor(klass.name());
        }
        long offset = writer.append(frame);
        metrics.incr("spool_records");
        metrics.incr("spool_bytes", frame.length);
        return new Receipt(klass.name(), writer.sequence(), offset, frame.length);
    }

    /** Write a batch, in order. */
    public List<Receipt> appendAll(List<LogRecord> records) {
        List<Receipt> out = new ArrayList<>();
        for (LogRecord record : records) {
            out.add(append(record));
        }
        return out;
    }

    /** Commit every open segment. */
    public void flush() {
        writers.values().forEach(SegmentWriter::flush);
    }

    /** Seal every open segment, which is what shutdown does. */
    public List<SegmentInfo> sealAll() {
        List<SegmentInfo> sealed = new ArrayList<>();
        for (String klass : new ArrayList<>(writers.keySet())) {
            seal(klass).ifPresent(sealed::add);
        }
        return sealed;
    }

    /**
     * Release every open segment without sealing it. What is left is exactly what a power cut
     * leaves, so the next start goes through recovery.
     */
    public void detach() {
        writers.values().forEach(SegmentWriter::close);
        writers.clear();
    }

    /** Protect a segment from eviction while it is being shipped. */
    public void pin(Path path) {
        pinned.add(path);
    }

    /** Let a shipped or abandoned segment be evicted again. */
    public void unpin(Path path) {
        pinned.remove(path);
    }

    private SegmentWriter writerFor(String klass) {
        return writers.computeIfAbsent(
                klass,
                name -> new SegmentWriter(directory, Segments.nextSequence(directory), name, segmentBytes, syncEvery));
    }

    private Optional<SegmentInfo> seal(String klass) {
        SegmentWriter writer = writers.remove(klass);
        if (writer == null) {
            return Optional.empty();
        }
        Optional<SegmentInfo> sealed = writer.seal();
        sealed.ifPresent(found -> {
            metrics.incr("spool_segments_sealed");
            events.info("segment_sealed", "path", found.path().toString(), "size", found.size(), "klass", klass);
        });
        return sealed;
    }

    private void makeRoom(long size) {
        if (budget.admits(usage(), size)) {
            return;
        }
        // Sealing first turns open bytes into deletable ones, and is the reason the reserve exists:
        // this write happens while the budget is already up against its usable limit.
        sealAll();
        long needed = budget.overBy(usage()) + size;
        EvictionPlan plan = Eviction.planEviction(segments(), table, needed, Set.copyOf(pinned));
        EvictionResult result = Eviction.applyEviction(plan);
        metrics.incr("spool_evicted_segments", result.count());
        metrics.incr("spool_evicted_bytes", result.freed());
        if (result.count() != 0) {
            events.warn("spool_evicted", "segments", result.count(), "bytes", result.freed());
        }
        if (!budget.admits(usage(), size)) {
            throw new SpoolError(
                    "spool is full and nothing more can be evicted", "needed " + needed + ", freed " + result.freed());
        }
    }

    /** A spool configured a setting at a time. */
    public static final class Builder {
        private final Path directory;
        private final Budget budget;
        private PriorityTable table = new PriorityTable();
        private long segmentBytes = Segments.DEFAULT_SEGMENT_BYTES;
        private int syncEvery;
        private boolean compress = true;
        private EventLog events = EventLog.nullLog();
        private Registry metrics = new Registry();

        private Builder(Path directory, Budget budget) {
            this.directory = directory;
            this.budget = budget;
        }

        /** The priority table. */
        public Builder table(PriorityTable value) {
            table = value;
            return this;
        }

        /** The sealing size. */
        public Builder segmentBytes(long value) {
            segmentBytes = value;
            return this;
        }

        /** The forced sync interval. */
        public Builder syncEvery(int value) {
            syncEvery = value;
            return this;
        }

        /** Whether to deflate. */
        public Builder compress(boolean value) {
            compress = value;
            return this;
        }

        /** Where events go. */
        public Builder events(EventLog value) {
            events = value;
            return this;
        }

        /** Where counters go. */
        public Builder metrics(Registry value) {
            metrics = value;
            return this;
        }

        /** The spool. */
        public Spool build() {
            return new Spool(this);
        }
    }
}
