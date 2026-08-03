package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.model.Symbol;
import static io.liftandshift.strikebench.util.Numbers.round2;

import io.liftandshift.strikebench.util.Quantiles;

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
        String symbol = Symbol.normalize(symbolRaw);
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol is required");
        if (worldId == null || worldId.isBlank()) {
            throw new IllegalArgumentException("market world id is required");
        }
        java.util.Objects.requireNonNull(analysis, "analysis context");
        ScenarioSpec spec = specRaw.sane();
        var scope = new PathEnsembleService.Scope(symbol, worldId, analysis);
        PathEnsembleService.Ensemble generated;
        try (AutoCloseable permit = SimBudget.acquire()) {
            // A dataset is ONE concrete future (the seed's), anchored on the active market and
            // calibrated from the active dataset. No shared call state, no observed-mode fallback.
            generated = ensembles.build(scope, PathEnsembleService.Basis.PARAMETRIC,
                    spec.withPaths(1).sane(), null, ensembles.anchorSpot(scope));
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
        LocalDate marketToday = market.marketToday(scope.worldId(), clock);
        List<Candle> bars = toDailyBars(path, spd, tradingDaysBack(marketToday, days - 1));
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

    /**
     * The listed expiry that supplied the IV for the separate options-market lens. Its maturity
     * is provenance; the scenario projection itself uses the canvas's typed trading-session clock.
     */
    public record MarketVolInput(double atmIv, java.time.LocalDate expiration,
                                 int expirationCalendarDays) {}

    /** A separate risk-neutral lens from the options market, never blended with user-scenario odds. */
    public record MarketImpliedRange(double atmIv, String expiration, int horizonSessions,
                                     int expirationCalendarDays, double p16, double p50, double p84,
                                     String basis) {
        /**
         * The same range stated as a move from an anchor price. Surfaces label the band "σ+ $X
         * (+Y%)"; deriving Y in the browser made the percentage a second, unowned statement of
         * the same fact. Null when the anchor cannot support a ratio.
         */
        public Double upMovePct(Double anchorSpot) { return movePct(anchorSpot, p84); }
        public Double downMovePct(Double anchorSpot) { return movePct(anchorSpot, p16); }
        /** Half of this result's p16–p84 interval, in underlying-price units. */
        public double halfWidth() { return (p84 - p16) / 2.0; }
        private static Double movePct(Double anchor, double level) {
            if (anchor == null || !(anchor > 0)) return null;
            return round2((level - anchor) / anchor * 100.0);
        }
        /**
         * Scenario-cone range. The IV may come from a listed contract, but its projection clock is
         * the separately declared scenario horizon—not that contract's expiration.
         */
        public static MarketImpliedRange forScenarioHorizon(
                double spot, double atmIv,
                io.liftandshift.strikebench.pricing.ExpectedMove.ScenarioHorizon horizon,
                String volatilityExpiration, int volatilityExpirationCalendarDays,
                double riskFreeRate) {
            var range = io.liftandshift.strikebench.pricing.ExpectedMove.scenarioHorizonRange(
                    spot, atmIv, horizon, riskFreeRate);
            if (range == null) return null;
            int sessions = horizon.tradingSessions();
            return new MarketImpliedRange(atmIv, volatilityExpiration, sessions,
                    volatilityExpirationCalendarDays, range.p16(), range.p50(), range.p84(),
                    "Risk-neutral lognormal range from ATM IV sourced from the listed "
                            + volatilityExpiration + " expiry (" + volatilityExpirationCalendarDays
                            + " calendar days away), projected over " + range.timeBasis()
                            + "; market pricing, not a forecast.");
        }

        /**
         * Listed-contract range. Both the expiration and the model clock come from the same
         * normalized option-time result.
         */
        public static MarketImpliedRange forListedExpiry(
                double spot, double atmIv,
                io.liftandshift.strikebench.market.OptionTime.Measure expiryTime,
                double riskFreeRate) {
            var range = io.liftandshift.strikebench.pricing.ExpectedMove.listedExpiryRange(
                    spot, atmIv, expiryTime, riskFreeRate);
            if (range == null || expiryTime.expiration() == null) return null;
            return new MarketImpliedRange(atmIv, expiryTime.expiration().toString(),
                    expiryTime.sessions(), Math.toIntExact(expiryTime.calendarDays()),
                    range.p16(), range.p50(), range.p84(),
                    "Risk-neutral lognormal range from ATM IV at the listed "
                            + expiryTime.expiration() + " expiry using " + range.timeBasis()
                            + "; market pricing, not a forecast.");
        }
    }

    /** Immutable identity of the exact path matrix shown to the user. */
    public record EnsembleMetadata(String fingerprint, String symbol, String worldId, String datasetId,
                                  String asOf, double anchorSpot, String anchorSource,
                                  String anchorFreshness, boolean anchorExecutable,
                                  String anchorLimitation, String modelVersion, ScenarioSpec spec) {}

    public record Preview(String symbol, double spot, int paths, int horizonDays, String pathModelVersion,
                          List<PreviewBand> bands, List<PreviewStepBand> stepBands,
                          List<List<Double>> samples,
                          List<Integer> sampleSourcePathIndices, int sampleFocusIndex,
                          double endP10, double endP50, double endP90,
                          DecisionMap decisionMap, MarketImpliedRange marketImplied,
                          EnsembleMetadata ensembleMetadata, List<String> notes) {}

    /** Exact wire projection of already-selected source rows; no paths or financial facts are regenerated. */
    public record PreviewProjection(List<PreviewStepBand> stepBands, List<List<Double>> samples,
                                    List<Integer> sampleSourcePathIndices, int sampleFocusIndex,
                                    PreviewProjectionMetadata metadata) {
        public PreviewProjection {
            stepBands = stepBands == null ? List.of() : List.copyOf(stepBands);
            samples = samples == null ? List.of() : samples.stream().map(List::copyOf).toList();
            sampleSourcePathIndices = sampleSourcePathIndices == null
                    ? List.of() : List.copyOf(sampleSourcePathIndices);
        }
    }

    /** Metadata binding every serialized preview point to its immutable source-matrix step. */
    public record PreviewProjectionMetadata(String version, int sourcePointCount,
                                            int returnedPointCount, List<Integer> displaySteps) {
        public PreviewProjectionMetadata {
            displaySteps = displaySteps == null ? List.of() : List.copyOf(displaySteps);
        }
    }

    public static final String PREVIEW_PROJECTION_VERSION = "preview-display-projection-1";

    public record PreviewRun(PathEnsembleService.Ensemble ensemble, Preview preview) {}

    /**
     * The one generated-fan entry: returns both the immutable ensemble and its display projection
     * so every caller preserves the exact paths that produced the preview.
     */
    public PreviewRun previewRun(String symbolRaw, ScenarioSpec specRaw, String worldId,
                                 io.liftandshift.strikebench.db.AnalysisContext analysis,
                                 List<DecisionLevel> requestedLevels, MarketVolInput marketVol,
                                 double riskFreeRate) {
        String symbol = Symbol.normalize(symbolRaw);
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol is required");
        if (worldId == null || worldId.isBlank()) {
            throw new IllegalArgumentException("market world id is required");
        }
        java.util.Objects.requireNonNull(analysis, "analysis context");
        ScenarioSpec spec = specRaw.sane();
        String resolvedWorld = worldId.trim();
        io.liftandshift.strikebench.db.AnalysisContext resolvedAnalysis = analysis;
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
        io.liftandshift.strikebench.market.MarketMode mode = mode(resolvedWorld);
        boolean executable = quote.evidence().executableIn(mode);
        String limitation = executable ? null : "The anchor is " + quote.markFreshness()
                + " and supports scenario analysis only; refresh an executable quote before trading.";
        EnsembleMetadata result = new EnsembleMetadata(fingerprint, symbol, resolvedWorld,
                resolvedAnalysis.datasetId(), asOf, round2(spot), quote.source(),
                quote.markFreshness() == null ? "MISSING" : quote.markFreshness(),
                executable, limitation, generated.modelVersion(), generated.spec());
        return new PreviewRun(generated, assemble(generated, decisionMap, marketVol, riskFreeRate, result));
    }

    /**
     * Repaint a persisted fan without regenerating paths: bands/samples/terminal stats are
     * recomputed deterministically from the stored matrix, decision levels arrive verbatim from
     * storage, and the result keeps the STORED fingerprint — nothing is re-hashed.
     */
    public Preview previewFromStored(io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble stored,
                                     List<LevelOdds> levels, MarketVolInput marketVol,
                                     double riskFreeRate) {
        PathEnsembleService.Ensemble ensemble = stored.ensemble();
        PathEnsembleService.Scope scope = ensemble.scope();
        DecisionMap decisionMap = new DecisionMap(terminalDistribution(ensemble.paths()),
                levels == null ? List.of() : levels, maxProbabilityMargin95(ensemble.paths().length));
        io.liftandshift.strikebench.market.MarketMode mode = mode(scope.worldId());
        String freshness = stored.anchorFreshness() == null ? "MISSING" : stored.anchorFreshness();
        boolean executable = io.liftandshift.strikebench.model.DataEvidence
                .fromLabel(stored.anchorSource(), freshness).executableIn(mode);
        String limitation = executable ? null : "The anchor is " + freshness
                + " and supports scenario analysis only; refresh an executable quote before trading.";
        EnsembleMetadata result = new EnsembleMetadata(stored.fingerprint(), scope.symbol(), scope.worldId(),
                scope.analysis().datasetId(),
                io.liftandshift.strikebench.util.Timestamps.isoInstant(stored.asOf()),
                round2(ensemble.spot()),
                stored.anchorSource(), freshness, executable, limitation,
                ensemble.modelVersion(), ensemble.spec());
        return assemble(ensemble, decisionMap, marketVol, riskFreeRate, result);
    }

    /** Deterministic fan assembly shared verbatim by live runs and stored restores. */
    private static Preview assemble(PathEnsembleService.Ensemble ensemble, DecisionMap decisionMap,
                                    MarketVolInput marketVol, double riskFreeRate,
                                    EnsembleMetadata result) {
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
            bands.add(new PreviewBand(day, round2(Quantiles.of(sorted, 0.10)), round2(Quantiles.of(sorted, 0.50)), round2(Quantiles.of(sorted, 0.90))));
        }
        int[] displaySteps = PathEnsembleService.displayStepIndices(spec.totalSteps());
        PreviewProjection projection = projectPreview(ensemble, List.of(), -1, displaySteps);
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
        return new Preview(result.symbol(), round2(ensemble.spot()), paths.length, days, ensemble.modelVersion(),
                bands, projection.stepBands(), projection.samples(),
                projection.sampleSourcePathIndices(), projection.sampleFocusIndex(),
                end.p10(), end.p50(), end.p90(), decisionMap, marketRange,
                result, notes);
    }

    /**
     * Reproject the already-selected preview rows and full-matrix bands on an exact source-step
     * grid. An empty selection requests the normalized terminal-quantile rows used by
     * {@link #assemble}; a non-empty selection is preserved byte-for-byte by source identity.
     */
    public static PreviewProjection projectPreview(PathEnsembleService.Ensemble ensemble,
                                                    List<Integer> selectedSourcePathIndices,
                                                    int selectedFocusIndex,
                                                    int[] exactDisplaySteps) {
        if (ensemble == null) throw new IllegalArgumentException("ensemble is required");
        double[][] paths = ensemble.paths();
        int totalSteps = paths[0].length - 1;
        int[] displaySteps = PathEnsembleService.exactDisplayStepIndices(
                totalSteps, exactDisplaySteps);
        List<Integer> selected = selectedSourcePathIndices == null
                ? List.of() : List.copyOf(selectedSourcePathIndices);
        int focusIndex = selectedFocusIndex;
        if (selected.isEmpty()) {
            Integer[] terminalOrder = new Integer[paths.length];
            for (int p = 0; p < paths.length; p++) terminalOrder[p] = p;
            java.util.Arrays.sort(terminalOrder, java.util.Comparator
                    .comparingDouble((Integer p) -> paths[p][paths[p].length - 1])
                    .thenComparingInt(Integer::intValue));
            int sampleCount = Math.min(48, paths.length);
            focusIndex = sampleCount == 0 ? -1 : Quantiles.index(sampleCount, .50);
            List<Integer> normalized = new ArrayList<>(sampleCount);
            for (int slot = 0; slot < sampleCount; slot++) {
                int at = sampleCount == 1 || slot == focusIndex
                        ? Quantiles.index(paths.length, .50)
                        : Quantiles.index(paths.length, (double) slot / (sampleCount - 1));
                normalized.add(terminalOrder[at]);
            }
            selected = List.copyOf(normalized);
        }
        if (focusIndex < 0 || focusIndex >= selected.size()) {
            throw new IllegalArgumentException("preview focus index must name a selected source path");
        }
        for (int sourcePathIndex : selected) {
            if (sourcePathIndex < 0 || sourcePathIndex >= paths.length) {
                throw new IllegalArgumentException("preview source path index " + sourcePathIndex
                        + " lies outside the stored ensemble");
            }
            if (paths[sourcePathIndex].length <= totalSteps) {
                throw new IllegalArgumentException("preview source path is shorter than the shared grid");
            }
        }

        int spd = Math.max(1, ensemble.spec().stepsPerDay());
        double[] values = new double[paths.length];
        List<PreviewStepBand> stepBands = new ArrayList<>(displaySteps.length);
        for (int step : displaySteps) {
            for (int p = 0; p < paths.length; p++) values[p] = paths[p][step];
            double[] sorted = values.clone();
            java.util.Arrays.sort(sorted);
            stepBands.add(new PreviewStepBand(step, (double) step / spd,
                    round2(Quantiles.of(sorted, 0.10)), round2(Quantiles.of(sorted, 0.25)),
                    round2(Quantiles.of(sorted, 0.50)), round2(Quantiles.of(sorted, 0.75)),
                    round2(Quantiles.of(sorted, 0.90))));
        }
        List<List<Double>> samples = new ArrayList<>(selected.size());
        for (int sourcePathIndex : selected) {
            List<Double> sample = new ArrayList<>(displaySteps.length);
            for (int step : displaySteps) sample.add(round2(paths[sourcePathIndex][step]));
            samples.add(List.copyOf(sample));
        }
        List<Integer> selectedSteps = java.util.Arrays.stream(displaySteps).boxed().toList();
        return new PreviewProjection(stepBands, samples, selected, focusIndex,
                new PreviewProjectionMetadata(PREVIEW_PROJECTION_VERSION, totalSteps + 1,
                        displaySteps.length, selectedSteps));
    }

    private static io.liftandshift.strikebench.market.MarketMode mode(String world) {
        return "observed".equals(world) ? io.liftandshift.strikebench.market.MarketMode.OBSERVED
                : "demo".equals(world) ? io.liftandshift.strikebench.market.MarketMode.DEMO
                : io.liftandshift.strikebench.market.MarketMode.SIMULATED;
    }

    private static MarketImpliedRange marketImpliedRange(double spot, int horizonSessions, MarketVolInput input,
                                                          double riskFreeRate) {
        if (input == null) return null;
        return MarketImpliedRange.forScenarioHorizon(spot, input.atmIv(),
                new io.liftandshift.strikebench.pricing.ExpectedMove.ScenarioHorizon(horizonSessions),
                input.expiration() == null ? null : input.expiration().toString(),
                input.expirationCalendarDays(), riskFreeRate);
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
                medianTouch = round2(Quantiles.of(td, 0.50));
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
        return new TerminalDistribution(round2(Quantiles.of(sorted, 0.05)),
                round2(Quantiles.of(sorted, 0.16)), round2(Quantiles.of(sorted, 0.50)),
                round2(Quantiles.of(sorted, 0.84)), round2(Quantiles.of(sorted, 0.95)),
                round2(mean), round2(sd), round2(sd / Math.sqrt(n)));
    }

    private static double maxProbabilityMargin95(int paths) { return 1.96 * Math.sqrt(0.25 / paths); }

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
