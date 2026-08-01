package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.eval.StrategyEvaluation;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-symbol opportunity orchestration. Candidate construction and DecisionPolicy evaluation
 * remain separate services; {@link OpportunityScanKernel} owns traversal and failure isolation,
 * while this class owns the exact-evaluation competition and one-per-symbol allocation policy.
 */
public final class OpportunityScanner {
    public record ScanResult(List<StrategyEvaluation> ranked, List<String> notes, int scanned,
                             List<CompensationView.CompensationEntry> compensation, String compensationBasis,
                             RedeploymentFrontier.Result frontier) {
    }


    private final RecommendationEngine engine;
    private final EvaluationService evaluations;
    private final OpportunityScanKernel scanKernel;

    public OpportunityScanner(RecommendationEngine engine, EvaluationService evaluations) {
        this(engine, evaluations, new OpportunityScanKernel());
    }

    public OpportunityScanner(RecommendationEngine engine, EvaluationService evaluations,
                              OpportunityScanKernel scanKernel) {
        this.engine = java.util.Objects.requireNonNull(engine, "engine");
        this.evaluations = java.util.Objects.requireNonNull(evaluations, "evaluations");
        this.scanKernel = java.util.Objects.requireNonNull(scanKernel, "scanKernel");
    }

    /** Candidates and evaluation context always share {@code worldId}; null means Observed. */
    public ScanResult scan(List<String> symbols, String intent, String thesis, String horizon,
                           String riskMode, long buyingPowerCents, String userId, int topN,
                           String worldId, Long maxLossCents) {
        return scanInternal(symbols, intent, thesis, horizon, riskMode, buyingPowerCents,
                userId, topN, worldId, maxLossCents, true, null);
    }

    public ScanResult scan(List<String> symbols, String intent, String thesis, String horizon,
                           String riskMode, long buyingPowerCents, String userId, int topN,
                           String worldId, Long maxLossCents,
                           RedeploymentFrontier.Context frontierContext) {
        return scanInternal(symbols, intent, thesis, horizon, riskMode, buyingPowerCents,
                userId, topN, worldId, maxLossCents, true,
                frontierContext == null ? null : ignored -> frontierContext);
    }

    public ScanResult scanWithFrontier(List<String> symbols, String intent, String thesis,
                                       String horizon, String riskMode, long buyingPowerCents,
                                       String userId, int topN, String worldId, Long maxLossCents,
                                       java.util.function.Function<List<StrategyEvaluation>,
                                               RedeploymentFrontier.Context> contextFactory) {
        return scanWithFrontier(symbols, intent, thesis, horizon, riskMode, buyingPowerCents,
                userId, topN, worldId, maxLossCents, true, contextFactory);
    }

    public ScanResult scanWithFrontier(List<String> symbols, String intent, String thesis,
                                       String horizon, String riskMode, long buyingPowerCents,
                                       String userId, int topN, String worldId, Long maxLossCents,
                                       Boolean avoidEarnings,
                                       java.util.function.Function<List<StrategyEvaluation>,
                                               RedeploymentFrontier.Context> contextFactory) {
        if (contextFactory == null) throw new IllegalArgumentException("frontier context factory is required");
        return scanInternal(symbols, intent, thesis, horizon, riskMode, buyingPowerCents,
                userId, topN, worldId, maxLossCents, avoidEarnings, contextFactory);
    }

