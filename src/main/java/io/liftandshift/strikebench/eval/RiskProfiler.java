package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the real payoff-vs-underlying grid (via {@link PayoffCurve}) plus the stressed tail loss.
 * For share-backed candidates the locked shares are folded in as a synthetic stock leg valued at
 * today's price, so the scenarios reflect the COMBINED position the trader actually holds.
 */
public final class RiskProfiler {

    private static final double[] MOVES = {-0.20, -0.10, -0.05, 0.0, 0.05, 0.10, 0.20};
    private static final double TAIL_MOVE = 0.20;

    public RiskProfile profile(Candidate c, EvalContext ctx) {
        long maxLoss = Math.max(0, c.combinedMaxLossCents() != null
                ? c.combinedMaxLossCents() : c.maxLossCents());
        Long maxProfit = c.maxProfitCents();

        List<RiskProfile.Scenario> scenarios = new ArrayList<>();
        long worstPnl = 0;
        boolean have = false;
        try {
            CurveInput input = curveInput(c, ctx);
            PayoffCurve pc = PayoffCurve.of(input.legs(), Math.max(1, c.qty()), input.entryAdjustmentCents());
            BigDecimal spot = cents(ctx.underlyingCents());
            for (double m : MOVES) {
                BigDecimal s = spot.multiply(BigDecimal.valueOf(1.0 + m));
                long pnl = pc.profitAtCents(s);
                scenarios.add(new RiskProfile.Scenario(m, pnl));
                worstPnl = have ? Math.min(worstPnl, pnl) : pnl;
                have = true;
            }
        } catch (RuntimeException e) {
            // Degrade to extremes-only rather than fail the whole evaluation.
        }
        long tailLoss = have ? Math.max(0, -worstPnl) : maxLoss;
        // The HISTORICAL-VOL SCENARIO lane: the same EV integral at REALIZED volatility (zero
        // drift). When implied >> realized, market-implied EV of short premium is negative while
        // this scenario EV is positive — that gap IS the volatility risk premium, and the two
        // numbers must never be blended into one.
        Long evHistVol = null;
        String basisNote = null;
        long distinctExpirations = c.legs() == null ? 0 : c.legs().stream()
                .filter(l -> l.expiration() != null && !l.expiration().isBlank())
                .map(LegView::expiration).distinct().count();
        if (distinctExpirations <= 1
                && ctx.realizedVol30() != null && ctx.realizedVol30() > 0 && ctx.underlyingCents() > 0
                && ctx.daysToExpiry() > 0 && c.legs() != null && !c.legs().isEmpty()) {
            try {
                CurveInput input = curveInput(c, ctx);
                PayoffCurve ppc = PayoffCurve.of(input.legs(), Math.max(1, c.qty()), input.entryAdjustmentCents());
                double t = ctx.daysToExpiry() / 365.0;
                evHistVol = ppc.expectedValueCents(ctx.underlyingCents() / 100.0, ctx.realizedVol30(), t, 0);
                basisNote = "expectedValueCents = market-implied (IV, risk-neutral); evHistVolCents = realized-vol "
                        + Math.round(ctx.realizedVol30() * 100) + "% (physical lane, zero drift). Both pre-commission.";
            } catch (RuntimeException ignored) { /* lane stays honestly null */ }
        } else if (distinctExpirations > 1) {
            basisNote = "EV lanes are unavailable for multi-expiration structures in the single-terminal model; use the strategy simulator's two-expiry path valuation.";
        }
        return new RiskProfile(maxLoss, maxProfit, c.pop(), c.expectedValueCents(), tailLoss, TAIL_MOVE,
                scenarios, evHistVol, basisNote);
    }

    /**
     * Builds the exact package curve once for every risk lane. The package-level entry is
     * authoritative (a user's limit/fill need not equal the sum of executable leg marks), while
     * held-share candidates add one stock lot PER package unit. {@code sharesNeeded} is the total
     * across quantity, so using it directly as a leg ratio would multiply quantity twice.
     */
    private static CurveInput curveInput(Candidate c, EvalContext ctx) {
        int qty = Math.max(1, c.qty());
        List<Leg> optionPackage = new ArrayList<>(c.legs().stream().map(LegView::toLeg).toList());
        long markedEntry = PayoffCurve.of(optionPackage, qty).entryNetPremiumCents();
        long entryAdjustment = c.entryNetPremiumCents() - markedEntry;
        List<Leg> combined = new ArrayList<>(optionPackage);
        if (Boolean.TRUE.equals(c.usesHeldShares()) && c.sharesNeeded() != null && c.sharesNeeded() > 0) {
            int lotsPerUnit = Math.max(1, c.sharesNeeded() / (Leg.SHARES_PER_CONTRACT * qty));
            combined.add(Leg.stock(LegAction.BUY, lotsPerUnit, cents(ctx.underlyingCents())));
        }
        return new CurveInput(combined, entryAdjustment);
    }

    private record CurveInput(List<Leg> legs, long entryAdjustmentCents) {}

    private static BigDecimal cents(long c) { return BigDecimal.valueOf(c).movePointLeft(2); }
}
