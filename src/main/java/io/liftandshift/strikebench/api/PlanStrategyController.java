package io.liftandshift.strikebench.api;
import io.liftandshift.strikebench.market.MarketLane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.plan.PlanService;
import io.liftandshift.strikebench.plan.PlanStrategyService;
import io.liftandshift.strikebench.recommend.AutoRecommender;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.SignalEngine;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Owns Plan Strategy, exact custom structures, and universe-scout request handling. */
final class PlanStrategyController {
    private static final Logger log = LoggerFactory.getLogger(PlanStrategyController.class);

    private final PlanController root;
    private final AppConfig cfg;
    private final MarketDataService market;
    private final PositionsService positions;
    private final TradeService trades;
    private final AutoRecommender auto;
    private final EvaluationService evaluations;
    private final PlanService planSvc;
    private final PlanStrategyService planStrategy;
    private final DiscoveryController discoveryController;
    private final TradeController tradeController;

    PlanStrategyController(PlanController root, AppConfig cfg, MarketDataService market,
                           PositionsService positions, TradeService trades, AutoRecommender auto,
                           EvaluationService evaluations, PlanService planSvc,
                           PlanStrategyService planStrategy,
                           DiscoveryController discoveryController,
                           TradeController tradeController) {
        this.root = root;
        this.cfg = cfg;
        this.market = market;
        this.positions = positions;
        this.trades = trades;
        this.auto = auto;
        this.evaluations = evaluations;
        this.planSvc = planSvc;
        this.planStrategy = planStrategy;
        this.discoveryController = discoveryController;
        this.tradeController = tradeController;
    }

    public record PlanStrategyRunRequest(Boolean allow0dte, Long maxLossCents, List<String> allowedStrategies,
                                         RecommendationEngine.Filters filters) {}
    public record PlanStrategyFitRequest(Long expectedVersion, String strategy, Boolean allow0dte,
                                         Long maxLossCents, RecommendationEngine.Filters filters) {}
    public record PlanStrategySelectRequest(String candidateId, Long expectedVersion) {}
    public record PlanStrategyCustomRequest(Long expectedVersion, TradeOpenRequest position) {}
    public record PlanScoutRequest(String scope, Integer maxPicks, Boolean allow0dte) {}
    public record PlanScoutSpawnRequest(String clientRequestId, String candidateId, String role) {}
    void planStrategyLatest(Context ctx) {
        var saved = planStrategy.latestCompetition(root.ownerId(ctx), ctx.pathParam("id"));
        ctx.json(new ApiResponses.StrategyState<>(saved,
                planStrategy.selectedCandidate(root.ownerId(ctx), ctx.pathParam("id"))));
    }

    void planStrategyRun(Context ctx) {
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        if (plan.intent() == null || plan.intent().isBlank()) {
            throw new IllegalStateException("Choose what this plan should do before comparing strategies");
        }
        PlanStrategyRunRequest controls = ApiRequest.bodyOrNull(ctx, PlanStrategyRunRequest.class);
        RecommendationEngine.Request request = planStrategyRequest(plan, controls);
        RecommendationEngine.Result recommended = discoveryController.resolveAndRecommend(ctx, request);
        JsonNode ranked = Json.MAPPER.valueToTree(
                discoveryController.decisionRanked(recommended, root.currentAccount(ctx), root.activeWorld(ctx),
                        plan.context().assignmentPreference()));
        JsonNode input = Json.MAPPER.valueToTree(request);
        var saved = planStrategy.saveCompetition(root.ownerId(ctx), plan, input, ranked);
        ctx.json(new ApiResponses.PlanStrategy<>(planSvc.get(root.ownerId(ctx), plan.id()), saved));
    }

