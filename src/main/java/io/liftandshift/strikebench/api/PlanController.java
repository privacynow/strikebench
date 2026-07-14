package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.backtest.Backtester;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.sim.SimulationSessions;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.plan.PlanDecisionService;
import io.liftandshift.strikebench.plan.PlanEvidenceService;
import io.liftandshift.strikebench.plan.PlanManagementService;
import io.liftandshift.strikebench.plan.PlanOutcomeService;
import io.liftandshift.strikebench.plan.PlanRehearsalService;
import io.liftandshift.strikebench.plan.PlanService;
import io.liftandshift.strikebench.plan.PlanStrategyService;
import io.liftandshift.strikebench.recommend.AutoRecommender;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.sim.PathEnsembleService;
import io.liftandshift.strikebench.sim.SimulationEngine;
import io.liftandshift.strikebench.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Owns the complete Plan HTTP journey while stage services own persisted domain behavior. */
final class PlanController {
    private static final Logger log = LoggerFactory.getLogger(PlanController.class);

    private final AppConfig cfg;
    private final Clock clock;
    private final Db db;
    private final MarketDataService market;
    private final SimulationSessions simSessions;
    private final PositionsService positions;
    private final TradeService trades;
    private final Backtester backtester;
    private final AutoRecommender auto;
    private final EvaluationService evaluations;
    private final PlanService planSvc;
    private final PlanEvidenceService planEvidence;
    private final PlanStrategyService planStrategy;
    private final PlanOutcomeService planOutcomes;
    private final PlanRehearsalService planRehearsals;
    private final PlanDecisionService planDecisions;
    private final PlanManagementService planManagement;
    private final PathEnsembleService pathEnsembles;
    private final SimulationEngine simEngine;
    private final DiscoveryController discoveryController;
    private final OutcomeController outcomeController;
    private final TradeController tradeController;
    private final Function<Context, Account> currentAccountResolver;
    private final Function<Context, String> ownerResolver;
    private final Function<Context, String> activeWorldResolver;
    private final Function<Context, AnalysisContext> analysisContextResolver;

    PlanController(AppConfig cfg, Clock clock, Db db, MarketDataService market,
                   SimulationSessions simSessions, PositionsService positions,
                   TradeService trades, Backtester backtester, AutoRecommender auto,
                   EvaluationService evaluations, PlanService planSvc,
                   PlanEvidenceService planEvidence, PlanStrategyService planStrategy,
                   PlanOutcomeService planOutcomes, PlanRehearsalService planRehearsals,
                   PlanDecisionService planDecisions, PlanManagementService planManagement,
                   PathEnsembleService pathEnsembles, SimulationEngine simEngine,
                   DiscoveryController discoveryController, OutcomeController outcomeController,
                   TradeController tradeController,
                   Function<Context, Account> currentAccountResolver,
                   Function<Context, String> ownerResolver,
                   Function<Context, String> activeWorldResolver,
                   Function<Context, AnalysisContext> analysisContextResolver) {
        this.cfg = cfg;
        this.clock = clock;
        this.db = db;
        this.market = market;
        this.simSessions = simSessions;
        this.positions = positions;
        this.trades = trades;
        this.backtester = backtester;
        this.auto = auto;
        this.evaluations = evaluations;
        this.planSvc = planSvc;
        this.planEvidence = planEvidence;
        this.planStrategy = planStrategy;
        this.planOutcomes = planOutcomes;
        this.planRehearsals = planRehearsals;
        this.planDecisions = planDecisions;
        this.planManagement = planManagement;
        this.pathEnsembles = pathEnsembles;
        this.simEngine = simEngine;
        this.discoveryController = discoveryController;
        this.outcomeController = outcomeController;
        this.tradeController = tradeController;
        this.currentAccountResolver = currentAccountResolver;
        this.ownerResolver = ownerResolver;
        this.activeWorldResolver = activeWorldResolver;
        this.analysisContextResolver = analysisContextResolver;
    }

    void register(JavalinConfig config) {
        PlanRoutes.register(config, new PlanRoutes.Handlers(
                this::plansList, this::planCreate, this::plansPortfolio, this::planGet,
                this::planContextPut, this::planIntentPut, this::planStagePut, this::planOpenPut,
                this::planArchive, this::planDelete, this::planEvidenceLatest,
                this::planEvidenceStudy, this::planStrategyLatest, this::planStrategyRun,
                this::planStrategyFit, this::planStrategyCustom, this::planStrategySelect,
                this::planStrategySelectionDelete, this::planScoutLatest, this::planScoutRun,
                this::planScoutSpawn, this::planOutcomesLatest, this::planEnsembleRun,
                this::planOutcomeRun, this::planOutcomeCompare, this::planBacktestRun,
                this::planBacktestGet, this::planRehearsalsList, this::planRehearsalCreate,
                this::planDecisionLatest, this::planDecisionPreview, this::planDecisionTrade,
                this::planDecisionCash, this::planManageGet, this::planManageRefresh,
                this::planManageUnwind, this::planManageSettle, this::planManageRoll,
                this::planManageVoid, this::planManageReview));
        config.routes.exception(PlanMarketMismatchException.class, (e, ctx) ->
                ctx.status(409).json(new ApiResponses.PlanMarketMismatchBody(
                        "plan_market_mismatch", e.getMessage(), e.marketKind, e.targetWorld)));
    }

