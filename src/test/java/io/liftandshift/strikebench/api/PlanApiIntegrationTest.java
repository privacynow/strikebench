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

        HttpResponse<String> locked = put("/api/plans/" + id + "/stage",
                "{\"expectedVersion\":" + updated.get("version").asLong() + ",\"stage\":\"manage_review\"}");
        assertThat(locked.statusCode()).isEqualTo(409);

        JsonNode closedChip = json(put("/api/plans/" + duplicate.get("id").asText() + "/open",
                "{\"expectedVersion\":" + duplicate.get("version").asLong() + ",\"open\":false}"));
        assertThat(closedChip.get("open").asBoolean()).isFalse();
        assertThat(json(get("/api/plans")).get("plans")).hasSize(baselinePlans + 1);
        JsonNode fullLibrary = json(get("/api/plans?scope=all&openOnly=false"));
        JsonNode retained = java.util.stream.StreamSupport.stream(fullLibrary.get("plans").spliterator(), false)
                .filter(p -> duplicate.get("id").asText().equals(p.get("id").asText())).findFirst().orElseThrow();
        assertThat(retained.get("open").asBoolean()).isFalse();

        JsonNode archived = json(post("/api/plans/" + duplicate.get("id").asText() + "/archive",
                "{\"expectedVersion\":" + closedChip.get("version").asLong() + "}"));
        HttpResponse<String> reopenArchived = put("/api/plans/" + duplicate.get("id").asText() + "/open",
                "{\"expectedVersion\":" + archived.get("version").asLong() + ",\"open\":true}");
        assertThat(reopenArchived.statusCode()).isEqualTo(409);
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

    @Test void finishingASimulatedMarketClosesItsPlanCollectionAtomically() throws Exception {
        JsonNode world = json(post("/api/sim/market", """
                {"name":"Plan collection terminal","symbols":{"AAPL":1.0},"scenario":"CHOP","speed":26}
                """));
        String worldId = world.get("worldId").asText();
        assertThat(put("/api/world", "{\"world\":\"" + worldId + "\"}").statusCode()).isEqualTo(200);
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"terminal-world-plan","symbol":"AAPL","intent":"DIRECTIONAL",
                 "thesis":"bullish","horizonDays":20,"riskMode":"conservative"}
                """));
        assertThat(plan.get("marketKind").asText()).isEqualTo("SIMULATED");
        assertThat(plan.get("open").asBoolean()).isTrue();

        assertThat(delete("/api/sim/market/" + worldId).statusCode()).isEqualTo(200);
        JsonNode retained = json(get("/api/plans/" + plan.get("id").asText()));
        assertThat(retained.get("open").asBoolean()).isFalse();
        assertThat(json(get("/api/world")).get("world").asText()).isEqualTo("demo");
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
        assertThat(candidate.at("/evaluation/score/components").isArray()).isTrue();
        assertThat(candidate.at("/evaluation/evidence/perDimension").isObject()).isTrue();
        assertThat(candidate.at("/evaluation/management/rules").isArray()).isTrue();

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
        assertThat(latest.at("/result/candidates/0/evaluation"))
                .isEqualTo(candidate.at("/evaluation"));

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

    @Test void builderFitUsesThePlanContextWithoutMutatingOrPersistingASecondWorkflow() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"strategy-fit-plan-1","symbol":"AAPL","intent":"DIRECTIONAL",
                 "thesis":"bullish","horizonDays":30,"riskMode":"conservative"}
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
                {"clientRequestId":"custom-plan-api-1","symbol":"AAPL","intent":"DIRECTIONAL",
                 "thesis":"bullish","horizonDays":30,"riskMode":"conservative"}
                """));
        String customId = customPlan.get("id").asText();
        JsonNode competition = json(post("/api/plans/" + customId + "/strategy/run", "{}"));
        JsonNode source = competition.at("/strategy/result/candidates/0");
        var customBody = Json.MAPPER.createObjectNode();
        customBody.put("expectedVersion", customPlan.get("version").asLong());
        var position = customBody.putObject("position");
        position.put("symbol", "AAPL"); position.put("strategy", source.get("strategy").asText());
        position.put("qty", source.get("qty").asInt()); position.set("legs", source.get("legs"));
        JsonNode custom = json(post("/api/plans/" + customId + "/strategy/custom", customBody.toString()));
        assertThat(custom.at("/strategy/result/candidate/selected").asBoolean()).isTrue();
        assertThat(custom.at("/strategy/result/candidate/legs")).isEqualTo(source.get("legs"));
        assertThat(json(get("/api/plans/" + customId + "/strategy/latest")).at("/selected/id").asText())
                .isEqualTo(custom.at("/strategy/result/candidate/id").asText());

        JsonNode scoutPlan = json(post("/api/plans", """
                {"clientRequestId":"scout-plan-api-1","symbol":"AAPL","intent":"DIRECTIONAL",
                 "thesis":"bullish","horizonDays":30,"riskMode":"conservative"}
                """));
        String scoutId = scoutPlan.get("id").asText();
        JsonNode scout = json(post("/api/plans/" + scoutId + "/scout/run",
                "{\"scope\":\"ALTERNATIVES\",\"maxPicks\":4}"));
        JsonNode scoutCandidates = scout.at("/scout/result/candidates");
        assertThat(scoutCandidates.size()).isGreaterThan(0);
        JsonNode pick = scoutCandidates.get(0);
        assertThat(pick.get("symbol").asText()).isNotEqualTo("AAPL");

        JsonNode child = json(post("/api/plans/" + scoutId + "/scout/spawn", """
                {"clientRequestId":"spawn-scout-api-1","candidateId":"%s","role":"ALTERNATIVE"}
                """.formatted(pick.get("id").asText())));
        assertThat(child.at("/plan/originPlanId").asText()).isEqualTo(scoutId);
        assertThat(child.at("/plan/symbol").asText()).isEqualTo(pick.get("symbol").asText());
        assertThat(json(get("/api/plans/" + child.at("/plan/id").asText() + "/strategy/latest"))
                .at("/selected/legs")).isEqualTo(pick.get("legs"));
    }

    @Test void outcomesReuseThePlanEvidenceEnsembleAndPersistSeparateInterpretations() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"outcome-plan-api-1","symbol":"AAPL","intent":"DIRECTIONAL",
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

        JsonNode market = json(post("/api/plans/" + id + "/outcomes/run",
                "{\"expectedVersion\":" + version + ",\"basis\":\"RISK_NEUTRAL\"}"));
        assertThat(market.at("/outcome/result/probabilityMap/pAnyProfit").isNumber()).isTrue();

        JsonNode latest = json(get("/api/plans/" + id + "/outcomes/latest"));
        assertThat(latest.get("outcomes")).hasSize(2);
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
                {"clientRequestId":"backtest-owner-check","symbol":"SPY","intent":"DIRECTIONAL",
                 "thesis":"bullish","horizonDays":30,"riskMode":"conservative"}
                """));
        assertThat(get("/api/plans/" + other.get("id").asText() + "/outcomes/backtests/" + backtestId).statusCode())
                .isEqualTo(404);
        JsonNode latestAfterReplay = json(get("/api/plans/" + id + "/outcomes/latest"));
        assertThat(latestAfterReplay.at("/backtests/0/backtestId").asText()).isEqualTo(backtestId);
        assertThat(latestAfterReplay.at("/backtests/0/maxDrawdownPct").isNumber()).isTrue();
        assertThat(latestAfterReplay.at("/backtests/0/state").asText()).isEqualTo("CURRENT");
        assertThat(latestAfterReplay.at("/backtests/0/currentContext").asBoolean()).isTrue();
    }

    @Test void exactPlanEnsembleCreatesAReplayableLinkedRehearsalAndReview() throws Exception {
        JsonNode plan = json(post("/api/plans", """
                {"clientRequestId":"rehearsal-plan-api-1","symbol":"AAPL","intent":"DIRECTIONAL",
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
        assertThat(report.get("note").asText()).contains("exact path 1").contains(fingerprint);

        assertThat(delete("/api/sim/market/" + worldId).statusCode()).isBetween(200, 299);
        JsonNode managed = json(get("/api/plans/" + planId + "/manage"));
        assertThat(managed.at("/plan/activeStage").asText()).isEqualTo("MANAGE_REVIEW");
        assertThat(managed.at("/management/actions/0/kind").asText()).isEqualTo("REHEARSAL_RESULT");
        assertThat(managed.at("/management/reviews/0/category").asText()).isEqualTo("SIM_REHEARSAL");
    }

    @Test void decideFreezesTheServerSelectedPackageAndLinksTradeOrCash() throws Exception {
        assertThat(put("/api/account/risk-context", """
                {"nlvCents":1930000,"cashBpCents":1930000,"riskCapitalCents":193000}
                """).statusCode()).isBetween(200, 299);
        JsonNode tradePlan = json(post("/api/plans", """
                {"clientRequestId":"decision-trade-plan","symbol":"AAPL","intent":"DIRECTIONAL",
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

        var tradeRequest = Json.MAPPER.createObjectNode();
        tradeRequest.put("expectedVersion", version);
        tradeRequest.put("qty", 1);
        tradeRequest.put("proposedNetCents", preview.at("/order/proposedNetCents").asLong());
        if (preview.has("ackToken")) tradeRequest.put("ackToken", preview.get("ackToken").asText());
        var acknowledgments = tradeRequest.putArray("acknowledgedRisks");
        for (JsonNode ack : preview.withArray("requiredAcks")) acknowledgments.add(ack.get("id").asText());
        JsonNode opened = json(post("/api/plans/" + tradePlanId + "/decision/trade", tradeRequest.toString()));
        assertThat(opened.at("/plan/status").asText()).isEqualTo("POSITION_OPEN");
        assertThat(opened.at("/plan/activeStage").asText()).isEqualTo("MANAGE_REVIEW");
        assertThat(opened.at("/decision/action").asText()).isEqualTo("TRADE");
        assertThat(opened.at("/decision/tradeId").asText()).isEqualTo(opened.at("/trade/id").asText());
        assertThat(opened.at("/decision/legs")).hasSize(candidate.withArray("legs").size());
        assertThat(opened.at("/decision/proposedNetCents").asLong())
                .isEqualTo(opened.at("/trade/entryNetPremiumCents").asLong());
        assertThat(opened.at("/decision/accountNlvCents").asLong()).isEqualTo(1_930_000L);
        assertThat(opened.at("/decision/riskCapitalCents").asLong()).isEqualTo(193_000L);

        long openVersion = opened.at("/plan/version").asLong();
        JsonNode marked = json(post("/api/plans/" + tradePlanId + "/manage/refresh",
                "{\"expectedVersion\":" + openVersion + "}"));
        assertThat(marked.at("/management/actions/0/kind").asText()).isEqualTo("MARK");
        JsonNode closed = json(post("/api/plans/" + tradePlanId + "/manage/roll",
                "{\"expectedVersion\":" + openVersion + ",\"confirm\":true}"));
        assertThat(closed.at("/plan/status").asText()).isEqualTo("ACTIVE");
        assertThat(closed.at("/plan/activeStage").asText()).isEqualTo("STRATEGY");
        assertThat(closed.at("/management/actions/0/kind").asText()).isEqualTo("ROLL");
        assertThat(closed.at("/management/links").toString()).contains("ENTRY").contains("ROLL");
        assertThat(closed.at("/management/reviews/0/category").asText()).isEqualTo("TRADE_DECISION");
        assertThat(closed.at("/management/reviews/0/benchmarkKind").asText()).isEqualTo("PLAN_POSITION");
        var rollPackage = Json.MAPPER.createObjectNode();
        rollPackage.put("expectedVersion", closed.at("/plan/version").asLong());
        rollPackage.set("position", closed.get("rolledPosition"));
        JsonNode savedRoll = json(post("/api/plans/" + tradePlanId + "/strategy/custom", rollPackage.toString()));
        JsonNode secondDecision = json(post("/api/plans/" + tradePlanId + "/decision/cash",
                "{\"expectedVersion\":" + savedRoll.at("/plan/version").asLong() + ",\"qty\":1," +
                        "\"note\":\"Skipped the replacement after closing the first cycle\"}"));
        assertThat(secondDecision.at("/plan/status").asText()).isEqualTo("DECIDED_CASH");
        assertThat(secondDecision.at("/decision/action").asText()).isEqualTo("CASH");

        JsonNode cashPlan = json(post("/api/plans", """
                {"clientRequestId":"decision-cash-plan","symbol":"QQQ","intent":"INCOME",
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
}
