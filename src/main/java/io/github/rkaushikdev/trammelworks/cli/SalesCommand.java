package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.forecourt.AuthStage;
import io.github.rkaushikdev.trammelworks.forecourt.Overrun;
import io.github.rkaushikdev.trammelworks.forecourt.PumpEvent;
import io.github.rkaushikdev.trammelworks.forecourt.PumpState;
import io.github.rkaushikdev.trammelworks.forecourt.PumpTimeline;
import io.github.rkaushikdev.trammelworks.query.InSet;
import io.github.rkaushikdev.trammelworks.query.Node;
import io.github.rkaushikdev.trammelworks.query.Or;
import io.github.rkaushikdev.trammelworks.query.SearchResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * {@code stanchion sales}: the sales on this forecourt a site manager has to answer for.
 *
 * <p>Two kinds: product that flowed from a pump nobody authorised, and a completion the
 * pre-authorisation did not cover. Both are money the site is unlikely to see, and both are found
 * by replaying the archive rather than by anything the containers say outright.
 */
@Command(name = "sales", description = "List deliveries nobody authorised and completions above their hold.")
final class SalesCommand implements Callable<Integer> {

    /** Exits with this when there is anything to answer for. */
    static final int EXIT_FOUND = 3;

    @ParentCommand
    Cli root;

    @Parameters(
            index = "0",
            arity = "0..1",
            defaultValue = "",
            paramLabel = "FILTER",
            description = "Narrow which records are read, such as 'since 1d'.")
    String filterText;

    /** The records a sale is made of: pump states and both halves of the payment. */
    static Node saleRecords() {
        List<String> states =
                Arrays.stream(PumpState.values()).map(PumpState::value).toList();
        return new Or(
                new InSet("label." + PumpState.LABEL, states),
                new InSet("label." + AuthStage.LABEL, List.of(AuthStage.PREAUTH, AuthStage.COMPLETION)));
    }

    static String render(PumpEvent event) {
        return "unauthorised pump " + event.pump() + " at " + event.at().isoformat() + ", "
                + (event.from() == null ? "unknown" : event.from().value()) + " to "
                + event.to().value();
    }

    static String render(Overrun overrun) {
        String pump = overrun.pump().isEmpty() ? "" : " pump " + overrun.pump();
        String held = overrun.authorised()
                .map(value -> "held " + value.toPlainString())
                .orElse("no preauth seen");
        return "overrun ref " + overrun.reference() + pump + " at "
                + overrun.at().isoformat() + ", " + held
                + ", completed " + overrun.completed().toPlainString() + ", over by "
                + overrun.excess().toPlainString();
    }

    @Override
    public Integer call() {
        CliContext context = root.context();
        SearchResult found =
                context.search("check sales in", context.filter(filterText).narrowedTo(saleRecords()));
        List<String> lines = new ArrayList<>();
        PumpTimeline.replay(found.records()).unauthorised().forEach(event -> lines.add(render(event)));
        Overrun.find(found.records()).forEach(overrun -> lines.add(render(overrun)));
        if (lines.isEmpty()) {
            context.echo("nothing to answer for");
        }
        lines.forEach(context::echo);
        if (found.truncated()) {
            context.warn("stopped after scanning " + found.scanned() + " rows, narrow the filter");
        }
        return lines.isEmpty() ? Cli.EXIT_OK : EXIT_FOUND;
    }
}
