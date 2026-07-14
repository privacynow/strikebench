package io.liftandshift.strikebench.sim;

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

    public record Band(int day, long p10Cents, long p50Cents, long p90Cents) {}

    public record Bucket(long fromCents, long toCents, int count) {}

    public record SimResult(long entryCostCents, int paths, int horizonDays,
                            long p5Cents, long p25Cents, long p50Cents, long p75Cents, long p95Cents,
                            long expectedPnlCents, double winRatePct, long bestCents, long worstCents,
                            double breachProbPct, List<Band> bands, List<Bucket> distribution,
                            List<Double> examplePath, List<String> notes) {}

    /**
     * EXTERNAL-PATH variant (evidence consolidation): the ensemble came from HISTORY — the exact
     * analog windows behind an event study, or a conditional bootstrap of them — and the simulator
     * reprices the strategy over it exactly as it would over generated paths. The simulator does
     * not care where paths came from; the CALLER labels the interpretation (empirical frequencies
     * are conditional on the selected past, never a parametric model's odds).
     * Paths must be absolute prices with spec.totalSteps()+1 points each.
     */
    public SimResult runOnPaths(double[][] paths, PathPosition position, int qty, ScenarioSpec spec,
                                IvSpec ivSpec, double riskFreeRate, Long entryOverrideCents, String entryNote,
                                long roundTripFeesCents) {
        try (AutoCloseable permit = SimBudget.acquire()) {
            ScenarioSpec s = spec.sane();
            if (paths == null || paths.length == 0) throw new IllegalArgumentException("no paths in the ensemble");
            for (double[] pth : paths) {
                if (pth.length != s.totalSteps() + 1) {
                    throw new IllegalArgumentException("ensemble paths must have " + (s.totalSteps() + 1)
                            + " points (got " + pth.length + ") — the horizon and the study forward window must match");
                }
            }
            requireWorkBudget((long) paths.length * (s.totalSteps() + 1) * position.legs().size());
            return runInner(paths, position, qty, s, ivSpec, riskFreeRate, entryOverrideCents,
                    entryNote, roundTripFeesCents);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record EnsembleRun(PathEnsembleService.Ensemble ensemble, SimResult result) {}

    /** Generate/reconstruct and value under one process-wide permit. */
    public EnsembleRun run(PathEnsembleService source, PathEnsembleService.Scope scope,
                           PathEnsembleService.Basis basis, ScenarioSpec spec,
                           io.liftandshift.strikebench.research.ResearchQuestionEngine.RunRequest study,
                           double spot, PathPosition position, int qty, IvSpec ivSpec,
                           double riskFreeRate, Long entryOverrideCents, String entryNote,
                           long roundTripFeesCents) {
        try (AutoCloseable permit = SimBudget.acquire()) {
            PathEnsembleService.Ensemble ensemble = source.build(scope, basis, spec, study, spot);
            ScenarioSpec sane = ensemble.spec().sane();
            validatePaths(ensemble.paths(), sane);
            requireWorkBudget((long) ensemble.paths().length * (sane.totalSteps() + 1)
                    * position.legs().size());
            return new EnsembleRun(ensemble, runInner(ensemble.paths(), position, qty,
                    sane, ivSpec, riskFreeRate, entryOverrideCents, entryNote, roundTripFeesCents));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** One structure to compare: resolved legs + an optional market-priced entry. */
    public record CompareItem(String key, PathPosition position, Long entryOverrideCents, String entryNote,
                              long roundTripFeesCents, Integer qty) {
        public CompareItem(String key, PathPosition position, Long entryOverrideCents, String entryNote,
                           long roundTripFeesCents) {
            this(key, position, entryOverrideCents, entryNote, roundTripFeesCents, null);
        }
    }

    public record CompareOutcome(String key, SimResult result) {}

    public record CompareRefusal(String key, String reason) {}

    public record CompareReport(List<CompareOutcome> results, List<CompareRefusal> refused) {}

    public record EnsembleComparison(PathEnsembleService.Ensemble ensemble, CompareReport report) {}

    /** Build one ensemble and judge every structure under one permit and aggregate work budget. */
    public EnsembleComparison compare(PathEnsembleService source, PathEnsembleService.Scope scope,
                                      PathEnsembleService.Basis basis, ScenarioSpec spec,
                                      io.liftandshift.strikebench.research.ResearchQuestionEngine.RunRequest study,
                                      double spot, List<CompareItem> items, int qty,
                                      IvSpec ivSpec, double riskFreeRate) {
        try (AutoCloseable permit = SimBudget.acquire()) {
            PathEnsembleService.Ensemble ensemble = source.build(scope, basis, spec, study, spot);
            return compareInner(ensemble, items, qty, ivSpec, riskFreeRate);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Judge several exact packages on an already-persisted ensemble. Plan Outcomes uses this so
     * its comparison cannot regenerate paths that merely resemble the Evidence receipt. */
    public EnsembleComparison compare(PathEnsembleService.Ensemble ensemble, List<CompareItem> items,
                                      int fallbackQty, IvSpec ivSpec, double riskFreeRate) {
        try (AutoCloseable permit = SimBudget.acquire()) {
            return compareInner(ensemble, items, fallbackQty, ivSpec, riskFreeRate);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private EnsembleComparison compareInner(PathEnsembleService.Ensemble ensemble, List<CompareItem> items,
                                             int fallbackQty, IvSpec ivSpec, double riskFreeRate) {
        if (ensemble == null) throw new IllegalArgumentException("ensemble is required");
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("comparison items are required");
        ScenarioSpec s = ensemble.spec().sane();
        validatePaths(ensemble.paths(), s);
        long totalLegs = items.stream().mapToLong(it -> it.position().legs().size()).sum();
        requireWorkBudget((long) ensemble.paths().length * (s.totalSteps() + 1) * Math.max(1, totalLegs));
        List<CompareOutcome> out = new ArrayList<>();
        List<CompareRefusal> refused = new ArrayList<>();
        for (CompareItem item : items) {
            try {
                int qty = item.qty() == null ? fallbackQty : Math.clamp(item.qty(), 1, 100);
                out.add(new CompareOutcome(item.key(), runInner(ensemble.paths(), item.position(), qty,
                        s, ivSpec, riskFreeRate,
                        item.entryOverrideCents(), item.entryNote(), item.roundTripFeesCents())));
            } catch (RuntimeException e) {
                refused.add(new CompareRefusal(item.key(), publicReason(e)));
            }
        }
        return new EnsembleComparison(ensemble, new CompareReport(out, refused));
    }

    /**
     * Valuation work = paths x (steps+1) x option legs. The per-spec point cap bounds path
     * GENERATION; this bounds leg-by-leg VALUATION, which comparisons multiply.
     */
    private static final long MAX_VALUATION_WORK = 40_000_000L;

    /** Keep validation guidance, but never expose implementation exceptions in product output. */
    public static String publicReason(Exception e) {
        if (e instanceof IllegalArgumentException && e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return "This structure could not be evaluated with the selected inputs.";
    }

    private static void requireWorkBudget(long work) {
        if (work > MAX_VALUATION_WORK) {
            throw new IllegalArgumentException("Simulation too large (" + work + " leg-steps > "
                    + MAX_VALUATION_WORK + ") — reduce paths, steps per day, or the number of structures.");
        }
    }

    private static void validatePaths(double[][] paths, ScenarioSpec s) {
        if (paths == null || paths.length == 0) throw new IllegalArgumentException("no paths in the ensemble");
        for (double[] path : paths) {
            if (path == null || path.length != s.totalSteps() + 1) {
                throw new IllegalArgumentException("ensemble paths must have " + (s.totalSteps() + 1)
                        + " points — the horizon and source window must match");
            }
        }
    }

    private SimResult runInner(double[][] paths, PathPosition position, int qty, ScenarioSpec s,
                               IvSpec ivSpec, double riskFreeRate,
                               Long entryOverrideCents, String entryNote, long roundTripFeesCents) {
        IvSpec iv = (ivSpec == null ? IvSpec.flat(s.volAnnual()) : ivSpec).sane();
        int steps = s.totalSteps();
        int spd = Math.max(1, s.stepsPerDay());
        double dt = s.dt();
        double[] ivPath = iv.path(steps, dt, spd);

        int q = Math.max(1, qty);
        double fees = Math.max(0, roundTripFeesCents) / 100.0;
        double entry = entryOverrideCents != null
                ? entryOverrideCents / 100.0
                : PathValuationKernel.value(position, paths[0], 0, steps, spd, dt,
                        ivPath[0], riskFreeRate) * q;

        // Per-path P&L at every step (for the fan) and at the horizon (for the distribution).
        int n = paths.length;
        double[][] pnl = new double[n][steps + 1];
        for (int p = 0; p < n; p++) {
            for (int i = 0; i <= steps; i++) {
                double v = PathValuationKernel.value(position, paths[p], i, steps, spd, dt,
                        ivPath[i], riskFreeRate) * q;
                pnl[p][i] = v - entry - fees;
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
        if (entryNote != null && !entryNote.isBlank()) notes.add(entryNote);
        notes.add(entryOverrideCents != null
                ? "Exit values along each path are MODELED (Black-Scholes on the IV path); the entry reflects the quotes named above."
                : "Synthetic scenario — entry AND exits are MODELED (Black-Scholes on the IV path), never observed market quotes.");
        notes.add("Seed " + s.seed() + " reproduces this exact run. The distribution includes "
                + io.liftandshift.strikebench.util.Money.fmt(Math.max(0, roundTripFeesCents))
                + " of configured round-trip commissions; modeled exits do not include future bid/ask slippage or early assignment.");
        if (s.model() == ScenarioSpec.PathModel.STUDENT_T) {
            notes.add("Student-t uses non-integer ν=" + s.tailNu()
                    + ", bounded at ±8 standardized deviations and re-compensated so the scenario guide remains the expected price path.");
        } else if (s.model() == ScenarioSpec.PathModel.BLOCK_BOOTSTRAP) {
            notes.add("Block bootstrap preserves contiguous return patterns and uses an empirical block-prefix compensator; it does not apply a Gaussian volatility-drag shortcut.");
        }
        if (iv.eventDay() >= 0) notes.add("IV " + (iv.eventShockPct() < 0 ? "crush" : "expansion") + " of "
                + Math.round(Math.abs(iv.eventShockPct()) * 100) + "% applied at day " + iv.eventDay() + "'s close.");

        return new SimResult(cents(entry), n, steps / spd,
                cents(pct(sorted, 0.05)), cents(pct(sorted, 0.25)), cents(pct(sorted, 0.50)),
                cents(pct(sorted, 0.75)), cents(pct(sorted, 0.95)),
                cents(sum / n), Math.round(wins * 1000.0 / n) / 10.0,
                cents(sorted[n - 1]), cents(sorted[0]),
                0, bands, dist, example, notes);
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
