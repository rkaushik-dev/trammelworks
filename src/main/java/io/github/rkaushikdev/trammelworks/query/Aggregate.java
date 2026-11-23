package io.github.rkaushikdev.trammelworks.query;

import io.github.rkaushikdev.trammelworks.QueryError;
import io.github.rkaushikdev.trammelworks.Text;
import io.github.rkaushikdev.trammelworks.Units;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Row;
import io.github.rkaushikdev.trammelworks.archive.SqlFailure;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Counting matches instead of listing them.
 *
 * <p>The question an incident review starts with is "which unit was noisy and when did it start".
 * So a filter can be grouped, by a field or into time buckets, and what comes back is one row per
 * group with a count and the span it covers.
 *
 * <p>The catch is the residual. A group count computed by SQL counts every row the SQL matched, and
 * when part of the filter could not be pushed those are not the same rows, so <b>the database groups
 * only when the plan pushed everything</b>. Otherwise the rows are read and tallied here, under the
 * same scan ceiling and the same truncation flag as a search.
 *
 * <p>Time buckets are counted from an origin rather than from the first record, so two reports run
 * an hour apart line up.
 *
 * <p>A count may also be grouped by a label, {@code label.response} or {@code label.pump}, which is
 * how a forecourt asks "declines by response code" or "which pump keeps faulting". Labels are held
 * as JSON in the archive, so a label grouping is always tallied in memory.
 */
public final class Aggregate {

    /** What a count may be grouped by. Not {@code message}: four million groups is no insight. */
    public static final Set<String> GROUPABLE = Set.of("site", "unit", "class", "stream", "confidence");

    /** The bucket a histogram uses when nobody says how wide. */
    public static final Duration DEFAULT_BUCKET = Duration.ofHours(1);

    /** The narrowest bucket allowed. */
    public static final Duration MIN_BUCKET = Duration.ofSeconds(1);

    /** More groups than this and the answer is a listing, not a summary. */
    public static final int MAX_GROUPS = 5000;

    /**
     * What a group with no value is called. Both counting paths use it, or the same archive would
     * answer two ways depending on whether the filter happened to be pushable.
     */
    public static final String NO_VALUE = "(none)";

    private static final long NS_PER_SECOND = 1_000_000_000L;

    private Aggregate() {}

    /** Validate a grouping field: one of {@link #GROUPABLE}, or any label. */
    public static String checkGroup(String name) {
        String cleaned = Text.strip(name).toLowerCase(Locale.ROOT);
        if (cleaned.startsWith(Ast.LABEL_PREFIX)) {
            return Ast.checkField(cleaned);
        }
        if (!GROUPABLE.contains(cleaned)) {
            throw new QueryError("cannot group by this field", name);
        }
        return cleaned;
    }

    /** Which bucket an instant falls in, as the bucket's start, with the origin at the epoch. */
    public static long bucketOf(long atNs, long widthNs) {
        return bucketOf(atNs, widthNs, 0);
    }

    /**
     * Which bucket an instant falls in, as the bucket's start. Floor division, so an instant before
     * the origin lands in the bucket that contains it rather than the one after.
     */
    public static long bucketOf(long atNs, long widthNs, long originNs) {
        if (widthNs < 1) {
            throw new QueryError("a bucket cannot be narrower than a nanosecond");
        }
        return originNs + Math.floorDiv(atNs - originNs, widthNs) * widthNs;
    }

