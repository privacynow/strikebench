package io.liftandshift.strikebench.recommend;
import static io.liftandshift.strikebench.util.Numbers.round2;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.eval.StrategyEvaluation;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.strategy.StrategyIntent;
import io.liftandshift.strikebench.util.Money;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
            RedeploymentRequest redeployment, // optional frozen lifecycle close action
            Boolean avoidEarnings           // persisted declaration; true excludes event-crossing packages
    ) {
        /** Compatibility shape retained for every pre-frontier caller. */
        public AutoRequest(List<String> universe, List<String> horizons, Integer maxPicks,
                           Long targetProfitCents, Long maxLossCents, Double maxRiskPctOfAccount,
                           Double minConfidence, String riskMode, Boolean allow0dte,
                           List<String> intents, RecommendationEngine.Filters filters,
                           String thesisOverride) {
            this(universe, horizons, maxPicks, targetProfitCents, maxLossCents,
                    maxRiskPctOfAccount, minConfidence, riskMode, allow0dte, intents,
                    filters, thesisOverride, null, null, true);
        }

        public AutoRequest(List<String> universe, List<String> horizons, Integer maxPicks,
                           Long targetProfitCents, Long maxLossCents, Double maxRiskPctOfAccount,
                           Double minConfidence, String riskMode, Boolean allow0dte,
                           List<String> intents, RecommendationEngine.Filters filters,
                           String thesisOverride, String destinationAccountId,
                           RedeploymentRequest redeployment) {
            this(universe, horizons, maxPicks, targetProfitCents, maxLossCents,
                    maxRiskPctOfAccount, minConfidence, riskMode, allow0dte, intents,
                    filters, thesisOverride, destinationAccountId, redeployment, true);
        }

        /** A copy with the risk-capital-capped per-trade budget; every other field unchanged. */
        public AutoRequest withMaxLossCents(Long cappedMaxLossCents) {
            return new AutoRequest(universe, horizons, maxPicks, targetProfitCents, cappedMaxLossCents,
                    maxRiskPctOfAccount, minConfidence, riskMode, allow0dte, intents, filters,
                    thesisOverride, destinationAccountId, redeployment, avoidEarnings);
        }
    }

    public record RedeploymentRequest(String lifecycleAnalysisId, String action, Integer quantity) {}

    /** A held equity position, injected by the API layer for EXIT/HEDGE/INCOME scans. */
    public record HoldingInfo(String symbol, int freeShares, long avgCostCents,
                              String destinationAccountId, String custodyType,
                              Long observedAtEpochMs) {
        public HoldingInfo(String symbol, int freeShares, long avgCostCents) {
            this(symbol, freeShares, avgCostCents, null, null, null);
        }
        public HoldingInfo {
            symbol = Symbol.normalize(symbol);
        }
    }

    public record ScoredCandidate(String targetFit, StrategyEvaluation evaluation,
                                  EventService.EarningsProximity earningsEvent) {
        public ScoredCandidate {
            if (evaluation == null || evaluation.candidate() == null) {
                throw new IllegalArgumentException("a scored candidate requires its exact evaluation");
            }
        }

        /** Compatibility shape for pure ranking fixtures that do not own issuer-event evidence. */
        public ScoredCandidate(String targetFit, StrategyEvaluation evaluation) {
            this(targetFit, evaluation, null);
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
            double newsCatalystAdjustment,
            String summary,
            SignalEngine.VolatilityEvidence volatilityEvidence,
            SignalEngine.NewsCatalystEvidence newsCatalystEvidence
    ) {}

    /** Compact best-candidate projection for an opportunity lens; the full evaluation remains below. */
    /**
     * The row a Scout result shows. {@code evaluationId} and {@code resultKey} name the EXACT
     * evaluation behind it, so clicking the row adopts that package rather than opening a freshly
     * recomputed field in its place (audit §8.2). Without them the row is a picture of a package
     * with no way back to the package itself.
     */
    public record BestIdea(
            boolean available,
            String evaluationId,
            String resultKey,
            String horizon,
            String family,
            String displayName,
            String economicVerdict,
            String endorsementStatus,
            EventService.EarningsProximity earningsEvent,
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
                             RedeploymentFrontier.Result frontier,
                             ScanCounts counts) {
        /** Pre-compensation-view constructor keeps existing callers' shape. */
        public AutoResult(List<Pick> picks, List<String> skipped, List<String> notes,
                          long riskBudgetCents, String disclaimer) {
            this(picks, skipped, notes, riskBudgetCents, disclaimer, List.of(), null, null,
                    ScanCounts.NONE);
        }

        /** Compatibility shape for callers that predate the Book-aware frontier. */
        public AutoResult(List<Pick> picks, List<String> skipped, List<String> notes,
                          long riskBudgetCents, String disclaimer,
                          List<CompensationView.CompensationEntry> compensation,
                          String compensationBasis) {
            this(picks, skipped, notes, riskBudgetCents, disclaimer,
                    compensation, compensationBasis, null, ScanCounts.NONE);
        }
    }

    /**
     * The four things a scan actually finishes, each with its OWN denominator (audit §8.2).
     *
     * <p>One {@code completed/total} pair cannot describe this work: the denominator used to change
     * meaning between phases — 5/105 symbols became 3/6 ideas — so a reader could never tell what
     * had finished. These four counts only ever rise, never share a denominator, and never restate
     * each other:</p>
     *
     * <ul>
     *   <li>{@code universeConsidered} — symbols whose evidence read completed;</li>
     *   <li>{@code evidenceEligible} — of those, the ones with enough evidence to price;</li>
     *   <li>{@code packagesEvaluated} — exact packages the evaluator priced and scored;</li>
     *   <li>{@code rowsRetained} — distinct result rows kept, by {@link ResultIdentity}.</li>
     * </ul>
     */
    public record ScanCounts(int universeConsidered, int evidenceEligible,
                             int packagesEvaluated, int rowsRetained) {
        public static final ScanCounts NONE = new ScanCounts(0, 0, 0, 0);

        public ScanCounts {
            if (universeConsidered < 0 || evidenceEligible < 0
                    || packagesEvaluated < 0 || rowsRetained < 0) {
                throw new IllegalArgumentException("scan counts cannot be negative");
            }
        }
    }

    /**
     * Small progressive projection for the existing Scout request. A progress listener observes
     * normalized work as it completes; it never ranks, prices, or mutates anything itself.
     *
     * <p>{@code phaseCompleted}/{@code phaseTotal} describe ONLY the named phase in flight. The
     * durable, comparable quantities live in {@link ScanCounts}, which every frame carries.</p>
     */
    public record Progress(String phase, int phaseCompleted, int phaseTotal, ScanCounts counts,
                           String symbol, Pick pick, String message) {}

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(Progress progress);
    }

    private static final ProgressListener NO_PROGRESS = ignored -> {};

    private final SignalEngine signals;
    private final RecommendationEngine engine;
    private final AppConfig cfg;
    private final EvaluationService evaluations;
    private final OpportunityScanKernel scanKernel;

    public AutoRecommender(SignalEngine signals, RecommendationEngine engine, EvaluationService evaluations,
                           AppConfig cfg) {
        this(signals, engine, evaluations, cfg, new OpportunityScanKernel());
    }

    public AutoRecommender(SignalEngine signals, RecommendationEngine engine, EvaluationService evaluations,
                           AppConfig cfg, OpportunityScanKernel scanKernel) {
        this.signals = signals;
        this.engine = engine;
        this.evaluations = java.util.Objects.requireNonNull(evaluations, "evaluations");
        this.cfg = cfg;
        this.scanKernel = java.util.Objects.requireNonNull(scanKernel, "scanKernel");
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
     * only composes the separate redeployment frontier after the normalized evaluations exist.
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

    /** Same normalized scan, with optional delivery of partial progress over the caller's transport. */
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
        List<String> horizons = normalizeHorizons(req.horizons(), allow0dte);
        int maxPicks = req.maxPicks() == null ? DEFAULT_MAX_PICKS : Math.clamp(req.maxPicks(), 1, 10);
        double minConfidence = req.minConfidence() == null ? MIN_SIGNAL_CONFIDENCE : Math.clamp(req.minConfidence(), 0, 1);
        List<StrategyIntent> intents = normalizeIntents(req.intents());
        boolean incomeExactField = intents.contains(StrategyIntent.INCOME);
        List<HoldingInfo> heldPositions = holdings == null ? List.of() : holdings;
        boolean scansMarketField = intents.stream()
                .anyMatch(intent -> intent != StrategyIntent.EXIT && intent != StrategyIntent.HEDGE);
        boolean scansHeldField = intents.stream()
                .anyMatch(intent -> intent == StrategyIntent.EXIT || intent == StrategyIntent.HEDGE);
        List<String> traversalSymbols = new ArrayList<>();
        if (scansMarketField) {
            traversalSymbols.addAll(req.universe() != null && !req.universe().isEmpty()
                    ? req.universe() : cfg.autoUniverse());
        }
        if (scansHeldField) {
            heldPositions.stream().filter(holding -> holding.freeShares() >= 100)
                    .map(HoldingInfo::symbol).forEach(traversalSymbols::add);
        }
        OpportunityScanKernel.Universe scanUniverse = scanKernel.prepare(traversalSymbols);
        List<String> universe = scanUniverse.symbols();
        ScanTally tally = new ScanTally();
        ProgressEmitter progress = new ProgressEmitter(progressListener);
        progress.emit(new Progress("STARTING", 0, universe.size(), tally.counts(), null, null,
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
        OpportunityScanKernel.Traversal<SignalEngine.Signals> signalTraversal =
                scanKernel.traverse(scanUniverse, OpportunityScanKernel.Policy.EVIDENCE_FIELD,
                        symbol -> signals.analyze(symbol, worldId).orElse(null),
                        completion -> {
                            SignalEngine.Signals analyzed = completion.value();
                            Pick preview = null;
                            if (analyzed != null && analyzed.optionable()
                                    && (incomeExactField || analyzed.confidence() >= minConfidence)) {
                                StrategyIntent primaryIntent = intents.get(0);
                                OpportunityContext opportunity =
                                        opportunityContext(analyzed, primaryIntent);
                                preview = new Pick(analyzed.symbol(), analyzed,
                                        opportunity.score(), List.of(), primaryIntent.name(),
                                        opportunity, null);
                            }
                            Pick completedPreview = preview;
                            // The kernel serializes completions from parallel symbol work. The
                            // eligibility count uses the same predicate as the screen below.
                            progress.updateAndEmit(() -> {
                                tally.considered(completion.completed());
                                if (completedPreview != null) tally.evidenceEligible();
                            }, () -> new Progress("SIGNALS", completion.completed(),
                                    completion.total(), tally.counts(), completion.symbol(),
                                    completedPreview, completedPreview == null
                                        ? "Reading price, volatility, event, and liquidity evidence."
                                        : "Evidence is ready; exact package pricing follows after the field is ranked."));
                        });
        java.util.Map<String, SignalEngine.Signals> bySymbol = new java.util.LinkedHashMap<>();
        for (OpportunityScanKernel.Item<SignalEngine.Signals> item : signalTraversal.items()) {
            if (item.succeeded() && item.value() != null) {
                bySymbol.put(item.symbol(), item.value());
            }
        }
        for (String symbol : universe) {
            SignalEngine.Signals s = bySymbol.get(symbol);
            if (s == null) { skipped.add(symbol + ": no market data"); continue; }
            if (!s.optionable()) { skipped.add(symbol + ": no listed options"); continue; }
            if (!incomeExactField && s.confidence() < minConfidence) {
                skipped.add(symbol + String.format(": signal confidence %.2f below %.2f", s.confidence(), minConfidence));
                continue;
            }
            eligibleSignals.add(s);
        }

        java.util.concurrent.atomic.AtomicLong riskBudget =
                new java.util.concurrent.atomic.AtomicLong();
        List<Pick> picks = new ArrayList<>();
        java.util.Map<String, HoldingInfo> heldBySymbol = new java.util.HashMap<>();
        for (HoldingInfo h : heldPositions) heldBySymbol.put(Symbol.normalize(h.symbol()), h);
        java.util.concurrent.atomic.AtomicInteger ideasCompleted = new java.util.concurrent.atomic.AtomicInteger();
        record GoalScored(SignalEngine.Signals signals, OpportunityContext opportunity) {}
        java.util.Map<StrategyIntent, List<GoalScored>> marketWork = new java.util.LinkedHashMap<>();
        java.util.Map<StrategyIntent, List<HoldingInfo>> heldWork = new java.util.LinkedHashMap<>();
        int plannedIdeas = 0;
        for (StrategyIntent intent : intents) {
            if (intent == StrategyIntent.EXIT || intent == StrategyIntent.HEDGE) {
                // Hold-based intents scan YOUR SHARES, not the universe: the question is
                // "which holding should I harvest or protect", not "which ticker looks good".
                List<HoldingInfo> eligible = heldPositions.stream()
                        .filter(h -> h.freeShares() >= 100)
                        .filter(h -> {
                            SignalEngine.Signals signal = bySymbol.get(Symbol.normalize(h.symbol()));
                            return signal != null && signal.optionable();
                        }).toList();
                if (eligible.isEmpty()) {
                    boolean hasFreeLot = heldPositions.stream().anyMatch(h -> h.freeShares() >= 100);
                    notes.add(hasFreeLot
                            ? "No held 100-share lot has enough current option evidence for "
                                    + intent.name().toLowerCase(java.util.Locale.ROOT) + " package pricing"
                            : intent == StrategyIntent.EXIT
                                    ? "Nothing to sell at a target: you hold no free 100-share lots — buy shares first"
                                    : "Nothing to protect: you hold no free 100-share lots");
                }
                heldWork.put(intent, eligible);
                plannedIdeas += eligible.size();
            } else {
                // A broad field scan must exact-price the governed field, not merely the first
                // few names that a price/news heuristic likes. The presentation can still show a
                // compact frontier after evaluation; maxPicks may not
                // prevent a lower-signal name with superior package economics or compensation
                // from ever reaching the normalized evaluator.
                List<GoalScored> rankedForGoal = eligibleSignals.stream()
                        .map(signal -> new GoalScored(signal, opportunityContext(signal, intent)))
                        .sorted(Comparator.comparingDouble(
                                        (GoalScored row) -> row.opportunity().score()).reversed()
                                .thenComparing(row -> row.signals().symbol()))
                        .toList();
                marketWork.put(intent, rankedForGoal);
                plannedIdeas += rankedForGoal.size();
            }
        }
        int ideasTotal = plannedIdeas;

        for (StrategyIntent intent : intents) {
            if (intent == StrategyIntent.EXIT || intent == StrategyIntent.HEDGE) {
                List<HoldingInfo> eligible = heldWork.getOrDefault(intent, List.of());
                for (HoldingInfo h : eligible) {
                    String sym = Symbol.normalize(h.symbol());
                    SignalEngine.Signals s = bySymbol.get(sym);
                    RecommendationEngine.Holdings ctx = new RecommendationEngine.Holdings(
                            h.freeShares(), h.avgCostCents(), null, null,
                            HoldingsEvidence.Provenance.ACCOUNT_BACKED,
                            h.destinationAccountId(), h.custodyType(), h.observedAtEpochMs());
                    List<HorizonIdeas> perHorizon = horizonIdeas(s, horizons, allow0dte, req, intent, ctx,
                            buyingPowerCents, riskBudget, worldId, tally);
                    OpportunityContext opportunity = opportunityContext(s, intent);
                    Pick pick = new Pick(sym, s, opportunity.score(), perHorizon, intent.name(),
                            opportunity, bestIdea(perHorizon, worldId));
                    picks.add(pick);
                    progress.emit(new Progress("IDEAS", ideasCompleted.incrementAndGet(),
                            ideasTotal, tally.counts(), sym, pick,
                            "Candidate results are ready but provisional; the final rows "
                                    + "are retained only after the whole field and destination Book are compared."));
                }
                continue;
            }
            List<GoalScored> exactRows = marketWork.getOrDefault(intent, List.of());
            java.util.Map<String, GoalScored> exactBySymbol = exactRows.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            row -> Symbol.normalize(row.signals().symbol()),
                            java.util.function.Function.identity(),
                            (left, right) -> left,
                            java.util.LinkedHashMap::new));
            OpportunityScanKernel.Universe exactUniverse = scanKernel.prepare(
                    exactRows.stream().map(row -> row.signals().symbol()).toList());
            OpportunityScanKernel.Traversal<Pick> exactTraversal = scanKernel.traverse(
                    exactUniverse, OpportunityScanKernel.Policy.EXACT_PACKAGE_FIELD,
                    symbol -> {
                        GoalScored top = exactBySymbol.get(symbol);
                        SignalEngine.Signals s = top.signals();
                        HoldingInfo held = heldBySymbol.get(Symbol.normalize(s.symbol()));
                        // ACQUIRE never inherits the existing position: sharesOwned means
                        // "shares I want" and defaults to one lot.
                        RecommendationEngine.Holdings ctx =
                                intent != StrategyIntent.DIRECTIONAL
                                        && intent != StrategyIntent.ACQUIRE && held != null
                                ? new RecommendationEngine.Holdings(
                                        held.freeShares(), held.avgCostCents(), null, null,
                                        HoldingsEvidence.Provenance.ACCOUNT_BACKED,
                                        held.destinationAccountId(), held.custodyType(),
                                        held.observedAtEpochMs())
                                : null;
                        List<HorizonIdeas> perHorizon = horizonIdeas(s, horizons, allow0dte,
                                req, intent, ctx, buyingPowerCents, riskBudget, worldId, tally);
                        return new Pick(s.symbol(), s, top.opportunity().score(), perHorizon,
                                intent.name(), top.opportunity(), bestIdea(perHorizon, worldId));
                    },
                    completion -> {
                        Pick pick = completion.value();
                        int completed = ideasCompleted.incrementAndGet();
                        progress.emit(new Progress("IDEAS", completed, ideasTotal,
                                tally.counts(), completion.symbol(), pick,
                                pick == null
                                    ? "Exact package pricing was unavailable for this symbol; "
                                        + "the scan continues."
                                    : "Candidate results are ready but provisional; "
                                        + "final rows are retained only after the whole field and "
                                        + "destination Book are compared."));
                    });
            for (OpportunityScanKernel.Item<Pick> item : exactTraversal.items()) {
                if (item.succeeded() && item.value() != null) {
                    picks.add(item.value());
                } else {
                    notes.add(item.symbol()
                            + ": exact package pricing was unavailable for "
                            + intent.name().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }

        if (picks.isEmpty()) {
            notes.add("Nothing in the universe passed the signal and risk screens — widen the universe or lower the confidence floor");
        }
        if (req.targetProfitCents() != null && req.targetProfitCents() > 0) {
            notes.add("Profit targets are aspirations, not predictions: a structure whose max profit covers the target still has to be right");
        }
        // Exact-price the governed field first. maxPicks limits the returned presentation, not the
        // pre-pricing heuristic: applying it before evaluation allowed a lower-signal symbol with
        // better economics to remain invisible. The full Book-aware frontier now chooses the
        // retained symbols, after which the response and its Book/compensation results are
        // filtered to the same exact evaluations.
        List<StrategyEvaluation> evaluated = surfaced(picks);
        RedeploymentFrontier.BookLayer fullBook =
                RedeploymentFrontier.composeBookLayer(evaluated, evaluations, worldId, contextFactory);
        List<Pick> retainedPicks = retainTopPicks(picks, maxPicks, fullBook.frontier());
        List<StrategyEvaluation> retained = surfaced(retainedPicks);
        RedeploymentFrontier.BookLayer book = retainBookLayer(fullBook, retained);
        tally.rowsRetainedFinal(retained.size());
        progress.emit(new Progress("BOOK", ideasCompleted.get(), ideasTotal, tally.counts(),
                null, null,
                "The governed field is complete; retained rows now reflect exact economics, "
                        + "evidence, compensation, and the destination Book without blending them."));
        return new AutoResult(retainedPicks, skipped, notes, riskBudget.get(), DISCLAIMER,
                book.compensation(), book.compensationBasis(), book.frontier(), tally.counts());
    }

    private static List<Pick> retainTopPicks(List<Pick> evaluated, int maxPicks,
                                             RedeploymentFrontier.Result frontier) {
        if (evaluated == null || evaluated.isEmpty()) return List.of();
        java.util.Map<String, Integer> frontierRank = new java.util.HashMap<>();
        if (frontier != null) {
            for (int i = 0; i < frontier.decisionRanking().size(); i++) {
                frontierRank.put(frontier.decisionRanking().get(i).evaluationId(), i);
            }
        }
        java.util.Map<String, List<Pick>> byIntent = new java.util.LinkedHashMap<>();
        for (Pick pick : evaluated) {
            byIntent.computeIfAbsent(pick.intent(), ignored -> new ArrayList<>()).add(pick);
        }
        List<Pick> retained = new ArrayList<>();
        Comparator<Pick> order = Comparator
                .comparingInt((Pick pick) -> bestFrontierRank(pick, frontierRank))
                .thenComparing(Comparator.comparingInt(AutoRecommender::endorsementTier).reversed())
                .thenComparing(Comparator.comparingDouble(AutoRecommender::bestDecisionScore).reversed())
                .thenComparing(Comparator.comparingDouble(Pick::opportunityScore).reversed())
                .thenComparing(Pick::symbol);
        for (List<Pick> intentRows : byIntent.values()) {
            retained.addAll(intentRows.stream().sorted(order).limit(maxPicks).toList());
        }
        return List.copyOf(retained);
    }

    private static int bestFrontierRank(Pick pick, java.util.Map<String, Integer> ranks) {
        int best = Integer.MAX_VALUE;
        for (HorizonIdeas horizon : pick.horizons()) {
            for (ScoredCandidate scored : horizon.candidates()) {
                best = Math.min(best, ranks.getOrDefault(scored.evaluation().id(), Integer.MAX_VALUE));
            }
        }
        return best;
    }

    private static int endorsementTier(Pick pick) {
        return pick.horizons().stream().flatMap(horizon -> horizon.candidates().stream())
                .anyMatch(row -> row.evaluation().endorsement() != null
                        && row.evaluation().endorsement().endorsed()) ? 1 : 0;
    }

    private static double bestDecisionScore(Pick pick) {
        return pick.horizons().stream().flatMap(horizon -> horizon.candidates().stream())
                .mapToDouble(row -> row.evaluation().decisionScore()).max()
                .orElse(Double.NEGATIVE_INFINITY);
    }

    private static RedeploymentFrontier.BookLayer retainBookLayer(
            RedeploymentFrontier.BookLayer full, List<StrategyEvaluation> retained) {
        java.util.Set<String> ids = retained.stream().map(StrategyEvaluation::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<CompensationView.CompensationEntry> compensation = full.compensation().stream()
                .filter(entry -> ids.contains(entry.evaluationId())).toList();
        RedeploymentFrontier.Result source = full.frontier();
        RedeploymentFrontier.Result frontier = source == null ? null
                : new RedeploymentFrontier.Result(source.schemaVersion(), source.universe(),
                        source.destinationAccountId(),
                        source.decisionRanking().stream()
                                .filter(entry -> ids.contains(entry.evaluationId())).toList(),
                        compensation, source.compensationBasis(), source.source(), source.notes(),
                        source.basis());
        return new RedeploymentFrontier.BookLayer(compensation, full.compensationBasis(), frontier);
    }

    /**
     * THE retained result rows of a scan: every exact package the Scout surfaced, in rank order,
     * deduplicated by {@link ResultIdentity} rather than by symbol.
     *
     * <p>Symbol-keyed deduplication silently threw away real answers — an income covered call and
     * a directional put spread on one ticker are different results, and only one of them survived.
     * Keyed on the full result identity, both survive, while the same package reached twice (two
     * declared goals converging on one structure) still collapses to one row.</p>
     */
    public static List<StrategyEvaluation> surfaced(List<Pick> picks) {
        java.util.Map<String, StrategyEvaluation> retained = new java.util.LinkedHashMap<>();
        for (Pick pick : picks == null ? List.<Pick>of() : picks) {
            for (HorizonIdeas horizon : pick.horizons()) {
                for (ScoredCandidate scored : horizon.candidates()) {
                    StrategyEvaluation evaluation = scored.evaluation();
                    retained.putIfAbsent(ResultIdentity.of(evaluation).key(), evaluation);
                }
            }
        }
        return List.copyOf(retained.values());
    }

    /** The exact rows a completed scan retained — the same list its Book layer was composed over. */
    public static List<StrategyEvaluation> surfaced(AutoResult result) {
        return result == null ? List.of() : surfaced(result.picks());
    }

    /** Monotonic scan counters. Each rises independently; none is ever restated downward. */
    private static final class ScanTally {
        private final java.util.concurrent.atomic.AtomicInteger considered =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger eligible =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger evaluated =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger retained =
                new java.util.concurrent.atomic.AtomicInteger();

        void considered(int completed) { considered.accumulateAndGet(completed, Math::max); }
        void evidenceEligible() { eligible.incrementAndGet(); }
        void packagesEvaluated(int count) { if (count > 0) evaluated.addAndGet(count); }
        void rowsRetainedFinal(int total) { retained.set(Math.max(0, total)); }

        ScanCounts counts() {
            return new ScanCounts(considered.get(), eligible.get(), evaluated.get(), retained.get());
        }
    }

    private static void emit(ProgressListener listener, Progress progress) {
        try {
            listener.onProgress(progress);
        } catch (RuntimeException ignored) {
            // Delivery is observational. A closed browser stream must never change the scan result.
        }
    }

    /**
     * Listener-visible frames have one order even though the work producing them is parallel.
     * Only tally publication and callback delivery are serialized; provider reads, signal
     * analysis, ranking, and package evaluation stay outside this critical section.
     */
    private static final class ProgressEmitter {
        private final ProgressListener listener;
        private final Object deliveryLock = new Object();

        private ProgressEmitter(ProgressListener listener) {
            this.listener = listener == null ? NO_PROGRESS : listener;
        }

        void emit(Progress progress) {
            synchronized (deliveryLock) {
                AutoRecommender.emit(listener, progress);
            }
        }

        void updateAndEmit(Runnable update, java.util.function.Supplier<Progress> frame) {
            synchronized (deliveryLock) {
                update.run();
                AutoRecommender.emit(listener, frame.get());
            }
        }
    }

    /** Runs the engine per horizon for one symbol under one intent. */
    private List<HorizonIdeas> horizonIdeas(SignalEngine.Signals s, List<String> horizons, boolean allow0dte,
                                            AutoRequest req, StrategyIntent intent,
                                            RecommendationEngine.Holdings holdingsCtx,
                                            long buyingPowerCents,
                                            java.util.concurrent.atomic.AtomicLong riskBudget,
                                            String worldId,
                                            ScanTally tally) {
        List<HorizonIdeas> perHorizon = new ArrayList<>();
        // One normalized event result per package boundary. Several families normally share an
        // expiry, so cache the owner read instead of querying issuer evidence once per family.
        Map<LocalDate, EventService.EarningsProximity> eventsByBoundary = new HashMap<>();
        for (String horizon : horizons) {
            if ("0DTE".equals(horizon) && !allow0dte) continue;
            // An explicitly declared view is part of the same Scout/Idea controls and must
            // narrow every goal's compatible families. Only an undeclared directional scan derives
            // a view from the signal engine; other undeclared goals remain purpose-only.
            String thesis = req.thesisOverride() != null && !req.thesisOverride().isBlank()
                    ? req.thesisOverride()
                    : intent == StrategyIntent.DIRECTIONAL ? s.thesis() : null;
            RecommendationEngine.Result result = engine.recommend(new RecommendationEngine.Request(
                    s.symbol(), thesis, horizon, req.riskMode(),
                    req.maxLossCents(), req.maxRiskPctOfAccount(), null, null,
                    Boolean.TRUE.equals(req.avoidEarnings()), "0DTE".equals(horizon),
                    intent.name(), holdingsCtx, req.filters()), buyingPowerCents, worldId);
            riskBudget.set(result.riskBudgetCents());

            List<String> hNotes = new ArrayList<>();
            List<Candidate> pool = result.candidates();
            if ("0DTE".equals(horizon)) {
                // The recommendation engine owns the selected mode's market date for EVERY mode.
                // LocalDate.now(clock) uses the Clock's presentation zone (often Phoenix on the
                // owner's machine), which can still be yesterday after the US option market has
                // crossed midnight Eastern. That made a valid observed 0DTE book disappear while
                // simulated modes happened to use the correct market date.
                LocalDate today = engine.marketDate(worldId);
                pool = pool.stream().filter(c -> expiresOn(c, today)).toList();
                if (pool.isEmpty()) {
                    hNotes.add("No same-day expiration listed for " + s.symbol() + " — 0DTE skipped");
                    perHorizon.add(new HorizonIdeas(horizon, List.of(), hNotes));
                    continue;
                }
            }
            if (s.newsCatalystMention() && "RICH".equals(s.volSignal())) {
                hNotes.add("A catalyst keyword appears in the recent-news window. It is not a dated "
                        + "event claim; the exact package event result separately governs endorsement.");
            }
            List<ScoredCandidate> assessed;
            if (!pool.isEmpty()) {
                List<StrategyEvaluation> evals = evaluations.evaluateBestPerFamily(s.symbol(), intent.name(),
                        thesis, horizon, req.riskMode(), pool, buyingPowerCents,
                        io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, worldId, null,
                        holdingsCtx == null ? null : holdingsCtx.assignmentPreference(),
                        result.riskBudgetCents());
                tally.packagesEvaluated(evals.size());
                assessed = evals.stream().map(e -> {
                            LocalDate boundary = latestExpiration(e.candidate());
                            EventService.EarningsProximity event = boundary == null ? null
                                    : eventsByBoundary.computeIfAbsent(boundary,
                                        date -> evaluations.eventProximity(e.symbol(), date, worldId));
                            return new ScoredCandidate(
                                    targetFit(e.candidate(), req.targetProfitCents()), e, event);
                        })
                        .sorted((left, right) -> io.liftandshift.strikebench.eval.StrategyEvaluator.RANKING
                                .compare(left.evaluation(), right.evaluation()))
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

    static String noFavorableNote(List<ScoredCandidate> assessed, boolean observedMarketMode) {
        boolean hasAssessment = assessed.stream().anyMatch(x -> economics(x) != null);
        boolean hasComparableAssessment = assessed.stream().anyMatch(x -> economics(x) != null
                && !"MECHANICALLY_INELIGIBLE".equals(economics(x).placement()));
        if (hasAssessment && !hasComparableAssessment) {
            return "No structure reached economic comparison because every candidate failed a mechanical or account check. Review each refusal reason; this is not an economic verdict on the market.";
        }
        boolean needsDailyHistory = assessed.stream().anyMatch(x -> economics(x) != null
                && economics(x).needsDailyHistory());
        if (hasAssessment && needsDailyHistory) {
            return observedMarketMode
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
        double newsCatalystAdjustment = newsCatalystAdjustment(s.newsCatalystMention(), intent);
        double score = Math.clamp(weighted + newsCatalystAdjustment, 0, 1);
        return new OpportunityContext(intent.name(), round2(score), round2(s.confidence()),
                volFit == null ? null : round2(volFit), round2(liquidity), round2(newsCatalystAdjustment),
                goalFitSummary(s, intent, volFit), s.volatilityEvidence(), s.newsCatalystEvidence());
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

    private static double newsCatalystAdjustment(boolean newsCatalystMention, StrategyIntent intent) {
        if (!newsCatalystMention) return 0.0;
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
        if (s.newsCatalystMention() && (intent == StrategyIntent.INCOME
                || intent == StrategyIntent.ACQUIRE || intent == StrategyIntent.EXIT)) {
            return "Option premium is elevated and recent headlines contain a catalyst keyword. "
                    + "That news signal is not a dated event claim; compare the exact package's "
                    + "event evidence, after-cost edge, and gap tail before collecting premium.";
        }
        return switch (intent) {
            case INCOME -> "Ranks richer option premium against realized movement, then asks the shared evaluator whether any income package clears costs and tail risk.";
            case ACQUIRE -> "Ranks put premium against realized movement for a desired-price entry; assignment and cash collateral remain explicit.";
            case EXIT -> "Ranks call premium against realized movement for held-share exits; assignment is the declared goal, not a failure.";
            case HEDGE -> "Ranks comparatively inexpensive option protection; catalyst-news context "
                    + "is disclosed while the exact dated event result stays separate.";
            case DIRECTIONAL -> "Ranks the size of the volatility mismatch alongside direction, evidence confidence, and executable liquidity.";
        };
    }

    private BestIdea bestIdea(List<HorizonIdeas> horizons, String worldId) {
        record Located(String horizon, ScoredCandidate scored) {}
        Located best = horizons.stream()
                .flatMap(horizon -> horizon.candidates().stream()
                        .map(scored -> new Located(horizon.horizon(), scored)))
                .max(Comparator
                        .comparingInt((Located row) -> row.scored().evaluation().endorsement() != null
                                && row.scored().evaluation().endorsement().endorsed() ? 1 : 0)
                        .thenComparingDouble(row -> row.scored().evaluation().decisionScore()))
                .orElse(null);
        if (best == null) {
            return new BestIdea(false,
                    null, null, null, null, null,
                    "UNAVAILABLE", "COMPARISON", null, null,
                    null, null, null, null, null, null, null, null,
                    false,
                    "No package passed the current market, evidence, and account screens.");
        }
        StrategyEvaluation evaluation = best.scored().evaluation();
        Candidate candidate = evaluation.candidate();
        EconomicAssessment economics = evaluation.assessment() == null
                ? null : evaluation.assessment().economics();
        Long difference = economics == null || economics.marketEvAfterCostsCents() == null
                || economics.realizedVolEvAfterCostsCents() == null ? null
                : economics.realizedVolEvAfterCostsCents() - economics.marketEvAfterCostsCents();
        LocalDate latestExpiration = latestExpiration(candidate);
        EventService.EarningsProximity earningsEvent = latestExpiration == null ? null
                : evaluations.eventProximity(evaluation.symbol(), latestExpiration, worldId);
        return new BestIdea(true, evaluation.id(), ResultIdentity.of(evaluation).key(),
                best.horizon(), candidate.strategy(), candidate.displayName(),
                economics == null ? "UNAVAILABLE" : economics.verdict().name(),
                evaluation.endorsement() == null ? "COMPARISON" : evaluation.endorsement().status(),
                earningsEvent,
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

    /** The package boundary used by the one normalized earnings-proximity result. */
    private static LocalDate latestExpiration(Candidate candidate) {
        if (candidate == null || candidate.legs() == null) return null;
        return candidate.legs().stream()
                .filter(leg -> !"STOCK".equalsIgnoreCase(leg.type()))
                .map(LegView::expiration)
                .filter(java.util.Objects::nonNull)
                .map(expiration -> {
                    try { return LocalDate.parse(expiration); }
                    catch (RuntimeException invalid) { return null; }
                })
                .filter(java.util.Objects::nonNull)
                .max(LocalDate::compareTo).orElse(null);
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

}
