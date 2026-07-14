package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;
import io.liftandshift.strikebench.backtest.Backtester;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.plan.PlanDecisionService;
import io.liftandshift.strikebench.plan.PlanEvidenceService;
import io.liftandshift.strikebench.plan.PlanManagementService;
import io.liftandshift.strikebench.plan.PlanOutcomeService;
import io.liftandshift.strikebench.plan.PlanRehearsalService;
import io.liftandshift.strikebench.plan.PlanService;
import io.liftandshift.strikebench.plan.PlanStrategyService;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.sim.PathEnsembleService;
import io.liftandshift.strikebench.sim.SimulationEngine;
import io.liftandshift.strikebench.util.Json;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Owns Plan Outcomes, replays, decisions, rehearsals, and linked-position management. */
final class PlanOutcomeController {
    private final PlanController root;
    private final AppConfig cfg;
    private final Clock clock;
    private final Db db;
    private final MarketDataService market;
    private final TradeService trades;
    private final Backtester backtester;
    private final PlanService planSvc;
    private final PlanEvidenceService planEvidence;
    private final PlanStrategyService planStrategy;
    private final PlanOutcomeService planOutcomes;
    private final PlanRehearsalService planRehearsals;
    private final PlanDecisionService planDecisions;
    private final PlanManagementService planManagement;
    private final PathEnsembleService pathEnsembles;
    private final SimulationEngine simEngine;
    private final OutcomeController outcomeController;
    private final TradeController tradeController;

