package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.Clock;
import io.github.rkaushikdev.trammelworks.EventLog;
import io.github.rkaushikdev.trammelworks.JsonFiles;
import io.github.rkaushikdev.trammelworks.Registry;
import io.github.rkaushikdev.trammelworks.ShipError;
import io.github.rkaushikdev.trammelworks.SpoolError;
import io.github.rkaushikdev.trammelworks.SystemClock;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import io.github.rkaushikdev.trammelworks.spool.SegmentInfo;
import io.github.rkaushikdev.trammelworks.spool.SegmentRead;
import io.github.rkaushikdev.trammelworks.spool.SegmentReader;
import io.github.rkaushikdev.trammelworks.spool.Segments;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Moves acknowledged records off the site, inside the allowance.
 *
 * <p>Each pass decides, in order: which segment (highest class first, oldest first inside it); has
 * the meter room for this class; did it go (only an acknowledgement moves the watermark); and
 * should we carry on (a retryable failure stops the whole pass, because the link is down and the
 * next segment would only waste metered bytes). A segment is pinned while it is being uploaded, and
 * deleted as soon as everything in it is acknowledged.
 */
public final class Shipper {

    private final String site;
    private final Path directory;
    private final Transport transport;
    private final Meter meter;
    private final AckLedger acks;
    private final PriorityTable table;
    private final BatchLimits limits;
    private final Backoff backoff;
    private final Clock clock;
    private final EventLog events;
    private final Registry metrics;
    private final boolean deleteAcknowledged;
    private final Set<Path> pinned = new HashSet<>();

    private Shipper(Builder from) {
        this.site = from.site;
        this.directory = from.directory;
        this.transport = from.transport;
        this.meter = from.meter;
        this.acks = from.acks;
        this.table = from.table;
        this.limits = from.limits;
        this.backoff = from.backoff;
        this.clock = from.clock;
        this.events = from.events;
        this.metrics = from.metrics;
        this.deleteAcknowledged = from.deleteAcknowledged;
    }

    /** A builder for the shipper of {@code site}'s spool in {@code directory}. */
    public static Builder builder(String site, Path directory, Transport transport) {
        return new Builder(site, directory, transport);
    }

    /** The meter being spent. */
    public Meter meter() {
        return meter;
    }

    /** The acknowledgement ledger. */
    public AckLedger acks() {
        return acks;
    }

    /** The retry state. */
    public Backoff backoff() {
        return backoff;
    }

    /** The transport. */
    public Transport transport() {
        return transport;
    }

    /** The segments pinned right now. */
    public Set<Path> pinned() {
        return Collections.unmodifiableSet(pinned);
    }

    /** Sealed segments, in the order they should be shipped. */
    public List<SegmentInfo> order() {
        Map<String, Integer> rank = new HashMap<>();
        List<String> shipOrder = table.shipOrder();
        for (int index = 0; index < shipOrder.size(); index++) {
            rank.put(shipOrder.get(index), index);
        }
        return Segments.listSegments(directory).stream()
                .filter(item -> rank.containsKey(item.klass()))
                .sorted(Comparator.comparingInt((SegmentInfo item) -> rank.get(item.klass()))
                        .thenComparingLong(SegmentInfo::sequence))
                .toList();
    }

    /** One pass over the spool. */
    public ShipReport runOnce() {
        ShipReport report = new ShipReport();
        meter.roll(clock.now());
        for (SegmentInfo segment : order()) {
            if (!shipSegment(segment, report)) {
                break;
            }
        }
        acks.sweep(
                Segments.listSegments(directory).stream().map(SegmentInfo::name).collect(Collectors.toSet()));
        return report;
    }

    private boolean shipSegment(SegmentInfo segment, ShipReport report) {
        SegmentRead found;
        try {
            found = SegmentReader.readSegment(segment.path());
        } catch (SpoolError exc) {
            events.exception("segment_unreadable", exc, "segment", segment.name());
            metrics.incr("ship_unreadable_segments");
            return true;
        }
        List<Map<String, Object>> payloads = found.payloads();
        Watermark mark = acks.mark(segment.name(), payloads.size());
        if (mark.done()) {
            retire(segment);
            return true;
        }
        report.touched();
        pinned.add(segment.path());
        try {
            return sendBatches(segment, payloads.subList(mark.acked(), payloads.size()), report);
        } finally {
            pinned.remove(segment.path());
        }
    }

