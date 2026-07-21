package io.liftandshift.strikebench.eval;

import java.math.BigDecimal;
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
        TerminalPayoff terminalPayoff,// exact server-owned curve receipt, explicit when unavailable
        Long evHistVolCents,         // EV at REALIZED vol, zero drift — a HISTORICAL-VOL SCENARIO, not the physical measure; null w/o history
        String evBasisNote            // the two lanes, spelled out — never one falsely precise number
) {
    public RiskProfile {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
    }

    /** Compatibility constructor for callers that do not produce presentation checkpoints. */
    public RiskProfile(long maxLossCents, Long maxProfitCents, Double pop, Long expectedValueCents,
                       long tailLossCents, double tailMovePct, List<Scenario> scenarios,
                       Long evHistVolCents, String evBasisNote) {
        this(maxLossCents, maxProfitCents, pop, expectedValueCents, tailLossCents, tailMovePct,
                scenarios, null, evHistVolCents, evBasisNote);
    }

    /** One point on the payoff-vs-underlying grid. */
    public record Scenario(double underlyingMovePct, long pnlCents) {}

    /** A bounded exact payoff polyline; clients may interpolate between these piecewise-linear points. */
    public record PayoffPoint(BigDecimal price, long profitCents) {}

    /**
     * Versioned terminal-payoff receipt from the same captured candidate evaluation. Mixed-expiry
     * packages are explicitly unavailable here because they require supplied-path valuation.
     */
    public record TerminalPayoff(
            String schemaVersion,
            String modelVersion,
            boolean available,
            Long anchorSpotCents,
            String expiration,
            String basis,
            String entryBasis,
            boolean feesIncluded,
            List<PayoffPoint> points,
            String unavailableReason
    ) {
        public TerminalPayoff {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }
}
