package io.github.rkaushikdev.trammelworks.report;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.StanchionError;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.forecourt.ForecourtDay;
import io.github.rkaushikdev.trammelworks.spool.Segments;
import io.github.rkaushikdev.trammelworks.spool.Usage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The nightly summary: one site, one day.
 *
 * <p>For most of the estate it is the only thing anybody reads, so it answers four questions in the
 * order an operator asks them: is the site collecting at all, is what it collects getting off the
 * site, is it about to run out of disk or allowance, and is its clock believable. It is a handful of
 * lines rather than a dump of every counter, because a document nobody finishes hides the one line
 * that mattered.
 */
public final class Summary {

    /**
     * Where a field's JSON name is not its own. Every entry is a name the estate side already parses
     * and cannot be changed, not a preference.
     */
    public static final Map<String, String> FIELD_NAMES =
            Map.of("files_followed", "files", "bytes_sent", "bytes", "unknown_records", "unknown");

    /** How wide the section names are in the text form, the longest and a space. */
    static final int TEXT_NAME_WIDTH = "forecourt".length() + 1;

    private final String site;
    private final NanoTime at;
    private IngestSummary ingest = new IngestSummary();
    private ShipSummary ship = new ShipSummary();
    private SpoolSummary spool = new SpoolSummary();
    private ClockSummary clock = new ClockSummary();
    private ForecourtSummary forecourt = new ForecourtSummary();
    private Map<String, Long> redacted = new LinkedHashMap<>();
    private long archived;
    private List<String> problems = new ArrayList<>();

    /** An empty summary for {@code site} at {@code at}. */
    public Summary(String site, NanoTime at) {
        this.site = site;
        this.at = at;
    }

