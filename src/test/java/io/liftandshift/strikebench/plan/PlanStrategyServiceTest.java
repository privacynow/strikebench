package io.liftandshift.strikebench.plan;

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
                   "legs":[
                     {"action":"SELL","type":"PUT","strike":"245","expiration":"2026-08-14","ratio":1,"entryPrice":"2.4"},
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
        assertThat(db.query("SELECT COUNT(*) n FROM plan_candidate_leg", r -> r.lng("n"))).containsExactly(4L);

        PlanStrategyService.Selection selection = strategies.select(null, plan.id(), candidateId, plan.version());
        assertThat(selection.planVersion()).isEqualTo(plan.version() + 1);
        assertThat(strategies.latestCompetition(null, plan.id()).result()
                .at("/candidates/0/selected").asBoolean()).isTrue();
    }
}
