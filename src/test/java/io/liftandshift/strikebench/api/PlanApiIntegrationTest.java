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
import java.time.ZoneOffset;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

class PlanApiIntegrationTest {
    private static ApiServer server;
    private static HttpClient http;
    private static String base;

    @BeforeAll static void start() {
        var config = new HashMap<>(TestDb.freshConfig());
        config.put("FIXTURES_ONLY", "true");
        server = ApiServer.create(new AppConfig(config),
                Clock.fixed(Instant.parse("2026-07-12T16:00:00Z"), ZoneOffset.UTC));
        Javalin app = server.start(0);
        base = "http://localhost:" + app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterAll static void stop() { if (server != null) server.stop(); }

    @Test void planCrudIsServerMarketOwnedVersionedAndIdempotent() throws Exception {
        JsonNode empty = json(get("/api/plans"));
        assertThat(empty.get("market").asText()).isEqualTo("DEMO");
        int baselinePlans = empty.get("plans").size();

        String body = """
                {"clientRequestId":"browser-create-1","symbol":"AAPL","intent":"INCOME",
                 "thesis":"neutral","horizonDays":30,"riskMode":"conservative",
                 "marketKind":"OBSERVED","worldId":"foreign"}
                """;
        HttpResponse<String> createdResponse = post("/api/plans", body);
        assertThat(createdResponse.statusCode()).isEqualTo(201);
        JsonNode created = Json.parse(createdResponse.body());
        String id = created.get("id").asText();
        long version = created.get("version").asLong();
        assertThat(created.get("marketKind").asText()).isEqualTo("DEMO");
        assertThat(created.has("worldId")).isFalse();
        assertThat(created.at("/context/rev").asInt()).isEqualTo(1);

        JsonNode replay = json(post("/api/plans", body));
        assertThat(replay.get("id").asText()).isEqualTo(id);
        JsonNode duplicate = json(post("/api/plans", body.replace("browser-create-1", "browser-create-2")));
        assertThat(duplicate.get("id").asText()).isNotEqualTo(id);
        assertThat(json(get("/api/plans")).get("plans")).hasSize(baselinePlans + 2);

        JsonNode updated = json(put("/api/plans/" + id + "/context",
                "{\"expectedVersion\":" + version + ",\"thesis\":\"bearish\",\"horizonDays\":45}"));
        assertThat(updated.at("/context/rev").asInt()).isEqualTo(2);
        assertThat(updated.at("/context/thesis").asText()).isEqualTo("bearish");

        HttpResponse<String> stale = put("/api/plans/" + id + "/context",
                "{\"expectedVersion\":" + version + ",\"thesis\":\"bullish\"}");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(Json.parse(stale.body()).get("detail").asText()).contains("another tab");

        HttpResponse<String> locked = put("/api/plans/" + id + "/stage",
                "{\"expectedVersion\":" + updated.get("version").asLong() + ",\"stage\":\"manage_review\"}");
        assertThat(locked.statusCode()).isEqualTo(409);
    }

    @Test void historicalEvidenceIsPlanOwnedExactAndInvalidatedByAContextRevision() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"evidence-plan-1","symbol":"AAPL","intent":"DIRECTIONAL",
                 "thesis":"bullish","horizonDays":10,"riskMode":"conservative"}
                """));
        String id = plan.get("id").asText();
        JsonNode saved = json(post("/api/plans/" + id + "/evidence/study", """
                {"key":"pullback_rebound","symbol":"AAPL","from":"2023-01-01","to":"2026-07-10",
                 "params":{"lookback":20,"dropPct":5,"forward":63,"eventSpacing":10,
                 "minSample":5,"confidencePct":95,"bootstrapSamples":200,
                 "regime":"ALL","multiplicity":"CATALOG_BONFERRONI","splitHalf":true}}
                """));

        assertThat(saved.get("state").asText()).isEqualTo("CURRENT");
        assertThat(saved.at("/result/symbol").asText()).isEqualTo("AAPL");
        assertThat(saved.at("/result/forwardDays").asInt())
                .as("the Plan horizon, not a client override, owns the study window")
                .isEqualTo(10);
        assertThat(saved.at("/result/studyKey").asText()).isNotBlank();
        assertThat(saved.at("/result/analogPaths").size()).isGreaterThanOrEqualTo(5);

        JsonNode latest = json(get("/api/plans/" + id + "/evidence/latest")).get("evidence");
        assertThat(latest.at("/result/studyKey").asText())
                .isEqualTo(saved.at("/result/studyKey").asText());
        assertThat(latest.at("/result/analogPaths"))
                .isEqualTo(saved.at("/result/analogPaths"));
        assertThat(latest.at("/request/params/forward").asInt()).isEqualTo(10);

        json(put("/api/plans/" + id + "/context",
                "{\"expectedVersion\":" + plan.get("version").asLong() + ",\"horizonDays\":21}"));
        JsonNode afterRevision = json(get("/api/plans/" + id + "/evidence/latest"));
        assertThat(!afterRevision.has("evidence") || afterRevision.get("evidence").isNull()).isTrue();
    }

    @Test void strategyCompetitionIsPlanOwnedNormalizedSelectableAndContextBound() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"strategy-plan-1","symbol":"AAPL","intent":"INCOME",
                 "thesis":"neutral","horizonDays":30,"riskMode":"conservative"}
                """));
        String id = plan.get("id").asText();
        JsonNode run = json(post("/api/plans/" + id + "/strategy/run", "{}"));
        assertThat(run.at("/strategy/state").asText()).isEqualTo("CURRENT");
        JsonNode result = run.at("/strategy/result");
        assertThat(result.get("symbol").asText()).isEqualTo("AAPL");
        assertThat(result.get("intent").asText()).isEqualTo("INCOME");
        assertThat(result.get("candidates").size()).isGreaterThan(0);
        JsonNode candidate = result.get("candidates").get(0);
        assertThat(candidate.get("id").asText()).startsWith("pcand_");
        assertThat(candidate.get("legs").size()).isGreaterThan(0);
        assertThat(candidate.has("economics")).isTrue();

        JsonNode latest = json(get("/api/plans/" + id + "/strategy/latest")).get("strategy");
        assertThat(latest.at("/result/candidates/0/id").asText()).isEqualTo(candidate.get("id").asText());
        JsonNode restoredLegs = latest.at("/result/candidates/0/legs");
        assertThat(restoredLegs.size()).isEqualTo(candidate.get("legs").size());
        for (int i = 0; i < restoredLegs.size(); i++) {
            JsonNode before = candidate.get("legs").get(i), after = restoredLegs.get(i);
            assertThat(after.get("action").asText()).isEqualTo(before.get("action").asText());
            assertThat(after.get("type").asText()).isEqualTo(before.get("type").asText());
            assertThat(after.get("expiration").asText()).isEqualTo(before.get("expiration").asText());
            assertThat(after.get("ratio").asInt()).isEqualTo(before.get("ratio").asInt());
            assertThat(new java.math.BigDecimal(after.get("strike").asText()))
                    .isEqualByComparingTo(new java.math.BigDecimal(before.get("strike").asText()));
            assertThat(new java.math.BigDecimal(after.get("entryPrice").asText()))
                    .isEqualByComparingTo(new java.math.BigDecimal(before.get("entryPrice").asText()));
        }
        assertThat(latest.at("/result/candidates/0/economics/verdict").asText())
                .isEqualTo(candidate.at("/economics/verdict").asText());

        JsonNode selected = json(put("/api/plans/" + id + "/strategy/select",
                "{\"candidateId\":\"" + candidate.get("id").asText() + "\",\"expectedVersion\":"
                        + run.at("/plan/version").asLong() + "}"));
        assertThat(selected.at("/selection/candidateId").asText()).isEqualTo(candidate.get("id").asText());
        assertThat(json(get("/api/plans/" + id + "/strategy/latest"))
                .at("/strategy/result/candidates/0/selected").asBoolean()).isTrue();

        json(put("/api/plans/" + id + "/context",
                "{\"expectedVersion\":" + selected.at("/plan/version").asLong() + ",\"horizonDays\":45}"));
        JsonNode afterRevision = json(get("/api/plans/" + id + "/strategy/latest"));
        assertThat(!afterRevision.has("strategy") || afterRevision.get("strategy").isNull()).isTrue();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isBetween(200, 299);
        return Json.parse(response.body());
    }
}
