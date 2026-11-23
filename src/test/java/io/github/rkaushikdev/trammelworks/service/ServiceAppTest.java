package io.github.rkaushikdev.trammelworks.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.PosixPath;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.forecourt.ForecourtConfig;
import io.github.rkaushikdev.trammelworks.forecourt.Tagger;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceAppTest {

    static final NanoTime NOW = NanoTime.parse("2025-03-04T12:00:00Z");

    static final List<List<String>> CORPUS = List.of(
            List.of("payment-kernel", "11:00:00", "auth approved"),
            List.of("payment-kernel", "11:15:00", "auth declined"),
            List.of("pump-bridge", "11:30:00", "nozzle 1 lifted"));

    @TempDir
    Path tmp;

    Path archiveFile;
    SiteConfig config;

    @BeforeEach
    void setUp() {
        archiveFile = tmp.resolve("archive").resolve("records.sqlite3");
        config = SiteConfig.builder("US-TX-0417")
                .spool(SpoolSettings.at(PosixPath.asPosix(tmp.resolve("spool"))))
                .archive(ArchiveSettings.at(PosixPath.asPosix(archiveFile)))
                .build();
    }

    void fill() {
        try (ArchiveDb connection = Schema.openArchive(archiveFile)) {
            Repository repo = new Repository(connection);
            for (List<String> line : CORPUS) {
                repo.add(
                        new LogRecord(
                                "US-TX-0417",
                                line.get(0),
                                Stream.STDOUT,
                                NanoTime.parse("2025-03-04T" + line.get(1) + "Z"),
                                line.get(2)),
                        NOW);
            }
        }
    }

    static Service service(SiteConfig config) {
        return new Service(config, NOW, "");
    }

    static Service service(SiteConfig config, String token) {
        return new Service(config, NOW, token);
    }

    ServiceResponse get(String target) {
        return service(config).handle(ServiceRequest.of(target));
    }

    static ServiceRequest withAuthorization(String target, String value) {
        return ServiceRequest.of(target, Map.of("Authorization", value), "GET");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> records(ServiceResponse found) {
        return (List<Map<String, Object>>) found.payload().get("records");
    }

    @Test
    void aTargetIsSplitIntoPathAndQuery() {
        ServiceRequest request = ServiceRequest.of("/records?filter=unit+%3D+a&limit=5");
        assertThat(request.path()).isEqualTo("/records");
        assertThat(request.one("limit")).isEqualTo("5");
        assertThat(request.one("filter")).isEqualTo("unit = a");
    }

    @Test
    void aTrailingSlashIsIgnored() {
        assertThat(ServiceRequest.of("/health/").path()).isEqualTo("/health");
    }

    @Test
    void theRootPathSurvivesTheTrim() {
        assertThat(ServiceRequest.of("/").path()).isEqualTo("/");
    }

    @Test
    void aMissingParameterFallsBack() {
        assertThat(ServiceRequest.of("/records").one("limit", "7")).isEqualTo("7");
    }

    @Test
    void aBearerTokenIsRead() {
        assertThat(withAuthorization("/", "Bearer abc").token()).isEqualTo("abc");
    }

    @Test
    void aHeaderNameIsMatchedWithoutCase() {
        assertThat(ServiceRequest.of("/", Map.of("AUTHORIZATION", "Bearer abc"), "GET")
                        .token())
                .isEqualTo("abc");
    }

    @Test
    void aNonBearerHeaderGivesNoToken() {
        assertThat(withAuthorization("/", "Basic abc").token()).isEmpty();
    }

    @Test
    void healthReportsTheSite() {
        ServiceResponse found = get("/health");
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.payload()).containsEntry("site", "US-TX-0417").containsEntry("status", "ok");
    }

    @Test
    void theRootIsTheHealthRoute() {
        assertThat(get("/").payload()).containsEntry("status", "ok");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnknownRouteIsA404() {
        ServiceResponse found = get("/admin");
        assertThat(found.status()).isEqualTo(404);
        assertThat((List<Object>) found.payload().get("routes")).contains("/health");
    }

    @Test
    void aWriteMethodIsRefused() {
        ServiceResponse found = service(config).handle(ServiceRequest.of("/health", Map.of(), "POST"));
        assertThat(found.status()).isEqualTo(405);
        assertThat((String) found.payload().get("error")).contains("only reads");
    }

    @Test
    void aTokenIsRequiredWhenOneIsSet() {
        assertThat(service(config, "secret")
                        .handle(ServiceRequest.of("/health"))
                        .status())
                .isEqualTo(401);
    }

    @Test
    void theRightTokenIsAccepted() {
        assertThat(service(config, "secret")
                        .handle(withAuthorization("/health", "Bearer secret"))
                        .status())
                .isEqualTo(200);
    }

    @Test
    void aWrongTokenIsRefused() {
        assertThat(service(config, "secret")
                        .handle(withAuthorization("/health", "Bearer wrong"))
                        .status())
                .isEqualTo(401);
    }

    @Test
    void noTokenConfiguredMeansNoCheck() {
        assertThat(get("/health").status()).isEqualTo(200);
    }

    @Test
    void theSummaryRouteReports() {
        fill();
        ServiceResponse found = get("/summary");
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.payload()).containsEntry("site", "US-TX-0417").containsEntry("archived", 3L);
    }

    @Test
    void theSummaryReadsTheForecourtOutOfTheArchive() {
        try (ArchiveDb connection = Schema.openArchive(archiveFile)) {
            new Repository(connection)
                    .add(
                            new LogRecord(
                                            "US-TX-0417",
                                            "tank-gauge",
                                            Stream.STDOUT,
                                            NanoTime.parse("2025-02-01T09:00:00Z"),
                                            "T 1:LEAK ALARM")
                                    .withLabels(Map.of("tank", "1", "alarm", "leak", "alarm_state", "active")),
                            NOW);
        }
        Map<String, Object> payload = get("/summary").payload();
        assertThat(payload.get("forecourt"))
                .isEqualTo(Map.of(
                        "sales", 0L,
                        "declines", 0L,
                        "host_unavailable", 0L,
                        "unauthorised", 0L,
                        "alarms_standing", 1L,
                        "environmental_standing", 1L,
                        "saf_depth", 0L,
                        "gauge_quiet_minutes", 1440L));
        assertThat(payload.get("problems").toString()).contains("1 environmental tank alarms are standing");
    }

    void fillForecourt(List<List<String>> lines) {
        Tagger tagger = new Tagger(new ForecourtConfig());
        try (ArchiveDb connection = Schema.openArchive(archiveFile)) {
            Repository repo = new Repository(connection);
            for (List<String> line : lines) {
                repo.add(
                        tagger.tag(new LogRecord(
                                "US-TX-0417", line.get(0), Stream.STDOUT, NanoTime.parse(line.get(1)), line.get(2))),
                        NOW);
            }
        }
    }

    @Test
    void theForecourtRouteShowsWhatTheKioskShows() {
        fillForecourt(List.of(
                List.of("pump-bridge", "2025-03-04T10:00:00Z", "FP10 IDLE"),
                List.of("pump-bridge", "2025-03-04T10:00:00Z", "FP2 AUTHORISED"),
                List.of("pump-bridge", "2025-03-04T10:01:00Z", "FP2 FUELLING"),
                List.of("pump-bridge", "2025-03-04T10:02:00Z", "FP2 COMPLETED 20 litres"),
                List.of("price-sign", "2025-03-04T08:00:00Z", "price diesel 1549"),
                List.of("pump-bridge", "2025-03-04T08:00:10Z", "FP02 price diesel 1.529"),
                List.of("tank-gauge", "2025-02-20T03:00:00Z", "T 3:LEAK ALARM"),
                List.of("tank-gauge", "2025-03-04T09:00:00Z", "T 1:HIGH WATER ALARM"),
                List.of("tank-gauge", "2025-03-04T09:30:00Z", "T 1:HIGH WATER ALARM CLEAR")));
        ServiceResponse found = get("/forecourt");
        assertThat(found.status()).isEqualTo(200);
        Map<String, Object> payload = found.payload();
        assertThat(payload).containsEntry("site", "US-TX-0417").containsEntry("at", NOW.isoformat());
        assertThat(payload.get("pumps").toString()).isEqualTo("{2=completed, 10=idle}");
        assertThat(payload.get("alarms"))
                .isEqualTo(List.of(Map.of(
                        "tank", "3",
                        "alarm", "leak",
                        "severity", "environmental",
                        "raised_at", "2025-02-20T03:00:00.000000000Z")));
        assertThat(payload.get("prices"))
                .isEqualTo(List.of(Map.of(
                        "pump", "2",
                        "grade", "diesel",
                        "sign", "1.549",
                        "pump_price", "1.529",
                        "since", "2025-03-04T08:00:10.000000000Z")));
        assertThat(((Map<?, ?>) payload.get("day")).get("sales")).isEqualTo(1L);
    }

    @Test
    void theForecourtRouteNeedsAnArchive() {
        assertThat(get("/forecourt").status()).isEqualTo(404);
    }

    @Test
    void theSummaryCarriesTheProblems() {
        // A site with no log root is following nothing, which the summary flags.
        fill();
        assertThat((List<?>) get("/summary").payload().get("problems")).isNotEmpty();
    }

    @Test
    void recordsComeBackAsJson() {
        fill();
        ServiceResponse found = get("/records");
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.payload()).containsEntry("count", 3);
        assertThat(records(found).get(0)).containsEntry("message", "auth approved");
    }

    @Test
    void recordsCanBeFiltered() {
        fill();
        assertThat(get("/records?filter=unit+%3D+pump-bridge").payload()).containsEntry("count", 1);
    }

    @Test
    void aBrokenFilterIsA400() {
        fill();
        ServiceResponse found = get("/records?filter=unti+%3D+a");
        assertThat(found.status()).isEqualTo(400);
        assertThat(found.payload()).containsEntry("code", "stanchion.query");
    }

    @Test
    void theLimitIsHonoured() {
        fill();
        assertThat(get("/records?limit=1").payload()).containsEntry("count", 1);
    }

    @Test
    void theLimitIsCapped() {
        fill();
        ServiceResponse found = get("/records?limit=" + Service.MAX_LIMIT * 10);
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.payload()).containsEntry("count", 3);
    }

    @Test
    void aLimitOfZeroIsRefused() {
        fill();
        assertThat(get("/records?limit=0").status()).isEqualTo(400);
    }

    @Test
    void aNonNumericLimitIsRefused() {
        fill();
        ServiceResponse found = get("/records?limit=lots");
        assertThat(found.status()).isEqualTo(400);
        assertThat((String) found.payload().get("error")).contains("not a number");
    }

    @Test
    void recordsBeforeTheArchiveExistsAreA404() {
        assertThat(get("/records").status()).isEqualTo(404);
    }

    @Test
    void aSiteWithNoArchiveGetsA409() {
        SiteConfig without = SiteConfig.builder("US-TX-0417")
                .spool(SpoolSettings.at(PosixPath.asPosix(tmp.resolve("spool"))))
                .archive(ArchiveSettings.builder().enabled(false).build())
                .build();
        assertThat(service(without).handle(ServiceRequest.of("/records")).status())
                .isEqualTo(409);
    }

    @Test
    void theUnitsRouteListsUnits() {
        fill();
        assertThat(get("/units").payload().get("units")).isEqualTo(List.of("payment-kernel", "pump-bridge"));
    }

    @Test
    void theUnitsRouteIsEmptyWithoutAnArchive() {
        assertThat(get("/units").payload().get("units")).isEqualTo(List.of());
    }

    @Test
    void everyResponseIsJson() {
        fill();
        for (String target : List.of("/health", "/summary", "/records", "/units", "/nope")) {
            String body = new String(get(target).body(), StandardCharsets.UTF_8);
            assertThat(Json.loads(body)).isInstanceOf(Map.class);
        }
    }

    @Test
    void anErrorResponseIsJsonToo() {
        assertThat(Json.loadsObject(new String(get("/admin").body(), StandardCharsets.UTF_8)))
                .containsKey("error");
    }

    @Test
    void aMalformedEscapeInTheQueryStaysAsWritten() {
        ServiceRequest request = ServiceRequest.of("/records?filter=100%zz&empty=&bare#frag");
        assertThat(request.one("filter")).isEqualTo("100%zz");
        assertThat(request.query()).doesNotContainKeys("empty", "bare");
    }
}
