package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
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
    private static Db inspectDb;

    @BeforeAll static void start() {
        var config = new HashMap<>(TestDb.freshConfig());
        config.put("FIXTURES_ONLY", "true");
        server = ApiServer.create(new AppConfig(config),
                Clock.fixed(Instant.parse("2026-07-12T16:00:00Z"), ZoneOffset.UTC));
        inspectDb = new Db(config.get("DB_URL"), config.get("DB_USER"), config.get("DB_PASSWORD"));
        Javalin app = server.start(0);
        base = "http://localhost:" + app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterAll static void stop() {
        if (server != null) server.stop();
        if (inspectDb != null) inspectDb.close();
    }

    @Test void invalidSavedWorldIsDurablyReconciledToTheAvailableBaseline() throws Exception {
        JsonNode generated = json(post("/api/datasets/generate", """
                {"symbol":"AAPL","spec":{"model":"GBM","shape":"CHOP","horizonDays":40,
                 "stepsPerDay":4,"driftAnnual":0.0,"volAnnual":0.25,"jumpsPerYear":0,
                 "jumpMean":0,"jumpVol":0,"tailNu":6,"seed":8484,"paths":20}}
                """));
        String datasetId = generated.get("datasetId").asText();
        json(put("/api/datasets/active", "{\"id\":\"" + datasetId + "\"}"));
        inspectDb.exec("INSERT INTO settings(k,v,updated_at) VALUES(?,?,now()) " +
                        "ON CONFLICT (k) DO UPDATE SET v=excluded.v,updated_at=excluded.updated_at",
                "active_world:local", "observed");
        JsonNode world = json(get("/api/world"));
        assertThat(world.path("world").asText()).isEqualTo("demo");
        assertThat(inspectDb.query("SELECT v FROM settings WHERE k=?", row -> row.str("v"),
                "active_world:local")).containsExactly("demo");
        assertThat(json(get("/api/datasets")).get("active").asText()).isEqualTo("observed");
        json(delete("/api/datasets/" + datasetId));
    }

    @Test void planCrudIsServerMarketOwnedVersionedAndIdempotent() throws Exception {
        JsonNode empty = json(get("/api/plans"));
        assertThat(empty.get("market").asText()).isEqualTo("DEMO");
        int baselinePlans = empty.get("plans").size();

        String body = """
                {"clientRequestId":"browser-create-1","symbol":"AAPL","intent":"INCOME","title":"API CRUD plan",
                 "thesis":"neutral","horizonDays":30,"targetCents":27123,"riskMode":"conservative",
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
        assertThat(duplicate.get("id").asText()).isEqualTo(id);
        JsonNode sameInquiryDifferentPresentation = json(post("/api/plans", body
                .replace("browser-create-1", "browser-create-presentation")
                .replace("API CRUD plan", "Same inquiry from another entry point")
                .replace("\"riskMode\":\"conservative\"", "\"riskMode\":\"aggressive\"")));
        assertThat(sameInquiryDifferentPresentation.get("id").asText()).isEqualTo(id);
        JsonNode differentHorizon = json(post("/api/plans", body
                .replace("browser-create-1", "browser-create-3").replace("\"horizonDays\":30", "\"horizonDays\":60")));
        assertThat(differentHorizon.get("id").asText()).isNotEqualTo(id);
        assertThat(json(get("/api/plans")).get("plans")).hasSize(baselinePlans + 2);
        assertThat(duplicate.get("symbol").asText()).isEqualTo(created.get("symbol").asText());
        assertThat(duplicate.get("intent").asText()).isEqualTo(created.get("intent").asText());

        JsonNode updated = json(put("/api/plans/" + id + "/context",
                "{\"expectedVersion\":" + version + ",\"thesis\":\"bearish\",\"horizonDays\":45}"));
        assertThat(updated.at("/context/rev").asInt()).isEqualTo(2);
        assertThat(updated.at("/context/thesis").asText()).isEqualTo("bearish");

        HttpResponse<String> stale = put("/api/plans/" + id + "/context",
                "{\"expectedVersion\":" + version + ",\"thesis\":\"bullish\"}");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(Json.parse(stale.body()).get("detail").asText()).contains("another tab");

        HttpResponse<String> locked = post("/api/plans/" + id + "/progress",
                "{\"stage\":\"manage_review\"}");
        assertThat(locked.statusCode()).isEqualTo(409);

        long progressVersion = updated.get("version").asLong();
        JsonNode progressed = json(post("/api/plans/" + id + "/progress", "{\"stage\":\"outcomes\"}"));
        assertThat(progressed.get("furthestStage").asText()).isEqualTo("OUTCOMES");
        assertThat(progressed.get("version").asLong()).isEqualTo(progressVersion);

        JsonNode closedChip = json(put("/api/plans/" + id + "/open",
                "{\"expectedVersion\":" + updated.get("version").asLong() + ",\"open\":false}"));
        assertThat(closedChip.get("open").asBoolean()).isFalse();
        assertThat(json(get("/api/plans")).get("plans")).hasSize(baselinePlans + 1);
        JsonNode fullLibrary = json(get("/api/plans?scope=all&openOnly=false"));
        JsonNode retained = java.util.stream.StreamSupport.stream(fullLibrary.get("plans").spliterator(), false)
                .filter(p -> id.equals(p.get("id").asText())).findFirst().orElseThrow();
        assertThat(retained.get("open").asBoolean()).isFalse();

        JsonNode archived = json(post("/api/plans/" + id + "/archive",
                "{\"expectedVersion\":" + closedChip.get("version").asLong() + "}"));
        HttpResponse<String> reopenArchived = put("/api/plans/" + id + "/open",
                "{\"expectedVersion\":" + archived.get("version").asLong() + ",\"open\":true}");
        assertThat(reopenArchived.statusCode()).isEqualTo(409);

        JsonNode disposable = json(post("/api/plans", """
                {"clientRequestId":"delete-draft-api","symbol":"QQQ","intent":"ACQUIRE",
                 "title":"Disposable draft","thesis":"neutral","horizonDays":23}
                """));
        assertThat(json(delete("/api/plans/" + disposable.get("id").asText() + "?expectedVersion="
                + disposable.get("version").asLong())).get("deleted").asText())
                .isEqualTo(disposable.get("id").asText());
        assertThat(get("/api/plans/" + disposable.get("id").asText()).statusCode()).isEqualTo(404);
    }

    @Test void planAnalysisMarketMismatchReturnsARecoverableTypedConflict() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"market-mismatch-plan","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"Demo-owned analysis","thesis":"bullish","horizonDays":30,"riskMode":"conservative"}
                """));
        JsonNode world = json(post("/api/sim/market", """
                {"name":"Different active market","symbols":{"AAPL":1.0},"scenario":"CHOP","speed":26}
                """));
        String worldId = world.get("worldId").asText();
        try {
            assertThat(put("/api/world", "{\"world\":\"" + worldId + "\"}").statusCode()).isEqualTo(200);
            HttpResponse<String> mismatch = post("/api/plans/" + plan.get("id").asText() + "/outcomes/ensemble",
                    "{\"expectedVersion\":" + plan.get("version").asLong() + "}");
            assertThat(mismatch.statusCode()).isEqualTo(409);
            JsonNode body = Json.parse(mismatch.body());
            assertThat(body.get("error").asText()).isEqualTo("plan_market_mismatch");
            assertThat(body.get("market").asText()).isEqualTo("DEMO");
            assertThat(body.get("targetWorld").asText()).isEqualTo("demo");
            assertThat(body.get("detail").asText()).contains("belongs to the Demo market");
        } finally {
            put("/api/world", "{\"world\":\"demo\"}");
            delete("/api/sim/market/" + worldId);
        }
    }

    @Test void researchReportsPlanEligibilityAndCreationFailsClosedForUnavailableSymbols() throws Exception {
        JsonNode optionable = json(get("/api/research/AAPL"));
        assertThat(optionable.get("planEligible").asBoolean()).isTrue();
        assertThat(optionable.get("planEligibility").asText()).contains("Ready");

        JsonNode stockOnly = json(get("/api/research/VTSAX"));
        assertThat(stockOnly.get("planEligible").asBoolean()).isFalse();
        assertThat(stockOnly.get("planEligibility").asText()).contains("no listed options");

        HttpResponse<String> noOptions = post("/api/plans", """
                {"clientRequestId":"unavailable-vtsax","symbol":"VTSAX","intent":"DIRECTIONAL"}
                """);
        assertThat(noOptions.statusCode()).isEqualTo(422);
        assertThat(Json.parse(noOptions.body()).get("error").asText()).isEqualTo("plan_symbol_unavailable");

        HttpResponse<String> outsideMarket = post("/api/plans", """
                {"clientRequestId":"unavailable-unknown","symbol":"ZZZZQQ","intent":"DIRECTIONAL"}
                """);
        assertThat(outsideMarket.statusCode()).isEqualTo(422);
        assertThat(Json.parse(outsideMarket.body()).get("detail").asText()).contains("not available");
    }

    @Test void historicalEvidenceIsPlanOwnedExactAndInvalidatedByAContextRevision() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"evidence-plan-1","symbol":"AAPL","intent":"DIRECTIONAL","title":"Evidence ownership plan",
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

    @Test void publicHistoricalReceiptMustKeepItsExactStudyIdentityWhenAttachedToAPlan() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"evidence-exact-public-1","symbol":"TSLA","intent":"DIRECTIONAL",
                 "title":"Exact public evidence","thesis":"bearish","horizonDays":23,"riskMode":"conservative"}
                """));
        String id = plan.get("id").asText();
        String study = """
                {"key":"pullback_rebound","symbol":"TSLA","from":"2023-01-01","to":"2026-07-10",
                 "params":{"lookback":20,"dropPct":5,"forward":23,"eventSpacing":23,
                 "minSample":5,"confidencePct":95,"bootstrapSamples":200,
                 "regime":"ALL","multiplicity":"CATALOG_BONFERRONI","splitHalf":true}}
                """;
        JsonNode publicResult = json(post("/api/research/event-studies", study));
        String exactKey = publicResult.get("studyKey").asText();

        ObjectNode drifted = (ObjectNode) Json.parse(study);
        drifted.put("expectedStudyKey", "not-the-public-study-key");
        HttpResponse<String> rejected = post("/api/plans/" + id + "/evidence/study", drifted.toString());
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(Json.parse(rejected.body()).get("detail").asText()).contains("changed");
        JsonNode afterReject = json(get("/api/plans/" + id + "/evidence/latest"));
        assertThat(afterReject.path("evidence").isMissingNode() || afterReject.path("evidence").isNull()).isTrue();

        ObjectNode exact = (ObjectNode) Json.parse(study);
        exact.put("expectedStudyKey", exactKey);
        JsonNode saved = json(post("/api/plans/" + id + "/evidence/study", exact.toString()));
        assertThat(saved.at("/result/studyKey").asText()).isEqualTo(exactKey);
        assertThat(saved.at("/result/analogPaths")).isEqualTo(publicResult.get("analogPaths"));
        assertThat(json(get("/api/plans/" + id + "/evidence/latest"))
                .at("/evidence/result/studyKey").asText()).isEqualTo(exactKey);
    }

    @Test void planEvidenceRestoresOnlyTheActiveAnalysisDataset() throws Exception {
        json(put("/api/datasets/active", "{\"id\":\"observed\"}"));
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"evidence-dataset-plan-1","symbol":"AAPL","intent":"DIRECTIONAL","title":"Evidence dataset plan",
                 "thesis":"bullish","horizonDays":10,"riskMode":"conservative"}
                """));
        String id = plan.get("id").asText();
        String study = """
                {"key":"pullback_rebound","symbol":"AAPL","from":"2023-01-01","to":"2026-07-12",
                 "params":{"lookback":20,"dropPct":1,"forward":10,"eventSpacing":10,
                 "minSample":2,"confidencePct":95,"bootstrapSamples":100,
                 "regime":"ALL","multiplicity":"CATALOG_BONFERRONI","splitHalf":true}}
                """;
        JsonNode baseline = json(post("/api/plans/" + id + "/evidence/study", study));
        assertThat(baseline.get("basis").asText()).isEqualTo("DEMO_HISTORY");
        assertThat(baseline.path("datasetId").isNull() || baseline.path("datasetId").isMissingNode()).isTrue();

        JsonNode generated = json(post("/api/datasets/generate", """
                {"symbol":"AAPL","spec":{"model":"GBM","shape":"CHOP","horizonDays":400,
                 "stepsPerDay":4,"driftAnnual":0.0,"volAnnual":0.25,"jumpsPerYear":0,
                 "jumpMean":0,"jumpVol":0,"tailNu":6,"seed":424242,"paths":20}}
                """));
        String datasetId = generated.get("datasetId").asText();
        json(put("/api/datasets/active", "{\"id\":\"" + datasetId + "\"}"));
        JsonNode beforeScenarioRun = json(get("/api/plans/" + id + "/evidence/latest"));
        assertThat(beforeScenarioRun.path("evidence").isNull() || beforeScenarioRun.path("evidence").isMissingNode())
                .as("baseline evidence must not appear under a generated analysis label").isTrue();

        JsonNode scenario = json(post("/api/plans/" + id + "/evidence/study", study));
        assertThat(scenario.get("basis").asText()).isEqualTo("SCENARIO_DATASET");
        assertThat(scenario.get("datasetId").asText()).isEqualTo(datasetId);

        json(put("/api/datasets/active", "{\"id\":\"observed\"}"));
        assertThat(json(get("/api/plans/" + id + "/evidence/latest"))
                .at("/evidence/result/studyKey").asText()).isEqualTo(baseline.at("/result/studyKey").asText());
        json(put("/api/datasets/active", "{\"id\":\"" + datasetId + "\"}"));
        assertThat(json(get("/api/plans/" + id + "/evidence/latest"))
                .at("/evidence/result/studyKey").asText()).isEqualTo(scenario.at("/result/studyKey").asText());
        json(put("/api/datasets/active", "{\"id\":\"observed\"}"));
        json(delete("/api/datasets/" + datasetId));
    }

    @Test void finishingASimulatedMarketClosesItsPlanCollectionAtomically() throws Exception {
        JsonNode world = json(post("/api/sim/market", """
                {"name":"Plan collection terminal","symbols":{"AAPL":1.0},"scenario":"CHOP","speed":26}
                """));
        String worldId = world.get("worldId").asText();
        assertThat(put("/api/world", "{\"world\":\"" + worldId + "\"}").statusCode()).isEqualTo(200);
        JsonNode plan;
        try {
            plan = json(post("/api/plans", """
                    {"clientRequestId":"terminal-world-plan","symbol":"AAPL","intent":"DIRECTIONAL","title":"Terminal world plan",
                     "thesis":"bullish","horizonDays":20,"riskMode":"conservative"}
                    """));
            assertThat(plan.get("marketKind").asText()).isEqualTo("SIMULATED");
            assertThat(plan.get("open").asBoolean()).isTrue();

            JsonNode strategy = json(post("/api/plans/" + plan.get("id").asText() + "/strategy/run", "{}"));
            JsonNode candidate = strategy.at("/strategy/result/candidates/0");
            JsonNode restored = json(get("/api/plans/" + plan.get("id").asText() + "/strategy/latest"))
                    .at("/strategy/result/candidates/0");
            assertThat(candidate.at("/legs")).isNotEmpty();
            assertThat(candidate.at("/identity/family").asText()).isEqualTo(candidate.get("strategy").asText());
            assertThat(candidate.at("/identity/definedRisk").isBoolean()).isTrue();
            assertThat(restored.get("identity")).isEqualTo(candidate.get("identity"));
            assertThat(restored.at("/legs").size()).isEqualTo(candidate.at("/legs").size());
            for (int i = 0; i < candidate.at("/legs").size(); i++) {
                JsonNode before = candidate.at("/legs").get(i), after = restored.at("/legs").get(i);
                assertThat(after.get("action").asText()).isEqualTo(before.get("action").asText());
                assertThat(after.get("type").asText()).isEqualTo(before.get("type").asText());
                assertThat(new java.math.BigDecimal(after.get("strike").asText()))
                        .isEqualByComparingTo(new java.math.BigDecimal(before.get("strike").asText()));
                assertThat(new java.math.BigDecimal(after.get("entryPrice").asText()))
                        .isEqualByComparingTo(new java.math.BigDecimal(before.get("entryPrice").asText()));
            }
        } finally {
            assertThat(delete("/api/sim/market/" + worldId).statusCode()).isEqualTo(200);
        }
        JsonNode retained = json(get("/api/plans/" + plan.get("id").asText()));
        assertThat(retained.get("open").asBoolean()).isFalse();
        assertThat(json(get("/api/world")).get("world").asText()).isEqualTo("demo");
    }

    @Test void strategyCompetitionIsPlanOwnedNormalizedSelectableAndContextBound() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"strategy-plan-1","symbol":"AAPL","intent":"INCOME","title":"Strategy competition plan",
                 "thesis":"neutral","horizonDays":30,"riskMode":"conservative"}
                """));
        String id = plan.get("id").asText();
        JsonNode run = json(post("/api/plans/" + id + "/strategy/run", "{}"));
        assertThat(run.at("/strategy/state").asText()).isEqualTo("CURRENT");
        assertThat(run.at("/strategy/inputHash").asText()).hasSize(64);
        JsonNode result = run.at("/strategy/result");
        assertThat(result.get("symbol").asText()).isEqualTo("AAPL");
        assertThat(result.get("intent").asText()).isEqualTo("INCOME");
        assertThat(result.get("candidates").size()).isGreaterThan(0);
        JsonNode candidate = result.get("candidates").get(0);
        assertThat(candidate.get("id").asText()).startsWith("pcand_");
        assertThat(candidate.get("legs").size()).isGreaterThan(0);
        assertThat(candidate.at("/evaluation/assessment/economics/verdict").asText()).isNotBlank();
        assertThat(candidate.has("economics")).isFalse();
        assertThat(candidate.at("/evaluation/score/components").isArray()).isTrue();
        assertThat(candidate.at("/evaluation/evidence/perDimension").isObject()).isTrue();
        assertThat(candidate.at("/evaluation/management/rules").isArray()).isTrue();

        JsonNode latest = json(get("/api/plans/" + id + "/strategy/latest")).get("strategy");
        assertThat(latest.at("/inputHash").asText()).isEqualTo(run.at("/strategy/inputHash").asText());
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
        assertThat(latest.at("/result/candidates/0/evaluation/assessment/economics/verdict").asText())
                .isEqualTo(candidate.at("/evaluation/assessment/economics/verdict").asText());
        assertThat(candidate.at("/evaluation/assessment/portfolioImpacts/practice/lane").asText())
                .isEqualTo("PRACTICE");
        assertJsonEquivalent(latest.at("/result/candidates/0/evaluation"), candidate.at("/evaluation"));

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
        JsonNode staleOutcomes = json(get("/api/plans/" + id + "/outcomes/latest"));
        assertThat(staleOutcomes.at("/selectionState").asText()).isEqualTo("STALE");
        assertThat(staleOutcomes.at("/priorSelection/id").asText()).isEqualTo(candidate.get("id").asText());
        JsonNode staleDecision = json(get("/api/plans/" + id + "/decision/latest"));
        assertThat(staleDecision.at("/selectionState").asText()).isEqualTo("STALE");
        assertThat(staleDecision.at("/priorSelection/id").asText()).isEqualTo(candidate.get("id").asText());
    }

    @Test void builderFitUsesThePlanContextWithoutMutatingOrPersistingASecondWorkflow() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"strategy-fit-plan-1","symbol":"AAPL","intent":"DIRECTIONAL","title":"Builder fit plan",
                 "thesis":"bullish","horizonDays":31,"riskMode":"conservative"}
                """));
        String id = plan.get("id").asText();
        long version = plan.get("version").asLong();
        JsonNode fit = json(post("/api/plans/" + id + "/strategy/fit", """
                {"expectedVersion":%d,"strategy":"DEBIT_CALL_SPREAD","maxLossCents":100000,
                 "filters":{"minPop":0.05}}
                """.formatted(version)));
        assertThat(fit.at("/candidate/strategy").asText()).isEqualTo("DEBIT_CALL_SPREAD");
        assertThat(fit.at("/candidate/legs")).isNotEmpty();
        assertThat(fit.at("/plan/version").asLong()).isEqualTo(version);
        JsonNode latest = json(get("/api/plans/" + id + "/strategy/latest"));
        assertThat(!latest.has("strategy") || latest.get("strategy").isNull()).isTrue();

        assertThat(post("/api/plans/" + id + "/strategy/fit",
                "{\"expectedVersion\":999,\"strategy\":\"DEBIT_CALL_SPREAD\"}").statusCode()).isEqualTo(409);
    }

    @Test void customBuilderAndScoutPicksRemainExactPlanOwnedStructures() throws Exception {
        JsonNode customPlan = json(post("/api/plans", """
                {"clientRequestId":"custom-plan-api-1","symbol":"AAPL","intent":"DIRECTIONAL","title":"Custom builder plan",
                 "thesis":"bullish","horizonDays":30,"riskMode":"conservative"}
                """));
        String customId = customPlan.get("id").asText();
        JsonNode competition = json(post("/api/plans/" + customId + "/strategy/run", "{}"));
        JsonNode source = competition.at("/strategy/result/candidates/0");
        var customBody = Json.MAPPER.createObjectNode();
        customBody.put("expectedVersion", customPlan.get("version").asLong());
        var position = customBody.putObject("position");
        position.put("symbol", "AAPL"); position.put("strategy", source.get("strategy").asText());
        position.put("qty", source.get("qty").asInt()); position.put("fillNature", "PROPOSED");
        position.set("legs", source.get("legs"));
        JsonNode custom = json(post("/api/plans/" + customId + "/strategy/custom", customBody.toString()));
        assertThat(custom.at("/strategy/result/candidate/selected").asBoolean()).isTrue();
        assertThat(custom.at("/strategy/result/candidate/legs")).isEqualTo(source.get("legs"));
        assertThat(json(get("/api/plans/" + customId + "/strategy/latest")).at("/selected/id").asText())
                .isEqualTo(custom.at("/strategy/result/candidate/id").asText());

        String exactSelectionId = custom.at("/strategy/result/candidate/id").asText();
        JsonNode refreshedCompetition = json(post("/api/plans/" + customId + "/strategy/run",
                "{\"filters\":{\"minPop\":0.05}}"));
        JsonNode restoredAfterRefresh = json(get("/api/plans/" + customId + "/strategy/latest"));
        assertThat(refreshedCompetition.at("/strategy/inputHash").asText()).hasSize(64);
        assertThat(restoredAfterRefresh.at("/strategy/inputHash").asText())
                .isEqualTo(refreshedCompetition.at("/strategy/inputHash").asText());
        assertThat(restoredAfterRefresh.at("/selected/id").asText()).isEqualTo(exactSelectionId);
        assertThat(restoredAfterRefresh.at("/selected/sourceKind").asText()).isEqualTo("CUSTOM");

        JsonNode scoutPlan = json(post("/api/plans", """
                {"clientRequestId":"scout-plan-api-1","symbol":"AAPL","intent":"DIRECTIONAL","title":"Plan scout origin",
                 "thesis":"bullish","horizonDays":30,"riskMode":"conservative"}
                """));
        String scoutId = scoutPlan.get("id").asText();
        JsonNode scout = json(post("/api/plans/" + scoutId + "/scout/run",
                "{\"scope\":\"ALTERNATIVES\",\"maxPicks\":4}"));
        JsonNode scoutCandidates = scout.at("/scout/result/candidates");
        assertThat(scoutCandidates.size()).isGreaterThan(0);
        assertThat(scout.at("/scout/result/sentimentScorerVersion").asText())
                .isEqualTo("sentiment-keyword-v1");
        JsonNode pick = scoutCandidates.get(0);
        assertThat(pick.get("symbol").asText()).isNotEqualTo("AAPL");
        assertThat(pick.at("/sentimentScorerVersion").asText()).isEqualTo("sentiment-keyword-v1");
        JsonNode restoredScout = json(get("/api/plans/" + scoutId + "/scout/latest?scope=ALTERNATIVES"));
        assertThat(restoredScout.at("/scout/result/sentimentScorerVersion").asText())
                .isEqualTo("sentiment-keyword-v1");
        assertThat(restoredScout.at("/scout/result/candidates/0/sentimentScorerVersion").asText())
                .isEqualTo("sentiment-keyword-v1");

        JsonNode child = json(post("/api/plans/" + scoutId + "/scout/spawn", """
                {"clientRequestId":"spawn-scout-api-1","candidateId":"%s","role":"ALTERNATIVE"}
                """.formatted(pick.get("id").asText())));
        assertThat(child.at("/plan/originPlanId").asText()).isEqualTo(scoutId);
        assertThat(child.at("/plan/symbol").asText()).isEqualTo(pick.get("symbol").asText());
        JsonNode childStrategy = json(get("/api/plans/" + child.at("/plan/id").asText() + "/strategy/latest"));
        assertThat(childStrategy.at("/selected/legs")).isEqualTo(pick.get("legs"));
        assertThat(childStrategy.at("/selected/sentimentScorerVersion").asText())
                .isEqualTo("sentiment-keyword-v1");
    }

    @Test void exactBuilderSelectionCompetesBesideServerProposalsOnOneStoredFan() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"custom-vs-proposals-one-fan","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"My structure beside the ranked field","thesis":"bullish","horizonDays":30,
                 "riskMode":"conservative"}
                """));
        String id = plan.get("id").asText();
        JsonNode ranked = json(post("/api/plans/" + id + "/strategy/run", "{}"));
        JsonNode proposals = ranked.at("/strategy/result/candidates");
        assertThat(proposals.size()).isGreaterThan(1);
        JsonNode source = proposals.get(0);

        var customBody = Json.MAPPER.createObjectNode();
        customBody.put("expectedVersion", ranked.at("/plan/version").asLong());
        var position = customBody.putObject("position");
        position.put("symbol", "AAPL");
        position.put("strategy", "CUSTOM");
        position.put("qty", source.path("qty").asInt(1));
        position.put("fillNature", "PROPOSED");
        position.set("legs", source.get("legs"));
        JsonNode custom = json(post("/api/plans/" + id + "/strategy/custom", customBody.toString()));
        String customId = custom.at("/strategy/result/candidate/id").asText();
        long version = custom.at("/plan/version").asLong();
        assertThat(custom.at("/strategy/result/candidate/selected").asBoolean()).isTrue();
        assertThat(customId).isNotBlank();
        assertThat(proposals.toString()).doesNotContain(customId);

        JsonNode fan = json(post("/api/plans/" + id + "/outcomes/ensemble", """
                {"expectedVersion":%d,
                 "over":{"model":"GBM","shape":"GRIND_UP","horizonDays":30,"stepsPerDay":2,
                   "driftAnnual":0.08,"volAnnual":0.30,"jumpsPerYear":0,"jumpMean":0,
                   "jumpVol":0,"tailNu":6,"seed":1414,"paths":120},
                 "iv":{"startIv":0.32,"driftPerYear":0,"meanRevertSpeed":1,"longRunIv":0.30,
                   "eventDay":-1,"eventShockPct":0,"minIv":0.03,"maxIv":4.0}}
                """.formatted(version)));
        String ensembleId = fan.at("/ensemble/id").asText();
        String fingerprint = fan.at("/ensemble/fingerprint").asText();

        JsonNode compared = json(post("/api/plans/" + id + "/outcomes/compare", """
                {"expectedVersion":%d,"basis":"PARAMETRIC","ensembleId":"%s"}
                """.formatted(version, ensembleId)));
        assertThat(compared.at("/ensemble/id").asText()).isEqualTo(ensembleId);
        assertThat(compared.at("/ensemble/fingerprint").asText()).isEqualTo(fingerprint);
        assertThat(compared.at("/comparison/ensembleId").asText()).isEqualTo(ensembleId);
        assertThat(compared.at("/comparison/ensembleFingerprint").asText()).isEqualTo(fingerprint);
        assertThat(compared.at("/comparison/fairness").asText())
                .contains(fingerprint).contains("captured proposal entries").contains("cash");

        JsonNode items = compared.at("/comparison/items");
        JsonNode customItem = null;
        int proposalRows = 0;
        boolean cash = false;
        java.util.Set<String> proposalIds = new java.util.HashSet<>();
        proposals.forEach(node -> proposalIds.add(node.path("id").asText()));
        for (JsonNode item : items) {
            if (customId.equals(item.path("candidateId").asText())) customItem = item;
            if (proposalIds.contains(item.path("candidateId").asText())) proposalRows++;
            if ("CASH".equals(item.path("key").asText())) cash = true;
        }
        assertThat(customItem).as("the exact selected Builder package stays in the field").isNotNull();
        assertThat(customItem.path("selected").asBoolean()).isTrue();
        assertThat(customItem.path("strategy").asText())
                .isEqualTo(custom.at("/strategy/result/candidate/strategy").asText());
        assertThat(proposalRows).as("server-ranked alternatives stay beside the user's package")
                .isEqualTo(proposals.size());
        assertThat(cash).as("cash stays in the same fair comparison").isTrue();

        assertThat(inspectDb.query("SELECT COUNT(*) n FROM plan_ensemble WHERE plan_id=?",
                row -> row.intv("n"), id)).containsExactly(1);
        assertThat(inspectDb.query("SELECT psr.run_kind FROM plan_strategy_run psr "
                        + "JOIN plan_candidate pc ON pc.run_id=psr.id WHERE pc.id=?",
                row -> row.str("run_kind"), customId)).containsExactly("CUSTOM");
        assertThat(inspectDb.query("SELECT ensemble_id || ':' || ensemble_fingerprint receipt "
                        + "FROM plan_outcome_comparison WHERE plan_id=?",
                row -> row.str("receipt"), id)).containsExactly(ensembleId + ":" + fingerprint);
    }

    @Test void defaultOneSessionPlanFanRetainsAnIntradayStochasticJourney() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"one-session-motion-grid","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"One-session stochastic journey","thesis":"bullish","horizonDays":1,
                 "riskMode":"balanced"}
                """));
        JsonNode fan = json(post("/api/plans/" + plan.get("id").asText() + "/outcomes/ensemble",
                "{\"expectedVersion\":" + plan.get("version").asLong() + "}"));

        JsonNode path = fan.at("/preview/samples/0");
        assertThat(fan.at("/preview/horizonDays").asInt()).isEqualTo(1);
        assertThat(path.size()).isEqualTo(13);
        double marketIv = fan.at("/preview/marketImplied/atmIv").asDouble();
        assertThat(marketIv).isPositive();
        assertThat(fan.at("/preview/receipt/spec/volAnnual").asDouble()).isEqualTo(marketIv);
        double start = path.get(0).asDouble(), end = path.get(path.size() - 1).asDouble();
        boolean hasStochasticInterior = false;
        for (int i = 1; i < path.size() - 1; i++) {
            double straightLine = start + (end - start) * i / (path.size() - 1.0);
            if (Math.abs(path.get(i).asDouble() - straightLine) > 1e-8) {
                hasStochasticInterior = true;
                break;
            }
        }
        assertThat(hasStochasticInterior).isTrue();

        JsonNode neighbor = fan.at("/preview/samples/1");
        boolean sourcePathsDiverge = false;
        for (int i = 1; i < path.size() - 1; i++) {
            if (Math.abs(path.get(i).asDouble() - neighbor.get(i).asDouble()) > 1e-8) {
                sourcePathsDiverge = true;
                break;
            }
        }
        assertThat(sourcePathsDiverge).isTrue();
        assertThat(fan.at("/preview/canvas/underlying")).hasSize(2);
        assertThat(fan.at("/preview/canvas/underlyingSteps")).hasSize(13);
        assertThat(fan.at("/preview/canvas/underlyingSteps/1/step").asInt()).isEqualTo(1);
        assertThat(fan.at("/preview/canvas/underlyingSteps/1/sessionProgress").asDouble())
                .isEqualTo(0.0833);
        assertThat(fan.at("/preview/canvas/positions/0/days")).hasSize(2);
        assertThat(fan.at("/preview/canvas/positions/0/steps")).hasSize(13);
        assertThat(fan.at("/preview/canvas/positions/0/legs/0/days")).hasSize(2);
        assertThat(fan.at("/preview/canvas/positions/0/legs/0/steps")).hasSize(13);
        assertThat(fan.at("/preview/canvas/positions/0/steps/1").has("focusValueCents")).isTrue();
        assertThat(fan.at("/preview/canvas/positions/0/steps/1").has("greeks")).isTrue();

    }

    @Test void explicitOneSessionResolutionAndVolatilityRemainAuthored() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"one-session-explicit-grid","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"Explicit one-session grid","thesis":"bullish","horizonDays":1,
                 "riskMode":"balanced"}
                """));
        JsonNode fan = json(post("/api/plans/" + plan.get("id").asText() + "/outcomes/ensemble", """
                {"expectedVersion":%d,"over":{"model":"GBM","shape":"CHOP","horizonDays":1,
                 "stepsPerDay":1,"driftAnnual":0.0,"volAnnual":0.41,"jumpsPerYear":0,
                 "jumpMean":0,"jumpVol":0,"tailNu":6,"seed":7171,"paths":50}}
                """.formatted(plan.get("version").asLong())));

        assertThat(fan.at("/preview/samples/0").size()).isEqualTo(2);
        assertThat(fan.at("/preview/receipt/spec/stepsPerDay").asInt()).isEqualTo(1);
        assertThat(fan.at("/preview/receipt/spec/volAnnual").asDouble()).isEqualTo(0.41);
    }

    @Test void customPlanAnalysisPreservesAdjustedContractMultipliersEndToEnd() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"adjusted-custom-plan","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"Adjusted contract plan","thesis":"bullish","horizonDays":30,
                 "riskMode":"conservative"}
                """));
        String id = plan.get("id").asText();
        JsonNode field = json(post("/api/plans/" + id + "/strategy/run", "{}"));
        JsonNode source = null;
        for (JsonNode item : field.at("/strategy/result/candidates")) {
            java.util.Set<String> expirations = new java.util.LinkedHashSet<>();
            for (JsonNode leg : item.path("legs")) {
                if (leg.hasNonNull("expiration")) expirations.add(leg.path("expiration").asText());
            }
            if (item.path("legs").size() == 2 && expirations.size() == 1
                    && item.path("maxLossCents").asLong() > 0) {
                source = item;
                break;
            }
        }
        assertThat(source).as("fixture strategy field contains a two-leg defined-risk structure").isNotNull();

        var request = Json.MAPPER.createObjectNode();
        request.put("expectedVersion", plan.get("version").asLong());
        var position = request.putObject("position");
        position.put("symbol", "AAPL");
        position.put("strategy", source.get("strategy").asText());
        position.put("qty", 3);
        position.put("fillNature", "PROPOSED");
        var adjustedLegs = position.putArray("legs");
        for (JsonNode leg : source.withArray("legs")) {
            var adjusted = (com.fasterxml.jackson.databind.node.ObjectNode) leg.deepCopy();
            adjusted.put("multiplier", 10);
            adjustedLegs.add(adjusted);
        }

        JsonNode analyzed = json(post("/api/plans/" + id + "/strategy/custom", request.toString()));
        JsonNode candidate = analyzed.at("/strategy/result/candidate");
        assertThat(candidate.path("qty").asInt()).isEqualTo(3);
        for (JsonNode leg : candidate.withArray("legs")) {
            assertThat(leg.path("multiplier").asInt()).isEqualTo(10);
        }
        long sourceQty = source.path("qty").asLong(1);
        assertThat(candidate.path("entryNetPremiumCents").asLong() * 10 * sourceQty)
                .isEqualTo(source.path("entryNetPremiumCents").asLong() * 3);
        assertThat(candidate.path("maxLossCents").asLong() * 10 * sourceQty)
                .isEqualTo(source.path("maxLossCents").asLong() * 3);
        assertThat(json(get("/api/plans/" + id + "/strategy/latest"))
                .at("/selected/legs/0/multiplier").asInt()).isEqualTo(10);

        JsonNode marketOutcome = json(post("/api/plans/" + id + "/outcomes/run",
                "{\"expectedVersion\":" + analyzed.at("/plan/version").asLong()
                        + ",\"basis\":\"RISK_NEUTRAL\"}"));
        assertThat(marketOutcome.at("/outcome/result/theoreticalMaxLossCents").asLong())
                .isEqualTo(candidate.path("maxLossCents").asLong());

        JsonNode decided = json(post("/api/plans/" + id + "/decision/cash",
                "{\"expectedVersion\":" + analyzed.at("/plan/version").asLong()
                        + ",\"note\":\"Preserve the adjusted-contract receipt\"}"));
        assertThat(decided.at("/decision/legs/0/multiplier").asInt())
                .as(decided.toPrettyString()).isEqualTo(10);
        assertThat(decided.at("/decision/metrics/decisionQty").asInt()).isEqualTo(3);
        assertThat(decided.at("/decision/metrics/entryNetPremiumCents").asLong())
                .isEqualTo(candidate.path("entryNetPremiumCents").asLong());
        assertThat(decided.at("/decision/maxLossCents").asLong())
                .isEqualTo(candidate.path("maxLossCents").asLong());
    }

    @Test void constrainedCustomAnalysisCannotReplaceTheSelectedPlanStructure() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"constrained-custom-plan","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"Constrained custom analysis","thesis":"bullish","horizonDays":30,
                 "riskMode":"conservative"}
                """));
        String id = plan.get("id").asText();
        JsonNode field = json(post("/api/plans/" + id + "/strategy/run", "{}"));
        JsonNode source = field.at("/strategy/result/candidates/0");

        var validBody = Json.MAPPER.createObjectNode();
        validBody.put("expectedVersion", plan.get("version").asLong());
        var validPosition = validBody.putObject("position");
        validPosition.put("symbol", "AAPL");
        validPosition.put("strategy", source.get("strategy").asText());
        validPosition.put("qty", source.path("qty").asInt(1));
        validPosition.put("fillNature", "PROPOSED");
        validPosition.set("legs", source.get("legs"));
        JsonNode valid = json(post("/api/plans/" + id + "/strategy/custom", validBody.toString()));
        long selectedVersion = valid.at("/plan/version").asLong();

        var constrainedBody = validBody.deepCopy();
        constrainedBody.put("expectedVersion", selectedVersion);
        constrainedBody.withObject("/position").put("proposedNetCents", 99_999_00L);
        JsonNode constrained = json(post("/api/plans/" + id + "/strategy/custom", constrainedBody.toString()));

        assertThat(constrained.at("/preview/ok").asBoolean()).isFalse();
        assertThat(constrained.at("/strategy/result/candidate/selected").asBoolean()).isFalse();
        assertThat(constrained.at("/plan/version").asLong()).isEqualTo(selectedVersion + 1);
        assertThat(constrained.at("/plan/furthestStage").asText()).isEqualTo("STRATEGY");
        assertThat(json(get("/api/plans/" + id + "/strategy/latest")).get("selected")).isNull();
    }

    @Test void outcomesReuseThePlanEvidenceEnsembleAndPersistSeparateInterpretations() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"outcome-plan-api-1","symbol":"AAPL","intent":"DIRECTIONAL","title":"Outcome comparison plan",
                 "thesis":"bullish","horizonDays":30,"riskMode":"conservative"}
                """));
        String id = plan.get("id").asText();
        JsonNode strategy = json(post("/api/plans/" + id + "/strategy/run", "{}"));
        JsonNode candidate = null;
        for (JsonNode item : strategy.at("/strategy/result/candidates")) {
            if ("DEBIT_CALL_SPREAD".equals(item.path("strategy").asText())) { candidate = item; break; }
        }
        if (candidate == null) candidate = strategy.at("/strategy/result/candidates/0");
        JsonNode selected = json(put("/api/plans/" + id + "/strategy/select",
                "{\"candidateId\":\"" + candidate.get("id").asText() + "\",\"expectedVersion\":"
                        + strategy.at("/plan/version").asLong() + "}"));
        long version = selected.at("/plan/version").asLong();

        JsonNode ensemble = json(post("/api/plans/" + id + "/outcomes/ensemble", """
                {"expectedVersion":%d,"over":{"model":"GBM","shape":"GRIND_UP","horizonDays":99,
                 "stepsPerDay":2,"driftAnnual":0.12,"volAnnual":0.28,"jumpsPerYear":0,
                 "jumpMean":0,"jumpVol":0,"tailNu":6,"seed":8080,"paths":120},
                 "iv":{"startIv":0.32,"driftPerYear":-0.02,"meanRevertSpeed":1.0,"longRunIv":0.28,
                 "eventDay":-1,"eventShockPct":0,"minIv":0.03,"maxIv":4.0}}
                """.formatted(version)));
        String ensembleId = ensemble.at("/ensemble/id").asText();
        String fingerprint = ensemble.at("/ensemble/fingerprint").asText();
        assertThat(ensemble.at("/preview/horizonDays").asInt()).isEqualTo(30);
        assertThat(ensemble.at("/preview/receipt/fingerprint").asText()).isEqualTo(fingerprint);

        JsonNode modeled = json(post("/api/plans/" + id + "/outcomes/run",
                "{\"expectedVersion\":" + version + ",\"basis\":\"PARAMETRIC\",\"ensembleId\":\""
                        + ensembleId + "\"}"));
        assertThat(modeled.at("/outcome/ensembleId").asText()).isEqualTo(ensembleId);
        assertThat(modeled.at("/outcome/result/paths").asInt()).isEqualTo(120);

        JsonNode compared = json(post("/api/plans/" + id + "/outcomes/compare",
                "{\"expectedVersion\":" + version + ",\"basis\":\"PARAMETRIC\",\"ensembleId\":\""
                        + ensembleId + "\"}"));
        assertThat(compared.at("/comparison/ensembleFingerprint").asText()).isEqualTo(fingerprint);
        assertThat(compared.at("/comparison/items").size())
                .isEqualTo(strategy.at("/strategy/result/candidates").size() + 1);
        assertThat(compared.at("/comparison/items").toString()).contains("Keep cash");
        assertThat(compared.at("/comparison/fairness").asText()).contains(fingerprint).contains("cash");

        JsonNode market = json(post("/api/plans/" + id + "/outcomes/run",
                "{\"expectedVersion\":" + version + ",\"basis\":\"RISK_NEUTRAL\"}"));
        assertThat(market.at("/outcome/result/probabilityMap/pAnyProfit").isNumber()).isTrue();

        JsonNode latest = json(get("/api/plans/" + id + "/outcomes/latest"));
        assertThat(latest.get("outcomes")).hasSize(2);
        assertThat(latest.get("comparisons")).hasSize(1);
        assertThat(latest.at("/comparisons/0/ensembleFingerprint").asText()).isEqualTo(fingerprint);
        JsonNode restoredModel = null;
        for (JsonNode run : latest.get("outcomes")) if ("PARAMETRIC".equals(run.path("basis").asText())) restoredModel = run;
        assertThat(restoredModel).isNotNull();
        assertThat(restoredModel.path("ensembleId").asText()).isEqualTo(ensembleId);
        assertThat(restoredModel.path("bands").size()).isGreaterThan(1);
        assertThat(latest.at("/selected/id").asText()).isEqualTo(candidate.get("id").asText());

        HttpResponse<String> badReplay = post("/api/plans/" + id + "/outcomes/backtest",
                "{\"expectedVersion\":" + version + ",\"engine\":\"single\",\"from\":\"nope\",\"to\":\"2026-06-30\"}");
        assertThat(badReplay.statusCode()).isEqualTo(400);

        JsonNode replay = json(post("/api/plans/" + id + "/outcomes/backtest", """
                {"expectedVersion":%d,"engine":"single","from":"2026-03-02","to":"2026-06-30",
                 "targetDte":30,"entryEveryDays":5,"qty":1,"slippagePct":0.0,"startingCashCents":10000000}
                """.formatted(version)));
        assertThat(replay.at("/report/sampleSize").asInt()).isGreaterThan(0);
        assertThat(replay.at("/report/pricingMode").asText()).isEqualTo("MODELED_FROM_UNDERLYING");
        String backtestId = replay.at("/report/id").asText();
        assertThat(json(get("/api/plans/" + id + "/outcomes/backtests/" + backtestId)).get("id").asText())
                .isEqualTo(backtestId);
        JsonNode other = json(post("/api/plans", """
                {"clientRequestId":"backtest-owner-check","symbol":"SPY","intent":"DIRECTIONAL","title":"Backtest ownership plan",
                 "thesis":"bullish","horizonDays":30,"riskMode":"conservative"}
                """));
        assertThat(get("/api/plans/" + other.get("id").asText() + "/outcomes/backtests/" + backtestId).statusCode())
                .isEqualTo(404);
        JsonNode latestAfterReplay = json(get("/api/plans/" + id + "/outcomes/latest"));
        assertThat(latestAfterReplay.at("/backtests/0/backtestId").asText()).isEqualTo(backtestId);
        assertThat(latestAfterReplay.at("/backtests/0/maxDrawdownPct").isNumber()).isTrue();
        assertThat(latestAfterReplay.at("/backtests/0/state").asText()).isEqualTo("CURRENT");
        assertThat(latestAfterReplay.at("/backtests/0/currentContext").asBoolean()).isTrue();

        JsonNode replacement = null;
        for (JsonNode item : strategy.at("/strategy/result/candidates")) {
            if (!item.path("id").asText().equals(candidate.path("id").asText())) { replacement = item; break; }
        }
        assertThat(replacement).as("fixture field provides another proposal").isNotNull();
        JsonNode reselected = json(put("/api/plans/" + id + "/strategy/select",
                "{\"candidateId\":\"" + replacement.path("id").asText() + "\",\"expectedVersion\":" + version + "}"));
        JsonNode afterSelectionChange = json(get("/api/plans/" + id + "/outcomes/latest"));
        assertThat(afterSelectionChange.get("outcomes")).hasSize(0);
        assertThat(afterSelectionChange.get("comparisons")).hasSize(1);
        assertThat(afterSelectionChange.at("/backtests/0/state").asText()).isEqualTo("STALE");
        assertThat(afterSelectionChange.at("/selected/id").asText())
                .isEqualTo(reselected.at("/selection/candidateId").asText());
    }

    @Test void storedEnsembleRestoresByteCompatiblyForTheCurrentContext() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"ensemble-restore-plan","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"Stored fan restore plan","thesis":"bullish","horizonDays":30,"riskMode":"conservative"}
                """));
        String id = plan.get("id").asText();
        assertThat(get("/api/plans/" + id + "/outcomes/ensemble/latest").statusCode()).isEqualTo(404);

        JsonNode ran = json(post("/api/plans/" + id + "/outcomes/ensemble", """
                {"expectedVersion":%d,"levels":[{"key":"floor","price":240},{"key":"target","price":280}]}
                """.formatted(plan.get("version").asLong())));
        HttpResponse<String> restoredResponse = get("/api/plans/" + id + "/outcomes/ensemble/latest");
        assertThat(restoredResponse.statusCode()).isEqualTo(200);
        JsonNode restored = Json.parse(restoredResponse.body());

        assertThat(restored.at("/ensemble/id").asText()).isEqualTo(ran.at("/ensemble/id").asText());
        assertThat(restored.at("/ensemble/fingerprint").asText())
                .isEqualTo(ran.at("/ensemble/fingerprint").asText());
        assertThat(restored.at("/preview/planEnsembleFingerprint").asText())
                .isEqualTo(ran.at("/preview/planEnsembleFingerprint").asText());
        assertThat(restored.at("/preview/receipt/fingerprint").asText())
                .isEqualTo(ran.at("/ensemble/fingerprint").asText());
        assertThat(restored.at("/preview/decisionMap/levels")).hasSize(2);
        assertJsonEquivalent(restored.get("preview"), ran.get("preview"));
    }

    @Test void unifiedScenarioCanvasPersistsSurfaceTemplateReceiptAndSameEnsemblePositions() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"unified-scenario-canvas","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"Unified scenario canvas","thesis":"neutral","horizonDays":8,"riskMode":"conservative"}
                """));
        String id = plan.get("id").asText();
        long version = plan.get("version").asLong();
        JsonNode run = json(post("/api/plans/" + id + "/outcomes/ensemble", """
                {"expectedVersion":%d,
                 "canvas":{"calendar":"NYSE","dividendYieldAnnual":0.012,
                   "dividendBasis":"User-authored annualized continuous dividend yield.",
                   "skewVolPerLogMoneyness":-0.25,"termVolPerSqrtYear":0.08,
                   "surfaceDynamics":"STICKY_STRIKE","settlementPolicy":"PHYSICAL_IF_ITM",
                   "exercisePolicy":"EXPIRATION_ONLY",
                   "ivNodes":[{"dayIndex":0,"atmIv":0.42},{"dayIndex":8,"atmIv":0.24}]},
                 "template":{"kind":"DRIFT_TO_TARGET","targetPriceCents":27000}}
                """.formatted(version)));

        assertThat(run.at("/preview/waypointFill").asText()).isEqualTo("EXACT_CONDITIONAL");
        assertThat(run.at("/preview/waypoints")).hasSize(1);
        assertThat(run.at("/preview/canvas/underlying")).hasSize(9);
        assertThat(run.at("/preview/canvas/positions").size()).isGreaterThanOrEqualTo(1);
        assertThat(run.at("/preview/canvas/positions").toString()).contains("STOCK_BASELINE");
        assertThat(run.at("/preview/canvas/modelReceipt/authoredPathMeaning").asText())
                .isEqualTo("USER_HYPOTHESIS_NOT_FORECAST");
        assertThat(run.at("/preview/canvas/modelReceipt/template/kind").asText())
                .isEqualTo("DRIFT_TO_TARGET");
        assertThat(run.at("/preview/canvas/modelReceipt/template/noHindsight").asBoolean()).isTrue();
        assertThat(run.at("/preview/canvas/modelReceipt/ivNodes")).hasSize(2);
        assertThat(run.at("/preview/canvasModel/surfaceDynamics").asText()).isEqualTo("STICKY_STRIKE");

        JsonNode restored = json(get("/api/plans/" + id + "/outcomes/ensemble/latest"));
        assertThat(restored.at("/ensemble/fingerprint").asText())
                .isEqualTo(run.at("/ensemble/fingerprint").asText());
        assertThat(restored.at("/preview/canvas/modelReceipt/template/fingerprint").asText())
                .isEqualTo(run.at("/preview/canvas/modelReceipt/template/fingerprint").asText());
        assertThat(restored.at("/preview/canvasModel")).isEqualTo(run.at("/preview/canvasModel"));

        ObjectNode save = Json.MAPPER.createObjectNode();
        save.put("expectedVersion", version);
        save.put("title", "Target hypothesis");
        save.put("baseEnsembleId", run.at("/ensemble/id").asText());
        save.set("over", run.at("/preview/receipt/spec"));
        JsonNode authored = json(post("/api/plans/" + id + "/scenarios", save.toString()));
        assertThat(authored.at("/scenario/canvas/template/kind").asText()).isEqualTo("DRIFT_TO_TARGET");
        assertThat(authored.at("/scenario/modelReceipt/baseEnsembleFingerprint").asText())
                .isEqualTo(run.at("/ensemble/fingerprint").asText());
        assertThat(authored.at("/scenario/modelReceipt/authoredPathMeaning").asText())
                .isEqualTo("USER_HYPOTHESIS_NOT_FORECAST");

        HttpResponse<String> borrowedEvent = post("/api/plans/" + id + "/outcomes/ensemble",
                "{\"expectedVersion\":" + version + ",\"template\":{\"kind\":\"EARNINGS_GAP_UP\"}}");
        assertThat(borrowedEvent.statusCode()).isEqualTo(400);
        assertThat(Json.parse(borrowedEvent.body()).path("detail").asText())
                .contains("Observed market").contains("never borrowed into Demo");
    }

    @Test void authoredWaypointsReachGenerationCarryTheirHonestyLabelAndFreezeAsScenarios() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"scenario-canvas-plan","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"Scenario canvas plan","thesis":"bullish","horizonDays":30,"riskMode":"conservative"}
                """));
        String id = plan.get("id").asText();
        long version = plan.get("version").asLong();

        // 1) Waypoints POSTed on the scenario spec reach generation; the response carries the label.
        String gbmSpec = """
                {"model":"GBM","shape":"CHOP","horizonDays":30,"stepsPerDay":1,
                 "driftAnnual":0.0,"volAnnual":0.25,"jumpsPerYear":0,"jumpMean":0,"jumpVol":0,
                 "tailNu":6,"seed":7231,"paths":60,
                 "waypoints":[{"dayIndex":5,"priceRatio":0.95},{"dayIndex":12,"priceRatio":1.06,"tolerance":0.02}]}
                """;
        JsonNode pinned = json(post("/api/plans/" + id + "/outcomes/ensemble",
                "{\"expectedVersion\":" + version + ",\"over\":" + gbmSpec + "}"));
        assertThat(pinned.at("/ensemble/waypointFill").asText()).isEqualTo("EXACT_CONDITIONAL");
        assertThat(pinned.at("/preview/waypointFill").asText()).isEqualTo("EXACT_CONDITIONAL");
        assertThat(pinned.at("/preview/waypoints")).hasSize(2);
        assertThat(pinned.at("/preview/waypoints/1/tolerance").asDouble()).isEqualTo(0.02);
        assertThat(pinned.at("/preview/sessionDates")).hasSize(30);
        assertThat(pinned.at("/preview/sessionDates/0").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        String pinnedEnsembleId = pinned.at("/ensemble/id").asText();
        String pinnedFingerprint = pinned.at("/ensemble/fingerprint").asText();
        // The pins actually condition the paths: median at the pinned day sits at the pinned level.
        double spot = pinned.at("/preview/spot").asDouble();
        double medianAtPin = pinned.at("/preview/bands/12/p50").asDouble();
        assertThat(medianAtPin / spot).isCloseTo(1.06, org.assertj.core.data.Offset.offset(0.002));

        // 2) The label and pins survive storage: the repaint endpoint restores both.
        JsonNode restored = json(get("/api/plans/" + id + "/outcomes/ensemble/latest"));
        assertThat(restored.at("/ensemble/waypointFill").asText()).isEqualTo("EXACT_CONDITIONAL");
        assertThat(restored.at("/preview/waypointFill").asText()).isEqualTo("EXACT_CONDITIONAL");
        assertThat(restored.at("/preview/waypoints")).hasSize(2);

        // 3) A fat-tailed model with pins is labeled as guided interpolation, never as exact.
        JsonNode guided = json(post("/api/plans/" + id + "/outcomes/ensemble",
                ("{\"expectedVersion\":" + version + ",\"over\":" + gbmSpec + "}")
                        .replace("\"GBM\"", "\"STUDENT_T\"")));
        assertThat(guided.at("/ensemble/waypointFill").asText()).isEqualTo("GUIDED_INTERPOLATION");
        assertThat(guided.at("/preview/waypointFill").asText()).isEqualTo("GUIDED_INTERPOLATION");
        String guidedEnsembleId = guided.at("/ensemble/id").asText();

        // 4) A pin beyond the Plan horizon is refused with a units-bearing explanation.
        HttpResponse<String> beyond = post("/api/plans/" + id + "/outcomes/ensemble",
                "{\"expectedVersion\":" + version + ",\"over\":"
                        + gbmSpec.replace("\"dayIndex\":12", "\"dayIndex\":40") + "}");
        assertThat(beyond.statusCode()).isEqualTo(400);
        assertThat(Json.parse(beyond.body()).get("detail").asText())
                .contains("beyond the scenario horizon").contains("30 trading days");

        // 5) Save the authored scenario over the exact base fan; the receipt carries lineage.
        JsonNode savedScenario = json(post("/api/plans/" + id + "/scenarios",
                "{\"expectedVersion\":" + version + ",\"title\":\"Dip, then squeeze\",\"baseEnsembleId\":\""
                        + pinnedEnsembleId + "\",\"over\":" + gbmSpec + "}"));
        String scenarioId = savedScenario.at("/scenario/id").asText();
        assertThat(savedScenario.at("/scenario/title").asText()).isEqualTo("Dip, then squeeze");
        assertThat(savedScenario.at("/scenario/waypointFill").asText()).isEqualTo("EXACT_CONDITIONAL");
        assertThat(savedScenario.at("/scenario/waypointCount").asInt()).isEqualTo(2);
        assertThat(savedScenario.at("/scenario/currentContext").asBoolean()).isTrue();
        assertThat(savedScenario.at("/scenario/baseEnsembleFingerprint").asText()).isEqualTo(pinnedFingerprint);
        assertThat(savedScenario.at("/scenario/fingerprint").asText()).hasSize(64);

        // The Desk receives a bounded projection of the exact stored fan.  This is selection over
        // the base artifact, not a second local simulator or a newly generated visual-only path set.
        JsonNode displayPaths = json(get("/api/plans/" + id + "/outcomes/ensemble/paths?scenarioId="
                + scenarioId + "&limit=6"));
        assertThat(displayPaths.at("/ensemble/id").asText()).isEqualTo(pinnedEnsembleId);
        assertThat(displayPaths.at("/ensemble/fingerprint").asText()).isEqualTo(pinnedFingerprint);
        assertThat(displayPaths.at("/scenario/fingerprint").asText())
                .isEqualTo(savedScenario.at("/scenario/fingerprint").asText());
        assertThat(displayPaths.at("/paths/selection").asText()).isEqualTo("NEAREST_AUTHORED_WAYPOINTS");
        assertThat(displayPaths.at("/paths/totalPathCount").asInt()).isEqualTo(60);
        assertThat(displayPaths.at("/paths/paths")).hasSize(6);
        assertThat(displayPaths.at("/paths/paths/0/role").asText()).isEqualTo("FOCUS");
        assertThat(displayPaths.at("/paths/paths/0/prices")).hasSize(31);
        assertThat(displayPaths.at("/paths/paths/0/prices/12").asDouble() / spot)
                .isCloseTo(1.06, org.assertj.core.data.Offset.offset(0.002));

        // Saving without a single waypoint is refused: the canvas is for authored paths.
        HttpResponse<String> unpinned = post("/api/plans/" + id + "/scenarios",
                "{\"expectedVersion\":" + version + ",\"over\":"
                        + gbmSpec.replaceAll("\"waypoints\":\\[[^]]*]", "\"waypoints\":[]") + "}");
        assertThat(unpinned.statusCode()).isEqualTo(400);
        assertThat(Json.parse(unpinned.body()).get("detail").asText()).contains("waypoint");

        // 6) List + load round-trip the frozen spec, pins, tolerance, and label over the API.
        JsonNode listed = json(get("/api/plans/" + id + "/scenarios"));
        assertThat(listed.get("scenarios")).hasSize(1);
        assertThat(listed.at("/scenarios/0/id").asText()).isEqualTo(scenarioId);
        JsonNode loaded = json(get("/api/plans/" + id + "/scenarios/" + scenarioId));
        assertThat(loaded.at("/scenario/spec/model").asText()).isEqualTo("GBM");
        assertThat(loaded.at("/scenario/spec/waypoints")).hasSize(2);
        assertThat(loaded.at("/scenario/spec/waypoints/1/tolerance").asDouble()).isEqualTo(0.02);
        assertThat(loaded.at("/scenario/waypoints/0/dayIndex").asInt()).isEqualTo(5);
        assertThat(get("/api/plans/" + id + "/scenarios/auth_missing").statusCode()).isEqualTo(404);

        // 7) A position priced on a pinned fan carries the label on the fresh run AND when the
        //    saved result is restored later from /outcomes/latest.
        JsonNode strategy = json(post("/api/plans/" + id + "/strategy/run", "{}"));
        JsonNode candidate = strategy.at("/strategy/result/candidates/0");
        JsonNode selected = json(put("/api/plans/" + id + "/strategy/select",
                "{\"candidateId\":\"" + candidate.get("id").asText() + "\",\"expectedVersion\":" + version + "}"));
        long selectedVersion = selected.at("/plan/version").asLong();
        JsonNode modeled = json(post("/api/plans/" + id + "/outcomes/run",
                "{\"expectedVersion\":" + selectedVersion + ",\"basis\":\"PARAMETRIC\",\"ensembleId\":\""
                        + guidedEnsembleId + "\"}"));
        assertThat(modeled.at("/ensemble/waypointFill").asText()).isEqualTo("GUIDED_INTERPOLATION");
        JsonNode latestOutcomes = json(get("/api/plans/" + id + "/outcomes/latest"));
        JsonNode restoredRun = null;
        for (JsonNode run : latestOutcomes.get("outcomes")) {
            if ("PARAMETRIC".equals(run.path("basis").asText())) restoredRun = run;
        }
        assertThat(restoredRun).isNotNull();
        assertThat(restoredRun.path("waypointFill").asText()).isEqualTo("GUIDED_INTERPOLATION");

        // The production animation query is non-mutating: inline pins and IV/canvas assumptions
        // select and value one coherent source path without changing the stored fan fingerprint.
        String animationRequest = """
                {"ensembleId":"%s","limit":6,
                 "waypoints":[{"dayIndex":10,"priceRatio":0.97,"tolerance":0.03}],
                 "iv":{"startIv":0.55,"driftPerYear":-0.1,"meanRevertSpeed":2,
                   "longRunIv":0.32,"eventDay":-1,"eventShockPct":0,"minIv":0.03,"maxIv":4.0},
                 "canvas":{"calendar":"NYSE","skewVolPerLogMoneyness":-0.2,
                   "termVolPerSqrtYear":0.04,"surfaceDynamics":"STICKY_MONEYNESS",
                   "settlementPolicy":"CASH_INTRINSIC","exercisePolicy":"EXPIRATION_ONLY",
                   "ivNodes":[{"dayIndex":0,"atmIv":0.55},{"dayIndex":30,"atmIv":0.30}]}}
                """.formatted(guidedEnsembleId);
        JsonNode animation = json(post("/api/plans/" + id + "/outcomes/ensemble/paths", animationRequest));
        assertThat(animation.at("/ensemble/id").asText()).isEqualTo(guidedEnsembleId);
        assertThat(animation.at("/ensemble/fingerprint").asText())
                .isEqualTo(guided.at("/ensemble/fingerprint").asText());
        assertThat(animation.at("/receipt/contractVersion").asText()).isEqualTo("scenario-animation-1");
        assertThat(animation.at("/receipt/pathModelVersion").asText()).isNotBlank();
        assertThat(animation.at("/receipt/worldId").asText()).isEqualTo("demo");
        assertThat(animation.at("/receipt/datasetId").asText()).isEqualTo("observed");
        assertThat(animation.at("/receipt/pathAssumptions").isObject()).isTrue();
        assertThat(animation.at("/receipt/conditioningAssumptions/waypoints")).hasSize(1);
        assertThat(animation.at("/receipt/ivAssumptions/startIv").asDouble()).isEqualTo(.55);
        assertThat(animation.at("/receipt/valuationAssumptions/ivNodes")).hasSize(2);
        assertThat(animation.at("/paths/receipt/version").asText())
                .isEqualTo(io.liftandshift.strikebench.sim.PathEnsembleService.DISPLAY_SELECTION_VERSION);
        assertThat(animation.at("/paths/receipt/withinToleranceCount").asInt())
                .isGreaterThanOrEqualTo(animation.at("/paths/receipt/selectedWithinToleranceCount").asInt());
        int focusIndex = animation.at("/paths/receipt/focusSourcePathIndex").asInt();
        assertThat(animation.at("/checkpoints/focusSourcePathIndex").asInt()).isEqualTo(focusIndex);
        assertThat(animation.at("/checkpoints/underlying/10/focusPrice").asDouble())
                .isEqualTo(animation.at("/paths/paths/0/prices/10").asDouble());
        assertThat(animation.at("/checkpoints/underlyingSteps"))
                .hasSize(animation.at("/paths/paths/0/prices").size());
        assertThat(animation.at("/checkpoints/underlyingSteps/10/focusPrice").asDouble())
                .isEqualTo(animation.at("/paths/paths/0/prices/10").asDouble());
        assertThat(animation.at("/checkpoints/positions").toString())
                .contains("PROPOSED:" + candidate.get("id").asText());
        assertThat(animation.at("/checkpoints/positions/0/days/0").has("focusValueCents")).isTrue();
        assertThat(animation.at("/checkpoints/positions/0/steps/0").has("focusValueCents")).isTrue();
        assertThat(animation.at("/checkpoints/positions/0/legs/0/days/0").has("state")).isTrue();
        assertThat(animation.at("/checkpoints/positions/0/legs/0/steps/0").has("state")).isTrue();
        assertThat(animation.at("/checkpoints/modelReceipt/selectedCandidateId").asText())
                .isEqualTo(candidate.get("id").asText());
        assertThat(animation.at("/checkpoints/modelReceipt/valuationFingerprint").asText()).hasSize(64);
        assertThat(animation.at("/receipt/valuationFingerprint").asText())
                .isEqualTo(animation.at("/checkpoints/modelReceipt/valuationFingerprint").asText());

        JsonNode changedAnimation = json(post("/api/plans/" + id + "/outcomes/ensemble/paths",
                animationRequest.replace("\"startIv\":0.55", "\"startIv\":0.65")));
        assertThat(changedAnimation.at("/ensemble/fingerprint").asText())
                .isEqualTo(animation.at("/ensemble/fingerprint").asText());
        assertThat(changedAnimation.at("/receipt/valuationFingerprint").asText())
                .isNotEqualTo(animation.at("/receipt/valuationFingerprint").asText());
        JsonNode afterTransientAnimation = json(get("/api/plans/" + id + "/outcomes/ensemble/latest"));
        assertThat(afterTransientAnimation.at("/preview/waypoints")).hasSize(2);
        assertThat(afterTransientAnimation.at("/preview/canvasModel/ivNodes")).isEmpty();

        // 8) Context drift: saving against the old fan is refused with the reason, and the
        //    already-authored scenario stays listed with an explicit staleness explanation.
        JsonNode revised = json(put("/api/plans/" + id + "/context",
                "{\"expectedVersion\":" + selectedVersion + ",\"horizonDays\":45}"));
        long revisedVersion = revised.get("version").asLong();
        HttpResponse<String> drifted = post("/api/plans/" + id + "/scenarios",
                "{\"expectedVersion\":" + revisedVersion + ",\"baseEnsembleId\":\"" + pinnedEnsembleId
                        + "\",\"over\":" + gbmSpec + "}");
        assertThat(drifted.statusCode()).isEqualTo(409);
        assertThat(Json.parse(drifted.body()).get("detail").asText())
                .contains("does not belong to the current Plan assumptions");
        HttpResponse<String> staleAnimation = post("/api/plans/" + id + "/outcomes/ensemble/paths",
                "{\"scenarioId\":\"" + scenarioId + "\",\"limit\":4}");
        assertThat(staleAnimation.statusCode()).isEqualTo(409);
        assertThat(Json.parse(staleAnimation.body()).path("detail").asText())
                .contains("earlier Plan assumptions");
        JsonNode listedAfterDrift = json(get("/api/plans/" + id + "/scenarios"));
        assertThat(listedAfterDrift.get("scenarios")).hasSize(1);
        assertThat(listedAfterDrift.at("/scenarios/0/currentContext").asBoolean()).isFalse();
        assertThat(listedAfterDrift.at("/scenarios/0/staleness").asText())
                .contains("assumptions changed").contains("re-anchor");
    }

    @Test void exactPlanEnsembleCreatesAReplayableLinkedRehearsalAndReview() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"rehearsal-plan-api-1","symbol":"AAPL","intent":"DIRECTIONAL","title":"Rehearsal plan",
                 "thesis":"bullish","horizonDays":5,"riskMode":"conservative"}
                """));
        String planId = plan.get("id").asText();
        long version = plan.get("version").asLong();
        JsonNode ensemble = json(post("/api/plans/" + planId + "/outcomes/ensemble", """
                {"expectedVersion":%d,"over":{"model":"GBM","shape":"GRIND_UP","horizonDays":5,
                 "stepsPerDay":2,"driftAnnual":0.10,"volAnnual":0.25,"jumpsPerYear":0,
                 "jumpMean":0,"jumpVol":0,"tailNu":6,"seed":5150,"paths":20},
                 "iv":{"startIv":0.30,"driftPerYear":0,"meanRevertSpeed":0,"longRunIv":0.30,
                 "eventDay":-1,"eventShockPct":0,"minIv":0.03,"maxIv":4.0}}
                """.formatted(version)));
        String ensembleId = ensemble.at("/ensemble/id").asText();
        String fingerprint = ensemble.at("/ensemble/fingerprint").asText();

        JsonNode created = json(post("/api/plans/" + planId + "/rehearsals", """
                {"expectedVersion":%d,"ensembleId":"%s","selection":"SAMPLE","pathIndex":0,"speed":26}
                """.formatted(version, ensembleId)));
        String worldId = created.at("/rehearsal/worldId").asText();
        assertThat(created.at("/rehearsal/fingerprint").asText()).isEqualTo(fingerprint);
        assertThat(created.at("/rehearsal/pathIndex").asInt()).isZero();
        assertThat(created.at("/rehearsal/selection").asText()).isEqualTo("SAMPLE");
        assertThat(created.at("/plan/version").asLong()).isEqualTo(version + 1);

        JsonNode rehearsals = json(get("/api/plans/" + planId + "/rehearsals"));
        assertThat(rehearsals.at("/rehearsals/0/worldId").asText()).isEqualTo(worldId);
        assertThat(rehearsals.at("/rehearsals/0/fingerprint").asText()).isEqualTo(fingerprint);
        JsonNode sessions = json(get("/api/sim/market"));
        JsonNode session = null;
        for (JsonNode item : sessions.withArray("sessions")) if (worldId.equals(item.path("id").asText())) session = item;
        assertThat(session).isNotNull();
        assertThat(session.at("/rehearsal/planId").asText()).isEqualTo(planId);

        JsonNode report = json(get("/api/sim/market/" + worldId + "/report"));
        assertThat(report.at("/rehearsal/fingerprint").asText()).isEqualTo(fingerprint);
        assertThat(report.get("note").asText()).contains("exact path 1").contains("durable receipt")
                .doesNotContain(fingerprint);

        assertThat(delete("/api/sim/market/" + worldId).statusCode()).isBetween(200, 299);
        JsonNode managed = json(get("/api/plans/" + planId + "/manage"));
        assertThat(managed.at("/plan/furthestStage").asText()).isEqualTo("MANAGE_REVIEW");
        assertThat(managed.at("/management/actions/0/kind").asText()).isEqualTo("REHEARSAL_RESULT");
        assertThat(managed.at("/management/reviews/0/category").asText()).isEqualTo("SIM_REHEARSAL");
    }

    @Test void archivedPlanCannotCreateARehearsal() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"archived-rehearsal-plan","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"Archived rehearsal plan","thesis":"bullish","horizonDays":5,"riskMode":"conservative"}
                """));
        String planId = plan.get("id").asText();
        JsonNode ensemble = json(post("/api/plans/" + planId + "/outcomes/ensemble", """
                {"expectedVersion":%d,"over":{"model":"GBM","shape":"GRIND_UP","horizonDays":5,
                 "stepsPerDay":2,"driftAnnual":0.10,"volAnnual":0.25,"jumpsPerYear":0,
                 "jumpMean":0,"jumpVol":0,"tailNu":6,"seed":5151,"paths":20}}
                """.formatted(plan.get("version").asLong())));
        JsonNode archived = json(post("/api/plans/" + planId + "/archive",
                "{\"expectedVersion\":" + plan.get("version").asLong() + "}"));

        HttpResponse<String> rejected = post("/api/plans/" + planId + "/rehearsals",
                "{\"expectedVersion\":" + archived.get("version").asLong()
                        + ",\"ensembleId\":\"" + ensemble.at("/ensemble/id").asText()
                        + "\",\"selection\":\"TYPICAL\"}");
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(Json.parse(rejected.body()).path("detail").asText()).contains("decision is frozen");
        assertThat(json(get("/api/plans/" + planId + "/rehearsals")).path("rehearsals").size()).isZero();
    }

    @Test void archivedPlanCannotRecordARehearsalFinish() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"archived-rehearsal-finish","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"Archive during rehearsal","thesis":"bullish","horizonDays":5,"riskMode":"conservative"}
                """));
        String planId = plan.get("id").asText();
        JsonNode ensemble = json(post("/api/plans/" + planId + "/outcomes/ensemble", """
                {"expectedVersion":%d,"over":{"model":"GBM","shape":"GRIND_UP","horizonDays":5,
                 "stepsPerDay":2,"driftAnnual":0.10,"volAnnual":0.25,"jumpsPerYear":0,
                 "jumpMean":0,"jumpVol":0,"tailNu":6,"seed":5152,"paths":20}}
                """.formatted(plan.get("version").asLong())));
        JsonNode created = json(post("/api/plans/" + planId + "/rehearsals", """
                {"expectedVersion":%d,"ensembleId":"%s","selection":"SAMPLE","pathIndex":0,"speed":26}
                """.formatted(plan.get("version").asLong(), ensemble.at("/ensemble/id").asText())));
        String worldId = created.at("/rehearsal/worldId").asText();
        JsonNode archived = json(post("/api/plans/" + planId + "/archive",
                "{\"expectedVersion\":" + created.at("/plan/version").asLong() + "}"));
        long frozenVersion = archived.get("version").asLong();
        String frozenStage = archived.get("furthestStage").asText();

        HttpResponse<String> rejected = delete("/api/sim/market/" + worldId);
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(Json.parse(rejected.body()).path("detail").asText()).contains("archived and read-only");
        JsonNode after = json(get("/api/plans/" + planId));
        assertThat(after.get("status").asText()).isEqualTo("ARCHIVED");
        assertThat(after.get("version").asLong()).isEqualTo(frozenVersion);
        assertThat(after.get("furthestStage").asText()).isEqualTo(frozenStage);
        assertThat(inspectDb.query("SELECT id FROM plan_management_action WHERE plan_id=?",
                row -> row.str("id"), planId)).isEmpty();
        assertThat(inspectDb.query("SELECT id FROM plan_review WHERE plan_id=?",
                row -> row.str("id"), planId)).isEmpty();
        assertThat(inspectDb.query("SELECT status FROM sim_session WHERE id=?",
                row -> row.str("status"), worldId)).noneMatch("FINISHED"::equals);

        // Return the test fixture to a finishable state so it does not consume session capacity.
        inspectDb.exec("UPDATE plans SET status='ACTIVE' WHERE id=?", planId);
        assertThat(delete("/api/sim/market/" + worldId).statusCode()).isBetween(200, 299);
    }

    @Test void decideFreezesTheServerSelectedPackageAndLinksTradeOrCash() throws Exception {
        assertThat(put("/api/account/risk-context", """
                {"nlvCents":1930000,"cashBpCents":1930000,"riskCapitalCents":193000}
                """).statusCode()).isBetween(200, 299);
        JsonNode tradePlan = json(post("/api/plans", """
                {"clientRequestId":"decision-trade-plan","symbol":"AAPL","intent":"DIRECTIONAL","title":"Trade decision plan",
                 "thesis":"bullish","horizonDays":30,"riskMode":"conservative"}
                """));
        String tradePlanId = tradePlan.get("id").asText();
        JsonNode field = json(post("/api/plans/" + tradePlanId + "/strategy/run", "{}"));
        JsonNode candidate = null;
        for (JsonNode item : field.at("/strategy/result/candidates")) {
            java.util.Set<String> expirations = new java.util.HashSet<>();
            for (JsonNode leg : item.withArray("legs")) if (!"STOCK".equals(leg.path("type").asText())) {
                expirations.add(leg.path("expiration").asText());
            }
            if (expirations.size() == 1) { candidate = item; break; }
        }
        assertThat(candidate).as("fixture field has a same-expiry package").isNotNull();
        JsonNode selected = json(put("/api/plans/" + tradePlanId + "/strategy/select",
                "{\"candidateId\":\"" + candidate.get("id").asText() + "\",\"expectedVersion\":"
                        + field.at("/plan/version").asLong() + "}"));
        long version = selected.at("/plan/version").asLong();

        JsonNode preview = json(post("/api/plans/" + tradePlanId + "/decision/preview",
                "{\"expectedVersion\":" + version + ",\"qty\":1}"));
        assertThat(preview.at("/selected/id").asText()).isEqualTo(candidate.get("id").asText());
        assertThat(preview.at("/preview/ok").asBoolean()).isTrue();
        assertThat(preview.at("/order/proposedNetCents").isNumber()).isTrue();
        assertThat(preview.at("/preview/entryNetPremiumCents").asLong())
                .isEqualTo(preview.at("/order/proposedNetCents").asLong());
        assertThat(preview.at("/order/orderInstruction/type").asText()).isEqualTo("MARKET");
        assertThat(preview.at("/order/orderInstruction/timeInForce").asText()).isEqualTo("DAY");
        assertThat(preview.at("/order/executability").asText()).isEqualTo("IMMEDIATE");
        assertThat(preview.at("/order/presentlyExecutable").asBoolean()).isTrue();

        long naturalNet = preview.at("/order/proposedNetCents").asLong();
        JsonNode marketableLimit = json(post("/api/plans/" + tradePlanId + "/decision/preview",
                "{\"expectedVersion\":" + version + ",\"qty\":1,\"orderInstruction\":"
                        + "{\"type\":\"LIMIT\",\"limitNetCents\":" + (naturalNet - 1000) + "}}"));
        assertThat(marketableLimit.at("/order/executability").asText()).isEqualTo("IMMEDIATE");
        assertThat(marketableLimit.at("/preview/entryNetPremiumCents").asLong()).isEqualTo(naturalNet);
        assertThat(marketableLimit.at("/order/orderInstruction/limitNetCents").asLong())
                .isEqualTo(naturalNet - 1000);

        JsonNode restingLimit = json(post("/api/plans/" + tradePlanId + "/decision/preview",
                "{\"expectedVersion\":" + version + ",\"qty\":1,\"orderInstruction\":"
                        + "{\"type\":\"LIMIT\",\"limitNetCents\":" + (naturalNet + 1000) + "}}"));
        assertThat(restingLimit.at("/order/executability").asText()).isEqualTo("RESTING");
        assertThat(restingLimit.at("/order/presentlyExecutable").asBoolean()).isFalse();
        assertThat(restingLimit.at("/preview/ok").asBoolean()).isFalse();

        var tradeRequest = Json.MAPPER.createObjectNode();
        tradeRequest.put("expectedVersion", version);
        tradeRequest.put("qty", 1);
        tradeRequest.set("orderInstruction", preview.at("/order/orderInstruction"));
        if (preview.has("ackToken")) tradeRequest.put("ackToken", preview.get("ackToken").asText());
        var acknowledgments = tradeRequest.putArray("acknowledgedRisks");
        for (JsonNode ack : preview.withArray("requiredAcks")) acknowledgments.add(ack.get("id").asText());
        JsonNode opened = json(post("/api/plans/" + tradePlanId + "/decision/trade", tradeRequest.toString()));
        assertThat(opened.at("/plan/status").asText()).isEqualTo("POSITION_OPEN");
        assertThat(opened.at("/plan/furthestStage").asText()).isEqualTo("MANAGE_REVIEW");
        assertThat(opened.at("/plan/assumptionsEditable").asBoolean()).isFalse();
        assertThat(opened.at("/decision/action").asText()).isEqualTo("TRADE");
        assertThat(opened.at("/decision/tradeId").asText()).isEqualTo(opened.at("/trade/id").asText());
        assertThat(opened.at("/decision/legs")).hasSize(candidate.withArray("legs").size());
        assertThat(opened.at("/decision/proposedNetCents").asLong())
                .isEqualTo(opened.at("/trade/entryNetPremiumCents").asLong());
        assertThat(opened.at("/decision/orderInstruction/type").asText()).isEqualTo("MARKET");
        assertThat(opened.at("/decision/executability").asText()).isEqualTo("IMMEDIATE");
        assertThat(opened.at("/decision/presentlyExecutable").asBoolean()).isTrue();
        assertThat(opened.at("/decision/accountNlvCents").asLong()).isEqualTo(1_930_000L);
        assertThat(opened.at("/decision/riskCapitalCents").asLong()).isEqualTo(193_000L);

        JsonNode planBook = json(get("/api/plans/portfolio"));
        JsonNode tradePlanRow = null;
        for (JsonNode row : planBook.withArray("plans")) {
            if (tradePlanId.equals(row.at("/plan/id").asText())) { tradePlanRow = row; break; }
        }
        assertThat(tradePlanRow).as("the Plan book includes the opened position").isNotNull();
        assertThat(tradePlanRow.at("/decision/action").asText()).isEqualTo("TRADE");
        assertThat(tradePlanRow.at("/tradeId").asText()).isEqualTo(opened.at("/trade/id").asText());
        assertThat(tradePlanRow.at("/mark/tradeId").asText()).isEqualTo(opened.at("/trade/id").asText());

        long openVersion = opened.at("/plan/version").asLong();
        JsonNode marked = json(post("/api/plans/" + tradePlanId + "/manage/refresh",
                "{\"expectedVersion\":" + openVersion + "}"));
        assertThat(marked.at("/management/actions/0/kind").asText()).isEqualTo("MARK");
        JsonNode listedExpirations = json(get("/api/research/AAPL/expirations")).get("expirations");
        String currentExpiration = candidate.at("/legs/0/expiration").asText();
        String laterExpiration = java.util.stream.StreamSupport.stream(listedExpirations.spliterator(), false)
                .map(JsonNode::asText).filter(value -> value.compareTo(currentExpiration) > 0)
                .findFirst().orElseThrow();
        ObjectNode replacement = replacementPackage(candidate, laterExpiration, "bullish");
        JsonNode closed = applyTransformation(opened.at("/trade/id").asText(), tradePlanId,
                openVersion, "ROLL", replacement);
        assertThat(closed.at("/plan/status").asText()).isEqualTo("POSITION_OPEN");
        assertThat(closed.at("/plan/furthestStage").asText()).isEqualTo("MANAGE_REVIEW");
        assertThat(closed.at("/management/actions/0/kind").asText()).isEqualTo("ROLL");
        assertThat(closed.at("/management/links").toString()).contains("ENTRY").contains("ROLL");
        assertThat(closed.at("/management/reviews/0/category").asText()).isEqualTo("TRADE_DECISION");
        assertThat(closed.at("/management/reviews/0/benchmarkKind").asText()).isEqualTo("PLAN_POSITION");
        assertThat(closed.at("/trade/status").asText()).isEqualTo("ACTIVE");
        assertThat(closed.at("/trade/legs/0/expiration").asText()).isEqualTo(laterExpiration);
        assertThat(closed.get("receiptId").asText()).isNotBlank();

        JsonNode cashPlan = json(post("/api/plans", """
                {"clientRequestId":"decision-cash-plan","symbol":"QQQ","intent":"INCOME","title":"Cash decision plan",
                 "thesis":"neutral","horizonDays":30,"riskMode":"conservative"}
                """));
        String cashPlanId = cashPlan.get("id").asText();
        JsonNode cashField = json(post("/api/plans/" + cashPlanId + "/strategy/run", "{}"));
        JsonNode cashCandidate = cashField.at("/strategy/result/candidates/0");
        JsonNode cashSelected = json(put("/api/plans/" + cashPlanId + "/strategy/select",
                "{\"candidateId\":\"" + cashCandidate.get("id").asText() + "\",\"expectedVersion\":"
                        + cashField.at("/plan/version").asLong() + "}"));
        JsonNode cash = json(post("/api/plans/" + cashPlanId + "/decision/cash",
                "{\"expectedVersion\":" + cashSelected.at("/plan/version").asLong()
                        + ",\"qty\":1,\"note\":\"Costs outweighed the modeled edge\"}"));
        assertThat(cash.at("/plan/status").asText()).isEqualTo("DECIDED_CASH");
        assertThat(cash.at("/decision/action").asText()).isEqualTo("CASH");
        assertThat(cash.at("/decision/tradeId").isMissingNode()).isTrue();
        assertThat(cash.at("/decision/metrics/decisionNote").asText())
                .isEqualTo("Costs outweighed the modeled edge");
        assertThat(cash.at("/decision/metrics/decisionQty").asDouble()).isEqualTo(1.0);
        assertThat(cash.at("/decision/metrics/riskFreeRateAnnual").isNumber()).isTrue();

        String frozenStudy = """
                {"key":"pullback_rebound","symbol":"QQQ","from":"2023-01-01","to":"2026-07-10",
                 "params":{"lookback":20,"dropPct":5,"forward":30,"eventSpacing":10,
                 "minSample":5,"confidencePct":95,"bootstrapSamples":200,
                 "regime":"ALL","multiplicity":"CATALOG_BONFERRONI","splitHalf":true}}
                """;
        for (HttpResponse<String> rejected : java.util.List.of(
                post("/api/plans/" + cashPlanId + "/evidence/study", frozenStudy),
                post("/api/plans/" + cashPlanId + "/strategy/run", "{}"),
                post("/api/plans/" + cashPlanId + "/outcomes/ensemble",
                        "{\"expectedVersion\":" + cash.at("/plan/version").asLong() + "}"))) {
            assertThat(rejected.statusCode()).isEqualTo(409);
            assertThat(Json.parse(rejected.body()).path("detail").asText())
                    .contains("decision is frozen").contains("linked Plan");
        }
    }

    @Test void trackedPositionAdoptionIsRefusedOutsideObservedAtTheApiBoundary() throws Exception {
        JsonNode account = json(post("/api/portfolio/accounts", """
                {"name":"Adoption brokerage","accountType":"TAXABLE","broker":"Example","openingCashCents":9000000}
                """));
        String accountId = account.get("id").asText();
        assertThat(post("/api/portfolio/accounts/" + accountId + "/transactions", """
                {"occurredAt":"2026-07-10T15:00:00Z","eventType":"TRADE","source":"MANUAL","fillNature":"EXECUTED",
                 "legs":[{"instrumentType":"STOCK","action":"BUY","positionEffect":"OPEN","symbol":"AAPL",
                          "quantity":200,"multiplier":1,"price":"250.00"}]}
                """).statusCode()).isEqualTo(201);
        JsonNode lots = json(get("/api/portfolio/accounts/" + accountId + "/lots"));
        JsonNode lot = lots.withArray("lots").get(0);
        String lotId = lot.get("id").asText();

        var request = Json.MAPPER.createObjectNode();
        request.put("clientRequestId", "adopt-aapl-shares-1");
        request.put("portfolioAccountId", accountId);
        request.put("symbol", "AAPL");
        request.put("label", "Adopted AAPL shares");
        var allocations = request.putArray("allocations");
        allocations.addObject().put("lotId", lotId);

        int plansBefore = inspectDb.query("SELECT COUNT(*) n FROM plans WHERE custom_title=?",
                row -> row.intv("n"), "Adopted AAPL shares").getFirst();
        int receiptsBefore = inspectDb.query("SELECT COUNT(*) n FROM position_receipt WHERE kind='ADOPTION'",
                row -> row.intv("n")).getFirst();
        HttpResponse<String> rejected = post("/api/plans/adopt", request.toString());
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(Json.parse(rejected.body()).path("detail").asText())
                .contains("real tracked position").contains("Observed market");
        assertThat(inspectDb.query("SELECT COUNT(*) n FROM plans WHERE custom_title=?",
                row -> row.intv("n"), "Adopted AAPL shares")).containsExactly(plansBefore);
        assertThat(inspectDb.query("SELECT COUNT(*) n FROM position_receipt WHERE kind='ADOPTION'",
                row -> row.intv("n"))).containsExactly(receiptsBefore);
    }

    @Test void brokerDecisionPromotesThePlanIntoTheTrackedBookAtomically() throws Exception {
        JsonNode account = json(post("/api/portfolio/accounts", """
                {"name":"Real brokerage","accountType":"TAXABLE","broker":"Example Broker","openingCashCents":5000000}
                """));
        String accountId = account.get("id").asText();

        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"decision-broker-plan","symbol":"AAPL","intent":"DIRECTIONAL","title":"Broker decision plan",
                 "thesis":"bullish","horizonDays":30,"riskMode":"conservative"}
                """));
        String planId = plan.get("id").asText();
        JsonNode field = json(post("/api/plans/" + planId + "/strategy/run", "{}"));
        JsonNode candidate = null;
        for (JsonNode item : field.at("/strategy/result/candidates")) {
            java.util.Set<String> expirations = new java.util.HashSet<>();
            for (JsonNode leg : item.withArray("legs")) if (!"STOCK".equals(leg.path("type").asText())) {
                expirations.add(leg.path("expiration").asText());
            }
            if (expirations.size() == 1) { candidate = item; break; }
        }
        assertThat(candidate).as("fixture field has a same-expiry package").isNotNull();
        JsonNode selected = json(put("/api/plans/" + planId + "/strategy/select",
                "{\"candidateId\":\"" + candidate.get("id").asText() + "\",\"expectedVersion\":"
                        + field.at("/plan/version").asLong() + "}"));
        long version = selected.at("/plan/version").asLong();
        JsonNode preview = json(post("/api/plans/" + planId + "/decision/preview",
                "{\"expectedVersion\":" + version + ",\"qty\":1}"));

        var request = Json.MAPPER.createObjectNode();
        request.put("expectedVersion", version);
        request.put("qty", 1);
        request.put("proposedNetCents", preview.at("/order/proposedNetCents").asLong());
        request.put("portfolioAccountId", accountId);
        request.put("externalRef", "broker-order-77");
        request.put("feesCents", 130);
        if (preview.has("ackToken")) request.put("ackToken", preview.get("ackToken").asText());
        var acknowledgments = request.putArray("acknowledgedRisks");
        for (JsonNode ack : preview.withArray("requiredAcks")) acknowledgments.add(ack.get("id").asText());
        var fills = request.putArray("fills");
        JsonNode previewLegs = preview.at("/preview/legs");
        for (int index = 0; index < candidate.withArray("legs").size(); index++) {
            JsonNode previewLeg = previewLegs.path(index);
            String price = previewLeg.hasNonNull("mid") ? previewLeg.get("mid").asText()
                    : candidate.at("/legs/" + index + "/entryPrice").asText();
            var fill = fills.addObject();
            fill.put("legIndex", index);
            fill.put("fillPrice", price);
        }

        // Atomicity: a fill the tracked book rejects (inside the transaction, after the decision
        // freeze already executed) rolls the frozen decision back with it.
        var poisoned = request.deepCopy();
        ((ObjectNode) poisoned.withArray("fills").get(0)).put("fillPrice", "-1");
        HttpResponse<String> rejected = post("/api/plans/" + planId + "/decision/broker", poisoned.toString());
        assertThat(rejected.statusCode()).isBetween(400, 499);
        assertThat(rejected.body()).contains("price cannot be negative");
        JsonNode afterRollback = json(get("/api/plans/" + planId + "/decision/latest"));
        assertThat(afterRollback.at("/decision").isMissingNode())
                .as("no partially frozen decision survives").isTrue();
        assertThat(afterRollback.at("/plan/status").asText()).isEqualTo("ACTIVE");
        assertThat(afterRollback.at("/plan/version").asLong()).isEqualTo(version);

        JsonNode placed = json(post("/api/plans/" + planId + "/decision/broker", request.toString()));
        assertThat(placed.at("/plan/status").asText()).isEqualTo("POSITION_OPEN");
        assertThat(placed.at("/plan/furthestStage").asText()).isEqualTo("MANAGE_REVIEW");
        assertThat(placed.at("/decision/action").asText()).isEqualTo("BROKER");
        assertThat(placed.at("/decision/qty").asInt()).isEqualTo(1);
        assertThat(placed.at("/decision/tradeId").isMissingNode())
                .as("a broker placement links through artifacts, never a Practice trade").isTrue();
        assertThat(placed.at("/transaction/source").asText()).isEqualTo("BROKER");
        assertThat(placed.at("/transaction/externalRef").asText()).isEqualTo("broker-order-77");
        assertThat(placed.at("/transaction/feesCents").asLong()).isEqualTo(130L);
        assertThat(placed.at("/transaction/legs")).hasSize(candidate.withArray("legs").size());
        assertThat(placed.get("structureId").asText()).isNotBlank();
        assertThat(placed.get("receiptId").asText()).isNotBlank();

        JsonNode manage = json(get("/api/plans/" + planId + "/manage"));
        assertThat(manage.at("/management/activeTradeId").isMissingNode()).isTrue();
        JsonNode tracked = manage.at("/management/trackedStructure");
        assertThat(tracked.path("structureId").asText()).isEqualTo(placed.get("structureId").asText());
        assertThat(tracked.path("accountName").asText()).isEqualTo("Real brokerage");
        assertThat(tracked.path("positionState").asText()).isEqualTo("OPEN");
        assertThat(tracked.path("role").asText()).isEqualTo("ENTRY");
        assertThat(tracked.path("openQuantityRemaining").asLong()).isGreaterThan(0);
        assertThat(tracked.path("legs")).hasSize(candidate.withArray("legs").size());
        for (JsonNode leg : tracked.path("legs")) {
            assertThat(leg.hasNonNull("fillPrice")).as("broker-reported fills are on the receipt").isTrue();
        }

        // The frozen plan refuses another decision; the tracked book refuses the duplicate reference.
        assertThat(post("/api/plans/" + planId + "/decision/broker", request.toString()).statusCode())
                .isBetween(400, 499);
    }

    @Test void planPartialCloseKeepsThePositionOpenAndRecordsOneAtomicManagementAction() throws Exception {
        assertThat(put("/api/account/risk-context", """
                {"nlvCents":10000000,"cashBpCents":10000000,"riskCapitalCents":1000000}
                """).statusCode()).isBetween(200, 299);
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"plan-partial-close","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"Partial close lifecycle","thesis":"bullish","horizonDays":30,
                 "riskMode":"conservative"}
                """));
        String planId = plan.get("id").asText();
        JsonNode field = json(post("/api/plans/" + planId + "/strategy/run", "{}"));
        JsonNode candidate = null;
        for (JsonNode item : field.at("/strategy/result/candidates")) {
            java.util.Set<String> expirations = new java.util.HashSet<>();
            for (JsonNode leg : item.withArray("legs")) if (!"STOCK".equals(leg.path("type").asText())) {
                expirations.add(leg.path("expiration").asText());
            }
            if (expirations.size() == 1) { candidate = item; break; }
        }
        assertThat(candidate).isNotNull();
        JsonNode selected = json(put("/api/plans/" + planId + "/strategy/select",
                "{\"candidateId\":\"" + candidate.get("id").asText() + "\",\"expectedVersion\":"
                        + field.at("/plan/version").asLong() + "}"));
        long version = selected.at("/plan/version").asLong();
        JsonNode preview = json(post("/api/plans/" + planId + "/decision/preview",
                "{\"expectedVersion\":" + version + ",\"qty\":3}"));
        var order = Json.MAPPER.createObjectNode();
        order.put("expectedVersion", version);
        order.put("qty", 3);
        order.put("proposedNetCents", preview.at("/order/proposedNetCents").asLong());
        if (preview.has("ackToken")) order.put("ackToken", preview.get("ackToken").asText());
        var acknowledgments = order.putArray("acknowledgedRisks");
        preview.withArray("requiredAcks").forEach(ack -> acknowledgments.add(ack.get("id").asText()));
        JsonNode opened = json(post("/api/plans/" + planId + "/decision/trade", order.toString()));
        String tradeId = opened.at("/trade/id").asText();
        long openVersion = opened.at("/plan/version").asLong();

        var partial = Json.MAPPER.createObjectNode();
        partial.put("source", "PRACTICE_TRADE");
        partial.put("sourceId", tradeId);
        partial.put("planId", planId);
        partial.put("expectedPlanVersion", openVersion);
        partial.put("action", "PARTIAL_CLOSE");
        partial.put("closeQuantity", 1);
        JsonNode partialPreview = json(post("/api/position-transformations/preview", partial.toString()));
        partial.put("previewToken", partialPreview.get("previewToken").asText());
        JsonNode applied = json(post("/api/position-transformations/apply", partial.toString()));

        assertThat(applied.at("/trade/id").asText()).isEqualTo(tradeId);
        assertThat(applied.at("/trade/qty").asInt()).isEqualTo(2);
        assertThat(applied.at("/trade/status").asText()).isEqualTo("ACTIVE");
        assertThat(applied.at("/plan/status").asText()).isEqualTo("POSITION_OPEN");
        assertThat(applied.at("/plan/furthestStage").asText()).isEqualTo("MANAGE_REVIEW");
        assertThat(applied.at("/plan/version").asLong()).isEqualTo(openVersion + 1);
        assertThat(applied.at("/management/actions/0/kind").asText()).isEqualTo("PARTIAL_CLOSE");
        assertThat(applied.at("/management/links").toString()).contains("PARTIAL_CLOSE");
        assertThat(applied.at("/management/reviews").isEmpty()).isTrue();
        String receiptId = applied.get("receiptId").asText();
        assertThat(inspectDb.query("SELECT plan_id || '|' || position_state || '|' || transformation_action v "
                        + "FROM position_receipt WHERE id=?", row -> row.str("v"), receiptId))
                .containsExactly(planId + "|PARTIALLY_CLOSED|PARTIAL_CLOSE");
        assertThat(inspectDb.query("SELECT realized_cents FROM plan_management_action "
                        + "WHERE plan_id=? AND receipt_id=?", row -> row.lng("realized_cents"), planId, receiptId))
                .containsExactly(applied.get("actionRealizedPnlCents").asLong());

        long partialAction = applied.get("actionRealizedPnlCents").asLong();
        var close = Json.MAPPER.createObjectNode();
        close.put("source", "PRACTICE_TRADE");
        close.put("sourceId", tradeId);
        close.put("planId", planId);
        close.put("expectedPlanVersion", applied.at("/plan/version").asLong());
        close.put("action", "CLOSE");
        JsonNode closePreview = json(post("/api/position-transformations/preview", close.toString()));
        long closeAction = closePreview.get("actionRealizedPnlCents").asLong();
        long lifetimeRealized = closePreview.get("realizedPnlToDateCents").asLong();
        assertThat(lifetimeRealized).isEqualTo(partialAction + closeAction);
        close.put("previewToken", closePreview.get("previewToken").asText());
        JsonNode closed = json(post("/api/position-transformations/apply", close.toString()));
        String closeReceiptId = closed.get("receiptId").asText();

        assertThat(closed.at("/plan/status").asText()).isEqualTo("CLOSED");
        assertThat(inspectDb.query("SELECT realized_cents FROM plan_management_action "
                        + "WHERE plan_id=? AND receipt_id=?", row -> row.lng("realized_cents"),
                planId, closeReceiptId)).containsExactly(closeAction);
        assertThat(inspectDb.query("SELECT realized_cents FROM plan_review WHERE plan_id=?",
                row -> row.lng("realized_cents"), planId)).containsExactly(lifetimeRealized);
        assertThat(inspectDb.query("SELECT SUM(realized_cents) n FROM plan_management_action "
                        + "WHERE plan_id=? AND kind IN ('PARTIAL_CLOSE','CLOSE')", row -> row.lng("n"), planId))
                .containsExactly(lifetimeRealized);
    }

    @Test void planLegAdjustmentWritesOneReceiptActionLinkAndVersionAtomically() throws Exception {
        assertThat(put("/api/account/risk-context", """
                {"nlvCents":10000000,"cashBpCents":10000000,"riskCapitalCents":1000000}
                """).statusCode()).isBetween(200, 299);
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"plan-leg-adjustment","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"Leg adjustment lifecycle","thesis":"bullish","horizonDays":30,
                 "riskMode":"conservative"}
                """));
        String planId = plan.get("id").asText();
        String expiration = json(get("/api/research/AAPL/expirations")).at("/expirations/2").asText();
        var custom = Json.MAPPER.createObjectNode();
        custom.put("expectedVersion", plan.get("version").asLong());
        custom.set("position", Json.parse("""
                {"symbol":"AAPL","strategy":"DEBIT_CALL_SPREAD","qty":1,
                 "fillNature":"PROPOSED","legs":[
                   {"action":"BUY","type":"CALL","strike":"255","expiration":"%s","ratio":1,
                    "multiplier":100,"positionEffect":"OPEN"},
                   {"action":"SELL","type":"CALL","strike":"260","expiration":"%s","ratio":1,
                    "multiplier":100,"positionEffect":"OPEN"}]}
                """.formatted(expiration, expiration)));
        JsonNode selected = json(post("/api/plans/" + planId + "/strategy/custom", custom.toString()));
        long selectedVersion = selected.at("/plan/version").asLong();
        JsonNode decisionPreview = json(post("/api/plans/" + planId + "/decision/preview",
                "{\"expectedVersion\":" + selectedVersion + ",\"qty\":1}"));
        var order = Json.MAPPER.createObjectNode();
        order.put("expectedVersion", selectedVersion);
        order.put("qty", 1);
        order.put("proposedNetCents", decisionPreview.at("/order/proposedNetCents").asLong());
        if (decisionPreview.has("ackToken")) order.put("ackToken", decisionPreview.get("ackToken").asText());
        var openingAcks = order.putArray("acknowledgedRisks");
        decisionPreview.withArray("requiredAcks").forEach(ack -> openingAcks.add(ack.get("id").asText()));
        JsonNode opened = json(post("/api/plans/" + planId + "/decision/trade", order.toString()));
        String tradeId = opened.at("/trade/id").asText();
        long openVersion = opened.at("/plan/version").asLong();

        var request = Json.MAPPER.createObjectNode();
        request.put("source", "PRACTICE_TRADE");
        request.put("sourceId", tradeId);
        request.put("planId", planId);
        request.put("expectedPlanVersion", openVersion);
        request.put("action", "REMOVE_LEG");
        request.set("after", Json.parse("""
                {"symbol":"AAPL","strategy":"CUSTOM","qty":1,"thesis":"bullish",
                 "horizon":"month","riskMode":"conservative","source":"POSITION_TRANSFORMATION",
                 "fillNature":"PROPOSED","legs":[
                   {"action":"BUY","type":"CALL","strike":"255","expiration":"%s","ratio":1,
                    "multiplier":100,"positionEffect":"OPEN"}]}
                """.formatted(expiration)));
        JsonNode adjustmentPreview = json(post("/api/position-transformations/preview", request.toString()));
        if (!adjustmentPreview.at("/after/requiredAcks").isEmpty()) {
            var after = (ObjectNode) request.get("after");
            var acks = after.putArray("acknowledgedRisks");
            adjustmentPreview.at("/after/requiredAcks").forEach(ack -> acks.add(ack.get("id").asText()));
            after.put("ackToken", adjustmentPreview.at("/after/ackToken").asText());
        }
        request.put("previewToken", adjustmentPreview.get("previewToken").asText());
        JsonNode applied = json(post("/api/position-transformations/apply", request.toString()));
        String receiptId = applied.get("receiptId").asText();

        assertThat(applied.at("/trade/id").asText()).isEqualTo(tradeId);
        assertThat(applied.at("/trade/legs").size()).isEqualTo(1);
        assertThat(applied.at("/plan/status").asText()).isEqualTo("POSITION_OPEN");
        assertThat(applied.at("/plan/version").asLong()).isEqualTo(openVersion + 1);
        assertThat(applied.at("/management/actions/0/kind").asText()).isEqualTo("ADJUST");
        assertThat(inspectDb.query("SELECT COUNT(*) n FROM position_receipt WHERE id=? AND plan_id=? "
                        + "AND transformation_action='REMOVE_LEG'", row -> row.lng("n"), receiptId, planId))
                .containsExactly(1L);
        assertThat(inspectDb.query("SELECT COUNT(*) n FROM plan_management_action WHERE plan_id=? "
                        + "AND receipt_id=? AND kind='ADJUST'", row -> row.lng("n"), planId, receiptId))
                .containsExactly(1L);
        assertThat(inspectDb.query("SELECT COUNT(*) n FROM plan_link WHERE plan_id=? AND trade_id=? "
                        + "AND role='ADJUST'", row -> row.lng("n"), planId, tradeId))
                .containsExactly(1L);
    }

    @Test void planAssignmentKeepsTheHedgeAndWritesOneAtomicLifecycleSet() throws Exception {
        assertThat(put("/api/account/risk-context", """
                {"nlvCents":10000000,"cashBpCents":10000000,"riskCapitalCents":1000000}
                """).statusCode()).isBetween(200, 299);
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"plan-assignment-lifecycle","symbol":"AAPL","intent":"ACQUIRE",
                 "title":"Assignment with hedge","thesis":"bullish","horizonDays":30,
                 "riskMode":"conservative"}
                """));
        String planId = plan.get("id").asText();
        String expiration = json(get("/api/research/AAPL/expirations")).at("/expirations/2").asText();
        var custom = Json.MAPPER.createObjectNode();
        custom.put("expectedVersion", plan.get("version").asLong());
        custom.set("position", Json.parse("""
                {"symbol":"AAPL","strategy":"CREDIT_PUT_SPREAD","qty":1,
                 "fillNature":"PROPOSED","legs":[
                   {"action":"SELL","type":"PUT","strike":"260","expiration":"%s","ratio":1,
                    "multiplier":100,"positionEffect":"OPEN"},
                   {"action":"BUY","type":"PUT","strike":"250","expiration":"%s","ratio":1,
                    "multiplier":100,"positionEffect":"OPEN"}]}
                """.formatted(expiration, expiration)));
        JsonNode selected = json(post("/api/plans/" + planId + "/strategy/custom", custom.toString()));
        long selectedVersion = selected.at("/plan/version").asLong();
        JsonNode decisionPreview = json(post("/api/plans/" + planId + "/decision/preview",
                "{\"expectedVersion\":" + selectedVersion + ",\"qty\":1}"));
        var order = Json.MAPPER.createObjectNode();
        order.put("expectedVersion", selectedVersion);
        order.put("qty", 1);
        order.put("proposedNetCents", decisionPreview.at("/order/proposedNetCents").asLong());
        if (decisionPreview.has("ackToken")) order.put("ackToken", decisionPreview.get("ackToken").asText());
        var openingAcks = order.putArray("acknowledgedRisks");
        decisionPreview.withArray("requiredAcks").forEach(ack -> openingAcks.add(ack.get("id").asText()));
        JsonNode opened = json(post("/api/plans/" + planId + "/decision/trade", order.toString()));
        String tradeId = opened.at("/trade/id").asText();
        long openVersion = opened.at("/plan/version").asLong();

        var request = Json.MAPPER.createObjectNode();
        request.put("source", "PRACTICE_TRADE");
        request.put("sourceId", tradeId);
        request.put("planId", planId);
        request.put("expectedPlanVersion", openVersion);
        request.put("action", "ASSIGNMENT");
        request.put("legIndex", 0);
        JsonNode lifecyclePreview = json(post("/api/position-transformations/preview", request.toString()));
        assertThat(lifecyclePreview.at("/transformation/afterIdentity/family").asText())
                .isEqualTo("PROTECTIVE_PUT");
        request.put("previewToken", lifecyclePreview.get("previewToken").asText());
        JsonNode applied = json(post("/api/position-transformations/apply", request.toString()));
        String receiptId = applied.get("receiptId").asText();

        assertThat(applied.at("/trade/id").asText()).isEqualTo(tradeId);
        assertThat(applied.at("/trade/status").asText()).isEqualTo("ACTIVE");
        assertThat(applied.at("/trade/strategy").asText()).isEqualTo("PROTECTIVE_PUT");
        assertThat(applied.at("/plan/status").asText()).isEqualTo("POSITION_OPEN");
        assertThat(applied.at("/plan/version").asLong()).isEqualTo(openVersion + 1);
        assertThat(applied.at("/management/activeTradeId").asText()).isEqualTo(tradeId);
        assertThat(applied.at("/management/actions/0/kind").asText()).isEqualTo("ASSIGNMENT");
        assertThat(inspectDb.query("SELECT COUNT(*) n FROM position_receipt WHERE id=? AND plan_id=? "
                        + "AND position_state='ASSIGNED' AND transformation_action='ASSIGNMENT'",
                row -> row.lng("n"), receiptId, planId)).containsExactly(1L);
        assertThat(inspectDb.query("SELECT COUNT(*) n FROM plan_management_action WHERE plan_id=? "
                        + "AND receipt_id=? AND kind='ASSIGNMENT'",
                row -> row.lng("n"), planId, receiptId)).containsExactly(1L);
        assertThat(inspectDb.query("SELECT COUNT(*) n FROM plan_link WHERE plan_id=? AND trade_id=? "
                        + "AND role='ASSIGNMENT'", row -> row.lng("n"), planId, tradeId))
                .containsExactly(1L);
        assertThat(inspectDb.query("SELECT COUNT(*) n FROM ledger WHERE trade_id=? "
                        + "AND type IN ('SETTLEMENT','STOCK_BUY')", row -> row.lng("n"), tradeId))
                .containsExactly(2L);
    }

    @Test void planAssignmentToSharesUsesTheFrozenReceiptAsItsCurrentManagePosition() throws Exception {
        assertThat(put("/api/account/risk-context", """
                {"nlvCents":10000000,"cashBpCents":10000000,"riskCapitalCents":1000000}
                """).statusCode()).isBetween(200, 299);
        long sharesBefore = 0;
        for (JsonNode position : json(get("/api/positions")).withArray("positions")) {
            if ("AAPL".equals(position.path("symbol").asText())) sharesBefore = position.path("shares").asLong();
        }
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"plan-assignment-shares","symbol":"AAPL","intent":"ACQUIRE",
                 "title":"Acquire shares through assignment","thesis":"bullish","horizonDays":30,
                 "riskMode":"conservative"}
                """));
        String planId = plan.get("id").asText();
        String expiration = json(get("/api/research/AAPL/expirations")).at("/expirations/2").asText();
        var custom = Json.MAPPER.createObjectNode();
        custom.put("expectedVersion", plan.get("version").asLong());
        custom.set("position", Json.parse("""
                {"symbol":"AAPL","strategy":"CASH_SECURED_PUT","qty":1,
                 "fillNature":"PROPOSED","legs":[
                   {"action":"SELL","type":"PUT","strike":"260","expiration":"%s","ratio":1,
                    "multiplier":100,"positionEffect":"OPEN"}]}
                """.formatted(expiration)));
        JsonNode selected = json(post("/api/plans/" + planId + "/strategy/custom", custom.toString()));
        long selectedVersion = selected.at("/plan/version").asLong();
        JsonNode decisionPreview = json(post("/api/plans/" + planId + "/decision/preview",
                "{\"expectedVersion\":" + selectedVersion + ",\"qty\":1}"));
        var order = Json.MAPPER.createObjectNode();
        order.put("expectedVersion", selectedVersion);
        order.put("qty", 1);
        order.put("proposedNetCents", decisionPreview.at("/order/proposedNetCents").asLong());
        if (decisionPreview.has("ackToken")) order.put("ackToken", decisionPreview.get("ackToken").asText());
        var openingAcks = order.putArray("acknowledgedRisks");
        decisionPreview.withArray("requiredAcks").forEach(ack -> openingAcks.add(ack.get("id").asText()));
        JsonNode opened = json(post("/api/plans/" + planId + "/decision/trade", order.toString()));
        String tradeId = opened.at("/trade/id").asText();
        long openVersion = opened.at("/plan/version").asLong();

        var request = Json.MAPPER.createObjectNode();
        request.put("source", "PRACTICE_TRADE");
        request.put("sourceId", tradeId);
        request.put("planId", planId);
        request.put("expectedPlanVersion", openVersion);
        request.put("action", "ASSIGNMENT");
        request.put("legIndex", 0);
        JsonNode preview = json(post("/api/position-transformations/preview", request.toString()));
        assertThat(preview.at("/transformation/afterIdentity/label").asText()).isEqualTo("Long shares");
        request.put("previewToken", preview.get("previewToken").asText());
        JsonNode applied = json(post("/api/position-transformations/apply", request.toString()));

        assertThat(applied.at("/trade/status").asText()).isEqualTo("EXPIRED");
        assertThat(applied.at("/plan/status").asText()).isEqualTo("POSITION_OPEN");
        assertThat(applied.at("/management/activeTradeId").isMissingNode()
                || applied.at("/management/activeTradeId").isNull()).isTrue();
        assertThat(applied.at("/management/currentPosition/identity").asText()).isEqualTo("Long shares");
        assertThat(applied.at("/management/currentPosition/holdingShares").asLong()).isEqualTo(sharesBefore + 100);
        assertThat(applied.at("/management/currentPosition/legs").size()).isEqualTo(1);
        assertThat(applied.at("/management/currentPosition/legs/0/instrumentType").asText()).isEqualTo("STOCK");

        JsonNode manage = json(get("/api/plans/" + planId + "/manage"));
        assertThat(manage.has("trade") && !manage.get("trade").isNull()).isFalse();
        assertThat(manage.at("/management/currentPosition/identity").asText()).isEqualTo("Long shares");
        assertThat(manage.at("/management/currentPosition/legs/0/quantity").asLong()).isEqualTo(100);
    }

    @Test void voidingAPlanTradeIsRecordedAsVoidRatherThanAnOrdinaryClose() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"decision-void-plan","symbol":"AAPL","intent":"DIRECTIONAL",
                 "title":"Void semantics plan","thesis":"bearish","horizonDays":21,"riskMode":"conservative"}
                """));
        String planId = plan.get("id").asText();
        JsonNode field = json(post("/api/plans/" + planId + "/strategy/run", "{}"));
        JsonNode candidate = null;
        for (JsonNode item : field.at("/strategy/result/candidates")) {
            java.util.Set<String> expirations = new java.util.HashSet<>();
            for (JsonNode leg : item.withArray("legs")) if (!"STOCK".equals(leg.path("type").asText())) {
                expirations.add(leg.path("expiration").asText());
            }
            if (expirations.size() == 1) { candidate = item; break; }
        }
        assertThat(candidate).isNotNull();
        JsonNode selected = json(put("/api/plans/" + planId + "/strategy/select",
                "{\"candidateId\":\"" + candidate.get("id").asText() + "\",\"expectedVersion\":"
                        + field.at("/plan/version").asLong() + "}"));
        long version = selected.at("/plan/version").asLong();
        JsonNode preview = json(post("/api/plans/" + planId + "/decision/preview",
                "{\"expectedVersion\":" + version + ",\"qty\":1}"));
        var order = Json.MAPPER.createObjectNode();
        order.put("expectedVersion", version);
        order.put("qty", 1);
        order.put("proposedNetCents", preview.at("/order/proposedNetCents").asLong());
        if (preview.has("ackToken")) order.put("ackToken", preview.get("ackToken").asText());
        var acks = order.putArray("acknowledgedRisks");
        for (JsonNode ack : preview.withArray("requiredAcks")) acks.add(ack.get("id").asText());
        JsonNode opened = json(post("/api/plans/" + planId + "/decision/trade", order.toString()));

        JsonNode voided = applyTransformation(opened.at("/trade/id").asText(), planId,
                opened.at("/plan/version").asLong(), "VOID", null);
        assertThat(voided.at("/plan/status").asText()).isEqualTo("CLOSED");
        assertThat(voided.at("/trade/status").asText()).isEqualTo("DELETED");
        assertThat(voided.at("/management/actions/0/kind").asText()).isEqualTo("VOID");
        assertThat(voided.at("/management/actions/0/note").asText()).contains("voided");
        assertThat(voided.at("/management/reviews")).isEmpty();
    }

    private static ObjectNode replacementPackage(JsonNode candidate, String expiration, String thesis) {
        ObjectNode position = Json.MAPPER.createObjectNode();
        position.put("symbol", "AAPL");
        position.put("strategy", candidate.get("strategy").asText());
        position.put("qty", 1);
        position.put("intent", candidate.path("intent").asText("DIRECTIONAL"));
        position.put("thesis", thesis);
        position.put("horizon", "month");
        position.put("riskMode", "conservative");
        position.put("source", "POSITION_TRANSFORMATION_PLAN_TEST");
        position.put("fillNature", "PROPOSED");
        var legs = position.putArray("legs");
        for (JsonNode source : candidate.withArray("legs")) {
            ObjectNode leg = legs.addObject();
            leg.put("action", source.get("action").asText());
            leg.put("type", source.get("type").asText());
            if (!"STOCK".equals(source.get("type").asText())) {
                leg.put("strike", source.get("strike").asText());
                leg.put("expiration", expiration);
            }
            leg.put("ratio", source.path("ratio").asInt(1));
            leg.put("multiplier", source.path("multiplier").asInt(100));
            leg.put("positionEffect", "OPEN");
        }
        return position;
    }

    private static JsonNode applyTransformation(String tradeId, String planId, long expectedVersion,
                                                String action, ObjectNode after) throws Exception {
        ObjectNode request = Json.MAPPER.createObjectNode();
        request.put("source", "PRACTICE_TRADE");
        request.put("sourceId", tradeId);
        request.put("planId", planId);
        request.put("expectedPlanVersion", expectedVersion);
        request.put("action", action);
        if (after != null) request.set("after", after);
        JsonNode preview = json(post("/api/position-transformations/preview", request.toString()));
        JsonNode required = preview.at("/after/requiredAcks");
        if (after != null && required.isArray() && !required.isEmpty()) {
            var acknowledgments = after.putArray("acknowledgedRisks");
            required.forEach(value -> acknowledgments.add(value.get("id").asText()));
            after.put("ackToken", preview.at("/after/ackToken").asText());
        }
        request.put("previewToken", preview.get("previewToken").asText());
        return json(post("/api/position-transformations/apply", request.toString()));
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

    private static HttpResponse<String> delete(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isBetween(200, 299);
        return Json.parse(response.body());
    }

    private static void assertJsonEquivalent(JsonNode actual, JsonNode expected) {
        assertJsonEquivalent(actual, expected, "$ ");
    }

    private static void assertJsonEquivalent(JsonNode actual, JsonNode expected, String path) {
        assertThat(actual.getNodeType()).as(path + "node type").isEqualTo(expected.getNodeType());
        if (expected.isNumber()) {
            assertThat(actual.decimalValue()).as(path).isEqualByComparingTo(expected.decimalValue());
            return;
        }
        if (expected.isObject()) {
            java.util.Set<String> actualFields = new java.util.TreeSet<>();
            actual.fieldNames().forEachRemaining(actualFields::add);
            java.util.Set<String> expectedFields = new java.util.TreeSet<>();
            expected.fieldNames().forEachRemaining(expectedFields::add);
            assertThat(actualFields).as(path + "fields").isEqualTo(expectedFields);
            for (String field : expectedFields) {
                assertJsonEquivalent(actual.get(field), expected.get(field), path + "." + field);
            }
            return;
        }
        if (expected.isArray()) {
            assertThat(actual.size()).as(path + "array size").isEqualTo(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                assertJsonEquivalent(actual.get(i), expected.get(i), path + "[" + i + "]");
            }
            return;
        }
        assertThat(actual).as(path).isEqualTo(expected);
    }
}
