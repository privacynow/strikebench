package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;
import io.liftandshift.strikebench.backtest.Backtester;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.plan.PlanEvidenceService;
import io.liftandshift.strikebench.plan.PlanOutcomeService;
import io.liftandshift.strikebench.plan.PlanService;
import io.liftandshift.strikebench.plan.PlanStrategyService;
import io.liftandshift.strikebench.sim.PathEnsembleService;
import io.liftandshift.strikebench.sim.SimulationEngine;
import io.liftandshift.strikebench.util.Json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Owns Plan outcome ensembles, exact-position valuation, comparisons, and historical replays. */
final class PlanOutcomeController {
    private final PlanController root;
    private final AppConfig cfg;
    private final MarketDataService market;
    private final Backtester backtester;
    private final PlanService planSvc;
    private final PlanEvidenceService planEvidence;
    private final PlanStrategyService planStrategy;
    private final PlanOutcomeService planOutcomes;
    private final PathEnsembleService pathEnsembles;
    private final SimulationEngine simEngine;
    private final OutcomeController outcomeController;

    PlanOutcomeController(PlanController root, AppConfig cfg,
                          MarketDataService market, Backtester backtester,
                          PlanService planSvc, PlanEvidenceService planEvidence,
                          PlanStrategyService planStrategy, PlanOutcomeService planOutcomes,
                          PathEnsembleService pathEnsembles, SimulationEngine simEngine,
                          OutcomeController outcomeController) {
        this.root = root;
        this.cfg = cfg;
        this.market = market;
        this.backtester = backtester;
        this.planSvc = planSvc;
        this.planEvidence = planEvidence;
        this.planStrategy = planStrategy;
        this.planOutcomes = planOutcomes;
        this.pathEnsembles = pathEnsembles;
        this.simEngine = simEngine;
        this.outcomeController = outcomeController;
    }

    public record PlanEnsembleRequest(Long expectedVersion,
                                      io.liftandshift.strikebench.sim.ScenarioSpec over,
                                      io.liftandshift.strikebench.sim.IvSpec iv,
                                      List<io.liftandshift.strikebench.sim.SimulationEngine.DecisionLevel> levels) {}
    public record PlanOutcomeRunRequest(Long expectedVersion, String basis, String ensembleId,
                                       io.liftandshift.strikebench.sim.ScenarioSpec over,
                                       io.liftandshift.strikebench.sim.IvSpec iv) {}
    public record PlanOutcomeCompareRequest(Long expectedVersion, String basis, String ensembleId,
                                            List<String> candidateIds,
                                            io.liftandshift.strikebench.sim.ScenarioSpec over,
                                            io.liftandshift.strikebench.sim.IvSpec iv) {}
    public record PlanBacktestRequest(Long expectedVersion, String engine, String from, String to,
                                      Integer targetDte, Integer entryEveryDays, Integer maxConcurrent,
                                      Integer qty, Double slippagePct, Long startingCashCents,
                                      Double shortDelta, Double widthPct, Double profitTargetPct,
                                      Double stopFraction, Integer rollDte) {}
    void planOutcomesLatest(Context ctx) {
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        ObjectNode out = planOutcomes.latest(root.ownerId(ctx), plan, root.analysisCtx(ctx));
        JsonNode selected = planStrategy.selectedCandidate(root.ownerId(ctx), plan.id());
        ctx.json(new ApiResponses.PlanOutcomesLatest<>(out.path("outcomes"), out.path("comparisons"),
                out.path("backtests"), selected));
    }