    /** The label a grouped value gets, empty and null alike. */
    public static String groupKey(Object value) {
        if (value == null) {
            return NO_VALUE;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? NO_VALUE : text;
    }

    /** Count matching records grouped by one field, under the default ceiling. */
    public static Tally countBy(ArchiveDb connection, Query query, String group) {
        return countBy(connection, query, group, Execute.DEFAULT_SCAN_CEILING);
    }

    /** Count matching records grouped by one field. */
    public static Tally countBy(ArchiveDb connection, Query query, String group, int ceiling) {
        String name = checkGroup(group);
        Plan built = Plan.plan(query);
        if (built.fullyPushed() && Plan.COLUMNS.containsKey(name)) {
            return countInDatabase(connection, built, Plan.COLUMNS.get(name));
        }
        return countInMemory(
                connection,
                query,
                ceiling,
                record -> groupKey(Evaluate.fieldValue(record, name).orElse("")));
    }

    /** Count matching records into equal time buckets, under the default ceiling. */
    public static Tally countOverTime(ArchiveDb connection, Query query, Duration bucket) {
        return countOverTime(connection, query, bucket, Execute.DEFAULT_SCAN_CEILING);
    }

    /** Count matching records into equal time buckets. */
    public static Tally countOverTime(ArchiveDb connection, Query query, Duration bucket, int ceiling) {
        if (bucket.compareTo(MIN_BUCKET) < 0) {
            throw new QueryError("a bucket must be at least a second", Units.describe(bucket));
        }
        long width = (long) (Units.totalSeconds(bucket) * NS_PER_SECOND);
        Plan built = Plan.plan(query);
        if (built.fullyPushed()) {
            String column = "CAST(" + Plan.TIME_COLUMN + " / " + width + " AS INTEGER) * " + width;
            return countInDatabase(connection, built, column);
        }
        return countInMemory(
                connection,
                query,
                ceiling,
                record -> Long.toString(bucketOf(record.bestAt().epochNs(), width)));
    }

    private static Tally countInDatabase(ArchiveDb connection, Plan built, String column) {
        String statement = "SELECT " + column + " AS bucket, COUNT(*) AS n,"
                + " MIN(" + Plan.TIME_COLUMN + ") AS first_ns, MAX(" + Plan.TIME_COLUMN + ") AS last_ns"
                + " FROM records WHERE " + built.fullWhere() + " GROUP BY bucket ORDER BY n DESC, bucket ASC"
                + " LIMIT " + MAX_GROUPS;
        List<Row> rows;
        try {
            rows = connection.query(statement, built.allParams().toArray());
        } catch (SqlFailure exc) {
            throw new QueryError("the archive refused the count", exc.getMessage(), exc);
        }
        GroupCounter counter = new GroupCounter();
        for (Row row : rows) {
            counter.put(groupKey(row.get("bucket")), row.getLong("n"), row.getLong("first_ns"), row.getLong("last_ns"));
        }
        Tally tally = new Tally(counter.groups(), true);
        tally.scanned(tally.total());
        tally.truncated(rows.size() >= MAX_GROUPS);
        return tally;
    }

    /**
     * Read the rows and tally them here, because SQL cannot be trusted to. This is the path a label
     * predicate forces: the residual has to be applied per record before anything is counted.
     */
    private static Tally countInMemory(
            ArchiveDb connection, Query query, int ceiling, Function<StoredRecord, String> keyOf) {
        if (ceiling < 1) {
            throw new QueryError("scan ceiling must be at least one", Integer.toString(ceiling));
        }
        Plan built = Plan.plan(query);
        List<Row> rows;
        try {
            rows = connection.query(
                    built.sql() + " LIMIT " + ceiling, built.allParams().toArray());
        } catch (SqlFailure exc) {
            throw new QueryError("the archive refused the count", exc.getMessage(), exc);
        }
        GroupCounter counter = new GroupCounter();
        Tally tally = new Tally(List.of(), false);
        tally.truncated(rows.size() >= ceiling);
        for (Row row : rows) {
            tally.scan();
            StoredRecord record = Repository.rowToRecord(row);
            if (built.residual().isPresent()
                    && !Evaluate.matches(built.residual().get(), record)) {
                tally.drop();
                continue;
            }
            counter.add(keyOf.apply(record), record.bestAt().epochNs());
        }
        List<Group> groups = counter.groups();
        tally.groups(groups.size() > MAX_GROUPS ? groups.subList(0, MAX_GROUPS) : groups);
        return tally;
    }
}
