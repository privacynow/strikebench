package io.liftandshift.strikebench.research;

import io.liftandshift.strikebench.eval.StrategyEvaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Portfolio construction engine: allocates a capital budget across ranked winners under
 * real constraints — a greedy allocation by objective DENSITY (value per unit of capital) subject to
 * a per-position cap, a per-symbol concentration cap (diversification), a max number of positions,
 * and the total budget. Only VIABLE (gate-passing) evaluations are funded; every skip is explained.
 *
 * Pure and deterministic — the caller supplies the evaluations (typically an OpportunityScanner run).
 */
public final class PortfolioOptimizer {

    public record Constraints(
            long totalCapitalCents,
            Long maxPerPositionCents,   // null -> 25% of budget
            Integer maxPositions,       // null -> 10
            Double maxSymbolPct,        // null -> 0.40 of budget per symbol
            String objective,           // "score" (default) | "ev"
            boolean diagnostic          // false (default): fund only positive-EV ideas; true: least-bad set, LABELED
    ) {}

    public record Allocation(StrategyEvaluation eval, int units, long capitalCents) {}

    public record OptimizationResult(
            List<Allocation> allocations,
            long capitalUsedCents,
            long totalTailLossCents,
            long expectedValueCents,
            double avgScore,
            Map<String, Long> perSymbolCents,
            boolean diagnostic,         // true when this is a least-bad (possibly negative-EV) diagnostic set
            List<String> notes
    ) {}

    public OptimizationResult optimize(List<StrategyEvaluation> evals, Constraints c) {
        long budget = Math.max(0, c.totalCapitalCents());
        long perPosCap = c.maxPerPositionCents() != null ? c.maxPerPositionCents() : Math.max(1, budget / 4);
        int maxPositions = c.maxPositions() != null ? Math.max(1, c.maxPositions()) : 10;
        double maxSymbolPct = c.maxSymbolPct() != null ? Math.clamp(c.maxSymbolPct(), 0.05, 1.0) : 0.40;
        long perSymbolCap = (long) (budget * maxSymbolPct);
        boolean byEv = "ev".equalsIgnoreCase(c.objective());
        boolean diagnostic = c.diagnostic();

        // Viable + capital-shaped candidates. In NORMAL mode we additionally require a POSITIVE modeled
        // expected value — an optimizer must not present a portfolio the model expects to LOSE money on as
        // an answer (the project's honesty non-negotiable). null-EV = "unknown value", not fundable in
        // normal mode. Diagnostic mode keeps the old least-bad behavior but is LABELED as such.
        List<StrategyEvaluation> capitalOk = evals.stream()
                .filter(StrategyEvaluation::viable)
                .filter(e -> e.capitalIncrementalCents() != null && e.capitalIncrementalCents() > 0)
                .toList();
        long rejectedNonPositive = capitalOk.stream().filter(e -> !hasPositiveEv(e)).count();
        List<StrategyEvaluation> fundable = capitalOk.stream()
                .filter(e -> diagnostic || hasPositiveEv(e))
                .sorted(Comparator.comparingDouble((StrategyEvaluation e) -> density(e, byEv)).reversed())
                .toList();

        List<Allocation> allocations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Long> perSymbol = new LinkedHashMap<>();
        long used = 0, tail = 0, ev = 0;
        double scoreSum = 0;

        for (StrategyEvaluation e : fundable) {
            if (allocations.size() >= maxPositions) { notes.add("stopped at maxPositions=" + maxPositions); break; }
            long unit = e.capitalIncrementalCents();
            String sym = e.symbol() == null ? "?" : e.symbol();
            long symUsed = perSymbol.getOrDefault(sym, 0L);
            long room = Math.min(budget - used, Math.min(perPosCap, perSymbolCap - symUsed));
            int units = (int) (room / unit);
            if (units < 1) {
                notes.add(sym + " " + e.family() + ": no room within budget / per-position / per-symbol caps");
                continue;
            }
            long capital = (long) units * unit;
            allocations.add(new Allocation(e, units, capital));
            used += capital;
            tail += (long) units * e.tailLossCents();
            ev += (long) units * (e.evCents() == null ? 0 : e.evCents());
            scoreSum += e.rankScore();
            perSymbol.merge(sym, capital, Long::sum);
        }

        double avgScore = allocations.isEmpty() ? 0 : Math.round(scoreSum / allocations.size() * 100) / 100.0;
        if (allocations.isEmpty()) {
            if (!diagnostic && rejectedNonPositive > 0) {
                notes.add("No idea in this universe has positive modeled expected value at current marks — nothing funded. "
                        + rejectedNonPositive + " viable idea" + (rejectedNonPositive == 1 ? " was" : "s were")
                        + " rejected as negative or unknown EV. Switch to Expert diagnostic mode to inspect the least-bad set.");
            } else {
                notes.add("Nothing funded — no viable evaluations fit the budget.");
            }
        } else if (diagnostic && ev < 0) {
            notes.add("DIAGNOSTIC set: this is the least-bad allocation, not a recommendation — its modeled expected value is negative.");
        }
        return new OptimizationResult(allocations, used, tail, ev, avgScore, perSymbol, diagnostic, notes);
    }

    /** An idea is fundable in normal mode only if its modeled expected value is affirmatively positive. */
    private static boolean hasPositiveEv(StrategyEvaluation e) {
        return e.evCents() != null && e.evCents() > 0;
    }

    /** Objective value per cent of capital — the greedy ranking key. */
    private static double density(StrategyEvaluation e, boolean byEv) {
        double cap = e.capitalIncrementalCents();
        if (cap <= 0) return 0;
        double value = byEv ? (e.evCents() == null ? 0 : e.evCents()) : e.rankScore();
        return value / cap;
    }
}
