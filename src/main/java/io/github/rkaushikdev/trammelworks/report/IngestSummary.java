package io.github.rkaushikdev.trammelworks.report;

import java.util.ArrayList;
import java.util.List;

/**
 * What came in.
 *
 * @param filesFollowed container log files being followed
 * @param records records produced
 * @param skipped lines that would not parse
 * @param rotations spool segments recovered after a restart
 * @param lostBytes bytes lost to those recoveries
 */
public record IngestSummary(long filesFollowed, long records, long skipped, long rotations, long lostBytes)
        implements SummarySection {

    /** Nothing counted yet. */
    public IngestSummary() {
        this(0, 0, 0, 0, 0);
    }

    /** Whether anything is being collected at all. */
    @Override
    public boolean healthy() {
        return filesFollowed > 0;
    }

    @Override
    public List<String> problems() {
        List<String> found = new ArrayList<>();
        if (!healthy()) {
            found.add("no container log files are being followed");
        }
        if (lostBytes != 0) {
            found.add(lostBytes + " bytes were lost to rotations");
        }
        return found;
    }

    @Override
    public String line() {
        return records + " records from " + filesFollowed + " files";
    }

    /** The same, following {@code value} files. */
    public IngestSummary withFilesFollowed(long value) {
        return new IngestSummary(value, records, skipped, rotations, lostBytes);
    }

    /** The same, with {@code value} records. */
    public IngestSummary withRecords(long value) {
        return new IngestSummary(filesFollowed, value, skipped, rotations, lostBytes);
    }

    /** The same, having lost {@code value} bytes. */
    public IngestSummary withLostBytes(long value) {
        return new IngestSummary(filesFollowed, records, skipped, rotations, value);
    }
}
