package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.research.BootstrapSampler;
import io.liftandshift.strikebench.research.ResearchQuestionEngine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import io.liftandshift.strikebench.util.DataUnavailableException;

/**
 * The one source of price-path ensembles. It owns lane-aware anchors, bootstrap history and
 * empirical analog reconstruction; valuation engines consume the resulting immutable matrix.
 * Statistical interpretations remain explicit and are never blended.
 */
public final class PathEnsembleService {

    /** A bounded, source-indexed path for motion/rendering.  These are never re-simulated paths. */
    public record DisplayPath(int sourcePathIndex, double[] prices, double terminalQuantile,
                              double waypointDistance, boolean withinExplicitTolerance,
                              String role) {
        public DisplayPath {
            prices = prices == null ? new double[0] : prices.clone();
        }

        @Override public double[] prices() { return prices.clone(); }
    }

    /**
     * A UI projection over one immutable ensemble artifact.  When an authored scenario is supplied,
     * paths are ranked by their observed distance to its pins; no new paths are generated and the
     * selection therefore retains the source ensemble's provenance and reproducibility.
     */
    public record DisplaySelectionReceipt(String version, String rule, int requestedLimit,
                                          int returnedPathCount, int sourcePathCount,
                                          int waypointCount, int explicitToleranceCount,
                                          int withinToleranceCount, int selectedWithinToleranceCount,
                                          int sourcePointCount, int returnedPointCount,
                                          int focusSourcePathIndex,
                                          double focusTerminalQuantile,
                                          double focusWaypointDistance) {}

    /** Step-resolution quantiles over the selected neighborhood, in trading-session units. */
    public record DisplayBand(int step, double sessionProgress,
                              double p10, double p25, double p50, double p75, double p90) {}

    /**
     * A non-persisted display-conditioning pin. Unlike an authored scenario day pin, session
     * progress may be fractional so a one-session fan can retain an opening, middle, and closing
     * journey on its stored intraday grid.
     */
    public record DisplayWaypoint(double sessionProgress, double priceRatio, Double tolerance) {
        public DisplayWaypoint {
            if (!(sessionProgress > 0) || !Double.isFinite(sessionProgress)) {
                throw new IllegalArgumentException("display waypoint sessionProgress must be positive and finite");
            }
            if (!(priceRatio > 0) || !Double.isFinite(priceRatio)) {
                throw new IllegalArgumentException("display waypoint priceRatio must be positive and finite");
            }
            if (tolerance != null && (!(tolerance >= 0) || !Double.isFinite(tolerance))) {
                throw new IllegalArgumentException("display waypoint tolerance must be non-negative and finite");
            }
        }
    }

    public record DisplayProjection(List<DisplayPath> paths, int totalPathCount,
                                    String selection, List<DisplayBand> bands,
                                    String bandBasis, int bandPathCount,
                                    DisplaySelectionReceipt receipt,
                                    String interpretation) {
        public DisplayProjection {
            paths = paths == null ? List.of() : List.copyOf(paths);
            bands = bands == null ? List.of() : List.copyOf(bands);
        }
    }

    /** Keep visual motion bounded even when an outcome artifact contains thousands of paths. */
    public static final int MAX_DISPLAY_PATHS = 60;
    /**
     * A wire/display series is a view over the full stored matrix, not another stored artifact.
     * Bounding each series prevents a valid high-resolution fan (up to 72,576 source steps) from
     * expanding into millions of JSON numbers. The source matrix, ranking and quantiles remain at
     * full authority; only the evenly spaced rendering checkpoints are projected.
     */
    public static final int MAX_DISPLAY_POINTS_PER_SERIES = 1_025;
    public static final String DISPLAY_SELECTION_VERSION = "ensemble-display-selection-2";

    public enum Basis { PARAMETRIC, HISTORICAL_ANALOGS, CONDITIONAL_BOOTSTRAP }

    public record Scope(String symbol, String worldId, AnalysisContext analysis) {
        public Scope {
            symbol = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
            worldId = worldId == null || worldId.isBlank() ? "observed" : worldId;
            analysis = analysis == null ? AnalysisContext.OBSERVED : analysis;
            if (symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        }
    }