    PlanOutcomeController(PlanController root, AppConfig cfg, Clock clock, Db db,
                          MarketDataService market, TradeService trades, Backtester backtester,
                          PlanService planSvc, PlanEvidenceService planEvidence,
                          PlanStrategyService planStrategy, PlanOutcomeService planOutcomes,
                          PlanRehearsalService planRehearsals, PlanDecisionService planDecisions,
                          PlanManagementService planManagement, PathEnsembleService pathEnsembles,
                          SimulationEngine simEngine, OutcomeController outcomeController,
                          TradeController tradeController) {
        this.root = root;
        this.cfg = cfg;
        this.clock = clock;
        this.db = db;
        this.market = market;
        this.trades = trades;
        this.backtester = backtester;
        this.planSvc = planSvc;
        this.planEvidence = planEvidence;
        this.planStrategy = planStrategy;
        this.planOutcomes = planOutcomes;
        this.planRehearsals = planRehearsals;
        this.planDecisions = planDecisions;
        this.planManagement = planManagement;
        this.pathEnsembles = pathEnsembles;
        this.simEngine = simEngine;
        this.outcomeController = outcomeController;
        this.tradeController = tradeController;
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
    public record PlanDecisionRequest(Long expectedVersion, Integer qty, Long proposedNetCents,
                                      Long feesOverrideCents, List<String> acknowledgedRisks,
                                      String ackToken, String note) {}
    public record PlanManageRequest(Long expectedVersion, Boolean confirm) {}
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
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
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
        var legs = outcomeController.toSimLegs(ctx, position.legs());
        var simRequest = new OutcomeController.StrategySimRequest(plan.symbol(), legs, position.qty(),
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
                var simLegs = outcomeController.toSimLegs(ctx, position.legs());
                String problem = outcomeController.validateSimLegs(simLegs);
                if (problem != null) {
                    earlyRefusals.put(id, problem);
                    continue;
                }
                simItems.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem(
                        id, simLegs, position.entryCostCents(),
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
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
        String family = candidate.path("strategy").asText();
        if (family.isBlank() || "CUSTOM".equals(family)) {
            throw new IllegalArgumentException("Historical replay needs a named strategy rule; model futures still test the exact custom package.");
        }
        String engineKind = body.engine() == null ? "single" : body.engine().trim().toLowerCase(Locale.ROOT);
        Object report;
        if ("portfolio".equals(engineKind)) {
            report = backtester.runPortfolio(new Backtester.PortfolioRequest(plan.symbol(), family, body.from(), body.to(),
                    body.targetDte() == null ? plan.context().horizonDays() : body.targetDte(), body.entryEveryDays(),
                    body.maxConcurrent(), body.qty(), body.shortDelta(), body.widthPct(), body.profitTargetPct(),
                    body.stopFraction(), body.rollDte(), body.startingCashCents()), root.analysisCtx(ctx), root.ownerId(ctx));
        } else if ("single".equals(engineKind)) {
            report = backtester.run(new Backtester.BacktestRequest(plan.symbol(), family, body.from(), body.to(),
                    body.targetDte() == null ? plan.context().horizonDays() : body.targetDte(), body.entryEveryDays(),
                    body.qty(), body.slippagePct(), body.startingCashCents()), root.analysisCtx(ctx), root.ownerId(ctx));
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

    void planRehearsalsList(Context ctx) {
        ctx.json(planRehearsals.list(root.ownerId(ctx), ctx.pathParam("id")));
    }

    void planRehearsalCreate(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx,
                io.liftandshift.strikebench.plan.PlanRehearsalService.Request.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        var created = planRehearsals.create(root.ownerId(ctx), plan, body, root.analysisCtx(ctx));
        ctx.status(201).json(new ApiResponses.PlanRehearsal<>(
                created, planSvc.get(root.ownerId(ctx), plan.id())));
    }

    void planDecisionLatest(Context ctx) {
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        ctx.json(new ApiResponses.PlanDecisionState<>(plan,
                planStrategy.selectedCandidate(root.ownerId(ctx), plan.id()),
                planDecisions.latest(root.ownerId(ctx), plan.id())));
    }

    void planDecisionPreview(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body);
        ApiResponses.TradePreviewResponse payload = tradeController.previewPayload(ctx, order);
        var first = payload.preview();
        if (order.proposedNetCents() == null) {
            body = new PlanDecisionRequest(body.expectedVersion(), body.qty(), first.entryNetPremiumCents(),
                    body.feesOverrideCents(), body.acknowledgedRisks(), body.ackToken(), body.note());
            order = planDecisionOrder(plan, candidate, body);
            payload = tradeController.previewPayload(ctx, order);
        }
        ctx.json(new ApiResponses.PlanDecisionPreview<>(payload.preview(), payload.economics(),
                payload.guardrails(), payload.requiredAcks(), payload.ackToken(), payload.accountFit(),
                plan, candidate, new ApiResponses.OrderSummary(order.qty(), order.proposedNetCents(),
                order.feesOverrideCents() == null ? 0L : order.feesOverrideCents())));
    }

    void planDecisionTrade(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body);
        ApiResponses.TradePreviewResponse payload = tradeController.previewPayload(ctx, order);
        var decisionInput = planDecisionInput(ctx, plan, body, candidate, payload);
        var prepared = planDecisions.prepareTrade(decisionInput);
        TradeController.CreatedTrade created = tradeController.execute(ctx, order, prepared.hook());
        var updated = planSvc.get(root.ownerId(ctx), plan.id());
        ctx.status(201).json(new ApiResponses.PlanPlacedTrade<>(updated, TradeView.of(created.trade()),
                planDecisions.latest(root.ownerId(ctx), plan.id()), created.verdict().warnings()));
    }

    void planDecisionCash(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body);
        ApiResponses.TradePreviewResponse payload = tradeController.previewPayload(ctx, order);
        ObjectNode decision = planDecisions.chooseCash(planDecisionInput(ctx, plan, body, candidate, payload));
        var updated = planSvc.get(root.ownerId(ctx), plan.id());
        ctx.status(201).json(new ApiResponses.PlanDecision<>(updated, decision));
    }

    private TradeOpenRequest planDecisionOrder(io.liftandshift.strikebench.plan.Plan.View plan,
                                               ObjectNode candidate, PlanDecisionRequest body) {
        List<LegView> legs = new ArrayList<>();
        for (JsonNode leg : candidate.withArray("legs")) {
            legs.add(new LegView(leg.path("action").asText(), leg.path("type").asText(),
                    leg.path("strike").isMissingNode() || leg.path("strike").isNull() ? null : leg.path("strike").asText(),
                    leg.path("expiration").isMissingNode() || leg.path("expiration").isNull() ? null : leg.path("expiration").asText(),
                    leg.path("ratio").asInt(1), leg.path("entryPrice").isMissingNode() ? null : leg.path("entryPrice").asText(null)));
        }
        int qty = body.qty() == null ? candidate.path("qty").asInt(1) : body.qty();
        return new TradeOpenRequest(plan.symbol(), candidate.path("strategy").asText("CUSTOM"), qty, legs,
                plan.context().thesis(), (plan.context().horizonDays() == null
                        ? io.liftandshift.strikebench.model.Horizon.MONTH.tradingSessions()
                        : plan.context().horizonDays()) + "d",
                plan.context().riskMode(),
                plan.intent(), candidate.path("usesHeldShares").asBoolean(false),
                candidate.path("recommendationId").asText(null), body.proposedNetCents(), body.feesOverrideCents(),
                "PLAN", null, null, null, false, body.acknowledgedRisks(), body.ackToken());
    }

    private io.liftandshift.strikebench.plan.PlanDecisionService.Input planDecisionInput(
            Context ctx, io.liftandshift.strikebench.plan.Plan.View plan, PlanDecisionRequest body,
            ObjectNode candidate, ApiResponses.TradePreviewResponse payload) {
        return new io.liftandshift.strikebench.plan.PlanDecisionService.Input(root.ownerId(ctx), plan,
                body.expectedVersion(), candidate.path("id").asText(), root.currentAccount(ctx),
                payload.preview(), payload.economics(),
                io.liftandshift.strikebench.paper.AccountRiskContext.load(db, root.ownerId(ctx)),
                body.qty(),
                body.acknowledgedRisks() == null ? List.of() : body.acknowledgedRisks(), body.note(), root.analysisCtx(ctx));
    }

    void planManageGet(Context ctx) {
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        ObjectNode management = planManagement.latest(root.ownerId(ctx), plan.id());
        String tradeId = management.path("activeTradeId").asText(null);
        if (tradeId == null) {
            for (JsonNode link : management.withArray("links")) if (link.hasNonNull("tradeId")) tradeId = link.get("tradeId").asText();
        }
        ctx.json(new ApiResponses.PlanWorkspace<>(plan,
                planDecisions.latest(root.ownerId(ctx), plan.id()), management,
                tradeId == null ? null : tradeController.detailData(tradeId)));
    }

    void plansPortfolio(Context ctx) {
        String world = PlanController.worldParam(root.activeWorld(ctx));
        var marketKind = root.activePlanMarket(ctx);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var plan : planSvc.list(root.ownerId(ctx), marketKind,
                marketKind == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED ? world : null, false)) {
            if (plan.status() == io.liftandshift.strikebench.plan.Plan.Status.ARCHIVED) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("plan", plan);
            ObjectNode decision = planDecisions.latest(root.ownerId(ctx), plan.id());
            if (decision != null) row.put("decision", decision);
            String tradeId = planManagement.activeTradeId(root.ownerId(ctx), plan.id());
            if (tradeId != null) {
                row.put("tradeId", tradeId);
                try { row.put("mark", trades.currentMark(tradeId)); }
                catch (RuntimeException e) { row.put("markUnavailable", true); }
            }
            rows.add(row);
        }
        ctx.json(new ApiResponses.PlanRows<>(rows, marketKind.name()));
    }

