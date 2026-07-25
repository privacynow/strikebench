package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.liftandshift.strikebench.config.AppConfig;
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
import java.time.ZoneOffset;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit §8.2: a Scout row must open the EXACT package it showed.
 *
 * <p>The whole journey is exercised end to end — scan, row identity, Plan creation, adoption —
 * because the defect it closes lives precisely in the seam: the row carried an exact iron condor
 * and the workspace opened a freshly recomputed field that need not contain it.</p>
 */
class ScoutRowAdoptionApiTest {
    private static ApiServer server;
    private static HttpClient http;
    private static String base;

    @BeforeAll static void start() {
        var config = new HashMap<>(io.liftandshift.strikebench.support.TestDb.freshConfig());
        config.put("FIXTURES_ONLY", "true");
        server = ApiServer.create(new AppConfig(config),
                Clock.fixed(Instant.parse("2026-07-12T16:00:00Z"), ZoneOffset.UTC));
        Javalin app = server.start(0);
        base = "http://localhost:" + app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterAll static void stop() { if (server != null) server.stop(); }

    @Test void aScannedRowIsAdoptedIntoAPlanAsTheByteIdenticalPackageItShowed() throws Exception {
        JsonNode scan = json(post("/api/research/scout", """
                {"universe":["AAPL"],"horizons":["30d"],"maxPicks":1,"riskMode":"balanced",
                 "intents":["INCOME"],"thesisOverride":"neutral"}
                """));
        JsonNode row = scan.at("/frontier/decisionRanking/0");
        String evaluationId = row.path("evaluationId").asText();
        assertThat(evaluationId).isNotBlank();
        assertThat(row.at("/identity/key").asText()).isNotBlank();
        assertThat(row.at("/identity/symbol").asText()).isEqualTo("AAPL");
        assertThat(row.at("/identity/goal").asText()).isEqualTo("INCOME");
        assertThat(row.at("/identity/horizonSessions").asInt()).isEqualTo(30);
        assertThat(row.at("/identity/expiration").asText()).isNotBlank();

        JsonNode shownPackage = scannedPackage(scan, evaluationId);
        assertThat(shownPackage.path("legs")).isNotEmpty();

        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"scout-adopt-1","symbol":"AAPL","intent":"INCOME",
                 "title":"Adopted scan row","thesis":"neutral","horizonDays":30,"riskMode":"balanced"}
                """));
        String planId = plan.get("id").asText();

        HttpResponse<String> adoptedResponse = post("/api/plans/" + planId + "/strategy/adopt",
                "{\"expectedVersion\":" + plan.get("version").asLong()
                        + ",\"evaluationId\":\"" + evaluationId + "\"}");
        assertThat(adoptedResponse.statusCode()).as(adoptedResponse.body()).isEqualTo(200);
        JsonNode adopted = Json.parse(adoptedResponse.body());
        assertThat(adopted.path("evaluationId").asText()).isEqualTo(evaluationId);
        assertThat(adopted.at("/identity/key").asText()).isEqualTo(row.at("/identity/key").asText());

        JsonNode adoptedCandidate = adopted.at("/strategy/result/candidate");
        assertThat(adoptedCandidate.path("selected").asBoolean()).isTrue();
        assertThat(adoptedCandidate.path("sourceEvaluationId").asText()).isEqualTo(evaluationId);
        assertSamePackage(shownPackage, adoptedCandidate);

        // The same exactness has to survive a reload: a restored Plan must still be the clicked row.
        JsonNode restored = json(get("/api/plans/" + planId + "/strategy/latest")).at("/selected");
        assertThat(restored.path("sourceEvaluationId").asText()).isEqualTo(evaluationId);
        assertThat(restored.path("sourceKind").asText()).isEqualTo("SCOUT");
        assertSamePackage(shownPackage, restored);
    }

    @Test void aPackageEvaluatedUnderAnotherBriefIsRefusedByName() throws Exception {
        JsonNode scan = json(post("/api/research/scout", """
                {"universe":["AAPL"],"horizons":["30d"],"maxPicks":1,"riskMode":"balanced",
                 "intents":["INCOME"],"thesisOverride":"neutral"}
                """));
        String evaluationId = scan.at("/frontier/decisionRanking/0/evaluationId").asText();

        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"scout-adopt-mismatch","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"Different brief","thesis":"bullish","horizonDays":30,"riskMode":"balanced"}
                """));
        HttpResponse<String> refused = post("/api/plans/" + plan.get("id").asText() + "/strategy/adopt",
                "{\"expectedVersion\":" + plan.get("version").asLong()
                        + ",\"evaluationId\":\"" + evaluationId + "\"}");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("goal", "direction")
                .contains("evaluated under a different");
    }

    @Test void anUnknownScannedPackageIsNotSilentlyReplacedByAFreshField() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"scout-adopt-missing","symbol":"AAPL","intent":"INCOME",
                 "title":"Missing row","thesis":"neutral","horizonDays":30,"riskMode":"balanced"}
                """));
        HttpResponse<String> missing = post("/api/plans/" + plan.get("id").asText() + "/strategy/adopt",
                "{\"expectedVersion\":" + plan.get("version").asLong()
                        + ",\"evaluationId\":\"seval_does_not_exist\"}");
        assertThat(missing.statusCode()).isEqualTo(422);
        assertThat(missing.body()).contains("no longer available in this market",
                "no substitute package was selected");
        assertThat(json(get("/api/plans/" + plan.get("id").asText() + "/strategy/latest"))
                .hasNonNull("selected"))
                .as("a refused adoption leaves the Plan without an invented structure").isFalse();
    }

    /** The exact package the scan row carried, found by the row's own evaluation identity. */
    private static JsonNode scannedPackage(JsonNode scan, String evaluationId) {
        for (JsonNode pick : scan.path("picks")) {
            for (JsonNode horizon : pick.path("horizons")) {
                for (JsonNode scored : horizon.path("candidates")) {
                    if (evaluationId.equals(scored.at("/evaluation/id").asText())) {
                        return scored.at("/evaluation/candidate");
                    }
                }
            }
        }
        throw new AssertionError("the scan did not carry the row's own evaluation " + evaluationId);
    }

    /**
     * Same strikes, same expirations, same size, same package price — value for value.
     *
     * <p>JSON member ORDER is not content, and the persisted rail deliberately re-emits breakevens
     * as decimals rather than strings, so the comparison is made on sorted keys and on decimal
     * values. Every actual amount, contract and stamp still has to match exactly.</p>
     */
    private static void assertSamePackage(JsonNode shown, JsonNode adopted) {
        assertThat(keySorted(adopted.path("legs")))
                .as("exact legs, strikes and expirations").isEqualTo(keySorted(shown.path("legs")));
        assertThat(keySorted(adopted.path("price")))
                .as("the one package-price receipt").isEqualTo(keySorted(shown.path("price")));
        assertThat(adopted.path("strategy").asText()).isEqualTo(shown.path("strategy").asText());
        assertThat(adopted.path("qty").asInt()).isEqualTo(shown.path("qty").asInt());
        assertThat(adopted.path("maxLossCents").asLong()).isEqualTo(shown.path("maxLossCents").asLong());
        assertThat(decimals(adopted.path("breakevens"))).isEqualTo(decimals(shown.path("breakevens")));
    }

    /** The node's exact text with object members in a deterministic order. */
    private static String keySorted(JsonNode node) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode sorted = Json.MAPPER.createObjectNode();
            java.util.List<String> names = new java.util.ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            java.util.Collections.sort(names);
            for (String name : names) sorted.put(name, keySorted(node.get(name)));
            return Json.write(sorted);
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode sorted = Json.MAPPER.createArrayNode();
            node.forEach(child -> sorted.add(keySorted(child)));
            return Json.write(sorted);
        }
        return Json.write(node);
    }

    private static java.util.List<java.math.BigDecimal> decimals(JsonNode array) {
        java.util.List<java.math.BigDecimal> values = new java.util.ArrayList<>();
        array.forEach(value -> values.add(new java.math.BigDecimal(value.asText())));
        return values;
    }

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

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return Json.parse(response.body());
    }
}
