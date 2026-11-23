package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.SystemClock;
import io.github.rkaushikdev.trammelworks.Units;
import io.github.rkaushikdev.trammelworks.forecourt.Grade;
import io.github.rkaushikdev.trammelworks.forecourt.PriceAudit;
import io.github.rkaushikdev.trammelworks.forecourt.PriceMismatch;
import io.github.rkaushikdev.trammelworks.query.InSet;
import io.github.rkaushikdev.trammelworks.query.SearchResult;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code stanchion prices}: where a pump charged a different price than the sign showed. */
@Command(name = "prices", description = "List pumps that charged a different price than the sign showed.")
final class PricesCommand implements Callable<Integer> {

    /** Exits with this when a pump and the sign disagreed past the grace period. */
    static final int EXIT_MISMATCH = 3;

    @ParentCommand
    Cli root;

    @Parameters(
            index = "0",
            arity = "0..1",
            defaultValue = "",
            paramLabel = "FILTER",
            description = "Narrow which records are read, such as 'since 7d'.")
    String filterText;

    static String render(PriceMismatch item, NanoTime now) {
        String until = item.until().map(NanoTime::isoformat).orElse("STILL");
        return "pump " + item.pump() + " " + item.grade().value() + " sign "
                + item.sign().toPlainString() + " pump "
                + item.pumpPrice().toPlainString() + " from " + item.since().isoformat() + " until " + until + " ("
                + Units.formatDuration(item.lasted(now)) + ")";
    }

    @Override
    public Integer call() {
        CliContext context = root.context();
        List<String> grades = Arrays.stream(Grade.values()).map(Grade::value).toList();
        SearchResult found = context.search(
                "audit prices in", context.filter(filterText).narrowedTo(new InSet("label." + Grade.LABEL, grades)));
        NanoTime now = new SystemClock().now();
        List<PriceMismatch> mismatches = PriceAudit.replay(found.records()).mismatches(now);
        if (mismatches.isEmpty()) {
            context.echo("every pump charged what the sign showed");
        }
        mismatches.forEach(item -> context.echo(render(item, now)));
        return mismatches.isEmpty() ? Cli.EXIT_OK : EXIT_MISMATCH;
    }
}
