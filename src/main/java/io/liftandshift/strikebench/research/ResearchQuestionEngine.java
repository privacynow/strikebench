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

    private static final int DEFAULT_MIN_SAMPLE = 15;
    private static final int DEFAULT_BOOTSTRAP = 800;

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

    /** Bumped whenever detection/stats change — persisted study keys must not collide across engines. */
    static final int ENGINE_VERSION = 3;

    /** Order-sensitive fold over every bar's date + close: any content change changes the hash. */
    static String contentHash(java.util.List<Candle> candles, String source) {
        long h = io.liftandshift.strikebench.sim.RandomStreams.mix(
                source == null ? 0 : source.hashCode());
        for (Candle c : candles) {
            h = io.liftandshift.strikebench.sim.RandomStreams.mix(h ^ c.date().toEpochDay());
            h = io.liftandshift.strikebench.sim.RandomStreams.mix(h ^ c.close().unscaledValue().longValue());
        }
        return Long.toHexString(h);
    }

    // ---- Request / result ----

    public record RunRequest(String key, String symbol, String from, String to, Map<String, Object> params) {}

    public record Stat(int sample, double winRatePct, double meanReturnPct, double medianReturnPct,
                       double worstPct, double bestPct) {}

    public record Bucket(double fromPct, double toPct, int count) {}

    /** The disclosed statistical protocol. Every field also participates in the study key. */
    public record Protocol(String baseline, String regime, int eventSpacingDays, int effectiveEventBlock,
                           int minSample, int confidencePct, int bootstrapSamples,
                           String multiplicity, boolean splitHalfCheck, double criticalZ) {}

    public record QuestionResult(String key, String symbol, String question, String from, String to,
                                 int forwardDays, Stat baseline, Stat conditioned,
                                 double winRateEdgePct, double meanEdgePct, double zScore, boolean significant,
                                 double ciLowPct, double ciHighPct, List<Bucket> distribution,
                                 List<String> exampleDates, String evidence, boolean observed,
                                 String verdict, List<String> notes,
                                 Double effectSize, String holdout,
                                 // The EMPIRICAL PATH ENSEMBLE: each separated event's forward
                                 // window as day-by-day RELATIVE prices (1.0 = the event close) — the
                                 // exact analogs behind the inference, reusable as a scenario source.
                                 List<List<Double>> analogPaths, List<String> eventDates, String studyKey,
                                 Protocol protocol) {}

    public QuestionResult run(RunRequest req) {
        return run(req, io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, null);
    }

    /** Context-aware variant: the study runs over the caller's analysis dataset. */
    public QuestionResult run(RunRequest req, io.liftandshift.strikebench.db.AnalysisContext actx) {
        return run(req, actx, null);
    }

    /** Dataset and market are separate axes: Demo/Simulated studies read their own candles. */
    public QuestionResult run(RunRequest req, io.liftandshift.strikebench.db.AnalysisContext actx,
                              String worldId) {
        String key = req.key() == null ? "" : req.key().trim();
        String symbol = req.symbol() == null ? "" : req.symbol().trim().toUpperCase(Locale.ROOT);
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol is required");
        Question q = catalog().stream().filter(x -> x.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown question: " + key));
        Map<String, Object> p = req.params() == null ? Map.of() : req.params();

        LocalDate laneToday = market.simInstant(worldId)
                .map(i -> LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                .orElseGet(() -> LocalDate.now(clockOrDefault()));
        LocalDate to = parseDate(req.to(), laneToday);
        LocalDate from = parseDate(req.from(), to.minusYears(3));
        int forward = clampParam(p, "forward", 10, 1, 120);
        int lookback = clampParam(p, "lookback", 20, 1, 250);
        int spacing = clampParam(p, "eventSpacing", forward, 1, forward);
        int minSample = clampParam(p, "minSample", DEFAULT_MIN_SAMPLE, 5, 100);
        int confidence = choiceParam(p, "confidencePct", 95, 90, 95, 99);
        int bootstrapSamples = clampParam(p, "bootstrapSamples", DEFAULT_BOOTSTRAP, 200, 10_000);
        String regime = enumParam(p, "regime", "ALL",
                "ALL", "ABOVE_200DMA", "BELOW_200DMA", "HIGH_VOL", "LOW_VOL");
        String multiplicity = enumParam(p, "multiplicity", "CATALOG_BONFERRONI",
                "CATALOG_BONFERRONI", "UNADJUSTED_EXPLORATORY");
        boolean splitHalf = boolParam(p, "splitHalf", true);
        int eventBlock = Math.max(1, (int) Math.ceil((double) forward / spacing));
        double criticalZ = criticalZ(confidence, "CATALOG_BONFERRONI".equals(multiplicity), catalog().size());
        Protocol protocol = new Protocol("NON_SIGNAL_COMPLEMENT", regime, spacing, eventBlock,
                minSample, confidence, bootstrapSamples, multiplicity, splitHalf, criticalZ);

        // Pull enough history to warm up the look-back before `from`.
        int regimeWarmup = "ABOVE_200DMA".equals(regime) || "BELOW_200DMA".equals(regime) ? 200
                : "HIGH_VOL".equals(regime) || "LOW_VOL".equals(regime) ? 60 : 0;
        int warmup = Math.max(lookback, regimeWarmup);
        CandleSeries series = market.candleSeries(symbol, from.minusDays(warmup + 100L), to, worldId, actx);
        List<Candle> candles = series.candles().stream().filter(c -> !c.date().isAfter(to)).toList();
        boolean observed = series.evidence().provenance()
                == io.liftandshift.strikebench.model.DataProvenance.OBSERVED;
        List<String> notes = new ArrayList<>();
        notes.add("A model result over one historical window, not a forecast. Regime and survivorship effects apply.");
        if (series.evidence().provenance() == io.liftandshift.strikebench.model.DataProvenance.MISSING) {
            notes.add("No compatible history is available in the selected market and dataset. Import observed bars or choose another explicit data lane.");
            return empty(q, symbol, from, to, forward, false, Freshness.MISSING,
                    "History unavailable — this study was not run.", notes, protocol);
        }
        if (!observed) notes.add(series.evidence().provenance() == io.liftandshift.strikebench.model.DataProvenance.DEMO
                ? "Built-in DEMO history — fabricated teaching data, not observed market evidence."
                : "Generated history — useful for scenario exploration, not observed market evidence.");

        double[] closes = candles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
        int n = closes.length;
        if (n < warmup + forward + 5) {
            return empty(q, symbol, from, to, forward, observed, series.freshness(),
                    "Not enough price history for this window and protocol — widen the dates, relax the regime, or shorten the hold.",
                    notes, protocol);
        }

        // Align the analyzed window with the REQUESTED `from` (not just the array warm-up), so a
        // large look-back doesn't silently shrink or shift the window the user asked about.
        int startIdx = warmup;
        for (int i = warmup; i < candles.size(); i++) {
            if (!candles.get(i).date().isBefore(from)) { startIdx = Math.max(warmup, i); break; }
        }

        // Forward returns split into SIGNAL vs its COMPLEMENT (non-signal bars). The baseline is the
        // complement, not all bars — so the win-rate edge compares disjoint groups (finding: a baseline
        // that is a superset of the conditioned set biases the test toward "no edge"). No look-ahead:
        // bar i's signal uses closes[<= i]; the forward return looks ahead only for the outcome.
        // SEPARATED EVENTS: a signal that keeps firing day after day is one market episode, not many
        // independent observations. The default spacing equals the outcome horizon (non-overlapping);
        // an expert may shorten it, in which case the effective sample and bootstrap are block-adjusted.
        List<Double> baseFwd = new ArrayList<>();  // non-signal complement = "normally"
        List<Double> condFwd = new ArrayList<>();
        List<String> examples = new ArrayList<>();
        List<List<Double>> analogPaths = new ArrayList<>(); // one full forward window per event
        List<String> eventDates = new ArrayList<>();
        int mergedFirings = 0;
        int eventOpenUntil = -1;
        for (int i = startIdx; i + forward < n; i++) {
            if (!inRegime(regime, closes, i)) continue;
            double fwd = closes[i + forward] / closes[i] - 1.0;
            if (signal(key, closes, i, lookback, p)) {
                if (i < eventOpenUntil) { mergedFirings++; continue; } // same episode as a taken signal
                condFwd.add(fwd);
                eventOpenUntil = i + spacing;
                if (examples.size() < 6) examples.add(candles.get(i).date().toString());
                eventDates.add(candles.get(i).date().toString());
                List<Double> path = new ArrayList<>(forward + 1);
                for (int k = 0; k <= forward; k++) path.add(Math.round(closes[i + k] / closes[i] * 1e6) / 1e6);
                analogPaths.add(path);
            } else if (i >= eventOpenUntil) {
                baseFwd.add(fwd);
            }
        }

        Stat baseline = stat(baseFwd);
        Stat conditioned = stat(condFwd);
        double winEdge = round(conditioned.winRatePct() - baseline.winRatePct());
        double meanEdge = round(conditioned.meanReturnPct() - baseline.meanReturnPct());
        double z = twoPropZ(conditioned, baseline, forward, eventBlock);
        boolean significant = conditioned.sample() >= minSample && Math.abs(z) >= criticalZ;
        double[] ci = BootstrapSampler.meanCi(condFwd, symbol.hashCode() ^ key.hashCode(),
                eventBlock, bootstrapSamples, confidence);
        if (mergedFirings > 0) notes.add("Repeated firings inside the configured " + spacing
                + "-day separation count as ONE episode (" + mergedFirings + " merged). The "
                + conditioned.sample() + " events are separated by at least " + spacing + " trading days.");
        if (eventBlock > 1) notes.add("The chosen event spacing permits overlapping outcome windows, so the event sample "
                + "and bootstrap use a dependence block of " + eventBlock + ".");
        if (forward > 1) notes.add("The baseline's windows still overlap by the hold length, so its effective "
                + "sample is deflated (÷" + forward + ") in the significance test — a conservative dependence correction.");
        // EFFECT SIZE (Cohen's d vs the baseline spread): a "significant" edge that is a tiny fraction
        // of normal day-to-day noise is not tradable — say so with a number.
        Double effectSize = effectSize(condFwd, baseFwd);
        // SPLIT-HALF HOLDOUT: did the edge exist in BOTH halves of the window, or is it one regime's story?
        String holdout = splitHalf ? splitHalfHoldout(condFwd, baseline.winRatePct(), winEdge) : null;
        if ("CATALOG_BONFERRONI".equals(multiplicity)) {
            notes.add("The significance threshold is Bonferroni-adjusted across the " + catalog().size()
                    + " built-in questions (critical |z| " + round(criticalZ) + ").");
        } else {
            notes.add("Exploratory unadjusted significance was selected. Trying several questions or thresholds raises the false-positive risk.");
        }
        if (!splitHalf) notes.add("Split-half consistency is off; this protocol cannot show whether the result depended on one half of the window.");
        if (!"ALL".equals(regime)) notes.add("Baseline and signal events are both restricted to regime " + regimeLabel(regime) + ".");
        List<Bucket> dist = histogram(condFwd);

        String verdict;
        if (conditioned.sample() < minSample) {
            verdict = "Too few signals (" + conditioned.sample() + ") to conclude — widen the window or relax the condition.";
        } else if (significant && winEdge > 0) {
            verdict = "Supported (" + confidence + "%, two-sided, dependence- and multiplicity-aware) — after this signal, " + symbol + " was positive " + pct(conditioned.winRatePct())
                    + " of the time over " + forward + " days, vs " + pct(baseline.winRatePct())
                    + " normally (mean " + signed(conditioned.meanReturnPct()) + " vs " + signed(baseline.meanReturnPct()) + ")."
                    + ("held".equals(holdout) ? " The edge was consistent across both halves of the window (an in-sample check, not out-of-sample validation)."
                       : "faded".equals(holdout) ? " CAUTION: the edge lived in only one half of the window \u2014 possibly one regime's story." : "");
        } else if (significant && winEdge < 0) {
            verdict = "Rejected (" + confidence + "%, two-sided, dependence- and multiplicity-aware) — the signal preceded WORSE outcomes than normal (" + pct(conditioned.winRatePct())
                    + " positive vs " + pct(baseline.winRatePct()) + "). Fading it may make more sense than following it.";
        } else {
            verdict = "No clear edge — outcomes after the signal look like " + symbol + "'s normal behavior ("
                    + pct(conditioned.winRatePct()) + " vs " + pct(baseline.winRatePct()) + ").";
        }

        // The study's IDENTITY: any consumer (UI cache, strategy sim) may only pair artifacts
        // whose keys match — the anti-"AAPL result on a QQQ page" contract. The key includes the
        // DATA identity (dataset + evidence level + engine version): the same window over demo,
        // synthetic, or observed candles is a DIFFERENT study (holistic review #9).
        String ds = actx == null || actx.datasetId() == null
                ? io.liftandshift.strikebench.db.DatasetService.OBSERVED : actx.datasetId();
        // F4: the key carries the CANDLE CONTENT hash + source — a backfill or correction that
        // changes the underlying bars changes the key, so drift detection actually fires. With a
        // deterministic engine, matching keys guarantee bit-identical analogs: the re-derivation
        // IS the persisted artifact.
        String dataHash = contentHash(candles, series.source());
        String studyKey = symbol + "|" + key + "|" + from + ".." + to + "|" + new java.util.TreeMap<>(p)
                + "|ds=" + ds + "|ev=" + evidenceLabel(series.freshness()) + "|src=" + series.source()
                + "|data=" + dataHash + "|v=" + ENGINE_VERSION;
        return new QuestionResult(key, symbol, questionText(q, symbol, forward, lookback, p), from.toString(), to.toString(),
                forward, baseline, conditioned, winEdge, meanEdge, round(z), significant,
                round(ci[0]), round(ci[1]), dist, examples, evidenceLabel(series.freshness()), observed, verdict, notes,
                effectSize, holdout, analogPaths, eventDates, studyKey, protocol);
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

    /**
     * Two-proportion z-test on the conditioned vs baseline win rate (pooled). Event and baseline
     * effective samples are both deflated when their outcome windows overlap.
     */
    private static double twoPropZ(Stat cond, Stat base, int forward, int conditionedBlock) {
        if (cond.sample() == 0 || base.sample() == 0) return 0;
        double p1 = cond.winRatePct() / 100.0, p2 = base.winRatePct() / 100.0;
        int n1 = Math.max(1, cond.sample() / Math.max(1, conditionedBlock));
        int n2 = Math.max(1, base.sample() / Math.max(1, forward)); // overlapping complement
        double pooled = (p1 * n1 + p2 * n2) / (n1 + n2);
        double se = Math.sqrt(pooled * (1 - pooled) * (1.0 / n1 + 1.0 / n2));
        return se == 0 ? 0 : (p1 - p2) / se;
    }

    /**
     * Cohen's d of the conditioned mean vs the baseline distribution: edge \u00f7 normal noise.
     * Null when either side is too thin to estimate a spread.
     */
    private static Double effectSize(List<Double> cond, List<Double> base) {
        if (cond.size() < 5 || base.size() < 5) return null;
        double mc = cond.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double mb = base.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = 0;
        for (double r : base) var += (r - mb) * (r - mb);
        double sd = Math.sqrt(var / (base.size() - 1));
        return sd == 0 ? null : round((mc - mb) / sd);
    }

    /**
     * Split-half walk-forward check: did the win-rate edge (vs the FULL baseline) point the same way
     * in both the first and second half of the events? "held" / "faded" / null (too few to split).
     */
    private static String splitHalfHoldout(List<Double> condFwd, double baselineWinPct, double fullEdge) {
        int n = condFwd.size();
        if (n < 10 || fullEdge == 0) return null;
        int half = n / 2;
        double w1 = winPct(condFwd.subList(0, half)) - baselineWinPct;
        double w2 = winPct(condFwd.subList(half, n)) - baselineWinPct;
        boolean sameSign = Math.signum(w1) == Math.signum(fullEdge) && Math.signum(w2) == Math.signum(fullEdge);
        return sameSign ? "held" : "faded";
    }

    private static double winPct(List<Double> rs) {
        if (rs.isEmpty()) return 0;
        long wins = rs.stream().filter(r -> r > 0).count();
        return (double) wins / rs.size() * 100.0;
    }

    // bootstrapMeanCi moved to the SHARED BootstrapSampler (identical algorithm + seeding).

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
                                 boolean observed, Freshness f, String verdict, List<String> notes,
                                 Protocol protocol) {
        Stat z = new Stat(0, 0, 0, 0, 0, 0);
        return new QuestionResult(q.key(), symbol, q.title(), from.toString(), to.toString(), forward,
                z, z, 0, 0, 0, false, 0, 0, List.of(), List.of(), evidenceLabel(f), observed, verdict, notes,
                null, null, List.of(), List.of(),
                symbol + "|" + q.key() + "|" + from + ".." + to + "|protocol=" + protocol, protocol);
    }

    private static boolean inRegime(String regime, double[] closes, int i) {
        if ("ALL".equals(regime)) return true;
        if (("ABOVE_200DMA".equals(regime) || "BELOW_200DMA".equals(regime)) && i >= 200) {
            double sum = 0;
            for (int j = i - 199; j <= i; j++) sum += closes[j];
            double avg = sum / 200.0;
            return "ABOVE_200DMA".equals(regime) ? closes[i] >= avg : closes[i] < avg;
        }
        if (("HIGH_VOL".equals(regime) || "LOW_VOL".equals(regime)) && i >= 60) {
            double shortVol = realizedStd(closes, i, 20);
            double longVol = realizedStd(closes, i, 60);
            return "HIGH_VOL".equals(regime) ? shortVol >= longVol : shortVol < longVol;
        }
        return false;
    }

    private static double realizedStd(double[] closes, int i, int days) {
        double sum = 0, sumSq = 0;
        for (int j = i - days + 1; j <= i; j++) {
            double r = Math.log(closes[j] / closes[j - 1]);
            sum += r;
            sumSq += r * r;
        }
        double mean = sum / days;
        return Math.sqrt(Math.max(0, sumSq / days - mean * mean));
    }

    private static double criticalZ(int confidence, boolean bonferroni, int comparisons) {
        double alpha = 1.0 - confidence / 100.0;
        if (bonferroni) alpha /= Math.max(1, comparisons);
        return inverseNormal(1.0 - alpha / 2.0);
    }

    /** Acklam's inverse-normal approximation; accurate well beyond the displayed 3 decimals. */
    private static double inverseNormal(double p) {
        if (!(p > 0 && p < 1)) throw new IllegalArgumentException("probability must be inside (0,1)");
        double[] a = {-39.6968302866538, 220.946098424521, -275.928510446969,
                138.357751867269, -30.6647980661472, 2.50662827745924};
        double[] b = {-54.4760987982241, 161.585836858041, -155.698979859887,
                66.8013118877197, -13.2806815528857};
        double[] c = {-0.00778489400243029, -0.322396458041136, -2.40075827716184,
                -2.54973253934373, 4.37466414146497, 2.93816398269878};
        double[] d = {0.00778469570904146, 0.32246712907004, 2.445134137143,
                3.75440866190742};
        double lo = 0.02425, hi = 1 - lo;
        if (p < lo) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5])
                    / ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1);
        }
        if (p > hi) return -inverseNormal(1 - p);
        double q = p - 0.5, r = q * q;
        return (((((a[0]*r+a[1])*r+a[2])*r+a[3])*r+a[4])*r+a[5])*q
                / (((((b[0]*r+b[1])*r+b[2])*r+b[3])*r+b[4])*r+1);
    }

    private static String regimeLabel(String regime) {
        return switch (regime) {
            case "ABOVE_200DMA" -> "above the 200-day average";
            case "BELOW_200DMA" -> "below the 200-day average";
            case "HIGH_VOL" -> "20-day volatility at or above its 60-day baseline";
            case "LOW_VOL" -> "20-day volatility below its 60-day baseline";
            default -> "all market conditions";
        };
    }

    private static String evidenceLabel(Freshness f) {
        if (f == Freshness.MISSING) return "MISSING";
        if (f == Freshness.FIXTURE) return "DEMO_FIXTURE";
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

    private static int choiceParam(Map<String, Object> p, String key, int def, int... allowed) {
        int value = clampParam(p, key, def, Integer.MIN_VALUE, Integer.MAX_VALUE);
        for (int x : allowed) if (value == x) return value;
        return def;
    }

    private static String enumParam(Map<String, Object> p, String key, String def, String... allowed) {
        String value = String.valueOf(p.getOrDefault(key, def)).trim().toUpperCase(Locale.ROOT);
        for (String x : allowed) if (x.equals(value)) return value;
        return def;
    }

    private static boolean boolParam(Map<String, Object> p, String key, boolean def) {
        Object value = p.get(key);
        return value == null ? def : Boolean.parseBoolean(String.valueOf(value));
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
