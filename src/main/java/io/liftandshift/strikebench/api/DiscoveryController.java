package io.liftandshift.strikebench.api;
import static io.liftandshift.strikebench.market.MarketLane.worldParam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.market.sim.SimulationSessions;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountObjectiveService;
import io.liftandshift.strikebench.paper.BookRiskService;
import io.liftandshift.strikebench.paper.PortfolioAccountingService;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.recommend.AutoRecommender;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.DecisionDeclarationPolicy;
import io.liftandshift.strikebench.recommend.OpportunityScanner;
import io.liftandshift.strikebench.recommend.RedeploymentFrontier;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.recommend.RiskBudgetPolicy;
import io.liftandshift.strikebench.strategy.StrategyIntent;
import io.liftandshift.strikebench.util.DataUnavailableException;
import io.liftandshift.strikebench.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Owns strategy discovery, shared ranking, universe scans, and portfolio construction. */
final class DiscoveryController {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryController.class);
    private static final String SCOUT_STREAM_TYPE = "application/x-ndjson";
    /*
     * Safari may retain a tiny streaming response until its receive buffer fills. One ignorable
     * whitespace record commits enough bytes for the first real progress frame to paint promptly;
     * NDJSON consumers already ignore blank records.
     */
    private static final byte[] SCOUT_STREAM_PREAMBLE =
            (" ".repeat(2048) + "\n").getBytes(StandardCharsets.UTF_8);

    private final Db db;
    private final MarketDataService market;
    private final EvaluationService evaluations;
    private final OpportunityScanner opportunityScanner;
    private final RecommendationEngine engine;
    private final AutoRecommender auto;
    private final PositionsService positions;
    private final TradeService trades;
    private final PortfolioAccountingService portfolioBooks;
    private final AccountObjectiveService accountObjectives;
    private final BookRiskService bookRisk;
    private final io.liftandshift.strikebench.position.PositionLifecycleDecisionService lifecycleDecisions;
    private final UniverseService universe;
    private final SimulationSessions simSessions;
    private final Clock clock;
    private final io.liftandshift.strikebench.sim.MarketVolatilityResolver marketVolatility;
    private final Function<Context, Account> accountResolver;
    private final Function<Context, String> ownerResolver;
    private final Function<Context, String> activeWorldResolver;
    private final Function<Context, Long> riskCapResolver;

    DiscoveryController(Db db, MarketDataService market, EvaluationService evaluations,
                        OpportunityScanner opportunityScanner,
                        RecommendationEngine engine, AutoRecommender auto,
                        PositionsService positions, TradeService trades,
                        PortfolioAccountingService portfolioBooks,
                        AccountObjectiveService accountObjectives, BookRiskService bookRisk,
                        io.liftandshift.strikebench.position.PositionLifecycleDecisionService lifecycleDecisions,
                        UniverseService universe,
                        SimulationSessions simSessions, Clock clock,
                        io.liftandshift.strikebench.sim.MarketVolatilityResolver marketVolatility,
                        Function<Context, Account> accountResolver,
                        Function<Context, String> ownerResolver,
                        Function<Context, String> activeWorldResolver,
                        Function<Context, Long> riskCapResolver) {
        this.db = db;
        this.market = market;
        this.evaluations = evaluations;
        this.opportunityScanner = opportunityScanner;
        this.engine = engine;
        this.auto = auto;
        this.positions = positions;
        this.trades = trades;
        this.portfolioBooks = portfolioBooks;
        this.accountObjectives = accountObjectives;
        this.bookRisk = bookRisk;
        this.lifecycleDecisions = lifecycleDecisions;
        this.universe = universe;
        this.simSessions = simSessions;
        this.clock = clock;
        this.marketVolatility = marketVolatility;
        this.accountResolver = accountResolver;
        this.ownerResolver = ownerResolver;
        this.activeWorldResolver = activeWorldResolver;
        this.riskCapResolver = riskCapResolver;
    }

    void register(JavalinConfig config) {
        DiscoveryRoutes.register(config, new DiscoveryRoutes.Handlers(
                this::welcomeTeachingExample, this::researchScout,
                this::researchIntentLadder, this::optimize));
    }


    // ---- Product-owned strategy discovery ----

    private void welcomeTeachingExample(Context ctx) {
        var request = new RecommendationEngine.Request("AAPL", "bullish", "month", "conservative",
                null, null, null, null, true, false, "DIRECTIONAL", null, null);
        RecommendationEngine.Result result = resolveAndRecommend(ctx, request);
        ctx.json(decisionRanked(result, accountResolver.apply(ctx), activeWorldResolver.apply(ctx)));
    }

    /**
     * ONE ranking everywhere: candidates leave this API ordered by the DECISION score (the full
     * StrategyEvaluation composite — gates, capital, tail risk, evidence haircut), the same score
     * the Decision page and opportunity scan use. Ranking is mandatory: returning a second plausible
     * order on evaluation failure would make a data problem look like a product judgment.
     */
    Object decisionRanked(RecommendationEngine.Result result, Account acct, String world) {
        return decisionRanked(result, acct, world, null);
    }

    /** Ranked with the plan's declared assignment preference woven into the DecisionPolicy lens. */
    Object decisionRanked(RecommendationEngine.Result result, Account acct, String world,
                          String assignmentPreference) {
        if (result.candidates() == null || result.candidates().isEmpty()) return result;
        try {
            // The decision score is computed from the SAME market that priced the candidates —
            // inside a simulated session that is the world's spot/IV/vol, never observed (review P0).
            // ONE ranking primitive (shared with Scout and the Portfolio scan): best package per
            // family in decision-score order. rank() emits exactly one evaluation per candidate or
            // throws, so an empty field here is the "nothing could be ranked" data failure.
            var evals = evaluations.evaluateBestPerFamily(result.symbol(), result.intent(), result.thesis(),
                    result.horizon(), result.riskMode(), result.candidates(), acct.buyingPowerCents(),
                    io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, worldParam(world),
                    practiceExposure(acct, result.symbol()), assignmentPreference);
            if (evals.isEmpty()) {
                throw new DataUnavailableException("Decision ranking did not evaluate every candidate");
            }
            com.fasterxml.jackson.databind.node.ObjectNode out =
                    (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(result);
            com.fasterxml.jackson.databind.node.ArrayNode cands = out.putArray("candidates");
            io.liftandshift.strikebench.eval.EconomicReadiness.Tally readinessTally =
                    io.liftandshift.strikebench.eval.EconomicReadiness.tally();
            for (var e : evals) { // evaluateAndRank order is exactly the monotonic Decision score
                com.fasterxml.jackson.databind.node.ObjectNode m =
                        (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(e.candidate());
                m.set("identity", Json.MAPPER.valueToTree(
                        io.liftandshift.strikebench.strategy.StrategyCatalog.identify(
                                io.liftandshift.strikebench.strategy.StrategyFamily.valueOf(
                                        e.candidate().strategy()))));
                var endorsement = e.evidence() == null ? null
                        : e.evidence().claims().get("endorsement");
                readinessTally.add(e.assessment().economics(),
                        endorsement == null ? null : endorsement.missingDimensions());
                attachEvaluationReceipt(m, e);
                cands.add(m);
            }
            io.liftandshift.strikebench.eval.EconomicReadiness readiness = readinessTally.summarize();
            out.put("ranking", "decision"); // disclosed: what ordered this list
            out.put("economicPolicy", "decision_score");
            out.put("favorableCount", readiness.favorable());
            out.put("actionableFavorableCount", readiness.actionableFavorable());
            out.put("mixedCount", readiness.mixed());
            out.put("unfavorableCount", readiness.unfavorable());
            out.put("unavailableCount", readiness.unavailable());
            out.put("economicReadiness", readiness.readiness());
            var missingArray = out.putArray("missingEvidence");
            readiness.missingEvidence().forEach(missingArray::add);
            int actionable = readiness.actionableFavorable();
            out.put("economicMessage", actionable > 0
                    ? actionable + " setup" + (actionable == 1 ? "" : "s")
                            + " worth investigating on end-to-end observed evidence; compare costs and alternatives before acting."
                    : readiness.favorable() > 0
                        ? readiness.favorable() + " setup" + (readiness.favorable() == 1 ? "" : "s")
                            + " favorable inside an explicit generated teaching market. That is useful practice, not evidence of a live-market edge."
                    : readiness.needsDailyHistory()
                        ? "A favorable observed verdict cannot be formed yet because eligible daily history is missing. The structures remain available for mechanics and market-implied comparison; acquire observed bars in Data → Sources & jobs."
                    : "No setup currently shows a material realistic-measure advantage after costs. Mixed and unfavorable structures remain available for comparison and learning.");
            return out;
        } catch (RuntimeException e) {
            log.warn("Decision ranking is temporarily unavailable");
            log.debug("Decision-ranking failure detail", e);
            if (e instanceof DataUnavailableException unavailable) throw unavailable;
            throw new DataUnavailableException(
                    "Decision ranking is unavailable right now; no alternate ranking was substituted", e);
        }
    }

    private static void attachEvaluationReceipt(ObjectNode candidate,
                                                 io.liftandshift.strikebench.eval.StrategyEvaluation evaluation) {
        candidate.set("evaluation", Json.MAPPER.valueToTree(ApiResponses.EvaluationReceipt.of(evaluation)));
    }

    /** Builds the one ranked Decision competition, including explicit cash and share-owning baselines. */
    ApiResponses.DecisionCompetition decisionCompetition(Context ctx, RecommendationEngine.Request decision) {
        RecommendationEngine.Result result = resolveAndRecommend(ctx, decision);
        Account account = accountResolver.apply(ctx);
        String world = activeWorldResolver.apply(ctx);
        boolean generatedWorld = !"observed".equals(world);
        String owner = ownerResolver.apply(ctx);
        var ranked = evaluations.evaluateBestPerFamily(result.symbol(), result.intent(), result.thesis(),
                result.horizon(), result.riskMode(), result.candidates(), account.buyingPowerCents(),
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, worldParam(world),
                practiceExposure(account, result.symbol()));
        if (!generatedWorld) evaluations.persist(ranked, owner);

        String recommendationId = null;
        if (!ranked.isEmpty() && !generatedWorld) {
            try {
                recommendationId = evaluations.recordSurfaced(ranked.getFirst().id(), owner);
            } catch (RuntimeException e) {
                log.warn("A recommendation could not be added to the learning record");
                log.debug("Recommendation-record detail", e);
            }
        }

        List<ApiResponses.DecisionBaseline> baselines = new java.util.ArrayList<>();
        baselines.add(new ApiResponses.DecisionBaseline("CASH", 0L, 0L, 0L, null, 0L,
                null, true, null, null, null, null, null, null,
                "Do nothing: $0 expected, $0 at risk, zero costs — every idea above must beat this after fees and spreads."));
        addBuyAndHoldBaseline(result, world, baselines);

        return new ApiResponses.DecisionCompetition(result.symbol(), String.valueOf(result.intent()),
                ranked, result.rejected(), baselines, recommendationId,
                generatedWorld
                        ? "Simulated market — this competition is NOT recorded in your calibration record."
                        : null);
    }

    private void addBuyAndHoldBaseline(RecommendationEngine.Result result, String world,
                                       List<ApiResponses.DecisionBaseline> baselines) {
        String laneWorld = worldParam(world);
        try {
            var quote = market.quote(result.symbol(), laneWorld).orElse(null);
            if (quote == null || quote.mark() == null) return;

            double spot = quote.mark().doubleValue();
            long capitalCents = Math.round(spot * 100) * 100;
            LocalDate frontExpiration = result.candidates().stream()
                    .flatMap(candidate -> candidate.legs().stream())
                    .map(leg -> {
                        try { return LocalDate.parse(leg.expiration()); }
                        catch (RuntimeException invalidExpiration) { return null; }
                    })
                    .filter(java.util.Objects::nonNull)
                    .min(LocalDate::compareTo)
                    .orElse(null);
            LocalDate laneToday = market.simInstant(laneWorld)
                    .map(instant -> LocalDate.ofInstant(instant,
                            io.liftandshift.strikebench.market.MarketHours.EASTERN))
                    .orElseGet(() -> LocalDate.now(clock));
            int horizonDays = frontExpiration == null ? 30
                    : (int) Math.max(1, ChronoUnit.DAYS.between(laneToday, frontExpiration));
            Double iv = marketVolatility.atmIv(result.symbol(), laneWorld, 30);
            double volatility = iv == null ? 0.3 : iv;
            var stockLeg = io.liftandshift.strikebench.model.Leg.stock(
                    io.liftandshift.strikebench.model.LegAction.BUY, 1, quote.mark());
            var curve = io.liftandshift.strikebench.pricing.PayoffCurve.of(List.of(stockLeg), 1);
            var rateQuote = market.riskFreeRateQuote(horizonDays, laneWorld);
            double rate = rateQuote.annualRate();
            var probability = io.liftandshift.strikebench.pricing.ProbabilityMap.of(
                    curve, spot, volatility, horizonDays / 365.0, rate, List.of());

            baselines.add(new ApiResponses.DecisionBaseline("BUY_AND_HOLD",
                    curve.riskNeutralExpectedValueCents(spot, volatility, horizonDays / 365.0, rate),
                    null, probability.cvar95Cents(), probability.stressLossCents(), capitalCents,
                    probability.pAnyProfit(), true, market.lane(laneWorld).name(), laneToday.toString(),
                    horizonDays, volatility, iv != null ? "same-market ATM IV" : "30% modeled fallback",
                    rateQuote.evidence(),
                    "Own 100 shares (" + io.liftandshift.strikebench.util.Money.fmt(capitalCents)
                            + "): present-value risk-neutral EV is approximately $0 before costs (r="
                            + String.format(Locale.ROOT, "%.2f", rate * 100)
                            + "%, q=0 assumed), with no expiry or option spread; the tail numbers are its modeled "
                            + horizonDays + "-day downside at the chain's own vol."));
        } catch (RuntimeException e) {
            log.debug("Buy-and-hold baseline unavailable", e);
        }
    }

    /** Parses the recommend request (injecting real holdings for hold-based intents) and runs the engine. */
    private RecommendationEngine.Result resolveAndRecommend(Context ctx) {
        return resolveAndRecommend(ctx, ApiRequest.bodyOrNull(ctx, RecommendationEngine.Request.class));
    }

    RecommendationEngine.Result resolveAndRecommend(Context ctx, RecommendationEngine.Request req) {
        StrategyIntent intent = DecisionDeclarationPolicy.requireRecommendation(
                "Strategy recommendation", req, true);
        Account acct = accountResolver.apply(ctx);
        req = withAccountHoldings(req, intent, acct);
        req = withRiskCap(req, ctx);
        return engine.recommend(req, acct.buyingPowerCents(), activeWorldResolver.apply(ctx));
    }

    /**
     * Hold-based intents read the account's real position when the caller didn't supply one: free
     * shares + average basis feed strike selection and the intent framing. ACQUIRE is excluded —
     * holdings.sharesOwned means "shares I WANT" there, and injecting the existing position would
     * silently size new purchases to what is already owned. Shared by every recommending surface.
     */
    private RecommendationEngine.Request withAccountHoldings(RecommendationEngine.Request req,
                                                             StrategyIntent intent, Account acct) {
        if (req.holdings() != null || intent == StrategyIntent.DIRECTIONAL || intent == StrategyIntent.ACQUIRE) {
            return req;
        }
        try {
            PositionsService.PositionView pos = positions.get(acct.id(), req.symbol());
            return req.withHoldings(new RecommendationEngine.Holdings(
                    (int) Math.min(Integer.MAX_VALUE, pos.freeShares()), pos.avgCostCents(), null));
        } catch (io.liftandshift.strikebench.util.ResourceNotFoundException noPosition) {
            return req; // no position — the engine handles it (buy-write style where relevant)
        }
    }

    /**
     * R4 via THE policy (review IC-1): the declared risk-capital line caps the engine's per-trade
     * budget — one translation shared by every recommending surface (recommend, ladder, scout).
     */
    private RecommendationEngine.Request withRiskCap(RecommendationEngine.Request req, Context ctx) {
        Long cap = RiskBudgetPolicy.effectiveMaxLossCents(req.maxLossCents(), riskCapResolver.apply(ctx));
        return java.util.Objects.equals(cap, req.maxLossCents()) ? req : req.withMaxLossCents(cap);
    }

    /** The same declared risk-capital cap for the Scout's AutoRequest. */
    private AutoRecommender.AutoRequest withRiskCap(AutoRecommender.AutoRequest req, Context ctx) {
        Long cap = RiskBudgetPolicy.effectiveMaxLossCents(req.maxLossCents(), riskCapResolver.apply(ctx));
        return java.util.Objects.equals(cap, req.maxLossCents()) ? req : req.withMaxLossCents(cap);
    }


    public record OptimizeRequest(List<String> universe, String thesis, String horizon, String riskMode,
                                  String intent, Long totalCapitalCents, Long maxPerPositionCents,
                                  Integer maxPositions, Double maxSymbolPct, String objective, Boolean diagnostic) {}

    /** Portfolio construction: scan a universe, then allocate a budget across the winners. */
    private void optimize(Context ctx) {
        OptimizeRequest req = ApiRequest.bodyOrNull(ctx, OptimizeRequest.class);
        DecisionDeclarationPolicy.requireConstruction("Portfolio construction",
                req == null ? null : req.intent(), req == null ? null : req.thesis(),
                req == null ? null : req.horizon(), req == null ? null : req.riskMode(),
                req == null ? null : req.objective());
        String activeWorld = activeWorldResolver.apply(ctx);
        String optWorld = worldParam(activeWorld);
        List<String> symbols = (req.universe() != null && !req.universe().isEmpty())
                ? req.universe()
                : optWorld != null
                        ? market.worldSymbols(optWorld).map(List::copyOf).orElse(List.of())
                        : universe.active().symbols();
        Account acct = accountResolver.apply(ctx);
        String ownerId = ownerResolver.apply(ctx);
        var rcOpt = io.liftandshift.strikebench.paper.AccountRiskContext.load(db, ownerResolver.apply(ctx));
        RedeploymentFrontier.UniverseScope scope = universeScope(req.universe(), activeWorld, ownerId);
        var scan = opportunityScanner.scanWithFrontier(symbols, req.intent(), req.thesis(), req.horizon(), req.riskMode(),
                acct.buyingPowerCents(), optWorld != null ? null : ownerId, Math.max(1, symbols.size()),
                optWorld, rcOpt.riskCapitalCents(), evaluations -> frontierContext(ownerId, acct,
                        acct.id(), scope, null, evaluations, optWorld == null));
        long budget = req.totalCapitalCents() != null ? req.totalCapitalCents() : acct.buyingPowerCents();
        var result = new io.liftandshift.strikebench.research.PortfolioOptimizer().optimize(scan.ranked(),
                new io.liftandshift.strikebench.research.PortfolioOptimizer.Constraints(
                        budget, req.maxPerPositionCents(), req.maxPositions(), req.maxSymbolPct(), req.objective(),
                        Boolean.TRUE.equals(req.diagnostic())));
        ctx.json(new ApiResponses.Optimization<>(result, scan.scanned(), scan.notes(), scan.frontier()));
    }

    private void researchIntentLadder(Context ctx) {
        String symbol = ctx.pathParam("symbol").trim().toUpperCase(Locale.ROOT);
        RecommendationEngine.Request req = ApiRequest.bodyOrNull(ctx, RecommendationEngine.Request.class);
        StrategyIntent intent = DecisionDeclarationPolicy.requireLadder("Strike ladder", req);
        if (req.symbol() != null && !req.symbol().isBlank()
                && !symbol.equalsIgnoreCase(req.symbol())) {
            throw new IllegalArgumentException("The ladder symbol must match the Research workspace");
        }
        req = new RecommendationEngine.Request(symbol, req.thesis(), req.horizon(), req.riskMode(),
                req.maxLossCents(), req.maxRiskPctOfAccount(), req.minConfidence(), req.allowedStrategies(),
                req.avoidEarnings(), req.allow0dte(), req.intent(), req.holdings(), req.filters());
        Account acct = accountResolver.apply(ctx);
        req = withAccountHoldings(req, intent, acct);
        req = withRiskCap(req, ctx);
        var ladder = engine.ladder(req, acct.buyingPowerCents(), activeWorldResolver.apply(ctx));
        // R9: the SAME decision policy annotates every rung — no ranked surface escapes it.
        try {
            var rungEvals = evaluations.evaluate(req.symbol(), req.intent(), req.thesis(), req.horizon(),
                    req.riskMode(), ladder.rungs(), acct.buyingPowerCents(), null, false,
                    io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, worldParam(activeWorldResolver.apply(ctx)),
                    practiceExposure(acct, req.symbol()));
            if (rungEvals.size() == ladder.rungs().size()) {
                com.fasterxml.jackson.databind.node.ObjectNode out =
                        (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(ladder);
                com.fasterxml.jackson.databind.node.ArrayNode arr = out.putArray("rungs");
                // ladder ORDER is the strike ladder (its meaning) — decision score is an annotation
                var byCand = new java.util.IdentityHashMap<Object, io.liftandshift.strikebench.eval.StrategyEvaluation>();
                for (var e : rungEvals) byCand.put(e.candidate(), e);
                for (var c : ladder.rungs()) {
                    com.fasterxml.jackson.databind.node.ObjectNode m =
                            (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(c);
                    var e = byCand.get(c);
                    if (e != null) {
                        attachEvaluationReceipt(m, e);
                    }
                    arr.add(m);
                }
                ctx.json(out);
                return;
            }
        } catch (RuntimeException e) {
            log.warn("Ladder decision details are temporarily unavailable");
            log.debug("Ladder decision-detail failure", e);
            if (e instanceof DataUnavailableException unavailable) throw unavailable;
            throw new DataUnavailableException(
                    "The strike ladder cannot be compared until its decision analysis is available", e);
        }
        throw new DataUnavailableException("The strike ladder did not evaluate every rung");
    }

    private io.liftandshift.strikebench.eval.PortfolioExposureContext practiceExposure(
            Account account, String symbol) {
        return trades.portfolioDollarDelta(account.id(), symbol).toContext(
                io.liftandshift.strikebench.position.PositionDomain.ExecutionLane.PRACTICE);
    }

    private void researchScout(Context ctx) {
        AutoRecommender.AutoRequest req = ApiRequest.bodyOrNull(ctx, AutoRecommender.AutoRequest.class);
        DecisionDeclarationPolicy.requireScout("Universe Scout", req);
        String world = activeWorldResolver.apply(ctx);
        RedeploymentFrontier.UniverseScope scope = universeScope(req.universe(), world,
                ownerResolver.apply(ctx));
        if (req.universe() == null || req.universe().isEmpty()) {
            // Default scan list: the selected universe — or, inside a simulated session, the
            // world's OWN symbols (the observed universe does not exist in that market).
            List<String> scan = "observed".equals(world) ? universe.active().symbols()
                    : simSessions.getOrRestore(world, ownerResolver.apply(ctx))
                            .map(w -> List.copyOf(w.config().symbolBetas().keySet()))
                            .orElseGet(() -> universe.active().symbols());
            req = new AutoRecommender.AutoRequest(scan, req.horizons(), req.maxPicks(),
                    req.targetProfitCents(), req.maxLossCents(), req.maxRiskPctOfAccount(), req.minConfidence(),
                    req.riskMode(), req.allow0dte(), req.intents(), req.filters(), req.thesisOverride(),
                    req.destinationAccountId(), req.redeployment());
        }
        Account acct = accountResolver.apply(ctx);
        String owner = ownerResolver.apply(ctx);
        // THE policy applies to the scout too (review IC-1): auto and manual recommendations
        // must size under the identical declared-capital cap.
        req = withRiskCap(req, ctx);
        String destination = req.destinationAccountId() == null || req.destinationAccountId().isBlank()
                ? acct.id() : req.destinationAccountId().trim();
        if (!"observed".equals(world) && !destination.equals(acct.id())) {
            throw new IllegalArgumentException(
                    "A generated-market Scout cannot project a trade into a real tracked account");
        }
        io.liftandshift.strikebench.position.PositionLifecycleDecisionService.ResolvedAction resolved = null;
        if (req.redeployment() != null) {
            if (!"observed".equals(world)) {
                throw new IllegalArgumentException("Tracked-position redeployment requires the observed market");
            }
            if (destination.equals(acct.id())) {
                throw new IllegalArgumentException(
                        "A tracked lifecycle receipt requires its tracked destination account id");
            }
            resolved = lifecycleDecisions.resolveAction(owner, destination,
                    req.redeployment().lifecycleReceiptId(), req.redeployment().action(),
                    req.redeployment().quantity());
        }

        List<AutoRecommender.HoldingInfo> held;
        long destinationBuyingPower;
        if (destination.equals(acct.id())) {
            held = positions.list(acct.id()).stream()
                    .map(p -> new AutoRecommender.HoldingInfo(p.symbol(),
                            (int) Math.min(Integer.MAX_VALUE, p.freeShares()), p.avgCostCents()))
                    .toList();
            destinationBuyingPower = acct.buyingPowerCents();
        } else {
            var summary = portfolioBooks.summary(owner, destination);
            held = portfolioBooks.equityHoldings(owner, destination).stream()
                    .map(p -> new AutoRecommender.HoldingInfo(p.symbol(),
                            (int) Math.min(Integer.MAX_VALUE, p.freeShares()),
                            p.avgEconomicCostPerShareCents())).toList();
            Long reported = summary.liquidity().genuinelyFreeBuyingPower().cents();
            destinationBuyingPower = reported != null ? Math.max(0, reported)
                    : resolved != null && resolved.capitalReleasedCents() != null
                            ? resolved.capitalReleasedCents() : 0;
        }
        AutoRecommender.AutoRequest finalReq = req;
        var finalResolved = resolved;
        String finalDestination = destination;
        boolean observedWorld = "observed".equals(world);
        var contextFactory = (java.util.function.Function<List<io.liftandshift.strikebench.eval.StrategyEvaluation>,
                RedeploymentFrontier.Context>) evaluations -> frontierContext(owner, acct,
                finalDestination, scope, finalResolved, evaluations, observedWorld);
        if (acceptsScoutStream(ctx)) {
            ctx.disableCompression()
                    .contentType(SCOUT_STREAM_TYPE)
                    .header("Cache-Control", "no-store")
                    .header("X-Accel-Buffering", "no");
            ScoutStreamWriter stream = new ScoutStreamWriter(ctx.outputStream(), ctx);
            try {
                AutoRecommender.AutoResult result = auto.runWithFrontier(finalReq,
                        destinationBuyingPower, held, worldParam(world), contextFactory,
                        progress -> stream.write(new ScoutStreamFrame(
                                "progress", progress, null, null)));
                stream.write(new ScoutStreamFrame("complete", null, result, null));
            } catch (RuntimeException failure) {
                log.warn("Progressive Universe Scout failed after its response began");
                log.debug("Progressive Universe Scout failure", failure);
                stream.write(new ScoutStreamFrame("error", null, null,
                        failure.getMessage() == null
                                ? "The opportunity scan could not finish."
                                : failure.getMessage()));
            }
            return;
        }
        ctx.json(auto.runWithFrontier(finalReq, destinationBuyingPower, held,
                worldParam(world), contextFactory));
    }

    private static boolean acceptsScoutStream(Context ctx) {
        String accept = ctx.header("Accept");
        return accept != null && accept.toLowerCase(Locale.ROOT).contains(SCOUT_STREAM_TYPE);
    }

    private record ScoutStreamFrame(String type, AutoRecommender.Progress progress,
                                    AutoRecommender.AutoResult result, String error) {}

    /** One response-local writer; worker callbacks may arrive concurrently during signal scans. */
    private static final class ScoutStreamWriter {
        private final OutputStream out;
        private final Context ctx;
        private boolean closed;
        private boolean primed;

        private ScoutStreamWriter(OutputStream out, Context ctx) {
            this.out = out;
            this.ctx = ctx;
        }

        private synchronized void write(ScoutStreamFrame frame) {
            if (closed) return;
            try {
                if (!primed) {
                    out.write(SCOUT_STREAM_PREAMBLE);
                    primed = true;
                }
                out.write(Json.MAPPER.writeValueAsBytes(frame));
                out.write('\n');
                out.flush();
                ctx.res().flushBuffer();
            } catch (IOException | RuntimeException disconnected) {
                closed = true;
            }
        }
    }

    private RedeploymentFrontier.UniverseScope universeScope(List<String> requested, String world,
                                                              String owner) {
        if (requested == null || requested.isEmpty()) {
            if (!"observed".equals(world)) {
                var symbols = simSessions.getOrRestore(world, owner)
                        .map(session -> List.copyOf(session.config().symbolBetas().keySet()))
                        .orElseGet(() -> universe.active().symbols());
                return new RedeploymentFrontier.UniverseScope("SIMULATED_WORLD",
                        "Current generated market", symbols);
            }
            UniverseService.Active active = universe.active();
            return new RedeploymentFrontier.UniverseScope(active.source().toUpperCase(Locale.ROOT),
                    active.sectorLabel(), active.symbols());
        }
        List<String> normalized = requested.stream().filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toUpperCase(Locale.ROOT)).filter(value -> !value.isBlank())
                .distinct().toList();
        List<String> opportunitySymbols = universe.warmSymbols();
        if (normalized.equals(opportunitySymbols)) {
            return new RedeploymentFrontier.UniverseScope(
                    universe.active().symbols().equals(opportunitySymbols) ? "ACTIVE" : "CURATED",
                    "Curated cross-sector opportunity universe", normalized);
        }
        for (var sector : io.liftandshift.strikebench.market.Universes.SECTORS.values()) {
            if (normalized.equals(sector.symbols())) {
                return new RedeploymentFrontier.UniverseScope("THEME", sector.label(), normalized);
            }
        }
        UniverseService.Active active = universe.active();
        if (normalized.equals(active.symbols())) {
            return new RedeploymentFrontier.UniverseScope(active.source().toUpperCase(Locale.ROOT),
                    active.sectorLabel(), normalized);
        }
        return new RedeploymentFrontier.UniverseScope("WATCHLIST", "Selected watchlist", normalized);
    }

    private RedeploymentFrontier.Context frontierContext(
            String owner, Account practice, String destination,
            RedeploymentFrontier.UniverseScope scope,
            io.liftandshift.strikebench.position.PositionLifecycleDecisionService.ResolvedAction resolved,
            List<io.liftandshift.strikebench.eval.StrategyEvaluation> evaluations,
            boolean includeTracked) {
        List<String> symbols = evaluations.stream()
                .map(io.liftandshift.strikebench.eval.StrategyEvaluation::symbol)
                .filter(java.util.Objects::nonNull).map(value -> value.toUpperCase(Locale.ROOT))
                .distinct().toList();
        List<RedeploymentFrontier.BookLane> lanes = new java.util.ArrayList<>();
        TradeService.DollarDeltaBook practiceDelta = trades.portfolioDollarDeltaBook(practice.id());
        Map<String, io.liftandshift.strikebench.eval.PortfolioExposureContext> practiceExposures =
                new LinkedHashMap<>();
        for (String symbol : symbols) {
            practiceExposures.put(symbol, practiceDelta.focus(symbol).toContext(
                    io.liftandshift.strikebench.position.PositionDomain.ExecutionLane.PRACTICE));
        }
        lanes.add(new RedeploymentFrontier.BookLane("PRACTICE", practice.id(), practice.name(),
                practiceExposures, null, null, practice.reservedCents(), "SYSTEM_CALCULATED"));

        if (includeTracked) {
            BookRiskService.Lane riskLane = bookRisk.lane(owner, null);
            for (BookRiskService.AccountRisk risk : riskLane.accounts()) {
                PortfolioAccountingService.PortfolioSummary summary =
                        portfolioBooks.summary(owner, risk.accountId());
                PortfolioAccountingService.DollarDeltaBook delta =
                        portfolioBooks.portfolioDollarDeltaBook(owner, risk.accountId());
                Map<String, io.liftandshift.strikebench.eval.PortfolioExposureContext> exposures =
                        new LinkedHashMap<>();
                for (String symbol : symbols) {
                    exposures.put(symbol, delta.focus(symbol).toContext(
                            io.liftandshift.strikebench.position.PositionDomain.ExecutionLane.REAL));
                }
                AccountObjectiveService.Revision revision = accountObjectives.latest(owner, risk.accountId());
                AccountObjectiveService.AccountCapacityPolicy policy = revision == null
                        ? AccountObjectiveService.AccountCapacityPolicy.empty() : revision.capacityPolicy();
                var reportedReserve = summary.liquidity().recordedOrReportedReserve();
                Long encumbrance = reportedReserve.cents() != null ? reportedReserve.cents()
                        : summary.collateral().knownBlockedCashCents();
                String authority = reportedReserve.cents() != null
                        ? reportedReserve.authority().name() : "MODEL_DERIVED";
                lanes.add(new RedeploymentFrontier.BookLane("REAL", risk.accountId(), risk.name(),
                        exposures, risk, policy, encumbrance, authority));
            }
        }
        RedeploymentFrontier.RedeploymentSource source = resolved == null ? null
                : new RedeploymentFrontier.RedeploymentSource(resolved.receiptId(), resolved.accountId(),
                resolved.symbol(), resolved.action(), resolved.quantity(),
                resolved.executableCloseCostCents(), resolved.capitalReleasedCents(),
                resolved.closingPnlCents(), resolved.postActionBook(), resolved.basisEffect(),
                resolved.authority(), resolved.basis());
        return new RedeploymentFrontier.Context(scope, destination, lanes, source);
    }

}
