package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.util.Money;

import java.util.ArrayList;
import java.util.List;

/**
 * The economic judgment that sits beside mechanical eligibility. A valid payoff is not
 * automatically a good opportunity: this record keeps unfavorable structures available for
 * learning while preventing payout size or capital efficiency from presenting them as ordinary
 * recommendations.
 */
public record EconomicAssessment(
        Verdict verdict,
        String placement,
        String label,
        String summary,
        Long marketEvAfterCostsCents,
        Long realizedVolEvAfterCostsCents,
        long estimatedRoundTripFeesCents,
        Double marketEvPctOfRisk,
        boolean observedEvidence,
        List<String> reasons
) {
    public enum Verdict {
        FAVORABLE(3), MIXED(2), UNAVAILABLE(1), UNFAVORABLE(0);

        private final int rank;
        Verdict(int rank) { this.rank = rank; }
        public int rank() { return rank; }
    }

    public EconomicAssessment {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public boolean favorable() { return verdict == Verdict.FAVORABLE; }
    public boolean teachingCase() { return verdict == Verdict.UNFAVORABLE; }

    public static EconomicAssessment assess(Candidate c, RiskProfile risk, EvidenceProfile evidence,
                                            ScoreBreakdown score, EvalContext ctx) {
        return assess(c, risk, evidence, ctx, score != null && score.gatePassed(),
                score == null ? List.of() : score.gateFailures(), roundTripFees(c, ctx));
    }

    /** Exact-ticket assessment: mechanical eligibility comes from the trade preview and fees are
     * the preview's actual override/default, not a reconstructed ranking assumption. */
    public static EconomicAssessment assessExact(Candidate c, RiskProfile risk, EvidenceProfile evidence,
                                                  EvalContext ctx, boolean mechanicallyEligible,
                                                  List<String> mechanicalFailures, long roundTripFeesCents) {
        return assess(c, risk, evidence, ctx, mechanicallyEligible, mechanicalFailures,
                Math.max(0, roundTripFeesCents));
    }

    private static EconomicAssessment assess(Candidate c, RiskProfile risk, EvidenceProfile evidence,
                                              EvalContext ctx, boolean mechanicallyEligible,
                                              List<String> mechanicalFailures, long fees) {
        Long marketNet = risk == null || risk.expectedValueCents() == null
                ? null : risk.expectedValueCents() - fees;
        Long realizedNet = risk == null || risk.evHistVolCents() == null
                ? null : risk.evHistVolCents() - fees;
        long maxLoss = risk == null ? 0 : Math.max(0, risk.maxLossCents());
        Double evPct = marketNet == null || maxLoss <= 0
                ? null : 100.0 * marketNet / maxLoss;
        boolean observed = evidence != null && evidence.rollup() != null && evidence.rollup().isObserved();
        List<String> reasons = new ArrayList<>();

        if (!mechanicallyEligible) {
            if (mechanicalFailures != null) reasons.addAll(mechanicalFailures);
            return new EconomicAssessment(Verdict.UNAVAILABLE, "MECHANICALLY_INELIGIBLE",
                    "Cannot assess as a trade",
                    "The package does not pass the mechanical and account checks required for an economic comparison.",
                    marketNet, realizedNet, fees, evPct, observed, reasons);
        }

        if (marketNet == null && realizedNet == null) {
            reasons.add("Neither a market-implied nor a realized-volatility EV lane is available.");
            return new EconomicAssessment(Verdict.UNAVAILABLE, "MECHANICS_ONLY",
                    "Economics unavailable",
                    "You can study the payoff mechanics, but the available data cannot support an economic verdict.",
                    null, null, fees, null, observed, reasons);
        }

        long material = Math.max(100L, Math.round(maxLoss * 0.03));
        boolean marketMateriallyNegative = marketNet != null && marketNet < -material;
        boolean realizedPositive = realizedNet != null && realizedNet > material;
        boolean realizedNegative = realizedNet != null && realizedNet < -material;
        boolean lowProbability = risk != null && risk.pop() != null && risk.pop() < 0.30;

        if (marketNet != null) reasons.add("Market-implied EV after estimated round-trip fees: " + Money.fmt(marketNet) + ".");
        if (realizedNet != null) reasons.add("Realized-volatility scenario EV after estimated round-trip fees: "
                + Money.fmt(realizedNet) + ".");
        if (lowProbability) reasons.add("The modeled chance of any profit is below 30%; low probability is not a rejection by itself.");
        if (!observed) reasons.add("The evidence is generated or modeled, so it cannot establish a live-market edge.");

        // A favorable classification requires a positive realized-volatility scenario that survives
        // fees. Observed evidence determines whether the label can describe a live-market edge or only
        // a favorable case inside an explicitly generated teaching market. The market-implied lane may
        // be mildly negative (the volatility risk premium thesis), but not so negative that execution
        // overwhelms the proposed edge.
        if (realizedPositive && (marketNet == null || marketNet >= -material)) {
            return new EconomicAssessment(Verdict.FAVORABLE, "WORTH_INVESTIGATING",
                    observed ? "Worth investigating" : "Favorable in this teaching market",
                    observed
                            ? "The observed realized-volatility scenario remains positive after estimated costs, and the market-implied lane is not materially adverse."
                            : "In this explicitly generated market, the realized-volatility scenario remains positive after estimated costs. Use it to learn the setup; it is not evidence of a live-market edge.",
                    marketNet, realizedNet, fees, evPct, observed, reasons);
        }

        // Materially negative after-cost economics stay visible, but in a teaching lane. A small
        // POP alone never triggers this classification; it must be accompanied by non-positive EV.
        if ((marketMateriallyNegative && !realizedPositive)
                || (lowProbability && marketNet != null && marketNet < 0 && (realizedNet == null || realizedNet <= 0))
                || realizedNegative) {
            return new EconomicAssessment(Verdict.UNFAVORABLE, "LEARN_FROM",
                    "Unfavorable at these prices",
                    "The package is mechanically valid, but its modeled after-cost economics are adverse. Keep it as a counterexample or re-price it; do not mistake availability for endorsement.",
                    marketNet, realizedNet, fees, evPct, observed, reasons);
        }

        return new EconomicAssessment(Verdict.MIXED, "COMPARE_CAREFULLY",
                "No demonstrated edge yet",
                observed
                        ? "The structure is plausible, but the available models do not show a robust after-cost advantage. Compare it with cash, stock, and alternatives."
                        : "This is useful for learning and comparison, but generated or incomplete evidence cannot support a real-market edge claim.",
                marketNet, realizedNet, fees, evPct, observed, reasons);
    }

    private static long roundTripFees(Candidate c, EvalContext ctx) {
        if (c == null || c.legs() == null || ctx == null) return 0;
        long contracts = c.legs().stream()
                .filter(l -> !"STOCK".equalsIgnoreCase(l.type()))
                .mapToLong(l -> Math.max(1, l.ratio()))
                .sum() * Math.max(1, c.qty());
        return contracts * Math.max(0, ctx.feePerContractCents()) * 2;
    }
}
