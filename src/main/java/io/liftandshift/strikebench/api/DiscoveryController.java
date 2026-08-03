package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.model.BroadBasedIndexOptions;
import static io.liftandshift.strikebench.market.MarketMode.worldParam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketMode;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountObjectiveService;
import io.liftandshift.strikebench.paper.BookRiskService;
import io.liftandshift.strikebench.paper.PortfolioAccountingService;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.recommend.AutoRecommender;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.DecisionDeclarationPolicy;
import io.liftandshift.strikebench.recommend.HoldingsEvidence;
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
                        Clock clock,
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
            var evals = evaluations.evaluateBestPerFamily(new EvaluationService.RankingRequest(
                    result.symbol(), result.intent(), result.thesis(), result.horizon(),
                    result.riskMode(), result.candidates(), acct.buyingPowerCents(),
                    io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, worldParam(world),
                    practiceExposure(acct, result.symbol()), assignmentPreference,
                    result.riskBudgetCents()));
            if (evals.isEmpty()) {
                throw new DataUnavailableException("Decision ranking did not evaluate every candidate");
            }
            com.fasterxml.jackson.databind.node.ObjectNode out =
                    (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(result);
            com.fasterxml.jackson.databind.node.ArrayNode cands = out.putArray("candidates");
            io.liftandshift.strikebench.eval.EconomicReadiness.Tally readinessTally =
                    io.liftandshift.strikebench.eval.EconomicReadiness.tally();
            java.time.Instant marketNow = market.marketNow(worldParam(world), clock);
            io.liftandshift.strikebench.eval.DecisionEndorsement deskPick = null;
            for (var e : evals) { // evaluateAndRank order is exactly the monotonic Decision score
                com.fasterxml.jackson.databind.node.ObjectNode m =
                        (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(e.candidate());
                m.put("id", e.id());
                m.set("identity", Json.MAPPER.valueToTree(
                        io.liftandshift.strikebench.strategy.StrategyCatalog.identify(
                                io.liftandshift.strikebench.strategy.StrategyCatalog.ClassificationRequest.draft(
                                        e.candidate().strategy(), e.spec().symbol(), e.candidate().qty(),
                                        e.candidate().legs().stream()
                                                .map(io.liftandshift.strikebench.recommend.LegView::toLeg)
                                                .toList(),
                                        Boolean.TRUE.equals(e.candidate().usesHeldShares())))));
                // B5: the exact trading-sessions/calendar-days-to-expiry result (MarketHours via
                // OptionTime) rides each candidate, so the desk shows real sessions, never a client count.
                attachCandidateTime(m, marketNow);
                attachCandidateEvent(m, result.symbol(), world);
                attachCandidateSettlement(m, result.symbol());
                var endorsement = e.evidence() == null ? null
                        : e.evidence().claims().get("endorsement");
                readinessTally.add(e.assessment().economics(),
                        endorsement == null ? null : endorsement.missingDimensions());
                ApiResponses.EvaluationResult.attachTo(m, e);
                var promotion = e.endorsement();
                if (deskPick == null && promotion.endorsed()) deskPick = promotion;
                cands.add(m);
            }
            io.liftandshift.strikebench.eval.EconomicReadiness readiness = readinessTally.summarize();
            out.put("ranking", "decision"); // disclosed: what ordered this list
            out.put("economicPolicy", "decision_score");
            if (deskPick == null) out.putNull("deskPickCandidateId");
            else out.put("deskPickCandidateId", deskPick.candidateId());
            out.set("deskPickEndorsement", Json.MAPPER.valueToTree(deskPick == null
                    ? new io.liftandshift.strikebench.eval.DecisionEndorsement(false,
                        io.liftandshift.strikebench.eval.DecisionEndorsement.COMPARISON,
                        null, List.of("No ranked package cleared every promotion gate."),
                        "The full ranked field remains available for explicit comparison.")
                    : deskPick));
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

    /** Builds the one ranked Decision competition, including explicit cash and share-owning baselines. */
    ApiResponses.DecisionCompetition decisionCompetition(Context ctx, RecommendationEngine.Request decision) {
        RecommendationEngine.Result result = resolveAndRecommend(ctx, decision);
        Account account = accountResolver.apply(ctx);
        String world = activeWorldResolver.apply(ctx);
        boolean generatedWorld = !"observed".equals(world);
        String owner = ownerResolver.apply(ctx);
        var ranked = evaluations.evaluateBestPerFamily(new EvaluationService.RankingRequest(
                result.symbol(), result.intent(), result.thesis(), result.horizon(),
                result.riskMode(), result.candidates(), account.buyingPowerCents(),
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, worldParam(world),
                practiceExposure(account, result.symbol()), null, result.riskBudgetCents()));
        if (!generatedWorld) {
            evaluations.persist(new EvaluationService.PersistenceRequest(ranked, owner, "observed"));
        }

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
        baselines.add(new ApiResponses.DecisionBaseline("CASH", 0L, 0L,
                true, null, null, null, null, null, null,
                io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.BaselineAnalysis.cash(),
                "Do nothing in options: $0 option P/L and zero option risk or trading costs. "
                        + "Settlement-fund interest, if any, remains a separate account result and "
                        + "is not modeled as $0 or silently added here."));
        addBuyAndHoldBaseline(result, world, baselines);

        return new ApiResponses.DecisionCompetition(result.symbol(), String.valueOf(result.intent()),
                ranked, result.rejected(), baselines, recommendationId,
                generatedWorld
                        ? "Simulated market — this competition is NOT recorded in your calibration record."
                        : null);
    }

    /**
     * B5: attach the live trading-sessions/calendar-days-to-expiry result to every candidate node,
     * computed at read time from its option legs against the mode's own today — so a restored
     * competition reports sessions REMAINING now, not a stale snapshot. THE one place that maps a
     * ranked/restored candidate node to its {@link io.liftandshift.strikebench.market.OptionTime}.
     */
    void attachCandidateTimes(com.fasterxml.jackson.databind.JsonNode result, String world) {
        if (result == null || !result.path("candidates").isArray()) return;
        java.time.Instant marketNow = market.marketNow(worldParam(world), clock);
        String symbol = result.path("symbol").asText(null);
        for (com.fasterxml.jackson.databind.JsonNode candidate : result.path("candidates")) {
            if (candidate instanceof com.fasterxml.jackson.databind.node.ObjectNode node) {
                attachCandidateEvidence(node, symbol, world, marketNow);
            }
        }
    }

    /**
     * Selected candidates are stored separately from their ranked competition. Decorate that
     * selected object through the same result owner so restoring a Plan cannot lose its time,
     * event, or settlement facts merely because it came from the selected-candidate record.
     */
    void attachCandidateEvidence(ObjectNode candidate, String fallbackSymbol, String world) {
        attachCandidateEvidence(candidate, fallbackSymbol, world,
                market.marketNow(worldParam(world), clock));
    }

    private void attachCandidateEvidence(ObjectNode candidate, String fallbackSymbol, String world,
                                         java.time.Instant marketNow) {
        attachCandidateTime(candidate, marketNow);
        attachCandidateEvent(candidate, candidate.path("symbol").asText(fallbackSymbol), world);
        attachCandidateSettlement(candidate, candidate.path("symbol").asText(fallbackSymbol));
    }

    /** The selected contract's event window, from the same EventService result evaluation uses. */
    private void attachCandidateEvent(com.fasterxml.jackson.databind.node.ObjectNode candidate,
                                      String symbol, String world) {
        if (symbol == null || symbol.isBlank()) return;
        LocalDate packageEnd = null;
        for (com.fasterxml.jackson.databind.JsonNode leg : candidate.path("legs")) {
            if ("STOCK".equalsIgnoreCase(leg.path("type").asText())) continue;
            String iso = leg.path("expiration").asText(null);
            if (iso == null || iso.isBlank()) continue;
            try {
                LocalDate parsed = LocalDate.parse(iso);
                if (packageEnd == null || parsed.isAfter(packageEnd)) packageEnd = parsed;
            } catch (RuntimeException ignored) { /* malformed expiry remains unavailable elsewhere */ }
        }
        candidate.set("event", Json.MAPPER.valueToTree(
                evaluations.eventProximity(symbol, packageEnd, worldParam(world))));
    }

    /**
     * Cash-equivalent scenario valuation and physical option deliverables are different facts.
     * Publish both from the backend so the browser never infers share or strike-cash consequences.
     */
    private static void attachCandidateSettlement(
            com.fasterxml.jackson.databind.node.ObjectNode candidate, String symbol) {
        boolean cashSettledIndex = BroadBasedIndexOptions.isKnownRoot(symbol);
        ObjectNode result = Json.MAPPER.createObjectNode();
        result.put("scenarioValuationPolicy", "CASH_INTRINSIC");
        result.put("exercisePolicy", "EXPIRATION_ONLY");
        result.put("contractSettlementStyle",
                cashSettledIndex ? "CASH_SETTLED_INDEX" : "PHYSICAL_EQUITY_OPTION");
        result.put("collateralAuthority", "MECHANICAL_NOT_ACCOUNT_SPECIFIC");
        result.put("valuationMeaning",
                "Scenario P/L values option legs at cash-equivalent intrinsic value at expiry.");
        result.put("physicalMeaning", cashSettledIndex
                ? "Known broad-based index options settle in cash; no shares are delivered."
                : "Standard equity-option exercise or assignment changes shares and strike cash; "
                        + "the per-leg conditional deliverables below are not inventory forecasts.");
        result.put("collateralMeaning", cashSettledIndex
                ? "Mechanical risk limits are shown here; exact account collateral is evaluated separately."
                : "Mechanical deliverables can release covered shares, convert cash-secured collateral "
                        + "into stock, or create a stock/cash obligation. Exact account collateral is evaluated separately.");
        var deliverables = result.putArray("conditionalDeliverables");
        int packageQty = Math.max(1, candidate.path("qty").asInt(1));
        int legIndex = 0;
        for (JsonNode leg : candidate.path("legs")) {
            String type = leg.path("type").asText("");
            if ("STOCK".equalsIgnoreCase(type)) { legIndex++; continue; }
            int ratio = Math.max(1, leg.path("ratio").asInt(1));
            int multiplier = Math.max(1, leg.path("multiplier").asInt(100));
            long shares = Math.multiplyExact((long) packageQty,
                    Math.multiplyExact((long) ratio, (long) multiplier));
            boolean buy = "BUY".equalsIgnoreCase(leg.path("action").asText());
            boolean call = "CALL".equalsIgnoreCase(type);
            long shareChange = (buy == call) ? shares : -shares;
            // LegView deliberately carries decimal values as normalized strings. TextNode's
            // decimalValue() returns zero, which previously turned every deliverable into a
            // fictitious $0 strike. Parse the normalized wire value explicitly.
            var strike = new java.math.BigDecimal(leg.path("strike").asText());
            long strikeCents = strike.movePointRight(2).longValueExact();
            long cashChangeCents = Math.multiplyExact(-shareChange, strikeCents);
            ObjectNode row = deliverables.addObject();
            row.put("legIndex", legIndex);
            row.put("condition", "IF_IN_THE_MONEY_AT_EXPIRY");
            row.put("action", leg.path("action").asText());
            row.put("type", type);
            row.put("strike", strike);
            row.put("expiration", leg.path("expiration").asText());
            if (cashSettledIndex) {
                row.put("cashSettled", true);
                row.put("shareChange", 0);
                row.putNull("strikeCashChangeCents");
                row.put("collateralConsequence",
                        "Cash-settled index option; no shares or strike cash are exchanged.");
            } else {
                row.put("cashSettled", false);
                row.put("shareChange", shareChange);
                row.put("strikeCashChangeCents", cashChangeCents);
                row.put("collateralConsequence", settlementConsequence(buy, call));
            }
            legIndex++;
        }
        candidate.set("settlement", result);
    }

    private static String settlementConsequence(boolean buy, boolean call) {
        if (buy && call) {
            return "Exercise pays strike cash and creates long shares.";
        }
        if (buy) {
            return "Exercise delivers shares for strike cash; without owned shares it creates short stock.";
        }
        if (call) {
            return "Assignment delivers shares; covered shares are released, otherwise a short-stock obligation remains.";
        }
        return "Assignment uses strike cash to buy shares; cash-secured collateral converts into stock.";
    }

    /** The candidate node's exact time-to-expiry via the one shared OptionTime/MarketHours convention. */
    static void attachCandidateTime(com.fasterxml.jackson.databind.node.ObjectNode candidate,
                                    java.time.Instant marketNow) {
        LocalDate frontExpiration = null;
        LocalDate finalExpiration = null;
        for (com.fasterxml.jackson.databind.JsonNode leg : candidate.path("legs")) {
            if ("STOCK".equalsIgnoreCase(leg.path("type").asText())) continue;
            String iso = leg.path("expiration").asText(null);
            if (iso == null || iso.isBlank()) continue;
            try {
                LocalDate parsed = LocalDate.parse(iso);
                if (frontExpiration == null || parsed.isBefore(frontExpiration)) frontExpiration = parsed;
                if (finalExpiration == null || parsed.isAfter(finalExpiration)) finalExpiration = parsed;
            } catch (RuntimeException ignored) { /* a malformed expiration contributes no session count */ }
        }
        candidate.set("time", Json.MAPPER.valueToTree(
                io.liftandshift.strikebench.market.OptionTime.toExpiry(marketNow, frontExpiration)));
        candidate.set("terminalTime", Json.MAPPER.valueToTree(
                io.liftandshift.strikebench.market.OptionTime.toExpiry(marketNow, finalExpiration)));
    }

    private void addBuyAndHoldBaseline(RecommendationEngine.Result result, String world,
                                       List<ApiResponses.DecisionBaseline> baselines) {
        String marketWorld = worldParam(world);
        try {
            var quote = market.quote(result.symbol(), marketWorld).orElse(null);
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
            LocalDate marketToday = market.marketToday(marketWorld, clock);
            int horizonDays = frontExpiration == null ? 30
                    : (int) Math.max(1, ChronoUnit.DAYS.between(marketToday, frontExpiration));
            Double iv = marketVolatility.atmIv(result.symbol(), marketWorld, 30);
            double volatility = iv == null ? 0.3 : iv;
            var rateQuote = market.riskFreeRateQuote(horizonDays, marketWorld);
            double rate = rateQuote.annualRate();
            var time = io.liftandshift.strikebench.market.OptionTime.toExpiry(
                    market.marketNow(marketWorld, clock), marketToday.plusDays(horizonDays));
            var baseline = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer
                    .analyzeBuyAndHold(Math.round(spot * 100), volatility, time, rate);

            baselines.add(new ApiResponses.DecisionBaseline("BUY_AND_HOLD",
                    null, capitalCents, true, market.mode(marketWorld,
                            io.liftandshift.strikebench.db.AnalysisContext.OBSERVED).name(),
                    marketToday.toString(),
                    horizonDays, volatility, iv != null ? "same-market ATM IV" : "30% modeled fallback",
                    rateQuote.evidence(), baseline,
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
        req = withAccountHoldings(req, intent, acct, ownerResolver.apply(ctx),
                activeWorldResolver.apply(ctx));
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
                                                             StrategyIntent intent, Account acct,
                                                             String ownerId, String world) {
        RecommendationEngine.Holdings declared = req.holdings();
        if (intent == StrategyIntent.DIRECTIONAL) {
            return req;
        }
        if (intent == StrategyIntent.ACQUIRE) {
            if (declared == null || declared.provenance()
                    == HoldingsEvidence.Provenance.ACQUISITION_TARGET) {
                return req;
            }
            return req.withHoldings(new RecommendationEngine.Holdings(
                    declared.sharesOwned(), declared.costBasisCents(), declared.targetPriceCents(),
                    declared.assignmentPreference(), HoldingsEvidence.Provenance.ACQUISITION_TARGET,
                    null, null, null));
        }
        // A user-supplied share count is an explicit what-if. Preserve it so the requested
        // package is actually analyzed, but preserve the HYPOTHETICAL_HOLDINGS label too: neither
        // ranking nor placement may reinterpret it as an account pledge.
        if (declared != null && declared.sharesOwned() != null
                && (declared.provenance() == null
                    || declared.provenance()
                        == HoldingsEvidence.Provenance.HYPOTHETICAL_HOLDINGS)) {
            return req.withHoldings(new RecommendationEngine.Holdings(
                    declared.sharesOwned(), declared.costBasisCents(), declared.targetPriceCents(),
                    declared.assignmentPreference(),
                    HoldingsEvidence.Provenance.HYPOTHETICAL_HOLDINGS,
                    null, null, null));
        }
        // A declared target/basis must not pre-empt the REAL position: for hold-based intents the
        // share count and basis come from the ONE destination account. Cross-account aggregation
        // would let shares in an IRA endorse a package destined for Practice (or vice versa).
        long practiceShares = 0;
        Long practiceBasis = null;
        try {
            PositionsService.PositionView pos = positions.get(acct.id(), req.symbol());
            practiceShares = pos.shares();
            practiceBasis = pos.avgCostCents();
        } catch (io.liftandshift.strikebench.util.ResourceNotFoundException noPosition) {
            // This destination has no shares.
        }
        String wanted = Symbol.normalize(req.symbol());
        // Subtract every pledge in this same Practice destination.
        long pledged = positions.pledgedBySymbol(acct.id()).getOrDefault(wanted, 0L);
        long practiceFree = Math.max(0, Math.subtractExact(practiceShares, pledged));
        return req.withHoldings(new RecommendationEngine.Holdings(
                (int) Math.min(Integer.MAX_VALUE, practiceFree), practiceBasis,
                declared == null ? null : declared.targetPriceCents(),
                declared == null ? null : declared.assignmentPreference(),
                HoldingsEvidence.Provenance.ACCOUNT_BACKED, acct.id(),
                MarketMode.isObservedWorld(world) ? "PRACTICE" : world, clock.instant().toEpochMilli()));
    }

    /**
     * The Scout's hold-based scan for the Practice destination. Tracked destinations are resolved
     * separately by account in {@link #auto(Context)}; this method must never merge custody modes.
     */
    List<AutoRecommender.HoldingInfo> combinedHeldShares(String ownerId, String practiceAccountId) {
        java.util.Map<String, Long> pledged = positions.pledgedBySymbol(practiceAccountId);
        java.util.List<AutoRecommender.HoldingInfo> out = new java.util.ArrayList<>();
        positions.list(practiceAccountId).forEach(p -> {
            String symbol = Symbol.normalize(p.symbol());
            long free = Math.max(0, p.shares() - pledged.getOrDefault(symbol, 0L));
            if (free <= 0) return;
            out.add(new AutoRecommender.HoldingInfo(symbol,
                    (int) Math.min(Integer.MAX_VALUE, free), p.avgCostCents(),
                    practiceAccountId, "PRACTICE", clock.instant().toEpochMilli()));
        });
        return java.util.List.copyOf(out);
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
                                  Integer maxPositions, Double maxSymbolPct, String objective,
                                  Boolean diagnostic, Boolean avoidEarnings) {}

    /** Portfolio construction: scan a universe, then allocate a budget across the winners. */
    private void optimize(Context ctx) {
        OptimizeRequest req = ApiRequest.bodyOrNull(ctx, OptimizeRequest.class);
        DecisionDeclarationPolicy.requireConstruction("Portfolio construction",
                req == null ? null : req.intent(), req == null ? null : req.thesis(),
                req == null ? null : req.horizon(), req == null ? null : req.riskMode(),
                req == null ? null : req.objective(), req == null ? null : req.avoidEarnings());
        String activeWorld = activeWorldResolver.apply(ctx);
        String optWorld = worldParam(activeWorld);
        List<String> symbols = (req.universe() != null && !req.universe().isEmpty())
                ? req.universe()
                : MarketMode.isObservedWorld(optWorld)
                        ? universe.active().symbols()
                        : market.worldSymbols(optWorld).map(List::copyOf).orElse(List.of());
        Account acct = accountResolver.apply(ctx);
        String ownerId = ownerResolver.apply(ctx);
        var rcOpt = io.liftandshift.strikebench.paper.AccountRiskContext.load(db, ownerResolver.apply(ctx));
        RedeploymentFrontier.UniverseScope scope = universeScope(req.universe(), activeWorld, ownerId);
        // The owner travels in BOTH modes now: the store records which market priced each row, so a
        // generated-market scan can persist its exact packages without them ever reading as
        // observed evidence — and without them being unadoptable inside their own world.
        var scan = opportunityScanner.scan(symbols, req.intent(), req.thesis(), req.horizon(), req.riskMode(),
                acct.buyingPowerCents(), ownerId, Math.max(1, symbols.size()),
                optWorld, rcOpt.riskCapitalCents(), req.avoidEarnings(),
                evaluations -> frontierContext(ownerId, acct,
                        acct.id(), scope, null, evaluations, MarketMode.isObservedWorld(optWorld)));
        long budget = req.totalCapitalCents() != null ? req.totalCapitalCents() : acct.buyingPowerCents();
        var result = new io.liftandshift.strikebench.research.PortfolioOptimizer().optimize(scan.ranked(),
                new io.liftandshift.strikebench.research.PortfolioOptimizer.Constraints(
                        budget, req.maxPerPositionCents(), req.maxPositions(), req.maxSymbolPct(), req.objective(),
                        Boolean.TRUE.equals(req.diagnostic())));
        ctx.json(new ApiResponses.Optimization<>(result, scan.scanned(), scan.notes(), scan.frontier()));
    }

    private void researchIntentLadder(Context ctx) {
        String symbol = Symbol.normalize(ctx.pathParam("symbol"));
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
        req = withAccountHoldings(req, intent, acct, ownerResolver.apply(ctx),
                activeWorldResolver.apply(ctx));
        req = withRiskCap(req, ctx);
        var ladder = engine.ladder(req, acct.buyingPowerCents(), activeWorldResolver.apply(ctx));
        // R9: the SAME decision policy annotates every rung — no ranked surface escapes it.
        try {
            var rungEvals = evaluations.evaluate(new EvaluationService.RankingRequest(
                    req.symbol(), req.intent(), req.thesis(), req.horizon(), req.riskMode(),
                    ladder.rungs(), acct.buyingPowerCents(),
                    io.liftandshift.strikebench.db.AnalysisContext.OBSERVED,
                    worldParam(activeWorldResolver.apply(ctx)), practiceExposure(acct, req.symbol()),
                    req.holdings() == null ? null : req.holdings().assignmentPreference(),
                    req.maxLossCents()));
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
                        ApiResponses.EvaluationResult.attachTo(m, e);
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
        return trades.portfolioDollarDelta(account.id(), symbol, null).toContext(
                io.liftandshift.strikebench.position.PositionDomain.BookType.PRACTICE);
    }

    private void researchScout(Context ctx) {
        AutoRecommender.AutoRequest req = ApiRequest.bodyOrNull(ctx, AutoRecommender.AutoRequest.class);
        DecisionDeclarationPolicy.requireScout("Universe Scout", req);
        String world = activeWorldResolver.apply(ctx);
        RedeploymentFrontier.UniverseScope scope = universeScope(req.universe(), world,
                ownerResolver.apply(ctx));
        if (req.universe() == null || req.universe().isEmpty()) {
            // Every market scans its own symbols. A missing generated-market universe is an
            // unavailable input, never permission to borrow names from the observed market.
            List<String> scan = MarketUniverseView.symbolsForWorld(market, universe, world);
            if (scan.isEmpty()) {
                throw new DataUnavailableException(
                        "The active market has no symbols available to scan.");
            }
            req = new AutoRecommender.AutoRequest(scan, req.horizons(), req.maxPicks(),
                    req.targetProfitCents(), req.maxLossCents(), req.maxRiskPctOfAccount(), req.minConfidence(),
                    req.riskMode(), req.allow0dte(), req.intents(), req.filters(), req.thesisOverride(),
                    req.destinationAccountId(), req.redeployment(), req.avoidEarnings());
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
                        "A tracked lifecycle result requires its tracked destination account id");
            }
            resolved = lifecycleDecisions.resolveAction(owner, destination,
                    req.redeployment().lifecycleAnalysisId(), req.redeployment().action(),
                    req.redeployment().quantity());
        }

        List<AutoRecommender.HoldingInfo> held;
        long destinationBuyingPower;
        if (destination.equals(acct.id())) {
            held = combinedHeldShares(owner, acct.id());
            destinationBuyingPower = acct.buyingPowerCents();
        } else {
            var summary = portfolioBooks.summary(owner, destination);
            held = portfolioBooks.equityHoldings(owner, destination).stream()
                    .map(p -> new AutoRecommender.HoldingInfo(p.symbol(),
                            (int) Math.min(Integer.MAX_VALUE, p.freeShares()),
                            p.avgEconomicCostPerShareCents(), destination, "TRACKED",
                            clock.instant().toEpochMilli())).toList();
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
                java.util.concurrent.atomic.AtomicReference<RuntimeException> durabilityFailure =
                        new java.util.concurrent.atomic.AtomicReference<>();
                AutoRecommender.AutoResult result = auto.run(finalReq,
                        destinationBuyingPower, held, worldParam(world), contextFactory,
                        progress -> {
                            if (durabilityFailure.get() != null || stream.isClosed()) return;
                            try {
                                retainScoutedPick(progress.pick(), owner, worldParam(world));
                                stream.write(new ScoutStreamFrame(
                                        "progress", progress, null, null));
                            } catch (RuntimeException failure) {
                                durabilityFailure.compareAndSet(null, failure);
                            }
                        }, () -> stream.isClosed() || durabilityFailure.get() != null);
                if (stream.isClosed()) return;
                if (durabilityFailure.get() != null) throw durabilityFailure.get();
                requireScoutedPackages(result, owner, worldParam(world));
                stream.write(new ScoutStreamFrame("complete", null, result, null));
            } catch (RuntimeException failure) {
                if (stream.isClosed()) return;
                log.warn("Progressive Universe Scout failed after its response began");
                log.debug("Progressive Universe Scout failure", failure);
                stream.write(new ScoutStreamFrame("error", null, null,
                        failure.getMessage() == null
                                ? "The opportunity scan could not finish."
                                : failure.getMessage()));
            }
            return;
        }
        AutoRecommender.AutoResult result = auto.run(finalReq, destinationBuyingPower,
                held, worldParam(world), contextFactory, null);
        requireScoutedPackages(result, owner, worldParam(world));
        ctx.json(result);
    }

    /**
     * Audit §8.2: every row the Scout surfaced is retained as its own immutable evaluation, in the
     * market mode that priced it, so clicking that row later opens THAT package. Retention is
     * a precondition of delivery: a row cannot claim an exact Analyze action until that exact
     * package can be reloaded. No row is ever re-priced or re-ranked on the way in.
     */
    private void requireScoutedPackages(AutoRecommender.AutoResult result,
                                         String owner, String world) {
        evaluations.persist(new EvaluationService.PersistenceRequest(
                AutoRecommender.surfaced(result.picks()), owner, world));
    }

    /** Progressive rows earn their Analyze action one pick at a time, before they leave the server. */
    private void retainScoutedPick(AutoRecommender.Pick pick, String owner, String world) {
        if (pick != null && pick.horizons() != null && !pick.horizons().isEmpty()) {
            evaluations.persist(new EvaluationService.PersistenceRequest(
                    AutoRecommender.surfaced(List.of(pick)), owner, world));
        }
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

        private synchronized boolean isClosed() {
            return closed;
        }
    }

    private RedeploymentFrontier.UniverseScope universeScope(List<String> requested, String world,
                                                              String owner) {
        if (requested == null || requested.isEmpty()) {
            if (!"observed".equals(world)) {
                var symbols = MarketUniverseView.symbolsForWorld(market, universe, world);
                if (symbols.isEmpty()) {
                    throw new DataUnavailableException(
                            "The active market has no symbols available to scan.");
                }
                boolean demo = "demo".equals(world);
                return new RedeploymentFrontier.UniverseScope(
                        demo ? "DEMO" : "SIMULATED_WORLD",
                        demo ? "Built-in demo market" : "Current simulated market", symbols);
            }
            UniverseService.Active active = universe.active();
            return new RedeploymentFrontier.UniverseScope(active.source().toUpperCase(Locale.ROOT),
                    active.sectorLabel(), active.symbols());
        }
        List<String> normalized = Symbol.list(requested);
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
                .filter(java.util.Objects::nonNull).map(Symbol::normalize).distinct().toList();
        List<RedeploymentFrontier.BookAccountContext> modes = new java.util.ArrayList<>();
        TradeService.DollarDeltaBook practiceDelta = trades.portfolioDollarDeltaBook(practice.id(), null);
        Map<String, io.liftandshift.strikebench.eval.PortfolioExposureContext> practiceExposures =
                new LinkedHashMap<>();
        for (String symbol : symbols) {
            practiceExposures.put(symbol, practiceDelta.focus(symbol).toContext(
                    io.liftandshift.strikebench.position.PositionDomain.BookType.PRACTICE));
        }
        modes.add(new RedeploymentFrontier.BookAccountContext("PRACTICE", practice.id(), practice.name(),
                practiceExposures, null, null, practice.reservedCents(), "SYSTEM_CALCULATED"));

        if (includeTracked) {
            BookRiskService.BookRiskSummary riskSummary = bookRisk.trackedSummary(owner);
            for (BookRiskService.AccountRisk risk : riskSummary.accounts()) {
                PortfolioAccountingService.PortfolioSummary summary =
                        portfolioBooks.summary(owner, risk.accountId());
                PortfolioAccountingService.DollarDeltaBook delta =
                        portfolioBooks.portfolioDollarDeltaBook(owner, risk.accountId());
                Map<String, io.liftandshift.strikebench.eval.PortfolioExposureContext> exposures =
                        new LinkedHashMap<>();
                for (String symbol : symbols) {
                    exposures.put(symbol, delta.focus(symbol).toContext(
                            io.liftandshift.strikebench.position.PositionDomain.BookType.TRACKED));
                }
                AccountObjectiveService.Revision revision = accountObjectives.latest(owner, risk.accountId());
                AccountObjectiveService.AccountCapacityPolicy policy = revision == null
                        ? AccountObjectiveService.AccountCapacityPolicy.empty() : revision.capacityPolicy();
                var reportedReserve = summary.liquidity().recordedOrReportedReserve();
                Long encumbrance = reportedReserve.cents() != null ? reportedReserve.cents()
                        : summary.collateral().knownBlockedCashCents();
                String authority = reportedReserve.cents() != null
                        ? reportedReserve.authority().name() : "MODEL_DERIVED";
                modes.add(new RedeploymentFrontier.BookAccountContext("TRACKED", risk.accountId(), risk.name(),
                        exposures, risk, policy, encumbrance, authority));
            }
        }
        RedeploymentFrontier.RedeploymentSource source = resolved == null ? null
                : new RedeploymentFrontier.RedeploymentSource(resolved.analysisId(), resolved.accountId(),
                resolved.symbol(), resolved.action(), resolved.quantity(),
                resolved.executableCloseCostCents(), resolved.capitalReleasedCents(),
                resolved.closingPnlCents(), resolved.postActionBook(), resolved.basisEffect(),
                resolved.authority(), resolved.basis());
        return new RedeploymentFrontier.Context(scope, destination, modes, source);
    }

}
