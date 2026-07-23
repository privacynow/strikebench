package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.eval.StrategyEvaluation;
import io.liftandshift.strikebench.strategy.StrategyIntent;
import io.liftandshift.strikebench.util.Money;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The auto-scout: scans a universe of optionable symbols, derives a thesis per symbol from
 * price action + news sentiment + IV-vs-HV (SignalEngine), picks the most interesting names,
 * and asks the RecommendationEngine for defined-risk structures per requested horizon
 * (0DTE / week / month). Every structure is ranked by the shared DecisionPolicy and annotated
 * against the user's profit target and max-loss/risk budget. Educational output only — every
 * pick carries its evidence.
 */
public final class AutoRecommender {

    public static final String DISCLAIMER = RecommendationEngine.DISCLAIMER + " "
            + "The auto-scout's signals are simple, auditable heuristics (price momentum, keyword news "
            + "sentiment, IV vs realized volatility) — they are teaching aids, not predictions.";

    private static final int DEFAULT_MAX_PICKS = 3;
    private static final int CANDIDATES_PER_HORIZON = 2;
    private static final double MIN_SIGNAL_CONFIDENCE = 0.2;

    public record AutoRequest(
            List<String> universe,          // optional; derives from the active market scope
            List<String> horizons,          // required subset of 0DTE|week|month|quarter
            Integer maxPicks,               // default 3
            Long targetProfitCents,         // optional profit goal for the whole position
            Long maxLossCents,              // optional absolute per-trade risk cap
            Double maxRiskPctOfAccount,     // optional refinement within the required risk mode
            Double minConfidence,           // optional floor on signal confidence 0..1
            String riskMode,                // conservative|balanced|aggressive
            Boolean allow0dte,
            List<String> intents,           // required StrategyIntent names
            RecommendationEngine.Filters filters, // optional hard screens per candidate
            String thesisOverride,          // optional Plan-owned thesis for focused single-view scans
            String destinationAccountId,    // optional tracked destination; null = active Practice account
            RedeploymentRequest redeployment // optional frozen lifecycle close action
    ) {
        /** Compatibility shape retained for every pre-frontier caller. */
        public AutoRequest(List<String> universe, List<String> horizons, Integer maxPicks,
                           Long targetProfitCents, Long maxLossCents, Double maxRiskPctOfAccount,
                           Double minConfidence, String riskMode, Boolean allow0dte,
                           List<String> intents, RecommendationEngine.Filters filters,
                           String thesisOverride) {
            this(universe, horizons, maxPicks, targetProfitCents, maxLossCents,
                    maxRiskPctOfAccount, minConfidence, riskMode, allow0dte, intents,
                    filters, thesisOverride, null, null);
        }
    }

    public record RedeploymentRequest(String lifecycleReceiptId, String action, Integer quantity) {}

    /** A held equity position, injected by the API layer for EXIT/HEDGE/INCOME scans. */
    public record HoldingInfo(String symbol, int freeShares, long avgCostCents) {}

    public record ScoredCandidate(String targetFit, StrategyEvaluation evaluation) {
        public ScoredCandidate {
            if (evaluation == null || evaluation.candidate() == null) {
                throw new IllegalArgumentException("a scored candidate requires its exact evaluation");
            }
        }
    }

    public record HorizonIdeas(String horizon, List<ScoredCandidate> candidates, List<String> notes) {}

    /** Goal-aware cross-symbol score. It ranks the scan, never replaces candidate economics. */
    public record OpportunityContext(
            String goal,
            double score,
            double signalConfidence,
            Double volatilityFit,
            Double liquidity,
            double eventAdjustment,
            String summary,
            SignalEngine.VolatilityEvidence volatilityEvidence,
            SignalEngine.EventEvidence eventEvidence
    ) {}

