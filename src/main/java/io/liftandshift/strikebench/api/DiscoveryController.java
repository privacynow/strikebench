package io.liftandshift.strikebench.api;

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
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.recommend.AutoRecommender;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.recommend.RiskBudgetPolicy;
import io.liftandshift.strikebench.strategy.StrategyIntent;
import io.liftandshift.strikebench.util.DataUnavailableException;
import io.liftandshift.strikebench.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Owns strategy discovery, shared ranking, universe scans, and portfolio construction. */
final class DiscoveryController {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryController.class);

    private final Db db;
    private final MarketDataService market;
    private final EvaluationService evaluations;
    private final RecommendationEngine engine;
    private final AutoRecommender auto;
    private final PositionsService positions;
    private final UniverseService universe;
    private final SimulationSessions simSessions;
    private final Clock clock;
    private final io.liftandshift.strikebench.sim.MarketVolatilityResolver marketVolatility;
    private final Function<Context, Account> accountResolver;
    private final Function<Context, String> ownerResolver;
    private final Function<Context, String> activeWorldResolver;
    private final Function<Context, Long> riskCapResolver;

    DiscoveryController(Db db, MarketDataService market, EvaluationService evaluations,
                        RecommendationEngine engine, AutoRecommender auto,
                        PositionsService positions, UniverseService universe,
                        SimulationSessions simSessions, Clock clock,
                        io.liftandshift.strikebench.sim.MarketVolatilityResolver marketVolatility,
                        Function<Context, Account> accountResolver,
                        Function<Context, String> ownerResolver,
                        Function<Context, String> activeWorldResolver,
                        Function<Context, Long> riskCapResolver) {
        this.db = db;
        this.market = market;
        this.evaluations = evaluations;
        this.engine = engine;
        this.auto = auto;
        this.positions = positions;
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
                this::researchIntentLadder, this::opportunities, this::optimize));
    }

    private static String worldParam(String world) {
        return world == null || "observed".equals(world) ? null : world;
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
        if (result.candidates() == null || result.candidates().isEmpty()) return result;
        try {
            // The decision score is computed from the SAME market that priced the candidates —
            // inside a simulated session that is the world's spot/IV/vol, never observed (review P0).
            var evals = evaluations.evaluate(result.symbol(), result.intent(), result.thesis(), result.horizon(),
                    result.riskMode(), result.candidates(), acct.buyingPowerCents(), null, false,
                    io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, worldParam(world));
            if (evals.size() != result.candidates().size()) {
                throw new DataUnavailableException("Decision ranking did not evaluate every candidate");
            }
            com.fasterxml.jackson.databind.node.ObjectNode out =
                    (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(result);
            com.fasterxml.jackson.databind.node.ArrayNode cands = out.putArray("candidates");
            int favorable = 0, actionableFavorable = 0, mixed = 0, unfavorable = 0, unavailable = 0;
            for (var e : evals) { // evaluateAndRank order is exactly the monotonic Decision score
                com.fasterxml.jackson.databind.node.ObjectNode m =
                        (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(e.candidate());
                m.put("decisionScore", e.decisionScore());
                m.put("decisionViable", e.viable());
                m.put("structurallyEligible", e.viable());
                if (e.economics() != null) {
                    m.set("economics", Json.MAPPER.valueToTree(e.economics()));
                    m.put("economicVerdict", e.economics().verdict().name());
                    m.put("economicPlacement", e.economics().placement());
                    switch (e.economics().verdict()) {
                        case FAVORABLE -> {
                            favorable++;
                            if (e.economics().actionableFavorable()) actionableFavorable++;
                        }
                        case MIXED -> mixed++;
                        case UNFAVORABLE -> unfavorable++;
                        case UNAVAILABLE -> unavailable++;
                    }
                }
                if (e.evCents() != null && e.evCents() < 0) {
                    m.put("negativeEv", true); // never an unlabeled recommendation
                }
                attachEvaluationReceipt(m, e);
                cands.add(m);
            }
            out.put("ranking", "decision"); // disclosed: what ordered this list
            out.put("economicPolicy", "decision_score");
            out.put("favorableCount", favorable);
            out.put("actionableFavorableCount", actionableFavorable);
            out.put("mixedCount", mixed);
            out.put("unfavorableCount", unfavorable);
            out.put("unavailableCount", unavailable);
            out.put("economicMessage", actionableFavorable > 0
                    ? actionableFavorable + " setup" + (actionableFavorable == 1 ? "" : "s")
                            + " worth investigating on end-to-end observed evidence; compare costs and alternatives before acting."
                    : favorable > 0
                        ? favorable + " setup" + (favorable == 1 ? "" : "s")
                            + " favorable inside an explicit generated teaching market. That is useful practice, not evidence of a live-market edge."
                    : "No setup currently shows a robust after-cost edge. Mixed and unfavorable structures remain available for comparison and learning.");
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
        ObjectNode receipt = candidate.putObject("evaluation");
        if (evaluation.capital() != null) receipt.set("capital", Json.MAPPER.valueToTree(evaluation.capital()));
        if (evaluation.risk() != null) receipt.set("risk", Json.MAPPER.valueToTree(evaluation.risk()));
        if (evaluation.evidence() != null) receipt.set("evidence", Json.MAPPER.valueToTree(evaluation.evidence()));
        if (evaluation.management() != null) receipt.set("management", Json.MAPPER.valueToTree(evaluation.management()));
        if (evaluation.score() != null) receipt.set("score", Json.MAPPER.valueToTree(evaluation.score()));
        if (evaluation.explanation() != null) receipt.set("explanation", Json.MAPPER.valueToTree(evaluation.explanation()));
    }

    /** Builds the one ranked Decision competition, including explicit cash and share-owning baselines. */
    ApiResponses.DecisionCompetition decisionCompetition(Context ctx, RecommendationEngine.Request decision) {
        RecommendationEngine.Result result = resolveAndRecommend(ctx, decision);
        Account account = accountResolver.apply(ctx);
        String world = activeWorldResolver.apply(ctx);
        boolean generatedWorld = !"observed".equals(world);
        String owner = ownerResolver.apply(ctx);
        var ranked = evaluations.evaluate(result.symbol(), result.intent(), result.thesis(), result.horizon(),
                result.riskMode(), result.candidates(), account.buyingPowerCents(), owner, !generatedWorld,
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, worldParam(world));

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
        if (req == null || req.symbol() == null || req.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        Account acct = accountResolver.apply(ctx);
        // Hold-based intents read the account's real position when the caller didn't supply
        // one: free shares + average basis feed strike selection and the intent framing.
        StrategyIntent intent = StrategyIntent.parse(req.intent());
        // ACQUIRE is excluded: holdings.sharesOwned means "shares I WANT" there, and injecting
        // the existing position would silently size new purchases to what is already owned.
        if (req.holdings() == null && intent != StrategyIntent.DIRECTIONAL && intent != StrategyIntent.ACQUIRE) {
            try {
                PositionsService.PositionView pos = positions.get(acct.id(), req.symbol());
                req = new RecommendationEngine.Request(req.symbol(), req.thesis(), req.horizon(), req.riskMode(),
                        req.maxLossCents(), req.maxRiskPctOfAccount(), req.minConfidence(), req.allowedStrategies(),
                        req.avoidEarnings(), req.allow0dte(), req.intent(),
                        new RecommendationEngine.Holdings((int) Math.min(Integer.MAX_VALUE, pos.freeShares()),
                                pos.avgCostCents(), null),
                        req.filters());
            } catch (io.liftandshift.strikebench.util.ResourceNotFoundException ignored) { /* no position — engine handles it */ }
        }
        // R4 via THE policy (review IC-1): the declared risk-capital line caps the engine's
        // per-trade budget — one translation shared by every recommending surface.
        Long capGov = RiskBudgetPolicy.effectiveMaxLossCents(
                req.maxLossCents(), riskCapResolver.apply(ctx));
        if (!java.util.Objects.equals(capGov, req.maxLossCents())) {
            req = new RecommendationEngine.Request(req.symbol(), req.thesis(), req.horizon(), req.riskMode(),
                    capGov, req.maxRiskPctOfAccount(), req.minConfidence(), req.allowedStrategies(),
                    req.avoidEarnings(), req.allow0dte(), req.intent(), req.holdings(), req.filters());
        }
        return engine.recommend(req, acct.buyingPowerCents(), activeWorldResolver.apply(ctx));
    }


    public record OpportunitiesRequest(List<String> universe, String thesis, String horizon,
                                       String riskMode, String intent, Integer topN) {}

    /**
     * Universe-scale competition: scans a universe (the active one by default), keeps each symbol's
     * best viable idea, and ranks them cross-symbol so the strongest opportunities surface first.
     */
    private void opportunities(Context ctx) {
        OpportunitiesRequest req = ApiRequest.bodyOrNull(ctx, OpportunitiesRequest.class);
        if (req == null) req = new OpportunitiesRequest(null, null, null, null, null, null);
        String world = worldParam(activeWorldResolver.apply(ctx));
        // Inside a simulated session the scan covers the WORLD's symbols through the world-routed
        // engine — an observed-universe scan sized with sim capital was a cross-lane blend (P1).
        List<String> symbols = (req.universe() != null && !req.universe().isEmpty())
                ? req.universe()
                : world != null
                        ? market.worldSymbols(world).map(List::copyOf).orElse(List.of())
                        : universe.active().symbols();
        Account acct = accountResolver.apply(ctx);
        String ownerId = ownerResolver.apply(ctx);
        int topN = req.topN() != null ? req.topN() : 8;
        var rcScan = io.liftandshift.strikebench.paper.AccountRiskContext.load(db, ownerResolver.apply(ctx));
        var result = evaluations.scan(symbols, req.intent(),
                req.thesis() != null ? req.thesis() : "neutral",
                req.horizon() != null ? req.horizon() : "month",
                req.riskMode() != null ? req.riskMode() : "balanced",
                acct.buyingPowerCents(), world != null ? null : ownerId, topN, world, rcScan.riskCapitalCents());
        ctx.json(new ApiResponses.Opportunities<>(result.ranked(), result.notes(), result.scanned()));
    }

    public record OptimizeRequest(List<String> universe, String thesis, String horizon, String riskMode,
                                  String intent, Long totalCapitalCents, Long maxPerPositionCents,
                                  Integer maxPositions, Double maxSymbolPct, String objective, Boolean diagnostic) {}

    /** Portfolio construction: scan a universe, then allocate a budget across the winners. */
    private void optimize(Context ctx) {
        OptimizeRequest req = ApiRequest.bodyOrNull(ctx, OptimizeRequest.class);
        if (req == null) req = new OptimizeRequest(null, null, null, null, null, null, null, null, null, null, null);
        String optWorld = worldParam(activeWorldResolver.apply(ctx));
        List<String> symbols = (req.universe() != null && !req.universe().isEmpty())
                ? req.universe()
                : optWorld != null
                        ? market.worldSymbols(optWorld).map(List::copyOf).orElse(List.of())
                        : universe.active().symbols();
        Account acct = accountResolver.apply(ctx);
        String ownerId = ownerResolver.apply(ctx);
        var rcOpt = io.liftandshift.strikebench.paper.AccountRiskContext.load(db, ownerResolver.apply(ctx));
        var scan = evaluations.scan(symbols, req.intent(),
                req.thesis() != null ? req.thesis() : "neutral",
                req.horizon() != null ? req.horizon() : "month",
                req.riskMode() != null ? req.riskMode() : "balanced",
                acct.buyingPowerCents(), optWorld != null ? null : ownerId, Math.max(1, symbols.size()),
                optWorld, rcOpt.riskCapitalCents());
        long budget = req.totalCapitalCents() != null ? req.totalCapitalCents() : acct.buyingPowerCents();
        var result = new io.liftandshift.strikebench.research.PortfolioOptimizer().optimize(scan.ranked(),
                new io.liftandshift.strikebench.research.PortfolioOptimizer.Constraints(
                        budget, req.maxPerPositionCents(), req.maxPositions(), req.maxSymbolPct(), req.objective(),
                        Boolean.TRUE.equals(req.diagnostic())));
        ctx.json(new ApiResponses.Optimization<>(result, scan.scanned(), scan.notes()));
    }

    private void researchIntentLadder(Context ctx) {
        String symbol = ctx.pathParam("symbol").trim().toUpperCase(Locale.ROOT);
        RecommendationEngine.Request req = ApiRequest.bodyOrNull(ctx, RecommendationEngine.Request.class);
        if (req == null) {
            req = new RecommendationEngine.Request(symbol, null, "month", null, null, null, null,
                    null, true, false, null, null, null);
        } else {
            if (req.symbol() != null && !req.symbol().isBlank()
                    && !symbol.equalsIgnoreCase(req.symbol())) {
                throw new IllegalArgumentException("The ladder symbol must match the Research workspace");
            }
            req = new RecommendationEngine.Request(symbol, req.thesis(), req.horizon(), req.riskMode(),
                    req.maxLossCents(), req.maxRiskPctOfAccount(), req.minConfidence(), req.allowedStrategies(),
                    req.avoidEarnings(), req.allow0dte(), req.intent(), req.holdings(), req.filters());
        }
        Account acct = accountResolver.apply(ctx);
        StrategyIntent intent = StrategyIntent.parse(req.intent());
        if (req.holdings() == null && intent != StrategyIntent.DIRECTIONAL && intent != StrategyIntent.ACQUIRE) {
            try {
                PositionsService.PositionView pos = positions.get(acct.id(), req.symbol());
                req = new RecommendationEngine.Request(req.symbol(), req.thesis(), req.horizon(), req.riskMode(),
                        req.maxLossCents(), req.maxRiskPctOfAccount(), req.minConfidence(), req.allowedStrategies(),
                        req.avoidEarnings(), req.allow0dte(), req.intent(),
                        new RecommendationEngine.Holdings((int) Math.min(Integer.MAX_VALUE, pos.freeShares()),
                                pos.avgCostCents(), null),
                        req.filters());
            } catch (io.liftandshift.strikebench.util.ResourceNotFoundException ignored) { /* buy-write ladder */ }
        }
        // R4 via THE policy: ladders obey the same declared-capital translation as every surface.
        Long capLad = RiskBudgetPolicy.effectiveMaxLossCents(
                req.maxLossCents(), riskCapResolver.apply(ctx));
        if (!java.util.Objects.equals(capLad, req.maxLossCents())) {
            req = new RecommendationEngine.Request(req.symbol(), req.thesis(), req.horizon(), req.riskMode(),
                    capLad, req.maxRiskPctOfAccount(), req.minConfidence(), req.allowedStrategies(),
                    req.avoidEarnings(), req.allow0dte(), req.intent(), req.holdings(), req.filters());
        }
        var ladder = engine.ladder(req, acct.buyingPowerCents(), activeWorldResolver.apply(ctx));
        // R9: the SAME decision policy annotates every rung — no ranked surface escapes it.
        try {
            var rungEvals = evaluations.evaluate(req.symbol(), req.intent(), req.thesis(), req.horizon(),
                    req.riskMode(), ladder.rungs(), acct.buyingPowerCents(), null, false,
                    io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, worldParam(activeWorldResolver.apply(ctx)));
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
                        m.put("decisionScore", e.decisionScore());
                        m.put("decisionViable", e.viable());
                        m.put("structurallyEligible", e.viable());
                        if (e.economics() != null) {
                            m.set("economics", Json.MAPPER.valueToTree(e.economics()));
                            m.put("economicVerdict", e.economics().verdict().name());
                            m.put("economicPlacement", e.economics().placement());
                        }
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

    private void researchScout(Context ctx) {
        AutoRecommender.AutoRequest req = ApiRequest.bodyOrNull(ctx, AutoRecommender.AutoRequest.class);
        if (req == null) { // absent body (or the literal document "null") means defaults
            req = new AutoRecommender.AutoRequest(null, null, null, null, null, null, null, null, null, null, null, null);
        }
        String world = activeWorldResolver.apply(ctx);
        if (req.universe() == null || req.universe().isEmpty()) {
            // Default scan list: the selected universe — or, inside a simulated session, the
            // world's OWN symbols (the observed universe does not exist in that market).
            List<String> scan = "observed".equals(world) ? universe.active().symbols()
                    : simSessions.getOrRestore(world, ownerResolver.apply(ctx))
                            .map(w -> List.copyOf(w.config().symbolBetas().keySet()))
                            .orElseGet(() -> universe.active().symbols());
            req = new AutoRecommender.AutoRequest(scan, req.horizons(), req.maxPicks(),
                    req.targetProfitCents(), req.maxLossCents(), req.maxRiskPctOfAccount(), req.minConfidence(),
                    req.riskMode(), req.allow0dte(), req.intents(), req.filters(), req.thesisOverride());
        }
        Account acct = accountResolver.apply(ctx);
        // THE policy applies to the scout too (review IC-1): auto and manual recommendations
        // must size under the identical declared-capital cap.
        Long capAuto = RiskBudgetPolicy.effectiveMaxLossCents(
                req.maxLossCents(), riskCapResolver.apply(ctx));
        if (!java.util.Objects.equals(capAuto, req.maxLossCents())) {
            req = new AutoRecommender.AutoRequest(req.universe(), req.horizons(), req.maxPicks(),
                    req.targetProfitCents(), capAuto, req.maxRiskPctOfAccount(), req.minConfidence(),
                    req.riskMode(), req.allow0dte(), req.intents(), req.filters(), req.thesisOverride());
        }
        // Real holdings feed the EXIT/HEDGE scans and let INCOME write against held shares
        List<AutoRecommender.HoldingInfo> held = positions.list(acct.id()).stream()
                .map(p -> new AutoRecommender.HoldingInfo(p.symbol(),
                        (int) Math.min(Integer.MAX_VALUE, p.freeShares()), p.avgCostCents()))
                .toList();
        ctx.json(auto.run(req, acct.buyingPowerCents(), held, worldParam(world)));
    }

}
