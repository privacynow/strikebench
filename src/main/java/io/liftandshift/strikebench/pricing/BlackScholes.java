package io.liftandshift.strikebench.pricing;

/**
 * Black-Scholes-Merton kernel. Pure doubles by design (decision #2): this is the numeric
 * engine for modeled prices/greeks/probabilities, never ledger money. Callers convert any
 * modeled price to BigDecimal/cents at the boundary via util.Money.
 *
 * Conventions: T in years, r and q continuously compounded, sigma annualized.
 * theta is per YEAR (divide by 365 for per-day), vega is per 1.00 of vol (divide by 100 for per-point).
 */
public final class BlackScholes {

    private BlackScholes() {}

    /** Standard normal CDF via Abramowitz-Stegun 7.1.26 (|error| < 7.5e-8). */
    public static double normCdf(double x) {
        if (Double.isNaN(x)) return Double.NaN;
        if (x > 8) return 1.0;
        if (x < -8) return 0.0;
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        double nd = normPdf(Math.abs(x)) * poly;
        return x >= 0 ? 1.0 - nd : nd;
    }

    /** Standard normal PDF. */
    public static double normPdf(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2.0 * Math.PI);
    }

    public static double d1(double s, double k, double t, double r, double q, double sigma) {
        return (Math.log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / (sigma * Math.sqrt(t));
    }

    /** Option price. Degenerate inputs (t<=0 or sigma<=0) return discounted intrinsic on forward terms. */
    public static double price(boolean call, double s, double k, double t, double r, double q, double sigma) {
        if (s <= 0 || k <= 0) return Double.NaN;
        if (t <= 0 || sigma <= 0) {
            double fwdIntrinsic = call ? s * disc(q, Math.max(t, 0)) - k * disc(r, Math.max(t, 0))
                                       : k * disc(r, Math.max(t, 0)) - s * disc(q, Math.max(t, 0));
            return Math.max(fwdIntrinsic, 0);
        }
        double d1 = d1(s, k, t, r, q, sigma);
        double d2 = d1 - sigma * Math.sqrt(t);
        if (call) return s * disc(q, t) * normCdf(d1) - k * disc(r, t) * normCdf(d2);
        return k * disc(r, t) * normCdf(-d2) - s * disc(q, t) * normCdf(-d1);
    }

    public static double delta(boolean call, double s, double k, double t, double r, double q, double sigma) {
        if (t <= 0) {
            boolean itm = call ? s > k : s < k;
            return itm ? (call ? 1.0 : -1.0) : 0.0;
        }
        if (sigma <= 0) {
            double forward = s * Math.exp((r - q) * t);
            boolean itm = call ? forward > k : forward < k;
            return itm ? (call ? disc(q, t) : -disc(q, t)) : 0.0;
        }
        double d1 = d1(s, k, t, r, q, sigma);
        return call ? disc(q, t) * normCdf(d1) : disc(q, t) * (normCdf(d1) - 1.0);
    }

    public static double gamma(double s, double k, double t, double r, double q, double sigma) {
        if (t <= 0 || sigma <= 0) return 0.0;
        double d1 = d1(s, k, t, r, q, sigma);
        return disc(q, t) * normPdf(d1) / (s * sigma * Math.sqrt(t));
    }

    /** Theta per year (negative = decay). */
    public static double theta(boolean call, double s, double k, double t, double r, double q, double sigma) {
        if (t <= 0 || sigma <= 0) return 0.0;
        double d1 = d1(s, k, t, r, q, sigma);
        double d2 = d1 - sigma * Math.sqrt(t);
        double term1 = -s * disc(q, t) * normPdf(d1) * sigma / (2.0 * Math.sqrt(t));
        if (call) return term1 - r * k * disc(r, t) * normCdf(d2) + q * s * disc(q, t) * normCdf(d1);
        return term1 + r * k * disc(r, t) * normCdf(-d2) - q * s * disc(q, t) * normCdf(-d1);
    }

    /** Vega per 1.00 change in vol. */
    public static double vega(double s, double k, double t, double r, double q, double sigma) {
        if (t <= 0 || sigma <= 0) return 0.0;
        double d1 = d1(s, k, t, r, q, sigma);
        return s * disc(q, t) * normPdf(d1) * Math.sqrt(t);
    }

    private static double disc(double rate, double t) {
        return Math.exp(-rate * t);
    }
}
