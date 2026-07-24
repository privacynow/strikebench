package io.liftandshift.strikebench.pricing;

/**
 * THE risk-neutral lognormal terminal distribution of S_T: {@code ln S_T ~ N(mu, sd²)} with
 * {@code mu = ln(spot) + (drift − σ²/2)·T} and {@code sd = σ·√T}. This drift/variance parameterization
 * was hand-inlined in ProbabilityMap, PayoffCurve (twice), and SimulationEngine; one definition now,
 * so a POP integral and a market-implied range can never quote a different terminal distribution.
 * The CDF routes through the one normal CDF ({@link BlackScholes#normCdf}).
 */
public record LognormalTerminal(double mu, double sd) {

    /** {@code drift} is the risk-neutral drift (the risk-free rate, q = 0 assumed). */
    public static LognormalTerminal of(double spot, double sigma, double tYears, double drift) {
        double sd = sigma * Math.sqrt(tYears);
        double mu = Math.log(spot) + (drift - 0.5 * sigma * sigma) * tYears;
        return new LognormalTerminal(mu, sd);
    }

    /** P(S_T ≤ s). */
    public double cdf(double s) {
        return s <= 0 ? 0 : BlackScholes.normCdf((Math.log(s) - mu) / sd);
    }
}
