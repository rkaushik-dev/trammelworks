package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.SystemClock;
import io.github.rkaushikdev.trammelworks.forecourt.BusinessDay;
import io.github.rkaushikdev.trammelworks.forecourt.DayEnd;
import io.github.rkaushikdev.trammelworks.query.And;
import io.github.rkaushikdev.trammelworks.query.Query;
import io.github.rkaushikdev.trammelworks.query.SearchResult;
import io.github.rkaushikdev.trammelworks.query.Since;
import io.github.rkaushikdev.trammelworks.query.Until;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * {@code stanchion dayend}: the day end figures for one trading day, cut at the site's day close.
 * With no date, the last day to have closed.
 */
@Command(name = "dayend", description = "Write the day end figures for one trading day.")
final class DayEndCommand implements Callable<Integer> {

    @ParentCommand
    Cli root;

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "DATE",
            description = "The trading day, YYYY-MM-DD; the last one to close when left out.")
    String date;

    @Override
    public Integer call() {
        CliContext context = root.context();
        SiteConfig config = context.loaded();
        BusinessDay day;
        if (date == null) {
            day = config.forecourt().lastClosedDay(new SystemClock().now());
        } else {
            try {
                day = new BusinessDay(
                        LocalDate.parse(date),
                        config.forecourt().timezone(),
                        config.forecourt().dayClose());
            } catch (DateTimeException exc) {
                throw context.fail(
                        "stanchion.error: a trading day is written YYYY-MM-DD (" + date + ")", Cli.EXIT_CONFIG);
            }
        }
        Query inside = Query.of(
                new And(new Since(day.start().epochNs()), new Until(day.end().epochNs())));
        SearchResult found = context.search("write a day end from", inside);
        if (found.truncated()) {
            context.warn("stopped after scanning " + found.scanned() + " rows, these figures are short");
        }
        DayEnd figures = DayEnd.of(day, found.records());
        context.echo(config.site() + " " + String.join("\n", figures.lines()));
        return Cli.EXIT_OK;
    }
}