    public record Ensemble(Basis basis, Scope scope, double spot, ScenarioSpec spec,
                           double[][] paths, ResearchQuestionEngine.QuestionResult study,
                           String modelVersion, LocalDate anchorDate) {
        public Ensemble {
            if (paths == null || paths.length == 0) throw new IllegalArgumentException("no paths in the ensemble");
            anchorDate = anchorDate == null ? LocalDate.of(1970, 1, 1) : anchorDate;
        }

        /** Pre-calendar constructor kept for tests and restored legacy artifacts. */
        public Ensemble(Basis basis, Scope scope, double spot, ScenarioSpec spec,
                        double[][] paths, ResearchQuestionEngine.QuestionResult study,
                        String modelVersion) {
            this(basis, scope, spot, spec, paths, study, modelVersion, LocalDate.of(1970, 1, 1));
        }

        /**
         * Standing decision 9's label, derived from the spec so it can never drift out of sync
         * with the fan: NONE without authored waypoints; EXACT_CONDITIONAL only for Gaussian
         * models; GUIDED_INTERPOLATION for everything else. Every downstream surface that shows
         * a pinned fan must carry this.
         */
        public PathGenerator.WaypointFill waypointFill() { return PathGenerator.waypointFill(spec); }
    }

    private final MarketDataService market;
    private final Clock clock;
    private final PathGenerator generator;
    private final ResearchQuestionEngine research;

    public PathEnsembleService(MarketDataService market, Clock clock) {
        this.market = market;
        this.clock = clock;
        this.generator = new PathGenerator();
        this.research = new ResearchQuestionEngine(market, clock);
    }

    /**
     * Select a small deterministic display subset from an existing artifact.  A scenario's pins
     * select the nearest original paths, rather than silently manufacturing a new conditional fan.
     * Explicit tolerances are reported per path, while pin-only scenarios still receive an honest
     * nearest-path projection for animation.
     */
    public DisplayProjection displayPaths(Ensemble ensemble, ScenarioSpec authoredScenario, int requested) {
        if (ensemble == null) throw new IllegalArgumentException("ensemble is required");
        ScenarioSpec scenario = authoredScenario == null ? null : authoredScenario.sane();
        List<DisplayWaypoint> waypoints = scenario == null ? List.of() : scenario.waypoints().stream()
                .map(pin -> new DisplayWaypoint(pin.dayIndex(), pin.priceRatio(), pin.tolerance()))
                .toList();
        return displayPathsAtProgress(ensemble, waypoints, requested);
    }