    /** Evidence owns path generation; Outcomes later values the exact selected package on this artifact. */
    void planEnsembleRun(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanEnsembleRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        var spec = planScenarioSpec(plan, body.over());
        var world = PlanController.worldParam(root.activeWorld(ctx));
        var marketVol = outcomeController.marketVol(plan.symbol(), world, spec.horizonDays());
        var calibrated = spec.volAnnual() > 0 || marketVol == null ? spec : spec.withVol(marketVol.atmIv());
        double rate = market.riskFreeRateQuote(Math.max(1, calibrated.horizonDays()), world).annualRate();
        var run = simEngine.previewRun(plan.symbol(), calibrated, world, root.analysisCtx(ctx),
                body.levels() == null ? List.of() : body.levels(), marketVol, rate);
        var iv = body.iv() == null ? defaultPlanIv(run.ensemble().spec(), marketVol) : body.iv();
        JsonNode input = Json.MAPPER.valueToTree(body);
        var stored = planOutcomes.saveEnsemble(root.ownerId(ctx), plan, run.ensemble(), iv, rate, run.preview(), input);
        ObjectNode preview = Json.MAPPER.valueToTree(run.preview());
        preview.put("planEnsembleId", stored.id());
        preview.put("planEnsembleFingerprint", stored.fingerprint());
        if (preview.path("receipt") instanceof ObjectNode receipt) receipt.put("fingerprint", stored.fingerprint());
        ctx.json(new ApiResponses.PlanEnsemble<>(plan,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis()), preview));
    }

    void planOutcomeRun(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanOutcomeRunRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = root.selectedCandidate(ctx, plan, true);
        var position = planOutcomePosition(candidate);
        String basisName = body.basis() == null ? "PARAMETRIC" : body.basis().trim().toUpperCase(Locale.ROOT);
        JsonNode input = Json.MAPPER.valueToTree(body);
        if ("RISK_NEUTRAL".equals(basisName)) {
            var request = new io.liftandshift.strikebench.outcomes.OutcomeContract.Request(
                    io.liftandshift.strikebench.outcomes.OutcomeContract.Operation.POSITION,
                    io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.RISK_NEUTRAL,
                    planOutcomeContext(ctx, plan), position, null, null, null, null, null, null);
            var evaluated = outcomeController.evaluateOutcomes(ctx, request);
            JsonNode result = Json.MAPPER.valueToTree(evaluated.result());
            var saved = planOutcomes.saveRiskNeutral(root.ownerId(ctx), plan, body.expectedVersion(),
                    candidate.path("id").asText(), result, input, evaluated.interpretation(), root.analysisCtx(ctx));
            ctx.json(new ApiResponses.PlanOutcome<>(plan, saved));
            return;
        }
        io.liftandshift.strikebench.sim.PathEnsembleService.Basis basis;
        try { basis = io.liftandshift.strikebench.sim.PathEnsembleService.Basis.valueOf(basisName); }
        catch (Exception e) { throw new IllegalArgumentException("basis must be RISK_NEUTRAL, PARAMETRIC, HISTORICAL_ANALOGS, or CONDITIONAL_BOOTSTRAP"); }
        var stored = resolvePlanEnsemble(ctx, plan, body, basis);
        var pathPosition = outcomeController.toPathPosition(ctx, position.legs());
        var simRequest = new OutcomeController.StrategySimRequest(plan.symbol(), pathPosition, position.qty(),
                stored.ensemble().spec(), stored.iv(), basis, null, position.entryCostCents(),
                outcomeController.contractExpirations(position.legs()));
        JsonNode result = Json.MAPPER.valueToTree(
                outcomeController.simStrategyResult(ctx, simRequest, stored.ensemble()));
        String interpretation = switch (basis) {
            case PARAMETRIC -> "The exact selected package is repriced on the same stored model ensemble shown in Evidence.";
            case HISTORICAL_ANALOGS -> "The exact selected package is repriced over the Plan's stored matching historical occurrences.";
            case CONDITIONAL_BOOTSTRAP -> "The exact selected package is repriced over whole-path resamples of the Plan's stored analog sample.";
        };
        var saved = planOutcomes.savePathOutcome(root.ownerId(ctx), plan, body.expectedVersion(),
                candidate.path("id").asText(), stored, result, input, interpretation);
        ctx.json(new ApiResponses.PlanOutcomeWithEnsemble<>(plan, saved,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis())));
    }

    /** Compare the Plan's current proposals on one exact stored path artifact. */
    void planOutcomeCompare(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanOutcomeCompareRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        String basisName = body.basis() == null ? "PARAMETRIC" : body.basis().trim().toUpperCase(Locale.ROOT);
        io.liftandshift.strikebench.sim.PathEnsembleService.Basis basis;
        try { basis = io.liftandshift.strikebench.sim.PathEnsembleService.Basis.valueOf(basisName); }
        catch (Exception e) {
            throw new IllegalArgumentException("comparison basis must be PARAMETRIC, HISTORICAL_ANALOGS, or CONDITIONAL_BOOTSTRAP");
        }
        var stored = resolvePlanEnsemble(ctx, plan,
                new PlanOutcomeRunRequest(body.expectedVersion(), basisName, body.ensembleId(), body.over(), body.iv()), basis);

        java.util.Set<String> wanted = body.candidateIds() == null || body.candidateIds().isEmpty()
                ? null : body.candidateIds().stream().filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        LinkedHashMap<String, ObjectNode> field = new LinkedHashMap<>();
        var competition = planStrategy.latestCompetition(root.ownerId(ctx), plan.id());
        if (competition != null) for (JsonNode node : competition.result().path("candidates")) {
            if (!(node instanceof ObjectNode candidate)) continue;
            String id = candidate.path("id").asText();
            if (!id.isBlank() && (wanted == null || wanted.contains(id))) field.put(id, candidate);
        }
        JsonNode selectedNode = planStrategy.selectedCandidate(root.ownerId(ctx), plan.id());
        if (selectedNode instanceof ObjectNode selected) {
            String id = selected.path("id").asText();
            if (!id.isBlank() && (wanted == null || wanted.contains(id))) field.putIfAbsent(id, selected);
        }
        if (field.isEmpty()) throw new IllegalStateException("Run the Strategy comparison before comparing proposal outcomes.");
        if (field.size() > 32) throw new IllegalArgumentException("at most 32 current Plan proposals can be compared together");

        List<io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem> simItems = new ArrayList<>();
        LinkedHashMap<String, PlanComparisonMeta> metadata = new LinkedHashMap<>();
        LinkedHashMap<String, String> earlyRefusals = new LinkedHashMap<>();
        for (ObjectNode candidate : field.values()) {
            String id = candidate.path("id").asText();
            int qty = Math.clamp(candidate.path("qty").asInt(1), 1, 100);
            var position = planOutcomePosition(candidate);
            long fees = candidate.path("economics").path("estimatedRoundTripFeesCents").isNumber()
                    ? candidate.path("economics").path("estimatedRoundTripFeesCents").longValue()
                    : position.legs().stream().filter(leg -> !"STOCK".equalsIgnoreCase(leg.type()))
                    .mapToLong(leg -> Math.max(1, leg.ratio())).sum() * qty * cfg.feePerContractCents() * 2L
                    + (position.legs().isEmpty() ? 0 : cfg.feePerOrderCents() * 2L);
            metadata.put(id, new PlanComparisonMeta(candidate, position, qty, fees));
            try {
                var pathPosition = outcomeController.toPathPosition(ctx, position.legs());
                simItems.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem(
                        id, pathPosition, position.entryCostCents(),
                        "entry fixed to the Plan proposal's captured executable package", fees, qty));
            } catch (RuntimeException e) {
                earlyRefusals.put(id, io.liftandshift.strikebench.sim.ScenarioSimulator.publicReason(e));
            }
        }

        LinkedHashMap<String, io.liftandshift.strikebench.sim.ScenarioSimulator.SimResult> results = new LinkedHashMap<>();
        LinkedHashMap<String, String> refusals = new LinkedHashMap<>(earlyRefusals);
        if (!simItems.isEmpty()) {
            var compared = new io.liftandshift.strikebench.sim.ScenarioSimulator().compare(
                    stored.ensemble(), simItems, 1, stored.iv(), stored.rateAnnual());
            for (var outcome : compared.report().results()) results.put(outcome.key(), outcome.result());
            for (var refusal : compared.report().refused()) refusals.put(refusal.key(), refusal.reason());
        }

        List<io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem> items = new ArrayList<>();
        for (var entry : metadata.entrySet()) {
            String id = entry.getKey();
            PlanComparisonMeta meta = entry.getValue();
            ObjectNode candidate = meta.candidate();
            var result = results.get(id);
            Long p5 = result == null ? null : result.p5Cents();
            Long expected = result == null ? null : result.expectedPnlCents();
            Double tailScore = expected == null || p5 == null ? null
                    : expected.doubleValue() / Math.max(100.0, Math.max(0.0, -p5.doubleValue()));
            String display = candidate.path("displayName").asText(candidate.path("strategy").asText("Structure"));
            items.add(new io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem(
                    id, id, 0, candidate.path("strategy").asText("CUSTOM"), display, meta.qty(),
                    meta.position().entryCostCents(), candidate.hasNonNull("maxLossCents")
                            ? candidate.path("maxLossCents").longValue() : null,
                    result == null ? null : result.winRatePct(), expected, p5,
                    result == null ? null : result.p50Cents(), result == null ? null : result.p95Cents(),
                    tailScore, meta.roundTripFees(), candidate.path("economicVerdict").asText(null),
                    candidate.path("economicPlacement").asText(null),
                    candidate.hasNonNull("structurallyEligible") ? candidate.path("structurallyEligible").asBoolean() : null,
                    candidate.hasNonNull("decisionScore") ? candidate.path("decisionScore").doubleValue() : null,
                    candidate.path("selected").asBoolean(false), refusals.get(id)));
        }
        items.add(new io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem(
                "CASH", null, 0, "CASH", "Keep cash", 0, 0L, 0L,
                null, 0L, 0L, 0L, 0L, 0.0, 0L, null, "BASELINE", true, null, false, null));
        items.sort((a, b) -> {
            if ((a.refusalReason() != null) != (b.refusalReason() != null)) return a.refusalReason() == null ? -1 : 1;
            double as = a.tailReturnScore() == null ? Double.NEGATIVE_INFINITY : a.tailReturnScore();
            double bs = b.tailReturnScore() == null ? Double.NEGATIVE_INFINITY : b.tailReturnScore();
            int score = Double.compare(bs, as);
            if (score != 0) return score;
            return a.displayName().compareToIgnoreCase(b.displayName());
        });
        List<io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem> ranked = new ArrayList<>();
        int rank = 0;
        for (var item : items) ranked.add(new io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem(
                item.key(), item.candidateId(), ++rank, item.strategy(), item.displayName(), item.qty(),
                item.entryCostCents(), item.maxLossCents(), item.winRatePct(), item.expectedPnlCents(),
                item.p5Cents(), item.p50Cents(), item.p95Cents(), item.tailReturnScore(),
                item.roundTripFeesCents(), item.economicVerdict(), item.economicPlacement(),
                item.mechanicallyEligible(), item.decisionScore(), item.selected(), item.refusalReason()));

        String interpretation = switch (basis) {
            case PARAMETRIC -> "Every current Plan proposal repriced on the exact stored model futures.";
            case HISTORICAL_ANALOGS -> "Every current Plan proposal repriced on the exact matching historical occurrences.";
            case CONDITIONAL_BOOTSTRAP -> "Every current Plan proposal repriced on whole-path resamples of the same analog sample.";
        };
        String fairness = "Same ensemble " + stored.fingerprint() + ", captured proposal entries, quantities, and after-cost convention; cash is the zero-risk baseline.";
        var saved = planOutcomes.saveComparison(root.ownerId(ctx), plan, body.expectedVersion(), stored, ranked,
                Json.MAPPER.valueToTree(body), interpretation, fairness);
        ctx.json(new ApiResponses.PlanComparison<>(plan, saved,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis())));
    }

    private record PlanComparisonMeta(ObjectNode candidate,
                                      io.liftandshift.strikebench.outcomes.OutcomeContract.Position position,
                                      int qty, long roundTripFees) {}

    void planBacktestRun(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanBacktestRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = root.selectedCandidate(ctx, plan, true);
        String family = candidate.path("strategy").asText();
        if (family.isBlank() || "CUSTOM".equals(family)) {
            throw new IllegalArgumentException("Historical replay needs a named strategy rule; model futures still test the exact custom package.");
        }
        String engineKind = body.engine() == null ? "single" : body.engine().trim().toLowerCase(Locale.ROOT);
        String world = PlanController.worldParam(root.activeWorld(ctx));
        Object report;
        if ("portfolio".equals(engineKind)) {
            report = backtester.runPortfolio(new Backtester.PortfolioRequest(plan.symbol(), family, body.from(), body.to(),
                    body.targetDte() == null ? plan.context().horizonDays() : body.targetDte(), body.entryEveryDays(),
                    body.maxConcurrent(), body.qty(), body.shortDelta(), body.widthPct(), body.profitTargetPct(),
                    body.stopFraction(), body.rollDte(), body.startingCashCents()), root.analysisCtx(ctx),
                    root.ownerId(ctx), world);
        } else if ("single".equals(engineKind)) {
            report = backtester.run(new Backtester.BacktestRequest(plan.symbol(), family, body.from(), body.to(),
                    body.targetDte() == null ? plan.context().horizonDays() : body.targetDte(), body.entryEveryDays(),
                    body.qty(), body.slippagePct(), body.startingCashCents()), root.analysisCtx(ctx),
                    root.ownerId(ctx), world);
        } else throw new IllegalArgumentException("engine must be single or portfolio");
        JsonNode reportJson = Json.MAPPER.valueToTree(report);
        var saved = planOutcomes.saveBacktest(root.ownerId(ctx), plan, body.expectedVersion(),
                candidate.path("id").asText(), engineKind, reportJson, Json.MAPPER.valueToTree(body), root.analysisCtx(ctx));
        ctx.json(new ApiResponses.PlanBacktest<>(plan, saved, report));
    }

    void planBacktestGet(Context ctx) {
        String planId = ctx.pathParam("id");
        String backtestId = ctx.pathParam("backtestId");
        planOutcomes.requireBacktest(root.ownerId(ctx), planId, backtestId);
        ctx.json(backtester.get(backtestId));
    }


    private io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble resolvePlanEnsemble(
            Context ctx, io.liftandshift.strikebench.plan.Plan.View plan, PlanOutcomeRunRequest body,
            io.liftandshift.strikebench.sim.PathEnsembleService.Basis basis) {
        if (body.ensembleId() != null && !body.ensembleId().isBlank()) {
            var stored = planOutcomes.loadCurrentEnsemble(root.ownerId(ctx), plan, body.ensembleId(), root.analysisCtx(ctx));
            if (!basis.name().equals(stored.basis())) throw new IllegalArgumentException("Stored ensemble basis does not match the requested basis");
            return stored;
        }
        var existing = planOutcomes.latestEnsemble(root.ownerId(ctx), plan, basis.name(), root.analysisCtx(ctx));
        if (existing != null && basis == io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC
                && body.over() == null && body.iv() == null) return existing;
        var spec = planScenarioSpec(plan, body.over());
        String world = PlanController.worldParam(root.activeWorld(ctx));
        double spot = pathEnsembles.anchorSpot(new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(
                plan.symbol(), world, root.analysisCtx(ctx)));
        io.liftandshift.strikebench.sim.PathEnsembleService.Ensemble ensemble;
        if (basis == io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC) {
            var marketVol = outcomeController.marketVol(plan.symbol(), world, spec.horizonDays());
            if (spec.volAnnual() <= 0 && marketVol != null) spec = spec.withVol(marketVol.atmIv());
            ensemble = pathEnsembles.build(new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(
                    plan.symbol(), world, root.analysisCtx(ctx)), basis, spec, null, spot);
        } else {
            var evidence = planEvidence.latest(root.ownerId(ctx), plan.id(), root.analysisCtx(ctx));
            if (evidence == null) throw new IllegalStateException("Run Past evidence in this Plan before using historical analog outcomes.");
            ensemble = pathEnsembles.fromStudy(new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(
                    plan.symbol(), world, root.analysisCtx(ctx)), basis, spec, evidence.result(), spot);
        }
        double rate = market.riskFreeRateQuote(Math.max(1, ensemble.spec().horizonDays()), world).annualRate();
        var marketVol = outcomeController.marketVol(plan.symbol(), world, ensemble.spec().horizonDays());
        var iv = body.iv() == null ? defaultPlanIv(ensemble.spec(), marketVol) : body.iv();
        return planOutcomes.saveEnsemble(root.ownerId(ctx), plan, ensemble, iv, rate, null, Json.MAPPER.valueToTree(body));
    }

    private static io.liftandshift.strikebench.outcomes.OutcomeContract.Position planOutcomePosition(JsonNode candidate) {
        List<io.liftandshift.strikebench.outcomes.OutcomeContract.Leg> legs = new ArrayList<>();
        for (JsonNode leg : candidate.path("legs")) {
            String type = leg.path("type").asText();
            legs.add(new io.liftandshift.strikebench.outcomes.OutcomeContract.Leg(
                    leg.path("action").asText(), type,
                    "STOCK".equalsIgnoreCase(type) ? BigDecimal.ZERO : new BigDecimal(leg.path("strike").asText()),
                    leg.path("expiration").asText(null), null, Math.max(1, leg.path("ratio").asInt(1))));
        }
        Long entryNet = candidate.hasNonNull("entryNetPremiumCents") ? candidate.path("entryNetPremiumCents").longValue() : null;
        return new io.liftandshift.strikebench.outcomes.OutcomeContract.Position(candidate.path("id").asText(), legs,
                Math.max(1, candidate.path("qty").asInt(1)), entryNet == null ? null : -entryNet);
    }

    private io.liftandshift.strikebench.outcomes.OutcomeContract.MarketContext planOutcomeContext(
            Context ctx, io.liftandshift.strikebench.plan.Plan.View plan) {
        var analysis = root.analysisCtx(ctx);
        return new io.liftandshift.strikebench.outcomes.OutcomeContract.MarketContext(plan.symbol(),
                root.activePlanMarket(ctx).name(), root.activeWorld(ctx), analysis.datasetId(), null);
    }

    private static io.liftandshift.strikebench.sim.ScenarioSpec planScenarioSpec(
            io.liftandshift.strikebench.plan.Plan.View plan,
            io.liftandshift.strikebench.sim.ScenarioSpec raw) {
        int days = plan.context().horizonDays() == null
                ? io.liftandshift.strikebench.model.Horizon.MONTH.tradingSessions()
                : plan.context().horizonDays();
        var base = raw == null
                ? io.liftandshift.strikebench.sim.ScenarioSpec.preset(
                    io.liftandshift.strikebench.sim.ScenarioSpec.Shape.CHOP, days, 0, 4242L, 500)
                : raw;
        return new io.liftandshift.strikebench.sim.ScenarioSpec(base.model(), base.shape(), days,
                base.stepsPerDay(), base.driftAnnual(), base.volAnnual(), base.jumpsPerYear(),
                base.jumpMean(), base.jumpVol(), base.tailNu(), base.heston(), base.seed(), base.paths()).sane();
    }

    private static io.liftandshift.strikebench.sim.IvSpec defaultPlanIv(
            io.liftandshift.strikebench.sim.ScenarioSpec spec,
            io.liftandshift.strikebench.sim.SimulationEngine.MarketVolInput marketVol) {
        double anchor = marketVol != null && marketVol.atmIv() > 0 ? marketVol.atmIv() : spec.sane().volAnnual();
        return spec.sane().shape() == io.liftandshift.strikebench.sim.ScenarioSpec.Shape.EVENT_JUMP
                ? io.liftandshift.strikebench.sim.IvSpec.eventCrushAround(anchor,
                    Math.max(1, Math.round(spec.sane().horizonDays() / 3.0f)))
                : io.liftandshift.strikebench.sim.IvSpec.flat(anchor);
    }

}
