package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Units;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import io.github.rkaushikdev.trammelworks.forecourt.AuthStage;
import io.github.rkaushikdev.trammelworks.forecourt.FuellingPoint;
import io.github.rkaushikdev.trammelworks.forecourt.Outcome;
import io.github.rkaushikdev.trammelworks.forecourt.PumpState;
import io.github.rkaushikdev.trammelworks.forecourt.ResponseCode;
import io.github.rkaushikdev.trammelworks.query.And;
import io.github.rkaushikdev.trammelworks.query.Compare;
import io.github.rkaushikdev.trammelworks.query.Node;
import io.github.rkaushikdev.trammelworks.query.Op;
import io.github.rkaushikdev.trammelworks.query.Or;
import io.github.rkaushikdev.trammelworks.query.Query;
import io.github.rkaushikdev.trammelworks.query.SearchResult;
import io.github.rkaushikdev.trammelworks.query.Since;
import io.github.rkaushikdev.trammelworks.query.Until;
import io.github.rkaushikdev.trammelworks.report.Render;
import io.github.rkaushikdev.trammelworks.report.RenderOptions;
import io.github.rkaushikdev.trammelworks.report.ReportFormat;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.Option;

/**
 * {@code stanchion dispute}: everything the archive holds about one sale, for a chargeback case.
 *
 * <p>A dispute team asks about a sale by its transaction reference, or by the pump and the time on
 * the till roll. Either way what they need is the same sheet: the payment lines and the dispenser
 * lines for that pump either side of the sale, with the pump, its state, the response code and the
 * amounts in columns they can sort by. Found by the reference, the sheet also carries every line
 * of that reference, whichever pump it names.
 */
@Command(name = "dispute", description = "Gather everything about one sale for a chargeback case.")
final class DisputeCommand extends ForecourtCommand {

    /** Nothing matched. */
    static final int EXIT_EMPTY = 4;

    /** The labels a dispute sheet carries in columns, in the order a dispute team reads them. */
    static final List<String> COLUMNS = List.of(
            FuellingPoint.LABEL,
            PumpState.LABEL,
            AuthStage.LABEL,
            AuthStage.AMOUNT_LABEL,
            AuthStage.REF_LABEL,
            ResponseCode.LABEL,
            Outcome.LABEL);

    @Option(names = "--ref", paramLabel = "REF", description = "The transaction reference.")
    String reference;

    @Option(names = "--pump", paramLabel = "N", description = "The fuelling point, when there is no reference.")
    String pump;

    @Option(names = "--at", paramLabel = "TIME", description = "When the sale was, when there is no reference.")
    String at;

    @Option(
            names = "--window",
            defaultValue = "15m",
            showDefaultValue = Visibility.ALWAYS,
            description = "How far either side of the sale to read.")
    String window;

    @Option(
            names = "--format",
            defaultValue = "csv",
            showDefaultValue = Visibility.ALWAYS,
            description = "Output format.")
    String formatName;

    private NanoTime when;
    private Duration either;

    @Override
    Node records() {
        Node around =
                new And(new Since(when.epochNs() - either.toNanos()), new Until(when.epochNs() + either.toNanos()));
        Node sale = new And(new Compare("label." + FuellingPoint.LABEL, Op.EQ, pump), around);
        return reference == null ? sale : new Or(sale, byReference());
    }

    private Node byReference() {
        return new Compare("label." + AuthStage.REF_LABEL, Op.EQ, reference);
    }

    /** Where the reference was first seen, which fixes the pump and the time when they were not given. */
    private void locate(CliContext context) {
        List<StoredRecord> named =
                context.search("gather a dispute from", Query.of(byReference())).records();
        if (named.isEmpty()) {
            throw context.fail("no payment line carries reference " + reference, EXIT_EMPTY);
        }
        StoredRecord first =
                named.stream().min(Comparator.comparing(StoredRecord::bestAt)).orElseThrow();
        when = when != null ? when : first.bestAt();
        if (pump == null) {
            pump = named.stream()
                    .map(record -> record.labels().get(FuellingPoint.LABEL))
                    .filter(value -> value != null)
                    .findFirst()
                    .orElseThrow(() -> context.fail("reference " + reference + " names no pump, give --pump"));
        }
    }

    @Override
    public Integer call() {
        CliContext context = root.context();
        RenderOptions options = context.reporting(() ->
                RenderOptions.of(ReportFormat.parse(formatName)).withWidth(120).withLabels(COLUMNS));
        either = context.reporting(() -> Units.parseDuration(window));
        when = at == null ? null : context.reporting(() -> NanoTime.parse(at));
        if (reference != null) {
            locate(context);
        }
        if (pump == null || when == null) {
            throw context.fail("give a reference, or a pump and a time", Cli.EXIT_CONFIG);
        }
        SearchResult found = read(context, "gather a dispute from");
        if (found.count() == 0) {
            context.warn("nothing on pump " + pump + " within " + window + " of " + when.isoformat());
            return EXIT_EMPTY;
        }
        context.echo(Render.render(found.records(), options));
        return Cli.EXIT_OK;
    }
}
