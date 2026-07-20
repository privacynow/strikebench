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
                                          int focusSourcePathIndex,
                                          double focusTerminalQuantile,
                                          double focusWaypointDistance) {}

    public record DisplayProjection(List<DisplayPath> paths, int totalPathCount,
                                    String selection, DisplaySelectionReceipt receipt,
                                    String interpretation) {
        public DisplayProjection { paths = paths == null ? List.of() : List.copyOf(paths); }
    }

    /** Keep visual motion bounded even when an outcome artifact contains thousands of paths. */
    public static final int MAX_DISPLAY_PATHS = 24;
    public static final String DISPLAY_SELECTION_VERSION = "ensemble-display-selection-1";

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
        int limit = Math.clamp(requested <= 0 ? 8 : requested, 1, MAX_DISPLAY_PATHS);
        double[][] source = ensemble.paths();
        ScenarioSpec scenario = authoredScenario == null ? null : authoredScenario.sane();
        List<RankedPath> ranked = new ArrayList<>(source.length);
        boolean constrained = scenario != null && !scenario.waypoints().isEmpty();
        for (int index = 0; index < source.length; index++) {
            double[] path = source[index];
            if (path == null || path.length == 0) continue;
            ranked.add(rank(path, index, ensemble, scenario));
        }
        if (ranked.isEmpty()) throw new IllegalArgumentException("ensemble has no displayable paths");
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
                chosen.add(ranked.get(i).display(i == focusIndex ? "FOCUS" : "CONTEXT"));
            }
        } else {
            int focusSlot = limit / 2;
            int medianAt = (ranked.size() - 1) / 2;
            for (int slot = 0; slot < limit; slot++) {
                int at = limit == 1 ? medianAt
                        : slot == focusSlot ? medianAt
                        : (int) Math.round((double) slot * (ranked.size() - 1) / (limit - 1));
                chosen.add(ranked.get(at).display(slot == focusSlot ? "FOCUS" : "CONTEXT"));
            }
        }
        String selection = constrained ? "NEAREST_AUTHORED_WAYPOINTS" : "TERMINAL_QUANTILES";
        DisplayPath focus = chosen.stream().filter(path -> "FOCUS".equals(path.role()))
                .findFirst().orElseThrow();
        int explicitToleranceCount = scenario == null ? 0 : (int) scenario.waypoints().stream()
                .filter(waypoint -> waypoint.tolerance() != null).count();
        int withinToleranceCount = explicitToleranceCount == 0 ? 0
                : (int) ranked.stream().filter(path -> path.withinExplicitTolerance).count();
        int selectedWithinToleranceCount = explicitToleranceCount == 0 ? 0
                : (int) chosen.stream().filter(DisplayPath::withinExplicitTolerance).count();
        var receipt = new DisplaySelectionReceipt(DISPLAY_SELECTION_VERSION, selection, limit,
                chosen.size(), ranked.size(), scenario == null ? 0 : scenario.waypoints().size(),
                explicitToleranceCount, withinToleranceCount, selectedWithinToleranceCount,
                focus.sourcePathIndex(),
                focus.terminalQuantile(), focus.waypointDistance());
        String interpretation = constrained
                ? "Original stored-fan paths ranked by distance to the authored waypoints; no new paths were generated."
                : "Representative terminal quantiles selected from the original stored fan; no new paths were generated.";
        return new DisplayProjection(chosen, source.length, selection, receipt, interpretation);
    }

    private static RankedPath rank(double[] path, int index, Ensemble ensemble, ScenarioSpec scenario) {
        double spot = ensemble.spot();
        if (!(spot > 0)) throw new IllegalArgumentException("ensemble spot must be positive");
        if (scenario == null || scenario.waypoints().isEmpty()) {
            return new RankedPath(index, path, 0, true);
        }
        int stepsPerDay = Math.max(1, ensemble.spec().stepsPerDay());
        double squaredDistance = 0;
        boolean withinAllExplicitTolerances = true;
        for (ScenarioSpec.Waypoint waypoint : scenario.waypoints()) {
            int step = waypoint.dayIndex() * stepsPerDay;
            if (step >= path.length) {
                throw new IllegalArgumentException("scenario waypoint day " + waypoint.dayIndex()
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
        return new RankedPath(index, path, Math.sqrt(squaredDistance / scenario.waypoints().size()),
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
        DisplayPath display(String role) {
            return new DisplayPath(index, prices, terminalQuantile, distance,
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
