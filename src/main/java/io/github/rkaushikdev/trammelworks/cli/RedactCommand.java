package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.JsonFiles;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.StanchionError;
import io.github.rkaushikdev.trammelworks.redact.RedactionAudit;
import io.github.rkaushikdev.trammelworks.redact.RedactionResult;
import io.github.rkaushikdev.trammelworks.redact.Redactor;
import io.github.rkaushikdev.trammelworks.redact.RuleHit;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code stanchion redact}: run the site's redaction pass over some lines and report what went. */
@Command(name = "redact", description = "Run the site's redaction pass over some lines and report what went.")
final class RedactCommand implements Callable<Integer> {

    static final int EXIT_CLEAN = 0;
    static final int EXIT_FOUND = 3;

    @ParentCommand
    Cli root;

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "SOURCE",
            description = "A file of lines; standard input when absent.")
    String source;

    @Option(names = "--explain", description = "Name the rules that fired on each line.")
    boolean explain;

    @Option(names = "--quiet", description = "Print the tally only.")
    boolean quiet;

    /**
     * What one line cost.
     *
     * @param number the line number, from one
     * @param text the line after the pass
     * @param rules the rules that fired, once per removal
     */
    record LineReport(int number, String text, List<String> rules) {
        boolean changed() {
            return !rules.isEmpty();
        }
    }

    /** Lines from a file, or from standard input when there is no file; bad bytes become U+FFFD. */
    static List<String> readLines(CliContext context, String source) {
        byte[] raw;
        try {
            raw = source == null ? context.in().readAllBytes() : Files.readAllBytes(Path.of(source));
        } catch (IOException exc) {
            throw new StanchionError("cannot read the input", JsonFiles.describe(exc));
        }
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException exc) {
            throw new StanchionError("cannot read the input", exc.getMessage());
        }
        return splitLines(text);
    }

    /** Lines the way a text file is read: any line ending ends a line, and no trailing empty line. */
    static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            boolean ending =
                    switch (character) {
                        case '\n', 0x0b, 0x0c, 0x1c, 0x1d, 0x1e, 0x85, 0x2028, 0x2029 -> true;
                        case '\r' -> {
                            if (index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                                index++;
                            }
                            yield true;
                        }
                        default -> false;
                    };
            if (ending) {
                out.add(line.toString());
                line.setLength(0);
            } else {
                line.append(character);
            }
        }
        if (!line.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }

    static List<LineReport> runPass(Redactor redactor, List<String> lines) {
        List<LineReport> out = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            RedactionResult found = redactor.redactText(lines.get(index));
            List<String> names = new ArrayList<>();
            for (int count = 0; count < found.stripes(); count++) {
                names.add(Redactor.STRIPE_RULE);
            }
            for (int count = 0; count < found.pans(); count++) {
                names.add(Redactor.CARD_RULE);
            }
            for (RuleHit hit : found.hits()) {
                names.add(hit.rule());
            }
            out.add(new LineReport(index + 1, found.text(), names));
        }
        return out;
    }

    @Override
    public Integer call() {
        CliContext context = root.context();
        SiteConfig config = context.reporting(context::load);
        List<String> lines = context.reporting(() -> readLines(context, source));
        if (!config.redact().enabled()) {
            context.warn("redaction is disabled on this site, nothing was checked");
        }
        Redactor redactor = Redactor.of(config.redact());
        List<LineReport> reports = context.reporting(() -> runPass(redactor, lines));
        for (LineReport report : reports) {
            if (!quiet) {
                context.echo(report.text());
            }
            if (explain && report.changed()) {
                context.warn("  line " + report.number() + ": " + String.join(", ", new TreeSet<>(report.rules())));
            }
        }
        printTally(context, redactor, lines.size());
        return redactor.audit().totalHits() != 0 ? EXIT_FOUND : EXIT_CLEAN;
    }

    private static void printTally(CliContext context, Redactor redactor, int lines) {
        RedactionAudit audit = redactor.audit();
        context.warn(lines + " lines, " + audit.totalHits() + " removed");
        Map<String, Long> sorted = new TreeMap<>(audit.hits());
        sorted.forEach((name, count) -> context.warn("  " + Columns.left(name, 20) + " " + count));
    }
}
