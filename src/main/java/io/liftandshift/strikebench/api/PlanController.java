package io.liftandshift.strikebench.api;
import static io.liftandshift.strikebench.market.MarketLane.worldParam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.backtest.Backtester;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.plan.PlanDecisionService;
import io.liftandshift.strikebench.plan.PlanEvidenceService;
import io.liftandshift.strikebench.plan.PlanManagementService;
import io.liftandshift.strikebench.plan.PlanOutcomeService;
import io.liftandshift.strikebench.plan.PlanRehearsalService;
import io.liftandshift.strikebench.plan.PlanService;
import io.liftandshift.strikebench.plan.PlanStrategyService;
import io.liftandshift.strikebench.recommend.AutoRecommender;
import io.liftandshift.strikebench.sim.PathEnsembleService;
import io.liftandshift.strikebench.sim.SimulationEngine;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Owns the complete Plan HTTP journey while stage services own persisted domain behavior. */
final class PlanController {
    private final AppConfig cfg;
    private final Clock clock;
    private final MarketDataService market;
    private final PositionsService positions;
    private final PlanService planSvc;
    private final PlanEvidenceService planEvidence;
    private final PlanStrategyService planStrategy;
    private final Function<Context, Account> currentAccountResolver;
    private final Function<Context, String> ownerResolver;
    private final Function<Context, String> activeWorldResolver;
    private final Function<Context, AnalysisContext> analysisContextResolver;
    private final PlanStrategyController strategyController;
    private final PlanOutcomeController planOutcomeController;
    private final PlanDecisionController planDecisionController;
    private final io.liftandshift.strikebench.plan.PlanAdoptionService planAdoptions;

    PlanController(AppConfig cfg, Clock clock, Db db, MarketDataService market,
                   io.liftandshift.strikebench.market.EventService events,
                   PositionsService positions,
                   TradeService trades, Backtester backtester, AutoRecommender auto,
                   EvaluationService evaluations, PlanService planSvc,
                   PlanEvidenceService planEvidence, PlanStrategyService planStrategy,
                   PlanOutcomeService planOutcomes, PlanRehearsalService planRehearsals,
                   PlanDecisionService planDecisions, PlanManagementService planManagement,
                   io.liftandshift.strikebench.plan.PlanPromotionService planPromotions,
                   io.liftandshift.strikebench.plan.PlanAdoptionService planAdoptions,
                   PlanAdoptionReviewService planAdoptionReviews,
                   PathEnsembleService pathEnsembles, SimulationEngine simEngine,
                   DiscoveryController discoveryController, OutcomeController outcomeController,
                   TradeController tradeController,
                   Function<Context, Account> currentAccountResolver,
                   Function<Context, String> ownerResolver,
                   Function<Context, String> activeWorldResolver,
                   Function<Context, AnalysisContext> analysisContextResolver) {
        this.cfg = cfg;
        this.clock = clock;
        this.market = market;
        this.positions = positions;
        this.planSvc = planSvc;
        this.planEvidence = planEvidence;
        this.planStrategy = planStrategy;
        this.currentAccountResolver = currentAccountResolver;
        this.ownerResolver = ownerResolver;
        this.activeWorldResolver = activeWorldResolver;
        this.analysisContextResolver = analysisContextResolver;
        this.strategyController = new PlanStrategyController(this, cfg, market, positions, trades,
                auto, evaluations, planSvc, planStrategy, discoveryController, tradeController);
        this.planOutcomeController = new PlanOutcomeController(this, cfg, market, backtester,
                planSvc, planEvidence, planStrategy, planManagement, planOutcomes, pathEnsembles, simEngine,
                outcomeController,
                new io.liftandshift.strikebench.plan.AuthoredScenarioService(db, clock),
                new io.liftandshift.strikebench.sim.ScenarioCanvasTemplateService(market, events, clock),
                new io.liftandshift.strikebench.position.ScenarioPositionScopeService(db, trades, positions));
        this.planAdoptions = planAdoptions;
        this.planDecisionController = new PlanDecisionController(this, clock, db, market, trades,
                planSvc, planRehearsals, planDecisions, planManagement, tradeController,
                planPromotions, planAdoptionReviews);
    }

