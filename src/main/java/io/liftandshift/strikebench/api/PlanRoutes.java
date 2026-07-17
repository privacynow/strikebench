package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;

/** Canonical HTTP surface for the Plan-centered journey. */
public final class PlanRoutes {
    public record Handlers(
            Handler list,
            Handler create,
            Handler adopt,
            Handler portfolio,
            Handler get,
            Handler updateContext,
            Handler claimIntent,
            Handler advanceProgress,
            Handler updateOpen,
            Handler archive,
            Handler delete,
            Handler latestEvidence,
            Handler runEvidenceStudy,
            Handler latestStrategy,
            Handler runStrategy,
            Handler fitStrategy,
            Handler customStrategy,
            Handler selectStrategy,
            Handler clearStrategySelection,
            Handler latestScout,
            Handler runScout,
            Handler spawnScoutPlan,
            Handler latestOutcomes,
            Handler latestEnsemble,
            Handler runEnsemble,
            Handler runOutcome,
            Handler compareOutcomes,
            Handler saveScenario,
            Handler listScenarios,
            Handler getScenario,
            Handler runBacktest,
            Handler getBacktest,
            Handler listRehearsals,
            Handler createRehearsal,
            Handler latestDecision,
            Handler previewDecision,
            Handler placeDecisionTrade,
            Handler chooseCashDecision,
            Handler recordDecisionBroker,
            Handler manage,
            Handler refreshManagement,
            Handler review
    ) {}

    private PlanRoutes() {}

    public static void register(JavalinConfig config, Handlers h) {
        config.routes.get("/api/plans", h.list());
        config.routes.post("/api/plans", h.create());
        config.routes.post("/api/plans/adopt", h.adopt());
        config.routes.get("/api/plans/portfolio", h.portfolio());
        config.routes.get("/api/plans/{id}", h.get());
        config.routes.put("/api/plans/{id}/context", h.updateContext());
        config.routes.put("/api/plans/{id}/intent", h.claimIntent());
        config.routes.post("/api/plans/{id}/progress", h.advanceProgress());
        config.routes.put("/api/plans/{id}/open", h.updateOpen());
        config.routes.post("/api/plans/{id}/archive", h.archive());
        config.routes.delete("/api/plans/{id}", h.delete());
        config.routes.get("/api/plans/{id}/evidence/latest", h.latestEvidence());
        config.routes.post("/api/plans/{id}/evidence/study", h.runEvidenceStudy());
        config.routes.get("/api/plans/{id}/strategy/latest", h.latestStrategy());
        config.routes.post("/api/plans/{id}/strategy/run", h.runStrategy());
        config.routes.post("/api/plans/{id}/strategy/fit", h.fitStrategy());
        config.routes.post("/api/plans/{id}/strategy/custom", h.customStrategy());
        config.routes.put("/api/plans/{id}/strategy/select", h.selectStrategy());
        config.routes.delete("/api/plans/{id}/strategy/selection", h.clearStrategySelection());
        config.routes.get("/api/plans/{id}/scout/latest", h.latestScout());
        config.routes.post("/api/plans/{id}/scout/run", h.runScout());
        config.routes.post("/api/plans/{id}/scout/spawn", h.spawnScoutPlan());
        config.routes.get("/api/plans/{id}/outcomes/latest", h.latestOutcomes());
        config.routes.get("/api/plans/{id}/outcomes/ensemble/latest", h.latestEnsemble());
        config.routes.post("/api/plans/{id}/outcomes/ensemble", h.runEnsemble());
        config.routes.post("/api/plans/{id}/outcomes/run", h.runOutcome());
        config.routes.post("/api/plans/{id}/outcomes/compare", h.compareOutcomes());
        config.routes.post("/api/plans/{id}/scenarios", h.saveScenario());
        config.routes.get("/api/plans/{id}/scenarios", h.listScenarios());
        config.routes.get("/api/plans/{id}/scenarios/{scenarioId}", h.getScenario());
        config.routes.post("/api/plans/{id}/outcomes/backtest", h.runBacktest());
        config.routes.get("/api/plans/{id}/outcomes/backtests/{backtestId}", h.getBacktest());
        config.routes.get("/api/plans/{id}/rehearsals", h.listRehearsals());
        config.routes.post("/api/plans/{id}/rehearsals", h.createRehearsal());
        config.routes.get("/api/plans/{id}/decision/latest", h.latestDecision());
        config.routes.post("/api/plans/{id}/decision/preview", h.previewDecision());
        config.routes.post("/api/plans/{id}/decision/trade", h.placeDecisionTrade());
        config.routes.post("/api/plans/{id}/decision/cash", h.chooseCashDecision());
        config.routes.post("/api/plans/{id}/decision/broker", h.recordDecisionBroker());
        config.routes.get("/api/plans/{id}/manage", h.manage());
        config.routes.post("/api/plans/{id}/manage/refresh", h.refreshManagement());
        config.routes.post("/api/plans/{id}/manage/review", h.review());
    }
}
