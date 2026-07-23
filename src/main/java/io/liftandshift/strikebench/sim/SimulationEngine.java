package io.liftandshift.strikebench.sim;
import static io.liftandshift.strikebench.util.Numbers.round2;

import io.liftandshift.strikebench.db.DatasetService;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.MarketDataMaintenanceGate;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.model.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link ScenarioSpec} into a PERSISTED synthetic dataset: one concrete future (the seed's
 * path) written as daily bars under a fresh dataset_id, auto-saved in the dataset registry with its
 * full spec — so every run coexists with observed data (never overwrites it), can be re-selected as
 * the active analysis dataset, compared against others, and reproduced exactly from its seed. When
 * real history exists it also anchors the run: the block-bootstrap model resamples the symbol's own
 * observed returns, and the start price is the last real close.
 */
public final class SimulationEngine {

    private final MarketDataService market;
    private final DatasetService datasets;
    private final Db db;
    private final Clock clock;
    private final PathEnsembleService ensembles;
    private final MarketDataMaintenanceGate maintenance;

    public SimulationEngine(MarketDataService market, DatasetService datasets, Db db, Clock clock,
                            PathEnsembleService ensembles) {
        this(market, datasets, db, clock, ensembles, new MarketDataMaintenanceGate());
    }

    public SimulationEngine(MarketDataService market, DatasetService datasets, Db db, Clock clock,
                            PathEnsembleService ensembles, MarketDataMaintenanceGate maintenance) {
        this.market = market;
        this.datasets = datasets;
        this.db = db;
        this.clock = clock;
        this.ensembles = ensembles;
        this.maintenance = java.util.Objects.requireNonNull(maintenance, "maintenance");
    }

    public record DatasetRun(String datasetId, String name, String symbol, int bars, long seed,
                             String pathModelVersion,
                             double startPrice, double endPrice, List<String> notes) {}

