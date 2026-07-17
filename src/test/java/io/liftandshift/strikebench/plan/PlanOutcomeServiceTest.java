package io.liftandshift.strikebench.plan;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountRiskContext;
import io.liftandshift.strikebench.paper.TradePreview;
import io.liftandshift.strikebench.sim.IvSpec;
import io.liftandshift.strikebench.sim.PathEnsembleService;
import io.liftandshift.strikebench.sim.ScenarioCanvasSpec;
import io.liftandshift.strikebench.sim.ScenarioSpec;
import io.liftandshift.strikebench.sim.SimulationEngine;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanOutcomeServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-12T16:00:00Z"), ZoneOffset.UTC);
    private Db db;
    private PlanService plans;
    private PlanStrategyService strategies;
    private PlanOutcomeService outcomes;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        plans = new PlanService(db, CLOCK);
        strategies = new PlanStrategyService(db, CLOCK);
        outcomes = new PlanOutcomeService(db, CLOCK);
    }

    @AfterEach void close() { if (db != null) db.close(); }

    @Test void publicFanReceiptPromotesExactArtifactAndBothSaveAndRetryAreIdempotent() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("public-fan-adopt", "AAPL", "DIRECTIONAL", null, null,
                        "one exact fan", 2, null, "conservative", null, null, null, null));
        ScenarioSpec spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 2, .27, 9077L, 3);
        double[][] paths = {{100, 101, 103}, {100, 99, 97}, {100, 100.5, 101}};
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("AAPL", "demo", AnalysisContext.OBSERVED),
                100, spec, paths, null, "paths-public-test", LocalDate.of(2026, 7, 10));
        IvSpec iv = IvSpec.flat(.31);
        var preview = preview(ensemble);
        ObjectNode input = (ObjectNode) Json.parse("{\"operation\":\"PATHS\",\"seed\":9077}");
        var context = new PlanOutcomeService.ResearchContext("DEMO", "demo", "observed");

        var receipt = outcomes.saveResearchEnsemble(null, context, ensemble, iv,
                ScenarioCanvasSpec.defaults(), .04, preview, input);
        var duplicateReceipt = outcomes.saveResearchEnsemble(null, context, ensemble, iv,
                ScenarioCanvasSpec.defaults(), .04, preview, input);
        assertThat(duplicateReceipt.id()).isEqualTo(receipt.id());
        assertThat(duplicateReceipt.fingerprint()).isEqualTo(receipt.fingerprint());

        var first = outcomes.promoteResearchEnsemble(null, plan, receipt.id(), receipt.fingerprint(), context);
        var retried = outcomes.promoteResearchEnsemble(null, plan, receipt.id(), receipt.fingerprint(), context);
        assertThat(retried.ensemble().id()).isEqualTo(first.ensemble().id());
        assertThat(first.ensemble().fingerprint()).isEqualTo(receipt.fingerprint());
        assertThat(first.ensemble().ensemble().paths()).isDeepEqualTo(paths);
        assertThat(db.query("SELECT state || ':' || adopted_plan_id || ':' || adopted_ensemble_id value " +
                        "FROM research_ensemble_receipt WHERE id=?", r -> r.str("value"), receipt.id()))
                .containsExactly("ADOPTED:" + plan.id() + ":" + first.ensemble().id());
        assertThat(db.query("SELECT COUNT(*) n FROM plan_ensemble WHERE plan_id=?",
                r -> r.lng("n"), plan.id())).containsExactly(1L);
        assertThat(db.query("SELECT COUNT(*) n FROM ensemble_artifact",
                r -> r.lng("n"))).containsExactly(1L);

        // The ordinary Plan save command obeys the same retry contract.
        var savedAgain = outcomes.saveEnsemble(null, plan, ensemble, iv,
                ScenarioCanvasSpec.defaults(), .04, preview, input);
        var savedRetry = outcomes.saveEnsemble(null, plan, ensemble, iv,
                ScenarioCanvasSpec.defaults(), .04, preview, input);
        assertThat(savedRetry.id()).isEqualTo(savedAgain.id());
    }

    @Test void scenarioDatasetReceiptKeepsObservedExecutionOwnershipAndExactDataset() {
        db.exec("INSERT INTO dataset(id,name,kind,symbol,seed,spec,user_id) " +
                        "VALUES('ds-public-scenario','Scenario AAPL','SYNTHETIC_PURE','AAPL',19,'{}'::jsonb,'local')");
        var analysis = new AnalysisContext(null, "ds-public-scenario");
        Plan.View plan = plans.create(null, Plan.MarketKind.OBSERVED, null, null,
                new Plan.CreateRequest("public-scenario-lane", "AAPL", "DIRECTIONAL", null, null,
                        "scenario lane", 2, null, "conservative", null, null, null, null));
        ScenarioSpec spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 2, .25, 1901L, 2);
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("AAPL", null, analysis), 100, spec,
                new double[][]{{100, 101, 102}, {100, 98, 99}}, null,
                "paths-scenario-test", LocalDate.of(2026, 7, 10));
        var context = new PlanOutcomeService.ResearchContext(
                "SCENARIO", "observed", "ds-public-scenario");
        var receipt = outcomes.saveResearchEnsemble(null, context, ensemble, IvSpec.flat(.25),
                ScenarioCanvasSpec.defaults(), .04, preview(ensemble),
                Json.parse("{\"operation\":\"PATHS\",\"datasetId\":\"ds-public-scenario\"}"));
        assertThat(db.query("SELECT market_kind || ':' || market_lane || ':' || dataset_id value " +
                        "FROM research_ensemble_receipt WHERE id=?", r -> r.str("value"), receipt.id()))
                .containsExactly("OBSERVED:SCENARIO:ds-public-scenario");
        var promoted = outcomes.promoteResearchEnsemble(null, plan, receipt.id(), receipt.fingerprint(), context);
        assertThat(promoted.ensemble().datasetId()).isEqualTo("ds-public-scenario");
        assertThat(db.query("SELECT dataset_id FROM plan_ensemble WHERE id=?",
                r -> r.str("dataset_id"), promoted.ensemble().id()))
                .containsExactly("ds-public-scenario");
    }

    @Test void previewOnlyMarketLensChangeCreatesANewReceiptAndPromotesWhatWasDisplayed() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("public-preview-identity", "AAPL", "DIRECTIONAL", null, null,
                        "preview identity", 2, null, "conservative", null, null, null, null));
        ScenarioSpec spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 2, .27, 9917L, 2);
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("AAPL", "demo", AnalysisContext.OBSERVED), 100, spec,
                new double[][]{{100, 101, 103}, {100, 99, 98}}, null,
                "paths-preview-test", LocalDate.of(2026, 7, 10));
        var base = preview(ensemble);
        var changed = previewWithMarketLens(base, "2026-07-12T16:05:00Z", .44);
        ObjectNode input = (ObjectNode) Json.parse("{\"operation\":\"PATHS\",\"seed\":9917}");
        var context = new PlanOutcomeService.ResearchContext("DEMO", "demo", "observed");
        var first = outcomes.saveResearchEnsemble(null, context, ensemble, IvSpec.flat(.27),
                ScenarioCanvasSpec.defaults(), .04, base, input);
        var second = outcomes.saveResearchEnsemble(null, context, ensemble, IvSpec.flat(.27),
                ScenarioCanvasSpec.defaults(), .04, changed, input);
        assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(second.id()).isNotEqualTo(first.id());

        var promoted = outcomes.promoteResearchEnsemble(null, plan, second.id(), second.fingerprint(), context);
        assertThat(promoted.preview().at("/marketImplied/atmIv").asDouble()).isEqualTo(.44);
        assertThat(promoted.preview().at("/receipt/asOf").asText()).isEqualTo("2026-07-12T16:05:00Z");
        assertThat(promoted.preview().at("/bands")).isEqualTo(second.preview().at("/bands"));
        assertThat(promoted.preview().at("/samples")).isEqualTo(second.preview().at("/samples"));
    }

    @Test void canvasSurfaceTemplateAndCalendarAnchorRoundTripInFingerprint() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("canvas-receipt-roundtrip", "AAPL", "DIRECTIONAL", null, null,
                        "neutral", 2, null, "conservative", null, null, null, null));
        LocalDate anchor = LocalDate.of(2026, 7, 2);
        ScenarioSpec spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 2, .30, 812L, 2);
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("AAPL", "demo", AnalysisContext.OBSERVED), 100, spec,
                new double[][]{{100, 98, 102}, {100, 101, 104}}, null,
                "paths-calendar-test", anchor);
        var template = new ScenarioCanvasSpec.TemplateReceipt(
                ScenarioCanvasSpec.TemplateKind.HISTORICAL_REPLAY, "owned daily CSV", "OBSERVED",
                anchor, LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 3), 3,
                true, true, "Observed underlying closes; modeled option leg-days.",
                "Completed window with no look-ahead.", "").signed();
        var canvas = new ScenarioCanvasSpec("NYSE", .012, "user-entered dividend", -.25, .08,
                ScenarioCanvasSpec.SurfaceDynamics.STICKY_STRIKE,
                ScenarioCanvasSpec.SettlementPolicy.PHYSICAL_IF_ITM,
                ScenarioCanvasSpec.ExercisePolicy.EXTRINSIC_THRESHOLD,
                List.of(new ScenarioCanvasSpec.IvNode(0, .42),
                        new ScenarioCanvasSpec.IvNode(2, .24)), template);

        var stored = outcomes.saveEnsemble(null, plan, ensemble, IvSpec.flat(.30), canvas, .041,
                null, Json.parse("{\"journey\":\"canvas\"}"));
        var restored = outcomes.loadEnsemble(null, plan.id(), stored.id());

        assertThat(restored.ensemble().anchorDate()).isEqualTo(anchor);
        assertThat(restored.canvas()).isEqualTo(canvas.sane(2));
        assertThat(restored.canvas().template().validFingerprint()).isTrue();
        assertThat(outcomes.canvasSpec(null, plan.id(), stored.id(), 2)).isEqualTo(restored.canvas());
        assertThat(db.query("SELECT anchor_date::text d FROM plan_ensemble_canvas WHERE ensemble_id=?",
                r -> r.str("d"), stored.id())).containsExactly("2026-07-02");
        assertThat(db.query("SELECT day_index FROM plan_ensemble_canvas_iv_node WHERE ensemble_id=? ORDER BY day_index",
                r -> r.intv("day_index"), stored.id())).containsExactly(0, 2);

        var changedSurface = new ScenarioCanvasSpec("NYSE", .012, "user-entered dividend", -.25, .09,
                canvas.surfaceDynamics(), canvas.settlementPolicy(), canvas.exercisePolicy(),
                canvas.ivNodes(), template);
        var changed = outcomes.saveEnsemble(null, plan, ensemble, IvSpec.flat(.30), changedSurface, .041,
                null, Json.parse("{\"journey\":\"canvas\"}"));
        assertThat(changed.fingerprint()).isNotEqualTo(stored.fingerprint());

        var nextAnchor = new PathEnsembleService.Ensemble(ensemble.basis(), ensemble.scope(), ensemble.spot(),
                ensemble.spec(), ensemble.paths(), ensemble.study(), ensemble.modelVersion(), anchor.plusDays(1));
        var movedClock = outcomes.saveEnsemble(null, plan, nextAnchor, IvSpec.flat(.30), canvas, .041,
                null, Json.parse("{\"journey\":\"canvas\"}"));
        assertThat(movedClock.fingerprint()).isNotEqualTo(stored.fingerprint());
    }

    @Test void currentOutcomeArtifactsCannotCrossPlanRevisionsOrAnalysisDatasets() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("outcome-scope-guard", "AAPL", "DIRECTIONAL", null, null,
                        "bullish", 30, null, "conservative", null, null, null, null));
        ScenarioSpec spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 1, 0.25, 4242L, 3);
        var baseline = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("AAPL", "demo", AnalysisContext.OBSERVED), 250, spec,
                new double[][]{{250, 251}, {250, 249}, {250, 252}}, null, "paths-test");
        var stored = outcomes.saveEnsemble(null, plan, baseline, IvSpec.flat(0.25), 0.04, null,
                Json.parse("{\"basis\":\"baseline\"}"));
        assertThat(outcomes.loadCurrentEnsemble(null, plan, stored.id(), AnalysisContext.OBSERVED).id())
                .isEqualTo(stored.id());

        db.exec("INSERT INTO dataset(id,name,kind,symbol,seed,spec) VALUES(?,?,?,?,?,?::jsonb)",
                "ds-outcome-guard", "AAPL alternate", "SYNTHETIC_PURE", "AAPL", 77L, "{}");
        AnalysisContext alternate = new AnalysisContext(null, "ds-outcome-guard");
        var alternateEnsemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("AAPL", "demo", alternate), 250, spec,
                new double[][]{{250, 248}, {250, 253}, {250, 250}}, null, "paths-test");
        var alternateStored = outcomes.saveEnsemble(null, plan, alternateEnsemble, IvSpec.flat(0.25), 0.04,
                null, Json.parse("{\"basis\":\"alternate\"}"));
        assertThatThrownBy(() -> outcomes.loadCurrentEnsemble(null, plan, alternateStored.id(), AnalysisContext.OBSERVED))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("different analysis dataset");

        Plan.View revised = plans.updateContext(null, plan.id(), new Plan.ContextUpdateRequest(plan.version(),
                null, 45, null, null, null, null, null, null, java.util.Set.of()));
        assertThat(revised.context().rev()).isEqualTo(plan.context().rev() + 1);
        assertThat(outcomes.loadEnsemble(null, plan.id(), stored.id()).state()).isEqualTo("STALE");
        assertThatThrownBy(() -> outcomes.loadCurrentEnsemble(null, revised, stored.id(), AnalysisContext.OBSERVED))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("earlier Plan assumptions");
    }

    @Test void exactMatrixIvPathAndPositionOutcomeRoundTripWithoutRegeneration() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("outcome-service-1", "AAPL", "DIRECTIONAL", null, null,
                        "bullish", 3, null, "conservative", null, null, null, null));
        ObjectNode candidate = (ObjectNode) Json.parse("""
                {"strategy":"DEBIT_CALL_SPREAD","displayName":"Bull call spread","structureGroup":"DIRECTIONAL",
                 "label":"BUY 250C / SELL 260C","qty":1,"entryNetPremiumCents":-30000,
	                 "maxProfitCents":70000,"maxLossCents":30000,"breakevens":[253],
	                 "liquidityScore":0.9,"freshness":"FIXTURE","warnings":[],
	                 "confidence":0.8,"intent":"DIRECTIONAL","intents":["DIRECTIONAL"],
	                 "evaluation":{"available":true,"decisionScore":42.0,"viable":true,
	                   "capital":{},"volatility":{},"risk":{"pop":0.45},"evidence":{},
	                   "management":{},"score":{},
	                   "assessment":{"mechanics":{"eligible":true,"reasons":[]},
	                     "economics":{"verdict":"MIXED","placement":"LEARN_FROM",
	                       "label":"Mixed","summary":"Costs matter","marketEvAfterCostsCents":-900,
	                       "estimatedRoundTripFeesCents":520,"observedEvidence":false,"reasons":["Costs"]}},
	                   "stance":{},"participation":{},"impliedStance":{},"ivContext":{},
	                   "coverage":{},"explanation":{}},
                 "legs":[
                   {"action":"BUY","type":"CALL","strike":"250","expiration":"2026-08-21","ratio":1,"multiplier":100,"entryPrice":"7","positionEffect":"OPEN"},
                   {"action":"SELL","type":"CALL","strike":"260","expiration":"2026-08-21","ratio":1,"multiplier":100,"entryPrice":"4","positionEffect":"OPEN"}]}
                """);
        strategies.saveCustom(null, plan, Json.parse("{\"source\":\"BUILDER\"}"), candidate, plan.version(), true);
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

        ObjectNode latest = outcomes.latest(null, plan, AnalysisContext.OBSERVED);
        assertThat(latest.at("/outcomes/0/ensembleId").asText()).isEqualTo(stored.id());
        assertThat(latest.at("/outcomes/0/p50Cents").asLong()).isEqualTo(5000);
        assertThat(latest.at("/outcomes/0/bands/1/p90Cents").asLong()).isEqualTo(28000);
        assertThat(latest.at("/outcomes/0/notes/0").asText()).isEqualTo("Exact stored paths");

        var baselineComparison = outcomes.saveComparison(null, plan, plan.version(), restored, List.of(
                        new PlanOutcomeService.ComparisonItem(candidateId, candidateId, 1,
                                "DEBIT_CALL_SPREAD", "Bull call spread", 1, 30000L,
                                30000L, 66.6667, 2000L, -25000L, 5000L, 30000L, 0.08, 520,
                                "MIXED", "LEARN_FROM", true, 70.0, true, null),
                        new PlanOutcomeService.ComparisonItem("CASH", null, 2, "CASH", "Keep cash",
                                0, 0L, 0L, null, 0L, 0L, 0L, 0L, 0.0, 0,
                                null, "BASELINE", true, null, false, null)),
                Json.parse("{\"basis\":\"PARAMETRIC\"}"),
                "Every Plan proposal on the exact stored futures",
                "Same entry snapshot and ensemble fingerprint");
        assertThat(baselineComparison.ensembleFingerprint()).isEqualTo(stored.fingerprint());
        ObjectNode withComparison = outcomes.latest(null, plan, AnalysisContext.OBSERVED);
        assertThat(withComparison.at("/comparisons/0/ensembleFingerprint").asText())
                .isEqualTo(stored.fingerprint());
        assertThat(withComparison.at("/comparisons/0/items/0/displayName").asText())
                .isEqualTo("Bull call spread");
        assertThat(withComparison.at("/comparisons/0/items/1/key").asText()).isEqualTo("CASH");

        ObjectNode firstReport = (ObjectNode) Json.parse("""
                {"id":"bt-old","symbol":"AAPL","strategy":"DEBIT_CALL_SPREAD","from":"2025-01-02","to":"2025-06-30",
                 "pricingMode":"MODELED_FROM_UNDERLYING","confidence":"modeled","sampleSize":12,"winRate":0.50,
                 "avgReturnOnRisk":0.08,"startingCents":10000000,"endingCents":10096000,"maxDrawdownPct":0.06,"demoUnderlying":true}
                """);
        insertBacktest(firstReport, "2026-07-12T15:00:00Z");
        outcomes.saveBacktest(null, plan, plan.version(), candidateId, "single", firstReport,
                Json.parse("{\"targetDte\":30}"), AnalysisContext.OBSERVED);
        ObjectNode secondReport = firstReport.deepCopy();
        secondReport.put("id", "bt-current"); secondReport.put("sampleSize", 18); secondReport.put("winRate", 0.61);
        insertBacktest(secondReport, "2026-07-12T16:00:00Z");
        outcomes.saveBacktest(null, plan, plan.version(), candidateId, "portfolio", secondReport,
                Json.parse("{\"targetDte\":45}"), AnalysisContext.OBSERVED);

        ObjectNode withHistory = outcomes.latest(null, plan, AnalysisContext.OBSERVED);
        assertThat(withHistory.at("/backtests")).hasSize(2);
        assertThat(withHistory.at("/backtests/0/backtestId").asText()).isEqualTo("bt-current");
        assertThat(withHistory.at("/backtests/0/evidenceProvenance").asText()).isEqualTo("DEMO_FIXTURE");
        assertThat(db.query("SELECT DISTINCT evidence_provenance FROM plan_backtest",
                r -> r.str("evidence_provenance"))).containsExactly("DEMO_FIXTURE");
        assertThat(withHistory.at("/backtests/0/state").asText()).isEqualTo("CURRENT");
        assertThat(withHistory.at("/backtests/0/currentContext").asBoolean()).isTrue();
        assertThat(withHistory.at("/backtests/1/state").asText()).isEqualTo("STALE");

        db.exec("INSERT INTO dataset(id,name,kind,symbol,seed,spec) VALUES(?,?,?,?,?,?::jsonb)",
                "ds-plan-scope", "AAPL alternate history", "SYNTHETIC_PURE", "AAPL", 44L, "{}");
        AnalysisContext scenarioAnalysis = new AnalysisContext(null, "ds-plan-scope");
        double[][] alternatePaths = {
                {250, 248, 246, 244}, {250, 253, 257, 262}, {250, 250, 251, 252}
        };
        var alternateEnsemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("AAPL", "demo", scenarioAnalysis), 250, spec,
                alternatePaths, null, "paths-test");
        var alternateStored = outcomes.saveEnsemble(null, plan, alternateEnsemble, iv, 0.04, null,
                Json.parse("{\"shape\":\"CHOP\",\"dataset\":\"alternate\"}"));
        ObjectNode alternateResult = result.deepCopy();
        alternateResult.put("p50Cents", 9000);
        outcomes.savePathOutcome(null, plan, plan.version(), candidateId, alternateStored, alternateResult,
                Json.parse("{\"basis\":\"PARAMETRIC\",\"dataset\":\"alternate\"}"), "alternate history");
        outcomes.saveComparison(null, plan, plan.version(), alternateStored, List.of(
                        new PlanOutcomeService.ComparisonItem(candidateId, candidateId, 1,
                                "DEBIT_CALL_SPREAD", "Bull call spread", 1, 30000L,
                                30000L, 75.0, 6000L, -18000L, 9000L, 34000L, 0.333, 520,
                                "MIXED", "LEARN_FROM", true, 70.0, true, null),
                        new PlanOutcomeService.ComparisonItem("CASH", null, 2, "CASH", "Keep cash",
                                0, 0L, 0L, null, 0L, 0L, 0L, 0L, 0.0, 0,
                                null, "BASELINE", true, null, false, null)),
                Json.parse("{\"basis\":\"PARAMETRIC\",\"dataset\":\"alternate\"}"),
                "Every Plan proposal on the alternate stored futures",
                "Same entry snapshot and alternate ensemble fingerprint");

        assertThat(outcomes.latest(null, plan, AnalysisContext.OBSERVED).at("/outcomes/0/p50Cents").asLong())
                .as("the observed/demo baseline result remains current in its own analysis lane")
                .isEqualTo(5000);
        assertThat(outcomes.latest(null, plan, scenarioAnalysis).at("/outcomes/0/p50Cents").asLong())
                .as("the generated dataset restores only its own result")
                .isEqualTo(9000);
        assertThat(outcomes.latest(null, plan, AnalysisContext.OBSERVED)
                .at("/comparisons/0/items/0/p50Cents").asLong()).isEqualTo(5000);
        assertThat(outcomes.latest(null, plan, scenarioAnalysis)
                .at("/comparisons/0/items/0/p50Cents").asLong()).isEqualTo(9000);
        assertThat(outcomes.latestEnsemble(null, plan, "PARAMETRIC", AnalysisContext.OBSERVED).id())
                .isEqualTo(stored.id());
        assertThat(outcomes.latestEnsemble(null, plan, "PARAMETRIC", scenarioAnalysis).id())
                .isEqualTo(alternateStored.id());

        ObjectNode scenarioReport = firstReport.deepCopy();
        scenarioReport.put("id", "bt-scenario"); scenarioReport.put("sampleSize", 9);
        insertBacktest(scenarioReport, "2026-07-12T16:30:00Z");
        outcomes.saveBacktest(null, plan, plan.version(), candidateId, "single", scenarioReport,
                Json.parse("{\"targetDte\":30}"), scenarioAnalysis);
        ObjectNode observedHistory = outcomes.latest(null, plan, AnalysisContext.OBSERVED);
        ObjectNode scenarioHistory = outcomes.latest(null, plan, scenarioAnalysis);
        java.util.function.BiFunction<ObjectNode, String, com.fasterxml.jackson.databind.JsonNode> replay =
                (document, id) -> java.util.stream.StreamSupport.stream(document.withArray("backtests").spliterator(), false)
                        .filter(row -> id.equals(row.path("backtestId").asText())).findFirst().orElseThrow();
        assertThat(replay.apply(observedHistory, "bt-current").path("currentContext").asBoolean()).isTrue();
        assertThat(replay.apply(observedHistory, "bt-scenario").path("currentContext").asBoolean()).isFalse();
        assertThat(replay.apply(scenarioHistory, "bt-scenario").path("currentContext").asBoolean()).isTrue();
        assertThat(replay.apply(scenarioHistory, "bt-current").path("currentContext").asBoolean()).isFalse();

        db.exec("INSERT INTO accounts(id,name,type,starting_cash_cents,cash_cents,reserved_cents,has_traded," +
                        "created_at,updated_at) VALUES(?,?,?,?,?,?,0,?,?)",
                "acct-decision", "Decision account", "DEMO", 10_000_000L, 10_000_000L, 0L,
                "2026-07-12T16:00:00Z", "2026-07-12T16:00:00Z");
        Account account = new Account("acct-decision", "Decision account", "DEMO", 10_000_000L,
                10_000_000L, 0L, false, "2026-07-12T16:00:00Z", "2026-07-12T16:00:00Z", null);
        TradePreview preview = new TradePreview(true, List.of(), List.of(), -30_000L, 65L,
                30_000L, 70_000L, List.of("253"), 0.45, -900L, 0L,
                10_000_000L, 9_969_935L, 0L, 0L, 10_000_000L, 9_969_935L,
                "FIXTURE", DataEvidence.of("fixture", Freshness.FIXTURE), 25_000L, null,
                List.of(Map.ofEntries(Map.entry("action", "BUY"), Map.entry("type", "CALL"),
                        Map.entry("strike", "250"), Map.entry("expiration", "2026-08-21"),
                        Map.entry("ratio", 1), Map.entry("multiplier", 100), Map.entry("bid", "6.9123"),
                        Map.entry("ask", "7.0456"), Map.entry("mid", "6.97895"),
                        Map.entry("fill", "7.0456"), Map.entry("iv", 0.3))), List.of(),
                Map.of("probabilityMap", Map.of("pMaxProfit", 0.2, "pMaxLoss", 0.3,
                        "cvar95Cents", -28_000L)));
        EconomicAssessment economics = new EconomicAssessment(EconomicAssessment.Verdict.MIXED,
                "LEARN_FROM", "Mixed", "Costs matter", -1_420L, 480L, 520L,
                -4.7, false, List.of("Generated evidence"));
        PlanDecisionService decisions = new PlanDecisionService(db,
                Clock.fixed(Instant.parse("2026-07-12T16:00:00Z"), ZoneOffset.UTC));
        ObjectNode decision = decisions.chooseCash(new PlanDecisionService.Input(null, plan, plan.version(),
                candidateId, account, preview, economics,
                new AccountRiskContext(null, null, null, null, null), 1, List.of(), "Kept cash",
                AnalysisContext.OBSERVED));
        assertThat(decision.path("ensembleId").asText()).isEqualTo(stored.id());
        assertThat(decision.at("/legs/0/bidPrice").asText()).isEqualTo("6.9123");
        assertThat(decision.at("/legs/0/askPrice").asText()).isEqualTo("7.0456");
        assertThat(decision.at("/legs/0/midPrice").asText()).isEqualTo("6.97895");
        assertThat(decision.at("/legs/0/fillPrice").asText()).isEqualTo("7.0456");
        assertThat(db.query("SELECT ea.pinned FROM ensemble_artifact ea JOIN plan_ensemble pe " +
                        "ON pe.fingerprint=ea.fingerprint WHERE pe.id=?", row -> row.bool("pinned"), stored.id()))
                .containsExactly(true);
    }

    private static SimulationEngine.Preview preview(PathEnsembleService.Ensemble ensemble) {
        var terminal = new SimulationEngine.TerminalDistribution(97, 98, 101, 102, 103,
                100.5, 2.0, 1.0);
        var level = new SimulationEngine.LevelOdds("target", 102, "ABOVE",
                .4, .6, .4, .5, .2, .8, 1.0);
        var decisionMap = new SimulationEngine.DecisionMap(terminal, List.of(level), .15);
        var receipt = new SimulationEngine.EnsembleReceipt("path-preview", ensemble.scope().symbol(),
                ensemble.scope().worldId(), ensemble.scope().analysis().datasetId(), CLOCK.instant().toString(),
                ensemble.spot(), "fixture", "FIXTURE", false, "fixture path", ensemble.modelVersion(),
                ensemble.spec());
        return new SimulationEngine.Preview(ensemble.scope().symbol(), ensemble.spot(),
                ensemble.paths().length, ensemble.spec().horizonDays(), ensemble.modelVersion(),
                List.of(new SimulationEngine.PreviewBand(0, ensemble.spot(), ensemble.spot(), ensemble.spot()),
                        new SimulationEngine.PreviewBand(ensemble.spec().horizonDays(), 97, 101, 103)),
                List.of(List.of(ensemble.spot(), ensemble.paths()[0][ensemble.paths()[0].length - 1])),
                97, 101, 103, decisionMap, null, receipt, List.of("Possible futures, not a forecast"));
    }

    private static SimulationEngine.Preview previewWithMarketLens(SimulationEngine.Preview base,
                                                                  String asOf, double atmIv) {
        var old = base.receipt();
        var receipt = new SimulationEngine.EnsembleReceipt(old.fingerprint(), old.symbol(), old.worldId(),
                old.datasetId(), asOf, old.anchorSpot(), old.anchorSource(), old.anchorFreshness(),
                old.anchorExecutable(), old.anchorLimitation(), old.modelVersion(), old.spec());
        var market = new SimulationEngine.MarketImpliedRange(atmIv, "2026-08-21",
                base.horizonDays(), 40, 92, 100, 108,
                "Risk-neutral listed-options lens; not a forecast");
        return new SimulationEngine.Preview(base.symbol(), base.spot(), base.paths(), base.horizonDays(),
                base.pathModelVersion(), base.bands(), base.samples(), base.endP10(), base.endP50(),
                base.endP90(), base.decisionMap(), market, receipt, base.notes());
    }

    private void insertBacktest(ObjectNode report, String createdAt) {
        db.exec("INSERT INTO backtests(id,user_id,created_at,run_kind,symbol,strategy,from_date,to_date," +
                        "pricing_mode,confidence,days_covered,sample_size,starting_cents,ending_cents," +
                        "max_drawdown_pct,demo_underlying,disclaimer) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                report.path("id").asText(), "local", createdAt, "SINGLE", report.path("symbol").asText(),
                report.path("strategy").asText(), report.path("from").asText(), report.path("to").asText(),
                report.path("pricingMode").asText(), report.path("confidence").asText(),
                report.path("daysCovered").asInt(0), report.path("sampleSize").asInt(),
                report.path("startingCents").asLong(), report.path("endingCents").asLong(),
                report.path("maxDrawdownPct").asDouble(), report.path("demoUnderlying").asBoolean() ? 1 : 0,
                "Plan outcome test backtest");
    }
}
