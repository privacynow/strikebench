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
        long tailLossCents,           // bounded envelope max loss, otherwise modeled stress loss (>= 0)
        double tailMovePct,           // base stress grid, e.g. 0.20 for -20%/+20%; bounded envelope may lie beyond it
        List<Scenario> scenarios,     // ordered by underlyingMovePct ascending
        TerminalPayoff terminalPayoff,// exact server-owned curve receipt, explicit when unavailable
        Long evHistVolCents,         // EV at REALIZED vol, zero drift — a HISTORICAL-VOL SCENARIO, not the physical measure; null w/o history
        String evBasisNote,           // the two lanes, spelled out — never one falsely precise number
        // The SEPARATE real-world / tail lane: a Merton jump-mixture (body + calibrated down-gap) that
        // owns the tail-aware POP, expected shortfall and calm/base/tense gap dial. It sits ALONGSIDE
        // the risk-neutral lognormal `pop` above, never replacing it. Null when unavailable/not computed.
        io.liftandshift.strikebench.pricing.JumpMixtureTerminal.Tail jumpTail,
        WorstScenario worstScenario
) {
    public RiskProfile {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
    }

    /** Compatibility constructor for callers that have not yet attached a named-story severity. */
    public RiskProfile(long maxLossCents, Long maxProfitCents, Double pop, Long expectedValueCents,
                       long tailLossCents, double tailMovePct, List<Scenario> scenarios,
                       TerminalPayoff terminalPayoff, Long evHistVolCents, String evBasisNote,
                       io.liftandshift.strikebench.pricing.JumpMixtureTerminal.Tail jumpTail) {
        this(maxLossCents, maxProfitCents, pop, expectedValueCents, tailLossCents, tailMovePct,
                scenarios, terminalPayoff, evHistVolCents, evBasisNote, jumpTail, null);
    }

    /** Compatibility constructor for callers that produce presentation checkpoints but no tail lane. */
    public RiskProfile(long maxLossCents, Long maxProfitCents, Double pop, Long expectedValueCents,
                       long tailLossCents, double tailMovePct, List<Scenario> scenarios,
                       TerminalPayoff terminalPayoff, Long evHistVolCents, String evBasisNote) {
        this(maxLossCents, maxProfitCents, pop, expectedValueCents, tailLossCents, tailMovePct,
                scenarios, terminalPayoff, evHistVolCents, evBasisNote, null, null);
    }

    /** Compatibility constructor for callers that do not produce presentation checkpoints. */
    public RiskProfile(long maxLossCents, Long maxProfitCents, Double pop, Long expectedValueCents,
                       long tailLossCents, double tailMovePct, List<Scenario> scenarios,
                       Long evHistVolCents, String evBasisNote) {
        this(maxLossCents, maxProfitCents, pop, expectedValueCents, tailLossCents, tailMovePct,
                scenarios, null, evHistVolCents, evBasisNote, null, null);
    }

    /**
     * One point on the payoff-vs-underlying grid. {@code prob} is the risk-neutral lognormal mass in
     * the Voronoi bin around this move (same distribution as {@link #pop()}); null when no ATM IV /
     * multi-expiry, so the client shows the bar without a probability rather than inventing one.
     */
    public record Scenario(double underlyingMovePct, long pnlCents, Double prob) {}

    public enum ScenarioSeverity {
        CONTAINED,
        MATERIAL,
        SEVERE
    }

    /**
     * The loss severity of the worst NAMED scenario checkpoint. It is deliberately separate from
     * the tail envelope: this answers how much of the exact maximum loss is consumed by the most
     * adverse story tile, so every surface can render the same severity without recomputing it.
     */
    public record WorstScenario(
            boolean severityAvailable,
            Double underlyingMovePct,
            Long pnlCents,
            Long lossCents,
            Long comparisonLossCents,
            String comparisonBasis,
            Double lossSharePct,
            ScenarioSeverity severity,
            String basis,
            String unavailableReason) {}

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
        /** THE one terminal-payoff receipt schema, shared by the candidate profiler and the
         *  held-trade serializer so an idea and the position it becomes speak the same shape. */
        public static final String SCHEMA = "risk-terminal-payoff-1";
        public static final String MODEL = "payoff-curve-1";

        public TerminalPayoff {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }
}