    /** Generate + persist one synthetic future for a symbol. Returns the auto-saved dataset. */
    public DatasetRun runAndPersist(String symbolRaw, ScenarioSpec specRaw, String userId,
                                    String worldId,
                                    io.liftandshift.strikebench.db.AnalysisContext analysis) {
        String symbol = symbolRaw == null ? "" : symbolRaw.trim().toUpperCase(Locale.ROOT);
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol is required");
        ScenarioSpec spec = specRaw.sane();
        var scope = new PathEnsembleService.Scope(symbol, worldId, analysis);
        PathEnsembleService.Ensemble generated;
        try (AutoCloseable permit = SimBudget.acquire()) {
            // A dataset is ONE concrete future (the seed's), anchored on the active market and
            // calibrated from the active dataset. No shared call state, no observed-lane fallback.
            generated = ensembles.build(scope, PathEnsembleService.Basis.PARAMETRIC,
                    spec.withPaths(1).sane(), null);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        double spot = generated.spot();
        double[] path = generated.paths()[0];
        int spd = Math.max(1, spec.stepsPerDay());
        int days = spec.totalSteps() / spd;

        String name = symbol + " · " + pretty(spec.shape()) + " · " + days + "d · seed " + spec.seed();
        // FRAMING: the bars are written as the RECENT PAST, ending today — "imagine this had just
        // happened". Future-dated bars satisfied nothing (Research asks for history ending today;
        // backtests use historical windows), so activating a saved run changed only the banner.
        // As the recent past, the active dataset genuinely drives charts, HV, and backtests.
        LocalDate laneToday = market.simInstant(scope.worldId())
                .map(i -> LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                .orElseGet(() -> LocalDate.now(clock));
        List<Candle> bars = toDailyBars(path, spd, tradingDaysBack(laneToday, days - 1));
        String id = maintenance.write(() -> {
            String created = datasets.create(name, "SYNTHETIC_PURE", symbol, spec.seed(),
                    Map.of("pathModelVersion", generated.modelVersion(), "scenario", spec), userId);
            db.tx(c -> {
                for (Candle b : bars) {
                    Db.execOn(c, "INSERT INTO underlying_bar (symbol, d, open, high, low, close, volume, source, observed, dataset_id) "
                                    + "VALUES (?,?,?,?,?,?,?,?,0,?) ON CONFLICT (symbol, d, source, dataset_id) DO UPDATE SET "
                                    + "open=excluded.open, high=excluded.high, low=excluded.low, close=excluded.close",
                            symbol, b.date(), b.open(), b.high(), b.low(), b.close(), b.volume(), "synthetic", created);
                }
                return null;
            });
            return created;
        });

        List<String> notes = new ArrayList<>();
        notes.add("Synthetic future — one concrete path from seed " + spec.seed() + ". Saved as its own dataset; observed data is untouched.");
        if (spec.model() == ScenarioSpec.PathModel.BLOCK_BOOTSTRAP) {
            notes.add("Block-bootstrap inputs, when available, come from " + symbol
                    + " in the active market and dataset named by this request.");
        }
        return new DatasetRun(id, name, symbol, bars.size(), spec.seed(), generated.modelVersion(),
                round2(path[0]), round2(path[path.length - 1]), notes);
    }

    public record PreviewBand(int day, double p10, double p50, double p90) {}

    /**
     * Intraday/full-step distribution band for rendering the stored fan without reducing a
     * one-session ensemble to a two-point triangle.  Session progress is measured in trading
     * sessions from the anchor (for example 0.5 is halfway through the first session).
     */
    public record PreviewStepBand(int step, double sessionProgress,
                                  double p10, double p25, double p50, double p75, double p90) {}

    public record DecisionLevel(String key, double price) {}

    public record TerminalDistribution(double p5, double p16, double p50, double p84, double p95,
                                       double mean, double standardDeviation, double standardError) {}

    /** Direct empirical counts on this scenario ensemble; no barrier approximation is mixed in. */
    public record LevelOdds(String key, double price, String direction,
                            double endAboveProbability, double endBelowProbability,
                            double endBeyondProbability, double touchProbability,
                            double touchCiLow, double touchCiHigh, Double medianFirstTouchDay) {}

    public record DecisionMap(TerminalDistribution terminal, List<LevelOdds> levels,
                              double maxProbabilityMargin95) {}

    /** The exact listed expiry used to calibrate the separate options-market lens. */
    public record MarketVolInput(double atmIv, java.time.LocalDate expiration,
                                 int expirationCalendarDays) {}

    /** A separate risk-neutral lens from the options market, never blended with user-scenario odds. */
    public record MarketImpliedRange(double atmIv, String expiration, int horizonSessions,
                                     int expirationCalendarDays, double p16, double p50, double p84,
                                     String basis) {}

    /** Immutable identity of the exact path matrix shown to the user. */
    public record EnsembleReceipt(String fingerprint, String symbol, String worldId, String datasetId,
                                  String asOf, double anchorSpot, String anchorSource,
                                  String anchorFreshness, boolean anchorExecutable,
                                  String anchorLimitation, String modelVersion, ScenarioSpec spec) {}

    public record Preview(String symbol, double spot, int paths, int horizonDays, String pathModelVersion,
                          List<PreviewBand> bands, List<PreviewStepBand> stepBands,
                          List<List<Double>> samples,
                          List<Integer> sampleSourcePathIndices, int sampleFocusIndex,
                          double endP10, double endP50, double endP90,
                          DecisionMap decisionMap, MarketImpliedRange marketImplied,
                          EnsembleReceipt receipt, List<String> notes) {
        /** Source-compatible constructor for stored fixtures and callers that predate step bands. */
        public Preview(String symbol, double spot, int paths, int horizonDays, String pathModelVersion,
                       List<PreviewBand> bands, List<List<Double>> samples,
                       double endP10, double endP50, double endP90,
                       DecisionMap decisionMap, MarketImpliedRange marketImplied,
                       EnsembleReceipt receipt, List<String> notes) {
            this(symbol, spot, paths, horizonDays, pathModelVersion, bands, List.of(), samples,
                    List.of(), samples == null || samples.isEmpty() ? -1 : samples.size() / 2,
                    endP10, endP50, endP90, decisionMap, marketImplied, receipt, notes);
        }
    }

    public record PreviewRun(PathEnsembleService.Ensemble ensemble, Preview preview) {}

    /**
     * The "show me N possible futures" fan — price bands per day + a few concrete sample paths for
     * the preview chart. Pure compute: nothing is persisted; the same seed reproduces it exactly.
     */
    /** The active world and dataset are explicit immutable inputs; concurrent calls cannot cross. */
    public Preview preview(String symbolRaw, ScenarioSpec specRaw, String worldId,
                           io.liftandshift.strikebench.db.AnalysisContext analysis,
                           List<DecisionLevel> requestedLevels, MarketVolInput marketVol,
                           double riskFreeRate) {
        return previewRun(symbolRaw, specRaw, worldId, analysis, requestedLevels, marketVol, riskFreeRate).preview();
    }

    /** Internal same-ensemble seam used when Research overlays a working position on its fan. */
    public PreviewRun previewRun(String symbolRaw, ScenarioSpec specRaw, String worldId,
                                 io.liftandshift.strikebench.db.AnalysisContext analysis,
                                 List<DecisionLevel> requestedLevels, MarketVolInput marketVol,
                                 double riskFreeRate) {
        String symbol = symbolRaw == null ? "" : symbolRaw.trim().toUpperCase(Locale.ROOT);
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol is required");
        ScenarioSpec spec = specRaw.sane();
        String resolvedWorld = worldId == null || worldId.isBlank() ? "observed" : worldId;
        io.liftandshift.strikebench.db.AnalysisContext resolvedAnalysis = analysis == null
                ? io.liftandshift.strikebench.db.AnalysisContext.OBSERVED : analysis;
        var quote = market.quote(symbol, resolvedWorld).orElseThrow(() -> new io.liftandshift.strikebench.util.DataUnavailableException(
                "No price for " + symbol + " — this analysis needs a price in the active market."));
        double anchor = java.util.Optional.ofNullable(quote.mark()).map(java.math.BigDecimal::doubleValue)
                .filter(v -> v > 0).orElseThrow(() -> new io.liftandshift.strikebench.util.DataUnavailableException(
                        "No price for " + symbol + " — this analysis needs a price in the active market."));
        PathEnsembleService.Ensemble generated;
        try (AutoCloseable permit = SimBudget.acquire()) {
            generated = ensembles.build(new PathEnsembleService.Scope(symbol, resolvedWorld, resolvedAnalysis),
                    PathEnsembleService.Basis.PARAMETRIC, spec, null, anchor);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        double spot = generated.spot();
        double[][] paths = generated.paths();
        DecisionMap decisionMap = decisionMap(paths, spot, Math.max(1, spec.stepsPerDay()), requestedLevels);
        String asOf = java.time.Instant.ofEpochMilli(quote.asOfEpochMs()).toString();
        String material = symbol + '|' + resolvedWorld + '|' + resolvedAnalysis.datasetId() + '|' + asOf
                + '|' + Double.toHexString(spot) + '|' + generated.modelVersion() + '|' + generated.spec();
        String fingerprint = fingerprint(material, paths);
        io.liftandshift.strikebench.market.MarketLane lane = lane(resolvedWorld);
        boolean executable = quote.evidence().executableIn(lane);
        String limitation = executable ? null : "The anchor is " + quote.markFreshness()
                + " and supports scenario analysis only; refresh an executable quote before trading.";
        EnsembleReceipt receipt = new EnsembleReceipt(fingerprint, symbol, resolvedWorld,
                resolvedAnalysis.datasetId(), asOf, round2(spot), quote.source(),
                quote.markFreshness() == null ? "MISSING" : quote.markFreshness().name(),
                executable, limitation, generated.modelVersion(), generated.spec());
        return new PreviewRun(generated, assemble(generated, decisionMap, marketVol, riskFreeRate, receipt));
    }

    /**
     * Repaint a persisted fan without regenerating paths: bands/samples/terminal stats are
     * recomputed deterministically from the stored matrix, decision levels arrive verbatim from
     * storage, and the receipt keeps the STORED fingerprint — nothing is re-hashed.
     */
    public Preview previewFromStored(io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble stored,
                                     List<LevelOdds> levels, MarketVolInput marketVol,
                                     double riskFreeRate) {
        PathEnsembleService.Ensemble ensemble = stored.ensemble();
        PathEnsembleService.Scope scope = ensemble.scope();
        DecisionMap decisionMap = new DecisionMap(terminalDistribution(ensemble.paths()),
                levels == null ? List.of() : levels, maxProbabilityMargin95(ensemble.paths().length));
        io.liftandshift.strikebench.market.MarketLane lane = lane(scope.worldId());
        String freshness = stored.anchorFreshness() == null ? "MISSING" : stored.anchorFreshness();
        io.liftandshift.strikebench.model.Freshness parsed;
        try { parsed = io.liftandshift.strikebench.model.Freshness.valueOf(freshness); }
        catch (IllegalArgumentException e) { parsed = io.liftandshift.strikebench.model.Freshness.MISSING; }
        boolean executable = io.liftandshift.strikebench.model.DataEvidence
                .of(stored.anchorSource(), parsed).executableIn(lane);
        String limitation = executable ? null : "The anchor is " + freshness
                + " and supports scenario analysis only; refresh an executable quote before trading.";
        EnsembleReceipt receipt = new EnsembleReceipt(stored.fingerprint(), scope.symbol(), scope.worldId(),
                scope.analysis().datasetId(), isoInstant(stored.asOf()), round2(ensemble.spot()),
                stored.anchorSource(), freshness, executable, limitation,
                ensemble.modelVersion(), ensemble.spec());
        return assemble(ensemble, decisionMap, marketVol, riskFreeRate, receipt);
    }

    /** Deterministic fan assembly shared verbatim by live runs and stored restores. */
    private static Preview assemble(PathEnsembleService.Ensemble ensemble, DecisionMap decisionMap,
                                    MarketVolInput marketVol, double riskFreeRate,
                                    EnsembleReceipt receipt) {
        ScenarioSpec spec = ensemble.spec();
        double[][] paths = ensemble.paths();
        int spd = Math.max(1, spec.stepsPerDay());
        int days = spec.totalSteps() / spd;

        List<PreviewBand> bands = new ArrayList<>();
        double[] tmp = new double[paths.length];
        for (int day = 0; day <= days; day++) {
            int i = Math.min(spec.totalSteps(), day * spd);
            for (int p = 0; p < paths.length; p++) tmp[p] = paths[p][i];
            double[] sorted = tmp.clone();
            java.util.Arrays.sort(sorted);
            bands.add(new PreviewBand(day, round2(q(sorted, 0.10)), round2(q(sorted, 0.50)), round2(q(sorted, 0.90))));
        }
        int[] displaySteps = PathEnsembleService.displayStepIndices(spec.totalSteps());
        List<PreviewStepBand> stepBands = new ArrayList<>(displaySteps.length);
        for (int step : displaySteps) {
            for (int p = 0; p < paths.length; p++) tmp[p] = paths[p][step];
            double[] sorted = tmp.clone();
            java.util.Arrays.sort(sorted);
            stepBands.add(new PreviewStepBand(step, (double) step / spd,
                    round2(q(sorted, 0.10)), round2(q(sorted, 0.25)),
                    round2(q(sorted, 0.50)), round2(q(sorted, 0.75)),
                    round2(q(sorted, 0.90))));
        }
        // Representative futures retain the stored rows and full-matrix terminal ranking. Only
        // their wire representation is deterministically sampled at the same source steps as the
        // quantile bands, keeping the response bounded without creating browser-side prices.
        List<List<Double>> samples = new ArrayList<>();
        Integer[] terminalOrder = new Integer[paths.length];
        for (int p = 0; p < paths.length; p++) terminalOrder[p] = p;
        java.util.Arrays.sort(terminalOrder, java.util.Comparator
                .comparingDouble((Integer p) -> paths[p][paths[p].length - 1])
                .thenComparingInt(Integer::intValue));
        int sampleCount = Math.min(48, paths.length);
        int sampleFocusIndex = sampleCount == 0 ? -1 : sampleCount / 2;
        List<Integer> sampleSourcePathIndices = new ArrayList<>(sampleCount);
        for (int slot = 0; slot < sampleCount; slot++) {
            int at = sampleCount == 1 || slot == sampleFocusIndex ? (paths.length - 1) / 2
                    : (int) Math.round((double) slot * (paths.length - 1) / (sampleCount - 1));
            int p = terminalOrder[at];
            sampleSourcePathIndices.add(p);
            List<Double> sp = new ArrayList<>();
            for (int step : displaySteps) sp.add(round2(paths[p][step]));
            samples.add(sp);
        }
        PreviewBand end = bands.getLast();
        MarketImpliedRange marketRange = marketImpliedRange(
                ensemble.spot(), spec.horizonDays(), marketVol, riskFreeRate);
        List<String> notes = new ArrayList<>();
        notes.add("Synthetic futures from seed " + spec.seed() + " — a model of what COULD happen, never a forecast.");
        if (displaySteps.length < spec.totalSteps() + 1) {
            notes.add("The display carries " + displaySteps.length + " deterministic checkpoints from "
                    + (spec.totalSteps() + 1) + " stored points per path. Full stored paths still own "
                    + "the ensemble statistics and fingerprint.");
        }
        if (spec.model() == ScenarioSpec.PathModel.BLOCK_BOOTSTRAP)
            notes.add("Block-bootstrap history is resolved from this request's active market and dataset; if unavailable, the model falls back to Gaussian noise.");
        return new Preview(receipt.symbol(), round2(ensemble.spot()), paths.length, days, ensemble.modelVersion(),
                bands, stepBands, samples, List.copyOf(sampleSourcePathIndices), sampleFocusIndex,
                end.p10(), end.p50(), end.p90(), decisionMap, marketRange,
                receipt, notes);
    }

    private static io.liftandshift.strikebench.market.MarketLane lane(String world) {
        return "observed".equals(world) ? io.liftandshift.strikebench.market.MarketLane.OBSERVED
                : "demo".equals(world) ? io.liftandshift.strikebench.market.MarketLane.DEMO
                : io.liftandshift.strikebench.market.MarketLane.SIMULATED;
    }

    /** plan_ensemble.as_of round-trips through SQL text; re-emit the exact captured instant form. */
    private static String isoInstant(String asOf) {
        try { return java.time.Instant.parse(asOf).toString(); }
        catch (java.time.format.DateTimeParseException e) {
            String iso = asOf.replace(' ', 'T');
            if (iso.matches(".*[+-]\\d{2}$")) iso += ":00";
            return java.time.OffsetDateTime.parse(iso).toInstant().toString();
        }
    }

    private static MarketImpliedRange marketImpliedRange(double spot, int horizonSessions, MarketVolInput input,
                                                          double riskFreeRate) {
        if (input == null || !(input.atmIv() > 0) || !Double.isFinite(input.atmIv())) return null;
        double iv = input.atmIv();
        int sessions = Math.max(1, horizonSessions);
        double t = sessions / 252.0;
        double drift = (riskFreeRate - 0.5 * iv * iv) * t;
        double width = iv * Math.sqrt(t) * 0.994457883209753;
        String expiry = input.expiration() == null ? null : input.expiration().toString();
        return new MarketImpliedRange(iv, expiry, sessions, input.expirationCalendarDays(),
                round2(spot * Math.exp(drift - width)),
                round2(spot * Math.exp(drift)), round2(spot * Math.exp(drift + width)),
                "Risk-neutral lognormal range from ATM IV at the listed " + expiry + " expiry ("
                        + input.expirationCalendarDays() + " calendar days away), scaled over the requested "
                        + sessions + " trading sessions; market pricing, not a forecast.");
    }

    private static DecisionMap decisionMap(double[][] paths, double spot, int stepsPerDay,
                                           List<DecisionLevel> rawLevels) {
        int n = paths.length;
        int last = paths[0].length - 1;
        java.util.LinkedHashMap<String, DecisionLevel> levels = new java.util.LinkedHashMap<>();
        if (rawLevels != null) {
            if (rawLevels.size() > 20) throw new IllegalArgumentException("at most 20 decision levels");
            for (DecisionLevel level : rawLevels) {
                if (level == null || level.key() == null || level.key().isBlank()) {
                    throw new IllegalArgumentException("every decision level needs a key");
                }
                if (level.key().length() > 64 || !(level.price() > 0) || !Double.isFinite(level.price())) {
                    throw new IllegalArgumentException("decision level is outside the supported range");
                }
                levels.put(level.key(), level);
            }
        }
        List<LevelOdds> odds = new ArrayList<>();
        for (DecisionLevel level : levels.values()) {
            int above = 0, touched = 0;
            double[] touchDays = new double[n];
            int touchCount = 0;
            boolean upward = level.price() >= spot;
            for (double[] path : paths) {
                if (path[last] >= level.price()) above++;
                int first = -1;
                for (int step = 0; step <= last; step++) {
                    if (upward ? path[step] >= level.price() : path[step] <= level.price()) { first = step; break; }
                }
                if (first >= 0) {
                    touched++;
                    touchDays[touchCount++] = (double) first / Math.max(1, stepsPerDay);
                }
            }
            double touchP = (double) touched / n;
            double[] ci = wilson(touched, n);
            Double medianTouch = null;
            if (touchCount > 0) {
                double[] td = java.util.Arrays.copyOf(touchDays, touchCount);
                java.util.Arrays.sort(td);
                medianTouch = round2(quantile(td, 0.50));
            }
            double aboveP = (double) above / n;
            odds.add(new LevelOdds(level.key(), round2(level.price()), upward ? "ABOVE" : "BELOW",
                    aboveP, 1.0 - aboveP, upward ? aboveP : 1.0 - aboveP, touchP,
                    ci[0], ci[1], medianTouch));
        }
        return new DecisionMap(terminalDistribution(paths), odds, maxProbabilityMargin95(n));
    }

    private static TerminalDistribution terminalDistribution(double[][] paths) {
        int n = paths.length;
        int last = paths[0].length - 1;
        double[] terminal = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) { terminal[i] = paths[i][last]; sum += terminal[i]; }
        double mean = sum / n;
        double var = 0;
        for (double v : terminal) var += (v - mean) * (v - mean);
        double sd = Math.sqrt(var / Math.max(1, n - 1));
        double[] sorted = terminal.clone();
        java.util.Arrays.sort(sorted);
        return new TerminalDistribution(round2(quantile(sorted, 0.05)),
                round2(quantile(sorted, 0.16)), round2(quantile(sorted, 0.50)),
                round2(quantile(sorted, 0.84)), round2(quantile(sorted, 0.95)),
                round2(mean), round2(sd), round2(sd / Math.sqrt(n)));
    }

    private static double maxProbabilityMargin95(int paths) { return 1.96 * Math.sqrt(0.25 / paths); }

    private static double quantile(double[] sorted, double p) {
        if (sorted.length == 1) return sorted[0];
        double pos = Math.max(0, Math.min(1, p)) * (sorted.length - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted[lo];
        double w = pos - lo;
        return sorted[lo] * (1 - w) + sorted[hi] * w;
    }

    private static double[] wilson(int successes, int total) {
        double z = 1.96, n = total, p = successes / n;
        double den = 1 + z * z / n;
        double center = (p + z * z / (2 * n)) / den;
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / den;
        return new double[]{Math.max(0, center - half), Math.min(1, center + half)};
    }

    private static String fingerprint(String material, double[][] paths) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var bytes = java.nio.ByteBuffer.allocate(Long.BYTES);
            for (double[] path : paths) {
                for (double value : path) {
                    bytes.clear();
                    bytes.putLong(Double.doubleToLongBits(value));
                    md.update(bytes.array());
                }
            }
            byte[] digest = md.digest();
            return java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static double q(double[] sorted, double p) {
        return sorted[Math.max(0, Math.min(sorted.length - 1, (int) Math.floor(p * (sorted.length - 1))))];
    }

    private static List<Candle> toDailyBars(double[] path, int spd, LocalDate firstDay) {
        List<Candle> out = new ArrayList<>();
        LocalDate d = firstDay;
        int days = (path.length - 1) / spd;
        for (int day = 1; day <= days; day++) {
            int from = (day - 1) * spd, to = day * spd;
            double open = path[from], close = path[to], hi = open, lo = open;
            for (int i = from; i <= to; i++) { hi = Math.max(hi, path[i]); lo = Math.min(lo, path[i]); }
            out.add(new Candle(d, bd(open), bd(hi), bd(lo), bd(close), 0, false));
            d = MarketHours.tradingDateAfter(d, 1);
        }
        return out;
    }

    /** The trading day {@code n} exchange sessions before {@code end}. */
    private static LocalDate tradingDaysBack(LocalDate end, int n) {
        LocalDate d = end;
        while (!MarketHours.isTradingDay(d)) d = d.minusDays(1);
        for (int i = 0; i < Math.max(0, n); i++) {
            d = d.minusDays(1);
            while (!MarketHours.isTradingDay(d)) d = d.minusDays(1);
        }
        return d;
    }

    private static String pretty(ScenarioSpec.Shape s) {
        return s.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP); }

    public Map<String, Object> toJson(DatasetRun r) {
        return Map.of("datasetId", r.datasetId(), "name", r.name(), "symbol", r.symbol(), "bars", r.bars(),
                "seed", r.seed(), "pathModelVersion", r.pathModelVersion(),
                "startPrice", r.startPrice(), "endPrice", r.endPrice(), "notes", r.notes());
    }
}
