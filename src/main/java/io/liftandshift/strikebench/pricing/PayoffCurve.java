package io.liftandshift.strikebench.pricing;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Expiration payoff of a multi-leg position, piecewise-linear in the underlying price.
 * Exact BigDecimal arithmetic for profits at given prices (kinks only at strikes);
 * doubles only for the probabilistic analytics (probProfit, expected value).
 *
 * All legs must share one expiration (calendars/diagonals are MODELED elsewhere, not here).
 * Profits include entry premiums and exclude fees. Quantities: whole position = legs x qty.
 */
public final class PayoffCurve {

    private final List<Leg> legs;
    private final int qty;
    private final List<BigDecimal> knots;        // sorted distinct strikes
    private final List<BigDecimal> breakevens;   // sorted, scale 4
    private final BigDecimal maxProfit;          // dollars; meaningful only if !maxProfitUnbounded
    private final BigDecimal minProfit;          // dollars (most negative); meaningful only if !maxLossUnbounded
    private final boolean maxProfitUnbounded;
    private final boolean maxLossUnbounded;
    private final BigDecimal tailSlope;          // dollars of profit per $1 of underlying above last knot

    /** Additive package-level entry adjustment in cents: judges the SAME legs at YOUR net price
     *  (a proposed limit or an actual fill) without fabricating any per-leg quote — the whole
     *  curve, breakevens included, shifts exactly. */
    private final long entryAdjustCents;

    private PayoffCurve(List<Leg> legs, int qty) {
        this(legs, qty, 0L);
    }

    private PayoffCurve(List<Leg> legs, int qty, long entryAdjustCents) {
        this.entryAdjustCents = entryAdjustCents;
        if (legs == null || legs.isEmpty()) throw new IllegalArgumentException("at least one leg required");
        if (qty < 1) throw new IllegalArgumentException("qty must be >= 1");
        this.legs = List.copyOf(legs);
        this.qty = qty;
        this.knots = this.legs.stream()
                .filter(l -> !l.isStock())
                .map(Leg::strike)
                .distinct()
                .sorted()
                .toList();

        // Candidate extremes: S=0 and every kink. Between/beyond kinks the curve is linear.
        List<BigDecimal> candidates = new ArrayList<>();
        candidates.add(BigDecimal.ZERO);
        candidates.addAll(knots);

        BigDecimal maxP = null, minP = null;
        for (BigDecimal s : candidates) {
            BigDecimal p = profitAt(s);
            if (maxP == null || p.compareTo(maxP) > 0) maxP = p;
            if (minP == null || p.compareTo(minP) < 0) minP = p;
        }

        BigDecimal lastKnot = knots.isEmpty() ? BigDecimal.ZERO : knots.getLast();
        BigDecimal beyond = lastKnot.add(BigDecimal.ONE);
        this.tailSlope = profitAt(beyond).subtract(profitAt(lastKnot));
        this.maxProfitUnbounded = tailSlope.signum() > 0;
        this.maxLossUnbounded = tailSlope.signum() < 0;
        this.maxProfit = maxP;
        this.minProfit = minP;
        this.breakevens = computeBreakevens();
    }

    public static PayoffCurve of(List<Leg> legs, int qty) {
        return new PayoffCurve(legs, qty);
    }

    /** With a package-level entry-net adjustment (null/0 = none): profit(S) shifts by exactly this. */
    public static PayoffCurve of(List<Leg> legs, int qty, Long entryAdjustCents) {
        return new PayoffCurve(legs, qty, entryAdjustCents == null ? 0L : entryAdjustCents);
    }

    /** Exact profit in dollars for the whole position at expiration price s. */
    public BigDecimal profitAt(BigDecimal s) {
        BigDecimal total = BigDecimal.ZERO;
        for (Leg leg : legs) {
            BigDecimal perShare = leg.profitPerShare(s);
            total = total.add(perShare.multiply(BigDecimal.valueOf(leg.multiplier()))
                    .multiply(BigDecimal.valueOf((long) leg.ratio() * qty)));
        }
        return entryAdjustCents == 0 ? total : total.add(BigDecimal.valueOf(entryAdjustCents, 2));
    }

    public long profitAtCents(BigDecimal s) {
        return Money.toCents(profitAt(s));
    }

