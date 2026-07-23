package io.liftandshift.strikebench.pricing;
import static io.liftandshift.strikebench.util.Numbers.round4;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The FULL probability picture of a position at expiration — not just "POP". Probabilities are
 * RISK-NEUTRAL: a lognormal terminal distribution at the chain's own IV with drift {@code r − q − σ²/2}
 * (the same risk-free rate the options are priced with), so the numbers are the options market's
 * own q=0 approximation, not a physical forecast — every consumer must label them that way.
 *
 * <p>Regions are EXACT, not sampled (adversarial-review correction): the payoff is piecewise
 * linear, so profit / max-profit / max-loss regions are derived from the curve's own knots and
 * breakevens and integrated as closed-form lognormal CDF differences. A single-point maximum
 * (e.g. a butterfly's pin) honestly has probability 0 of exact attainment. Only CVaR keeps a
 * numeric grid (the worst-5% set is not an interval in S); the touch statistic is an explicitly
 * labeled reflection ESTIMATE (≈2× the expire-beyond odds), not a barrier model.
 */
public final class ProbabilityMap {

    /** Probabilities in [0,1]; cvar95Cents is the EXPECTED P/L in the worst 5% of outcomes. */
    public record Result(double pAnyProfit, double pMaxProfit, double pMaxLoss, double pPartial,
                         long cvar95Cents, long stressLossCents,
                         List<Touch> touches, String basis) {}

    /** Probability the underlying TOUCHES the strike before expiry (reflection estimate). */
    public record Touch(BigDecimal strike, double probability) {}

    private static final int CVAR_GRID = 800;        // numeric grid for CVaR only
    private static final double SPAN_SIGMAS = 6;

    private ProbabilityMap() {}

    /**
     * @param curve        exact payoff (includes any package-level price adjustment)
     * @param spot         current underlying
     * @param sigma        annualized volatility (the chain's own IV — risk-neutral basis)
     * @param tYears       time to expiry in calendar years (ACT/365, matching listed-option IV convention)
     * @param riskFreeRate annualized r used in the risk-neutral drift (r − q − σ²/2), with q=0
     * @param shortStrikes strikes of SHORT legs (touch/assignment interest); may be empty
     */
    public static Result of(PayoffCurve curve, double spot, double sigma, double tYears,
                            double riskFreeRate, List<BigDecimal> shortStrikes) {
        if (spot <= 0 || sigma <= 0 || tYears <= 0) {
            return new Result(0, 0, 0, 0, 0, 0, List.of(), "undefined (missing spot/vol/time)");
        }
        LognormalTerminal term = LognormalTerminal.of(spot, sigma, tYears, riskFreeRate);
        double sd = term.sd();
        double mu = term.mu();

        long maxProfit = curve.maxProfitUnbounded() ? Long.MAX_VALUE : curve.maxProfitCents();
        long maxLoss = curve.maxLossUnbounded() ? Long.MAX_VALUE : curve.maxLossCents();

        // ---- EXACT region probabilities from the curve's own geometry ----
        // Boundary set: 0, every knot, every breakeven, +inf. On each open interval the payoff is
        // linear, so its sign and its equality-to-extreme are decided by the endpoint values.
        List<Double> bounds = new ArrayList<>();
        bounds.add(0.0);
        for (BigDecimal k : curve.knots()) bounds.add(k.doubleValue());
        for (BigDecimal b : curve.breakevens()) bounds.add(b.doubleValue());
        bounds.add(spot * Math.exp(SPAN_SIGMAS * sd) * 4); // effective +inf for the closed forms
        bounds = new ArrayList<>(bounds.stream().filter(x -> x >= 0).sorted().distinct().toList());

        double pAny = 0, pMaxP = 0, pMaxL = 0;
        for (int i = 0; i + 1 < bounds.size(); i++) {
            double a = bounds.get(i), b = bounds.get(i + 1);
            if (b - a < 1e-9) continue;
            double mid = (a + b) / 2;
            long pm = curve.profitAtCents(BigDecimal.valueOf(mid));
            double mass = term.cdf(b) - term.cdf(a);
            if (pm > 0) pAny += mass;
            // Equality with the extreme holds on the whole interval only if BOTH endpoints (and
            // hence the linear segment) sit at the extreme — a single-point peak contributes 0.
            long pa = curve.profitAtCents(BigDecimal.valueOf(Math.max(a, 1e-9)));
            long pb = curve.profitAtCents(BigDecimal.valueOf(b));
            if (maxProfit != Long.MAX_VALUE && Math.abs(pa - maxProfit) <= 1 && Math.abs(pb - maxProfit) <= 1) pMaxP += mass;
            if (maxLoss != Long.MAX_VALUE && maxLoss > 0 && Math.abs(-pa - maxLoss) <= 1 && Math.abs(-pb - maxLoss) <= 1) pMaxL += mass;
        }
        pAny = clamp01(pAny); pMaxP = clamp01(pMaxP); pMaxL = clamp01(pMaxL);
        double pPartial = Math.max(0, 1 - pAny - pMaxL);

        // ---- CVaR95: numeric (the worst-5% set is not an S-interval) ----
        double lo = mu - SPAN_SIGMAS * sd, hi = mu + SPAN_SIGMAS * sd, step = (hi - lo) / CVAR_GRID;
        double[] pl = new double[CVAR_GRID];
        double[] w = new double[CVAR_GRID];
        double wsum = 0;
        for (int i = 0; i < CVAR_GRID; i++) {
            double x = lo + (i + 0.5) * step;
            double z = (x - mu) / sd;
            double dens = Math.exp(-z * z / 2);
            pl[i] = curve.profitAtCents(BigDecimal.valueOf(Math.exp(x)));
            w[i] = dens; wsum += dens;
        }
        Integer[] idx = new Integer[CVAR_GRID];
        for (int i = 0; i < CVAR_GRID; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(pl[a], pl[b]));
        double tailMass = 0.05 * wsum, acc = 0, cvarNum = 0;
        for (int k = 0; k < CVAR_GRID && acc < tailMass; k++) {
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
            double beyond = kk >= spot ? 1 - term.cdf(kk) : term.cdf(kk);
            touches.add(new Touch(k, Math.min(1.0, 2 * beyond)));
        }
        String basis = riskFreeRate != 0
                ? String.format("risk-neutral lognormal approximation (market IV, r=%.1f%%, q=0 assumed) — market-implied odds, not a forecast; touch is a reflection estimate", riskFreeRate * 100)
                : "zero-rate risk-neutral lognormal approximation (market IV, q=0 assumed) — model odds, not a forecast; touch is a reflection estimate";
        return new Result(round4(pAny), round4(pMaxP), round4(pMaxL), round4(pPartial),
                cvar95, Math.min(0, stress), touches, basis);
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

}
