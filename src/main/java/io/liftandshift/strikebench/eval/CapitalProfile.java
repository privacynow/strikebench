package io.liftandshift.strikebench.eval;

/**
 * Capital efficiency, both ways so neither hides the other:
 *  - incrementalCents: new buying power this trade consumes (what most brokers show).
 *  - economicCents: the full economic exposure (e.g. a covered call ties up the share value, not
 *    just the option's margin). Ranking on incremental alone flatters share-backed trades.
 * annualizedRocPct carries a repeat-the-trade assumption and is ALWAYS a labeled component, never
 * the primary rank.
 */
public record CapitalProfile(
        long incrementalCents,
        long economicCents,
        Double returnOnCapitalPct,   // best-case return on the economic capital, null if uncapped/unknown
        Double annualizedRocPct,     // returnOnCapitalPct scaled by 365/DTE — LABELED, never primary
        int daysToExpiry,
        String basis                 // human note on what economic capital represents
) {}