    /** Condition a display projection at exact points on the stored intraday session grid. */
    public DisplayProjection displayPathsAtProgress(Ensemble ensemble,
                                                     List<DisplayWaypoint> rawWaypoints,
                                                     int requested) {
        if (ensemble == null) throw new IllegalArgumentException("ensemble is required");
        int limit = Math.clamp(requested <= 0 ? 8 : requested, 1, MAX_DISPLAY_PATHS);
        double[][] source = ensemble.paths();
        List<DisplayWaypoint> waypoints = rawWaypoints == null ? List.of() : List.copyOf(rawWaypoints);
        double prior = 0;
        for (DisplayWaypoint waypoint : waypoints) {
            if (waypoint.sessionProgress() <= prior) {
                throw new IllegalArgumentException("display waypoints must be strictly ordered by session progress");
            }
            if (waypoint.sessionProgress() > ensemble.spec().horizonDays()) {
                throw new IllegalArgumentException("display waypoint session progress "
                        + waypoint.sessionProgress() + " lies beyond the stored ensemble horizon");
            }
            prior = waypoint.sessionProgress();
        }
        List<RankedPath> ranked = new ArrayList<>(source.length);
        boolean constrained = !waypoints.isEmpty();
        for (int index = 0; index < source.length; index++) {
            double[] path = source[index];
            if (path == null || path.length == 0) continue;
            ranked.add(rank(path, index, ensemble, waypoints));
        }
        if (ranked.isEmpty()) throw new IllegalArgumentException("ensemble has no displayable paths");
        int sourceSteps = ranked.stream().mapToInt(path -> path.prices.length).min().orElse(1) - 1;
        int[] displaySteps = displayStepIndices(sourceSteps);
        List<RankedPath> terminalRanked = new ArrayList<>(ranked);
        terminalRanked.sort(Comparator.comparingDouble(RankedPath::terminal).thenComparingInt(RankedPath::index));
        for (int i = 0; i < terminalRanked.size(); i++) {
            terminalRanked.get(i).terminalQuantile = terminalRanked.size() == 1
                    ? .5 : (double) i / (terminalRanked.size() - 1);
        }
        if (constrained) {
            ranked.sort(Comparator.comparingDouble(RankedPath::distance)
                    .thenComparingInt(RankedPath::index));
        } else {
            // A fan without a named scenario should show terminal quantiles, not the first N RNG rows.
            ranked = terminalRanked;
        }
        List<DisplayPath> chosen = new ArrayList<>(Math.min(limit, ranked.size()));
        if (constrained || ranked.size() <= limit) {
            int chosenCount = Math.min(limit, ranked.size());
            int focusIndex = constrained ? 0 : (chosenCount - 1) / 2;
            for (int i = 0; i < chosenCount; i++) {
                chosen.add(ranked.get(i).display(i == focusIndex ? "FOCUS" : "CONTEXT", displaySteps));
            }
        } else {
            int focusSlot = limit / 2;
            int medianAt = (ranked.size() - 1) / 2;
            for (int slot = 0; slot < limit; slot++) {
                int at = limit == 1 ? medianAt
                        : slot == focusSlot ? medianAt
                        : (int) Math.round((double) slot * (ranked.size() - 1) / (limit - 1));
                chosen.add(ranked.get(at).display(slot == focusSlot ? "FOCUS" : "CONTEXT", displaySteps));
            }
        }
        String selection = constrained ? "NEAREST_AUTHORED_WAYPOINTS" : "TERMINAL_QUANTILES";
        DisplayPath focus = chosen.stream().filter(path -> "FOCUS".equals(path.role()))
                .findFirst().orElseThrow();
        int explicitToleranceCount = (int) waypoints.stream()
                .filter(waypoint -> waypoint.tolerance() != null).count();
        int withinToleranceCount = explicitToleranceCount == 0 ? 0
                : (int) ranked.stream().filter(path -> path.withinExplicitTolerance).count();
        int selectedWithinToleranceCount = explicitToleranceCount == 0 ? 0
                : (int) chosen.stream().filter(DisplayPath::withinExplicitTolerance).count();
        // An unconstrained fan's bands describe the complete stored matrix. A conditioned fan's
        // bands use a statistically sturdier neighborhood than the bounded display-path list:
        // the nearest quintile plus every path satisfying all explicit tolerances. The basis and
        // count travel with the projection so that distinction is inspectable, not implied.
        List<RankedPath> bandNeighborhood;
        if (constrained) {
            int nearestCount = Math.min(ranked.size(), Math.max(chosen.size(),
                    (int) Math.ceil(ranked.size() * .20)));
            bandNeighborhood = new ArrayList<>(ranked.subList(0, nearestCount));
            if (explicitToleranceCount > 0) {
                java.util.Set<Integer> included = bandNeighborhood.stream()
                        .map(RankedPath::index).collect(java.util.stream.Collectors.toSet());
                for (RankedPath path : ranked) {
                    if (path.withinExplicitTolerance && included.add(path.index())) {
                        bandNeighborhood.add(path);
                    }
                }
            }
        } else {
            bandNeighborhood = ranked;
        }
        List<double[]> bandPaths = bandNeighborhood.stream().map(path -> path.prices).toList();
        List<DisplayBand> bands = displayBands(bandPaths,
                Math.max(1, ensemble.spec().stepsPerDay()), displaySteps);
        String bandBasis = constrained
                ? "CONDITIONED_NEAREST_QUINTILE_PLUS_FULL_TOLERANCE_SET"
                : "FULL_STORED_ENSEMBLE";
        var receipt = new DisplaySelectionReceipt(DISPLAY_SELECTION_VERSION, selection, limit,
                chosen.size(), ranked.size(), waypoints.size(),
                explicitToleranceCount, withinToleranceCount, selectedWithinToleranceCount,
                sourceSteps + 1, displaySteps.length,
                focus.sourcePathIndex(),
                focus.terminalQuantile(), focus.waypointDistance());
        String sampling = displaySteps.length < sourceSteps + 1
                ? " The wire projection contains " + displaySteps.length + " deterministic checkpoints from "
                    + (sourceSteps + 1) + " stored points per path; endpoints are retained."
                : " All " + displaySteps.length + " stored checkpoints are included.";
        String interpretation = constrained
                ? "Original stored-fan paths ranked by distance to the authored waypoints; bands use the "
                        + bandPaths.size() + "-path nearest/tolerance neighborhood and no new paths were generated."
                        + sampling
                : "Representative terminal quantiles selected from the original stored fan; bands use all "
                        + ranked.size() + " stored paths and no new paths were generated." + sampling;
        return new DisplayProjection(chosen, source.length, selection, bands, bandBasis,
                bandPaths.size(), receipt, interpretation);
    }