    /** Signed cash effect of opening (credit > 0, debit < 0), whole position, excluding fees, in cents. */
    public long entryNetPremiumCents() {
        BigDecimal total = BigDecimal.ZERO;
        for (Leg leg : legs) {
            BigDecimal cash = leg.entryPrice().multiply(BigDecimal.valueOf(leg.multiplier()))
                    .multiply(BigDecimal.valueOf((long) leg.ratio() * qty));
            total = leg.action() == LegAction.SELL ? total.add(cash) : total.subtract(cash);
        }
        return Money.toCents(total) + entryAdjustCents;
    }

    public List<BigDecimal> knots() { return knots; }
    public List<BigDecimal> breakevens() { return breakevens; }
    public boolean maxProfitUnbounded() { return maxProfitUnbounded; }
    public boolean maxLossUnbounded() { return maxLossUnbounded; }

    /** One sampled point of the expiration payoff, for charting. */
    public record ChartPoint(BigDecimal price, long profitCents) {}

    /**
     * Chart-ready samples over spot×[0.70, 1.30]: a 60-step grid plus every knot and
     * breakeven inside the window, sorted ascending. The payoff is piecewise linear, so
     * a renderer may interpolate between points exactly.
     */
    public List<ChartPoint> chartPoints(BigDecimal spot) {
        if (spot == null || spot.signum() <= 0) return List.of();
        java.util.TreeSet<BigDecimal> prices = new java.util.TreeSet<>();
        BigDecimal lo = spot.multiply(new BigDecimal("0.70")).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal hi = spot.multiply(new BigDecimal("1.30")).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal step = hi.subtract(lo).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
        if (step.signum() <= 0) step = new BigDecimal("0.01");
        for (BigDecimal p = lo; p.compareTo(hi) <= 0; p = p.add(step)) prices.add(p);
        for (BigDecimal k : knots) if (k.compareTo(lo) >= 0 && k.compareTo(hi) <= 0) prices.add(k.setScale(2, java.math.RoundingMode.HALF_UP));
        for (BigDecimal b : breakevens) if (b.compareTo(lo) >= 0 && b.compareTo(hi) <= 0) prices.add(b.setScale(2, java.math.RoundingMode.HALF_UP));
        List<ChartPoint> out = new ArrayList<>();
        for (BigDecimal p : prices) out.add(new ChartPoint(p, profitAtCents(p)));
        return out;
    }

    /** Max profit in cents; only meaningful when !maxProfitUnbounded(). */
    public long maxProfitCents() { return Money.toCents(maxProfit); }

    /** Max loss as a NON-NEGATIVE magnitude in cents; only meaningful when !maxLossUnbounded(). */
    public long maxLossCents() { return Math.max(0, -Money.toCents(minProfit)); }

    private List<BigDecimal> computeBreakevens() {
        List<BigDecimal> out = new ArrayList<>();
        List<BigDecimal> pts = new ArrayList<>();
        pts.add(BigDecimal.ZERO);
        pts.addAll(knots);

        for (int i = 0; i < pts.size(); i++) {
            BigDecimal a = pts.get(i);
            BigDecimal pa = profitAt(a);
            if (pa.signum() == 0) addUnique(out, a);
            if (i + 1 < pts.size()) {
                BigDecimal b = pts.get(i + 1);
                BigDecimal pb = profitAt(b);
                if (pa.signum() * pb.signum() < 0) {
                    // Linear on [a,b]: root = a + pa * (b-a) / (pa-pb)
                    BigDecimal root = a.add(pa.multiply(b.subtract(a))
                            .divide(pa.subtract(pb), Money.PRICE_SCALE, RoundingMode.HALF_UP));
                    addUnique(out, root);
                }
            }
        }
        // Tail segment [lastKnot, infinity)
        if (tailSlope.signum() != 0) {
            BigDecimal a = pts.getLast();
            BigDecimal pa = profitAt(a);
            if (pa.signum() * tailSlope.signum() < 0) {
                BigDecimal root = a.subtract(pa.divide(tailSlope, Money.PRICE_SCALE, RoundingMode.HALF_UP));
                if (root.compareTo(a) > 0) addUnique(out, root);
            }
        }
        out.sort(BigDecimal::compareTo);
        return List.copyOf(out);
    }

    private static void addUnique(List<BigDecimal> list, BigDecimal v) {
        BigDecimal scaled = v.setScale(Money.PRICE_SCALE, RoundingMode.HALF_UP);
        for (BigDecimal x : list) if (x.compareTo(scaled) == 0) return;
        list.add(scaled);
    }

