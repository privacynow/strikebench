package io.liftandshift.strikebench.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanStrategyServiceTest {
    private Db db;
    private PlanService plans;
    private PlanStrategyService strategies;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-07-12T16:00:00Z"), ZoneOffset.UTC);
        plans = new PlanService(db, clock);
        strategies = new PlanStrategyService(db, clock);
    }

    @AfterEach void close() { if (db != null) db.close(); }

    @Test void exactCandidateRoundTripsThroughNormalizedRows() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("strategy-service-1", "AAPL", "INCOME", null, null,
                        "neutral", 30, null, "conservative", null, null, null, null));
        ObjectNode result = (ObjectNode) Json.parse("""
                {"symbol":"AAPL","thesis":"neutral","horizon":"month","riskMode":"conservative",
                 "intent":"INCOME","riskBudgetCents":100000,"ranking":"decision",
                 "economicMessage":"Compare the field","favorableCount":1,"mixedCount":0,
                 "unfavorableCount":0,"unavailableCount":0,"notes":["Demo note"],"rejected":[],
                 "disclaimer":"Education only","candidates":[{
                   "strategy":"IRON_CONDOR","displayName":"Iron condor","structureGroup":"RANGE",
                   "label":"SELL 245P / BUY 240P / SELL 265C / BUY 270C","qty":1,
                   "entryNetPremiumCents":12500,"maxProfitCents":12500,"maxLossCents":37500,
                   "breakevens":["243.75","266.25"],"pop":0.61,"expectedValueCents":-1200,
                   "liquidityScore":0.82,"freshness":"FIXTURE","warnings":["Teaching data"],
                   "confidence":0.7,"whyConsidered":"Range view","bestUpside":"Time decay",
                   "biggestRisk":"Large move","wouldInvalidate":"Breakout","beginnerExplanation":"A range trade",
                   "intent":"INCOME","intents":["INCOME","DIRECTIONAL"],"assignmentProb":0.28,
                   "annualizedYieldPct":18.2,"effectivePrice":"243.75","intentNote":"Earn inside a range",
                   "usesHeldShares":false,"sharesNeeded":0,"combinedMaxLossCents":37500,
                   "evaluation":{"available":true,"decisionScore":68.0,"viable":true,
                     "capital":{},"volatility":{},"risk":{"pop":0.61},
                     "assessment":{"mechanics":{"eligible":true,"reasons":[]},
                       "economics":{"verdict":"FAVORABLE","placement":"WORTH_INVESTIGATING",
                         "label":"Favorable in this teaching market","summary":"Positive scenario after costs",
                         "marketEvAfterCostsCents":-1200,"realizedVolEvAfterCostsCents":2400,
                         "estimatedRoundTripFeesCents":520,"marketEvPctOfRisk":-3.2,
                         "observedEvidence":false,"reasons":["Scenario positive"]}},
                     "evidence":{"rollup":"DEMO_FIXTURE","perDimension":{"pricing":"DEMO_FIXTURE"},"note":"Teaching data"},
                     "score":{"gatePassed":true,"gateFailures":[],"normalizedScore":74,"riskAdjustedScore":68,
                       "components":[{"name":"expected value","weight":0.35,"value":0.55,"contribution":0.1925,"note":"after costs"}]},
                     "management":{"summary":"Take profits mechanically","rules":[{"kind":"profit","trigger":"50% captured","action":"close"}]},
                     "stance":{},"participation":{},"impliedStance":{},"ivContext":{},"coverage":{},
                     "explanation":{"assumptions":["No dividend yield"],"failureModes":["Move beyond a short strike"]}},
                   "legs":[
                     {"action":"SELL","type":"PUT","strike":"245","expiration":"2026-08-14","ratio":1,"multiplier":100,"entryPrice":"2.4007","positionEffect":"OPEN"},
                     {"action":"BUY","type":"PUT","strike":"240","expiration":"2026-08-14","ratio":1,"multiplier":100,"entryPrice":"1.1","positionEffect":"OPEN"},
                     {"action":"SELL","type":"CALL","strike":"265","expiration":"2026-08-14","ratio":1,"multiplier":100,"entryPrice":"2.3","positionEffect":"OPEN"},
                     {"action":"BUY","type":"CALL","strike":"270","expiration":"2026-08-14","ratio":1,"multiplier":100,"entryPrice":"1.05","positionEffect":"OPEN"}
                   ]}]}
                """);

        PlanStrategyService.SavedRun saved = strategies.saveCompetition(null, plan,
                Json.parse("{\"filters\":{\"minPop\":0.55}}"), result);
        assertThat(saved.state()).isEqualTo("CURRENT");
        assertThat(saved.inputHash()).hasSize(64);
        String candidateId = saved.result().at("/candidates/0/id").asText();
        assertThat(candidateId).startsWith("pcand_");

        PlanStrategyService.SavedRun restored = strategies.latestCompetition(null, plan.id());
        assertThat(restored.inputHash()).isEqualTo(saved.inputHash());
        assertThat(restored.result().at("/candidates/0/legs")).isEqualTo(saved.result().at("/candidates/0/legs"));
        assertThat(saved.result().at("/candidates/0/breakevens/0").isTextual()).isTrue();
        assertThat(restored.result().at("/candidates/0/breakevens/0").decimalValue())
                .isEqualByComparingTo("243.75");
        assertThat(restored.result().at("/candidates/0/breakevens/1").decimalValue())
                .isEqualByComparingTo("266.25");
        assertThat(restored.result().at("/candidates/0/evaluation/assessment/economics/reasons/0").asText())
                .isEqualTo("Scenario positive");
        assertThat(restored.result().at("/candidates/0/economics").isMissingNode()).isTrue();
        assertThat(restored.result().at("/candidates/0/decisionScore").isMissingNode()).isTrue();
        assertThat(restored.result().at("/candidates/0/evaluation/score/components/0/name").asText())
                .isEqualTo("expected value");
        assertThat(restored.result().at("/candidates/0/evaluation/management/rules/0/action").asText())
                .isEqualTo("close");
        assertThat(restored.result().at("/candidates/0/legs/0/entryPrice").asText()).isEqualTo("2.4007");
        assertThat(restored.result().at("/candidates/0/legs/0/positionEffect").asText()).isEqualTo("OPEN");
        assertThat(db.query("SELECT evaluation_snapshot->'evidence'->>'rollup' evidence FROM plan_candidate WHERE id=?",
                r -> r.str("evidence"), candidateId)).containsExactly("DEMO_FIXTURE");
        assertThat(restored.result().at("/candidates/0/sourceKind").asText()).isEqualTo("RANKED");
        assertThat(db.query("SELECT COUNT(*) n FROM plan_candidate_leg", r -> r.lng("n"))).containsExactly(4L);

        PlanStrategyService.Selection selection = strategies.select(null, plan.id(), candidateId, plan.version());
        assertThat(selection.planVersion()).isEqualTo(plan.version() + 1);
        assertThat(strategies.latestCompetition(null, plan.id()).result()
                .at("/candidates/0/selected").asBoolean()).isTrue();

        Plan.View selectedPlan = plans.get(null, plan.id());
        PlanStrategyService.SavedRun refreshed = strategies.saveCompetition(null, selectedPlan,
                Json.parse("{\"filters\":{\"minPop\":0.60}}"), result.deepCopy());
        assertThat(refreshed.runId()).isNotEqualTo(saved.runId());
        assertThat(strategies.latestCompetition(null, plan.id()).runId()).isEqualTo(refreshed.runId());
        assertThat(strategies.selectedCandidate(null, plan.id()).at("/id").asText()).isEqualTo(candidateId);
        assertThat(db.query("SELECT state FROM plan_candidate WHERE id=?", r -> r.str("state"), candidateId))
                .containsExactly("CURRENT");
        assertThat(db.query("SELECT state FROM plan_strategy_run WHERE id=?", r -> r.str("state"), saved.runId()))
                .containsExactly("STALE");
        assertThat(plans.get(null, plan.id()).version()).isEqualTo(selection.planVersion());
        assertThat(strategies.select(null, plan.id(), candidateId, selection.planVersion()).planVersion())
                .isEqualTo(selection.planVersion());

        PlanStrategyService.Selection cleared = strategies.clearSelection(null, plan.id(), selection.planVersion());
        assertThat(cleared.candidateId()).isNull();
        assertThat(cleared.planVersion()).isEqualTo(selection.planVersion() + 1);
        assertThat(strategies.selectedCandidate(null, plan.id())).isNull();
        assertThat(strategies.latestCompetition(null, plan.id()).result()
                .at("/candidates/0/selected").asBoolean()).isFalse();

        PlanStrategyService.Selection alreadyClear = strategies.clearSelection(null, plan.id(), cleared.planVersion());
        assertThat(alreadyClear.planVersion()).isEqualTo(cleared.planVersion());
    }

    @Test void customBuilderPackagePersistsAndRestoresAsTheSelectedStructure() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("strategy-custom-1", "AAPL", "DIRECTIONAL", null, null,
                        "bullish", 45, null, "balanced", null, null, null, null));
        ObjectNode candidate = (ObjectNode) Json.parse("""
                {"strategy":"CUSTOM","displayName":"Custom position","structureGroup":"custom",
                 "label":"BUY 250C / SELL 265C","qty":2,"entryNetPremiumCents":-85000,
                 "maxProfitCents":215000,"maxLossCents":85000,"breakevens":[254.25],"pop":0.43,
                 "expectedValueCents":-3200,"liquidityScore":1.0,"freshness":"FIXTURE",
                 "warnings":[],"confidence":1.0,"whyConsidered":"Exact ticket",
                 "bestUpside":"","biggestRisk":"","wouldInvalidate":"","beginnerExplanation":"",
                 "intent":"DIRECTIONAL","intents":["DIRECTIONAL"],"assignmentProb":0.22,
	                 "evaluation":{"available":true,"decisionScore":44.0,"viable":true,
                   "capital":{},"volatility":{},"risk":{"pop":0.43},"evidence":{},"management":{},"score":{},
                   "assessment":{"mechanics":{"eligible":true,"reasons":[]},
                     "economics":{"verdict":"MIXED","placement":"LEARN_FROM",
                       "label":"Mixed economics","summary":"Exact package after costs","marketEvAfterCostsCents":-3200,
                       "realizedVolEvAfterCostsCents":1400,"estimatedRoundTripFeesCents":1040,
                       "observedEvidence":false,"reasons":["Costs matter"]}},
                   "stance":{},"participation":{},"impliedStance":{},"ivContext":{},"coverage":{},"explanation":{}},
                 "legs":[
                   {"action":"BUY","type":"CALL","strike":"250","expiration":"2026-08-28","ratio":1,"multiplier":100,"entryPrice":"12.25","positionEffect":"OPEN"},
                   {"action":"SELL","type":"CALL","strike":"265","expiration":"2026-08-28","ratio":1,"multiplier":100,"entryPrice":"8.00","positionEffect":"OPEN"}
                 ]}
                """);

        PlanStrategyService.SavedRun saved = strategies.saveCustom(null, plan,
                Json.parse("{\"source\":\"BUILDER\"}"), candidate, plan.version(), true);

        assertThat(saved.result().at("/candidate/selected").asBoolean()).isTrue();
        JsonNode selected = strategies.selectedCandidate(null, plan.id());
        assertThat(selected.at("/legs/0/strike").asText()).isEqualTo("250");
        assertThat(selected.at("/legs/0/entryPrice").asText()).isEqualTo("12.25");
        assertThat(selected.at("/legs/1/entryPrice").asText()).isEqualTo("8");
        assertThat(selected.at("/evaluation/assessment/economics/realizedVolEvAfterCostsCents").asLong())
                .isEqualTo(1400);
        assertThat(selected.has("pop")).as("POP comes only from evaluation.risk").isFalse();
        assertThat(selected.has("expectedValueCents")).as("EV comes only from evaluation assessment/risk").isFalse();
        assertThat(db.query("SELECT source_kind FROM plan_candidate WHERE selected=1", r -> r.str("source_kind")))
                .containsExactly("CUSTOM");

        Plan.View afterSelection = plans.get(null, plan.id());
        long selectedVersion = afterSelection.version();
        String selectedId = selected.get("id").asText();

        ObjectNode refreshedField = Json.MAPPER.createObjectNode();
        refreshedField.put("symbol", "AAPL");
        refreshedField.put("thesis", "bullish");
        refreshedField.put("horizon", "month");
        refreshedField.put("riskMode", "balanced");
        refreshedField.put("intent", "DIRECTIONAL");
        refreshedField.putArray("candidates").add(candidate.deepCopy());
        PlanStrategyService.SavedRun refreshed = strategies.saveCompetition(null, afterSelection,
                Json.parse("{\"filters\":{\"minPop\":0.45}}"), refreshedField);

        assertThat(strategies.latestCompetition(null, plan.id()).inputHash()).isEqualTo(refreshed.inputHash());
        assertThat(strategies.selectedCandidate(null, plan.id()).at("/id").asText()).isEqualTo(selectedId);
        assertThat(db.query("SELECT state FROM plan_candidate WHERE id=?", r -> r.str("state"), selectedId))
                .containsExactly("CURRENT");
        assertThat(plans.get(null, plan.id()).version()).isEqualTo(selectedVersion);

        ObjectNode constrained = candidate.deepCopy();
        constrained.remove("id");
        constrained.put("label", "Analyzed but mechanically constrained");
        PlanStrategyService.SavedRun analyzed = strategies.saveCustom(null, afterSelection,
                Json.parse("{\"source\":\"ANALYZE\"}"), constrained, selectedVersion, false);

        assertThat(analyzed.result().at("/candidate/selected").asBoolean()).isFalse();
        Plan.View afterConstraint = plans.get(null, plan.id());
        assertThat(afterConstraint.version()).isEqualTo(selectedVersion + 1);
        assertThat(afterConstraint.furthestStage()).isEqualTo(Plan.Stage.STRATEGY);
        assertThat(strategies.selectedCandidate(null, plan.id())).isNull();
        assertThat(db.query("SELECT COUNT(*) n FROM plan_candidate WHERE selected=1", r -> r.lng("n")))
                .containsExactly(0L);
        assertThat(selectedId).isNotBlank();

        PlanStrategyService.SavedRun obsoleteCustom = strategies.saveCustom(null, afterConstraint,
                Json.parse("{\"source\":\"BUILDER\"}"), candidate.deepCopy(), afterConstraint.version(), true);
        String obsoleteCustomId = obsoleteCustom.result().at("/candidate/id").asText();
        db.exec("UPDATE plan_strategy_run SET engine_version='plan-strategy-obsolete' WHERE id=?",
                obsoleteCustom.runId());
        Plan.View beforeEngineRefresh = plans.get(null, plan.id());
        strategies.saveCompetition(null, beforeEngineRefresh,
                Json.parse("{\"filters\":{\"minPop\":0.50}}"), refreshedField);
        assertThat(strategies.selectedCandidate(null, plan.id())).isNull();
        assertThat(db.query("SELECT state FROM plan_candidate WHERE id=?", r -> r.str("state"), obsoleteCustomId))
                .containsExactly("STALE");
    }

    @Test void obsoleteCompetitionEngineCannotRestoreAnalysisOrSelectedCandidate() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("strategy-engine-version", "AAPL", "INCOME", null, null,
                        "neutral", 30, null, "conservative", null, null, null, null));
        ObjectNode result = Json.MAPPER.createObjectNode();
        result.put("symbol", "AAPL");
        result.put("thesis", "neutral");
        result.put("horizon", "month");
        result.put("riskMode", "conservative");
        result.put("intent", "INCOME");
        result.putArray("candidates").add(Json.parse("""
                {"strategy":"CREDIT_PUT_SPREAD","displayName":"Bull put spread",
                 "structureGroup":"credit_vertical","label":"SELL 250P / BUY 245P","qty":1,
                 "entryNetPremiumCents":12000,"maxProfitCents":12000,"maxLossCents":38000,
                 "evaluation":{"available":true,"decisionScore":62.0,"viable":true,
                   "capital":{},"volatility":{},"risk":{},"evidence":{},"management":{},"score":{},
                   "assessment":{},"stance":{},"participation":{},"impliedStance":{},
                   "ivContext":{},"coverage":{},"explanation":{}},
                 "legs":[
                   {"action":"SELL","type":"PUT","strike":"250","expiration":"2026-08-14",
                    "ratio":1,"multiplier":100,"entryPrice":"3.20","positionEffect":"OPEN"},
                   {"action":"BUY","type":"PUT","strike":"245","expiration":"2026-08-14",
                    "ratio":1,"multiplier":100,"entryPrice":"2.00","positionEffect":"OPEN"}
                 ]}
                """));

        PlanStrategyService.SavedRun saved = strategies.saveCompetition(null, plan,
                Json.parse("{}"), result);
        assertThat(strategies.latestCompetition(null, plan.id())).isNotNull();
        String candidateId = saved.result().at("/candidates/0/id").asText();

        db.exec("UPDATE plan_strategy_run SET engine_version='plan-strategy-obsolete' WHERE id=?",
                saved.runId());
        assertThatThrownBy(() -> strategies.select(null, plan.id(), candidateId, plan.version()))
                .hasMessageContaining("no current candidate");
        assertThat(plans.get(null, plan.id()).version()).isEqualTo(plan.version());

        db.exec("UPDATE plan_strategy_run SET engine_version=? WHERE id=?",
                PlanStrategyService.ENGINE_VERSION, saved.runId());
        strategies.select(null, plan.id(), candidateId, plan.version());
        assertThat(strategies.selectedCandidate(null, plan.id())).isNotNull();

        db.exec("UPDATE plan_strategy_run SET engine_version='plan-strategy-obsolete' WHERE id=?",
                saved.runId());

        assertThat(strategies.latestCompetition(null, plan.id())).isNull();
        assertThat(strategies.selectedCandidate(null, plan.id())).isNull();
    }

    @Test void priorRecommendationSemanticsAreNotReusedAndFreshRunReplacesTheField() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("strategy-economic-version", "AAPL", "INCOME", null, null,
                        "neutral", 45, null, "conservative", null, null, null, null));
        ObjectNode result = Json.MAPPER.createObjectNode();
        result.put("symbol", "AAPL");
        result.put("thesis", "neutral");
        result.put("horizon", "45-session");
        result.put("riskMode", "conservative");
        result.put("intent", "INCOME");
        result.putArray("candidates").add(Json.parse("""
                {"strategy":"CREDIT_PUT_SPREAD","displayName":"Bull put spread",
                 "structureGroup":"credit_vertical","label":"SELL 250P / BUY 245P","qty":1,
                 "entryNetPremiumCents":12000,"maxProfitCents":12000,"maxLossCents":38000,
                 "evaluation":{"available":true,"decisionScore":62.0,"viable":true,
                   "capital":{},"volatility":{},"risk":{},"evidence":{},"management":{},"score":{},
                   "assessment":{},"stance":{},"participation":{},"impliedStance":{},
                   "ivContext":{},"coverage":{},"explanation":{}},
                 "legs":[
                   {"action":"SELL","type":"PUT","strike":"250","expiration":"2026-08-28",
                    "ratio":1,"multiplier":100,"entryPrice":"3.20","positionEffect":"OPEN"},
                   {"action":"BUY","type":"PUT","strike":"245","expiration":"2026-08-28",
                    "ratio":1,"multiplier":100,"entryPrice":"2.00","positionEffect":"OPEN"}
                 ]}
                """));

        PlanStrategyService.SavedRun prior = strategies.saveCompetition(null, plan,
                Json.parse("{\"filters\":{\"minPop\":0.55}}"), result);
        String priorCandidateId = prior.result().at("/candidates/0/id").asText();
        db.exec("UPDATE plan_strategy_run SET engine_version='plan-strategy-5' WHERE id=?", prior.runId());

        assertThat(PlanStrategyService.ENGINE_VERSION).isEqualTo("plan-strategy-6");
        assertThat(strategies.latestCompetition(null, plan.id())).isNull();

        ObjectNode refreshedResult = result.deepCopy();
        refreshedResult.put("economicMessage", "Re-evaluated with the current economics and structure quality rules");
        PlanStrategyService.SavedRun refreshed = strategies.saveCompetition(null, plan,
                Json.parse("{\"filters\":{\"minPop\":0.60}}"), refreshedResult);

        assertThat(strategies.latestCompetition(null, plan.id()).runId()).isEqualTo(refreshed.runId());
        assertThat(refreshed.runId()).isNotEqualTo(prior.runId());
        assertThat(db.query("SELECT engine_version FROM plan_strategy_run WHERE id=?",
                r -> r.str("engine_version"), refreshed.runId()))
                .containsExactly(PlanStrategyService.ENGINE_VERSION);
        assertThat(db.query("SELECT state FROM plan_strategy_run WHERE id=?",
                r -> r.str("state"), prior.runId())).containsExactly("STALE");
        assertThat(db.query("SELECT state FROM plan_candidate WHERE id=?",
                r -> r.str("state"), priorCandidateId)).containsExactly("STALE");
    }

    @Test void obsoleteOrPartialEvaluationPayloadsAreRejectedInsteadOfAdapted() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("strategy-current-receipt", "AAPL", "DIRECTIONAL", null, null,
                        "bullish", 30, null, "balanced", null, null, null, null));
        ObjectNode oldFullEvaluation = (ObjectNode) Json.parse("""
                {"strategy":"CUSTOM","evaluation":{"id":"eval_old","available":true,"decisionScore":50,"viable":true}}
                """);
        assertThatThrownBy(() -> strategies.saveCustom(null, plan, Json.parse("{}"),
                oldFullEvaluation, plan.version(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("full StrategyEvaluation payloads are not accepted");

        ObjectNode partialReceipt = (ObjectNode) Json.parse("""
                {"strategy":"CUSTOM","evaluation":{"available":true,"decisionScore":50,"viable":true,"assessment":{}}}
                """);
        assertThatThrownBy(() -> strategies.saveCustom(null, plan, Json.parse("{}"),
                partialReceipt, plan.version(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evaluation receipt requires object field");
    }

    @Test void scoutResultRestoresAndCopiesAnExactPackageIntoALinkedSiblingPlan() {
        Plan.View origin = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("strategy-scout-origin", "AAPL", "DIRECTIONAL", null, null,
                        "bullish", 30, null, "conservative", null, null, null, null));
        ObjectNode result = (ObjectNode) Json.parse("""
                {"symbol":"AAPL","scope":"PEERS","thesis":"bullish","horizon":"month",
                 "riskMode":"conservative","intent":"DIRECTIONAL","riskBudgetCents":100000,
                 "economicMessage":"One peer matched","favorableCount":0,"mixedCount":1,
                 "unfavorableCount":0,"unavailableCount":0,"notes":["Same-sector scan"],
                 "disclaimer":"Education only","sentimentScorerVersion":"sentiment-keyword-v1","candidates":[{
                   "symbol":"SPY","scoutThesis":"BULLISH","strategy":"DEBIT_CALL_SPREAD",
                   "sentimentScorerVersion":"sentiment-keyword-v1",
                   "displayName":"Bull call spread","structureGroup":"DIRECTIONAL","label":"BUY 560C / SELL 570C",
                   "qty":1,"entryNetPremiumCents":-34000,"maxProfitCents":66000,"maxLossCents":34000,
                   "breakevens":[563.4],"pop":0.47,"expectedValueCents":-900,"liquidityScore":0.92,
                   "freshness":"FIXTURE","warnings":[],"confidence":0.72,
                   "whyConsidered":"Bullish peer","bestUpside":"Capped gain","biggestRisk":"Stock falls",
                   "wouldInvalidate":"Trend reverses","beginnerExplanation":"A capped bullish trade",
                   "intent":"DIRECTIONAL","intents":["DIRECTIONAL"],
                   "evaluation":{"available":true,"decisionScore":41.0,"viable":true,
                     "capital":{},"volatility":{},"risk":{"pop":0.47},"evidence":{},"management":{},"score":{},
                     "assessment":{"mechanics":{"eligible":true,"reasons":[]},
                       "economics":{"verdict":"MIXED","placement":"LEARN_FROM","label":"Mixed economics",
                         "summary":"Costs reduce the edge","marketEvAfterCostsCents":-900,
                         "realizedVolEvAfterCostsCents":400,"estimatedRoundTripFeesCents":520,
                         "observedEvidence":false,"reasons":["Compare costs"]}},
                     "stance":{},"participation":{},"impliedStance":{},"ivContext":{},"coverage":{},"explanation":{}},
                   "legs":[
                     {"action":"BUY","type":"CALL","strike":"560","expiration":"2026-08-21","ratio":1,"multiplier":100,"entryPrice":"8.4","positionEffect":"OPEN"},
                     {"action":"SELL","type":"CALL","strike":"570","expiration":"2026-08-21","ratio":1,"multiplier":100,"entryPrice":"5.0","positionEffect":"OPEN"}
                   ]}]}
                """);

        PlanStrategyService.SavedRun saved = strategies.saveScout(null, origin, "PEERS",
                Json.parse("{\"scope\":\"PEERS\"}"), result);
        String candidateId = saved.result().at("/candidates/0/id").asText();
        PlanStrategyService.SavedRun restored = strategies.latestScout(null, origin.id(), "PEERS");
        assertThat(restored.result().at("/sentimentScorerVersion").asText()).isEqualTo("sentiment-keyword-v1");
        assertThat(restored.result().at("/candidates/0/symbol").asText()).isEqualTo("SPY");
        assertThat(restored.result().at("/candidates/0/sentimentScorerVersion").asText())
                .isEqualTo("sentiment-keyword-v1");
        assertThatThrownBy(() -> strategies.select(null, origin.id(), candidateId, origin.version()))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("no current candidate");
        assertThat(strategies.selectedCandidate(null, origin.id())).isNull();

        Plan.View child = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("strategy-scout-child", "SPY", "DIRECTIONAL", origin.id(), null,
                        "bullish", 30, null, "conservative", null, null, null, null));
        plans.linkRelated(null, origin.id(), child.id(), "PEER");

        db.exec("UPDATE plan_strategy_run SET engine_version='plan-strategy-obsolete' WHERE id=?",
                saved.runId());
        assertThat(strategies.latestScout(null, origin.id(), "PEERS")).isNull();
        assertThatThrownBy(() -> strategies.copyScoutSelection(null, origin.id(), candidateId, child))
                .hasMessageContaining("no current scouted candidate");
        assertThat(plans.get(null, child.id()).version()).isEqualTo(child.version());

        db.exec("UPDATE plan_strategy_run SET engine_version=? WHERE id=?",
                PlanStrategyService.ENGINE_VERSION, saved.runId());
        strategies.copyScoutSelection(null, origin.id(), candidateId, child);

        JsonNode selected = strategies.selectedCandidate(null, child.id());
        assertThat(selected.at("/symbol").asText()).isEqualTo("SPY");
        assertThat(selected.at("/sentimentScorerVersion").asText()).isEqualTo("sentiment-keyword-v1");
        assertThat(db.query("SELECT role FROM plan_link WHERE plan_id=? AND related_plan_id=?",
                r -> r.str("role"), origin.id(), child.id())).containsExactly("PEER");
        assertThat(db.query("SELECT source_kind FROM plan_candidate WHERE plan_id=? AND selected=1",
                r -> r.str("source_kind"), child.id())).containsExactly("SCOUT");
        assertThat(db.query("SELECT sentiment_scorer_version FROM plan_strategy_run WHERE plan_id=?",
                r -> r.str("sentiment_scorer_version"), child.id())).containsExactly("sentiment-keyword-v1");
    }
}
