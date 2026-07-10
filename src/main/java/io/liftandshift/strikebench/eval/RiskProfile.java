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
        Long expectedValueCents,      // RISK-NEUTRAL (market IV, zero drift); null when model-dependent
        long tailLossCents,           // loss under the stress move (>= 0)
        double tailMovePct,           // the stress move used for tailLoss, e.g. 0.20 for -20%/+20%
        List<Scenario> scenarios,     // ordered by underlyingMovePct ascending
        Long evPhysicalCents,         // EV at REALIZED vol (physical-vol lane, zero drift); null w/o history
        String evBasisNote            // the two lanes, spelled out — never one falsely precise number
) {
    public RiskProfile {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
    }

    /** Pre-physical-lane shape. */
    public RiskProfile(long maxLossCents, Long maxProfitCents, Double pop, Long expectedValueCents,
                       long tailLossCents, double tailMovePct, List<Scenario> scenarios) {
        this(maxLossCents, maxProfitCents, pop, expectedValueCents, tailLossCents, tailMovePct,
                scenarios, null, null);
    }

    /** One point on the payoff-vs-underlying grid. */
    public record Scenario(double underlyingMovePct, long pnlCents) {}
}
