package io.liftandshift.pricing;

/** Implied volatility by bisection — slower than Newton but unconditionally convergent. */
public final class ImpliedVol {

    public static final double MIN_VOL = 1e-4;
    public static final double MAX_VOL = 5.0;
    private static final double PRICE_TOL = 1e-7;
    private static final int MAX_ITER = 200;

    private ImpliedVol() {}

    /**
     * Returns annualized implied vol, or NaN when the target price violates no-arbitrage
     * bounds or inputs are degenerate.
     */
    public static double solve(boolean call, double targetPrice, double s, double k, double t, double r, double q) {
        if (!(targetPrice > 0) || s <= 0 || k <= 0 || t <= 0) return Double.NaN;
        double lo = MIN_VOL, hi = MAX_VOL;
        double pLo = BlackScholes.price(call, s, k, t, r, q, lo);
        double pHi = BlackScholes.price(call, s, k, t, r, q, hi);
        if (targetPrice < pLo - PRICE_TOL || targetPrice > pHi + PRICE_TOL) return Double.NaN;
        for (int i = 0; i < MAX_ITER; i++) {
            double mid = 0.5 * (lo + hi);
            double p = BlackScholes.price(call, s, k, t, r, q, mid);
            if (Math.abs(p - targetPrice) < PRICE_TOL || (hi - lo) < 1e-9) return mid;
            if (p < targetPrice) lo = mid; else hi = mid;
        }
        return 0.5 * (lo + hi);
    }
}
