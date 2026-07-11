package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;

import java.util.ArrayList;
import java.util.List;

/**
 * THE DecisionPolicy: the one scorer behind every ranked surface (manual ideas, /api/recommend
 * decision ordering, intent ladders, opportunity scans, the Decision page). A hard GATE, a
 * weighted NORMALIZE over named 0..1 components — EXPECTED VALUE included as primary economics —
 * then a RISK-ADJUST haircut by evidence uncertainty, tail risk and gamma/DTE concentration. The
 * single number never travels without this breakdown, and negative-EV packages are never ranked
 * as if the market were paying them.
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
        // EXECUTABLE MARKET: a structure sold as a credit must actually EARN a credit at the
        // executable sides — a "credit spread" that pays nothing (or costs money) after crossing
        // the books is the book telling you it cannot be traded politely this week.
        String fam = c.strategy() == null ? "" : c.strategy();
        boolean creditFamily = fam.contains("CREDIT") || fam.startsWith("IRON");
        if (creditFamily && c.entryNetPremiumCents() <= 0) {
            gateFailures.add("a credit structure that pays nothing at executable sides — the book is too wide to earn a credit");
        }
        boolean gatePassed = gateFailures.isEmpty();

        // ---- NORMALIZE: weighted named components ----
        List<ScoreBreakdown.Component> comps = new ArrayList<>();
        double pop = c.pop() != null ? clamp01(c.pop()) : 0.5;
        comps.add(comp("Probability of profit", 0.10, pop, c.pop() == null ? "model-dependent — assumed neutral" : "lognormal model"));

        double rr;
        String rrNote;
        if (c.maxProfitCents() != null && risk.maxLossCents() > 0) {
            double ratio = (double) c.maxProfitCents() / risk.maxLossCents();
            rr = ratio / (ratio + 1.0); // 1:1 -> .5, 3:1 -> .75
            rrNote = String.format("reward:risk %.2f:1", ratio);
        } else { rr = 0.8; rrNote = "uncapped/model-dependent upside"; }
        comps.add(comp("Reward vs risk", 0.08, rr, rrNote));

        // EXPECTED VALUE is the primary economics: POP and reward:risk are its ingredients, and
        // scoring them separately without EV let a low-POP/high-payout package double-dip (the MU
        // condor scored 0.34 POP but 0.58 reward:risk while its EV was decidedly negative).
        double evComp;
        String evNote;
        Long ev = risk.expectedValueCents();
        if (ev != null && risk.maxLossCents() > 0) {
            // R9: judged NET of round-trip commissions — a thin edge that fees eat is no edge.
            // Contract count matches what the ledger actually charges: OPTION legs only —
            // commissions on a buy-write's stock leg were a phantom tax on hedged structures.
            long contracts = c.legs() == null ? 0
                    : c.legs().stream().filter(l -> !"STOCK".equalsIgnoreCase(l.type()))
                        .mapToLong(l -> Math.max(1, l.ratio())).sum() * Math.max(1, c.qty());
            long costs = contracts * ctx.feePerContractCents() * 2; // open + close
            long evNet = ev - costs;
            evComp = clamp01(0.5 + (double) evNet / (2.0 * risk.maxLossCents())); // -maxLoss -> 0, 0 -> .5, +maxLoss -> 1
            evNote = String.format("model EV $%s net of ~$%s round-trip fees, vs max loss $%s (risk-neutral)",
                    dollars(evNet), dollars(costs), dollars(risk.maxLossCents()));
        } else { evComp = 0.5; evNote = "EV not computable — assumed neutral"; }
        comps.add(comp("Expected value", 0.35, evComp, evNote));

        comps.add(comp("Liquidity", 0.12, clamp01(c.liquidityScore()), "tighter spreads / more open interest score higher"));

        double capComp;
        String capNote;
        if (cap.returnOnCapitalPct() != null) {
            capComp = clamp01(cap.returnOnCapitalPct() / (cap.returnOnCapitalPct() + 50.0));
            capNote = String.format("%.0f%% best-case return on economic capital", cap.returnOnCapitalPct());
        } else { capComp = 0.4; capNote = "return on capital not defined"; }
        comps.add(comp("Capital efficiency", 0.05, capComp, capNote));

        double evidComp = 1.0 - evidence.rollup().uncertainty() / 5.0;
        comps.add(comp("Evidence quality", 0.15, evidComp, evidence.rollup().label()));

        comps.add(comp("Thesis confidence", 0.15, clamp01(c.confidence()), "engine confidence in the fit"));

        double weighted = 0, weight = 0;
        for (var k : comps) { weighted += k.contribution(); weight += k.weight(); }
        double normalized = weight > 0 ? 100.0 * weighted / weight : 0.0;

        // ---- RISK-ADJUST: haircut by evidence + tail + gamma/DTE concentration ----
        double evidenceMult = 0.5 + 0.5 * evidComp;               // demo caps ~0.6, live keeps 1.0
        double tailRatio = cap.economicCents() > 0
                ? Math.min(1.0, (double) risk.tailLossCents() / cap.economicCents()) : 0.0;
        double tailMult = 1.0 - 0.35 * tailRatio;                 // a full-tail structure loses a third
        // Near-expiry positions concentrate gamma and remove the time to be wrong — the same
        // numbers three sessions from expiry are a different (worse) trade.
        double dteMult = ctx.daysToExpiry() <= 3 ? 0.8 : ctx.daysToExpiry() <= 7 ? 0.9 : 1.0;
        double riskAdjusted = gatePassed ? clamp(normalized * evidenceMult * tailMult * dteMult, 0, 100) : 0.0;

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
