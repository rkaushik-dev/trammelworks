package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.ConfigFile;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.StanchionError;
import io.github.rkaushikdev.trammelworks.SystemClock;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.query.Cursor;
import io.github.rkaushikdev.trammelworks.query.Execute;
import io.github.rkaushikdev.trammelworks.query.Parser;
import io.github.rkaushikdev.trammelworks.query.Query;
import io.github.rkaushikdev.trammelworks.query.SearchResult;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What every command needs and nothing more: where the config is, how loud to be, and where to
 * write.
 *
 * <p>Nothing a command does prints a bare stack trace. A trace on a laptop in a forecourt car park
 * tells the engineer nothing they can act on, so every expected failure is caught and rendered as one
 * line with the stable error code in it.
 */
public final class CliContext {

    private final Path configPath;
    private final boolean verbose;
    private final PrintWriter out;
    private final PrintWriter err;
    private final InputStream in;

    /** A context for one invocation. */
    public CliContext(Path configPath, boolean verbose, PrintWriter out, PrintWriter err, InputStream in) {
        this.configPath = configPath;
        this.verbose = verbose;
        this.out = out;
        this.err = err;
        this.in = in;
    }

    /** The config file the command was pointed at. */
    public Path configPath() {
        return configPath;
    }

    /** Whether to say more about each step. */
    public boolean verbose() {
        return verbose;
    }

    /** Standard input. */
    public InputStream in() {
        return in;
    }

    /** Standard output, for a caller printing help. */
    public PrintWriter printer() {
        return out;
    }

    /** Print one line to standard output. */
    public void echo(Object line) {
        out.println(line);
        out.flush();
    }

    /** Print text to standard output with no newline added. */
    public void write(String text) {
        out.print(text);
        out.flush();
    }

    /** Print one line to standard error. */
    public void warn(Object line) {
        out.flush();
        err.println(line);
        err.flush();
    }

    /** Load the site config, letting a failure through. */
    public SiteConfig load() {
        return ConfigFile.loadSiteConfig(configPath);
    }

    /** Print one line to standard error and stop with {@link Cli#EXIT_FAILED}. */
    public CliExit fail(String message) {
        return fail(message, Cli.EXIT_FAILED);
    }

    /** Print one line to standard error and stop with {@code code}. */
    public CliExit fail(String message, int code) {
        warn(message);
        throw new CliExit(code);
    }

    /** Run {@code work}, turning an expected failure into one line and {@link Cli#EXIT_FAILED}. */
    public <T> T reporting(Supplier<T> work) {
        return reporting(Cli.EXIT_FAILED, work);
    }

    /** Run {@code work}, turning an expected failure into one line and a stop with {@code code}. */
    public <T> T reporting(int code, Supplier<T> work) {
        try {
            return work.get();
        } catch (StanchionError exc) {
            throw fail(exc.code() + ": " + exc.getMessage(), code);
        }
    }

    /** Run {@code work}, turning an expected failure into one line and {@link Cli#EXIT_FAILED}. */
    public void reporting(Runnable work) {
        reporting(Cli.EXIT_FAILED, () -> {
            work.run();
            return null;
        });
    }

    /** The site config, or one line and a stop with {@link Cli#EXIT_CONFIG}. */
    public SiteConfig loaded() {
        return reporting(Cli.EXIT_CONFIG, this::load);
    }

    /** The site config, or one line and a stop with {@code code}. */
    public SiteConfig loaded(int code) {
        return reporting(code, this::load);
    }

    /**
     * The config and a read only archive connection, or one line and a stop. Read only matters: the
     * collector is writing to that file while an engineer runs a report against it, and a connection
     * opened for writing takes a lock that stops the site collecting for as long as the report runs.
     */
    public ReadArchive readingArchive(String doing) {
        SiteConfig config = loaded();
        if (!config.archive().enabled()) {
            throw fail("this site keeps no archive, so there is nothing to " + doing);
        }
        ArchiveDb connection =
                reporting(() -> Schema.openArchive(config.archive().file(), true));
        return new ReadArchive(config, connection);
    }

    /** A filter typed on the command line, its relative times measured from now. */
    public Query filter(String text) {
        return reporting(() -> Parser.parseQuery(text, new SystemClock().now()));
    }

    /** Run {@code work} against the archive opened read only, and let go of it afterwards. */
    public <T> T withArchive(String doing, Function<ArchiveDb, T> work) {
        ReadArchive opened = readingArchive(doing);
        try (ArchiveDb connection = opened.connection()) {
            return reporting(() -> work.apply(connection));
        }
    }

    /** A first page of a search, under the default ceiling. */
    public SearchResult search(String doing, Query query) {
        return search(doing, query, Execute.DEFAULT_SCAN_CEILING, Optional.empty());
    }

    /** A page of a search, under {@code ceiling}, resuming after {@code after}. */
    public SearchResult search(String doing, Query query, int ceiling, Optional<Cursor> after) {
        return withArchive(doing, connection -> Execute.search(connection, query, ceiling, after));
    }

    /**
     * A config and the archive connection opened for it.
     *
     * @param config the site config
     * @param connection the archive, open
     */
    public record ReadArchive(SiteConfig config, ArchiveDb connection) {}
}
