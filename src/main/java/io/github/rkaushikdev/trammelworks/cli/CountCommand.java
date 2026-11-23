package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Units;
import io.github.rkaushikdev.trammelworks.query.Aggregate;
import io.github.rkaushikdev.trammelworks.query.Group;
import io.github.rkaushikdev.trammelworks.query.Query;
import io.github.rkaushikdev.trammelworks.query.Tally;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code stanchion count}: count matching records, grouped by a field or over time. */
@Command(name = "count", description = "Count matching records, grouped by a field or over time.")
final class CountCommand implements Callable<Integer> {

    static final int BAR_WIDTH = 32;
    static final int KEY_WIDTH = 24;

    @ParentCommand
    Cli root;

    @Parameters(index = "0", arity = "0..1", defaultValue = "", paramLabel = "FILTER", description = "The filter.")
    String filterText;

    @Option(names = "--by", description = "Group by one of: class, confidence, site, stream, unit.")
    String group;

    @Option(names = "--every", description = "Group into time buckets of this width.")
    String bucket;

    @Option(
            names = "--ceiling",
            defaultValue = "200000",
            showDefaultValue = Visibility.ALWAYS,
            description = "Most rows to scan.")
    int ceiling;

    /**
     * A row of blocks in proportion to the biggest group, one block minimum for anything that
     * happened at all: a group rounding to no bar reads as a group with no records.
     */
    static String bar(long count, long largest) {
        return bar(count, largest, BAR_WIDTH);
    }

    /** A bar {@code width} blocks at most. */
    static String bar(long count, long largest, int width) {
        if (largest < 1 || count < 1) {
            return "";
        }
        long filled = Math.max(1, (long) Math.rint(width * (double) count / largest));
        return "#".repeat((int) Math.min(filled, width));
    }

    static String renderGroup(Group item, long largest, boolean asTime) {
        String key = asTime ? new NanoTime(Long.parseLong(item.key())).isoformat() : item.key();
        return Columns.left(key, KEY_WIDTH) + " " + Columns.right(item.count(), 8) + "  " + bar(item.count(), largest);
    }

    static List<String> render(Tally tally, boolean asTime) {
        long largest = tally.largest().map(Group::count).orElse(0L);
        List<Group> ordered = asTime
                ? tally.groups().stream()
                        .sorted(Comparator.comparingLong(item -> Long.parseLong(item.key())))
                        .toList()
                : tally.groups();
        return ordered.stream().map(item -> renderGroup(item, largest, asTime)).toList();
    }

    @Override
    public Integer call() {
        CliContext context = root.context();
        if (group != null && bucket != null) {
            throw context.fail("group by a field or over time, not both at once");
        }
        Query query = context.filter(filterText);
        Duration width = bucket == null ? null : context.reporting(() -> Units.parseDuration(bucket));
        Tally tally = context.withArchive(
                "count",
                connection -> width != null
                        ? Aggregate.countOverTime(connection, query, width, ceiling)
                        : Aggregate.countBy(connection, query, group == null ? "unit" : group, ceiling));
        if (tally.groups().isEmpty()) {
            context.echo("nothing matched");
            return Cli.EXIT_OK;
        }
        render(tally, width != null).forEach(context::echo);
        context.echo(tally.total() + " records in " + tally.groups().size() + " groups");
        if (tally.truncated()) {
            context.warn("stopped after scanning " + tally.scanned() + " rows, narrow the filter");
        }
        if (context.verbose()) {
            context.warn(tally.describe());
        }
        return Cli.EXIT_OK;
    }
}