    /** Compact best-candidate projection for an opportunity lens; the full evaluation remains below. */
    public record BestIdea(
            boolean available,
            String horizon,
            String family,
            String displayName,
            String economicVerdict,
            String placement,
            Double chanceOfProfit,
            Long maxLossCents,
            Long marketImpliedEvAfterCostsCents,
            Long realizedVolEvAfterCostsCents,
            Long realizedVsMarketEvDifferenceCents,
            Long realisticEvLowAfterCostsCents,
            Long realisticEvHighAfterCostsCents,
            String realisticEvBasis,
            boolean observedEvidence,
            String summary
    ) {}

    public record Pick(String symbol, SignalEngine.Signals signals, double opportunityScore,
                       List<HorizonIdeas> horizons, String intent,
                       OpportunityContext opportunity, BestIdea bestIdea) {}

    public record AutoResult(List<Pick> picks, List<String> skipped, List<String> notes,
                             long riskBudgetCents, String disclaimer,
                             List<CompensationView.CompensationEntry> compensation,
                             String compensationBasis,
                             RedeploymentFrontier.Result frontier) {
        /** Pre-compensation-view constructor keeps existing callers' shape. */
        public AutoResult(List<Pick> picks, List<String> skipped, List<String> notes,
                          long riskBudgetCents, String disclaimer) {
            this(picks, skipped, notes, riskBudgetCents, disclaimer, List.of(), null, null);
        }

        /** Compatibility shape for callers that predate the Book-aware frontier. */
        public AutoResult(List<Pick> picks, List<String> skipped, List<String> notes,
                          long riskBudgetCents, String disclaimer,
                          List<CompensationView.CompensationEntry> compensation,
                          String compensationBasis) {
            this(picks, skipped, notes, riskBudgetCents, disclaimer,
                    compensation, compensationBasis, null);
        }
    }

