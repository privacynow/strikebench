package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.eval.StrategyEvaluation;

import java.util.ArrayList;
import java.util.List;

/**
 * The compensation view (folded Phase 10.3): premium per unit of realized risk, ranked BESIDE
 * the decision score on scouting surfaces, never replacing it. Premium-collecting structures
 * only; every component is named and explained so the ordering never travels as a bare number.
 */
public final class CompensationView {
    private CompensationView() {}

    public static final String BASIS = "Premium per unit of realized risk: annualized yield, "
            + "variance risk premium, gap frequency (sessions opening >2% from the prior close), "
            + "liquidity, and capital efficiency. Earnings proximity is carried on each candidate's "
            + "own warnings. This view sits beside the Decision score; it never replaces it.";

    public record CompensationEntry(String symbol, String strategy, String label, double score,
                                    List<CompensationComponent> components, String evaluationId) {}
    public record CompensationComponent(String name, double weight, double value, String note) {}

    /** Premium-collecting structures ranked by named compensation components; others are absent by design. */
    public static List<CompensationEntry> compute(List<StrategyEvaluation> evaluationsRanked,
                                                  EvaluationService evaluations, String worldId) {
        List<CompensationEntry> out = new ArrayList<>();
        java.util.Map<String, Double> gapCache = new java.util.HashMap<>();
        for (StrategyEvaluation evaluation : evaluationsRanked) {
            var candidate = evaluation.candidate();
            if (candidate.entryNetPremiumCents() <= 0) continue; // premium collectors only, by design
            // Share/strike-backed structures carry a collateral yield; defined-risk credit
            // structures annualize the credit over their risk capital instead.
            Double yieldPct = candidate.annualizedYieldPct();
            String yieldBasis = "on the collateral";
            if (yieldPct == null && evaluation.capital() != null
                    && evaluation.capital().annualizedRocPct() != null) {
                yieldPct = evaluation.capital().annualizedRocPct();
                yieldBasis = "on the risk capital";
            }
            if (yieldPct == null) continue;
            String symbol = evaluation.spec().symbol();
            Double gap = gapCache.computeIfAbsent(symbol, sym -> {
                try { return evaluations.gapFrequency(sym, worldId); }
                catch (RuntimeException e) { return null; }
            });
            List<CompensationComponent> components = new ArrayList<>();
            double yieldNorm = clamp01(yieldPct / 30.0);
            components.add(new CompensationComponent("Annualized premium yield", 0.40, yieldNorm,
                    String.format("%.1f%%/yr %s, IF repeatable", yieldPct, yieldBasis)));
            Double vrp = evaluation.volatility() == null ? null : evaluation.volatility().varianceRiskPremium();
            components.add(new CompensationComponent("Variance risk premium", 0.20,
                    vrp == null ? 0.5 : clamp01(0.5 + vrp * 5.0),
                    vrp == null ? "IV vs realized unavailable — treated as neutral"
                            : String.format("options price %.0f vol points %s realized",
                                    Math.abs(vrp * 100), vrp >= 0 ? "over" : "under")));
            components.add(new CompensationComponent("Gap risk", 0.15,
                    gap == null ? 0.5 : clamp01(1.0 - gap * 8.0),
                    gap == null ? "gap history too thin — treated as neutral"
                            : String.format("%.0f%% of sessions opened >2%% from the prior close", gap * 100)));
            components.add(new CompensationComponent("Liquidity", 0.15,
                    clamp01(candidate.liquidityScore()), "tighter spreads keep the premium real"));
            Double roc = evaluation.capital() == null ? null : evaluation.capital().returnOnCapitalPct();
            components.add(new CompensationComponent("Capital efficiency", 0.10,
                    roc == null ? 0.4 : clamp01(roc / (roc + 50.0)),
                    roc == null ? "return on capital undefined"
                            : String.format("%.0f%% best-case return on the capital at work", roc)));
            double score = 0;
            for (CompensationComponent component : components) score += component.weight() * component.value();
            out.add(new CompensationEntry(symbol, candidate.strategy(), candidate.label(),
                    Math.round(score * 1000.0) / 10.0, components, evaluation.id()));
        }
        out.sort(java.util.Comparator.comparingDouble(CompensationEntry::score).reversed());
        return out;
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