    void register(JavalinConfig config) {
        PlanRoutes.register(config, new PlanRoutes.Handlers(
                this::plansList, this::planCreate, this::planAdopt, this::planAdoptBatch,
                planDecisionController::plansPortfolio, this::planGet,
                this::planContextPut, this::planIntentPut, this::planProgressPost, this::planOpenPut,
                this::planArchive, this::planDelete, this::planEvidenceLatest,
                this::planEvidenceStudy, strategyController::planStrategyLatest,
                strategyController::planStrategyRun, strategyController::planStrategyFit,
                strategyController::planStrategyCustom, strategyController::planStrategySelect,
                strategyController::planStrategySelectionDelete,
                strategyController::planScoutLatest, strategyController::planScoutRun,
                strategyController::planScoutSpawn, planOutcomeController::planOutcomesLatest,
                planOutcomeController::planEnsembleLatest,
                planOutcomeController::planScenarioPaths,
                planOutcomeController::planEnsembleRun, planOutcomeController::planOutcomeRun,
                planOutcomeController::planOutcomeCompare, planOutcomeController::planScenarioSave,
                planOutcomeController::planScenariosList, planOutcomeController::planScenarioGet,
                planOutcomeController::planBacktestRun,
                planOutcomeController::planBacktestGet, planDecisionController::planRehearsalsList,
                planDecisionController::planRehearsalCreate,
                planDecisionController::planDecisionLatest, planDecisionController::planDecisionPreview,
                planDecisionController::planDecisionTrade, planDecisionController::planDecisionCash,
                planDecisionController::planDecisionBroker,
                planDecisionController::planManageGet, planDecisionController::planManageRefresh,
                planDecisionController::planManageReview));
        config.routes.exception(PlanMarketMismatchException.class, (e, ctx) ->
                ctx.status(409).json(new ApiResponses.PlanMarketMismatchBody(
                        "plan_market_mismatch", e.getMessage(), e.marketKind, e.targetWorld)));
    }

