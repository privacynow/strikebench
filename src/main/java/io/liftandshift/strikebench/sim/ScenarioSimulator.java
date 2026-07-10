package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.pricing.BlackScholes;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a STRATEGY through a scenario's Monte-Carlo paths and reports the P&amp;L distribution — the
 * "I think X happens over the next N days; how would this position behave?" engine. Each leg is
 * priced along every path with BSM at the scenario's (deterministic) IV path — so theta decay and
 * the IV view (crush/expansion) are IN the numbers — and valued at intrinsic once expired. Entry is
 * priced the same way at t0, so the whole exercise is internally consistent and honestly MODELED:
 * this is never presented as observed market data.
 */
public final class ScenarioSimulator {

    /** action BUY/SELL; type CALL/PUT/STOCK; expiryDay = trading days from now (ignored for stock). */
    public record SimLeg(String action, String type, double strike, int expiryDay, int ratio) {}

    public record Band(int day, long p10Cents, long p50Cents, long p90Cents) {}

    public record Bucket(long fromCents, long toCents, int count) {}

    public record SimResult(long entryCostCents, int paths, int horizonDays,
                            long p5Cents, long p25Cents, long p50Cents, long p75Cents, long p95Cents,
                            long expectedPnlCents, double winRatePct, long bestCents, long worstCents,
                            double breachProbPct, List<Band> bands, List<Bucket> distribution,
                            List<Double> examplePath, List<String> notes) {}

    private final PathGenerator generator = new PathGenerator();

    public SimResult run(double spot, List<SimLeg> legs, int qty, ScenarioSpec spec, IvSpec ivSpec,
                         double riskFreeRate, double[] historicalLogReturns) {
        return run(spot, legs, qty, spec, ivSpec, riskFreeRate, historicalLogReturns, null, null);
    }

