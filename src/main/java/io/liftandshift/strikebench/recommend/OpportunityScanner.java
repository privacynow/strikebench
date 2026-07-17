package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.eval.StrategyEvaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Cross-symbol opportunity orchestration. Candidate construction and DecisionPolicy evaluation
 * remain separate services; this class only owns bounded provider concurrency, per-symbol failure
 * isolation, and the final competition across each symbol's best viable idea.
 */
public final class OpportunityScanner {
    private static final int CONCURRENCY = 8;

    public record ScanResult(List<StrategyEvaluation> ranked, List<String> notes, int scanned,
                             List<CompensationView.CompensationEntry> compensation, String compensationBasis) {
        /** Pre-compensation-view constructor keeps existing callers' shape. */
        public ScanResult(List<StrategyEvaluation> ranked, List<String> notes, int scanned) {
            this(ranked, notes, scanned, List.of(), null);
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
        List<String> normalized = normalize(symbols);
        if (normalized.isEmpty()) return new ScanResult(List.of(), List.of(), 0);

        record PerSymbol(StrategyEvaluation best, String note) {}
        Semaphore gate = new Semaphore(Math.min(CONCURRENCY, normalized.size()));
        List<PerSymbol> results = new ArrayList<>(Collections.nCopies(normalized.size(), null));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<PerSymbol>> futures = new ArrayList<>();
            for (String symbol : normalized) {
                futures.add(executor.submit(() -> {
                    gate.acquire();
                    try {
                        var request = new RecommendationEngine.Request(symbol, thesis, horizon, riskMode,
                                maxLossCents, null, null, null, false, false, intent, null, null);
                        var field = engine.recommend(request, buyingPowerCents, worldId);
                        if (field.candidates().isEmpty()) {
                            return new PerSymbol(null, symbol + ": no candidates");
                        }
                        StrategyEvaluation best = evaluations.evaluate(symbol, field.intent(), field.thesis(),
                                        field.horizon(), field.riskMode(), field.candidates(), buyingPowerCents,
                                        null, false, AnalysisContext.OBSERVED, worldId, null).stream()
                                .filter(StrategyEvaluation::viable)
                                .findFirst().orElse(null);
                        return new PerSymbol(best, null);
                    } catch (RuntimeException failure) {
                        return new PerSymbol(null, symbol + ": analysis unavailable right now");
                    } finally {
                        gate.release();
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.set(i, futures.get(i).get());
                } catch (Exception failure) {
                    results.set(i, new PerSymbol(null,
                            normalized.get(i) + ": analysis unavailable right now"));
                }
            }
        }

        List<StrategyEvaluation> best = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (PerSymbol result : results) {
            if (result == null) continue;
            if (result.best() != null) best.add(result.best());
            if (result.note() != null) notes.add(result.note());
        }
        best.sort(java.util.Comparator.comparingDouble(StrategyEvaluation::decisionScore).reversed());
        List<StrategyEvaluation> ranked = best.stream().limit(Math.max(1, topN)).toList();
        evaluations.persist(ranked, userId);
        List<CompensationView.CompensationEntry> compensation =
                CompensationView.compute(best, evaluations, worldId);
        return new ScanResult(ranked, notes, normalized.size(), compensation, CompensationView.BASIS);
    }



    private static List<String> normalize(List<String> symbols) {
        List<String> normalized = new ArrayList<>();
        for (String raw : symbols == null ? List.<String>of() : symbols) {
            String symbol = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            if (!symbol.isEmpty() && !normalized.contains(symbol)) normalized.add(symbol);
        }
        return List.copyOf(normalized);
    }
}
