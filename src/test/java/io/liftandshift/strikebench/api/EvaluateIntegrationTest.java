package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** POST /api/evaluate returns a ranked competition of full StrategyEvaluations and persists them. */
class EvaluateIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));

    private static ApiServer server;
    private static Javalin app;
    private static HttpClient http;
    private static String base;

    @BeforeAll
    static void start() {
        Map<String, String> conf = new HashMap<>(TestDb.freshConfig());
        conf.put("FIXTURES_ONLY", "true");
        server = ApiServer.create(new AppConfig(conf), CLOCK);
        app = server.start(0);
        base = "http://localhost:" + app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() { if (server != null) server.stop(); }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void evaluateReturnsRankedCompetitionWithEveryDimension() throws Exception {
        HttpResponse<String> r = post("/api/evaluate",
                "{\"symbol\":\"AAPL\",\"thesis\":\"bullish\",\"horizon\":\"month\",\"riskMode\":\"balanced\"}");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = Json.MAPPER.readTree(r.body());
        JsonNode evals = body.get("evaluations");
        assertThat(evals.isArray()).isTrue();
        assertThat(evals).isNotEmpty();

        JsonNode top = evals.get(0);
        // Every producer dimension is present on each evaluation.
        assertThat(top.hasNonNull("capital")).isTrue();
        assertThat(top.get("capital").hasNonNull("economicCents")).isTrue();
        assertThat(top.get("risk").get("scenarios").isArray()).isTrue();
        assertThat(top.get("risk").get("scenarios")).hasSize(7);
        assertThat(top.get("evidence").hasNonNull("rollup")).isTrue();
        assertThat(top.get("score").hasNonNull("riskAdjustedScore")).isTrue();
        assertThat(top.get("score").get("components")).isNotEmpty();
        assertThat(top.get("management").get("rules")).isNotEmpty();
        assertThat(top.get("explanation").get("assumptions")).isNotEmpty();

        // Fixture data is honestly labeled, never observed.
        assertThat(top.get("evidence").get("rollup").asText()).isEqualTo("DEMO_FIXTURE");

        // Ranking: risk-adjusted score is non-increasing down the list.
        double prev = Double.MAX_VALUE;
        for (JsonNode e : evals) {
            double s = e.get("score").get("riskAdjustedScore").asDouble();
            assertThat(s).isLessThanOrEqualTo(prev);
            prev = s;
        }
    }

    @Test void opportunitiesScanRanksAcrossTheUniverse() throws Exception {
        HttpResponse<String> r = post("/api/opportunities",
                "{\"thesis\":\"neutral\",\"horizon\":\"month\",\"riskMode\":\"balanced\",\"topN\":5}");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = Json.MAPPER.readTree(r.body());
        assertThat(body.get("scanned").asInt()).isGreaterThan(0); // scanned the active demo universe
        JsonNode ranked = body.get("ranked");
        assertThat(ranked.isArray()).isTrue();
        assertThat(ranked).isNotEmpty();

        // Each winner is a full evaluation carrying its symbol; the list ranks cross-symbol.
        double prev = Double.MAX_VALUE;
        java.util.Set<String> symbols = new java.util.HashSet<>();
        for (JsonNode e : ranked) {
            symbols.add(e.get("spec").get("symbol").asText());
            double s = e.get("score").get("riskAdjustedScore").asDouble();
            assertThat(s).isLessThanOrEqualTo(prev);
            prev = s;
        }
        assertThat(symbols).isNotEmpty(); // at least one symbol's best idea surfaced
    }

    @Test void calibrationEndpointReports() throws Exception {
        post("/api/evaluate", "{\"symbol\":\"AAPL\",\"thesis\":\"bullish\",\"horizon\":\"month\",\"riskMode\":\"balanced\"}");
        HttpResponse<String> r = get("/api/calibration");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode rep = Json.MAPPER.readTree(r.body());
        assertThat(rep.has("resolved")).isTrue();     // a recommendation was recorded; no outcome yet
        assertThat(rep.has("reliability")).isTrue();
        assertThat(rep.has("note")).isTrue();
    }

    @Test void evaluationsArePersistedAndListable() throws Exception {
        post("/api/evaluate", "{\"symbol\":\"AAPL\",\"thesis\":\"bullish\",\"horizon\":\"month\",\"riskMode\":\"balanced\"}");
        JsonNode listed = Json.MAPPER.readTree(get("/api/evaluations").body()).get("evaluations");
        assertThat(listed.isArray()).isTrue();
        assertThat(listed).isNotEmpty();
        assertThat(listed.get(0).hasNonNull("symbol")).isTrue();
        assertThat(listed.get(0).get("symbol").asText()).isEqualTo("AAPL");
    }
}
