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
                        "neutral", 30, null, "conservative", null, null, null));
        ObjectNode result = (ObjectNode) Json.parse("""
                {"symbol":"AAPL","thesis":"neutral","horizon":"month","riskMode":"conservative",
                 "intent":"INCOME","riskBudgetCents":100000,"ranking":"decision",
                 "economicMessage":"Compare the field","favorableCount":1,"mixedCount":0,
                 "unfavorableCount":0,"unavailableCount":0,"notes":["Demo note"],"rejected":[],
                 "disclaimer":"Education only","candidates":[{
                   "strategy":"IRON_CONDOR","displayName":"Iron condor","structureGroup":"RANGE",
                   "label":"SELL 245P / BUY 240P / SELL 265C / BUY 270C","qty":1,
                   "entryNetPremiumCents":12500,"maxProfitCents":12500,"maxLossCents":37500,
                   "breakevens":[243.75,266.25],"pop":0.61,"expectedValueCents":-1200,
                   "liquidityScore":0.82,"freshness":"FIXTURE","warnings":["Teaching data"],
                   "score":72.0,"confidence":0.7,"whyConsidered":"Range view","bestUpside":"Time decay",
                   "biggestRisk":"Large move","wouldInvalidate":"Breakout","beginnerExplanation":"A range trade",
                   "intent":"INCOME","intents":["INCOME","DIRECTIONAL"],"assignmentProb":0.28,
                   "annualizedYieldPct":18.2,"effectivePrice":"243.75","intentNote":"Earn inside a range",
                   "usesHeldShares":false,"sharesNeeded":0,"combinedMaxLossCents":37500,
                   "decisionScore":68.0,"decisionViable":true,"structurallyEligible":true,
                   "economicVerdict":"FAVORABLE","economicPlacement":"WORTH_INVESTIGATING",
                   "economics":{"verdict":"FAVORABLE","placement":"WORTH_INVESTIGATING",
                     "label":"Favorable in this teaching market","summary":"Positive scenario after costs",
                     "marketEvAfterCostsCents":-1200,"realizedVolEvAfterCostsCents":2400,
                     "estimatedRoundTripFeesCents":520,"marketEvPctOfRisk":-3.2,
                     "observedEvidence":false,"reasons":["Scenario positive"]},
                   "evaluation":{"evidence":{"rollup":"DEMO_FIXTURE","perDimension":{"pricing":"DEMO_FIXTURE"},"note":"Teaching data"},
                     "score":{"gatePassed":true,"gateFailures":[],"normalizedScore":74,"riskAdjustedScore":68,
                       "components":[{"name":"expected value","weight":0.35,"value":0.55,"contribution":0.1925,"note":"after costs"}]},
                     "management":{"summary":"Take profits mechanically","rules":[{"kind":"profit","trigger":"50% captured","action":"close"}]},
                     "explanation":{"assumptions":["No dividend yield"],"failureModes":["Move beyond a short strike"]}},
                   "legs":[
                     {"action":"SELL","type":"PUT","strike":"245","expiration":"2026-08-14","ratio":1,"entryPrice":"2.4007"},
                     {"action":"BUY","type":"PUT","strike":"240","expiration":"2026-08-14","ratio":1,"entryPrice":"1.1"},
                     {"action":"SELL","type":"CALL","strike":"265","expiration":"2026-08-14","ratio":1,"entryPrice":"2.3"},
                     {"action":"BUY","type":"CALL","strike":"270","expiration":"2026-08-14","ratio":1,"entryPrice":"1.05"}
                   ]}]}
                """);

        PlanStrategyService.SavedRun saved = strategies.saveCompetition(null, plan,
                Json.parse("{\"filters\":{\"minPop\":0.55}}"), result);
        assertThat(saved.state()).isEqualTo("CURRENT");
        String candidateId = saved.result().at("/candidates/0/id").asText();
        assertThat(candidateId).startsWith("pcand_");

        PlanStrategyService.SavedRun restored = strategies.latestCompetition(null, plan.id());
        assertThat(restored.result().at("/candidates/0/legs")).isEqualTo(saved.result().at("/candidates/0/legs"));
        assertThat(restored.result().at("/candidates/0/breakevens")).isEqualTo(saved.result().at("/candidates/0/breakevens"));
        assertThat(restored.result().at("/candidates/0/economics/reasons/0").asText()).isEqualTo("Scenario positive");
        assertThat(restored.result().at("/candidates/0/evaluation/score/components/0/name").asText())
                .isEqualTo("expected value");
        assertThat(restored.result().at("/candidates/0/evaluation/management/rules/0/action").asText())
                .isEqualTo("close");
        assertThat(restored.result().at("/candidates/0/legs/0/entryPrice").asText()).isEqualTo("2.4007");
        assertThat(db.query("SELECT evidence_provenance FROM plan_candidate WHERE id=?",
                r -> r.str("evidence_provenance"), candidateId)).containsExactly("DEMO_FIXTURE");
        assertThat(restored.result().at("/candidates/0/sourceKind").asText()).isEqualTo("RANKED");
        assertThat(db.query("SELECT COUNT(*) n FROM plan_candidate_leg", r -> r.lng("n"))).containsExactly(4L);

        PlanStrategyService.Selection selection = strategies.select(null, plan.id(), candidateId, plan.version());
        assertThat(selection.planVersion()).isEqualTo(plan.version() + 1);
        assertThat(strategies.latestCompetition(null, plan.id()).result()
                .at("/candidates/0/selected").asBoolean()).isTrue();
    }

    @Test void customBuilderPackagePersistsAndRestoresAsTheSelectedStructure() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("strategy-custom-1", "AAPL", "DIRECTIONAL", null, null,
                        "bullish", 45, null, "balanced", null, null, null));
        ObjectNode candidate = (ObjectNode) Json.parse("""
                {"strategy":"CUSTOM","displayName":"Custom position","structureGroup":"custom",
                 "label":"BUY 250C / SELL 265C","qty":2,"entryNetPremiumCents":-85000,
                 "maxProfitCents":215000,"maxLossCents":85000,"breakevens":[254.25],"pop":0.43,
                 "expectedValueCents":-3200,"liquidityScore":1.0,"freshness":"FIXTURE",
                 "warnings":[],"score":0.0,"confidence":1.0,"whyConsidered":"Exact ticket",
                 "bestUpside":"","biggestRisk":"","wouldInvalidate":"","beginnerExplanation":"",
                 "intent":"DIRECTIONAL","intents":["DIRECTIONAL"],"assignmentProb":0.22,
                 "decisionViable":true,"structurallyEligible":true,"economicVerdict":"MIXED",
                 "economicPlacement":"LEARN_FROM","economics":{"verdict":"MIXED","placement":"LEARN_FROM",
                   "label":"Mixed economics","summary":"Exact package after costs","marketEvAfterCostsCents":-3200,
                   "realizedVolEvAfterCostsCents":1400,"estimatedRoundTripFeesCents":1040,
                   "observedEvidence":false,"reasons":["Costs matter"]},
                 "legs":[
                   {"action":"BUY","type":"CALL","strike":"250","expiration":"2026-08-28","ratio":1,"entryPrice":"12.25"},
                   {"action":"SELL","type":"CALL","strike":"265","expiration":"2026-08-28","ratio":1,"entryPrice":"8.00"}
                 ]}
                """);

        PlanStrategyService.SavedRun saved = strategies.saveCustom(null, plan,
                Json.parse("{\"source\":\"BUILDER\"}"), candidate, plan.version());

        assertThat(saved.result().at("/candidate/selected").asBoolean()).isTrue();
        JsonNode selected = strategies.selectedCandidate(null, plan.id());
        assertThat(selected.at("/legs/0/strike").asText()).isEqualTo("250");
        assertThat(selected.at("/legs/0/entryPrice").asText()).isEqualTo("12.25");
        assertThat(selected.at("/legs/1/entryPrice").asText()).isEqualTo("8");
        assertThat(selected.at("/economics/realizedVolEvAfterCostsCents").asLong()).isEqualTo(1400);
        assertThat(db.query("SELECT source_kind FROM plan_candidate WHERE selected=1", r -> r.str("source_kind")))
                .containsExactly("CUSTOM");
    }

    @Test void scoutResultRestoresAndCopiesAnExactPackageIntoALinkedSiblingPlan() {
        Plan.View origin = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("strategy-scout-origin", "AAPL", "DIRECTIONAL", null, null,
                        "bullish", 30, null, "conservative", null, null, null));
        ObjectNode result = (ObjectNode) Json.parse("""
                {"symbol":"AAPL","scope":"PEERS","thesis":"bullish","horizon":"month",
                 "riskMode":"conservative","intent":"DIRECTIONAL","riskBudgetCents":100000,
                 "economicMessage":"One peer matched","favorableCount":0,"mixedCount":1,
                 "unfavorableCount":0,"unavailableCount":0,"notes":["Same-sector scan"],
                 "disclaimer":"Education only","candidates":[{
                   "symbol":"SPY","scoutThesis":"BULLISH","strategy":"DEBIT_CALL_SPREAD",
                   "displayName":"Bull call spread","structureGroup":"DIRECTIONAL","label":"BUY 560C / SELL 570C",
                   "qty":1,"entryNetPremiumCents":-34000,"maxProfitCents":66000,"maxLossCents":34000,
                   "breakevens":[563.4],"pop":0.47,"expectedValueCents":-900,"liquidityScore":0.92,
                   "freshness":"FIXTURE","warnings":[],"score":70,"confidence":0.72,
                   "whyConsidered":"Bullish peer","bestUpside":"Capped gain","biggestRisk":"Stock falls",
                   "wouldInvalidate":"Trend reverses","beginnerExplanation":"A capped bullish trade",
                   "intent":"DIRECTIONAL","intents":["DIRECTIONAL"],"decisionViable":true,
                   "structurallyEligible":true,"economicVerdict":"MIXED","economicPlacement":"LEARN_FROM",
                   "economics":{"verdict":"MIXED","placement":"LEARN_FROM","label":"Mixed economics",
                     "summary":"Costs reduce the edge","marketEvAfterCostsCents":-900,
                     "realizedVolEvAfterCostsCents":400,"estimatedRoundTripFeesCents":520,
                     "observedEvidence":false,"reasons":["Compare costs"]},
                   "legs":[
                     {"action":"BUY","type":"CALL","strike":"560","expiration":"2026-08-21","ratio":1,"entryPrice":"8.4"},
                     {"action":"SELL","type":"CALL","strike":"570","expiration":"2026-08-21","ratio":1,"entryPrice":"5.0"}
                   ]}]}
                """);

        PlanStrategyService.SavedRun saved = strategies.saveScout(null, origin, "PEERS",
                Json.parse("{\"scope\":\"PEERS\"}"), result);
        String candidateId = saved.result().at("/candidates/0/id").asText();
        assertThat(strategies.latestScout(null, origin.id(), "PEERS").result().at("/candidates/0/symbol").asText())
                .isEqualTo("SPY");
        assertThatThrownBy(() -> strategies.select(null, origin.id(), candidateId, origin.version()))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("no current candidate");
        assertThat(strategies.selectedCandidate(null, origin.id())).isNull();

        Plan.View child = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("strategy-scout-child", "SPY", "DIRECTIONAL", origin.id(), null,
                        "bullish", 30, null, "conservative", null, null, null));
        plans.linkRelated(null, origin.id(), child.id(), "PEER");
        strategies.copyScoutSelection(null, origin.id(), candidateId, child);

        assertThat(strategies.selectedCandidate(null, child.id()).at("/symbol").asText()).isEqualTo("SPY");
        assertThat(db.query("SELECT role FROM plan_link WHERE plan_id=? AND related_plan_id=?",
                r -> r.str("role"), origin.id(), child.id())).containsExactly("PEER");
        assertThat(db.query("SELECT source_kind FROM plan_candidate WHERE plan_id=? AND selected=1",
                r -> r.str("source_kind"), child.id())).containsExactly("SCOUT");
    }
}
