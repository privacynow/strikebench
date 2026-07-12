package io.liftandshift.strikebench.eval;

/**
 * The recipe that produced a candidate: the inputs that framed the search. Persisted alongside the
 * evaluation so a recommendation is reproducible and comparable to its alternatives.
 */
public record StrategySpec(
        String symbol,
        String family,      // StrategyFamily name
        String intent,      // StrategyIntent name
        String horizon,     // 0DTE / week / month / quarter
        String thesis,      // directional view, when applicable
        String riskMode,    // conservative / balanced / aggressive
        String objective    // the ranking objective this evaluation optimizes (e.g. "decision")
) {}
