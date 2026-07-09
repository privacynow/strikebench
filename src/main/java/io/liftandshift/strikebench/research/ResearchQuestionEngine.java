package io.liftandshift.strikebench.research;

import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * A real research-question workbench: instead of the old degenerate "20-day momentum ≥ 0%" toy
 * (which fired on nearly every bar and compared to a bare 50%), each question CONDITIONS on a
 * signal and measures the forward-return distribution AGAINST the symbol's own baseline — so the
 * answer is "does this signal beat normal behavior?", with sample size, a two-proportion z-test on
 * the win-rate edge, a bootstrap CI on the mean forward return, a distribution, worst-case drawdown,
 * example dates, and an honest evidence badge (demo data never masquerades as observed). No
 * look-ahead: every signal is evaluated from data at or before its own bar.
 */
public final class ResearchQuestionEngine {

    private static final double Z_95 = 1.96;
    private static final int MIN_SAMPLE = 15;
    private static final int BOOTSTRAP = 800;

    private final MarketDataService market;

    public ResearchQuestionEngine(MarketDataService market) { this.market = market; }

    // ---- Catalog ----

    public record Param(String key, String label, double def, double min, double max, String unit) {}

    public record Question(String key, String title, String plain, String description, List<Param> params) {}

    private static final Param LOOKBACK = new Param("lookback", "Look-back window", 20, 3, 250, "days");
    private static final Param FORWARD = new Param("forward", "Hold forward", 10, 1, 120, "days");

    public List<Question> catalog() {
        return List.of(
            new Question("pullback_rebound", "Buy the dip?",
                "After a pullback, does it bounce back?",
                "When the stock has fallen at least X% from its recent high, how does the next few weeks look versus normal?",
                List.of(LOOKBACK, new Param("dropPct", "Pullback size", 5, 1, 40, "%"), FORWARD)),
            new Question("breakout_followthrough", "Do breakouts follow through?",
                "After a new high, does it keep rising or fade?",
                "When the stock closes at a new N-day high, does it continue higher or mean-revert over the next few weeks?",
                List.of(LOOKBACK, FORWARD)),
            new Question("oversold_bounce", "Does a big down day bounce?",
                "After a sharp one-day drop, what happens next?",
                "When the stock drops at least X% in a single day, how do the following days compare to normal?",
                List.of(new Param("dropPct", "One-day drop", 3, 1, 20, "%"), FORWARD)),
            new Question("momentum", "Does momentum persist?",
                "When it's been rising, does it keep rising?",
                "When N-day momentum is at least X%, is the next stretch better than the stock's normal behavior?",
                List.of(LOOKBACK, new Param("thresholdPct", "Momentum threshold", 5, 0, 50, "%"), FORWARD)),
            new Question("up_streak", "Do winning streaks continue?",
                "After several up days in a row, what's next?",
                "When the stock has risen K days in a row, does the streak tend to continue or reverse?",
                List.of(new Param("streak", "Up days in a row", 3, 2, 10, "days"), FORWARD))
        );
    }

    // ---- Request / result ----

    public record RunRequest(String key, String symbol, String from, String to, Map<String, Object> params) {}

    public record Stat(int sample, double winRatePct, double meanReturnPct, double medianReturnPct,
                       double worstPct, double bestPct) {}

    public record Bucket(double fromPct, double toPct, int count) {}

    public record QuestionResult(String key, String symbol, String question, String from, String to,
                                 int forwardDays, Stat baseline, Stat conditioned,
                                 double winRateEdgePct, double meanEdgePct, double zScore, boolean significant,
                                 double ciLowPct, double ciHighPct, List<Bucket> distribution,
                                 List<String> exampleDates, String evidence, boolean observed,
                                 String verdict, List<String> notes) {}