    /** Price one named Builder structure against this Plan without creating a second strategy workflow. */
    void planStrategyFit(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanStrategyFitRequest.class));
        if (body.expectedVersion() == null || body.strategy() == null || body.strategy().isBlank()) {
            throw new IllegalArgumentException("expectedVersion and strategy are required");
        }
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        PlanController.requirePlanVersion(plan, body.expectedVersion());
        if (plan.intent() == null || plan.intent().isBlank()) {
            throw new IllegalStateException("Choose what this plan should do before fitting a structure");
        }
        var controls = new PlanStrategyRunRequest(body.allow0dte(), body.maxLossCents(),
                List.of(body.strategy().trim().toUpperCase(Locale.ROOT)), body.filters());
        var request = planStrategyRequest(plan, controls);
        var ranked = Json.MAPPER.valueToTree(discoveryController.decisionRanked(
                discoveryController.resolveAndRecommend(ctx, request),
                root.currentAccount(ctx), root.activeWorld(ctx), plan.context().assignmentPreference()));
        JsonNode candidates = ranked.path("candidates");
        ctx.json(new ApiResponses.PlanStrategyFit<>(plan, ranked,
                candidates.isArray() && !candidates.isEmpty() ? candidates.get(0) : null));
    }

    private static RecommendationEngine.Request planStrategyRequest(
            io.liftandshift.strikebench.plan.Plan.View plan, PlanStrategyRunRequest controls) {
        requireDeclaredView(plan);
        var c = plan.context();
        RecommendationEngine.Holdings holdings = c.holdingsShares() == null && c.costBasisCents() == null
                && c.targetCents() == null ? null
                : new RecommendationEngine.Holdings(c.holdingsShares() == null ? null
                        : Math.toIntExact(Math.min(Integer.MAX_VALUE, c.holdingsShares())),
                        c.costBasisCents(), c.targetCents());
        return new RecommendationEngine.Request(plan.symbol(), c.thesis(), PlanController.planHorizon(c.horizonDays()),
                c.riskMode(), controls == null ? null : controls.maxLossCents(), null, null,
                controls == null ? null : controls.allowedStrategies(), true,
                controls != null && Boolean.TRUE.equals(controls.allow0dte()), plan.intent(), holdings,
                controls == null ? null : controls.filters());
    }

    /** No ranking or structure analysis may manufacture an absent Plan assumption. */
    static void requireDeclaredView(io.liftandshift.strikebench.plan.Plan.View plan) {
        var missing = new ArrayList<String>();
        String intent = plan.intent() == null ? null : plan.intent().trim().toUpperCase(Locale.ROOT);
        var context = plan.context();
        if (intent == null || intent.isBlank()) missing.add("goal");
        if (context.thesis() == null || context.thesis().isBlank()) {
            missing.add("direction");
        }
        if (context.horizonDays() == null || context.horizonDays() <= 0) missing.add("horizon");
        if (context.riskMode() == null || context.riskMode().isBlank()) missing.add("risk posture");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Declare this Plan's view before ranking or analyzing a structure. Missing: "
                    + String.join(", ", missing) + ".");
        }
    }

    void planStrategySelect(Context ctx) {
        var request = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanStrategySelectRequest.class));
        if (request.candidateId() == null || request.candidateId().isBlank() || request.expectedVersion() == null) {
            throw new IllegalArgumentException("candidateId and expectedVersion are required");
        }
        var selected = planStrategy.select(root.ownerId(ctx), ctx.pathParam("id"), request.candidateId(),
                request.expectedVersion());
        ctx.json(new ApiResponses.PlanSelection<>(selected,
                planSvc.get(root.ownerId(ctx), ctx.pathParam("id"))));
    }

    void planStrategySelectionDelete(Context ctx) {
        String rawVersion = ctx.queryParam("expectedVersion");
        if (rawVersion == null || rawVersion.isBlank()) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        long expectedVersion;
        try { expectedVersion = Long.parseLong(rawVersion); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("expectedVersion must be an integer"); }
        String id = ctx.pathParam("id");
        var selection = planStrategy.clearSelection(root.ownerId(ctx), id, expectedVersion);
        ctx.json(new ApiResponses.PlanSelection<>(selection, planSvc.get(root.ownerId(ctx), id)));
    }

    void planStrategyCustom(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanStrategyCustomRequest.class));
        if (body.expectedVersion() == null || body.position() == null) {
            throw new IllegalArgumentException("expectedVersion and position are required");
        }
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        requireDeclaredView(plan);
        if (plan.intent() == null || plan.intent().isBlank()) {
            throw new IllegalStateException("Choose what this plan should do before saving a structure");
        }
        TradeOpenRequest supplied = body.position();
        if (supplied.symbol() != null && !supplied.symbol().isBlank()
                && !plan.symbol().equalsIgnoreCase(supplied.symbol())) {
            throw new IllegalArgumentException("A Plan can contain structures for " + plan.symbol() + " only");
        }
        var c = plan.context();
        TradeOpenRequest exactBody = new TradeOpenRequest(plan.symbol(), supplied.strategy(), supplied.qty(),
                supplied.legs(), c.thesis(), PlanController.planHorizon(c.horizonDays()), c.riskMode(), plan.intent(),
                supplied.useHeldShares(), supplied.recommendationId(), supplied.proposedNetCents(),
                supplied.feesOverrideCents(), "BUILDER", null, null,
                supplied.fillNature());
        Account account = root.currentAccount(ctx);
        TradeService.OpenRequest request = TradeController.toAnalysisOpenRequest(exactBody, account.id());
        var preview = trades.analyze(request);
        Candidate candidate = TradeController.exactPreviewCandidate(request, preview);
        ObjectNode candidateJson = Json.MAPPER.valueToTree(candidate);
        long roundTripFees = Math.multiplyExact(preview.feesOpenCents(), 2L);
        ApiResponses.EvaluationReceipt evaluation;
        try {
            evaluation = ApiResponses.EvaluationReceipt.of(evaluations.assessExact(
                    plan.symbol(), candidate, account.buyingPowerCents(),
                    root.analysisCtx(ctx), MarketLane.worldParam(root.activeWorld(ctx)), preview.ok(),
                    preview.blockReasons(), roundTripFees, practiceExposure(account, plan.symbol()),
                    new io.liftandshift.strikebench.eval.DeclaredObjective(plan.intent(), c.thesis(),
                            c.horizonDays(), c.assignmentPreference(), "this Plan's declared view")));
        } catch (RuntimeException e) {
            log.debug("Plan custom-package assessment is unavailable", e);
            evaluation = ApiResponses.EvaluationReceipt.unavailable(
                    "The package mechanics were checked, but the broader decision assessment is unavailable because one or more market inputs could not be observed. No score or economic claim was substituted.",
                    preview.ok(), preview.blockReasons(), roundTripFees);
        }
        candidateJson.set("evaluation", Json.MAPPER.valueToTree(evaluation));
        JsonNode requestJson = Json.MAPPER.valueToTree(exactBody);
        var saved = planStrategy.saveCustom(root.ownerId(ctx), plan, requestJson, candidateJson,
                body.expectedVersion(), preview.ok());
        ctx.json(new ApiResponses.PlanStrategyPreview<>(
                planSvc.get(root.ownerId(ctx), plan.id()), saved, preview,
                io.liftandshift.strikebench.strategy.StrategyCatalog.identify(
                        request.symbol(), request.qty(), request.legs())));
    }

    private io.liftandshift.strikebench.eval.PortfolioExposureContext practiceExposure(
            Account account, String symbol) {
        var exposure = trades.portfolioDollarDelta(account.id(), symbol);
        return new io.liftandshift.strikebench.eval.PortfolioExposureContext(
                io.liftandshift.strikebench.position.PositionDomain.ExecutionLane.PRACTICE,
                exposure.grossCents(), exposure.netCents(), exposure.focusSymbolGrossCents(),
                exposure.complete(), exposure.basis());
    }

    void planScoutLatest(Context ctx) {
        String scope = java.util.Objects.requireNonNullElse(ctx.queryParam("scope"), "PEERS");
        var saved = planStrategy.latestScout(root.ownerId(ctx), ctx.pathParam("id"), scope);
        ctx.json(new ApiResponses.Scout<>(saved));
    }

    void planScoutRun(Context ctx) {
        PlanScoutRequest controls = ApiRequest.bodyOrNull(ctx, PlanScoutRequest.class);
        String scope = controls == null || controls.scope() == null ? "PEERS" : controls.scope().trim().toUpperCase(Locale.ROOT);
        if (!List.of("PEERS", "ALTERNATIVES", "HEDGES").contains(scope)) {
            throw new IllegalArgumentException("scope must be PEERS, ALTERNATIVES, or HEDGES");
        }
        var plan = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, plan);
        requireDeclaredView(plan);
        List<String> scanUniverse;
        if (cfg.fixturesOnly()) {
            scanUniverse = io.liftandshift.strikebench.market.Universes.SECTORS.get("DEMO").symbols().stream()
                    .filter(symbol -> !symbol.equals(plan.symbol())).toList();
        } else if ("HEDGES".equals(scope)) {
            scanUniverse = io.liftandshift.strikebench.market.Universes.complementsFor(plan.symbol());
        } else {
            scanUniverse = io.liftandshift.strikebench.market.Universes.peersOf(plan.symbol());
        }
        String world = MarketLane.worldParam(root.activeWorld(ctx));
        if (world != null) {
            var available = market.worldSymbols(world).map(java.util.HashSet::new).orElseGet(java.util.HashSet::new);
            scanUniverse = scanUniverse.stream().filter(available::contains).toList();
        }
        if (scanUniverse.isEmpty()) {
            throw new IllegalStateException("This market has no eligible " + scope.toLowerCase(Locale.ROOT)
                    + " for " + plan.symbol());
        }
        Account account = root.currentAccount(ctx);
        List<AutoRecommender.HoldingInfo> held = positions.list(account.id()).stream()
                .map(p -> new AutoRecommender.HoldingInfo(p.symbol(),
                        (int) Math.min(Integer.MAX_VALUE, p.freeShares()), p.avgCostCents())).toList();
        String requestedIntent = plan.intent();
        if ("HEDGES".equals(scope) || "HEDGE".equals(requestedIntent) || "EXIT".equals(requestedIntent)) {
            // A cross-symbol complement is not a covered-share hedge. Screen it as a
            // directional package, then preserve HEDGE as the typed relationship.
            requestedIntent = "DIRECTIONAL";
        }
        boolean allow0 = controls != null && Boolean.TRUE.equals(controls.allow0dte());
        String focusedThesis = "HEDGES".equals(scope) ? null : plan.context().thesis();
        var request = new AutoRecommender.AutoRequest(scanUniverse, List.of(PlanController.planHorizon(plan.context().horizonDays())),
                controls == null ? 4 : controls.maxPicks(), null, null, null, null,
                plan.context().riskMode(), allow0, List.of(requestedIntent), null, focusedThesis);
        AutoRecommender.AutoResult raw = auto.run(request, account.buyingPowerCents(), held, world);
        ObjectNode result = flattenPlanScout(plan, scope, raw);
        var saved = planStrategy.saveScout(root.ownerId(ctx), plan, scope, Json.MAPPER.valueToTree(request), result);
        ctx.json(new ApiResponses.PlanScout<>(plan, saved));
    }

    private static ObjectNode flattenPlanScout(io.liftandshift.strikebench.plan.Plan.View plan, String scope,
                                               AutoRecommender.AutoResult raw) {
        ObjectNode result = Json.MAPPER.createObjectNode();
        result.put("symbol", plan.symbol()); result.put("scope", scope);
        if (plan.context().thesis() != null) result.put("thesis", plan.context().thesis());
        result.put("horizon", PlanController.planHorizon(plan.context().horizonDays()));
        result.put("riskMode", plan.context().riskMode());
        result.put("intent", plan.intent()); result.put("riskBudgetCents", raw.riskBudgetCents());
        result.put("disclaimer", raw.disclaimer());
        result.put("sentimentScorerVersion", SignalEngine.SENTIMENT_SCORER_VERSION);
        ArrayNode candidates = result.putArray("candidates");
        io.liftandshift.strikebench.eval.EconomicReadiness.Tally readinessTally =
                io.liftandshift.strikebench.eval.EconomicReadiness.tally();
        List<String> collectedNotes = new ArrayList<>();
        String wantedThesis = plan.context().thesis() == null ? null : plan.context().thesis().toUpperCase(Locale.ROOT);
        for (AutoRecommender.Pick pick : raw.picks()) {
            for (AutoRecommender.HorizonIdeas horizon : pick.horizons()) {
                for (String note : horizon.notes()) if (!collectedNotes.contains(note)) collectedNotes.add(note);
                for (AutoRecommender.ScoredCandidate scored : horizon.candidates()) {
                    var evaluation = scored.evaluation();
                    var economics = evaluation.assessment().economics();
                    ObjectNode candidate = Json.MAPPER.valueToTree(evaluation.candidate());
                    candidate.put("symbol", pick.symbol());
                    candidate.put("scoutThesis", !"HEDGES".equals(scope) && wantedThesis != null
                            ? wantedThesis : pick.signals().thesis());
                    candidate.put("scoutScope", scope); candidate.put("scoutHorizon", horizon.horizon());
                    candidate.put("opportunityScore", pick.opportunityScore());
                    if (scored.targetFit() != null) candidate.put("targetFit", scored.targetFit());
                    candidate.set("evaluation", Json.MAPPER.valueToTree(ApiResponses.EvaluationReceipt.of(evaluation)));
                    var endorsement = evaluation.evidence() == null ? null
                            : evaluation.evidence().claims().get("endorsement");
                    readinessTally.add(economics,
                            endorsement == null ? null : endorsement.missingDimensions());
                    candidates.add(candidate);
                }
            }
        }
        io.liftandshift.strikebench.eval.EconomicReadiness readiness = readinessTally.summarize();
        result.put("favorableCount", readiness.favorable()); result.put("mixedCount", readiness.mixed());
        result.put("unfavorableCount", readiness.unfavorable());
        result.put("unavailableCount", readiness.unavailable());
        result.put("economicReadiness", readiness.readiness());
        result.put("economicMessage", candidates.isEmpty()
                ? "No related symbol matched this Plan's evidence and mechanical screens."
                : switch (readiness.readiness()) {
                    case io.liftandshift.strikebench.eval.EconomicReadiness.MECHANICALLY_BLOCKED ->
                        "The related symbols produced structures, but none passed the mechanical and account checks required for an economic comparison.";
                    case io.liftandshift.strikebench.eval.EconomicReadiness.NEEDS_DAILY_HISTORY ->
                        "These symbols can be compared mechanically and under market-implied pricing, but daily history is insufficient for a realized-volatility edge verdict.";
                    default ->
                        "Related symbols remain separate Plans; compare their evidence, tail risk, and capital use before treating one as an alternative.";
                });
        ArrayNode notes = result.putArray("notes");
        collectedNotes.forEach(notes::add);
        raw.notes().forEach(notes::add);
        raw.skipped().stream().limit(8).forEach(skip -> notes.add("Skipped: " + skip));
        return result;
    }

    void planScoutSpawn(Context ctx) {
        var request = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, PlanScoutSpawnRequest.class));
        if (request.clientRequestId() == null || request.clientRequestId().isBlank()
                || request.candidateId() == null || request.candidateId().isBlank()) {
            throw new IllegalArgumentException("clientRequestId and candidateId are required");
        }
        var origin = planSvc.get(root.ownerId(ctx), ctx.pathParam("id"));
        root.requireActivePlanMarket(ctx, origin);
        ObjectNode candidate = planStrategy.scoutedCandidate(root.ownerId(ctx), origin.id(), request.candidateId());
        String symbol = candidate.path("symbol").asText();
        String role = request.role() == null ? "ALTERNATIVE" : request.role().trim().toUpperCase(Locale.ROOT);
        String childIntent = "HEDGE".equals(role) ? "DIRECTIONAL" : candidate.path("intent").asText(origin.intent());
        var childRequest = new io.liftandshift.strikebench.plan.Plan.CreateRequest(request.clientRequestId(),
                symbol, childIntent, origin.id(), null, candidate.path("scoutThesis").asText(origin.context().thesis()),
                origin.context().horizonDays(), origin.context().targetCents(), origin.context().riskMode(),
                null, null, origin.context().priceAssumptionCents(), origin.context().assignmentPreference());
        var child = planSvc.create(root.ownerId(ctx), origin.marketKind(), origin.worldId(), origin.accountId(), childRequest);
        planSvc.linkRelated(root.ownerId(ctx), origin.id(), child.id(), role);
        if (planStrategy.selectedCandidate(root.ownerId(ctx), child.id()) == null) {
            planStrategy.copyScoutSelection(root.ownerId(ctx), origin.id(), request.candidateId(), child);
        }
        child = planSvc.get(root.ownerId(ctx), child.id());
        ctx.json(new ApiResponses.ScoutSpawn<>(origin, child, role));
    }

}
