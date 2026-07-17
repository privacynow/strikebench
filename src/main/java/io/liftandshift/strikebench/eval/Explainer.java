package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;

import java.util.ArrayList;
import java.util.List;

/** Assembles the human case for a recommendation from the candidate + sub-profiles. */
public final class Explainer {

    public Explanation explain(Candidate c, StrategySpec spec, CapitalProfile cap,
                               VolatilityProfile vol, RiskProfile risk, EvidenceProfile evidence,
                               EvalContext ctx) {
        return explain(c, spec, cap, vol, risk, evidence, ctx, null);
    }

    public Explanation explain(Candidate c, StrategySpec spec, CapitalProfile cap,
                               VolatilityProfile vol, RiskProfile risk, EvidenceProfile evidence,
                               EvalContext ctx,
                               io.liftandshift.strikebench.position.ParticipationProfile participation) {
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
        // Historical structure-fit (folded Phase 10.3): the geometry against the lane's own
        // delivered history, always carrying the history-is-not-a-forecast label.
        assumptions.addAll(HistoryFit.sentences(c, ctx));

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
        // Composite-objective lens cautions (income honesty, NAV erosion, concentration) belong
        // with the failure modes: they are ways this trade fights the objective it serves.
        failureModes.addAll(ObjectiveLenses.apply(ctx.declared(), c, ctx).cautions());
        // Regime conditioning (folded Phase 10.3): the trailing trend frames the structure —
        // warnings only, never a re-rank. Down markets widen discounts and fatten call premium;
        // strong up markets make capped, low-participation income structures lag holding shares.
        RegimeSnapshot regime = ctx.regime();
        if (regime != null && regime.trendKnown()) {
            boolean acquiresViaShortPuts = c.legs() != null && c.legs().stream().anyMatch(l ->
                    "PUT".equalsIgnoreCase(l.type()) && "SELL".equalsIgnoreCase(l.action()));
            if (regime.trend() == RegimeSnapshot.Trend.DOWN && acquiresViaShortPuts) {
                failureModes.add(String.format("Regime: %s is down %.0f%% over the last %d sessions — "
                                + "the discount is widening, but assignment odds rise while it falls. The premium "
                                + "is your compensation; judge whether it is enough for a falling market. "
                                + "(Trend heuristic: %s.)",
                        c.legs().isEmpty() ? "the underlying" : spec == null ? "the underlying" : spec.symbol(),
                        Math.abs(regime.trendReturnPct()), regime.trendSessions(), regime.basis()));
            }
            Integer capture = participation == null ? null : participation.terminalUpsideCaptureBps();
            if (regime.trend() == RegimeSnapshot.Trend.UP && regime.trendReturnPct() > 10
                    && c.maxProfitCents() != null && capture != null && capture < 5000) {
                failureModes.add(String.format("Regime: up %.0f%% over %d sessions, and this structure "
                                + "captures only ~%.0f%% of further upside. In a trend like this, capped income "
                                + "lags simply holding shares — deliberate income is fine; accidental capping is "
                                + "regret. (Trend heuristic: %s.)",
                        regime.trendReturnPct(), regime.trendSessions(), capture / 100.0, regime.basis()));
            }
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
