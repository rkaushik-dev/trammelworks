package io.github.rkaushikdev.trammelworks.archive;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.Identity;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.SourceRef;
import io.github.rkaushikdev.trammelworks.StanchionError;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Putting records into the archive and getting them back out.
 *
 * <p>Writes are idempotent on the record key: a re-read file, a segment replayed off a stick or an
 * export imported twice must not double the archive, and {@code INSERT OR IGNORE} does that in one
 * statement. Reads are always bounded by time and ordered by it, by the corrected time where there
 * is one, because a dispute review asks what happened in the real world at 14:07.
 */
public final class Repository {

    /** Rows per read by default. */
    public static final int DEFAULT_LIMIT = 1000;

    /** Rows per transaction: the commit cost disappears and the lock is never held long. */
    public static final int DEFAULT_BATCH = 500;

    /** The most rows one read may ask for. */
    public static final int MAX_LIMIT = 100_000;

    /** What every read orders by. */
    public static final String ORDER_COLUMN = "COALESCE(corrected_ns, observed_ns)";

    static final String INSERT = "INSERT OR IGNORE INTO records"
            + " (key, site, unit, klass, stream, observed_ns, corrected_ns, confidence, message, labels, stored_ns)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final ArchiveDb db;
    private final PriorityTable table;
    private final int batch;
    private long written;
    private long duplicates;

    /** A repository filing by the built in classes. */
    public Repository(ArchiveDb db) {
        this(db, new PriorityTable(), DEFAULT_BATCH);
    }

    /** A repository filing by a site's classes. */
    public Repository(ArchiveDb db, PriorityTable table) {
        this(db, table, DEFAULT_BATCH);
    }

    /** A repository with a given transaction size. */
    public Repository(ArchiveDb db, PriorityTable table, int batch) {
        if (batch < 1) {
            throw new StanchionError("the archive batch must be at least one");
        }
        this.db = db;
        this.table = table;
        this.batch = batch;
    }

    /** The connection. */
    public ArchiveDb db() {
        return db;
    }

    /** Records stored that were new. */
    public long written() {
        return written;
    }

    /** Records offered that were already there. */
    public long duplicates() {
        return duplicates;
    }

    private List<Object> row(LogRecord record, NanoTime storedAt) {
        return Arrays.asList(
                Identity.recordKey(record),
                record.site(),
                record.unit(),
                table.classFor(record).name(),
                record.stream().value(),
                record.observedAt().epochNs(),
                record.correctedAt().map(NanoTime::epochNs).orElse(null),
                record.clockConfidence(),
                record.message(),
                Json.dumps(record.labels(), Json.Style.sorted()),
                storedAt.epochNs());
    }

    /** Store one record. Returns whether it was new. */
    public boolean add(LogRecord record, NanoTime storedAt) {
        return addAll(List.of(record), storedAt) == 1;
    }

    /**
     * Store many in as few transactions as it takes, one per chunk. Returns how many were new,
     * counted from the connection's change counter, since a batch update count says nothing about
     * what {@code INSERT OR IGNORE} skipped.
     */
    public long addAll(Iterable<LogRecord> records, NanoTime storedAt) {
        long added = 0;
        long seen = 0;
        Iterator<List<LogRecord>> chunks = chunks(records.iterator(), batch);
        while (chunks.hasNext()) {
            List<LogRecord> chunk = chunks.next();
            seen += chunk.size();
            List<List<Object>> rows = new ArrayList<>();
            for (LogRecord record : chunk) {
                rows.add(row(record, storedAt));
            }
            long before = db.totalChanges();
            db.transaction(() -> db.executeMany(INSERT, rows));
            added += db.totalChanges() - before;
        }
        written += added;
        duplicates += seen - added;
        return added;
    }

    /** How many records the archive holds. */
    public long count() {
        return db.queryOne("SELECT count(*) AS n FROM records").orElseThrow().getLong("n");
    }

    /** Records inside a window, oldest first, the first thousand. */
    public List<StoredRecord> between(NanoTime start, NanoTime end) {
        return between(start, end, null, null, DEFAULT_LIMIT, 0);
    }