    /** Adopts an as-is tracked position into a mid-journey Plan (ADOPTION receipt over
     *  existing lots); the live band is immediately real while the view stays undeclared. */
    void planAdopt(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx,
                io.liftandshift.strikebench.plan.PlanAdoptionService.Request.class));
        var market = activePlanMarket(ctx);
        var result = planAdoptions.adopt(ownerId(ctx), market,
                market == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED ? activeWorld(ctx) : null,
                body);
        ctx.status(201).json(new ApiResponses.PlanAdopted<>(result.plan(),
                result.artifacts().structureId(), result.artifacts().receiptId()));
    }

    /** Atomic adopt/link/skip confirmation for a statement-sized group of tracked positions. */
    void planAdoptBatch(Context ctx) {
        var body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx,
                io.liftandshift.strikebench.plan.PlanAdoptionService.BatchRequest.class));
        var market = activePlanMarket(ctx);
        ctx.status(201).json(planAdoptions.adoptBatch(ownerId(ctx), market,
                market == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED
                        ? activeWorld(ctx) : null, body));
    }

    String ownerId(Context ctx) { return ownerResolver.apply(ctx); }
    String activeWorld(Context ctx) { return activeWorldResolver.apply(ctx); }
    Account currentAccount(Context ctx) { return currentAccountResolver.apply(ctx); }
    AnalysisContext analysisCtx(Context ctx) { return analysisContextResolver.apply(ctx); }
    ObjectNode selectedCandidate(Context ctx, io.liftandshift.strikebench.plan.Plan.View plan,
                                 boolean required) {
        JsonNode selected = planStrategy.selectedCandidate(ownerId(ctx), plan.id());
        if (selected instanceof ObjectNode candidate) return candidate;
        if (required) throw new IllegalStateException("Select a structure in Strategy before continuing.");
        return null;
    }
    JsonNode priorSelectedCandidate(Context ctx, io.liftandshift.strikebench.plan.Plan.View plan) {
        return planStrategy.priorSelectedCandidate(ownerId(ctx), plan.id());
    }
    private static <T> T bodyOrNull(Context ctx, Class<T> type) { return ApiRequest.bodyOrNull(ctx, type); }
    private static <T> T requireBody(T body) { return ApiRequest.requireBody(body); }
    private List<LocalDate> activeExpirations(String symbol, String world) {
        var now = market.simInstant(worldParam(world)).orElse(clock.instant());
        return ResearchController.activeExpirations(market.expirations(symbol, world), now);
    }

    io.liftandshift.strikebench.plan.Plan.MarketKind activePlanMarket(Context ctx) {
        String world = activeWorld(ctx);
        if ("demo".equals(world)) return io.liftandshift.strikebench.plan.Plan.MarketKind.DEMO;
        if ("observed".equals(world)) return io.liftandshift.strikebench.plan.Plan.MarketKind.OBSERVED;
        return io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED;
    }

    record PlanSymbolEligibility(boolean eligible, String detail) {}

    private static final class PlanMarketMismatchException extends IllegalStateException {
        private final String marketKind;
        private final String targetWorld;

        private PlanMarketMismatchException(String marketKind, String targetWorld, String detail) {
            super(detail);
            this.marketKind = marketKind;
            this.targetWorld = targetWorld;
        }
    }

    /** A Plan may start only when its active market can supply a lane-owned option surface. */
    private PlanSymbolEligibility planSymbolEligibility(String rawSymbol, String world) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        if (symbol.isBlank()) return new PlanSymbolEligibility(false, "Choose a ticker symbol first.");
        var lane = io.liftandshift.strikebench.market.MarketLane.of(world, cfg.fixturesOnly());
        var quote = market.quote(symbol, world).orElse(null);
        if (quote == null) return new PlanSymbolEligibility(false,
                symbol + " is not available in the active " + lane.name().toLowerCase(Locale.ROOT) + " market.");
        var expirations = activeExpirations(symbol, world);
        var chain = expirations.isEmpty() ? null : market.chain(symbol, expirations.getFirst(), world).orElse(null);
        return planSymbolEligibility(symbol, lane, quote, expirations,
                chain == null || chain.isEmpty() ? io.liftandshift.strikebench.model.DataEvidence.missing("option chain")
                        : chain.evidence());
    }

    PlanSymbolEligibility planSymbolEligibility(String symbol,
            io.liftandshift.strikebench.market.MarketLane lane, Quote quote, List<LocalDate> expirations,
            io.liftandshift.strikebench.model.DataEvidence optionEvidence) {
        if (!quote.evidence().usableIn(lane)) return new PlanSymbolEligibility(false,
                symbol + " does not have " + lane.name().toLowerCase(Locale.ROOT) + " market evidence.");
        if (!quote.optionable()) return new PlanSymbolEligibility(false,
                symbol + " has no listed options in this market. Its stock research remains available.");
        if (expirations.isEmpty()) return new PlanSymbolEligibility(false,
                symbol + " has no active option expirations in this market.");
        if (optionEvidence == null || !optionEvidence.usableIn(lane)) {
            return new PlanSymbolEligibility(false,
                    "An option surface for " + symbol + " is unavailable in this market right now.");
        }
        return new PlanSymbolEligibility(true, "Ready to build an options Plan in the active market.");
    }

    private void plansList(Context ctx) {
        if (planSvc == null) throw new IllegalStateException("plan store unavailable");
        boolean allMarkets = "all".equalsIgnoreCase(ctx.queryParam("scope"));
        boolean openOnly = !"false".equalsIgnoreCase(ctx.queryParam("openOnly"));
        var market = allMarkets ? null : activePlanMarket(ctx);
        String world = market == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED
                ? activeWorld(ctx) : null;
        ctx.json(new ApiResponses.Plans<>(planSvc.list(ownerId(ctx), market, world, openOnly),
                activePlanMarket(ctx).name(), activeWorld(ctx)));
    }

    private void planCreate(Context ctx) {
        if (planSvc == null) throw new IllegalStateException("plan store unavailable");
        var request = requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.plan.Plan.CreateRequest.class));
        var market = activePlanMarket(ctx);
        String world = market == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED
                ? activeWorld(ctx) : null;
        String marketWorld = market == io.liftandshift.strikebench.plan.Plan.MarketKind.DEMO ? "demo" : world;
        if (request.symbol() != null && !request.symbol().isBlank()) {
            var eligibility = planSymbolEligibility(request.symbol(), marketWorld);
            if (!eligibility.eligible()) {
                ctx.attribute("apiErrorWritten", true);
                ctx.status(422).json(new ApiResponses.PlanSymbolError(
                        "plan_symbol_unavailable", eligibility.detail(), market.name()));
                return;
            }
        }
        // Market and account are server-derived. A client cannot create an observed plan while
        // looking at generated quotes or bind a plan to somebody else's simulation account.
        Account account = currentAccount(ctx);
        request = snapshotPlanHoldings(account, request);
        ctx.status(201).json(planSvc.create(ownerId(ctx), market, world, account.id(), request));
    }

    /**
     * Held-share goals start from the account that owns the Plan. When the caller did not supply
     * a hypothetical holding, capture the currently free shares and basis as durable Plan context.
     * ACQUIRE deliberately does not use this path: its shares field means shares the user wants.
     */
    private io.liftandshift.strikebench.plan.Plan.CreateRequest snapshotPlanHoldings(
            Account account, io.liftandshift.strikebench.plan.Plan.CreateRequest request) {
        String intent = request.intent() == null ? "" : request.intent().trim().toUpperCase(Locale.ROOT);
        if (request.holdingsShares() != null || !java.util.Set.of("EXIT", "HEDGE", "INCOME").contains(intent)) {
            return request;
        }
        var holding = positions.list(account.id()).stream()
                .filter(row -> row.symbol().equalsIgnoreCase(request.symbol()))
                .filter(row -> row.freeShares() > 0)
                .findFirst().orElse(null);
        if (holding == null) return request;
        return new io.liftandshift.strikebench.plan.Plan.CreateRequest(
                request.clientRequestId(), request.symbol(), request.intent(), request.originPlanId(), request.title(),
                request.thesis(), request.horizonDays(), request.targetCents(), request.riskMode(),
                holding.freeShares(), request.costBasisCents() == null ? holding.avgCostCents() : request.costBasisCents(),
                request.priceAssumptionCents(), request.assignmentPreference());
    }

    private void planGet(Context ctx) {
        if (planSvc == null) throw new IllegalStateException("plan store unavailable");
        ctx.json(planSvc.get(ownerId(ctx), ctx.pathParam("id")));
    }

    private void planContextPut(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.plan.Plan.ContextUpdateRequest.class));
        ctx.json(planSvc.updateContext(ownerId(ctx), ctx.pathParam("id"), request));
    }

    private void planIntentPut(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.plan.Plan.IntentRequest.class));
        ctx.json(planSvc.claimIntent(ownerId(ctx), ctx.pathParam("id"), request));
    }

    private void planProgressPost(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.plan.Plan.ProgressRequest.class));
        ctx.json(planSvc.advanceProgress(ownerId(ctx), ctx.pathParam("id"), request));
    }

    private void planOpenPut(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.plan.Plan.OpenRequest.class));
        ctx.json(planSvc.setOpen(ownerId(ctx), ctx.pathParam("id"), request));
    }

    private void planArchive(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.plan.Plan.ArchiveRequest.class));
        ctx.json(planSvc.archive(ownerId(ctx), ctx.pathParam("id"), request));
    }

    private void planDelete(Context ctx) {
        String rawVersion = ctx.queryParam("expectedVersion");
        if (rawVersion == null || rawVersion.isBlank()) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        long expectedVersion;
        try { expectedVersion = Long.parseLong(rawVersion); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("expectedVersion must be an integer"); }
        String id = ctx.pathParam("id");
        planSvc.deleteDraft(ownerId(ctx), id, expectedVersion);
        ctx.json(new ApiResponses.Deleted(id));
    }

    void requireActivePlanMarket(Context ctx, io.liftandshift.strikebench.plan.Plan.View plan) {
        var active = activePlanMarket(ctx);
        String activeWorld = active == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED
                ? activeWorld(ctx) : null;
        if (plan.marketKind() != active || !java.util.Objects.equals(plan.worldId(), activeWorld)) {
            String label = switch (plan.marketKind()) {
                case DEMO -> "Demo market";
                case OBSERVED -> "Observed market";
                case SIMULATED -> "simulated market session";
            };
            String targetWorld = plan.marketKind() == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED
                    ? plan.worldId() : plan.marketKind().name().toLowerCase(Locale.ROOT);
            throw new PlanMarketMismatchException(plan.marketKind().name(), targetWorld,
                    "This Plan belongs to the " + label + ". Open that market before running this analysis.");
        }
    }

    private void planEvidenceLatest(Context ctx) {
        var saved = planEvidence.latest(ownerId(ctx), ctx.pathParam("id"), analysisCtx(ctx));
        ctx.json(new ApiResponses.Evidence<>(saved));
    }

    private void planEvidenceStudy(Context ctx) {
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        var body = requireBody(bodyOrNull(ctx, PlanEvidenceStudyRequest.class));
        var request = new io.liftandshift.strikebench.research.ResearchQuestionEngine.RunRequest(
                body.key(), body.symbol(), body.from(), body.to(), body.params());
        ctx.json(planEvidence.run(ownerId(ctx), plan, request, analysisCtx(ctx),
                worldParam(activeWorld(ctx)), body.expectedStudyKey()));
    }

    private record PlanEvidenceStudyRequest(String key, String symbol, String from, String to,
                                            Map<String, Object> params, String expectedStudyKey) {}

    static void requirePlanVersion(io.liftandshift.strikebench.plan.Plan.View plan, Long expected) {
        if (expected == null) throw new IllegalArgumentException("expectedVersion is required");
        if (plan.version() != expected) {
            throw new IllegalStateException("This Plan changed in another tab. Reload it before continuing.");
        }
    }

    static String planHorizon(Integer days) {
        return io.liftandshift.strikebench.model.Horizon.exactTradingSessions(days);
    }
}