    /**
     * With an OBSERVED entry: when the caller priced the position from live market quotes
     * (executable sides), the whole exercise measures YOUR SCENARIO vs THE MARKET'S PRICE —
     * which is the actionable question. A model-priced entry against the same model's paths
     * converges to a coin flip by construction.
     */
    public SimResult run(double spot, List<SimLeg> legs, int qty, ScenarioSpec spec, IvSpec ivSpec,
                         double riskFreeRate, double[] historicalLogReturns,
                         Long entryOverrideCents, String entryNote) {
        try (AutoCloseable permit = SimBudget.acquire()) {
            ScenarioSpec s = spec.sane();
            double[][] paths = generator.generate(s, spot, historicalLogReturns);
            return runInner(paths, spot, legs, qty, s, ivSpec, riskFreeRate, entryOverrideCents, entryNote);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** One structure to compare: resolved legs + an optional market-priced entry. */
    public record CompareItem(String key, List<SimLeg> legs, Long entryOverrideCents, String entryNote) {}

    public record CompareOutcome(String key, SimResult result) {}

    public record CompareRefusal(String key, String reason) {}

    public record CompareReport(List<CompareOutcome> results, List<CompareRefusal> refused) {}

    /**
     * FAIR comparison: every structure is priced along the SAME generated path set (one budget
     * permit, one generation — not N re-generations), and a structure that cannot be priced is
     * REPORTED as refused, never silently dropped ("all strategies" must mean all).
     */
    public CompareReport compare(double spot, List<CompareItem> items, int qty, ScenarioSpec spec,
                                 IvSpec ivSpec, double riskFreeRate, double[] historicalLogReturns) {
        try (AutoCloseable permit = SimBudget.acquire()) {
            ScenarioSpec s = spec.sane();
            double[][] paths = generator.generate(s, spot, historicalLogReturns);
            List<CompareOutcome> out = new ArrayList<>();
            List<CompareRefusal> refused = new ArrayList<>();
            for (CompareItem item : items) {
                try {
                    out.add(new CompareOutcome(item.key(),
                            runInner(paths, spot, item.legs(), qty, s, ivSpec, riskFreeRate,
                                    item.entryOverrideCents(), item.entryNote())));
                } catch (Exception e) {
                    refused.add(new CompareRefusal(item.key(),
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                }
            }
            return new CompareReport(out, refused);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SimResult runInner(double[][] paths, double spot, List<SimLeg> legs, int qty, ScenarioSpec s,
                               IvSpec ivSpec, double riskFreeRate,
                               Long entryOverrideCents, String entryNote) {
        IvSpec iv = (ivSpec == null ? IvSpec.flat(s.volAnnual()) : ivSpec).sane();
        int steps = s.totalSteps();
        int spd = Math.max(1, s.stepsPerDay());
        double dt = s.dt();
        double[] ivPath = iv.path(steps, dt, spd);

        int q = Math.max(1, qty);
        double entry = entryOverrideCents != null
                ? entryOverrideCents / 100.0
                : portfolioValue(legs, paths[0], 0, steps, spd, dt, ivPath[0], riskFreeRate) * q;

        // Per-path P&L at every step (for the fan) and at the horizon (for the distribution).
        int n = paths.length;
        double[][] pnl = new double[n][steps + 1];
        for (int p = 0; p < n; p++) {
            for (int i = 0; i <= steps; i++) {
                double v = portfolioValue(legs, paths[p], i, steps, spd, dt, ivPath[i], riskFreeRate) * q;
                pnl[p][i] = v - entry;
            }
        }

        // Fan bands (daily granularity keeps the payload small).
        List<Band> bands = new ArrayList<>();
        double[] tmp = new double[n];
        for (int day = 0; day <= steps / spd; day++) {
            int i = Math.min(steps, day * spd);
            for (int p = 0; p < n; p++) tmp[p] = pnl[p][i];
            double[] sorted = tmp.clone();
            java.util.Arrays.sort(sorted);
            bands.add(new Band(day, cents(pct(sorted, 0.10)), cents(pct(sorted, 0.50)), cents(pct(sorted, 0.90))));
        }

        double[] terminal = new double[n];
        int wins = 0; double sum = 0;
        for (int p = 0; p < n; p++) {
            terminal[p] = pnl[p][steps];
            if (terminal[p] > 0) wins++;
            sum += terminal[p];
        }
        double[] sorted = terminal.clone();
        java.util.Arrays.sort(sorted);

        // Distribution histogram (9 buckets across the terminal P&L range).
        List<Bucket> dist = new ArrayList<>();
        double lo = sorted[0], hi = sorted[n - 1];
        if (hi - lo < 1e-9) hi = lo + 1;
        int bins = 9;
        double w = (hi - lo) / bins;
        int[] counts = new int[bins];
        for (double t : terminal) {
            int idx = (int) Math.floor((t - lo) / w);
            counts[Math.max(0, Math.min(bins - 1, idx))]++;
        }
        for (int b = 0; b < bins; b++) dist.add(new Bucket(cents(lo + b * w), cents(lo + (b + 1) * w), counts[b]));

        // The median path's underlying (an "example future") for the preview chart.
        int medianIdx = medianTerminalIndex(paths, steps);
        List<Double> example = new ArrayList<>();
        for (int day = 0; day <= steps / spd; day++) example.add(round2(paths[medianIdx][Math.min(steps, day * spd)]));

        List<String> notes = new ArrayList<>();
        if (entryOverrideCents != null && entryNote != null) notes.add(entryNote);
        notes.add(entryOverrideCents != null
                ? "Exit values along each path are MODELED (Black-Scholes on the IV path); the entry reflects the quotes named above."
                : "Synthetic scenario — entry AND exits are MODELED (Black-Scholes on the IV path), never observed market quotes.");
        notes.add("Seed " + s.seed() + " reproduces this exact run. Fills, commissions, and early assignment are not modeled here.");
        if (iv.eventDay() >= 0) notes.add("IV " + (iv.eventShockPct() < 0 ? "crush" : "expansion") + " of "
                + Math.round(Math.abs(iv.eventShockPct()) * 100) + "% applied at day " + iv.eventDay() + "'s close.");

        return new SimResult(cents(entry), n, steps / spd,
                cents(pct(sorted, 0.05)), cents(pct(sorted, 0.25)), cents(pct(sorted, 0.50)),
                cents(pct(sorted, 0.75)), cents(pct(sorted, 0.95)),
                cents(sum / n), Math.round(wins * 1000.0 / n) / 10.0,
                cents(sorted[n - 1]), cents(sorted[0]),
                0, bands, dist, example, notes);
    }

    /**
     * Signed portfolio value in DOLLARS for one unit (qty applied by the caller), valued along
     * ONE path at {@code step}. An expired leg settles ONCE — intrinsic at the underlying price
     * ON ITS EXPIRATION STEP — and stays that cash amount forever after; it must never re-value
     * against the post-expiration stock price (that minted impossible P&L for calendars and any
     * horizon longer than a leg's DTE).
     */
    private static double portfolioValue(List<SimLeg> legs, double[] path, int step, int steps,
                                         int spd, double dt, double iv, double r) {
        double underlying = path[Math.min(step, steps)];
        double v = 0;
        for (SimLeg leg : legs) {
            int ratio = Math.max(1, leg.ratio());
            double sign = "SELL".equalsIgnoreCase(leg.action()) ? -1 : 1;
            if ("STOCK".equalsIgnoreCase(leg.type())) {
                v += sign * ratio * 100 * underlying;
                continue;
            }
            boolean call = "CALL".equalsIgnoreCase(leg.type());
            int expiryStep = Math.max(0, leg.expiryDay()) * spd;
            double px;
            if (step >= expiryStep) {
                double settle = path[Math.min(expiryStep, steps)]; // the price WHEN it expired
                px = Math.max(0, call ? settle - leg.strike() : leg.strike() - settle);
            } else {
                double t = (expiryStep - step) * dt;
                px = BlackScholes.price(call, underlying, leg.strike(), t, r, 0, Math.max(0.01, iv));
            }
            v += sign * ratio * 100 * px;
        }
        return v;
    }

    private static int medianTerminalIndex(double[][] paths, int steps) {
        Integer[] idx = new Integer[paths.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(paths[a][steps], paths[b][steps]));
        return idx[idx.length / 2];
    }

    private static double pct(double[] sorted, double p) {
        int i = (int) Math.floor(p * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    private static long cents(double dollars) { return Math.round(dollars * 100); }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
}
