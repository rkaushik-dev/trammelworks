package io.github.rkaushikdev.trammelworks.service;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Site;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.StanchionError;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import io.github.rkaushikdev.trammelworks.forecourt.ForecourtDay;
import io.github.rkaushikdev.trammelworks.query.Cursor;
import io.github.rkaushikdev.trammelworks.query.Execute;
import io.github.rkaushikdev.trammelworks.query.Parser;
import io.github.rkaushikdev.trammelworks.query.Query;
import io.github.rkaushikdev.trammelworks.query.SearchResult;
import io.github.rkaushikdev.trammelworks.report.ForecourtSummary;
import io.github.rkaushikdev.trammelworks.report.Summary;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The routes for one site, as a pure function of a request.
 *
 * <p>No sockets here, and that is the point: the interesting behaviour is all in the deciding, what a
 * filter may ask for, what a missing token gets back, what happens when the archive is not there.
 * The service is read only and not a general API. It serves the kiosk behind the counter, which
 * shows the site's own status, and the estate poller, which collects the nightly summary from sites
 * whose uplink cannot push. Neither needs to write, so nothing here can.
 */
public final class Service {

    /** The most records one page may hold. */
    public static final int MAX_LIMIT = 500;

    /** The page size when nobody asks. */
    public static final int DEFAULT_LIMIT = 100;

    /** Every route, by path. */
    public static final Map<String, BiFunction<Service, ServiceRequest, ServiceResponse>> ROUTES = routes();

    private final SiteConfig config;
    private final NanoTime now;
    private final String token;

    /** The routes for {@code config}, answering as of {@code now}; an empty token means no check. */
    public Service(SiteConfig config, NanoTime now, String token) {
        this.config = config;
        this.now = now;
        this.token = token;
    }

    private static Map<String, BiFunction<Service, ServiceRequest, ServiceResponse>> routes() {
        Map<String, BiFunction<Service, ServiceRequest, ServiceResponse>> out = new LinkedHashMap<>();
        out.put("/", Service::health);
        out.put("/health", Service::health);
        out.put("/summary", Service::summary);
        out.put("/records", Service::records);
        out.put("/units", Service::units);
        out.put("/metrics", Service::metrics);
        out.put("/forecourt", Service::forecourt);
        return java.util.Collections.unmodifiableMap(out);
    }

    /** Route one request, refusing anything that is not a read. */
    public ServiceResponse handle(ServiceRequest request) {
        if (!request.method().equals("GET") && !request.method().equals("HEAD")) {
            return ServiceResponse.problem(405, "this service only reads");
        }
        if (!token.isEmpty() && !request.token().equals(token)) {
            return ServiceResponse.problem(401, "a bearer token is required");
        }
        BiFunction<Service, ServiceRequest, ServiceResponse> route = ROUTES.get(request.path());
        if (route == null) {
            List<String> names = ROUTES.keySet().stream().sorted().toList();
            return ServiceResponse.problem(404, "no such route", "routes", names);
        }
        try {
            return route.apply(this, request);
        } catch (StanchionError exc) {
            return ServiceResponse.problem(
                    400,
                    exc.message(),
                    "code",
                    exc.code(),
                    "detail",
                    exc.detail().orElse(""));
        }
    }

    /** That the service is up, for which site, and when it thinks it is. */
    public ServiceResponse health(ServiceRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site", config.site());
        payload.put("status", "ok");
        payload.put("at", now.isoformat());
        return new ServiceResponse(200, payload);
    }

    /** The nightly summary, built from what is on disk. */
    public ServiceResponse summary(ServiceRequest request) {
        Summary built = Summary.fromDisk(config, now, exc -> {
            throw exc;
        });
        return new ServiceResponse(200, built.asDict());
    }

