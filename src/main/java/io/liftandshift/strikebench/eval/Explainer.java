package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;

import java.util.ArrayList;
import java.util.List;

/** Assembles the human case for a recommendation from the candidate + sub-profiles. */
public final class Explainer {

    public Explanation explain(Candidate c, StrategySpec spec, CapitalProfile cap,
                               VolatilityProfile vol, RiskProfile risk, EvidenceProfile evidence,
                               EvalContext ctx) {
        String headline = c.whyConsidered() != null && !c.whyConsidered().isBlank()
                ? c.whyConsidered()
                : c.displayName() + " for a " + safe(spec == null ? null : spec.intent()) + " goal";

        List<String> assumptions = new ArrayList<>();
        assumptions.add(String.format("POP and market EV use the shared risk-neutral lognormal approximation "
                + "(r=%.2f%%, q=0 assumed); market EV is present-valued. Breakevens are payoff geometry. Raw model outputs "
                + "exclude commissions; an EV explicitly labeled after costs subtracts the disclosed estimated round-trip commissions.",
                ctx.riskFreeRate() * 100));
        assumptions.add(evidence.note());
        if (vol.ivRankPct() == null && vol.atmIv() != null) {
            assumptions.add("IV rank/percentile unavailable yet — needs more days of recorded snapshots.");
        }
        if (cap.annualizedRocPct() != null) {
            assumptions.add("Annualized return assumes you could repeat this trade every "
                    + cap.daysToExpiry() + " days — it is a comparison aid, not a forecast.");
        }

        List<String> failureModes = new ArrayList<>();
        if (c.entryNetPremiumCents() < 0) {
            failureModes.add("The move doesn't happen in time — theta erodes the debit.");
            failureModes.add("Implied vol falls after entry (IV crush), shrinking the option's value.");
        } else {
            failureModes.add("The underlying moves through your short strike, toward max loss.");
            failureModes.add("A volatility spike widens spreads and marks the position against you.");
        }
        if (c.assignmentProb() != null && c.assignmentProb() > 0.5) {
            failureModes.add("Assignment is more likely than not — be ready to manage the shares.");
        }
        if (evidence.rollup() == EvidenceLevel.DEMO_FIXTURE) {
            failureModes.add("These numbers are DEMO data — not tradeable prices.");
        }

        return new Explanation(
                headline,
                c.whyConsidered(),
                c.bestUpside(),
                c.biggestRisk(),
                c.wouldInvalidate(),
                assumptions,
                failureModes,
                c.beginnerExplanation());
    }

    private static String safe(String s) { return s == null ? "trading" : s.toLowerCase(java.util.Locale.ROOT); }
}
