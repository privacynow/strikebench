package io.liftandshift.strikebench.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The FULL probability picture of a position at expiration — not just "POP". All probabilities are
 * RISK-NEUTRAL (lognormal terminal distribution at the given sigma, zero drift): they are the
 * options market's own odds, not a physical forecast, and every consumer must label them that way.
 *
 * Computed by numeric integration of the terminal density against the exact piecewise-linear
 * payoff (PayoffCurve), so it is correct for every strategy shape — spreads, condors, flies,
 * covered structures, uncapped single legs — without per-family case analysis.
 */
public final class ProbabilityMap {

    /** Probabilities in [0,1]; cvar95Cents is the EXPECTED P/L in the worst 5% of outcomes. */
    public record Result(double pAnyProfit, double pMaxProfit, double pMaxLoss, double pPartial,
                         long cvar95Cents, long stressLossCents,
                         List<Touch> touches, String basis) {}

    /** Probability the underlying TOUCHES the strike before expiry (≈ 2x the expire-beyond odds). */
    public record Touch(BigDecimal strike, double probability) {}

    private static final int GRID = 800;          // integration resolution in log-space
    private static final double SPAN_SIGMAS = 5;  // integrate over ±5σ of terminal distribution
    private static final double PLATEAU_EPS = 0.005; // within 0.5% of the extreme counts as "at" it

    private ProbabilityMap() {}

    /**
     * @param curve       exact payoff
     * @param spot        current underlying
     * @param sigma       annualized volatility (the chain's own IV — risk-neutral basis)
     * @param tYears      time to expiry in YEARS; callers should pass trading-day time for
     *                    short-dated positions (sessions/252), calendar time otherwise
     * @param shortStrikes strikes of SHORT legs (touch/assignment interest); may be empty
     */
    public static Result of(PayoffCurve curve, double spot, double sigma, double tYears,
                            List<BigDecimal> shortStrikes) {
        if (spot <= 0 || sigma <= 0 || tYears <= 0) {
            return new Result(0, 0, 0, 0, 0, 0, List.of(), "undefined (missing spot/vol/time)");
        }
        double sd = sigma * Math.sqrt(tYears);
        long maxProfit = curve.maxProfitUnbounded() ? Long.MAX_VALUE : curve.maxProfitCents();
        long maxLoss = curve.maxLossUnbounded() ? Long.MAX_VALUE : curve.maxLossCents();

        // Numeric integration over the lognormal terminal density in log-space (zero drift,
        // martingale form: ln S_T ~ N(ln spot - sd^2/2, sd^2)).
        double mu = Math.log(spot) - sd * sd / 2;
        double pAny = 0, pMaxP = 0, pMaxL = 0;
        double[] pl = new double[GRID];
        double[] w = new double[GRID];
        double lo = mu - SPAN_SIGMAS * sd, hi = mu + SPAN_SIGMAS * sd, step = (hi - lo) / GRID;
        double wsum = 0;
        for (int i = 0; i < GRID; i++) {
            double x = lo + (i + 0.5) * step;                       // midpoint rule
            double z = (x - mu) / sd;
            double dens = Math.exp(-z * z / 2);                     // unnormalized; normalized by wsum
            double s = Math.exp(x);
            long p = curve.profitAtCents(BigDecimal.valueOf(s));
            pl[i] = p; w[i] = dens; wsum += dens;
            if (p > 0) pAny += dens;
            if (maxProfit != Long.MAX_VALUE && p >= maxProfit * (1 - PLATEAU_EPS) - 1) pMaxP += dens;
            if (maxLoss != Long.MAX_VALUE && maxLoss > 0 && -p >= maxLoss * (1 - PLATEAU_EPS) - 1) pMaxL += dens;
        }
        pAny /= wsum; pMaxP /= wsum; pMaxL /= wsum;
        double pPartial = Math.max(0, 1 - pAny - pMaxL);

        // CVaR95: expected P/L over the worst 5% of probability mass (sorted by P/L).
        Integer[] idx = new Integer[GRID];
        for (int i = 0; i < GRID; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(pl[a], pl[b]));
        double tailMass = 0.05 * wsum, acc = 0, cvarNum = 0;
        for (int k = 0; k < GRID && acc < tailMass; k++) {
            double take = Math.min(w[idx[k]], tailMass - acc);
            cvarNum += pl[idx[k]] * take;
            acc += take;
        }
        long cvar95 = acc > 0 ? Math.round(cvarNum / acc) : 0;

        // Stress loss: the worse of -20% and -2σ (and the call-side mirror) — the honest number
        // for undefined-risk structures where "max loss" is a lie.
        long stress = Math.min(
                Math.min(curve.profitAtCents(BigDecimal.valueOf(spot * 0.80)),
                         curve.profitAtCents(BigDecimal.valueOf(spot * 1.20))),
                Math.min(curve.profitAtCents(BigDecimal.valueOf(spot * Math.exp(-2 * sd))),
                         curve.profitAtCents(BigDecimal.valueOf(spot * Math.exp(2 * sd)))));

        List<Touch> touches = new ArrayList<>();
        for (BigDecimal k : shortStrikes == null ? List.<BigDecimal>of() : shortStrikes) {
            double kk = k.doubleValue();
            if (kk <= 0) continue;
            // P(expire beyond K) one-sided, doubled for touch (reflection heuristic), capped at 1.
            double d = (Math.log(kk / spot) - (-sd * sd / 2)) / sd;
            double beyond = kk >= spot ? 1 - normCdf(d) : normCdf(d);
            touches.add(new Touch(k, Math.min(1.0, 2 * beyond)));
        }
        return new Result(round4(pAny), round4(pMaxP), round4(pMaxL), round4(pPartial),
                cvar95, Math.min(0, stress), touches,
                "risk-neutral lognormal (market IV, zero drift) — the options market's own odds, not a forecast");
    }

    private static double normCdf(double z) {
        return 0.5 * (1 + erf(z / Math.sqrt(2)));
    }

    private static double erf(double x) {
        // Abramowitz–Stegun 7.1.26 (|err| < 1.5e-7) — same kernel family as BlackScholes.normCdf.
        double t = 1 / (1 + 0.3275911 * Math.abs(x));
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
                + 0.254829592) * t * Math.exp(-x * x);
        return x >= 0 ? y : -y;
    }

    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
