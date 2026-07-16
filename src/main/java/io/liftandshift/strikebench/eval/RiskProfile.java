package io.liftandshift.strikebench.eval;

import java.util.List;

/**
 * The full risk picture, not just max loss. tailLossCents is the loss in a stressed move (a
 * defined-risk spread's tail == its max loss; an uncapped structure's tail is a modeled large
 * move). scenarios is a P/L grid across underlying moves for the decision page. Produced by
 * {@code RiskProfiler}.
 */
public record RiskProfile(
        long maxLossCents,
        Long maxProfitCents,          // null = uncapped/model-dependent
        Double pop,                   // probability of profit (lognormal), null when model-dependent
        Long expectedValueCents,      // present-value RISK-NEUTRAL approximation (market IV, r, q=0)
        long tailLossCents,           // loss under the stress move (>= 0)
        double tailMovePct,           // the stress move used for tailLoss, e.g. 0.20 for -20%/+20%
        List<Scenario> scenarios,     // ordered by underlyingMovePct ascending
        Long evHistVolCents,         // EV at REALIZED vol, zero drift — a HISTORICAL-VOL SCENARIO, not the physical measure; null w/o history
        String evBasisNote            // the two lanes, spelled out — never one falsely precise number
) {
    public RiskProfile {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
    }

    /** One point on the payoff-vs-underlying grid. */
    public record Scenario(double underlyingMovePct, long pnlCents) {}
}