    private static List<DisplayBand> displayBands(List<double[]> paths, int stepsPerDay,
                                                   int[] displaySteps) {
        if (paths.isEmpty()) return List.of();
        if (displaySteps.length == 0) return List.of();
        List<DisplayBand> bands = new ArrayList<>(displaySteps.length);
        double[] values = new double[paths.size()];
        for (int step : displaySteps) {
            for (int i = 0; i < paths.size(); i++) values[i] = paths.get(i)[step];
            java.util.Arrays.sort(values);
            bands.add(new DisplayBand(step, (double) step / Math.max(1, stepsPerDay),
                    roundedQuantile(values, .10), roundedQuantile(values, .25),
                    roundedQuantile(values, .50), roundedQuantile(values, .75),
                    roundedQuantile(values, .90)));
        }
        return List.copyOf(bands);
    }

    /** Deterministic source-step projection shared by every serialized path/canvas view. */
    static int[] displayStepIndices(int totalSteps) {
        int steps = Math.max(0, totalSteps);
        int pointCount = Math.min(steps + 1, MAX_DISPLAY_POINTS_PER_SERIES);
        int[] indices = new int[pointCount];
        if (pointCount == 1) return indices;
        for (int slot = 0; slot < pointCount; slot++) {
            indices[slot] = (int) ((long) slot * steps / (pointCount - 1));
        }
        return indices;
    }

    private static double[] displayPrices(double[] source, int[] displaySteps) {
        double[] projected = new double[displaySteps.length];
        for (int i = 0; i < displaySteps.length; i++) projected[i] = source[displaySteps[i]];
        return projected;
    }

    private static double roundedQuantile(double[] sorted, double probability) {
        if (sorted.length == 0) return Double.NaN;
        int index = Math.clamp((int) Math.round(probability * (sorted.length - 1)),
                0, sorted.length - 1);
        return Math.round(sorted[index] * 100.0) / 100.0;
    }

    private static RankedPath rank(double[] path, int index, Ensemble ensemble,
                                   List<DisplayWaypoint> waypoints) {
        double spot = ensemble.spot();
        if (!(spot > 0)) throw new IllegalArgumentException("ensemble spot must be positive");
        if (waypoints == null || waypoints.isEmpty()) {
            return new RankedPath(index, path, 0, true);
        }
        int stepsPerDay = Math.max(1, ensemble.spec().stepsPerDay());
        double squaredDistance = 0;
        boolean withinAllExplicitTolerances = true;
        for (DisplayWaypoint waypoint : waypoints) {
            int step = (int) Math.round(waypoint.sessionProgress() * stepsPerDay);
            if (step >= path.length) {
                throw new IllegalArgumentException("scenario waypoint at session progress "
                        + waypoint.sessionProgress()
                        + " lies beyond the stored ensemble horizon");
            }
            double ratio = path[step] / spot;
            double logDistance = Math.log(Math.max(ratio, 1e-12) / waypoint.priceRatio());
            squaredDistance += logDistance * logDistance;
            if (waypoint.tolerance() != null
                    && Math.abs(ratio - waypoint.priceRatio()) > waypoint.tolerance()) {
                withinAllExplicitTolerances = false;
            }
        }
        return new RankedPath(index, path, Math.sqrt(squaredDistance / waypoints.size()),
                withinAllExplicitTolerances);
    }

    private static final class RankedPath {
        private final int index;
        private final double[] prices;
        private final double distance;
        private final boolean withinExplicitTolerance;
        private double terminalQuantile;

        private RankedPath(int index, double[] prices, double distance, boolean withinExplicitTolerance) {
            this.index = index;
            this.prices = prices;
            this.distance = distance;
            this.withinExplicitTolerance = withinExplicitTolerance;
        }

        int index() { return index; }
        double distance() { return distance; }
        double terminal() { return prices[prices.length - 1]; }
        DisplayPath display(String role, int[] displaySteps) {
            return new DisplayPath(index, displayPrices(prices, displaySteps), terminalQuantile, distance,
                    withinExplicitTolerance, role);
        }
    }

    /** Resolve the active lane's current anchor. Fabricated fallback prices are never invented. */
    public double anchorSpot(Scope scope) {
        return market.quote(scope.symbol(), scope.worldId())
                .map(q -> q.mark())
                .filter(java.util.Objects::nonNull)
                .map(BigDecimal::doubleValue)
                .filter(v -> v > 0)
                .orElseThrow(() -> new DataUnavailableException(
                        "No price for " + scope.symbol() + " — this analysis needs a price in the active market."));
    }

    public Ensemble build(Scope scope, Basis basis, ScenarioSpec raw,
                          ResearchQuestionEngine.RunRequest studyRequest) {
        return build(scope, basis, raw, studyRequest, anchorSpot(scope));
    }