    /**
     * One summary section as JSON, every field of it, named in snake case. Walks the record's
     * components rather than listing them, so a field added to a section is in the summary the same
     * day it is added.
     */
    public static Map<String, Object> sectionDict(Record section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RecordComponent component : section.getClass().getRecordComponents()) {
            String name = snakeCase(component.getName());
            try {
                out.put(
                        FIELD_NAMES.getOrDefault(name, name),
                        component.getAccessor().invoke(section));
            } catch (IllegalAccessException | InvocationTargetException exc) {
                throw new IllegalStateException("cannot read summary field " + name, exc);
            }
        }
        return out;
    }

    /** The field names of a section as its JSON carries them. */
    public static List<String> sectionKeys(Class<? extends Record> section) {
        List<String> out = new ArrayList<>();
        for (RecordComponent component : section.getRecordComponents()) {
            String name = snakeCase(component.getName());
            out.add(FIELD_NAMES.getOrDefault(name, name));
        }
        return out;
    }

    private static String snakeCase(String name) {
        StringBuilder out = new StringBuilder();
        for (char character : name.toCharArray()) {
            if (Character.isUpperCase(character)) {
                out.append('_').append(Character.toLowerCase(character));
            } else {
                out.append(character);
            }
        }
        return out.toString();
    }

    /**
     * Build a summary from a metrics snapshot. Keys may be missing: a site that has never shipped
     * has no ship counters at all, and a summary that refused that would be missing for the exact
     * site somebody needs to look at.
     */
    public static Summary fromMetrics(String site, NanoTime at, Map<String, Long> metrics) {
        Summary summary = new Summary(site, at);
        summary.ingest = new IngestSummary(
                read(metrics, "ingest_files_followed"),
                read(metrics, "ingest_records"),
                read(metrics, "ingest_skipped_unparseable"),
                read(metrics, "spool_recovered_segments"),
                read(metrics, "spool_recovered_lost_bytes"));
        summary.ship = new ShipSummary(
                read(metrics, "ship_batches"),
                read(metrics, "ship_records"),
                read(metrics, "ship_bytes"),
                read(metrics, "ship_retryable"),
                read(metrics, "ship_terminal"),
                0,
                1,
                read(metrics, "ship_meter_blocked"));
        summary.spool = new SpoolSummary(
                read(metrics, "spool_bytes"),
                Math.max(1, metrics.getOrDefault("spool_limit", 1L)),
                read(metrics, "spool_segments_sealed"),
                read(metrics, "spool_evicted_segments"));
        summary.check();
        return summary;
    }

    private static long read(Map<String, Long> metrics, String name) {
        return metrics.getOrDefault(name, 0L);
    }

    /**
     * The summary of what is on disk: the spool as it stands, and the archive's record count and
     * the last day of the forecourt. An archive that cannot be read is handed to {@code
     * unreadable} and the rest of the summary is still built: a site whose archive is broken is
     * exactly the site whose summary somebody needs.
     */
    public static Summary fromDisk(SiteConfig config, NanoTime now, Consumer<StanchionError> unreadable) {
        Summary built = new Summary(config.site(), now);
        Usage usage = Usage.measure(Segments.listSegments(config.spool().path()));
        built.spool(new SpoolSummary(usage.used(), config.spool().budgetBytes(), usage.segments(), 0));
        ArchiveSettings settings = config.archive();
        if (settings.enabled() && Files.exists(settings.file())) {
            try (ArchiveDb connection = Schema.openArchive(settings.file(), true)) {
                built.archived(new Repository(connection).count());
                built.forecourt(ForecourtSummary.of(ForecourtDay.read(connection, now), config.forecourt(), now));
            } catch (StanchionError exc) {
                unreadable.accept(exc);
            }
        }
        built.check();
        return built;
    }

    /** The site. */
    public String site() {
        return site;
    }

    /** The moment the summary describes. */
    public NanoTime at() {
        return at;
    }

    /** What came in. */
    public IngestSummary ingest() {
        return ingest;
    }

    /** Replace what came in. */
    public void ingest(IngestSummary value) {
        ingest = value;
    }

    /** What went out. */
    public ShipSummary ship() {
        return ship;
    }

    /** Replace what went out. */
    public void ship(ShipSummary value) {
        ship = value;
    }

    /** What is waiting. */
    public SpoolSummary spool() {
        return spool;
    }

    /** Replace what is waiting. */
    public void spool(SpoolSummary value) {
        spool = value;
    }

    /** What the box believes about its time. */
    public ClockSummary clock() {
        return clock;
    }

    /** Replace what the box believes about its time. */
    public void clock(ClockSummary value) {
        clock = value;
    }

    /** What happened on the forecourt. */
    public ForecourtSummary forecourt() {
        return forecourt;
    }

    /** Replace what happened on the forecourt. */
    public void forecourt(ForecourtSummary value) {
        forecourt = value;
    }

    /** Every section by the name its JSON and its text line carry, in the order an operator reads. */
    public Map<String, SummarySection> sections() {
        Map<String, SummarySection> out = new LinkedHashMap<>();
        out.put("ingest", ingest);
        out.put("ship", ship);
        out.put("spool", spool);
        out.put("clock", clock);
        out.put("forecourt", forecourt);
        return out;
    }

    /** Values removed by redaction, by rule. */
    public Map<String, Long> redacted() {
        return redacted;
    }

    /** Replace the redaction tally. */
    public void redacted(Map<String, Long> value) {
        redacted = new LinkedHashMap<>(value);
    }

    /** Records kept in the local archive. */
    public long archived() {
        return archived;
    }

    /** Replace the archived count. */
    public void archived(long value) {
        archived = value;
    }

    /** What the last {@link #check()} found. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Whether there is nothing to tell an operator. */
    public boolean healthy() {
        return problems.isEmpty() && sections().values().stream().allMatch(SummarySection::healthy);
    }

    /** Find what an operator would want telling, section by section, and keep it as the problems. */
    public List<String> check() {
        List<String> found = new ArrayList<>();
        sections().values().forEach(section -> found.addAll(section.problems()));
        problems = found;
        return List.copyOf(found);
    }

    /** The shape the estate side parses, derived from the section records. */
    public Map<String, Object> asDict() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("site", site);
        out.put("at", at.isoformat());
        out.put("healthy", healthy());
        sections().forEach((name, section) -> out.put(name, sectionDict((Record) section)));
        Map<String, Long> sorted = new TreeMap<>(Json::compareCodePoints);
        sorted.putAll(redacted);
        out.put("redacted", new LinkedHashMap<>(sorted));
        out.put("archived", archived);
        out.put("problems", List.copyOf(problems));
        return out;
    }

    /** The JSON form, keys sorted so a diff between two nights reads. */
    public String renderJson() {
        return Json.dumps(asDict(), Json.Style.indented(1));
    }

    private static String textLine(String name, String body) {
        return "  " + name + " ".repeat(TEXT_NAME_WIDTH - name.length()) + body;
    }

    /** The text form, checking first so the problems are current. */
    public String renderText() {
        check();
        String state = healthy() ? "ok" : "ATTENTION";
        List<String> lines = new ArrayList<>();
        lines.add(site + " " + at.isoformat() + " " + state);
        sections().forEach((name, section) -> lines.add(textLine(name, section.line())));
        lines.add(textLine("archive", archived + " records kept"));
        if (!redacted.isEmpty()) {
            long total = redacted.values().stream().mapToLong(Long::longValue).sum();
            lines.add(textLine("redact", total + " removed across " + redacted.size() + " rules"));
        }
        for (String problem : problems) {
            lines.add("  ! " + problem);
        }
        return String.join("\n", lines);
    }
}