    /**
     * Answer from the archive, opened read only for as long as {@code work} runs. A site that keeps
     * no archive, or has not written one yet, gets the problem that says which.
     */
    private ServiceResponse reading(Function<ArchiveDb, Map<String, Object>> work) {
        if (!config.archive().enabled()) {
            return ServiceResponse.problem(409, "this site keeps no archive");
        }
        if (!Files.exists(config.archive().file())) {
            return ServiceResponse.problem(404, "the archive has not been created yet");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site", config.site());
        try (ArchiveDb connection = Schema.openArchive(config.archive().file(), true)) {
            payload.putAll(work.apply(connection));
        }
        return new ServiceResponse(200, payload);
    }

    /** One page of archived records matching a filter. */
    public ServiceResponse records(ServiceRequest request) {
        int limit = limit(request);
        Query query = Parser.parseQuery(request.one("filter"), now);
        if (query.limit().isEmpty() || query.limit().getAsInt() > limit) {
            query = new Query(query.where(), OptionalInt.of(limit));
        }
        String after = request.one("after");
        Optional<Cursor> cursor = after.isEmpty() ? Optional.empty() : Optional.of(Cursor.parseCursor(after));
        Query asked = query;
        return reading(connection -> {
            SearchResult found = Execute.search(connection, asked, Execute.DEFAULT_SCAN_CEILING, cursor);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("count", found.count());
            payload.put("truncated", found.truncated());
            payload.put("records", found.records().stream().map(Service::record).toList());
            // Only when there might be more. A caller that follows the cursor while one is offered
            // and stops when it is not needs no count arithmetic to know when it has the lot.
            found.nextCursor().ifPresent(next -> payload.put("next", next.encode()));
            return payload;
        });
    }

    /**
     * What the kiosk behind the counter shows: the state each pump was last in, the tank alarms
     * still standing, any pump charging a different price from the sign, and the day's counts.
     */
    public ServiceResponse forecourt(ServiceRequest request) {
        return reading(connection -> {
            ForecourtDay read = ForecourtDay.read(connection, now);
            Map<String, Object> pumps = new TreeMap<>(Comparator.comparingInt(Integer::parseInt));
            read.pumps().states().forEach((pump, state) -> pumps.put(pump, state.value()));
            List<Map<String, Object>> alarms = read.alarmLog().open().stream()
                    .map(span -> Maps.of(
                            "tank", span.tank(),
                            "alarm", span.alarm().value(),
                            "severity", span.alarm().severity().value(),
                            "raised_at", span.raisedAt().isoformat()))
                    .toList();
            List<Map<String, Object>> prices = read.prices().mismatches(now).stream()
                    .filter(item -> item.until().isEmpty())
                    .map(item -> Maps.of(
                            "pump", item.pump(),
                            "grade", item.grade().value(),
                            "sign", item.sign().toPlainString(),
                            "pump_price", item.pumpPrice().toPlainString(),
                            "since", item.since().isoformat()))
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("at", now.isoformat());
            payload.put("pumps", new LinkedHashMap<>(pumps));
            payload.put("alarms", alarms);
            payload.put("prices", prices);
            payload.put("day", Summary.sectionDict(ForecourtSummary.of(read, config.forecourt(), now)));
            return payload;
        });
    }

    /**
     * Every gauge this site can answer for, read off its own disk: JSON like every other route,
     * with the plain text form beside it under {@code text} for the poller that parses with awk.
     */
    public ServiceResponse metrics(ServiceRequest request) {
        Gauges found = Metrics.gather(config, Site.stateDirectory(config.spool().directory()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site", config.site());
        payload.put("at", now.isoformat());
        payload.put("gauges", found.asDict());
        payload.put("unreadable", List.copyOf(found.missing()));
        payload.put("text", found.render());
        return new ServiceResponse(200, payload);
    }

    /** The units the archive has heard from, and how many records per class. */
    public ServiceResponse units(ServiceRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (!config.archive().enabled() || !Files.exists(config.archive().file())) {
            payload.put("units", List.of());
            return new ServiceResponse(200, payload);
        }
        try (ArchiveDb connection = Schema.openArchive(config.archive().file(), true)) {
            Repository repo = new Repository(connection);
            payload.put("units", List.copyOf(repo.units()));
            payload.put("counts", new TreeMap<>(repo.countsByClass()));
        }
        return new ServiceResponse(200, payload);
    }

    private static int limit(ServiceRequest request) {
        String raw = request.one("limit", Integer.toString(DEFAULT_LIMIT));
        OptionalLong wanted = Coerce.integral(raw);
        if (wanted.isEmpty()) {
            throw new StanchionError("limit is not a number", raw);
        }
        if (wanted.getAsLong() < 1) {
            throw new StanchionError("limit must be at least one", raw);
        }
        return (int) Math.min(wanted.getAsLong(), MAX_LIMIT);
    }

    private static Map<String, Object> record(StoredRecord item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("observed_at", item.observedAt().isoformat());
        payload.put("unit", item.unit());
        payload.put("class", item.klass());
        payload.put("stream", item.stream().value());
        payload.put("confidence", item.confidence());
        payload.put("message", item.message());
        payload.put("labels", new LinkedHashMap<>(item.labels()));
        item.correctedAt().ifPresent(corrected -> payload.put("corrected_at", corrected.isoformat()));
        return payload;
    }
}