    public QuestionResult run(RunRequest req) {
        String key = req.key() == null ? "" : req.key().trim();
        String symbol = req.symbol() == null ? "" : req.symbol().trim().toUpperCase(Locale.ROOT);
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol is required");
        Question q = catalog().stream().filter(x -> x.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown question: " + key));
        Map<String, Object> p = req.params() == null ? Map.of() : req.params();

        LocalDate to = parseDate(req.to(), LocalDate.now(clockOrDefault()));
        LocalDate from = parseDate(req.from(), to.minusYears(3));
        int forward = clampParam(p, "forward", 10, 1, 120);
        int lookback = clampParam(p, "lookback", 20, 1, 250);

        // Pull enough history to warm up the look-back before `from`.
        CandleSeries series = market.candleSeries(symbol, from.minusDays(lookback + 20L), to);
        List<Candle> candles = series.candles().stream().filter(c -> !c.date().isAfter(to)).toList();
        boolean observed = isObserved(series.freshness());
        List<String> notes = new ArrayList<>();
        notes.add("A model result over one historical window, not a forecast. Regime and survivorship effects apply.");
        if (!observed) notes.add("DEMO price history — set YAHOO_ENABLED, or a Polygon/Alpha Vantage key, for a real study.");

        double[] closes = candles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
        int n = closes.length;
        if (n < lookback + forward + 5) {
            return empty(q, symbol, from, to, forward, observed, series.freshness(),
                    "Not enough price history for this window — widen the dates or shorten the hold.", notes);
        }

        // Forward returns and the conditioning signal (no look-ahead: bar i uses closes[<= i]).
        List<Double> baseFwd = new ArrayList<>();
        List<Double> condFwd = new ArrayList<>();
        List<String> examples = new ArrayList<>();
        for (int i = 0; i + forward < n; i++) {
            if (i < lookback) continue; // need the look-back warmed
            double fwd = closes[i + forward] / closes[i] - 1.0;
            baseFwd.add(fwd);
            if (signal(key, closes, i, lookback, p)) {
                condFwd.add(fwd);
                if (examples.size() < 6) examples.add(candles.get(i).date().toString());
            }
        }

        Stat baseline = stat(baseFwd);
        Stat conditioned = stat(condFwd);
        double winEdge = round(conditioned.winRatePct() - baseline.winRatePct());
        double meanEdge = round(conditioned.meanReturnPct() - baseline.meanReturnPct());
        double z = twoPropZ(conditioned, baseline);
        boolean significant = conditioned.sample() >= MIN_SAMPLE && Math.abs(z) >= Z_95;
        double[] ci = bootstrapMeanCi(condFwd, symbol.hashCode() ^ key.hashCode());
        List<Bucket> dist = histogram(condFwd);

        String verdict;
        if (conditioned.sample() < MIN_SAMPLE) {
            verdict = "Too few signals (" + conditioned.sample() + ") to conclude — widen the window or relax the condition.";
        } else if (significant && winEdge > 0) {
            verdict = "Supported — after this signal, " + symbol + " was positive " + pct(conditioned.winRatePct())
                    + " of the time over " + forward + " days, vs " + pct(baseline.winRatePct())
                    + " normally (mean " + signed(conditioned.meanReturnPct()) + " vs " + signed(baseline.meanReturnPct()) + ").";
        } else if (significant && winEdge < 0) {
            verdict = "Rejected — the signal preceded WORSE outcomes than normal (" + pct(conditioned.winRatePct())
                    + " positive vs " + pct(baseline.winRatePct()) + "). Fading it may make more sense than following it.";
        } else {
            verdict = "No clear edge — outcomes after the signal look like " + symbol + "'s normal behavior ("
                    + pct(conditioned.winRatePct()) + " vs " + pct(baseline.winRatePct()) + ").";
        }

        return new QuestionResult(key, symbol, questionText(q, symbol, forward, lookback, p), from.toString(), to.toString(),
                forward, baseline, conditioned, winEdge, meanEdge, round(z), significant,
                round(ci[0]), round(ci[1]), dist, examples, evidenceLabel(series.freshness()), observed, verdict, notes);
    }

    // ---- Signals (no look-ahead) ----

    private boolean signal(String key, double[] c, int i, int lookback, Map<String, Object> p) {
        switch (key) {
            case "pullback_rebound": {
                double hi = c[i];
                for (int j = Math.max(0, i - lookback); j <= i; j++) hi = Math.max(hi, c[j]);
                double drop = clampParam(p, "dropPct", 5, 1, 40) / 100.0;
                return hi > 0 && (c[i] / hi - 1.0) <= -drop;
            }
            case "breakout_followthrough": {
                double hi = c[i];
                for (int j = Math.max(0, i - lookback); j < i; j++) hi = Math.max(hi, c[j]);
                return c[i] >= hi; // a new (>=) N-day high at the close
            }
            case "oversold_bounce": {
                if (i == 0) return false;
                double drop = clampParam(p, "dropPct", 3, 1, 20) / 100.0;
                return (c[i] / c[i - 1] - 1.0) <= -drop;
            }
            case "momentum": {
                if (i - lookback < 0) return false;
                double thr = clampParam(p, "thresholdPct", 5, 0, 50) / 100.0;
                return c[i - lookback] > 0 && (c[i] / c[i - lookback] - 1.0) >= thr;
            }
            case "up_streak": {
                int streak = clampParam(p, "streak", 3, 2, 10);
                if (i < streak) return false;
                for (int j = 0; j < streak; j++) if (!(c[i - j] > c[i - j - 1])) return false;
                return true;
            }
            default: return false;
        }
    }

    // ---- Statistics ----

    private static Stat stat(List<Double> rs) {
        if (rs.isEmpty()) return new Stat(0, 0, 0, 0, 0, 0);
        double[] a = rs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int wins = 0; double sum = 0, worst = a[0], best = a[a.length - 1];
        for (double r : a) { if (r > 0) wins++; sum += r; }
        double median = a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2.0;
        return new Stat(a.length, round((double) wins / a.length * 100), round(sum / a.length * 100),
                round(median * 100), round(worst * 100), round(best * 100));
    }

    /** Two-proportion z-test on the conditioned vs baseline win rate (pooled). */
    private static double twoPropZ(Stat cond, Stat base) {
        if (cond.sample() == 0 || base.sample() == 0) return 0;
        double p1 = cond.winRatePct() / 100.0, p2 = base.winRatePct() / 100.0;
        int n1 = cond.sample(), n2 = base.sample();
        double pooled = (p1 * n1 + p2 * n2) / (n1 + n2);
        double se = Math.sqrt(pooled * (1 - pooled) * (1.0 / n1 + 1.0 / n2));
        return se == 0 ? 0 : (p1 - p2) / se;
    }

    /** Deterministic bootstrap 90% CI on the conditioned mean forward return (percent). */
    private static double[] bootstrapMeanCi(List<Double> rs, long seed) {
        if (rs.size() < 5) return new double[]{0, 0};
        Random rnd = new Random(seed);
        double[] means = new double[BOOTSTRAP];
        int n = rs.size();
        for (int b = 0; b < BOOTSTRAP; b++) {
            double s = 0;
            for (int k = 0; k < n; k++) s += rs.get(rnd.nextInt(n));
            means[b] = s / n;
        }
        java.util.Arrays.sort(means);
        return new double[]{means[(int) (0.05 * BOOTSTRAP)] * 100, means[(int) (0.95 * BOOTSTRAP)] * 100};
    }

    /** Fixed-bucket histogram of forward returns (percent) for the distribution chart. */
    private static List<Bucket> histogram(List<Double> rs) {
        List<Bucket> out = new ArrayList<>();
        if (rs.isEmpty()) return out;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double r : rs) { lo = Math.min(lo, r); hi = Math.max(hi, r); }
        lo *= 100; hi *= 100;
        if (hi - lo < 1e-9) { hi = lo + 1; }
        int bins = 9;
        double w = (hi - lo) / bins;
        int[] counts = new int[bins];
        for (double r : rs) {
            int idx = (int) Math.floor((r * 100 - lo) / w);
            if (idx < 0) idx = 0; if (idx >= bins) idx = bins - 1;
            counts[idx]++;
        }
        for (int i = 0; i < bins; i++) out.add(new Bucket(round(lo + i * w), round(lo + (i + 1) * w), counts[i]));
        return out;
    }

