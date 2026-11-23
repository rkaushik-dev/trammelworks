package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.SystemClock;
import io.github.rkaushikdev.trammelworks.Units;
import io.github.rkaushikdev.trammelworks.forecourt.AlarmLog;
import io.github.rkaushikdev.trammelworks.forecourt.AlarmSeverity;
import io.github.rkaushikdev.trammelworks.forecourt.AlarmSpan;
import io.github.rkaushikdev.trammelworks.query.And;
import io.github.rkaushikdev.trammelworks.query.Node;
import io.github.rkaushikdev.trammelworks.query.Query;
import io.github.rkaushikdev.trammelworks.query.SearchResult;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code stanchion alarms}: tank gauge alarms in the archive, as raised and cleared intervals. */
@Command(name = "alarms", description = "List tank gauge alarms as raised and cleared intervals.")
final class AlarmsCommand implements Callable<Integer> {

    /** Exits with this when an environmental alarm is still standing. */
    static final int EXIT_STANDING = 3;

    @ParentCommand
    Cli root;

    @Parameters(
            index = "0",
            arity = "0..1",
            defaultValue = "",
            paramLabel = "FILTER",
            description = "Narrow which records are read, such as 'since 30d'.")
    String filterText;

    @Option(names = "--open", description = "Only the alarms still standing.")
    boolean openOnly;

    /** Only alarm records, narrowed by whatever was typed. */
    static Query alarmsOnly(Query typed) {
        Node alarms = AlarmLog.filter();
        return new Query(
                Optional.of(
                        typed.where().<Node>map(node -> new And(node, alarms)).orElse(alarms)),
                OptionalInt.empty());
    }

    /** One span on one line. */
    static String render(AlarmSpan span, NanoTime now) {
        Duration standing = span.standing(now);
        String until = span.clearedAt().map(NanoTime::isoformat).orElse("STANDING");
        return "T" + span.tank() + " " + Columns.left(span.alarm().value(), 14) + " "
                + Columns.left(span.alarm().severity().value(), 13) + " "
                + span.raisedAt().isoformat() + " "
                + until + " " + Units.formatDuration(standing);
    }

    @Override
    public Integer call() {
        CliContext context = root.context();
        Query query = alarmsOnly(context.filter(filterText));
        SearchResult found = context.search("list alarms from", query);
        NanoTime now = new SystemClock().now();
        List<AlarmSpan> spans = openOnly
                ? AlarmLog.replay(found.records()).open()
                : AlarmLog.replay(found.records()).spans();
        if (spans.isEmpty()) {
            context.echo("no alarms");
        }
        spans.forEach(span -> context.echo(render(span, now)));
        if (found.truncated()) {
            context.warn("stopped after scanning " + found.scanned() + " rows, narrow the filter");
        }
        boolean environmental =
                spans.stream().anyMatch(span -> span.open() && span.alarm().severity() == AlarmSeverity.ENVIRONMENTAL);
        return environmental ? EXIT_STANDING : Cli.EXIT_OK;
    }
}
