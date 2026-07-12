package io.liftandshift.strikebench.eval;

import java.util.List;

/**
 * How the single 0–100 score was built, so it never stands alone. Three stages:
 *  1. GATE — hard validity checks (finite risk, live-enough data, buying power). A failed gate
 *     disqualifies the candidate regardless of an attractive score.
 *  2. NORMALIZE — weighted, named components each in 0..1, combined to a 0..100 raw score.
 *  3. RISK-ADJUST — the raw score haircut by evidence uncertainty and tail risk. This is the
 *     within-economic-tier quality score; {@link StrategyEvaluation#decisionScore()} adds the
 *     mechanical/economic ordering bands used by every final ranked surface.
 * Produced by {@code ScoreComposer}.
 */
public record ScoreBreakdown(
        boolean gatePassed,
        List<String> gateFailures,
        double normalizedScore,   // 0..100 before risk adjustment
        double riskAdjustedScore, // 0..100 quality within an economic tier (0 when gate fails)
        List<Component> components
) {
    public ScoreBreakdown {
        gateFailures = gateFailures == null ? List.of() : List.copyOf(gateFailures);
        components = components == null ? List.of() : List.copyOf(components);
    }

    /** One named contribution: raw 0..1 value, its weight, and weight*value. */
    public record Component(String name, double weight, double value, double contribution, String note) {}
}
