package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.query.Node;
import io.github.rkaushikdev.trammelworks.query.SearchResult;
import java.util.concurrent.Callable;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * What the commands that replay the forecourt out of the archive share: a filter typed to narrow
 * what is read, the kind of record each one replays, and a warning when the read stopped short,
 * because a replay of half a day reads as a quiet day.
 */
abstract class ForecourtCommand implements Callable<Integer> {

    /** Exits with this when there is something on the forecourt to answer for. */
    static final int EXIT_FOUND = 3;

    @ParentCommand
    Cli root;

    @Parameters(
            index = "0",
            arity = "0..1",
            defaultValue = "",
            paramLabel = "FILTER",
            description = "Narrow which records are read, such as 'since 7d'.")
    String filterText = "";

    /** The records this command replays, whatever the filter. */
    abstract Node records();

    /** Read what the filter and {@link #records()} match, saying so when the read was cut short. */
    SearchResult read(CliContext context, String doing) {
        SearchResult found = context.search(doing, context.filter(filterText).narrowedTo(records()));
        if (found.truncated()) {
            context.warn("stopped after scanning " + found.scanned() + " rows, narrow the filter");
        }
        return found;
    }
}