    private ScanResult scanInternal(List<String> symbols, String intent, String thesis, String horizon,
                                    String riskMode, long buyingPowerCents, String userId, int topN,
                                    String worldId, Long maxLossCents, Boolean avoidEarnings,
                                    java.util.function.Function<List<StrategyEvaluation>,
                                            RedeploymentFrontier.Context> contextFactory) {
        OpportunityScanKernel.Universe universe = scanKernel.prepare(symbols);
        if (universe.isEmpty()) return new ScanResult(List.of(), List.of(), 0,
                List.of(), null, null);

        record PerSymbol(List<StrategyEvaluation> viable, String note) {}
        OpportunityScanKernel.Traversal<PerSymbol> traversal = scanKernel.traverse(
                universe, OpportunityScanKernel.Policy.EXACT_PACKAGE_FIELD,
                symbol -> {
                    var request = new RecommendationEngine.Request(symbol, thesis, horizon, riskMode,
                            maxLossCents, null, null, null,
                            Boolean.TRUE.equals(avoidEarnings), false, intent, null, null);
                    var field = engine.recommend(request, buyingPowerCents, worldId);
                    // "no candidates" is a NORMAL outcome, not a failure — it must stay a returned
                    // value and NOT route through onFailure (which would relabel it).
                    if (field.candidates().isEmpty()) {
                        return new PerSymbol(List.of(), symbol + ": no candidates");
                    }
                    // ONE ranking primitive (shared with Scout and Decision): best package per
                    // family in decision-score order. EVERY viable family is kept: two genuinely
                    // different structures on one symbol are two results, and collapsing them to
                    // the symbol's single best silently discarded the alternative.
                    List<StrategyEvaluation> evaluated = evaluations.evaluateBestPerFamily(
                            symbol, field.intent(), field.thesis(), field.horizon(), field.riskMode(),
                            field.candidates(), buyingPowerCents, AnalysisContext.OBSERVED, worldId,
                            null, null, field.riskBudgetCents());
                    List<StrategyEvaluation> viable = evaluated.stream()
                            .filter(StrategyEvaluation::viable).toList();
                    // A symbol that produced packages and then lost every one of them to the
                    // viability screen used to return in silence, so the scan's own notes could not
                    // distinguish "nothing was built here" from "everything built here was
                    // screened out" (program §3.2). Both are answers; only one was being reported.
                    if (viable.isEmpty()) {
                        return new PerSymbol(List.of(), symbol + ": " + evaluated.size()
                                + " package" + (evaluated.size() == 1 ? "" : "s")
                                + " priced, none passed the viability screen");
                    }
                    return new PerSymbol(viable, null);
                });

        // Deduplicated on the full result identity (symbol + family + exact package + expiration +
        // declarations), never on symbol alone.
        java.util.LinkedHashMap<String, StrategyEvaluation> retained = new java.util.LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (OpportunityScanKernel.Item<PerSymbol> item : traversal.items()) {
            if (!item.succeeded()) {
                notes.add(item.symbol() + ": analysis unavailable right now");
                continue;
            }
            PerSymbol result = item.value();
            if (result == null) continue;
            for (StrategyEvaluation evaluation : result.viable()) {
                retained.putIfAbsent(ResultIdentity.of(evaluation).key(), evaluation);
            }
            if (result.note() != null) notes.add(result.note());
        }
        List<StrategyEvaluation> surfaced = new ArrayList<>(retained.values());
        surfaced.sort(io.liftandshift.strikebench.eval.StrategyEvaluator.RANKING);
        // Portfolio construction still proposes at most ONE structure per symbol — that is an
        // allocation rule, not a display rule — while the frontier below keeps every retained row.
        java.util.Set<String> allocated = new java.util.LinkedHashSet<>();
        List<StrategyEvaluation> ranked = surfaced.stream()
                .filter(evaluation -> allocated.add(evaluation.symbol()))
                .limit(Math.max(1, topN)).toList();
        // The whole retained field is persisted, not only the allocated slice: every row the scan
        // surfaced must stay adoptable as the exact package it showed (audit §8.2).
        evaluations.persist(surfaced, userId, worldId);
        RedeploymentFrontier.BookLayer book =
                RedeploymentFrontier.composeBookLayer(surfaced, evaluations, worldId, contextFactory);
        return new ScanResult(ranked, notes, universe.size(), book.compensation(),
                book.compensationBasis(), book.frontier());
    }

}
