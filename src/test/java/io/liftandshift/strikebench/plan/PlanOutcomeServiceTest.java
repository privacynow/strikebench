package io.liftandshift.strikebench.plan;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.sim.IvSpec;
import io.liftandshift.strikebench.sim.PathEnsembleService;
import io.liftandshift.strikebench.sim.ScenarioSpec;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PlanOutcomeServiceTest {
    private Db db;
    private PlanService plans;
    private PlanStrategyService strategies;
    private PlanOutcomeService outcomes;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-07-12T16:00:00Z"), ZoneOffset.UTC);
        plans = new PlanService(db, clock);
        strategies = new PlanStrategyService(db, clock);
        outcomes = new PlanOutcomeService(db, clock);
    }

    @AfterEach void close() { if (db != null) db.close(); }

    @Test void exactMatrixIvPathAndPositionOutcomeRoundTripWithoutRegeneration() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("outcome-service-1", "AAPL", "DIRECTIONAL", null, null,
                        "bullish", 3, null, "conservative", null, null, null));
        ObjectNode candidate = (ObjectNode) Json.parse("""
                {"strategy":"DEBIT_CALL_SPREAD","displayName":"Bull call spread","structureGroup":"DIRECTIONAL",
                 "label":"BUY 250C / SELL 260C","qty":1,"entryNetPremiumCents":-30000,
                 "maxProfitCents":70000,"maxLossCents":30000,"breakevens":[253],"pop":0.45,
                 "expectedValueCents":-900,"liquidityScore":0.9,"freshness":"FIXTURE","warnings":[],
                 "score":70,"confidence":0.8,"intent":"DIRECTIONAL","intents":["DIRECTIONAL"],
                 "decisionViable":true,"structurallyEligible":true,"economicVerdict":"MIXED",
                 "economicPlacement":"LEARN_FROM","economics":{"verdict":"MIXED","placement":"LEARN_FROM",
                   "label":"Mixed","summary":"Costs matter","marketEvAfterCostsCents":-900,
                   "estimatedRoundTripFeesCents":520,"observedEvidence":false,"reasons":["Costs"]},
                 "legs":[
                   {"action":"BUY","type":"CALL","strike":"250","expiration":"2026-08-21","ratio":1,"entryPrice":"7"},
                   {"action":"SELL","type":"CALL","strike":"260","expiration":"2026-08-21","ratio":1,"entryPrice":"4"}]}
                """);
        strategies.saveCustom(null, plan, Json.parse("{\"source\":\"BUILDER\"}"), candidate, plan.version());
        plan = plans.get(null, plan.id());
        String candidateId = strategies.selectedCandidate(null, plan.id()).path("id").asText();

        ScenarioSpec spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 3, 0.25, 991L, 3);
        double[][] paths = {
                {250, 251, 252, 253},
                {250, 249, 247, 246},
                {250, 252, 255, 258}
        };
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("AAPL", "demo", AnalysisContext.OBSERVED),
                250, spec, paths, null, "paths-test");
        IvSpec iv = new IvSpec(0.30, -0.02, 1.2, 0.25, 2, -0.20, 0.03, 4.0);
        var stored = outcomes.saveEnsemble(null, plan, ensemble, iv, 0.04, null,
                Json.parse("{\"shape\":\"CHOP\"}"));

        var restored = outcomes.loadEnsemble(null, plan.id(), stored.id());
        assertThat(restored.fingerprint()).isEqualTo(stored.fingerprint());
        assertThat(restored.ensemble().paths()).isDeepEqualTo(paths);
        assertThat(restored.iv().path(spec.totalSteps(), spec.dt(), spec.stepsPerDay()))
                .containsExactly(iv.sane().path(spec.totalSteps(), spec.dt(), spec.stepsPerDay()));
        assertThat(db.query("SELECT COUNT(*) n FROM ensemble_artifact", r -> r.lng("n"))).containsExactly(1L);

        ObjectNode result = (ObjectNode) Json.parse("""
                {"entryCostCents":30000,"paths":3,"horizonDays":3,"p5Cents":-25000,
                 "p25Cents":-10000,"p50Cents":5000,"p75Cents":18000,"p95Cents":30000,
                 "expectedPnlCents":2000,"winRatePct":66.6667,"bestCents":32000,"worstCents":-27000,
                 "breachProbPct":12.5,"bands":[{"day":0,"p10Cents":0,"p50Cents":0,"p90Cents":0},
                   {"day":3,"p10Cents":-20000,"p50Cents":5000,"p90Cents":28000}],
                 "distribution":[{"fromCents":-27000,"toCents":0,"count":1},{"fromCents":0,"toCents":32000,"count":2}],
                 "notes":["Exact stored paths"]}
                """);
        outcomes.savePathOutcome(null, plan, plan.version(), candidateId, restored, result,
                Json.parse("{\"basis\":\"PARAMETRIC\"}"), "same stored ensemble");

        ObjectNode latest = outcomes.latest(null, plan);
        assertThat(latest.at("/outcomes/0/ensembleId").asText()).isEqualTo(stored.id());
        assertThat(latest.at("/outcomes/0/p50Cents").asLong()).isEqualTo(5000);
        assertThat(latest.at("/outcomes/0/bands/1/p90Cents").asLong()).isEqualTo(28000);
        assertThat(latest.at("/outcomes/0/notes/0").asText()).isEqualTo("Exact stored paths");
    }
}
