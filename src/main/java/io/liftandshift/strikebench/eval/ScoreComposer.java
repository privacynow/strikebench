package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.strategy.StrategyCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * THE DecisionPolicy: the one scorer behind every ranked surface (Plan Strategy, Research Scout,
 * decision ordering, intent ladders, and opportunity scans). A hard GATE, a
 * weighted NORMALIZE over named 0..1 components — realistic-measure expected value included as
 * primary economics while market-implied EV remains a separate cost disclosure —
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
        if (cap.incrementalCents() == null) {
            gateFailures.add("capital use is unavailable — "
                    + (c.capital().unavailableReason() == null
                    ? "the exact package has no complete capital result"
                    : c.capital().unavailableReason()));
        } else if (cap.incrementalCents() > ctx.buyingPowerCents())
            gateFailures.add("insufficient buying power ($" + dollars(cap.incrementalCents())
                    + " needed vs $" + dollars(ctx.buyingPowerCents()) + ")");
        // §3.2: an unpriced package is not a cheap package — it is an unknown one, and the gate is
        // exactly where "we cannot judge this" must stop being confused with "this is fine". The
        // absence is stated as its own failure so the surface can name what is missing, and the
        // credit test below is skipped rather than reading a null as "pays nothing".
        String unpriced = RiskProfiler.unpricedReason(c);
        if (unpriced != null) {
            gateFailures.add("this package has no price, so it cannot be screened — " + unpriced);
        }
        // EXECUTABLE MARKET: a structure sold as a credit must actually EARN a credit at the
        // executable sides — a "credit spread" that pays nothing (or costs money) after crossing
        // the books is the book telling you it cannot be traded politely this week.
        String fam = c.strategy() == null ? "" : c.strategy();
        boolean creditFamily = fam.contains("CREDIT") || fam.startsWith("IRON");
        if (unpriced == null && creditFamily && c.price().grossPackageNetCents() <= 0) {
            gateFailures.add("a credit structure that pays nothing at executable sides — the book is too wide to earn a credit");
        }
        boolean gatePassed = gateFailures.isEmpty();

        // ---- NORMALIZE: weighted named components ----
        List<ScoreBreakdown.Component> comps = new ArrayList<>();
        Double reportedPop = c.marketImpliedRisk().pop();
        double pop = reportedPop != null ? clamp01(reportedPop) : 0.5;
        comps.add(comp("Probability of profit", 0.10, pop,
                reportedPop == null ? "model-dependent — assumed neutral" : "lognormal model"));

        double rr;
        String rrNote;
        if (c.maxProfitCents() != null && risk.maxLossCents() > 0) {
            double ratio = (double) c.maxProfitCents() / risk.maxLossCents();
            rr = ratio / (ratio + 1.0); // 1:1 -> .5, 3:1 -> .75
            rrNote = String.format("reward:risk %.2f:1", ratio);
        } else if (StrategyCatalog.family(c.strategy()) != null
                && StrategyCatalog.family(c.strategy()).multiExpiration()) {
            rr = 0.5;
            rrNote = "reward unavailable until the multi-expiration path outcome is valued; neutral score";
        } else {
            rr = 0.8;
            rrNote = "uncapped upside";
        }
        comps.add(comp("Reward vs risk", 0.08, rr, rrNote));

        // EXPECTED VALUE is the primary economics, but the market-implied mode is not an edge
        // forecast: at executable prices under the market's own risk-neutral measure it mostly
        // restates spread and fees. Rank within an economic tier by the observed-history
        // realistic-measure mode when available; missing history is neutral, never silently
        // replaced by the risk-neutral cost benchmark.
        double evComp;
        String evNote;
        Long ev = risk.evHistVolCents();
        if (ev != null && risk.maxLossCents() > 0) {
            // R9: judged NET of the exact captured package commission. A missing fee is not a free
            // round trip and cannot become an expected-value advantage in the ranker.
            Long costs = EconomicAssessment.roundTripFees(c);
            if (costs == null) {
                evComp = 0.5;
                evNote = "after-cost expected value unavailable — the package price result states no commission";
            } else {
                long evNet = ev - costs;
                long scale = EconomicAssessment.realisticPayoffScaleCents(c, risk, ctx);
                evComp = clamp01(0.5 + (double) evNet / (2.0 * scale));
                evNote = String.format("realized-volatility scenario EV $%s net of ~$%s round-trip fees, vs structure payoff scale $%s; market-implied EV is disclosed separately as a cost benchmark",
                        dollars(evNet), dollars(costs), dollars(scale));
            }
        } else {
            evComp = 0.5;
            evNote = "realistic-measure EV unavailable — neutral score; market-implied cost disclosure was not substituted";
        }
        comps.add(comp("Expected value", 0.35, evComp, evNote));

        comps.add(comp("Liquidity", 0.12, clamp01(c.liquidityScore()), "tighter spreads / more open interest score higher"));

        double capComp;
        String capNote;
        if (cap.returnOnCapitalPct() != null) {
            capComp = clamp01(cap.returnOnCapitalPct() / (cap.returnOnCapitalPct() + 50.0));
            capNote = String.format("%.0f%% best-case return on economic exposure", cap.returnOnCapitalPct());
        } else { capComp = 0.4; capNote = "return on capital not defined"; }
        comps.add(comp("Capital efficiency", 0.05, capComp, capNote));

        // Missing evidence is a data limitation, not a payoff or account failure. Keep the
        // package visible for comparison, but give UNKNOWN no evidence-quality credit and let
        // EconomicAssessment name the unavailable mode instead of calling it mechanical.
        // Divisor tracks the tier count: LIVE 1.0 … OBSERVED_STALE 0.5 … DEMO/UNKNOWN 0, so an
        // observed-but-stale book keeps more evidence credit than any generated input.
        double evidComp = clamp01(1.0 - evidence.rollup().uncertainty() / 6.0);
        comps.add(comp("Evidence quality", 0.15, evidComp, evidence.rollup().label()));

        comps.add(comp("Thesis confidence", 0.15, clamp01(c.confidence()), "engine confidence in the fit"));

        // ---- OBJECTIVE LENS: the declared assignment preference reweights every
        // assignment-bearing structure. ACCEPT declares indifference and adds no component;
        // structures with no short legs have nothing to be assigned on and are never touched.
        DeclaredObjective declared = ctx.declared();
        String pref = declared == null ? null : declared.assignmentPreference();
        if (pref != null && !"ACCEPT".equals(pref) && c.shortSideExpirationItmProb() != null) {
            double p = clamp01(c.shortSideExpirationItmProb());
            int pct = (int) Math.round(p * 100);
            boolean acquires = acquiresShares(c);
            double fit;
            String note;
            switch (pref) {
                case "AVOID" -> {
                    fit = 1.0 - p;
                    note = "you declared: avoid assignment — the short side has " + pct
                            + "% modeled expiration-ITM odds. This is a fit proxy, not an "
                            + "early-assignment probability";
                }
                case "SEEK" -> {
                    fit = p;
                    note = "you declared: seek assignment — " + pct
                            + "% modeled expiration-ITM odds count in this structure's favor. "
                            + "This is a fit proxy, not an assignment guarantee";
                }
                case "PREFER_BELOW_BASIS" -> {
                    fit = acquires ? p : 1.0 - p;
                    note = acquires
                            ? "you declared: welcome assignment that adds shares below your basis — "
                                + pct + "% modeled expiration-ITM odds are the available proxy; "
                                + "actual assignment can differ"
                            : "you declared: welcome assignment only when it ADDS shares — this one would "
                                + "sell yours (" + pct + "% modeled expiration-ITM odds), so higher "
                                + "odds score lower";
                }
                default -> { fit = 0.5; note = "unrecognized assignment preference — treated as neutral"; }
            }
            comps.add(comp("Assignment-intent fit", 0.10, fit, note));
        }
        // Composite-objective lenses (registry-driven — see ObjectiveLenses): additional named
        // components for declared composites like income-while-accumulating.
        for (var lensComponent : ObjectiveLenses.apply(declared, c, ctx).components()) {
            comps.add(comp(lensComponent.name(), lensComponent.weight(), lensComponent.value(),
                    lensComponent.note()));
        }

        double weighted = 0, weight = 0;
        for (var k : comps) { weighted += k.contribution(); weight += k.weight(); }
        double normalized = weight > 0 ? 100.0 * weighted / weight : 0.0;

        // ---- RISK-ADJUST: haircut by evidence + tail + gamma/DTE concentration ----
        double evidenceMult = 0.5 + 0.5 * evidComp;               // demo caps ~0.6, live keeps 1.0
        double tailRatio = cap.economicCents() != null && cap.economicCents() > 0
                ? Math.min(1.0, (double) risk.tailLossCents() / cap.economicCents()) : 0.0;
        double tailMult = 1.0 - 0.35 * tailRatio;                 // a full-tail structure loses a third
        // Near-expiry positions concentrate gamma and remove the time to be wrong — the same
        // numbers three sessions from expiry are a different (worse) trade.
        int urgencySessions = ctx.tradingSessionsToExpiry() >= 0
                ? ctx.tradingSessionsToExpiry() : ctx.calendarDaysToExpiry();
        double dteMult = urgencySessions <= 3 ? 0.8 : urgencySessions <= 7 ? 0.9 : 1.0;
        double riskAdjusted = gatePassed ? clamp(normalized * evidenceMult * tailMult * dteMult, 0, 100) : 0.0;

        return new ScoreBreakdown(gatePassed, gateFailures, round(normalized), round(riskAdjusted), comps);
    }

    /** Whether this structure's assignment would BUY shares (short puts) rather than sell held ones. */
    private static boolean acquiresShares(Candidate c) {
        if ("ACQUIRE".equalsIgnoreCase(c.intent())) return true;
        if ("EXIT".equalsIgnoreCase(c.intent())) return false;
        return c.legs() != null && c.legs().stream().anyMatch(l ->
                "PUT".equalsIgnoreCase(l.type()) && "SELL".equalsIgnoreCase(l.action()));
    }

    private static ScoreBreakdown.Component comp(String name, double weight, double value, String note) {
        double normalized = clamp01(value);
        return new ScoreBreakdown.Component(name, weight, round(normalized), round(weight * normalized), note);
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
    private static long dollars(long cents) { return cents / 100; }
}
