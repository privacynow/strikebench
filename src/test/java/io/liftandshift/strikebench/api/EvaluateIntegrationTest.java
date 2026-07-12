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
                {"operation":"DECISION","basis":"DECISION_POLICY",
                 "context":{"symbol":"AAPL","marketLane":"DEMO","worldId":"demo","datasetId":"observed"},
                 "decision":{"symbol":"AAPL","thesis":"bullish","horizon":"month","riskMode":"balanced"}}
                """;
    }

    @Test void evaluateReturnsRankedCompetitionWithEveryDimension() throws Exception {
        HttpResponse<String> r = post("/api/evaluate", decisionBody());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = Json.MAPPER.readTree(r.body());
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
        assertThat(top.hasNonNull("decisionScore")).isTrue();
        assertThat(top.get("score").get("components")).isNotEmpty();
        assertThat(top.get("management").get("rules")).isNotEmpty();
        assertThat(top.get("explanation").get("assumptions")).isNotEmpty();

        // Fixture data is honestly labeled, never observed.
        assertThat(top.get("evidence").get("rollup").asText()).isEqualTo("DEMO_FIXTURE");

        // The served Decision score is the complete monotonic ordering: eligibility, economic
        // tier, then risk/evidence quality within that tier.
        double previousDecisionScore = Double.MAX_VALUE;
        for (JsonNode e : evals) {
            double decisionScore = e.get("decisionScore").asDouble();
            assertThat(decisionScore).isLessThanOrEqualTo(previousDecisionScore);
            previousDecisionScore = decisionScore;
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
        // monotonic Decision-score order as a single-symbol competition.
        double previousDecisionScore = Double.MAX_VALUE;
        java.util.Set<String> symbols = new java.util.HashSet<>();
        for (JsonNode e : ranked) {
            symbols.add(e.get("spec").get("symbol").asText());
            double decisionScore = e.get("decisionScore").asDouble();
            assertThat(decisionScore).isLessThanOrEqualTo(previousDecisionScore);
            previousDecisionScore = decisionScore;
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

    @Test void onePrivateContractOwnsPureOutcomeWorkAndPinsItsLane() throws Exception {
        String paths = """
                {"operation":"PATHS","basis":"PARAMETRIC",
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
        assertThat(envelope.at("/result/pathModelVersion").asText()).isEqualTo("paths-2");

        assertThat(post("/api/evaluate", paths.replace("\"DEMO\"", "\"OBSERVED\"")).statusCode())
                .isEqualTo(409);
        // No public compatibility burden: internal callers move atomically and old contracts die.
        assertThat(post("/api/evaluate",
                "{\"symbol\":\"AAPL\",\"thesis\":\"bullish\",\"horizon\":\"month\"}").statusCode())
                .isEqualTo(400);
        assertThat(post("/api/sim/scenario", "{}").statusCode()).isEqualTo(404);
        assertThat(post("/api/sim/strategy", "{}").statusCode()).isEqualTo(404);
        assertThat(post("/api/sim/compare", "{}").statusCode()).isEqualTo(404);
        assertThat(post("/api/trade/replicate", "{}").statusCode()).isEqualTo(404);
    }

    @Test void riskNeutralBasisEvaluatesExactListedPackagesThroughTheSameContract() throws Exception {
        String body = """
                {"operation":"POSITION","basis":"RISK_NEUTRAL",
                 "context":{"symbol":"AAPL","marketLane":"DEMO","worldId":"demo","datasetId":"observed"},
                 "position":{"key":"PUT_SPREAD","qty":1,"legs":[
                   {"action":"SELL","type":"PUT","strike":255,"expiration":"2026-08-14","ratio":1},
                   {"action":"BUY","type":"PUT","strike":250,"expiration":"2026-08-14","ratio":1}]}}
                """;
        HttpResponse<String> response = post("/api/evaluate", body);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode envelope = Json.MAPPER.readTree(response.body());
        assertThat(envelope.get("basis").asText()).isEqualTo("RISK_NEUTRAL");
        assertThat(envelope.at("/result/probabilityMap/pAnyProfit").asDouble()).isBetween(0.0, 1.0);
        assertThat(envelope.at("/result/marketIv").asDouble()).isPositive();
        assertThat(envelope.at("/result/time/basis").asText()).contains("chain-IV convention");
        assertThat(envelope.at("/result/source").asText()).containsIgnoringCase("fixture");
        assertThat(envelope.at("/result/evSensitivity").size()).isEqualTo(3);

        String compare = body.replace("\"operation\":\"POSITION\"", "\"operation\":\"COMPARE\"")
                .replace("\"position\":{", "\"positions\":[{")
                .replace("]}}\n", "]}]}\n");
        HttpResponse<String> compared = post("/api/evaluate", compare);
        assertThat(compared.statusCode()).isEqualTo(200);
        JsonNode comparison = Json.MAPPER.readTree(compared.body()).get("result");
        assertThat(comparison.get("results")).hasSize(1);
        assertThat(comparison.get("refused")).isEmpty();
        assertThat(comparison.get("fairness").asText()).contains("one captured");
    }

    @Test void outcomeExpiryUsesTheSharedTradingCalendar() {
        assertThat(ApiServer.outcomeExpiryDay(java.time.LocalDate.parse("2026-07-02"),
                java.time.LocalDate.parse("2026-07-06"))).isEqualTo(1); // July 3 observed holiday + weekend
        assertThat(ApiServer.outcomeExpiryDay(java.time.LocalDate.parse("2026-07-08"),
                java.time.LocalDate.parse("2026-07-13"))).isEqualTo(3); // Thu, Fri, Mon
    }

    @Test void ideasDecisionAndTicketShareOneRiskNeutralCalculation() throws Exception {
        JsonNode envelope = Json.MAPPER.readTree(post("/api/evaluate", decisionBody()).body());
        JsonNode evaluation = null;
        for (JsonNode item : envelope.at("/result/evaluations")) {
            JsonNode candidate = item.get("candidate");
            if (!candidate.path("usesHeldShares").asBoolean(false)
                    && item.at("/risk/expectedValueCents").isNumber()
                    && item.at("/risk/pop").isNumber()) {
                evaluation = item;
                break;
            }
        }
        assertThat(evaluation).as("a single-expiration candidate is available").isNotNull();
        JsonNode candidate = evaluation.get("candidate");

        var ticket = Json.MAPPER.createObjectNode();
        ticket.put("symbol", "AAPL");
        ticket.put("strategy", candidate.get("strategy").asText());
        ticket.put("qty", candidate.get("qty").asInt());
        ticket.set("legs", candidate.get("legs"));
        ticket.put("thesis", "bullish");
        ticket.put("horizon", "month");
        ticket.put("riskMode", "balanced");
        ticket.put("intent", candidate.get("intent").asText());
        ticket.put("source", "RECOMMENDATION");

        HttpResponse<String> previewResponse = post("/api/trades/preview", ticket.toString());
        assertThat(previewResponse.statusCode()).isEqualTo(200);
        JsonNode preview = Json.MAPPER.readTree(previewResponse.body()).get("preview");

        assertThat(preview.get("popEntry").asDouble())
                .as("candidate=%s preview=%s", candidate, preview)
                .isCloseTo(evaluation.at("/risk/pop").asDouble(), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(preview.get("expectedValueCents").asLong())
                .isEqualTo(evaluation.at("/risk/expectedValueCents").asLong());
        if (candidate.path("assignmentProb").isNumber()) {
            assertThat(preview.get("assignmentProb").asDouble())
                    .isCloseTo(candidate.get("assignmentProb").asDouble(), org.assertj.core.data.Offset.offset(1e-9));
        }
        assertThat(preview.at("/analytics/probabilityMap/basis").asText())
                .containsIgnoringCase("risk-neutral").containsIgnoringCase("q=0");
        assertThat(evaluation.at("/explanation/assumptions/0").asText())
                .containsIgnoringCase("present-valued").containsIgnoringCase("q=0");
    }
}