    /**
     * Probability of any profit at expiration under a lognormal terminal distribution:
     * ln S_T ~ N(ln spot + (drift - sigma^2/2) t, sigma^2 t). Ratio, not money — double is fine.
     */
    public double probProfit(double spot, double sigma, double tYears, double drift) {
        if (spot <= 0) return Double.NaN;
        if (tYears <= 0 || sigma <= 0) {
            return profitAt(BigDecimal.valueOf(spot)).signum() > 0 ? 1.0 : 0.0;
        }
        double m = Math.log(spot) + (drift - 0.5 * sigma * sigma) * tYears;
        double sd = sigma * Math.sqrt(tYears);

        List<Double> bounds = new ArrayList<>();
        bounds.add(0.0);
        for (BigDecimal b : breakevens) bounds.add(b.doubleValue());
        bounds.add(Double.POSITIVE_INFINITY);

        double prob = 0;
        for (int i = 0; i + 1 < bounds.size(); i++) {
            double lo = bounds.get(i), hi = bounds.get(i + 1);
            double sample = sampleWithin(lo, hi, spot);
            if (profitAt(BigDecimal.valueOf(sample)).signum() > 0) {
                double pHi = Double.isInfinite(hi) ? 1.0 : BlackScholes.normCdf((Math.log(hi) - m) / sd);
                double pLo = lo <= 0 ? 0.0 : BlackScholes.normCdf((Math.log(lo) - m) / sd);
                prob += pHi - pLo;
            }
        }
        return Math.clamp(prob, 0.0, 1.0);
    }

    private static double sampleWithin(double lo, double hi, double spot) {
        if (Double.isInfinite(hi)) return Math.max(lo * 1.5, Math.max(lo + 1.0, spot));
        if (lo <= 0) return hi / 2.0;
        return (lo + hi) / 2.0;
    }

    /**
     * Expected profit at expiration in cents under the same lognormal model, by Simpson
     * integration over log-price. A modeled statistic (never ledger money) — double kernel,
     * converted to cents at the boundary.
     */
    public long expectedValueCents(double spot, double sigma, double tYears, double drift) {
        if (spot <= 0) return 0;
        if (tYears <= 0 || sigma <= 0) return profitAtCents(BigDecimal.valueOf(spot));
        double m = Math.log(spot) + (drift - 0.5 * sigma * sigma) * tYears;
        double sd = sigma * Math.sqrt(tYears);
        int n = 2000; // even, for Simpson
        double lo = m - 8 * sd, hi = m + 8 * sd, h = (hi - lo) / n;
        double sum = 0;
        for (int i = 0; i <= n; i++) {
            double x = lo + i * h;
            double w = (i == 0 || i == n) ? 1 : (i % 2 == 1 ? 4 : 2);
            double z = (x - m) / sd;
            sum += w * profitDollars(Math.exp(x)) * BlackScholes.normPdf(z) / sd;
        }
        double ev = sum * h / 3.0;
        return Money.toCents(ev);
    }

    /**
     * Present-value expected profit under the risk-neutral distribution (q=0 approximation).
     * Entry cash moves today; only the terminal payoff is discounted. Discounting the whole
     * expiration P/L would incorrectly discount the premium paid or received at entry.
     */
    public long riskNeutralExpectedValueCents(double spot, double sigma, double tYears,
                                              double riskFreeRate) {
        if (spot <= 0) return 0;
        if (tYears <= 0 || sigma <= 0) return profitAtCents(BigDecimal.valueOf(spot));
        double entry = entryNetPremiumCents() / 100.0;
        double terminalPnl = expectedValueCents(spot, sigma, tYears, riskFreeRate) / 100.0;
        double expectedTerminalPayoff = terminalPnl - entry;
        return Money.toCents(entry + Math.exp(-riskFreeRate * tYears) * expectedTerminalPayoff);
    }

    /** Double-precision profit for the integration kernel only. */
    private double profitDollars(double s) {
        // The package-level price adjustment shifts EVERY statistic, including expected value —
        // a proposed price that moves breakevens but not EV would lie about the trade's economics.
        double total = entryAdjustCents / 100.0;
        for (Leg leg : legs) {
            double intrinsic;
            if (leg.isStock()) {
                intrinsic = s;
            } else {
                double k = leg.strike().doubleValue();
                intrinsic = leg.type() == io.liftandshift.strikebench.model.OptionType.CALL ? Math.max(s - k, 0) : Math.max(k - s, 0);
            }
            double edge = intrinsic - leg.entryPrice().doubleValue();
            double signed = leg.action() == LegAction.BUY ? edge : -edge;
            total += signed * leg.multiplier() * leg.ratio() * qty;
        }
        return total;
    }
}
