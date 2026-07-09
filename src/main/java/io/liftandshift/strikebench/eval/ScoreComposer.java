package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a candidate + its sub-profiles into an explainable score: a hard GATE, a weighted NORMALIZE
 * over named 0..1 components, then a RISK-ADJUST haircut by evidence uncertainty and tail risk. The
 * single number never travels without this breakdown.
 */
public final class ScoreComposer {

    public ScoreBreakdown compose(Candidate c, CapitalProfile cap, RiskProfile risk,
                                  EvidenceProfile evidence, EvalContext ctx) {
        // ---- GATE: hard validity ----
        List<String> gateFailures = new ArrayList<>();
        if (risk.maxLossCents() <= 0) gateFailures.add("no finite, positive max loss (cannot be risk-screened)");
        if (evidence.rollup() == EvidenceLevel.UNKNOWN) gateFailures.add("evidence unknown for a required dimension");
        if (cap.incrementalCents() > ctx.buyingPowerCents())
            gateFailures.add("insufficient buying power ($" + dollars(cap.incrementalCents())
                    + " needed vs $" + dollars(ctx.buyingPowerCents()) + ")");
        boolean gatePassed = gateFailures.isEmpty();

        // ---- NORMALIZE: weighted named components ----
        List<ScoreBreakdown.Component> comps = new ArrayList<>();
        double pop = c.pop() != null ? clamp01(c.pop()) : 0.5;
        comps.add(comp("Probability of profit", 0.20, pop, c.pop() == null ? "model-dependent — assumed neutral" : "lognormal model"));

        double rr;
        String rrNote;
        if (c.maxProfitCents() != null && risk.maxLossCents() > 0) {
            double ratio = (double) c.maxProfitCents() / risk.maxLossCents();
            rr = ratio / (ratio + 1.0); // 1:1 -> .5, 3:1 -> .75
            rrNote = String.format("reward:risk %.2f:1", ratio);
        } else { rr = 0.8; rrNote = "uncapped/model-dependent upside"; }
        comps.add(comp("Reward vs risk", 0.20, rr, rrNote));

        comps.add(comp("Liquidity", 0.10, clamp01(c.liquidityScore()), "tighter spreads / more open interest score higher"));

        double capComp;
        String capNote;
        if (cap.returnOnCapitalPct() != null) {
            capComp = clamp01(cap.returnOnCapitalPct() / (cap.returnOnCapitalPct() + 50.0));
            capNote = String.format("%.0f%% best-case return on economic capital", cap.returnOnCapitalPct());
        } else { capComp = 0.4; capNote = "return on capital not defined"; }
        comps.add(comp("Capital efficiency", 0.15, capComp, capNote));

        double evComp = 1.0 - evidence.rollup().uncertainty() / 5.0; // LIVE=1.0 ... DEMO=0.2, UNKNOWN=0.0
        comps.add(comp("Evidence quality", 0.15, evComp, evidence.rollup().label()));

        comps.add(comp("Thesis confidence", 0.20, clamp01(c.confidence()), "engine confidence in the fit"));

        double weighted = 0, weight = 0;
        for (var k : comps) { weighted += k.contribution(); weight += k.weight(); }
        double normalized = weight > 0 ? 100.0 * weighted / weight : 0.0;

        // ---- RISK-ADJUST: haircut by evidence + tail ----
        double evidenceMult = 0.5 + 0.5 * evComp;                 // demo caps ~0.6, live keeps 1.0
        double tailRatio = cap.economicCents() > 0
                ? Math.min(1.0, (double) risk.tailLossCents() / cap.economicCents()) : 0.0;
        double tailMult = 1.0 - 0.2 * tailRatio;                  // mild penalty for a large tail vs capital
        double riskAdjusted = gatePassed ? clamp(normalized * evidenceMult * tailMult, 0, 100) : 0.0;

        return new ScoreBreakdown(gatePassed, gateFailures, round(normalized), round(riskAdjusted), comps);
    }

    private static ScoreBreakdown.Component comp(String name, double weight, double value, String note) {
        return new ScoreBreakdown.Component(name, weight, round(value), round(weight * value), note);
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
    private static long dollars(long cents) { return cents / 100; }
}