    // ---- Labels / helpers ----

    private String questionText(Question q, String symbol, int forward, int lookback, Map<String, Object> p) {
        return switch (q.key()) {
            case "pullback_rebound" -> "After " + symbol + " falls " + clampParam(p, "dropPct", 5, 1, 40)
                    + "%+ from its " + lookback + "-day high, is the next " + forward + " days better than normal?";
            case "breakout_followthrough" -> "After " + symbol + " closes at a " + lookback
                    + "-day high, does the next " + forward + " days continue or fade?";
            case "oversold_bounce" -> "After " + symbol + " drops " + clampParam(p, "dropPct", 3, 1, 20)
                    + "%+ in a day, how do the next " + forward + " days compare to normal?";
            case "momentum" -> "After " + symbol + "'s " + lookback + "-day momentum reaches "
                    + clampParam(p, "thresholdPct", 5, 0, 50) + "%+, is the next " + forward + " days better than normal?";
            case "up_streak" -> "After " + symbol + " rises " + clampParam(p, "streak", 3, 2, 10)
                    + " days in a row, does the next " + forward + " days continue or reverse?";
            default -> q.title();
        };
    }

    private QuestionResult empty(Question q, String symbol, LocalDate from, LocalDate to, int forward,
                                 boolean observed, Freshness f, String verdict, List<String> notes) {
        Stat z = new Stat(0, 0, 0, 0, 0, 0);
        return new QuestionResult(q.key(), symbol, q.title(), from.toString(), to.toString(), forward,
                z, z, 0, 0, 0, false, 0, 0, List.of(), List.of(), evidenceLabel(f), observed, verdict, notes);
    }

    private static boolean isObserved(Freshness f) {
        return f == Freshness.REALTIME || f == Freshness.DELAYED || f == Freshness.EOD || f == Freshness.STALE;
    }

    private static String evidenceLabel(Freshness f) {
        if (f == Freshness.FIXTURE || f == Freshness.MISSING) return "DEMO_FIXTURE";
        if (f == Freshness.EOD || f == Freshness.STALE) return "OBSERVED_EOD";
        if (f == Freshness.DELAYED) return "OBSERVED_DELAYED";
        if (f == Freshness.REALTIME) return "OBSERVED_LIVE";
        return "MODELED";
    }

    private static int clampParam(Map<String, Object> p, String key, int def, int min, int max) {
        Object v = p.get(key);
        try { return Math.clamp(v == null ? def : (int) Math.round(Double.parseDouble(v.toString())), min, max); }
        catch (Exception e) { return def; }
    }

    private static LocalDate parseDate(String s, LocalDate def) {
        if (s == null || s.isBlank()) return def;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return def; }
    }

    private static java.time.Clock clockOrDefault() { return java.time.Clock.systemUTC(); }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
    private static String pct(double v) { return Math.round(v) + "%"; }
    private static String signed(double v) { return (v >= 0 ? "+" : "") + round(v) + "%"; }
}