    /**
     * Small progressive projection for the existing Scout request. A progress listener observes
     * canonical work as it completes; it never ranks, prices, or mutates anything itself.
     */
    public record Progress(String phase, int completed, int total, String symbol,
                           Pick pick, String message) {}

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(Progress progress);
    }

    private static final ProgressListener NO_PROGRESS = ignored -> {};

    private final SignalEngine signals;
    private final RecommendationEngine engine;
    private final AppConfig cfg;
    private final Clock clock;
    private final EvaluationService evaluations;

    public AutoRecommender(SignalEngine signals, RecommendationEngine engine, EvaluationService evaluations,
                           AppConfig cfg, Clock clock) {
        this.signals = signals;
        this.engine = engine;
        this.evaluations = java.util.Objects.requireNonNull(evaluations, "evaluations");
        this.cfg = cfg;
        this.clock = clock;
    }

    public AutoResult run(AutoRequest req, long buyingPowerCents) {
        return run(req, buyingPowerCents, List.of());
    }

    public AutoResult run(AutoRequest req, long buyingPowerCents, List<HoldingInfo> holdings) {
        return run(req, buyingPowerCents, holdings, null);
    }

    /** World-aware: a simulated session's scan reads and prices against THAT world. null = observed. */
    public AutoResult run(AutoRequest req, long buyingPowerCents, List<HoldingInfo> holdings, String worldId) {
        return run(req, buyingPowerCents, holdings, worldId, null);
    }

    /**
     * Book-aware variant. Candidate generation/evaluation remains unchanged; the optional context
     * only composes the separate redeployment frontier after the canonical evaluations exist.
     */
    public AutoResult run(AutoRequest req, long buyingPowerCents, List<HoldingInfo> holdings,
                          String worldId, RedeploymentFrontier.Context frontierContext) {
        return runInternal(req, buyingPowerCents, holdings, worldId,
                frontierContext == null ? null : ignored -> frontierContext, NO_PROGRESS);
    }

    /** Builds Book context only after the surfaced symbols are known, avoiding broad repeated marks. */
    public AutoResult runWithFrontier(AutoRequest req, long buyingPowerCents,
                                      List<HoldingInfo> holdings, String worldId,
                                      java.util.function.Function<List<StrategyEvaluation>,
                                              RedeploymentFrontier.Context> contextFactory) {
        return runWithFrontier(req, buyingPowerCents, holdings, worldId, contextFactory, NO_PROGRESS);
    }

    /** Same canonical scan, with optional delivery of partial progress over the caller's transport. */
    public AutoResult runWithFrontier(AutoRequest req, long buyingPowerCents,
                                      List<HoldingInfo> holdings, String worldId,
                                      java.util.function.Function<List<StrategyEvaluation>,
                                              RedeploymentFrontier.Context> contextFactory,
                                      ProgressListener progressListener) {
        if (contextFactory == null) throw new IllegalArgumentException("frontier context factory is required");
        return runInternal(req, buyingPowerCents, holdings, worldId, contextFactory,
                progressListener == null ? NO_PROGRESS : progressListener);
    }

    private AutoResult runInternal(AutoRequest req, long buyingPowerCents,
                                   List<HoldingInfo> holdings, String worldId,
                                   java.util.function.Function<List<StrategyEvaluation>,
                                           RedeploymentFrontier.Context> contextFactory,
                                   ProgressListener progressListener) {
        DecisionDeclarationPolicy.requireScout("Universe Scout", req);
        boolean allow0dte = Boolean.TRUE.equals(req.allow0dte());
        List<String> universe = req.universe() != null && !req.universe().isEmpty()
                ? req.universe().stream().map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isBlank()).distinct().toList()
                : cfg.autoUniverse();
        List<String> horizons = normalizeHorizons(req.horizons(), allow0dte);
        int maxPicks = req.maxPicks() == null ? DEFAULT_MAX_PICKS : Math.clamp(req.maxPicks(), 1, 10);
        double minConfidence = req.minConfidence() == null ? MIN_SIGNAL_CONFIDENCE : Math.clamp(req.minConfidence(), 0, 1);
        List<StrategyIntent> intents = normalizeIntents(req.intents());
        emit(progressListener, new Progress("STARTING", 0, universe.size(), null, null,
                "Preparing the governed universe and declared goal."));

        List<String> skipped = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (!allow0dte && req.horizons() != null
                && req.horizons().stream().anyMatch(h -> h != null && h.equalsIgnoreCase("0dte"))) {
            notes.add("0DTE was requested but allow0dte is off — enable it to see same-day ideas");
        }

        // 1. Signals for the whole universe, scanned concurrently — live providers are
        // network-bound and per-symbol independent (the service layer is thread-safe).
        List<SignalEngine.Signals> eligibleSignals = new ArrayList<>();
        java.util.Map<String, SignalEngine.Signals> bySymbol = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicInteger signalCompleted = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (String symbol : universe) {
                futures.add(executor.submit(() -> {
                    SignalEngine.Signals analyzed = null;
                    try {
                        analyzed = signals.analyze(symbol, worldId).orElse(null);
                        if (analyzed != null) bySymbol.put(symbol, analyzed);
                    } finally {
                        int completed = signalCompleted.incrementAndGet();
                        Pick preview = null;
                        if (analyzed != null && analyzed.optionable()
                                && analyzed.confidence() >= minConfidence) {
                            StrategyIntent primaryIntent = intents.get(0);
                            OpportunityContext opportunity =
                                    opportunityContext(analyzed, primaryIntent);
                            preview = new Pick(analyzed.symbol(), analyzed,
                                    opportunity.score(), List.of(), primaryIntent.name(),
                                    opportunity, null);
                        }
                        emit(progressListener, new Progress("SIGNALS", completed, universe.size(),
                                symbol, preview, preview == null
                                ? "Reading price, volatility, event, and liquidity evidence."
                                : "Evidence is ready; exact package pricing follows after the field is ranked."));
                    }
                }));
            }
            for (var f : futures) {
                try { f.get(); } catch (Exception e) { /* per-symbol failure -> treated as no data */ }
            }
        }
        for (String symbol : universe) {
            SignalEngine.Signals s = bySymbol.get(symbol);
            if (s == null) { skipped.add(symbol + ": no market data"); continue; }
            if (!s.optionable()) { skipped.add(symbol + ": no listed options"); continue; }
            if (s.confidence() < minConfidence) {
                skipped.add(symbol + String.format(": signal confidence %.2f below %.2f", s.confidence(), minConfidence));
                continue;
            }
            eligibleSignals.add(s);
        }

        long[] riskBudget = {0};
        List<Pick> picks = new ArrayList<>();
        java.util.Map<String, HoldingInfo> heldBySymbol = new java.util.HashMap<>();
        for (HoldingInfo h : holdings) heldBySymbol.put(h.symbol().toUpperCase(Locale.ROOT), h);
        java.util.concurrent.atomic.AtomicInteger ideasCompleted = new java.util.concurrent.atomic.AtomicInteger();
        int ideasTotal = Math.max(1, maxPicks * intents.size());

        for (StrategyIntent intent : intents) {
            record GoalScored(SignalEngine.Signals signals, OpportunityContext opportunity) {}
            List<GoalScored> rankedForGoal = eligibleSignals.stream()
                    .map(signal -> new GoalScored(signal, opportunityContext(signal, intent)))
                    .sorted(Comparator.comparingDouble(
                                    (GoalScored row) -> row.opportunity().score()).reversed()
                            .thenComparing(row -> row.signals().symbol()))
                    .toList();
            if (intent == StrategyIntent.EXIT || intent == StrategyIntent.HEDGE) {
                // Hold-based intents scan YOUR SHARES, not the universe: the question is
                // "which holding should I harvest or protect", not "which ticker looks good".
                List<HoldingInfo> eligible = holdings.stream()
                        .filter(h -> h.freeShares() >= 100)
                        .limit(maxPicks).toList();
                if (eligible.isEmpty()) {
                    notes.add(intent == StrategyIntent.EXIT
                            ? "Nothing to sell at a target: you hold no free 100-share lots — buy shares first"
                            : "Nothing to protect: you hold no free 100-share lots");
                    continue;
                }
                for (HoldingInfo h : eligible) {
                    String sym = h.symbol().toUpperCase(Locale.ROOT);
                    SignalEngine.Signals s = bySymbol.get(sym);
                    if (s == null) s = signals.analyze(sym, worldId).orElse(null);
                    if (s == null || !s.optionable()) {
                        skipped.add(sym + ": held, but no listed options to write against");
                        continue;
                    }
                    RecommendationEngine.Holdings ctx = new RecommendationEngine.Holdings(
                            h.freeShares(), h.avgCostCents(), null);
                    List<HorizonIdeas> perHorizon = horizonIdeas(s, horizons, allow0dte, req, intent, ctx,
                            buyingPowerCents, riskBudget, worldId);
                    OpportunityContext opportunity = opportunityContext(s, intent);
                    Pick pick = new Pick(sym, s, opportunity.score(), perHorizon, intent.name(),
                            opportunity, bestIdea(perHorizon));
                    picks.add(pick);
                    emit(progressListener, new Progress("IDEAS", ideasCompleted.incrementAndGet(),
                            ideasTotal, sym, pick,
                            "A canonical candidate field is ready; destination-Book gates are still composing."));
                }
                continue;
            }
            for (GoalScored top : rankedForGoal.subList(0, Math.min(maxPicks, rankedForGoal.size()))) {
                SignalEngine.Signals s = top.signals();
                HoldingInfo held = heldBySymbol.get(s.symbol().toUpperCase(Locale.ROOT));
                // ACQUIRE never inherits the existing position: sharesOwned means "shares I want"
                // there and defaults to one lot — owned shares must not scale new purchases.
                RecommendationEngine.Holdings ctx = intent != StrategyIntent.DIRECTIONAL
                        && intent != StrategyIntent.ACQUIRE && held != null
                        ? new RecommendationEngine.Holdings(held.freeShares(), held.avgCostCents(), null)
                        : null;
                List<HorizonIdeas> perHorizon = horizonIdeas(s, horizons, allow0dte, req, intent, ctx,
                        buyingPowerCents, riskBudget, worldId);
                Pick pick = new Pick(s.symbol(), s, top.opportunity().score(), perHorizon, intent.name(),
                        top.opportunity(), bestIdea(perHorizon));
                picks.add(pick);
                emit(progressListener, new Progress("IDEAS", ideasCompleted.incrementAndGet(),
                        ideasTotal, s.symbol(), pick,
                        "A canonical candidate field is ready; destination-Book gates are still composing."));
            }
        }

        if (picks.isEmpty()) {
            notes.add("Nothing in the universe passed the signal and risk screens — widen the universe or lower the confidence floor");
        }
        if (req.targetProfitCents() != null && req.targetProfitCents() > 0) {
            notes.add("Profit targets are aspirations, not predictions: a structure whose max profit covers the target still has to be right");
        }
        // The compensation view ranks every premium-collecting idea the Scout surfaced,
        // across picks and horizons, BESIDE the per-pick decision ordering (Phase 10.3).
        List<StrategyEvaluation> surfaced = picks.stream()
                .flatMap(pick -> pick.horizons().stream())
                .flatMap(h -> h.candidates().stream())
                .map(ScoredCandidate::evaluation)
                .toList();
        List<CompensationView.CompensationEntry> compensation =
                CompensationView.compute(surfaced, evaluations, worldId);
        emit(progressListener, new Progress("BOOK", ideasCompleted.get(), ideasTotal, null, null,
                "Applying destination-Book capacity, concentration, and expiry checks."));
        RedeploymentFrontier.Result frontier = contextFactory == null ? null
                : RedeploymentFrontier.compose(surfaced, compensation, contextFactory.apply(surfaced));
        return new AutoResult(picks, skipped, notes, riskBudget[0], DISCLAIMER,
                compensation, CompensationView.BASIS, frontier);
    }

    private static void emit(ProgressListener listener, Progress progress) {
        try {
            listener.onProgress(progress);
        } catch (RuntimeException ignored) {
            // Delivery is observational. A closed browser stream must never change the scan result.
        }
    }

    /** Runs the engine per horizon for one symbol under one intent. */
    private List<HorizonIdeas> horizonIdeas(SignalEngine.Signals s, List<String> horizons, boolean allow0dte,
                                            AutoRequest req, StrategyIntent intent,
                                            RecommendationEngine.Holdings holdingsCtx,
                                            long buyingPowerCents, long[] riskBudget, String worldId) {
        List<HorizonIdeas> perHorizon = new ArrayList<>();
        for (String horizon : horizons) {
            if ("0DTE".equals(horizon) && !allow0dte) continue;
            // An explicitly declared view is part of the same Scout/Idea control contract and must
            // narrow every goal's compatible families. Only an undeclared directional scan derives
            // a view from the signal engine; other undeclared goals remain purpose-only.
            String thesis = req.thesisOverride() != null && !req.thesisOverride().isBlank()
                    ? req.thesisOverride()
                    : intent == StrategyIntent.DIRECTIONAL ? s.thesis() : null;
            RecommendationEngine.Result result = engine.recommend(new RecommendationEngine.Request(
                    s.symbol(), thesis, horizon, req.riskMode(),
                    req.maxLossCents(), req.maxRiskPctOfAccount(), null, null,
                    true, "0DTE".equals(horizon),
                    intent.name(), holdingsCtx, req.filters()), buyingPowerCents, worldId);
            riskBudget[0] = result.riskBudgetCents();

            List<String> hNotes = new ArrayList<>();
            List<Candidate> pool = result.candidates();
            if ("0DTE".equals(horizon)) {
                LocalDate today = worldId == null
                        ? LocalDate.now(clock)
                        : engine.marketDate(worldId);
                pool = pool.stream().filter(c -> expiresOn(c, today)).toList();
                if (pool.isEmpty()) {
                    hNotes.add("No same-day expiration listed for " + s.symbol() + " — 0DTE skipped");
                    perHorizon.add(new HorizonIdeas(horizon, List.of(), hNotes));
                    continue;
                }
            }
            if (s.eventRisk() && "RICH".equals(s.volSignal())) {
                hNotes.add("Elevated event risk: rich premium often reflects a coming catalyst — gaps can blow through short strikes");
            }
            List<ScoredCandidate> assessed;
            if (!pool.isEmpty()) {
                List<StrategyEvaluation> evaluated = evaluations.evaluate(s.symbol(), intent.name(), thesis, horizon,
                        req.riskMode(), pool, buyingPowerCents, null, false,
                        io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, worldId, null);
                List<StrategyEvaluation> evals = io.liftandshift.strikebench.eval.StrategyEvaluator
                        .bestPackagePerFamily(evaluated);
                assessed = evals.stream().map(e -> new ScoredCandidate(
                                targetFit(e.candidate(), req.targetProfitCents()), e))
                        .sorted(Comparator.comparingDouble(
                                (ScoredCandidate candidate) -> candidate.evaluation().decisionScore()).reversed())
                        .toList();
            } else {
                assessed = List.of();
            }
            boolean anyFavorable = assessed.stream().anyMatch(x -> economics(x) != null
                    && economics(x).verdict() == EconomicAssessment.Verdict.FAVORABLE);
            if (!anyFavorable && !assessed.isEmpty()) {
                hNotes.add(noFavorableNote(assessed, worldId == null));
            }
            // Scout is a curated surface rather than the full catalog. Preserve at least one
            // unfavorable counterexample when present so it teaches why the stronger ideas rank
            // ahead, without flooding the scan with every family (manual Ideas still exposes all).
            List<ScoredCandidate> selected = new ArrayList<>(assessed.stream()
                    .filter(x -> economics(x) == null || !economics(x).teachingCase())
                    .limit(CANDIDATES_PER_HORIZON).toList());
            assessed.stream().filter(x -> economics(x) != null && economics(x).teachingCase())
                    .findFirst().filter(x -> selected.size() < CANDIDATES_PER_HORIZON + 1).ifPresent(selected::add);
            List<ScoredCandidate> ranked = List.copyOf(selected);
            if (ranked.isEmpty()) hNotes.addAll(result.notes());
            // The UI promises that screened-out ideas are called out with their reason —
            // honor that on the scout too, not only on the manual tab (capped for compactness).
            if (req.filters() != null || ranked.isEmpty()) {
                result.rejected().stream().limit(3).forEach(r ->
                        hNotes.add("Refused: " + r.displayName() + " — "
                                + (r.reasons().isEmpty() ? "did not pass the screens" : r.reasons().getFirst())));
            }
            perHorizon.add(new HorizonIdeas(horizon, ranked, hNotes));
        }
        return perHorizon;
    }

    static String noFavorableNote(List<ScoredCandidate> assessed, boolean observedLane) {
        boolean hasAssessment = assessed.stream().anyMatch(x -> economics(x) != null);
        boolean hasComparableAssessment = assessed.stream().anyMatch(x -> economics(x) != null
                && !"MECHANICALLY_INELIGIBLE".equals(economics(x).placement()));
        if (hasAssessment && !hasComparableAssessment) {
            return "No structure reached economic comparison because every candidate failed a mechanical or account check. Review each refusal reason; this is not an economic verdict on the market.";
        }
        boolean needsDailyHistory = assessed.stream().anyMatch(x -> economics(x) != null
                && economics(x).needsDailyHistory());
        if (hasAssessment && needsDailyHistory) {
            return observedLane
                    ? "A favorable observed verdict cannot be formed yet: this market has fewer than "
                      + io.liftandshift.strikebench.pricing.HistoricalVol.MIN_OBSERVATIONS
                      + " eligible daily closes, so realized-volatility EV is unavailable. These structures remain useful for mechanics and market-implied comparison; add observed daily history in Data → Sources & jobs."
                    : "A favorable verdict cannot be formed yet because this generated market has too little daily path history for realized-volatility EV. These structures remain useful comparisons, not endorsements.";
        }
        return "No favorable setup was found after the available after-cost economic checks. The structures below remain useful comparisons, not endorsements.";
    }

    private static EconomicAssessment economics(ScoredCandidate candidate) {
        return candidate == null || candidate.evaluation() == null || candidate.evaluation().assessment() == null
                ? null : candidate.evaluation().assessment().economics();
    }

    private static List<StrategyIntent> normalizeIntents(List<String> requested) {
        List<StrategyIntent> out = new ArrayList<>();
        for (String raw : requested) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("Universe Scout goal cannot be blank");
            }
            StrategyIntent intent = StrategyIntent.parse(raw); // throws 400-mapped error on unknown
            if (!out.contains(intent)) out.add(intent);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Universe Scout requires an explicit goal");
        return List.copyOf(out);
    }

    /**
     * Cross-symbol opportunity is goal-aware. Rich options help an income scan but do not count as
     * cheap protection; cheap options help a hedge scan but do not masquerade as premium richness.
     * Missing IV or realized volatility receives no volatility credit and is named in the payload.
     */
    private static OpportunityContext opportunityContext(SignalEngine.Signals s, StrategyIntent intent) {
        Double volFit = volatilityFit(s.ivHvRatio(), intent);
        double liquidity = s.liquidityScore() == null ? 0.5 : Math.clamp(s.liquidityScore(), 0, 1);
        double weighted = 0.5 * s.confidence() + 0.2 * liquidity;
        if (volFit != null) {
            weighted += 0.3 * volFit;
        }
        double eventAdjustment = eventAdjustment(s.eventRisk(), intent);
        double score = Math.clamp(weighted + eventAdjustment, 0, 1);
        return new OpportunityContext(intent.name(), round2(score), round2(s.confidence()),
                volFit == null ? null : round2(volFit), round2(liquidity), round2(eventAdjustment),
                goalFitSummary(s, intent, volFit), s.volatilityEvidence(), s.eventEvidence());
    }

    static Double volatilityFit(Double ivHvRatio, StrategyIntent intent) {
        if (ivHvRatio == null || !Double.isFinite(ivHvRatio) || ivHvRatio <= 0) return null;
        double log2 = Math.log(ivHvRatio) / Math.log(2);
        return switch (intent) {
            case INCOME, ACQUIRE, EXIT -> Math.clamp(log2, 0, 1);
            case HEDGE -> Math.clamp(-log2, 0, 1);
            case DIRECTIONAL -> Math.clamp(Math.abs(log2), 0, 1);
        };
    }

    private static double eventAdjustment(boolean eventRisk, StrategyIntent intent) {
        if (!eventRisk) return 0.0;
        return switch (intent) {
            case INCOME, ACQUIRE, EXIT -> -0.12;
            case HEDGE -> 0.08;
            case DIRECTIONAL -> 0.0;
        };
    }

    private static String goalFitSummary(SignalEngine.Signals s, StrategyIntent intent, Double volFit) {
        if (volFit == null) {
            return "The IV-versus-realized-volatility comparison is unavailable; it receives no ranking credit and the remaining signal and liquidity evidence stays visible.";
        }
        if (s.eventRisk() && (intent == StrategyIntent.INCOME
                || intent == StrategyIntent.ACQUIRE || intent == StrategyIntent.EXIT)) {
            return "Option premium is elevated, but a source-backed event flag may explain it; compare the after-cost edge with the gap tail before collecting premium.";
        }
        return switch (intent) {
            case INCOME -> "Ranks richer option premium against realized movement, then asks the shared evaluator whether any income package clears costs and tail risk.";
            case ACQUIRE -> "Ranks put premium against realized movement for a desired-price entry; assignment and cash collateral remain explicit.";
            case EXIT -> "Ranks call premium against realized movement for held-share exits; assignment is the declared goal, not a failure.";
            case HEDGE -> "Ranks comparatively inexpensive option protection, with event risk increasing—not hiding—the need to inspect the hedge.";
            case DIRECTIONAL -> "Ranks the size of the volatility mismatch alongside direction, evidence confidence, and executable liquidity.";
        };
    }

    private static BestIdea bestIdea(List<HorizonIdeas> horizons) {
        record Located(String horizon, ScoredCandidate scored) {}
        Located best = horizons.stream()
                .flatMap(horizon -> horizon.candidates().stream()
                        .map(scored -> new Located(horizon.horizon(), scored)))
                .max(Comparator.comparingDouble(row -> row.scored().evaluation().decisionScore()))
                .orElse(null);
        if (best == null) {
            return new BestIdea(false, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, false,
                    "No package passed the current market, evidence, and account screens.");
        }
        StrategyEvaluation evaluation = best.scored().evaluation();
        Candidate candidate = evaluation.candidate();
        EconomicAssessment economics = evaluation.assessment() == null
                ? null : evaluation.assessment().economics();
        Long difference = economics == null || economics.marketEvAfterCostsCents() == null
                || economics.realizedVolEvAfterCostsCents() == null ? null
                : economics.realizedVolEvAfterCostsCents() - economics.marketEvAfterCostsCents();
        return new BestIdea(true, best.horizon(), candidate.strategy(), candidate.displayName(),
                economics == null ? "UNAVAILABLE" : economics.verdict().name(),
                economics == null ? null : economics.placement(),
                evaluation.pop(), evaluation.maxLossCents(),
                economics == null ? null : economics.marketEvAfterCostsCents(),
                economics == null ? null : economics.realizedVolEvAfterCostsCents(),
                difference,
                economics == null ? null : economics.realisticEvLowAfterCostsCents(),
                economics == null ? null : economics.realisticEvHighAfterCostsCents(),
                economics == null ? null : economics.realisticEvBasis(),
                economics != null && economics.observedEvidence(),
                economics == null ? "Economic comparison is unavailable for this package."
                        : economics.summary());
    }

    private static String targetFit(Candidate c, Long targetProfitCents) {
        if (targetProfitCents == null || targetProfitCents <= 0) return null;
        if (c.maxProfitCents() == null) {
            return "Uncapped upside — " + Money.fmt(targetProfitCents) + " is possible on a large enough move, never assured";
        }
        if (c.maxProfitCents() >= targetProfitCents) {
            return "Max profit " + Money.fmt(c.maxProfitCents()) + " covers your " + Money.fmt(targetProfitCents) + " target";
        }
        return "Max profit " + Money.fmt(c.maxProfitCents()) + " cannot reach your " + Money.fmt(targetProfitCents)
                + " target at this risk budget";
    }

    private static boolean expiresOn(Candidate c, LocalDate date) {
        return c.legs().stream()
                .filter(l -> l.expiration() != null)
                .allMatch(l -> LocalDate.parse(l.expiration()).equals(date));
    }

    private static List<String> normalizeHorizons(List<String> requested, boolean allow0dte) {
        List<String> out = new ArrayList<>();
        for (String h : requested) {
            String norm = h == null ? "" : h.trim();
            if (norm.equalsIgnoreCase("0dte")) norm = "0DTE";
            else norm = norm.toLowerCase(Locale.ROOT);
            if ((norm.equals("0DTE") || norm.equals("week") || norm.equals("month") || norm.equals("quarter"))
                    && !out.contains(norm)) {
                out.add(norm);
            } else if (norm.matches("[1-9]\\d{0,2}d")
                    && Integer.parseInt(norm.substring(0, norm.length() - 1)) <= 756
                    && !out.contains(norm)) {
                out.add(norm);
            } else if (!norm.isBlank() && !out.contains(norm)) {
                throw new IllegalArgumentException("Unknown Scout horizon '" + h
                        + "' — choose 0DTE, week, month, quarter, or an exact value such as 30d");
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Universe Scout requires an explicit horizon");
        return List.copyOf(out);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