    void planManageRefresh(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanManageRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan); PlanController.requirePlanVersion(plan, body.expectedVersion());
        String tradeId = requirePlanActiveTrade(ctx, plan.id());
        var mark = trades.refresh(tradeId);
        planManagement.recordMark(root.ownerId(ctx), plan.id(), body.expectedVersion(), tradeId, mark);
        ctx.json(new ApiResponses.PlanMark<>(planSvc.get(root.ownerId(ctx), plan.id()), mark,
                planManagement.latest(root.ownerId(ctx), plan.id())));
    }

    void planManageUnwind(Context ctx) { planManageClose(ctx, "CLOSE", false); }
    void planManageSettle(Context ctx) { planManageClose(ctx, "SETTLE", false); }
    void planManageRoll(Context ctx) { planManageClose(ctx, "ROLL", true); }

    void planManageClose(Context ctx, String kind, boolean roll) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanManageRequest.class));
        if (!Boolean.TRUE.equals(body.confirm())) throw new IllegalArgumentException("confirm=true is required");
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan); PlanController.requirePlanVersion(plan, body.expectedVersion());
        String tradeId = requirePlanActiveTrade(ctx, plan.id());
        var hook = planManagement.lifecycleHook(root.ownerId(ctx), plan.id(), body.expectedVersion(), kind, roll);
        TradeService.CloseResult result = "SETTLE".equals(kind)
                ? trades.settle(tradeId, true, hook) : trades.unwind(tradeId, true, hook);
        tradeController.resolveRecommendation(tradeId,
                "SETTLE".equals(kind) ? "SETTLED" : "CLOSED",
                TradeController.decisionPnl(result.trade(), result.realizedPnlCents()));
        ctx.json(new ApiResponses.PlanClosedTrade<>(planSvc.get(root.ownerId(ctx), plan.id()),
                TradeView.of(result.trade()), result.realizedPnlCents(),
                planManagement.latest(root.ownerId(ctx), plan.id()),
                roll ? rolledPosition(result.trade(), PlanController.worldParam(root.activeWorld(ctx))) : null));
    }

    void planManageVoid(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanManageRequest.class));
        if (!Boolean.TRUE.equals(body.confirm())) throw new IllegalArgumentException("confirm=true is required");
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan); PlanController.requirePlanVersion(plan, body.expectedVersion());
        String tradeId = requirePlanActiveTrade(ctx, plan.id());
        var hook = planManagement.lifecycleHook(root.ownerId(ctx), plan.id(), body.expectedVersion(), "VOID", false);
        TradeRecord deleted = trades.delete(tradeId, true, hook);
        ctx.json(new ApiResponses.PlanTrade<>(planSvc.get(root.ownerId(ctx), plan.id()),
                TradeView.of(deleted), planManagement.latest(root.ownerId(ctx), plan.id())));
    }

    void planManageReview(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanManageRequest.class));
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan); PlanController.requirePlanVersion(plan, body.expectedVersion());
        ObjectNode decision = planDecisions.latest(root.ownerId(ctx), plan.id());
        if (decision == null || !"CASH".equals(decision.path("action").asText())) {
            throw new IllegalStateException("This Plan does not have a cash decision to review.");
        }
        ObjectNode metrics = decision.with("metrics");
        int horizon = decision.path("reviewHorizonDays").asInt(30);
        java.time.Instant decidedAt = java.time.OffsetDateTime.parse(decision.path("createdAt").asText()).toInstant();
        java.time.Instant dueAt = decidedAt.plus(java.time.Duration.ofDays(horizon));
        String world = plan.marketKind() == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED
                ? plan.worldId() : plan.marketKind() == io.liftandshift.strikebench.plan.Plan.MarketKind.DEMO ? "demo" : "observed";
        java.time.Instant laneNow = market.simInstant(world).orElse(clock.instant());
        if (laneNow.isBefore(dueAt)) {
            throw new IllegalStateException("This opportunity review is scheduled for "
                    + java.time.LocalDate.ofInstant(dueAt, io.liftandshift.strikebench.market.MarketHours.EASTERN) + ".");
        }
        java.time.LocalDate dueDate = java.time.LocalDate.ofInstant(dueAt,
                io.liftandshift.strikebench.market.MarketHours.EASTERN);
        var series = market.candleSeries(plan.symbol(), dueDate.minusDays(14), dueDate, world,
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED);
        io.liftandshift.strikebench.model.Candle dueBar = series.candles().stream()
                .filter(c -> !c.date().isAfter(dueDate)).max(java.util.Comparator.comparing(
                        io.liftandshift.strikebench.model.Candle::date))
                .orElseThrow(() -> new IllegalStateException("No lane-owned closing price is available for the review horizon."));
        long startUnderlying = metrics.path("underlyingCents").asLong(0);
        if (startUnderlying <= 0) throw new IllegalStateException("The frozen decision has no underlying anchor.");
        long endUnderlying = io.liftandshift.strikebench.util.Money.toCents(dueBar.close());
        long riskCapital = Math.max(startUnderlying, decision.path("maxLossCents").asLong(startUnderlying));
        long shares = Math.max(1, Math.min(10_000, riskCapital / startUnderlying));
        long stockPnl = Math.multiplyExact(endUnderlying - startUnderlying, shares);
        int qty = Math.max(1, (int) Math.round(metrics.path("decisionQty").asDouble(1)));
        double rate = metrics.path("riskFreeRateAnnual").asDouble(Double.NaN);
        if (!Double.isFinite(rate)) throw new IllegalStateException("The frozen decision has no pricing-rate snapshot.");
        long packageEnd = modeledRejectedPackageValue(decision.withArray("legs"), dueBar.close(), dueDate, rate, qty);
        long entry = decision.path("proposedNetCents").asLong();
        long fees = metrics.path("feesOpenCents").asLong(0);
        long rejectedPnl = entry + packageEnd - Math.multiplyExact(fees, 2L);
        ObjectNode management = planManagement.recordCashReview(root.ownerId(ctx), plan.id(), body.expectedVersion(),
                new io.liftandshift.strikebench.plan.PlanManagementService.CashReview(startUnderlying, endUnderlying,
                        stockPnl, entry, packageEnd, rejectedPnl, horizon,
                        decision.hasNonNull("pop") ? decision.get("pop").asDouble() : null,
                        "Frozen-IV modeled value at the lane-owned horizon close; kept outside trade calibration"));
        ctx.json(new ApiResponses.PlanManagement<>(planSvc.get(root.ownerId(ctx), plan.id()), management));
    }

    private static long modeledRejectedPackageValue(ArrayNode legs, BigDecimal spot, java.time.LocalDate horizon,
                                                     double rate, int qty) {
        long value = 0;
        for (JsonNode leg : legs) {
            int ratio = Math.max(1, leg.path("ratio").asInt(1));
            long units = Math.multiplyExact(100L, Math.multiplyExact((long) ratio, qty));
            double price;
            if ("STOCK".equals(leg.path("type").asText())) {
                price = spot.doubleValue();
            } else {
                if (!leg.hasNonNull("strikePrice") || !leg.hasNonNull("expiration") || !leg.hasNonNull("iv")) {
                    throw new IllegalStateException("The rejected package lacks a frozen strike, expiration, or IV.");
                }
                double strike = leg.get("strikePrice").asDouble();
                java.time.LocalDate expiry = java.time.LocalDate.parse(leg.get("expiration").asText());
                double years = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(horizon, expiry) / 365.0);
                price = io.liftandshift.strikebench.pricing.BlackScholes.price(
                        "CALL".equals(leg.path("type").asText()), spot.doubleValue(), strike, years,
                        rate, 0, leg.get("iv").asDouble());
            }
            long cents = io.liftandshift.strikebench.util.Money.centsFromPrice(BigDecimal.valueOf(price), units);
            value += "BUY".equals(leg.path("action").asText()) ? cents : -cents;
        }
        return value;
    }

    private String requirePlanActiveTrade(Context ctx, String planId) {
        String tradeId = planManagement.activeTradeId(root.ownerId(ctx), planId);
        if (tradeId == null) throw new IllegalStateException("This Plan has no active linked position.");
        return tradeId;
    }

    private Map<String, Object> rolledPosition(TradeRecord trade, String world) {
        List<LocalDate> listed = market.expirations(trade.symbol(), world).stream().sorted().toList();
        List<Map<String, Object>> legs = new ArrayList<>();
        for (Leg leg : trade.legs()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", leg.action().name()); row.put("type", leg.isStock() ? "STOCK" : leg.type().name());
            row.put("ratio", leg.ratio());
            if (!leg.isStock()) {
                LocalDate target = leg.expiration().plusDays(28);
                LocalDate next = listed.stream().filter(date -> !date.isBefore(target)).findFirst()
                        .orElseGet(() -> listed.stream().filter(date -> date.isAfter(leg.expiration()))
                                .reduce((a, b) -> b).orElseThrow(() -> new IllegalStateException(
                                        "No later listed expiration is available for this roll.")));
                OptionChain chain = market.chain(trade.symbol(), next, world)
                        .orElseThrow(() -> new IllegalStateException("The later expiration has no option book."));
                List<OptionQuote> side = leg.type() == io.liftandshift.strikebench.model.OptionType.CALL
                        ? chain.calls() : chain.puts();
                BigDecimal strike = side.stream().map(OptionQuote::strike)
                        .min(java.util.Comparator.comparing(value -> value.subtract(leg.strike()).abs()))
                        .orElseThrow(() -> new IllegalStateException("The later expiration has no "
                                + leg.type().name().toLowerCase(Locale.ROOT) + " strikes."));
                row.put("strike", strike.toPlainString());
                row.put("expiration", next.toString());
            }
            legs.add(row);
        }
        return Map.of("symbol", trade.symbol(), "strategy", trade.strategy(), "qty", trade.qty(), "legs", legs,
                "intent", trade.intent() == null ? "DIRECTIONAL" : trade.intent(), "source", "PLAN");
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

    private ObjectNode selectedPlanCandidate(Context ctx, io.liftandshift.strikebench.plan.Plan.View plan) {
        JsonNode selected = planStrategy.selectedCandidate(root.ownerId(ctx), plan.id());
        if (!(selected instanceof ObjectNode candidate)) {
            throw new IllegalStateException("Select a structure in Strategy before running Outcomes.");
        }
        return candidate;
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
