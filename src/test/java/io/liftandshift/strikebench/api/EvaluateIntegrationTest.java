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

    private static String decisionBody() {
        return """
                {"contractVersion":1,"operation":"DECISION","basis":"DECISION_POLICY",
                 "context":{"symbol":"AAPL","marketLane":"DEMO","worldId":"demo","datasetId":"observed"},
                 "decision":{"symbol":"AAPL","thesis":"bullish","horizon":"month","riskMode":"balanced"}}
                """;
    }

    @Test void evaluateReturnsRankedCompetitionWithEveryDimension() throws Exception {
        HttpResponse<String> r = post("/api/evaluate", decisionBody());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = Json.MAPPER.readTree(r.body());
        assertThat(body.get("contractVersion").asInt()).isEqualTo(1);
        assertThat(body.get("operation").asText()).isEqualTo("DECISION");
        body = body.get("result");
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

        // Ranking: mechanical eligibility, then economic tier, then score within a tier.
        // A mechanically valid but economically unknown calendar may precede a known-adverse
        // lottery even when its numeric Decision score is lower.
        int previousTier = Integer.MAX_VALUE;
        double previousScoreInTier = Double.MAX_VALUE;
        for (JsonNode e : evals) {
            String verdict = e.path("economics").path("verdict").asText("UNAVAILABLE");
            int tier = switch (verdict) {
                case "FAVORABLE" -> 3;
                case "MIXED" -> 2;
                case "UNAVAILABLE" -> 1;
                default -> 0;
            };
            double s = e.get("score").get("riskAdjustedScore").asDouble();
            assertThat(tier).isLessThanOrEqualTo(previousTier);
            if (tier == previousTier) assertThat(s).isLessThanOrEqualTo(previousScoreInTier);
            else previousScoreInTier = Double.MAX_VALUE;
            previousTier = tier;
            previousScoreInTier = s;
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

        // Each winner is a full evaluation carrying its symbol; the list uses the same explicit
        // viable -> economic tier -> Decision score order as a single-symbol competition.
        int previousTier = Integer.MAX_VALUE;
        double previousScoreInTier = Double.MAX_VALUE;
        java.util.Set<String> symbols = new java.util.HashSet<>();
        for (JsonNode e : ranked) {
            symbols.add(e.get("spec").get("symbol").asText());
            String verdict = e.path("economics").path("verdict").asText("UNAVAILABLE");
            int tier = switch (verdict) {
                case "FAVORABLE" -> 3;
                case "MIXED" -> 2;
                case "UNAVAILABLE" -> 1;
                default -> 0;
            };
            double s = e.get("score").get("riskAdjustedScore").asDouble();
            assertThat(tier).isLessThanOrEqualTo(previousTier);
            if (tier == previousTier) assertThat(s).isLessThanOrEqualTo(previousScoreInTier);
            else previousScoreInTier = Double.MAX_VALUE;
            previousTier = tier;
            previousScoreInTier = s;
        }
        assertThat(symbols).isNotEmpty(); // at least one symbol's best idea surfaced
    }

    @Test void calibrationEndpointReports() throws Exception {
        post("/api/evaluate", decisionBody());
        HttpResponse<String> r = get("/api/calibration");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode rep = Json.MAPPER.readTree(r.body());
        assertThat(rep.has("resolved")).isTrue();     // a recommendation was recorded; no outcome yet
        assertThat(rep.has("reliability")).isTrue();
        assertThat(rep.has("note")).isTrue();
    }

    @Test void demoEvaluationsDoNotPolluteObservedHistory() throws Exception {
        post("/api/evaluate", decisionBody());
        JsonNode listed = Json.MAPPER.readTree(get("/api/evaluations").body()).get("evaluations");
        assertThat(listed.isArray()).isTrue();
        assertThat(listed).isEmpty();
    }

    @Test void oneVersionedContractOwnsPureOutcomeWorkAndPinsItsLane() throws Exception {
        String paths = """
                {"contractVersion":1,"operation":"PATHS","basis":"PARAMETRIC",
                 "context":{"symbol":"AAPL","marketLane":"DEMO","worldId":"demo","datasetId":"observed"},
                 "over":{"model":"GBM","shape":"CHOP","horizonDays":5,"stepsPerDay":2,
                         "driftAnnual":0,"volAnnual":0.25,"jumpsPerYear":0,"jumpMean":0,
                         "jumpVol":0,"tailNu":6,"seed":77,"paths":40}}
                """;
        HttpResponse<String> r = post("/api/evaluate", paths);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode envelope = Json.MAPPER.readTree(r.body());
        assertThat(envelope.get("operation").asText()).isEqualTo("PATHS");
        assertThat(envelope.get("basis").asText()).isEqualTo("PARAMETRIC");
        assertThat(envelope.at("/context/marketLane").asText()).isEqualTo("DEMO");
        assertThat(envelope.at("/result/bands").isArray()).isTrue();

        assertThat(post("/api/evaluate", paths.replace("\"DEMO\"", "\"OBSERVED\"")).statusCode())
                .isEqualTo(409);
        // No public compatibility burden: internal callers move atomically and old contracts die.
        assertThat(post("/api/evaluate",
                "{\"symbol\":\"AAPL\",\"thesis\":\"bullish\",\"horizon\":\"month\"}").statusCode())
                .isEqualTo(400);
        assertThat(post("/api/sim/scenario", "{}").statusCode()).isEqualTo(404);
        assertThat(post("/api/sim/strategy", "{}").statusCode()).isEqualTo(404);
        assertThat(post("/api/sim/compare", "{}").statusCode()).isEqualTo(404);
    }
}