    /** Records inside a window, oldest first, with at most {@code limit} returned. */
    public List<StoredRecord> between(NanoTime start, NanoTime end, int limit) {
        return between(start, end, null, null, limit, 0);
    }

    /** Records inside a window, oldest first; {@code unit} and {@code klass} may be {@code null}. */
    public List<StoredRecord> between(NanoTime start, NanoTime end, String unit, String klass, int limit, int offset) {
        if (end.compareTo(start) < 0) {
            throw new StanchionError("the window ends before it starts");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new StanchionError("limit is out of range", Integer.toString(limit));
        }
        if (offset < 0) {
            throw new StanchionError("offset cannot be negative", Integer.toString(offset));
        }
        List<String> clauses = new ArrayList<>(List.of(ORDER_COLUMN + " >= ?", ORDER_COLUMN + " <= ?"));
        List<Object> values = new ArrayList<>(List.of(start.epochNs(), end.epochNs()));
        if (unit != null) {
            clauses.add("unit = ?");
            values.add(unit);
        }
        if (klass != null) {
            clauses.add("klass = ?");
            values.add(klass);
        }
        String sql = "SELECT * FROM records WHERE " + String.join(" AND ", clauses) + " ORDER BY " + ORDER_COLUMN
                + " ASC, id ASC LIMIT ? OFFSET ?";
        values.add(limit);
        values.add(offset);
        return db.query(sql, values.toArray()).stream()
                .map(Repository::rowToRecord)
                .toList();
    }

    /** Every unit with records, sorted. */
    public List<String> units() {
        return db.query("SELECT DISTINCT unit FROM records ORDER BY unit").stream()
                .map(row -> row.getString("unit"))
                .toList();
    }

    /**
     * The oldest and newest record times.
     *
     * @param oldest the earliest best time
     * @param newest the latest best time
     */
    public record Span(NanoTime oldest, NanoTime newest) {}

    /** The oldest and newest record, or empty when there are none. */
    public Optional<Span> span() {
        Row row = db.queryOne("SELECT min(" + ORDER_COLUMN + ") AS lo, max(" + ORDER_COLUMN + ") AS hi FROM records")
                .orElseThrow();
        if (row.isNull("lo")) {
            return Optional.empty();
        }
        return Optional.of(new Span(new NanoTime(row.getLong("lo")), new NanoTime(row.getLong("hi"))));
    }

    /** Record counts by class, sorted by class. */
    public Map<String, Long> countsByClass() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Row row : db.query("SELECT klass, count(*) AS n FROM records GROUP BY klass ORDER BY klass")) {
            out.put(row.getString("klass"), row.getLong("n"));
        }
        return out;
    }

    /** One stored row as a record. */
    public static StoredRecord rowToRecord(Row row) {
        Map<String, String> labels = new LinkedHashMap<>();
        Object raw = row.get("labels");
        if (raw instanceof String text) {
            try {
                Object parsed = Json.loads(text);
                if (parsed instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> labels.put(Coerce.text(key), Coerce.text(value)));
                }
            } catch (Json.JsonException exc) {
                labels.clear();
            }
        }
        Object corrected = row.get("corrected_ns");
        return new StoredRecord(
                row.getString("key"),
                row.getString("site"),
                row.getString("unit"),
                row.getString("klass"),
                Stream.parse(row.getString("stream")),
                new NanoTime(row.getLong("observed_ns")),
                corrected == null ? Optional.empty() : Optional.of(new NanoTime(((Number) corrected).longValue())),
                row.getString("confidence"),
                row.getString("message"),
                labels,
                new NanoTime(row.getLong("stored_ns")));
    }

    /** The archive keeps no byte spans, so this is always empty. */
    public static List<SourceRef> sourceRefs(StoredRecord record) {
        return List.of();
    }

    /** Walk an iterator in fixed size pieces, without pulling it all in. */
    public static <T> Iterator<List<T>> chunks(Iterator<T> source, int size) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public List<T> next() {
                if (!source.hasNext()) {
                    throw new NoSuchElementException();
                }
                List<T> held = new ArrayList<>(size);
                while (held.size() < size && source.hasNext()) {
                    held.add(source.next());
                }
                return held;
            }
        };
    }
}