    private boolean sendBatches(SegmentInfo segment, List<Map<String, Object>> remaining, ShipReport report) {
        for (Batch batch : Batches.cut(site, remaining, limits, segment.name(), new BatchStats())) {
            int size = batch.wireBytes();
            long needed = meter.requestCost(size);
            if (!meter.allows(needed, segment.klass())) {
                // Says what the request needed with its overhead, because "available 9000" beside
                // a batch of 8000 reads as a meter bug until the 1500 of handshake is in the line.
                report.blocked(segment.name());
                metrics.incr("ship_meter_blocked");
                events.warn(
                        "meter_blocked",
                        "segment",
                        segment.name(),
                        "klass",
                        segment.klass(),
                        "needed",
                        needed,
                        "available",
                        meter.availableTo(segment.klass()));
                return true;
            }
            if (!sendOne(segment, batch, size, report)) {
                return false;
            }
        }
        if (acks.marks().get(segment.name()).done()) {
            report.completed();
            retire(segment);
        }
        return true;
    }

    private boolean sendOne(SegmentInfo segment, Batch batch, int size, ShipReport report) {
        Watermark mark = acks.marks().get(segment.name());
        mark.attempt();
        Response answer = transport.send(batch);
        long cost = meter.requestCost(answer.bytesSent() != 0 ? answer.bytesSent() : size);
        meter.charge(cost);
        report.charged(cost);
        metrics.incr("ship_bytes", cost);
        if (answer.outcome() == Outcome.OK) {
            backoff.succeed();
            acks.acknowledge(segment.name(), batch.count());
            report.sent(batch);
            metrics.incr("ship_batches");
            metrics.incr("ship_records", batch.count());
            return true;
        }
        if (answer.outcome() == Outcome.TERMINAL) {
            mark.failure();
            report.refused();
            metrics.incr("ship_terminal");
            events.error(
                    "batch_refused",
                    "segment",
                    segment.name(),
                    "batch",
                    batch.batchId(),
                    "status",
                    answer.status(),
                    "message",
                    answer.message());
            return false;
        }
        double wait = backoff.record(answer.outcome(), answer.retryAfter());
        report.stop(answer.message().isEmpty() ? answer.outcome().value() : answer.message(), wait);
        metrics.incr("ship_retryable");
        events.warn(
                "upload_failed",
                "segment",
                segment.name(),
                "status",
                answer.status(),
                "wait",
                Math.rint(wait * 1000) / 1000,
                "failures",
                backoff.failures());
        return false;
    }

    private void retire(SegmentInfo segment) {
        if (!deleteAcknowledged) {
            return;
        }
        try {
            // Already gone is what retiring wanted, so a missing file is not an error.
            Files.deleteIfExists(segment.path());
        } catch (IOException exc) {
            throw new ShipError("cannot remove an acknowledged segment", JsonFiles.describe(exc));
        }
        acks.forget(segment.name());
        metrics.incr("ship_segments_retired");
        events.info("segment_retired", "segment", segment.name());
    }

    /** A shipper configured a setting at a time. */
    public static final class Builder {
        private final String site;
        private final Path directory;
        private final Transport transport;
        private Meter meter = new Meter(Meter.DEFAULT_ALLOWANCE, Meter.DEFAULT_ANCHOR_DAY);
        private AckLedger acks = new AckLedger();
        private PriorityTable table = new PriorityTable();
        private BatchLimits limits = new BatchLimits();
        private Backoff backoff = new Backoff();
        private Clock clock = new SystemClock();
        private EventLog events = EventLog.nullLog();
        private Registry metrics = new Registry();
        private boolean deleteAcknowledged = true;

        private Builder(String site, Path directory, Transport transport) {
            this.site = site;
            this.directory = directory;
            this.transport = transport;
        }

        /** The meter. */
        public Builder meter(Meter value) {
            meter = value;
            return this;
        }

        /** The acknowledgement ledger. */
        public Builder acks(AckLedger value) {
            acks = value;
            return this;
        }

        /** The priority table. */
        public Builder table(PriorityTable value) {
            table = value;
            return this;
        }

        /** The batch bounds. */
        public Builder limits(BatchLimits value) {
            limits = value;
            return this;
        }

        /** The retry state. */
        public Builder backoff(Backoff value) {
            backoff = value;
            return this;
        }

        /** The clock. */
        public Builder clock(Clock value) {
            clock = value;
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

        /** Whether done segments are deleted at once. */
        public Builder deleteAcknowledged(boolean value) {
            deleteAcknowledged = value;
            return this;
        }

        /** The shipper. */
        public Shipper build() {
            return new Shipper(this);
        }
    }
}
