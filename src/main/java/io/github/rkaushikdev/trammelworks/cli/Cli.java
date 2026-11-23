package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.Build;
import io.github.rkaushikdev.trammelworks.ConfigFile;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.StanchionError;
import io.github.rkaushikdev.trammelworks.Version;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * The command line, which is how a field engineer touches a site.
 *
 * <p>Every command takes the same two things, where the config is and how loud to be, and they sit
 * on the top level command rather than on each subcommand, because a support call goes "run
 * stanchion with the config you were given and paste me what it says", and that has to work for
 * whichever subcommand comes next.
 */
@Command(
        name = "stanchion",
        description = "Container log aggregation for a forecourt site controller.",
        versionProvider = Cli.VersionText.class,
        sortOptions = false,
        subcommands = {
            Cli.ConfigCommand.class,
            Cli.VersionCommand.class,
            AlarmsCommand.class,
            BackfillCommand.class,
            ClockCommand.class,
            CountCommand.class,
            DoctorCommand.class,
            HoldCommands.Hold.class,
            HoldCommands.Sweep.class,
            IngestCommands.Discover.class,
            IngestCommands.Tail.class,
            QueryCommand.class,
            RedactCommand.class,
            ReportCommands.Report.class,
            ReportCommands.Summary.class,
            RunCommands.Run.class,
            RunCommands.Ship.class,
            SalesCommand.class,
            ServeCommand.class,
            SpoolCommand.class,
            UnitsCommands.Units.class,
            UnitsCommands.Housekeep.class,
            VerifyCommand.class
        })
public final class Cli implements Callable<Integer> {

    /** Where a commissioned box keeps its config. */
    public static final String DEFAULT_CONFIG = "/etc/stanchion/site.yaml";

    /** The command did what was asked. */
    public static final int EXIT_OK = 0;

    /** The command could not do what was asked. */
    public static final int EXIT_FAILED = 1;

    /** The config, or the command line itself, was unusable. */
    public static final int EXIT_CONFIG = 2;

    @Option(
            names = "--config",
            paramLabel = "PATH",
            defaultValue = DEFAULT_CONFIG,
            showDefaultValue = CommandLine.Help.Visibility.ALWAYS,
            description = "Site configuration file.")
    Path configPath;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show more about each step.")
    boolean verbose;

    @Option(
            names = {"-h", "--help"},
            usageHelp = true,
            description = "Show this message and exit.")
    boolean help;

    @Option(names = "--version", versionHelp = true, description = "Show the version and exit.")
    boolean version;

    private PrintWriter out = new PrintWriter(System.out, true);
    private PrintWriter err = new PrintWriter(System.err, true);
    private InputStream in = System.in;

    void streams(PrintWriter outWriter, PrintWriter errWriter, InputStream input) {
        this.out = outWriter;
        this.err = errWriter;
        this.in = input;
    }

    /** The context the subcommands share. */
    CliContext context() {
        return new CliContext(configPath, verbose, out, err, in);
    }

    @Override
    public Integer call() {
        new CommandLine(this).usage(out);
        out.flush();
        return EXIT_OK;
    }

    /**
     * Parse {@code args} and run the command they name, with these streams. Returns the exit code:
     * an expected failure has already been printed as one line with its code in it.
     */
    public static int execute(String[] args, InputStream in, PrintWriter out, PrintWriter err) {
        Cli root = new Cli();
        root.streams(out, err, in);
        CommandLine line = new CommandLine(root);
        line.setOut(out);
        line.setErr(err);
        line.setExecutionExceptionHandler((exc, failed, parsed) -> {
            if (exc instanceof CliExit stop) {
                return stop.code();
            }
            if (exc instanceof CommandLine.ParameterException refused) {
                out.flush();
                return failed.getParameterExceptionHandler().handleParseException(refused, args);
            }
            if (exc instanceof StanchionError error) {
                out.flush();
                err.println(error.code() + ": " + error.getMessage());
                err.flush();
                return EXIT_FAILED;
            }
            throw exc;
        });
        int code = line.execute(args);
        out.flush();
        err.flush();
        return code;
    }

    /** The version line {@code --version} prints. */
    static final class VersionText implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"stanchion, version " + Version.VERSION};
        }
    }

    /** Load the site config and print it back as it was understood. */
    @Command(name = "config", description = "Load the site config and print it back as it was understood.")
    static final class ConfigCommand implements Callable<Integer> {

        @ParentCommand
        Cli root;

        @Option(names = "--check", description = "Validate only, print nothing.")
        boolean check;

        @Override
        public Integer call() {
            CliContext context = root.context();
            SiteConfig found = context.loaded();
            if (check) {
                context.echo("ok " + found.site());
                return EXIT_OK;
            }
            context.write(ConfigFile.dumpSiteConfig(found));
            return EXIT_OK;
        }
    }

    /** Print the version, or everything that identifies this build. */
    @Command(name = "version", description = "Print the version, or everything that identifies this build.")
    static final class VersionCommand implements Callable<Integer> {

        @ParentCommand
        Cli root;

        @Option(names = "--full", description = "Every number that identifies this build.")
        boolean full;

        @Option(names = "--json", description = "The same, as one object.")
        boolean asJson;

        @Override
        public Integer call() {
            CliContext context = root.context();
            if (asJson) {
                context.echo(Json.dumps(Build.current().asDict(), Json.Style.indented(1)));
            } else if (full) {
                context.echo(Build.current().toString());
            } else {
                context.echo(Version.VERSION);
            }
            return EXIT_OK;
        }
    }
}
