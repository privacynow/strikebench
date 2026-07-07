package io.liftandshift.pricing;

/**
 * A simple parametric volatility smile used wherever real IVs are unavailable
 * (fixture chains, modeled backtest chains): put skew plus symmetric wing curvature,
 * flattening with time to expiration.
 */
public final class VolSurface {

    private VolSurface() {}

    /**
     * @param baseIv ATM implied vol proxy (e.g. historical vol)
     * @param s      spot
     * @param k      strike
     * @param t      years to expiration
     */
    public static double smile(double baseIv, double s, double k, double t) {
        double m = Math.log(k / s); // log-moneyness
        double skew = -0.35 * m;    // downside strikes richer
        double wings = 1.4 * m * m; // both far wings richer
        double termDamp = Math.min(1.0, Math.sqrt(0.25 / Math.max(t, 0.01))); // smile flattens with time
        return Math.max(0.05, baseIv + (skew + wings) * baseIv * termDamp);
    }
}
