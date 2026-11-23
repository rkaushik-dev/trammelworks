package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import io.github.rkaushikdev.trammelworks.query.Cursor;
import io.github.rkaushikdev.trammelworks.query.Execute;
import io.github.rkaushikdev.trammelworks.query.Query;
import io.github.rkaushikdev.trammelworks.query.SearchResult;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code stanchion query}: search the local archive with a filter expression. */
@Command(name = "query", description = "Search the local archive with a filter expression.")
final class QueryCommand implements Callable<Integer> {

    static final int PREVIEW = 120;

    @ParentCommand
    Cli root;

    @Parameters(index = "0", arity = "0..1", defaultValue = "", paramLabel = "FILTER", description = "The filter.")
    String filterText;

    boolean full;

    @Option(names = "--full", description = "Print whole messages.")
    void full(boolean value) {
        full = true;
    }

    @Option(names = "--truncate", description = "Cut long messages short.")
    void truncate(boolean value) {
        full = false;
    }

    @Option(names = "--count", description = "Print the number of matches only.")
    boolean countOnly;

    @Option(names = "--explain", description = "Show how the filter will be run.")
    boolean showPlan;

    @Option(
            names = "--ceiling",
            defaultValue = "200000",
            showDefaultValue = Visibility.ALWAYS,
            description = "Most rows to scan.")
    int ceiling;

    @Option(names = "--after", defaultValue = "", description = "Resume from the cursor a previous page printed.")
    String after;

    /** One record on one line, the way an engineer reads it; a time nobody vouches for gets a mark. */
    static String renderRecord(StoredRecord record, boolean full) {
        String body = full ? record.message() : IngestCommands.preview(record.message(), PREVIEW);
        String mark =
                record.confidence().equals("measured") || record.confidence().equals("interpolated") ? "" : "?";
        return record.bestAt().isoformat() + mark + " " + record.unit() + " "
                + record.stream().value() + " " + body.replace("\n", "\\n");
    }

    @Override
    public Integer call() {
        CliContext context = root.context();
        Query query = context.filter(filterText);
        if (showPlan) {
            context.warn(Execute.explain(query));
        }
        Optional<Cursor> resume =
                after.isEmpty() ? Optional.empty() : Optional.of(context.reporting(() -> Cursor.parseCursor(after)));
        SearchResult found = context.search("query", query, ceiling, resume);
        if (countOnly) {
            context.echo(found.count());
        } else {
            found.records().forEach(record -> context.echo(renderRecord(record, full)));
        }
        if (found.more()) {
            found.nextCursor().ifPresent(next -> context.warn("next page: --after " + next.encode()));
        }
        if (found.truncated()) {
            context.warn("stopped after scanning " + found.scanned() + " rows, narrow the filter");
        }
        if (context.verbose()) {
            context.warn(found.describe());
        }
        return Cli.EXIT_OK;
    }
}
