package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.PosixPath;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.forecourt.ForecourtConfig;
import io.github.rkaushikdev.trammelworks.forecourt.Tagger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** What the command line tests share: config files under the test's directory, and a quick look at files. */
final class CliFixtures {

    private CliFixtures() {}

    /** A path as a config file spells it. */
    static String posix(Path path) {
        return PosixPath.asPosix(path);
    }

    /** Write {@code body} as {@code site.yaml} under {@code tmp}. */
    static Path writeConfig(Path tmp, String body) {
        Path target = tmp.resolve("site.yaml");
        try {
            Files.writeString(target, body, StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        return target;
    }

    /** Files in {@code directory} whose names end with {@code suffix}, sorted. */
    static List<Path> files(Path directory, String suffix) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> found = Files.list(directory)) {
            return found.filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    /** Run against {@code config}. */
    static CliRunner.Result invoke(Path config, String... args) {
        String[] full = new String[args.length + 2];
        full[0] = "--config";
        full[1] = config.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        return CliRunner.invoke(full);
    }

    /** The day the forecourt fixtures happen on. */
    static final String DAY = "2025-03-04T";

    /** When the forecourt fixtures were archived. */
    static final NanoTime ARCHIVED_AT = NanoTime.parse(DAY + "12:00:00Z");

    /** Where {@link #forecourtConfig} keeps its archive. */
    static Path archiveFile(Path tmp) {
        return tmp.resolve("archive").resolve("records.sqlite3");
    }

    /** A site with its spool and archive under {@code tmp}, and nothing else set. */
    static Path forecourtConfig(Path tmp) {
        return writeConfig(
                tmp,
                "site: US-TX-0417\nspool:\n  directory: " + posix(tmp.resolve("spool")) + "\narchive:\n  path: "
                        + posix(archiveFile(tmp)) + "\n");
    }

    /**
     * Archive container lines the way the site would: each one tagged first. A line is the unit,
     * the time of day on {@link #DAY}, and the message.
     */
    static void archiveTagged(Path tmp, List<List<String>> lines) {
        Tagger tagger = new Tagger(new ForecourtConfig());
        try (ArchiveDb connection = Schema.openArchive(archiveFile(tmp))) {
            Repository repo = new Repository(connection);
            for (List<String> line : lines) {
                repo.add(
                        tagger.tag(new LogRecord(
                                "US-TX-0417",
                                line.get(0),
                                io.github.rkaushikdev.trammelworks.Stream.STDOUT,
                                NanoTime.parse(DAY + line.get(1) + "Z"),
                                line.get(2))),
                        ARCHIVED_AT);
            }
        }
    }
}