    /** Build against an already-captured entry-book spot so paths and package pricing share t0. */
    public Ensemble build(Scope scope, Basis basis, ScenarioSpec raw,
                          ResearchQuestionEngine.RunRequest studyRequest, double spot) {
        if (basis == null) throw new IllegalArgumentException("path basis is required");
        if (!(spot > 0)) throw new IllegalArgumentException("path anchor must be positive");
        ScenarioSpec spec = raw == null ? null : raw.sane();
        if (spec == null) throw new IllegalArgumentException("scenario specification is required");

        if (basis == Basis.PARAMETRIC) {
            LocalDate anchor = anchorDate(scope);
            return new Ensemble(basis, scope, spot, spec,
                    generator.generate(spec, spot, historicalLogReturns(scope),
                            spec.calendarStepYears(anchor)), null,
                    PathGenerator.MODEL_VERSION, anchor);
        }
        if (studyRequest == null) {
            throw new IllegalArgumentException(basis + " requires a historical study request");
        }
        var normalized = new ResearchQuestionEngine.RunRequest(studyRequest.key(), scope.symbol(),
                studyRequest.from(), studyRequest.to(), studyRequest.params());
        var study = research.run(normalized, scope.analysis(), scope.worldId());
        return fromStudy(scope, basis, spec, study, spot);
    }

    /** Build from the exact Plan-owned study result instead of silently re-deriving after data changes. */
    public Ensemble fromStudy(Scope scope, Basis basis, ScenarioSpec raw,
                              ResearchQuestionEngine.QuestionResult study, double spot) {
        if (basis != Basis.HISTORICAL_ANALOGS && basis != Basis.CONDITIONAL_BOOTSTRAP) {
            throw new IllegalArgumentException("a historical study requires a historical path basis");
        }
        if (study == null) throw new IllegalArgumentException("historical study result is required");
        if (!(spot > 0)) throw new IllegalArgumentException("path anchor must be positive");
        ScenarioSpec spec = raw == null ? null : raw.sane();
        if (spec == null) throw new IllegalArgumentException("scenario specification is required");
        List<List<Double>> analogs = study.analogPaths();
        if (analogs == null || analogs.size() < 5) {
            throw new IllegalArgumentException("Only " + (analogs == null ? 0 : analogs.size())
                    + " historical analogs match this condition — at least 5 are required.");
        }
        if (basis == Basis.CONDITIONAL_BOOTSTRAP) {
            analogs = BootstrapSampler.resamplePaths(analogs,
                    Math.max(analogs.size(), spec.paths()), spec.seed());
        }
        ScenarioSpec empiricalSpec = new ScenarioSpec(spec.model(), spec.shape(), study.forwardDays(), 1,
                spec.driftAnnual(), spec.volAnnual(), spec.jumpsPerYear(), spec.jumpMean(),
                spec.jumpVol(), spec.tailNu(), spec.heston(), spec.seed(), analogs.size());
        double[][] absolute = new double[analogs.size()][];
        for (int i = 0; i < analogs.size(); i++) {
            List<Double> relative = analogs.get(i);
            absolute[i] = new double[relative.size()];
            for (int k = 0; k < relative.size(); k++) absolute[i][k] = spot * relative.get(k);
        }
        String version = basis == Basis.HISTORICAL_ANALOGS
                ? "historical-analogs-1" : "conditional-bootstrap-1";
        return new Ensemble(basis, scope, spot, empiricalSpec, absolute, study, version, anchorDate(scope));
    }

    /** Lane date used by generation, valuation, session labels, and the immutable receipt. */
    private LocalDate anchorDate(Scope scope) {
        return market.simInstant(scope.worldId())
                .map(i -> LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                .orElseGet(() -> LocalDate.now(clock));
    }

    /** Lane- and dataset-aware inputs for the block-bootstrap path model. */
    public double[] historicalLogReturns(Scope scope) {
        try {
            LocalDate to = market.simInstant(scope.worldId())
                    .map(i -> LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                    .orElseGet(() -> LocalDate.now(clock));
            List<Candle> candles = market.candleSeries(scope.symbol(), to.minusYears(2), to,
                    scope.worldId(), scope.analysis()).candles();
            if (candles.size() < 30) return null;
            double[] returns = new double[candles.size() - 1];
            for (int i = 1; i < candles.size(); i++) {
                returns[i - 1] = Math.log(candles.get(i).close().doubleValue()
                        / candles.get(i - 1).close().doubleValue());
            }
            return returns;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
