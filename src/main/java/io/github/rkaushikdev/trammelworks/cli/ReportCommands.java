package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.SystemClock;
import io.github.rkaushikdev.trammelworks.query.Query;
import io.github.rkaushikdev.trammelworks.query.SearchResult;
import io.github.rkaushikdev.trammelworks.report.Render;
import io.github.rkaushikdev.trammelworks.report.RenderOptions;
import io.github.rkaushikdev.trammelworks.report.ReportFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code stanchion report} and {@code stanchion summary}. */
final class ReportCommands {

    /** A report that matched nothing. */
    static final int EXIT_EMPTY = 4;

    private ReportCommands() {}

    /** Write a report of archived records. */
    @Command(name = "report", description = "Write a report of archived records.")
    static final class Report implements Callable<Integer> {

        @ParentCommand
        Cli root;

        @Parameters(index = "0", arity = "0..1", defaultValue = "", paramLabel = "FILTER", description = "The filter.")
        String filterText;

        @Option(
                names = "--format",
                defaultValue = "text",
                showDefaultValue = Visibility.ALWAYS,
                description = "Output format.")
        String formatName;

        @Option(names = "--out", paramLabel = "PATH", description = "Write here instead of standard output.")
        Path outPath;

        @Option(
                names = "--width",
                defaultValue = "80",
                showDefaultValue = Visibility.ALWAYS,
                description = "Message column width.")
        int width;

        @Option(names = "--no-header", description = "Leave the header row out.")
        boolean noHeader;

        @Option(names = "--raw-time", description = "Show what the box claimed, not the correction.")
        boolean rawTime;

        @Option(
                names = "--labels",
                split = ",",
                paramLabel = "LABEL",
                description = "Labels to show in columns of their own, such as pump,response.")
        List<String> labels = List.of();

        @Override
        public Integer call() {
            CliContext context = root.context();
            RenderOptions options = context.reporting(
                    () -> new RenderOptions(ReportFormat.parse(formatName), width, !noHeader, rawTime, labels));
            Query query = context.filter(filterText);
            SearchResult found = context.search("report on", query);
            String body = Render.render(found.records(), options);
            if (outPath == null) {
                context.echo(body);
            } else {
                try {
                    Files.writeString(outPath, body + "\n", StandardCharsets.UTF_8);
                } catch (IOException exc) {
                    throw context.fail("stanchion.error: cannot write the report (" + exc.getMessage() + ")");
                }
                context.warn("wrote " + found.count() + " records to " + outPath);
            }
            if (found.truncated()) {
                context.warn("the report was truncated, narrow the filter");
            }
            return found.count() == 0 ? EXIT_EMPTY : Cli.EXIT_OK;
        }
    }

    /** Write the nightly summary for this site. */
    @Command(name = "summary", description = "Write the nightly summary for this site.")
    static final class Summary implements Callable<Integer> {

        @ParentCommand
        Cli root;

        @Option(names = "--json", description = "Write the machine readable form.")
        boolean asJson;

        @Override
        public Integer call() {
            CliContext context = root.context();
            SiteConfig config = context.loaded();
            io.github.rkaushikdev.trammelworks.report.Summary built =
                    io.github.rkaushikdev.trammelworks.report.Summary.fromDisk(
                            config,
                            new SystemClock().now(),
                            exc -> context.warn("the archive could not be read: " + exc.getMessage()));
            context.echo(asJson ? built.renderJson() : built.renderText());
            return Cli.EXIT_OK;
        }
    }
}
