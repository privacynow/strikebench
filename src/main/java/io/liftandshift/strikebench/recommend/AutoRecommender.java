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
            String thesisOverride           // optional Plan-owned thesis for focused single-view scans
    ) {}

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

    public record Pick(String symbol, SignalEngine.Signals signals, double opportunityScore,
                       List<HorizonIdeas> horizons, String intent) {}

    public record AutoResult(List<Pick> picks, List<String> skipped, List<String> notes,
                             long riskBudgetCents, String disclaimer,
                             List<CompensationView.CompensationEntry> compensation,
                             String compensationBasis) {
        /** Pre-compensation-view constructor keeps existing callers' shape. */
        public AutoResult(List<Pick> picks, List<String> skipped, List<String> notes,
                          long riskBudgetCents, String disclaimer) {
            this(picks, skipped, notes, riskBudgetCents, disclaimer, List.of(), null);
        }
    }

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
        DecisionDeclarationPolicy.requireScout("Universe Scout", req);
        boolean allow0dte = Boolean.TRUE.equals(req.allow0dte());
        List<String> universe = req.universe() != null && !req.universe().isEmpty()
                ? req.universe().stream().map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isBlank()).distinct().toList()
                : cfg.autoUniverse();
        List<String> horizons = normalizeHorizons(req.horizons(), allow0dte);
        int maxPicks = req.maxPicks() == null ? DEFAULT_MAX_PICKS : Math.clamp(req.maxPicks(), 1, 10);
        double minConfidence = req.minConfidence() == null ? MIN_SIGNAL_CONFIDENCE : Math.clamp(req.minConfidence(), 0, 1);

        List<String> skipped = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (!allow0dte && req.horizons() != null
                && req.horizons().stream().anyMatch(h -> h != null && h.equalsIgnoreCase("0dte"))) {
            notes.add("0DTE was requested but allow0dte is off — enable it to see same-day ideas");
        }

        // 1. Signals for the whole universe, scanned concurrently — live providers are
        // network-bound and per-symbol independent (the service layer is thread-safe).
        record Scored(SignalEngine.Signals s, double score) {}
        List<Scored> scored = new ArrayList<>();
        java.util.Map<String, SignalEngine.Signals> bySymbol = new java.util.concurrent.ConcurrentHashMap<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (String symbol : universe) {
                futures.add(executor.submit(() ->
                        signals.analyze(symbol, worldId).ifPresent(s -> bySymbol.put(symbol, s))));
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
            scored.add(new Scored(s, opportunityScore(s)));
        }
        scored.sort(Comparator.comparingDouble((Scored x) -> x.score).reversed());

        long[] riskBudget = {0};
        List<Pick> picks = new ArrayList<>();
        java.util.Map<String, HoldingInfo> heldBySymbol = new java.util.HashMap<>();
        for (HoldingInfo h : holdings) heldBySymbol.put(h.symbol().toUpperCase(Locale.ROOT), h);

        for (StrategyIntent intent : normalizeIntents(req.intents())) {
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
                    picks.add(new Pick(sym, s, round2(opportunityScore(s)), perHorizon, intent.name()));
                }
                continue;
            }
            for (Scored top : scored.subList(0, Math.min(maxPicks, scored.size()))) {
                SignalEngine.Signals s = top.s;
                HoldingInfo held = heldBySymbol.get(s.symbol().toUpperCase(Locale.ROOT));
                // ACQUIRE never inherits the existing position: sharesOwned means "shares I want"
                // there and defaults to one lot — owned shares must not scale new purchases.
                RecommendationEngine.Holdings ctx = intent != StrategyIntent.DIRECTIONAL
                        && intent != StrategyIntent.ACQUIRE && held != null
                        ? new RecommendationEngine.Holdings(held.freeShares(), held.avgCostCents(), null)
                        : null;
                List<HorizonIdeas> perHorizon = horizonIdeas(s, horizons, allow0dte, req, intent, ctx,
                        buyingPowerCents, riskBudget, worldId);
                picks.add(new Pick(s.symbol(), s, round2(top.score), perHorizon, intent.name()));
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
        return new AutoResult(picks, skipped, notes, riskBudget[0], DISCLAIMER,
                CompensationView.compute(surfaced, evaluations, worldId), CompensationView.BASIS);
    }

    /** Runs the engine per horizon for one symbol under one intent. */
    private List<HorizonIdeas> horizonIdeas(SignalEngine.Signals s, List<String> horizons, boolean allow0dte,
                                            AutoRequest req, StrategyIntent intent,
                                            RecommendationEngine.Holdings holdingsCtx,
                                            long buyingPowerCents, long[] riskBudget, String worldId) {
        List<HorizonIdeas> perHorizon = new ArrayList<>();
        for (String horizon : horizons) {
            if ("0DTE".equals(horizon) && !allow0dte) continue;
            // Directional scans ride the derived thesis; intent scans pick families by purpose
            // (an explicit thesis would narrow them, so none is passed).
            String thesis = intent == StrategyIntent.DIRECTIONAL
                    ? (req.thesisOverride() == null || req.thesisOverride().isBlank()
                        ? s.thesis() : req.thesisOverride())
                    : null;
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

    /** How interesting a symbol is to look at, 0..1: signal strength, vol edge, liquidity. */
    private static double opportunityScore(SignalEngine.Signals s) {
        double volEdge = s.ivHvRatio() == null ? 0.3
                : Math.clamp(Math.abs(Math.log(s.ivHvRatio())) / Math.log(2), 0, 1); // 2x or 0.5x saturates
        double liquidity = s.liquidityScore() == null ? 0.5 : s.liquidityScore();
        return 0.5 * s.confidence() + 0.3 * volEdge + 0.2 * liquidity;
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
            } else if (!norm.isBlank() && !out.contains(norm)) {
                throw new IllegalArgumentException("Unknown Scout horizon '" + h
                        + "' — choose 0DTE, week, month, or quarter");
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Universe Scout requires an explicit horizon");
        return List.copyOf(out);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
