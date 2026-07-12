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
            String objective,           // DECISION (default) | MARKET_EV | HISTORY_EV
            boolean diagnostic          // false (default): fund only positive-EV ideas; true: least-bad set, LABELED
    ) {}

    public record Allocation(StrategyEvaluation eval, int units, long capitalCents) {}

    public record OptimizationResult(
            List<Allocation> allocations,
            long capitalUsedCents,
            long totalTailLossCents,
            Long marketEvAfterCostsCents,
            Long realizedVolEvAfterCostsCents,
            int marketEvCoverage,
            int realizedVolEvCoverage,
            double avgScore,
            Map<String, Long> perSymbolCents,
            boolean diagnostic,         // true when this is a least-bad (possibly negative-EV) diagnostic set
            boolean teachingOnly,       // generated/incomplete evidence: useful practice, not an observed allocation
            List<String> notes
    ) {}

    public OptimizationResult optimize(List<StrategyEvaluation> evals, Constraints c) {
        long budget = Math.max(0, c.totalCapitalCents());
        long perPosCap = c.maxPerPositionCents() != null ? c.maxPerPositionCents() : Math.max(1, budget / 4);
        int maxPositions = c.maxPositions() != null ? Math.max(1, c.maxPositions()) : 10;
        double maxSymbolPct = c.maxSymbolPct() != null ? Math.clamp(c.maxSymbolPct(), 0.05, 1.0) : 0.40;
        long perSymbolCap = (long) (budget * maxSymbolPct);
        Objective objective = Objective.parse(c.objective());
        boolean diagnostic = c.diagnostic();

        // Viable + capital-shaped candidates. Normal mode funds only evaluations the shared economic
        // policy calls FAVORABLE; every mixed/adverse/unavailable structure remains inspectable through
        // the explicitly labeled diagnostic mode instead of being presented as an allocation answer.
        List<StrategyEvaluation> capitalOk = evals.stream()
                .filter(StrategyEvaluation::viable)
                .filter(e -> e.capitalIncrementalCents() != null && e.capitalIncrementalCents() > 0)
                .toList();
        long rejectedEconomics = capitalOk.stream().filter(e -> !hasFavorableEconomics(e)).count();
        List<StrategyEvaluation> fundable = capitalOk.stream()
                .filter(e -> diagnostic || hasFavorableEconomics(e))
                .sorted(Comparator.comparingDouble((StrategyEvaluation e) -> density(e, objective)).reversed())
                .toList();

        List<Allocation> allocations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Long> perSymbol = new LinkedHashMap<>();
        long used = 0, tail = 0, marketEv = 0, historyEv = 0;
        int marketEvCoverage = 0, historyEvCoverage = 0;
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
            Long oneMarketEv = marketEv(e), oneHistoryEv = historyEv(e);
            if (oneMarketEv != null) { marketEv += (long) units * oneMarketEv; marketEvCoverage++; }
            if (oneHistoryEv != null) { historyEv += (long) units * oneHistoryEv; historyEvCoverage++; }
            scoreSum += e.decisionScore();
            perSymbol.merge(sym, capital, Long::sum);
        }

        double avgScore = allocations.isEmpty() ? 0 : Math.round(scoreSum / allocations.size() * 100) / 100.0;
        if (allocations.isEmpty()) {
            if (!diagnostic && rejectedEconomics > 0) {
                notes.add("No idea in this universe earned a favorable after-cost economic verdict — nothing funded. "
                        + rejectedEconomics + " viable idea" + (rejectedEconomics == 1 ? " was" : "s were")
                        + " mixed, adverse, or economically unavailable. Use Expert diagnostic mode to inspect them without treating them as recommendations.");
            } else {
                notes.add("Nothing funded — no viable evaluations fit the budget.");
            }
        } else if (diagnostic) {
            notes.add("DIAGNOSTIC set: this is a comparison allocation, not a recommendation; one or both after-cost EV lanes may be adverse or unavailable.");
        }
        boolean teachingAllocation = allocations.stream().anyMatch(a ->
                a.eval().economics() == null || !a.eval().economics().actionableFavorable());
        if (!allocations.isEmpty() && teachingAllocation && !diagnostic) {
            notes.add("TEACHING set: at least one funded case uses generated rather than end-to-end observed evidence. It is a practice allocation, not a live-market recommendation.");
        }
        if (!allocations.isEmpty() && marketEvCoverage < allocations.size()) {
            notes.add("Market EV total covers " + marketEvCoverage + " of " + allocations.size()
                    + " allocated positions; unavailable rows are excluded from that partial total.");
        }
        if (!allocations.isEmpty() && historyEvCoverage < allocations.size()) {
            notes.add("History EV total covers " + historyEvCoverage + " of " + allocations.size()
                    + " allocated positions; unavailable rows are excluded from that partial total.");
        }
        return new OptimizationResult(allocations, used, tail,
                marketEvCoverage > 0 ? marketEv : null,
                historyEvCoverage > 0 ? historyEv : null,
                marketEvCoverage, historyEvCoverage,
                avgScore, perSymbol, diagnostic, teachingAllocation, notes);
    }

    private enum Objective {
        DECISION, MARKET_EV, HISTORY_EV;

        static Objective parse(String raw) {
            if (raw == null || raw.isBlank()) return DECISION;
            try { return valueOf(raw.trim().toUpperCase()); }
            catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("objective must be DECISION, MARKET_EV, or HISTORY_EV");
            }
        }
    }

    private static boolean hasFavorableEconomics(StrategyEvaluation e) {
        return e.economics() != null && e.economics().verdict() == io.liftandshift.strikebench.eval.EconomicAssessment.Verdict.FAVORABLE;
    }

    private static Long marketEv(StrategyEvaluation e) {
        return e.economics() == null ? null : e.economics().marketEvAfterCostsCents();
    }

    private static Long historyEv(StrategyEvaluation e) {
        return e.economics() == null ? null : e.economics().realizedVolEvAfterCostsCents();
    }

    /** Objective value per cent of capital — the greedy ranking key. */
    private static double density(StrategyEvaluation e, Objective objective) {
        double cap = e.capitalIncrementalCents();
        if (cap <= 0) return 0;
        Long economicValue = objective == Objective.MARKET_EV ? marketEv(e)
                : objective == Objective.HISTORY_EV ? historyEv(e) : null;
        double value = objective == Objective.DECISION ? e.decisionScore()
                : economicValue == null ? Double.NEGATIVE_INFINITY : economicValue;
        return value / cap;
    }
}