    private String ownerId(Context ctx) { return ownerResolver.apply(ctx); }
    private String activeWorld(Context ctx) { return activeWorldResolver.apply(ctx); }
    private Account currentAccount(Context ctx) { return currentAccountResolver.apply(ctx); }
    private AnalysisContext analysisCtx(Context ctx) { return analysisContextResolver.apply(ctx); }
    private static <T> T bodyOrNull(Context ctx, Class<T> type) { return ApiRequest.bodyOrNull(ctx, type); }
    private static <T> T requireBody(T body) { return ApiRequest.requireBody(body); }
    private static String worldParam(String world) {
        return world == null || "observed".equals(world) ? null : world;
    }
    private List<LocalDate> activeExpirations(String symbol, String world) {
        var now = market.simInstant(worldParam(world)).orElse(clock.instant());
        return ResearchController.activeExpirations(market.expirations(symbol, world), now);
    }

    private io.liftandshift.strikebench.plan.Plan.MarketKind activePlanMarket(Context ctx) {
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
                request.priceAssumptionCents());
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

    private void planStagePut(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.plan.Plan.StageRequest.class));
        ctx.json(planSvc.setStage(ownerId(ctx), ctx.pathParam("id"), request));
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

    private void requireActivePlanMarket(Context ctx, io.liftandshift.strikebench.plan.Plan.View plan) {
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
        var request = requireBody(bodyOrNull(ctx,
                io.liftandshift.strikebench.research.ResearchQuestionEngine.RunRequest.class));
        ctx.json(planEvidence.run(ownerId(ctx), plan, request, analysisCtx(ctx),
                worldParam(activeWorld(ctx))));
    }

    public record PlanStrategyRunRequest(Boolean allow0dte, Long maxLossCents, List<String> allowedStrategies,
                                         RecommendationEngine.Filters filters) {}
    public record PlanStrategyFitRequest(Long expectedVersion, String strategy, Boolean allow0dte,
                                         Long maxLossCents, RecommendationEngine.Filters filters) {}
    public record PlanStrategySelectRequest(String candidateId, Long expectedVersion) {}
    public record PlanStrategyCustomRequest(Long expectedVersion, TradeOpenRequest position) {}
    public record PlanScoutRequest(String scope, Integer maxPicks, Boolean allow0dte) {}
    public record PlanScoutSpawnRequest(String clientRequestId, String candidateId, String role) {}
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

    private void planStrategyLatest(Context ctx) {
        var saved = planStrategy.latestCompetition(ownerId(ctx), ctx.pathParam("id"));
        ctx.json(new ApiResponses.StrategyState<>(saved,
                planStrategy.selectedCandidate(ownerId(ctx), ctx.pathParam("id"))));
    }

    private void planStrategyRun(Context ctx) {
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        if (plan.intent() == null || plan.intent().isBlank()) {
            throw new IllegalStateException("Choose what this plan should do before comparing strategies");
        }
        PlanStrategyRunRequest controls = bodyOrNull(ctx, PlanStrategyRunRequest.class);
        RecommendationEngine.Request request = planStrategyRequest(plan, controls);
        RecommendationEngine.Result recommended = discoveryController.resolveAndRecommend(ctx, request);
        JsonNode ranked = Json.MAPPER.valueToTree(
                discoveryController.decisionRanked(recommended, currentAccount(ctx), activeWorld(ctx)));
        JsonNode input = Json.MAPPER.valueToTree(request);
        var saved = planStrategy.saveCompetition(ownerId(ctx), plan, input, ranked);
        ctx.json(new ApiResponses.PlanStrategy<>(planSvc.get(ownerId(ctx), plan.id()), saved));
    }

    /** Price one named Builder structure against this Plan without creating a second strategy workflow. */
    private void planStrategyFit(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanStrategyFitRequest.class));
        if (body.expectedVersion() == null || body.strategy() == null || body.strategy().isBlank()) {
            throw new IllegalArgumentException("expectedVersion and strategy are required");
        }
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        if (plan.intent() == null || plan.intent().isBlank()) {
            throw new IllegalStateException("Choose what this plan should do before fitting a structure");
        }
        var controls = new PlanStrategyRunRequest(body.allow0dte(), body.maxLossCents(),
                List.of(body.strategy().trim().toUpperCase(Locale.ROOT)), body.filters());
        var request = planStrategyRequest(plan, controls);
        var ranked = Json.MAPPER.valueToTree(discoveryController.decisionRanked(
                discoveryController.resolveAndRecommend(ctx, request),
                currentAccount(ctx), activeWorld(ctx)));
        JsonNode candidates = ranked.path("candidates");
        ctx.json(new ApiResponses.PlanStrategyFit<>(plan, ranked,
                candidates.isArray() && !candidates.isEmpty() ? candidates.get(0) : null));
    }

    private static RecommendationEngine.Request planStrategyRequest(
            io.liftandshift.strikebench.plan.Plan.View plan, PlanStrategyRunRequest controls) {
        var c = plan.context();
        RecommendationEngine.Holdings holdings = c.holdingsShares() == null && c.costBasisCents() == null
                && c.targetCents() == null ? null
                : new RecommendationEngine.Holdings(c.holdingsShares() == null ? null
                        : Math.toIntExact(Math.min(Integer.MAX_VALUE, c.holdingsShares())),
                        c.costBasisCents(), c.targetCents());
        return new RecommendationEngine.Request(plan.symbol(), c.thesis(), planHorizon(c.horizonDays()),
                c.riskMode(), controls == null ? null : controls.maxLossCents(), null, null,
                controls == null ? null : controls.allowedStrategies(), true,
                controls != null && Boolean.TRUE.equals(controls.allow0dte()), plan.intent(), holdings,
                controls == null ? null : controls.filters());
    }

    private void planStrategySelect(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, PlanStrategySelectRequest.class));
        if (request.candidateId() == null || request.candidateId().isBlank() || request.expectedVersion() == null) {
            throw new IllegalArgumentException("candidateId and expectedVersion are required");
        }
        var selected = planStrategy.select(ownerId(ctx), ctx.pathParam("id"), request.candidateId(),
                request.expectedVersion());
        ctx.json(new ApiResponses.PlanSelection<>(selected,
                planSvc.get(ownerId(ctx), ctx.pathParam("id"))));
    }

    private void planStrategySelectionDelete(Context ctx) {
        String rawVersion = ctx.queryParam("expectedVersion");
        if (rawVersion == null || rawVersion.isBlank()) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        long expectedVersion;
        try { expectedVersion = Long.parseLong(rawVersion); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("expectedVersion must be an integer"); }
        String id = ctx.pathParam("id");
        var selection = planStrategy.clearSelection(ownerId(ctx), id, expectedVersion);
        ctx.json(new ApiResponses.PlanSelection<>(selection, planSvc.get(ownerId(ctx), id)));
    }

    private void planStrategyCustom(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanStrategyCustomRequest.class));
        if (body.expectedVersion() == null || body.position() == null) {
            throw new IllegalArgumentException("expectedVersion and position are required");
        }
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
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
                supplied.legs(), c.thesis(), planHorizon(c.horizonDays()), c.riskMode(), plan.intent(),
                supplied.useHeldShares(), supplied.recommendationId(), supplied.proposedNetCents(),
                supplied.feesOverrideCents(), "BUILDER", null, null, null, null, null, null);
        Account account = currentAccount(ctx);
        TradeService.OpenRequest request = tradeController.toOpenRequest(exactBody, account);
        var preview = trades.preview(request);
        Candidate candidate = TradeController.exactPreviewCandidate(request, preview);
        ObjectNode candidateJson = Json.MAPPER.valueToTree(candidate);
        long roundTripFees = Math.multiplyExact(preview.feesOpenCents(), 2L);
        io.liftandshift.strikebench.eval.EconomicAssessment economics;
        try {
            economics = evaluations.assessExact(plan.symbol(), candidate, account.buyingPowerCents(),
                    analysisCtx(ctx), worldParam(activeWorld(ctx)), preview.ok(), preview.blockReasons(),
                    roundTripFees);
        } catch (RuntimeException e) {
            log.debug("Plan custom-package economic assessment is unavailable", e);
            var provenance = preview.evidence().provenance();
            boolean observed = provenance == io.liftandshift.strikebench.model.DataProvenance.OBSERVED
                    || provenance == io.liftandshift.strikebench.model.DataProvenance.BROKER;
            economics = new io.liftandshift.strikebench.eval.EconomicAssessment(
                    io.liftandshift.strikebench.eval.EconomicAssessment.Verdict.UNAVAILABLE,
                    "MECHANICS_ONLY", "Economics unavailable",
                    "The exact package passed through the mechanical preview, but its evidence cannot support an economic verdict.",
                    preview.expectedValueCents() == null ? null : preview.expectedValueCents() - roundTripFees,
                    null, roundTripFees, null, observed,
                    List.of("No favorable claim is made without complete economic evidence."));
        }
        candidateJson.set("economics", Json.MAPPER.valueToTree(economics));
        candidateJson.put("economicVerdict", economics.verdict().name());
        candidateJson.put("economicPlacement", economics.placement());
        candidateJson.put("decisionViable", preview.ok());
        candidateJson.put("structurallyEligible", preview.ok());
        JsonNode requestJson = Json.MAPPER.valueToTree(exactBody);
        var saved = planStrategy.saveCustom(ownerId(ctx), plan, requestJson, candidateJson, body.expectedVersion());
        ctx.json(new ApiResponses.PlanStrategyPreview<>(
                planSvc.get(ownerId(ctx), plan.id()), saved, preview));
    }

    private void planScoutLatest(Context ctx) {
        String scope = java.util.Objects.requireNonNullElse(ctx.queryParam("scope"), "PEERS");
        var saved = planStrategy.latestScout(ownerId(ctx), ctx.pathParam("id"), scope);
        ctx.json(new ApiResponses.Scout<>(saved));
    }

    private void planScoutRun(Context ctx) {
        PlanScoutRequest controls = bodyOrNull(ctx, PlanScoutRequest.class);
        String scope = controls == null || controls.scope() == null ? "PEERS" : controls.scope().trim().toUpperCase(Locale.ROOT);
        if (!List.of("PEERS", "ALTERNATIVES", "HEDGES").contains(scope)) {
            throw new IllegalArgumentException("scope must be PEERS, ALTERNATIVES, or HEDGES");
        }
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        List<String> scanUniverse;
        if (cfg.fixturesOnly()) {
            scanUniverse = io.liftandshift.strikebench.market.Universes.SECTORS.get("DEMO").symbols().stream()
                    .filter(symbol -> !symbol.equals(plan.symbol())).toList();
        } else if ("HEDGES".equals(scope)) {
            scanUniverse = io.liftandshift.strikebench.market.Universes.complementsFor(plan.symbol());
        } else {
            scanUniverse = io.liftandshift.strikebench.market.Universes.peersOf(plan.symbol());
        }
        String world = worldParam(activeWorld(ctx));
        if (world != null) {
            var available = market.worldSymbols(world).map(java.util.HashSet::new).orElseGet(java.util.HashSet::new);
            scanUniverse = scanUniverse.stream().filter(available::contains).toList();
        }
        if (scanUniverse.isEmpty()) {
            throw new IllegalStateException("This market has no eligible " + scope.toLowerCase(Locale.ROOT)
                    + " for " + plan.symbol());
        }
        Account account = currentAccount(ctx);
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
        var request = new AutoRecommender.AutoRequest(scanUniverse, List.of(planHorizon(plan.context().horizonDays())),
                controls == null ? 4 : controls.maxPicks(), null, null, null, null,
                plan.context().riskMode(), allow0, List.of(requestedIntent), null, focusedThesis);
        AutoRecommender.AutoResult raw = auto.run(request, account.buyingPowerCents(), held, world);
        ObjectNode result = flattenPlanScout(plan, scope, raw);
        var saved = planStrategy.saveScout(ownerId(ctx), plan, scope, Json.MAPPER.valueToTree(request), result);
        ctx.json(new ApiResponses.PlanScout<>(plan, saved));
    }

    private static ObjectNode flattenPlanScout(io.liftandshift.strikebench.plan.Plan.View plan, String scope,
                                               AutoRecommender.AutoResult raw) {
        ObjectNode result = Json.MAPPER.createObjectNode();
        result.put("symbol", plan.symbol()); result.put("scope", scope);
        if (plan.context().thesis() != null) result.put("thesis", plan.context().thesis());
        result.put("horizon", planHorizon(plan.context().horizonDays()));
        result.put("riskMode", plan.context().riskMode());
        result.put("intent", plan.intent()); result.put("riskBudgetCents", raw.riskBudgetCents());
        result.put("disclaimer", raw.disclaimer());
        ArrayNode candidates = result.putArray("candidates");
        int favorable = 0, mixed = 0, unfavorable = 0, unavailable = 0;
        boolean anyEconomicAssessment = false, anyComparableAssessment = false;
        boolean anyRealizedVolLane = false, needsDailyHistory = false;
        List<String> collectedNotes = new ArrayList<>();
        String wantedThesis = plan.context().thesis() == null ? null : plan.context().thesis().toUpperCase(Locale.ROOT);
        for (AutoRecommender.Pick pick : raw.picks()) {
            for (AutoRecommender.HorizonIdeas horizon : pick.horizons()) {
                for (String note : horizon.notes()) if (!collectedNotes.contains(note)) collectedNotes.add(note);
                for (AutoRecommender.ScoredCandidate scored : horizon.candidates()) {
                    ObjectNode candidate = Json.MAPPER.valueToTree(scored.candidate());
                    candidate.put("symbol", pick.symbol());
                    candidate.put("scoutThesis", !"HEDGES".equals(scope) && wantedThesis != null
                            ? wantedThesis : pick.signals().thesis());
                    candidate.put("scoutScope", scope); candidate.put("scoutHorizon", horizon.horizon());
                    candidate.put("opportunityScore", pick.opportunityScore());
                    candidate.put("rankingScore", scored.rankingScore());
                    if (scored.targetFit() != null) candidate.put("targetFit", scored.targetFit());
                    if (scored.decisionScore() != null) candidate.put("decisionScore", scored.decisionScore());
                    if (scored.economics() != null) {
                        anyEconomicAssessment = true;
                        if (!"MECHANICALLY_INELIGIBLE".equals(scored.economics().placement())) {
                            anyComparableAssessment = true;
                        }
                        if (scored.economics().realizedVolEvAfterCostsCents() != null) anyRealizedVolLane = true;
                        if (scored.economics().needsDailyHistory()) needsDailyHistory = true;
                        candidate.set("economics", Json.MAPPER.valueToTree(scored.economics()));
                        candidate.put("economicVerdict", scored.economics().verdict().name());
                        candidate.put("economicPlacement", scored.economics().placement());
                        switch (scored.economics().verdict()) {
                            case FAVORABLE -> favorable++;
                            case MIXED -> mixed++;
                            case UNFAVORABLE -> unfavorable++;
                            case UNAVAILABLE -> unavailable++;
                        }
                    } else unavailable++;
                    candidates.add(candidate);
                }
            }
        }
        result.put("favorableCount", favorable); result.put("mixedCount", mixed);
        result.put("unfavorableCount", unfavorable); result.put("unavailableCount", unavailable);
        result.put("economicReadiness", anyEconomicAssessment && !anyComparableAssessment
                ? "MECHANICALLY_BLOCKED"
                : anyEconomicAssessment && !anyRealizedVolLane && needsDailyHistory
                    ? "NEEDS_DAILY_HISTORY" : "READY");
        result.put("economicMessage", candidates.isEmpty()
                ? "No related symbol matched this Plan's evidence and mechanical screens."
                : anyEconomicAssessment && !anyComparableAssessment
                    ? "The related symbols produced structures, but none passed the mechanical and account checks required for an economic comparison."
                : anyEconomicAssessment && !anyRealizedVolLane && needsDailyHistory
                    ? "These symbols can be compared mechanically and under market-implied pricing, but daily history is insufficient for a realized-volatility edge verdict."
                : "Related symbols remain separate Plans; compare their evidence, tail risk, and capital use before treating one as an alternative.");
        ArrayNode notes = result.putArray("notes");
        collectedNotes.forEach(notes::add);
        raw.notes().forEach(notes::add);
        raw.skipped().stream().limit(8).forEach(skip -> notes.add("Skipped: " + skip));
        return result;
    }

    private void planScoutSpawn(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, PlanScoutSpawnRequest.class));
        if (request.clientRequestId() == null || request.clientRequestId().isBlank()
                || request.candidateId() == null || request.candidateId().isBlank()) {
            throw new IllegalArgumentException("clientRequestId and candidateId are required");
        }
        var origin = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, origin);
        ObjectNode candidate = planStrategy.scoutedCandidate(ownerId(ctx), origin.id(), request.candidateId());
        String symbol = candidate.path("symbol").asText();
        String role = request.role() == null ? "ALTERNATIVE" : request.role().trim().toUpperCase(Locale.ROOT);
        String childIntent = "HEDGE".equals(role) ? "DIRECTIONAL" : candidate.path("intent").asText(origin.intent());
        var childRequest = new io.liftandshift.strikebench.plan.Plan.CreateRequest(request.clientRequestId(),
                symbol, childIntent, origin.id(), null, candidate.path("scoutThesis").asText(origin.context().thesis()),
                origin.context().horizonDays(), origin.context().targetCents(), origin.context().riskMode(),
                null, null, origin.context().priceAssumptionCents());
        var child = planSvc.create(ownerId(ctx), origin.marketKind(), origin.worldId(), origin.accountId(), childRequest);
        planSvc.linkRelated(ownerId(ctx), origin.id(), child.id(), role);
        if (planStrategy.selectedCandidate(ownerId(ctx), child.id()) == null) {
            planStrategy.copyScoutSelection(ownerId(ctx), origin.id(), request.candidateId(), child);
        }
        child = planSvc.get(ownerId(ctx), child.id());
        ctx.json(new ApiResponses.ScoutSpawn<>(origin, child, role));
    }

    private void planOutcomesLatest(Context ctx) {
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        ObjectNode out = planOutcomes.latest(ownerId(ctx), plan, analysisCtx(ctx));
        JsonNode selected = planStrategy.selectedCandidate(ownerId(ctx), plan.id());
        ctx.json(new ApiResponses.PlanOutcomesLatest<>(out.path("outcomes"), out.path("comparisons"),
                out.path("backtests"), selected));
    }

    /** Evidence owns path generation; Outcomes later values the exact selected package on this artifact. */
    private void planEnsembleRun(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanEnsembleRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        var spec = planScenarioSpec(plan, body.over());
        var world = worldParam(activeWorld(ctx));
        var marketVol = outcomeController.marketVol(plan.symbol(), world, spec.horizonDays());
        var calibrated = spec.volAnnual() > 0 || marketVol == null ? spec : spec.withVol(marketVol.atmIv());
        double rate = market.riskFreeRateQuote(Math.max(1, calibrated.horizonDays()), world).annualRate();
        var run = simEngine.previewRun(plan.symbol(), calibrated, world, analysisCtx(ctx),
                body.levels() == null ? List.of() : body.levels(), marketVol, rate);
        var iv = body.iv() == null ? defaultPlanIv(run.ensemble().spec(), marketVol) : body.iv();
        JsonNode input = Json.MAPPER.valueToTree(body);
        var stored = planOutcomes.saveEnsemble(ownerId(ctx), plan, run.ensemble(), iv, rate, run.preview(), input);
        ObjectNode preview = Json.MAPPER.valueToTree(run.preview());
        preview.put("planEnsembleId", stored.id());
        preview.put("planEnsembleFingerprint", stored.fingerprint());
        if (preview.path("receipt") instanceof ObjectNode receipt) receipt.put("fingerprint", stored.fingerprint());
        ctx.json(new ApiResponses.PlanEnsemble<>(plan,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis()), preview));
    }

    private void planOutcomeRun(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanOutcomeRunRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
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
            var saved = planOutcomes.saveRiskNeutral(ownerId(ctx), plan, body.expectedVersion(),
                    candidate.path("id").asText(), result, input, evaluated.interpretation(), analysisCtx(ctx));
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
        var saved = planOutcomes.savePathOutcome(ownerId(ctx), plan, body.expectedVersion(),
                candidate.path("id").asText(), stored, result, input, interpretation);
        ctx.json(new ApiResponses.PlanOutcomeWithEnsemble<>(plan, saved,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis())));
    }

    /** Compare the Plan's current proposals on one exact stored path artifact. */
    private void planOutcomeCompare(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanOutcomeCompareRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
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
        var competition = planStrategy.latestCompetition(ownerId(ctx), plan.id());
        if (competition != null) for (JsonNode node : competition.result().path("candidates")) {
            if (!(node instanceof ObjectNode candidate)) continue;
            String id = candidate.path("id").asText();
            if (!id.isBlank() && (wanted == null || wanted.contains(id))) field.put(id, candidate);
        }
        JsonNode selectedNode = planStrategy.selectedCandidate(ownerId(ctx), plan.id());
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
        var saved = planOutcomes.saveComparison(ownerId(ctx), plan, body.expectedVersion(), stored, ranked,
                Json.MAPPER.valueToTree(body), interpretation, fairness);
        ctx.json(new ApiResponses.PlanComparison<>(plan, saved,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis())));
    }

    private record PlanComparisonMeta(ObjectNode candidate,
                                      io.liftandshift.strikebench.outcomes.OutcomeContract.Position position,
                                      int qty, long roundTripFees) {}

    private void planBacktestRun(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanBacktestRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
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
                    body.stopFraction(), body.rollDte(), body.startingCashCents()), analysisCtx(ctx), ownerId(ctx));
        } else if ("single".equals(engineKind)) {
            report = backtester.run(new Backtester.BacktestRequest(plan.symbol(), family, body.from(), body.to(),
                    body.targetDte() == null ? plan.context().horizonDays() : body.targetDte(), body.entryEveryDays(),
                    body.qty(), body.slippagePct(), body.startingCashCents()), analysisCtx(ctx), ownerId(ctx));
        } else throw new IllegalArgumentException("engine must be single or portfolio");
        JsonNode reportJson = Json.MAPPER.valueToTree(report);
        var saved = planOutcomes.saveBacktest(ownerId(ctx), plan, body.expectedVersion(),
                candidate.path("id").asText(), engineKind, reportJson, Json.MAPPER.valueToTree(body), analysisCtx(ctx));
        ctx.json(new ApiResponses.PlanBacktest<>(plan, saved, report));
    }

    private void planBacktestGet(Context ctx) {
        String planId = ctx.pathParam("id");
        String backtestId = ctx.pathParam("backtestId");
        planOutcomes.requireBacktest(ownerId(ctx), planId, backtestId);
        ctx.json(backtester.get(backtestId));
    }

    private void planRehearsalsList(Context ctx) {
        ctx.json(planRehearsals.list(ownerId(ctx), ctx.pathParam("id")));
    }

    private void planRehearsalCreate(Context ctx) {
        var body = requireBody(bodyOrNull(ctx,
                io.liftandshift.strikebench.plan.PlanRehearsalService.Request.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        var created = planRehearsals.create(ownerId(ctx), plan, body, analysisCtx(ctx));
        ctx.status(201).json(new ApiResponses.PlanRehearsal<>(
                created, planSvc.get(ownerId(ctx), plan.id())));
    }

    private void planDecisionLatest(Context ctx) {
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        ctx.json(new ApiResponses.PlanDecisionState<>(plan,
                planStrategy.selectedCandidate(ownerId(ctx), plan.id()),
                planDecisions.latest(ownerId(ctx), plan.id())));
    }

    private void planDecisionPreview(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
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

    private void planDecisionTrade(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body);
        ApiResponses.TradePreviewResponse payload = tradeController.previewPayload(ctx, order);
        var decisionInput = planDecisionInput(ctx, plan, body, candidate, payload);
        var prepared = planDecisions.prepareTrade(decisionInput);
        TradeController.CreatedTrade created = tradeController.execute(ctx, order, prepared.hook());
        var updated = planSvc.get(ownerId(ctx), plan.id());
        ctx.status(201).json(new ApiResponses.PlanPlacedTrade<>(updated, TradeView.of(created.trade()),
                planDecisions.latest(ownerId(ctx), plan.id()), created.verdict().warnings()));
    }

    private void planDecisionCash(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body);
        ApiResponses.TradePreviewResponse payload = tradeController.previewPayload(ctx, order);
        ObjectNode decision = planDecisions.chooseCash(planDecisionInput(ctx, plan, body, candidate, payload));
        var updated = planSvc.get(ownerId(ctx), plan.id());
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
        return new io.liftandshift.strikebench.plan.PlanDecisionService.Input(ownerId(ctx), plan,
                body.expectedVersion(), candidate.path("id").asText(), currentAccount(ctx),
                payload.preview(), payload.economics(),
                io.liftandshift.strikebench.paper.AccountRiskContext.load(db, ownerId(ctx)),
                body.qty(),
                body.acknowledgedRisks() == null ? List.of() : body.acknowledgedRisks(), body.note(), analysisCtx(ctx));
    }

    private void planManageGet(Context ctx) {
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        ObjectNode management = planManagement.latest(ownerId(ctx), plan.id());
        String tradeId = management.path("activeTradeId").asText(null);
        if (tradeId == null) {
            for (JsonNode link : management.withArray("links")) if (link.hasNonNull("tradeId")) tradeId = link.get("tradeId").asText();
        }
        ctx.json(new ApiResponses.PlanWorkspace<>(plan,
                planDecisions.latest(ownerId(ctx), plan.id()), management,
                tradeId == null ? null : tradeController.detailData(tradeId)));
    }

    private void plansPortfolio(Context ctx) {
        String world = worldParam(activeWorld(ctx));
        var marketKind = activePlanMarket(ctx);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var plan : planSvc.list(ownerId(ctx), marketKind,
                marketKind == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED ? world : null, false)) {
            if (plan.status() == io.liftandshift.strikebench.plan.Plan.Status.ARCHIVED) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("plan", plan);
            ObjectNode decision = planDecisions.latest(ownerId(ctx), plan.id());
            if (decision != null) row.put("decision", decision);
            String tradeId = planManagement.activeTradeId(ownerId(ctx), plan.id());
            if (tradeId != null) {
                row.put("tradeId", tradeId);
                try { row.put("mark", trades.currentMark(tradeId)); }
                catch (RuntimeException e) { row.put("markUnavailable", true); }
            }
            rows.add(row);
        }
        ctx.json(new ApiResponses.PlanRows<>(rows, marketKind.name()));
    }

    private void planManageRefresh(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanManageRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan); requirePlanVersion(plan, body.expectedVersion());
        String tradeId = requirePlanActiveTrade(ctx, plan.id());
        var mark = trades.refresh(tradeId);
        planManagement.recordMark(ownerId(ctx), plan.id(), body.expectedVersion(), tradeId, mark);
        ctx.json(new ApiResponses.PlanMark<>(planSvc.get(ownerId(ctx), plan.id()), mark,
                planManagement.latest(ownerId(ctx), plan.id())));
    }

    private void planManageUnwind(Context ctx) { planManageClose(ctx, "CLOSE", false); }
    private void planManageSettle(Context ctx) { planManageClose(ctx, "SETTLE", false); }
    private void planManageRoll(Context ctx) { planManageClose(ctx, "ROLL", true); }

    private void planManageClose(Context ctx, String kind, boolean roll) {
        var body = requireBody(bodyOrNull(ctx, PlanManageRequest.class));
        if (!Boolean.TRUE.equals(body.confirm())) throw new IllegalArgumentException("confirm=true is required");
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan); requirePlanVersion(plan, body.expectedVersion());
        String tradeId = requirePlanActiveTrade(ctx, plan.id());
        var hook = planManagement.lifecycleHook(ownerId(ctx), plan.id(), body.expectedVersion(), kind, roll);
        TradeService.CloseResult result = "SETTLE".equals(kind)
                ? trades.settle(tradeId, true, hook) : trades.unwind(tradeId, true, hook);
        tradeController.resolveRecommendation(tradeId,
                "SETTLE".equals(kind) ? "SETTLED" : "CLOSED",
                TradeController.decisionPnl(result.trade(), result.realizedPnlCents()));
        ctx.json(new ApiResponses.PlanClosedTrade<>(planSvc.get(ownerId(ctx), plan.id()),
                TradeView.of(result.trade()), result.realizedPnlCents(),
                planManagement.latest(ownerId(ctx), plan.id()),
                roll ? rolledPosition(result.trade(), worldParam(activeWorld(ctx))) : null));
    }

    private void planManageVoid(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanManageRequest.class));
        if (!Boolean.TRUE.equals(body.confirm())) throw new IllegalArgumentException("confirm=true is required");
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan); requirePlanVersion(plan, body.expectedVersion());
        String tradeId = requirePlanActiveTrade(ctx, plan.id());
        var hook = planManagement.lifecycleHook(ownerId(ctx), plan.id(), body.expectedVersion(), "VOID", false);
        TradeRecord deleted = trades.delete(tradeId, true, hook);
        ctx.json(new ApiResponses.PlanTrade<>(planSvc.get(ownerId(ctx), plan.id()),
                TradeView.of(deleted), planManagement.latest(ownerId(ctx), plan.id())));
    }

    private void planManageReview(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanManageRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan); requirePlanVersion(plan, body.expectedVersion());
        ObjectNode decision = planDecisions.latest(ownerId(ctx), plan.id());
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
        ObjectNode management = planManagement.recordCashReview(ownerId(ctx), plan.id(), body.expectedVersion(),
                new io.liftandshift.strikebench.plan.PlanManagementService.CashReview(startUnderlying, endUnderlying,
                        stockPnl, entry, packageEnd, rejectedPnl, horizon,
                        decision.hasNonNull("pop") ? decision.get("pop").asDouble() : null,
                        "Frozen-IV modeled value at the lane-owned horizon close; kept outside trade calibration"));
        ctx.json(new ApiResponses.PlanManagement<>(planSvc.get(ownerId(ctx), plan.id()), management));
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
        String tradeId = planManagement.activeTradeId(ownerId(ctx), planId);
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
            var stored = planOutcomes.loadCurrentEnsemble(ownerId(ctx), plan, body.ensembleId(), analysisCtx(ctx));
            if (!basis.name().equals(stored.basis())) throw new IllegalArgumentException("Stored ensemble basis does not match the requested basis");
            return stored;
        }
        var existing = planOutcomes.latestEnsemble(ownerId(ctx), plan, basis.name(), analysisCtx(ctx));
        if (existing != null && basis == io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC
                && body.over() == null && body.iv() == null) return existing;
        var spec = planScenarioSpec(plan, body.over());
        String world = worldParam(activeWorld(ctx));
        double spot = pathEnsembles.anchorSpot(new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(
                plan.symbol(), world, analysisCtx(ctx)));
        io.liftandshift.strikebench.sim.PathEnsembleService.Ensemble ensemble;
        if (basis == io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC) {
            var marketVol = outcomeController.marketVol(plan.symbol(), world, spec.horizonDays());
            if (spec.volAnnual() <= 0 && marketVol != null) spec = spec.withVol(marketVol.atmIv());
            ensemble = pathEnsembles.build(new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(
                    plan.symbol(), world, analysisCtx(ctx)), basis, spec, null, spot);
        } else {
            var evidence = planEvidence.latest(ownerId(ctx), plan.id(), analysisCtx(ctx));
            if (evidence == null) throw new IllegalStateException("Run Past evidence in this Plan before using historical analog outcomes.");
            ensemble = pathEnsembles.fromStudy(new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(
                    plan.symbol(), world, analysisCtx(ctx)), basis, spec, evidence.result(), spot);
        }
        double rate = market.riskFreeRateQuote(Math.max(1, ensemble.spec().horizonDays()), world).annualRate();
        var marketVol = outcomeController.marketVol(plan.symbol(), world, ensemble.spec().horizonDays());
        var iv = body.iv() == null ? defaultPlanIv(ensemble.spec(), marketVol) : body.iv();
        return planOutcomes.saveEnsemble(ownerId(ctx), plan, ensemble, iv, rate, null, Json.MAPPER.valueToTree(body));
    }

    private ObjectNode selectedPlanCandidate(Context ctx, io.liftandshift.strikebench.plan.Plan.View plan) {
        JsonNode selected = planStrategy.selectedCandidate(ownerId(ctx), plan.id());
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
        var analysis = analysisCtx(ctx);
        return new io.liftandshift.strikebench.outcomes.OutcomeContract.MarketContext(plan.symbol(),
                activePlanMarket(ctx).name(), activeWorld(ctx), analysis.datasetId(), null);
    }

    private static void requirePlanVersion(io.liftandshift.strikebench.plan.Plan.View plan, Long expected) {
        if (expected == null) throw new IllegalArgumentException("expectedVersion is required");
        if (plan.version() != expected) throw new IllegalStateException("This Plan changed in another tab. Reload it before running Outcomes.");
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

    private static String planHorizon(Integer days) {
        return io.liftandshift.strikebench.model.Horizon.fromTradingSessions(days).key();
    }


    // ---- Account ----
}
