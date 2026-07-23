package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.eval.StrategyEvaluation;
import io.liftandshift.strikebench.util.BoundedFanout;
import io.liftandshift.strikebench.util.Symbols;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-symbol opportunity orchestration. Candidate construction and DecisionPolicy evaluation
 * remain separate services; this class only owns bounded provider concurrency, per-symbol failure
 * isolation, and the final competition across each symbol's best viable idea.
 */
public final class OpportunityScanner {
    private static final int CONCURRENCY = 8;

    public record ScanResult(List<StrategyEvaluation> ranked, List<String> notes, int scanned,
                             List<CompensationView.CompensationEntry> compensation, String compensationBasis,
                             RedeploymentFrontier.Result frontier) {
        /** Pre-compensation-view constructor keeps existing callers' shape. */
        public ScanResult(List<StrategyEvaluation> ranked, List<String> notes, int scanned) {
            this(ranked, notes, scanned, List.of(), null, null);
        }

        /** Compatibility shape for callers that predate the Book-aware frontier. */
        public ScanResult(List<StrategyEvaluation> ranked, List<String> notes, int scanned,
                          List<CompensationView.CompensationEntry> compensation,
                          String compensationBasis) {
            this(ranked, notes, scanned, compensation, compensationBasis, null);
        }
    }


    private final RecommendationEngine engine;
    private final EvaluationService evaluations;

    public OpportunityScanner(RecommendationEngine engine, EvaluationService evaluations) {
        this.engine = java.util.Objects.requireNonNull(engine, "engine");
        this.evaluations = java.util.Objects.requireNonNull(evaluations, "evaluations");
    }

    /** Candidates and evaluation context always share {@code worldId}; null means Observed. */
    public ScanResult scan(List<String> symbols, String intent, String thesis, String horizon,
                           String riskMode, long buyingPowerCents, String userId, int topN,
                           String worldId, Long maxLossCents) {
        return scanInternal(symbols, intent, thesis, horizon, riskMode, buyingPowerCents,
                userId, topN, worldId, maxLossCents, null);
    }

    public ScanResult scan(List<String> symbols, String intent, String thesis, String horizon,
                           String riskMode, long buyingPowerCents, String userId, int topN,
                           String worldId, Long maxLossCents,
                           RedeploymentFrontier.Context frontierContext) {
        return scanInternal(symbols, intent, thesis, horizon, riskMode, buyingPowerCents,
                userId, topN, worldId, maxLossCents,
                frontierContext == null ? null : ignored -> frontierContext);
    }

    public ScanResult scanWithFrontier(List<String> symbols, String intent, String thesis,
                                       String horizon, String riskMode, long buyingPowerCents,
                                       String userId, int topN, String worldId, Long maxLossCents,
                                       java.util.function.Function<List<StrategyEvaluation>,
                                               RedeploymentFrontier.Context> contextFactory) {
        if (contextFactory == null) throw new IllegalArgumentException("frontier context factory is required");
        return scanInternal(symbols, intent, thesis, horizon, riskMode, buyingPowerCents,
                userId, topN, worldId, maxLossCents, contextFactory);
    }

    private ScanResult scanInternal(List<String> symbols, String intent, String thesis, String horizon,
                                    String riskMode, long buyingPowerCents, String userId, int topN,
                                    String worldId, Long maxLossCents,
                                    java.util.function.Function<List<StrategyEvaluation>,
                                            RedeploymentFrontier.Context> contextFactory) {
        List<String> normalized = Symbols.normalize(symbols);
        if (normalized.isEmpty()) return new ScanResult(List.of(), List.of(), 0);

        record PerSymbol(StrategyEvaluation best, String note) {}
        List<PerSymbol> results = BoundedFanout.map(normalized, CONCURRENCY,
                symbol -> {
                    var request = new RecommendationEngine.Request(symbol, thesis, horizon, riskMode,
                            maxLossCents, null, null, null, false, false, intent, null, null);
                    var field = engine.recommend(request, buyingPowerCents, worldId);
                    // "no candidates" is a NORMAL outcome, not a failure — it must stay a returned
                    // value and NOT route through onFailure (which would relabel it).
                    if (field.candidates().isEmpty()) {
                        return new PerSymbol(null, symbol + ": no candidates");
                    }
                    // ONE ranking primitive (shared with Scout and Decision): best package per
                    // family in decision-score order, then this scan's own pick — the top viable.
                    StrategyEvaluation best = evaluations.evaluateBestPerFamily(symbol, field.intent(),
                                    field.thesis(), field.horizon(), field.riskMode(), field.candidates(),
                                    buyingPowerCents, AnalysisContext.OBSERVED, worldId, null).stream()
                            .filter(StrategyEvaluation::viable)
                            .findFirst().orElse(null);
                    return new PerSymbol(best, null);
                },
                (symbol, failure) -> new PerSymbol(null, symbol + ": analysis unavailable right now"));

        List<StrategyEvaluation> best = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (PerSymbol result : results) {
            if (result == null) continue;
            if (result.best() != null) best.add(result.best());
            if (result.note() != null) notes.add(result.note());
        }
        best.sort(io.liftandshift.strikebench.eval.StrategyEvaluator.RANKING);
        List<StrategyEvaluation> ranked = best.stream().limit(Math.max(1, topN)).toList();
        evaluations.persist(ranked, userId);
        RedeploymentFrontier.BookLayer book =
                RedeploymentFrontier.composeBookLayer(best, evaluations, worldId, contextFactory);
        return new ScanResult(ranked, notes, normalized.size(), book.compensation(),
                book.compensationBasis(), book.frontier());
    }

}
