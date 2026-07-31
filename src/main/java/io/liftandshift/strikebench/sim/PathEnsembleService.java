package io.liftandshift.strikebench.sim;
import static io.liftandshift.strikebench.util.Numbers.round2;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.DataProvenance;
import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.pricing.LogReturnStatistics;
import io.liftandshift.strikebench.research.BootstrapSampler;
import io.liftandshift.strikebench.research.ResearchQuestionEngine;
import io.liftandshift.strikebench.util.Quantiles;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.HexFormat;
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
                                          Double withinTolerancePct,
                                          int sourcePointCount, int returnedPointCount,
                                          List<Integer> displaySteps,
                                          int focusSourcePathIndex,
                                          double focusTerminalQuantile,
                                          double focusWaypointDistance) {
        public DisplaySelectionReceipt {
            displaySteps = displaySteps == null ? List.of() : List.copyOf(displaySteps);
        }
    }

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
    public static final String DISPLAY_SELECTION_VERSION = "ensemble-display-selection-3";
    public static final String JOINT_MODEL_VERSION = PathGenerator.JOINT_MODEL_VERSION;
    public static final int MIN_JOINT_RETURN_SESSIONS = 30;

    public enum Basis {
        PARAMETRIC, HISTORICAL_ANALOGS, CONDITIONAL_BOOTSTRAP,
        /** A member of one synchronized multi-symbol artifact; never constructed independently. */
        JOINT_ALIGNED_BOOTSTRAP
    }

    public record Scope(String symbol, String worldId, AnalysisContext analysis) {
        public Scope {
            symbol = Symbol.normalize(symbol);
            worldId = worldId == null || worldId.isBlank() ? "observed" : worldId;
            analysis = analysis == null ? AnalysisContext.OBSERVED : analysis;
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

    /**
     * Replays an immutable artifact's exact return paths from a new observed anchor and over a
     * shorter current horizon. This is a projection, not a newly simulated ensemble: path indexes,
     * remaining relative moves, model provenance, and source ordering are retained. The elapsed
     * prefix is discarded, each remaining suffix is rebased from its own value at the new anchor,
     * and the matrix is truncated on an existing step boundary.
     *
     * <p>The caller must publish both the source-artifact identity and the new anchor receipt.
     * Keeping this transformation here prevents controllers or browsers from independently
     * inventing "from now" path math.</p>
     */
    public Ensemble reanchoredProjection(Ensemble source, double newSpot,
                                         LocalDate newAnchorDate, int requestedHorizonDays) {
        if (source == null) throw new IllegalArgumentException("source ensemble is required");
        if (!(newSpot > 0) || !Double.isFinite(newSpot)) {
            throw new IllegalArgumentException("projection anchor spot must be positive and finite");
        }
        if (newAnchorDate == null) {
            throw new IllegalArgumentException("projection anchor date is required");
        }
        if (requestedHorizonDays < 1) {
            throw new IllegalArgumentException("projection horizon must contain at least one trading session");
        }
        int stepsPerDay = Math.max(1, source.spec().stepsPerDay());
        int availableSteps = source.spec().totalSteps();
        for (double[] path : source.paths()) {
            if (path == null || path.length < 2) {
                throw new IllegalArgumentException("source ensemble contains an incomplete path");
            }
            availableSteps = Math.min(availableSteps, path.length - 1);
        }
        LocalDate sourceAnchor = source.anchorDate();
        boolean datedSource = sourceAnchor != null
                && sourceAnchor.isAfter(LocalDate.of(1970, 1, 1));
        if (datedSource && newAnchorDate.isBefore(sourceAnchor)) {
            throw new IllegalArgumentException("projection anchor cannot precede the source ensemble anchor");
        }
        int elapsedSessions = datedSource && newAnchorDate.isAfter(sourceAnchor)
                ? MarketHours.tradingDaysBetween(sourceAnchor, newAnchorDate) : 0;
        int elapsedSteps = Math.multiplyExact(elapsedSessions, stepsPerDay);
        int remainingSteps = Math.max(0, availableSteps - elapsedSteps);
        int horizonDays = Math.min(requestedHorizonDays, remainingSteps / stepsPerDay);
        if (horizonDays < 1) {
            throw new IllegalArgumentException("source ensemble has no complete future session "
                    + "remaining after the requested anchor date");
        }
        int projectedSteps = horizonDays * stepsPerDay;
        double[][] projected = new double[source.paths().length][projectedSteps + 1];
        for (int pathIndex = 0; pathIndex < source.paths().length; pathIndex++) {
            double[] sourcePath = source.paths()[pathIndex];
            double sourceAnchorValue = sourcePath[elapsedSteps];
            if (!(sourceAnchorValue > 0) || !Double.isFinite(sourceAnchorValue)) {
                throw new IllegalArgumentException("source ensemble contains an invalid reanchor value");
            }
            double scale = newSpot / sourceAnchorValue;
            for (int step = 0; step <= projectedSteps; step++) {
                projected[pathIndex][step] = sourcePath[elapsedSteps + step] * scale;
            }
            projected[pathIndex][0] = newSpot;
        }
        ScenarioSpec spec = source.spec();
        // Authored ratios were relative to the source spot. Re-basing every suffix from its own
        // realized anchor makes one shared new-anchor ratio impossible; the conditioned path
        // shapes remain in the immutable matrix, while the projected spec deliberately carries
        // no false replacement declaration.
        ScenarioSpec projectedSpec = new ScenarioSpec(
                spec.model(), spec.shape(), horizonDays, stepsPerDay,
                spec.driftAnnual(), spec.volAnnual(), spec.jumpsPerYear(),
                spec.jumpMean(), spec.jumpVol(), spec.tailNu(), spec.heston(),
                spec.seed(), spec.paths(), List.of());
        return new Ensemble(source.basis(), source.scope(), newSpot, projectedSpec,
                projected, source.study(), source.modelVersion(), newAnchorDate);
    }

    /** One dated close-to-close return with its session identity retained for exact alignment. */
    public record DatedReturn(LocalDate session, double logReturn) {
        public DatedReturn {
            if (session == null || !Double.isFinite(logReturn)) {
                throw new IllegalArgumentException("a finite dated return is required");
            }
        }
    }

    /** Input evidence retained separately from the aligned matrix so omissions stay inspectable. */
    public record HistoryInput(String symbol, List<DatedReturn> returns, int candleCount,
                               DataEvidence evidence) {
        public HistoryInput {
            symbol = Symbol.normalize(symbol);
            returns = returns == null ? List.of() : returns.stream()
                    .sorted(Comparator.comparing(DatedReturn::session)).toList();
            candleCount = Math.max(0, candleCount);
            evidence = evidence == null ? DataEvidence.missing("history evidence unavailable") : evidence;
        }
    }

    public record SymbolCorrelationCoverage(String symbol, int candles, int returnSessions,
                                            int alignedSessions, int omittedReturnSessions,
                                            Double realizedVolAnnual, DataEvidence evidence) {}

    public record CorrelationPair(String left, String right, double correlation, int sessions) {}

    /** Measured, date-aligned dependence evidence. Pairwise fill-forward is never used. */
    public record CorrelationEvidence(boolean available, int alignedSessions,
                                      LocalDate firstSession, LocalDate lastSession,
                                      List<SymbolCorrelationCoverage> symbols,
                                      List<CorrelationPair> pairs, DataEvidence evidence,
                                      String basis, String unavailableReason) {
        public CorrelationEvidence {
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
            pairs = pairs == null ? List.of() : List.copyOf(pairs);
            evidence = evidence == null ? DataEvidence.missing("correlation evidence unavailable") : evidence;
        }

        public Double correlation(String first, String second) {
            String a = Symbol.normalizeOptional(first);
            String b = Symbol.normalizeOptional(second);
            if (a == null || b == null) return null;
            if (a.equals(b) && symbols.stream().anyMatch(row -> row.symbol().equals(a))) return 1.0;
            return pairs.stream().filter(pair -> pair.left().equals(a) && pair.right().equals(b)
                            || pair.left().equals(b) && pair.right().equals(a))
                    .map(CorrelationPair::correlation).findFirst().orElse(null);
        }
    }

    /**
     * One immutable multi-symbol artifact. Member matrices share path and step indexes; only this
     * property makes a book sum legitimate. Independent {@link Ensemble}s outside this artifact
     * remain separate projections and must never be summed.
     */
    public record JointEnsemble(Map<String, Ensemble> members, CorrelationEvidence correlation,
                                String modelVersion, String fingerprint, LocalDate anchorDate,
                                String interpretation) {
        public JointEnsemble {
            members = members == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(members));
            if (members.isEmpty()) throw new IllegalArgumentException("joint ensemble needs members");
            if (correlation == null || !correlation.available()) {
                throw new IllegalArgumentException("joint ensemble needs available correlation evidence");
            }
            modelVersion = modelVersion == null ? JOINT_MODEL_VERSION : modelVersion;
            fingerprint = fingerprint == null ? "" : fingerprint;
            anchorDate = anchorDate == null ? LocalDate.of(1970, 1, 1) : anchorDate;
            int paths = -1, points = -1;
            for (var member : members.values()) {
                if (paths < 0) {
                    paths = member.paths().length;
                    points = member.paths()[0].length;
                }
                if (member.paths().length != paths || member.paths()[0].length != points) {
                    throw new IllegalArgumentException("joint member matrices must share path and step indexes");
                }
            }
        }

        public Ensemble member(String symbol) {
            String canonical = Symbol.normalizeOptional(symbol);
            return canonical == null ? null : members.get(canonical);
        }
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
        return displayPaths(ensemble, authoredScenario, requested, null);
    }

    /**
     * Project selected source rows on an exact, already-resolved display grid. This is the seam
     * used when a Scenario Canvas has inserted package-lifecycle boundaries into the shared grid.
     */
    public DisplayProjection displayPaths(Ensemble ensemble, ScenarioSpec authoredScenario, int requested,
                                          int[] exactDisplaySteps) {
        if (ensemble == null) throw new IllegalArgumentException("ensemble is required");
        ScenarioSpec scenario = authoredScenario == null ? null : authoredScenario.sane();
        List<DisplayWaypoint> waypoints = scenario == null ? List.of() : scenario.waypoints().stream()
                .map(pin -> new DisplayWaypoint(pin.dayIndex(), pin.priceRatio(), pin.tolerance()))
                .toList();
        return displayPathsAtProgress(ensemble, waypoints, requested, exactDisplaySteps);
    }

    /** Condition a display projection at exact points on the stored intraday session grid. */
    public DisplayProjection displayPathsAtProgress(Ensemble ensemble,
                                                     List<DisplayWaypoint> rawWaypoints,
                                                     int requested) {
        return displayPathsAtProgress(ensemble, rawWaypoints, requested, null);
    }

    /**
     * Project one exact source row as the focus while retaining the ordinary full-fan context and
     * bands. A click therefore preserves immutable path identity instead of reconstructing pins
     * and hoping nearest-neighbor selection finds the same row.
     */
    public DisplayProjection displayPathsFocusedOnSource(Ensemble ensemble, int sourcePathIndex,
                                                          int requested, int[] exactDisplaySteps) {
        if (ensemble == null) throw new IllegalArgumentException("ensemble is required");
        double[][] source = ensemble.paths();
        if (sourcePathIndex < 0 || sourcePathIndex >= source.length
                || source[sourcePathIndex] == null || source[sourcePathIndex].length == 0) {
            throw new IllegalArgumentException("sourcePathIndex is outside the stored ensemble");
        }
        DisplayProjection base = displayPathsAtProgress(
                ensemble, List.of(), requested, exactDisplaySteps);
        List<DisplayPath> paths = new ArrayList<>(base.paths());
        int existing = -1, focus = -1;
        for (int i = 0; i < paths.size(); i++) {
            if (paths.get(i).sourcePathIndex() == sourcePathIndex) existing = i;
            if ("FOCUS".equals(paths.get(i).role())) focus = i;
        }
        if (focus < 0) throw new IllegalStateException("display projection omitted its focus path");
        if (existing >= 0) {
            DisplayPath priorFocus = paths.get(focus);
            DisplayPath exact = paths.get(existing);
            paths.set(existing, new DisplayPath(exact.sourcePathIndex(), exact.prices(),
                    exact.terminalQuantile(), 0, true, "FOCUS"));
            if (existing != focus) {
                paths.set(focus, new DisplayPath(priorFocus.sourcePathIndex(), priorFocus.prices(),
                        priorFocus.terminalQuantile(), priorFocus.waypointDistance(),
                        priorFocus.withinExplicitTolerance(), "CONTEXT"));
            }
        } else {
            int sourceSteps = source[sourcePathIndex].length - 1;
            int[] displaySteps = exactDisplaySteps == null
                    ? displayStepIndices(sourceSteps)
                    : exactDisplayStepIndices(sourceSteps, exactDisplaySteps);
            double terminal = source[sourcePathIndex][sourceSteps];
            long below = 0, comparable = 0;
            for (int i = 0; i < source.length; i++) {
                if (source[i] == null || source[i].length <= sourceSteps) continue;
                comparable++;
                double other = source[i][sourceSteps];
                if (other < terminal || other == terminal && i < sourcePathIndex) below++;
            }
            double quantile = comparable <= 1 ? .5 : (double) below / (comparable - 1);
            paths.set(focus, new DisplayPath(sourcePathIndex,
                    displayPrices(source[sourcePathIndex], displaySteps),
                    quantile, 0, true, "FOCUS"));
        }
        DisplaySelectionReceipt r = base.receipt();
        DisplaySelectionReceipt receipt = new DisplaySelectionReceipt(
                DISPLAY_SELECTION_VERSION, "EXACT_SOURCE_PATH", r.requestedLimit(),
                r.returnedPathCount(), r.sourcePathCount(), 0, 0, 0, 0, null,
                r.sourcePointCount(), r.returnedPointCount(), r.displaySteps(),
                sourcePathIndex,
                paths.stream().filter(path -> "FOCUS".equals(path.role())).findFirst()
                        .orElseThrow().terminalQuantile(),
                0);
        return new DisplayProjection(paths, base.totalPathCount(), "EXACT_SOURCE_PATH",
                base.bands(), "FULL_STORED_ENSEMBLE", base.bandPathCount(), receipt,
                "The named source row is retained exactly as focus; context paths and bands come "
                        + "from the same immutable stored ensemble and no path was reconstructed.");
    }

    /**
     * Condition and project on one exact source-step grid. Selection and band-neighborhood rules
     * are identical to the ordinary projection; only the serialized source-step identities differ.
     */
    public DisplayProjection displayPathsAtProgress(Ensemble ensemble,
                                                     List<DisplayWaypoint> rawWaypoints,
                                                     int requested,
                                                     int[] exactDisplaySteps) {
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
        int[] displaySteps = exactDisplaySteps == null
                ? displayStepIndices(sourceSteps)
                : exactDisplayStepIndices(sourceSteps, exactDisplaySteps);
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
        int explicitToleranceCount = (int) waypoints.stream()
                .filter(waypoint -> waypoint.tolerance() != null).count();
        int withinToleranceCount = explicitToleranceCount == 0 ? 0
                : (int) ranked.stream().filter(path -> path.withinExplicitTolerance).count();
        RankedPath requiredFocus = null;
        if (constrained && explicitToleranceCount > 0) {
            requiredFocus = ranked.stream().filter(path -> path.withinExplicitTolerance)
                    .findFirst()
                    .orElseThrow(() -> new DataUnavailableException(
                            "No stored market path matches every authored scenario tolerance. "
                            + "This story is unavailable on the current immutable market fan; "
                            + "adjust its move or choose an exact stored path."));
        }
        List<DisplayPath> chosen = new ArrayList<>(Math.min(limit, ranked.size()));
        if (constrained) {
            int chosenCount = Math.min(limit, ranked.size());
            RankedPath focusPath = requiredFocus == null ? ranked.getFirst() : requiredFocus;
            chosen.add(focusPath.display("FOCUS", displaySteps));
            for (RankedPath path : ranked) {
                if (chosen.size() >= chosenCount) break;
                if (path.index() != focusPath.index()) {
                    chosen.add(path.display("CONTEXT", displaySteps));
                }
            }
        } else if (ranked.size() <= limit) {
            int chosenCount = Math.min(limit, ranked.size());
            int focusIndex = Quantiles.index(chosenCount, .50);
            for (int i = 0; i < chosenCount; i++) {
                chosen.add(ranked.get(i).display(i == focusIndex ? "FOCUS" : "CONTEXT",
                        displaySteps));
            }
        } else {
            int focusSlot = limit / 2;
            int medianAt = Quantiles.index(ranked.size(), .50);
            for (int slot = 0; slot < limit; slot++) {
                int at = limit == 1 ? medianAt
                        : slot == focusSlot ? medianAt
                        : Quantiles.index(ranked.size(), (double) slot / (limit - 1));
                chosen.add(ranked.get(at).display(slot == focusSlot ? "FOCUS" : "CONTEXT", displaySteps));
            }
        }
        String selection = constrained ? "NEAREST_AUTHORED_WAYPOINTS" : "TERMINAL_QUANTILES";
        DisplayPath focus = chosen.stream().filter(path -> "FOCUS".equals(path.role()))
                .findFirst().orElseThrow();
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
                explicitToleranceCount == 0 || ranked.isEmpty()
                        ? null : 100.0 * withinToleranceCount / ranked.size(),
                sourceSteps + 1, displaySteps.length,
                Arrays.stream(displaySteps).boxed().toList(),
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
            bands.add(new DisplayBand(step, ScenarioSpec.sessionProgress(step, stepsPerDay),
                    round2(Quantiles.of(values, .10)), round2(Quantiles.of(values, .25)),
                    round2(Quantiles.of(values, .50)), round2(Quantiles.of(values, .75)),
                    round2(Quantiles.of(values, .90))));
        }
        return List.copyOf(bands);
    }

    /**
     * Deterministic source-step projection shared by every serialized path/canvas view.
     *
     * <p>Callers may name source steps that carry semantic boundaries (for example, an exact
     * package-settlement frame). Those steps replace their nearest non-mandatory uniform sample
     * rather than being appended, so the wire bound remains absolute and every named boundary is
     * retained exactly.
     */
    static int[] displayStepIndices(int totalSteps, int... mandatorySteps) {
        int steps = Math.max(0, totalSteps);
        int pointCount = Math.min(steps + 1, MAX_DISPLAY_POINTS_PER_SERIES);
        if (pointCount == steps + 1) {
            int[] all = new int[pointCount];
            for (int step = 0; step < pointCount; step++) all[step] = step;
            return all;
        }
        java.util.TreeSet<Integer> mandatory = new java.util.TreeSet<>();
        mandatory.add(0);
        mandatory.add(steps);
        if (mandatorySteps != null) {
            for (int step : mandatorySteps) mandatory.add(Math.clamp(step, 0, steps));
        }
        if (mandatory.size() > pointCount) {
            throw new IllegalArgumentException("mandatory display steps exceed the "
                    + MAX_DISPLAY_POINTS_PER_SERIES + "-point wire bound");
        }
        java.util.TreeSet<Integer> selected = new java.util.TreeSet<>();
        for (int slot = 0; slot < pointCount; slot++) {
            selected.add((int) ((long) slot * steps / (pointCount - 1)));
        }
        for (int required : mandatory) {
            if (selected.contains(required)) continue;
            Integer replace = null;
            long nearestDistance = Long.MAX_VALUE;
            for (int candidate : selected) {
                if (mandatory.contains(candidate)) continue;
                long distance = Math.abs((long) candidate - required);
                if (distance < nearestDistance
                        || distance == nearestDistance && (replace == null || candidate < replace)) {
                    nearestDistance = distance;
                    replace = candidate;
                }
            }
            if (replace == null) {
                throw new IllegalStateException("no replaceable display step remains for mandatory boundary "
                        + required);
            }
            selected.remove(replace);
            selected.add(required);
        }
        return selected.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Validate and defensively copy a fully resolved wire grid. Exact grids are not resampled:
     * index {@code i} must identify the same source step on every price, band, and valued position.
     */
    public static int[] exactDisplayStepIndices(int totalSteps, int[] rawSteps) {
        int steps = Math.max(0, totalSteps);
        if (rawSteps == null || rawSteps.length == 0) {
            throw new IllegalArgumentException("an exact display grid is required");
        }
        if (rawSteps.length > MAX_DISPLAY_POINTS_PER_SERIES) {
            throw new IllegalArgumentException("exact display grid exceeds the "
                    + MAX_DISPLAY_POINTS_PER_SERIES + "-point wire bound");
        }
        int[] exact = rawSteps.clone();
        if (exact[0] != 0 || exact[exact.length - 1] != steps) {
            throw new IllegalArgumentException("exact display grid must retain source endpoints 0 and " + steps);
        }
        for (int i = 0; i < exact.length; i++) {
            if (exact[i] < 0 || exact[i] > steps) {
                throw new IllegalArgumentException("exact display step " + exact[i]
                        + " lies outside source range 0.." + steps);
            }
            if (i > 0 && exact[i] <= exact[i - 1]) {
                throw new IllegalArgumentException("exact display steps must be strictly increasing");
            }
        }
        return exact;
    }

    private static double[] displayPrices(double[] source, int[] displaySteps) {
        double[] projected = new double[displaySteps.length];
        for (int i = 0; i < displaySteps.length; i++) projected[i] = source[displaySteps[i]];
        return projected;
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
            spec = spec.resolvedForGeneration();
            LocalDate anchor = anchorDate(scope);
            return new Ensemble(basis, scope, spot, spec,
                    generator.generate(spec, spot, historicalLogReturns(scope),
                            spec.calendarStepYears(anchor)), null,
                    PathGenerator.MODEL_VERSION, anchor);
        }
        if (basis == Basis.JOINT_ALIGNED_BOOTSTRAP) {
            throw new IllegalArgumentException("JOINT_ALIGNED_BOOTSTRAP must be built through buildJoint");
        }
        if (studyRequest == null) {
            throw new IllegalArgumentException(basis + " requires a historical study request");
        }
        var normalized = new ResearchQuestionEngine.RunRequest(studyRequest.key(), scope.symbol(),
                studyRequest.from(), studyRequest.to(), studyRequest.params());
        var study = research.run(normalized, scope.analysis(), scope.worldId());
        return fromStudy(scope, basis, spec, study, spot);
    }

    /** Build the canonical joint artifact from lane-owned histories and already-captured spots. */
    public JointEnsemble buildJoint(List<Scope> rawScopes, ScenarioSpec raw,
                                    Map<String, Double> rawSpots) {
        if (market == null) throw new IllegalStateException("market data is required for a joint ensemble");
        List<Scope> scopes = normalizeJointScopes(rawScopes);
        Scope first = scopes.getFirst();
        LocalDate anchor = anchorDate(first);
        LocalDate from = anchor.minusYears(2);
        List<HistoryInput> histories = new ArrayList<>();
        for (Scope scope : scopes) {
            var series = market.candleSeries(scope.symbol(), from, anchor,
                    scope.worldId(), scope.analysis());
            histories.add(history(scope.symbol(), series.candles(), series.evidence()));
        }
        return buildJointFromEvidence(scopes, raw, rawSpots, histories, anchor);
    }

    /**
     * Build the automatic Book artifact from history already resident in StrikeBench. Unlike
     * {@link #buildJoint}, this method never allows the market-data read to contact a provider.
     * Every member is anchored to the latest close date shared by the entire book, so the spot
     * vector and measured return matrix have one honest as-of date rather than a mixture of stale
     * and current symbols.
     */
    public JointEnsemble buildJointFromLocalHistory(List<Scope> rawScopes, ScenarioSpec raw) {
        if (market == null) throw new IllegalStateException("market data is required for a joint ensemble");
        List<Scope> scopes = normalizeJointScopes(rawScopes);
        Scope first = scopes.getFirst();
        LocalDate requestedTo = anchorDate(first);
        LocalDate requestedFrom = requestedTo.minusYears(2);
        LinkedHashMap<String, TreeMap<LocalDate, Candle>> candlesBySymbol = new LinkedHashMap<>();
        LinkedHashMap<String, DataEvidence> evidenceBySymbol = new LinkedHashMap<>();
        Set<LocalDate> commonDates = null;
        for (Scope scope : scopes) {
            var read = market.localCandleSeries(scope.symbol(), requestedFrom, requestedTo,
                    scope.worldId(), scope.analysis());
            TreeMap<LocalDate, Candle> dated = new TreeMap<>();
            for (Candle candle : read.series().candles()) {
                if (candle != null && candle.date() != null && candle.close() != null
                        && candle.close().signum() > 0) {
                    dated.put(candle.date(), candle);
                }
            }
            if (dated.size() < 2) {
                throw new DataUnavailableException("Local history for " + scope.symbol()
                        + " has only " + dated.size() + " usable closes; no provider acquisition "
                        + "was attempted for the automatic Book artifact.");
            }
            candlesBySymbol.put(scope.symbol(), dated);
            evidenceBySymbol.put(scope.symbol(), read.series().evidence());
            if (commonDates == null) commonDates = new LinkedHashSet<>(dated.keySet());
            else commonDates.retainAll(dated.keySet());
        }
        if (commonDates == null || commonDates.isEmpty()) {
            throw new DataUnavailableException("The Book symbols have no shared local close date; "
                    + "no provider acquisition was attempted.");
        }
        LocalDate anchor = commonDates.stream().max(Comparator.naturalOrder()).orElseThrow();
        LinkedHashMap<String, Double> spots = new LinkedHashMap<>();
        List<HistoryInput> histories = new ArrayList<>();
        for (Scope scope : scopes) {
            TreeMap<LocalDate, Candle> dated = candlesBySymbol.get(scope.symbol());
            spots.put(scope.symbol(), dated.get(anchor).close().doubleValue());
            List<Candle> throughAnchor = dated.headMap(anchor, true).values().stream().toList();
            histories.add(history(scope.symbol(), throughAnchor, evidenceBySymbol.get(scope.symbol())));
        }
        JointEnsemble built = buildJointFromEvidence(scopes, raw, spots, histories, anchor);
        return new JointEnsemble(built.members(), built.correlation(), built.modelVersion(),
                built.fingerprint(), built.anchorDate(),
                "Automatic Book generation used only history already resident in StrikeBench; "
                        + "no external provider acquisition was attempted. " + built.interpretation());
    }

    /** Testable/persistable seam: build from exact supplied history evidence without another read. */
    public JointEnsemble buildJointFromEvidence(List<Scope> rawScopes, ScenarioSpec raw,
                                                Map<String, Double> rawSpots,
                                                List<HistoryInput> histories,
                                                LocalDate anchor) {
        List<Scope> scopes = normalizeJointScopes(rawScopes);
        ScenarioSpec spec = raw == null ? null : raw.resolvedForGeneration();
        if (spec == null) throw new IllegalArgumentException("joint scenario specification is required");
        if (spec.model() != ScenarioSpec.PathModel.BLOCK_BOOTSTRAP) {
            throw new IllegalArgumentException("joint ensemble must use BLOCK_BOOTSTRAP");
        }
        LinkedHashMap<String, Double> spots = new LinkedHashMap<>();
        for (Scope scope : scopes) {
            Double spot = rawSpots == null ? null : rawSpots.get(scope.symbol());
            if (spot == null || !(spot > 0) || !Double.isFinite(spot)) {
                throw new IllegalArgumentException("joint spot unavailable for " + scope.symbol());
            }
            spots.put(scope.symbol(), spot);
        }
        validateJointLaneEvidence(scopes.getFirst(), histories);
        AlignedHistory aligned = align(histories, scopes.stream().map(Scope::symbol).toList());
        if (!aligned.evidence().available()) {
            throw new DataUnavailableException(aligned.evidence().unavailableReason());
        }
        LocalDate effectiveAnchor = anchor == null ? LocalDate.now(clock) : anchor;
        double[] stepYears = spec.calendarStepYears(effectiveAnchor);
        var generated = generator.generateJointBootstrap(spec, spots, aligned.values(), stepYears);
        LinkedHashMap<String, Ensemble> members = new LinkedHashMap<>();
        for (Scope scope : scopes) {
            members.put(scope.symbol(), new Ensemble(Basis.JOINT_ALIGNED_BOOTSTRAP, scope,
                    spots.get(scope.symbol()),
                    spec, generated.paths().get(scope.symbol()), null, JOINT_MODEL_VERSION,
                    effectiveAnchor));
        }
        String fingerprint = jointFingerprint(members, aligned.evidence(), spec);
        String interpretation = "One synchronized aligned-return block bootstrap across "
                + members.size() + " symbols and " + aligned.evidence().alignedSessions()
                + " common sessions. Path index N is the same sampled market history for every "
                + "member; this permits package P/L aggregation. Independent single-symbol fans "
                + "remain separate and are never summed.";
        return new JointEnsemble(members, aligned.evidence(), JOINT_MODEL_VERSION,
                fingerprint, effectiveAnchor, interpretation);
    }

    /** Measure the exact same evidence used by joint generation, without generating paths. */
    public CorrelationEvidence measureCorrelation(List<HistoryInput> histories) {
        List<String> symbols = histories == null ? List.of() : histories.stream()
                .map(HistoryInput::symbol).toList();
        return align(histories, symbols).evidence();
    }

    private record AlignedHistory(CorrelationEvidence evidence,
                                  Map<String, double[]> values) {}

    private static List<Scope> normalizeJointScopes(List<Scope> rawScopes) {
        if (rawScopes == null || rawScopes.isEmpty()) {
            throw new IllegalArgumentException("at least one joint scope is required");
        }
        if (rawScopes.size() > 32) throw new IllegalArgumentException("at most 32 joint symbols");
        List<Scope> scopes = rawScopes.stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(Scope::symbol)).toList();
        if (scopes.size() != rawScopes.size()) {
            throw new IllegalArgumentException("joint scopes cannot contain nulls");
        }
        Set<String> symbols = new LinkedHashSet<>();
        Scope first = scopes.getFirst();
        for (Scope scope : scopes) {
            if (!symbols.add(scope.symbol())) {
                throw new IllegalArgumentException("duplicate joint symbol " + scope.symbol());
            }
            if (!scope.worldId().equals(first.worldId()) || !scope.analysis().equals(first.analysis())) {
                throw new IllegalArgumentException("joint symbols must share one market and analysis lane");
            }
        }
        return scopes;
    }

    private static HistoryInput history(String symbol, List<Candle> rawCandles,
                                        DataEvidence evidence) {
        List<Candle> candles = rawCandles == null ? List.of() : rawCandles.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(Candle::date)).toList();
        List<DatedReturn> returns = new ArrayList<>();
        Candle prior = null;
        for (Candle candle : candles) {
            if (prior != null && MarketHours.tradingDaysBetween(prior.date(), candle.date()) == 1
                    && prior.close() != null && candle.close() != null
                    && prior.close().signum() > 0 && candle.close().signum() > 0) {
                returns.add(new DatedReturn(candle.date(), Math.log(candle.close().doubleValue()
                        / prior.close().doubleValue())));
            }
            prior = candle;
        }
        return new HistoryInput(symbol, returns, candles.size(), evidence);
    }

    private static AlignedHistory align(List<HistoryInput> rawHistories,
                                        List<String> rawExpectedSymbols) {
        List<HistoryInput> histories = rawHistories == null ? List.of() : List.copyOf(rawHistories);
        List<String> expected = Symbol.list(rawExpectedSymbols).stream().sorted().toList();
        if (expected.isEmpty()) throw new IllegalArgumentException("correlation symbols are required");
        Map<String, HistoryInput> bySymbol = new TreeMap<>();
        Map<String, TreeMap<LocalDate, Double>> returnsBySymbol = new TreeMap<>();
        for (HistoryInput history : histories) {
            if (history == null) continue;
            if (bySymbol.putIfAbsent(history.symbol(), history) != null) {
                throw new IllegalArgumentException("duplicate history for " + history.symbol());
            }
            TreeMap<LocalDate, Double> dated = new TreeMap<>();
            for (DatedReturn value : history.returns()) {
                if (dated.putIfAbsent(value.session(), value.logReturn()) != null) {
                    throw new IllegalArgumentException("duplicate return session for " + history.symbol());
                }
            }
            returnsBySymbol.put(history.symbol(), dated);
        }
        if (!bySymbol.keySet().equals(new LinkedHashSet<>(expected))) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(bySymbol.keySet());
            Set<String> extra = new LinkedHashSet<>(bySymbol.keySet());
            extra.removeAll(expected);
            throw new IllegalArgumentException("history symbols do not match joint scopes; missing="
                    + missing + ", extra=" + extra);
        }
        Set<LocalDate> common = null;
        for (String symbol : expected) {
            if (common == null) common = new LinkedHashSet<>(returnsBySymbol.get(symbol).keySet());
            else common.retainAll(returnsBySymbol.get(symbol).keySet());
        }
        List<LocalDate> sessions = common == null ? List.of() : common.stream().sorted().toList();
        LinkedHashMap<String, double[]> aligned = new LinkedHashMap<>();
        List<SymbolCorrelationCoverage> coverage = new ArrayList<>();
        List<DataEvidence> evidenceRows = new ArrayList<>();
        boolean missingEvidence = false;
        boolean zeroVariance = false;
        for (String symbol : expected) {
            HistoryInput history = bySymbol.get(symbol);
            double[] values = new double[sessions.size()];
            for (int i = 0; i < sessions.size(); i++) {
                values[i] = returnsBySymbol.get(symbol).get(sessions.get(i));
            }
            aligned.put(symbol, values);
            Double vol = values.length < 2 ? null
                    : LogReturnStatistics.of(values).annualizedSampleStdDev();
            if (vol != null && vol <= 1e-9) zeroVariance = true;
            evidenceRows.add(history.evidence());
            missingEvidence |= history.evidence().provenance() == DataProvenance.MISSING;
            coverage.add(new SymbolCorrelationCoverage(symbol, history.candleCount(),
                    history.returns().size(), sessions.size(),
                    Math.max(0, history.returns().size() - sessions.size()), vol,
                    history.evidence()));
        }
        boolean enough = sessions.size() >= MIN_JOINT_RETURN_SESSIONS;
        boolean available = enough && !missingEvidence && !zeroVariance;
        String reason = null;
        if (!enough) {
            reason = "Only " + sessions.size() + " aligned return sessions are available; at least "
                    + MIN_JOINT_RETURN_SESSIONS + " are required for a measured joint book.";
        } else if (missingEvidence) {
            reason = "At least one symbol has missing history provenance; correlation remains unavailable.";
        } else if (zeroVariance) {
            reason = "At least one symbol has no measured return variation; correlation is undefined.";
        }
        List<CorrelationPair> pairs = new ArrayList<>();
        if (available) {
            for (int left = 0; left < expected.size(); left++) {
                for (int right = left + 1; right < expected.size(); right++) {
                    String a = expected.get(left), b = expected.get(right);
                    pairs.add(new CorrelationPair(a, b,
                            roundedCorrelation(aligned.get(a), aligned.get(b)), sessions.size()));
                }
            }
        }
        DataEvidence aggregate = DataEvidence.aggregate(evidenceRows);
        String basis = "Close-to-close log returns on the exact intersection of dated sessions across "
                + expected.size() + " symbols; no fill-forward, pairwise expansion, or generated "
                + "substitution. Each symbol discloses returns omitted by the common-session alignment.";
        CorrelationEvidence receipt = new CorrelationEvidence(available, sessions.size(),
                sessions.isEmpty() ? null : sessions.getFirst(),
                sessions.isEmpty() ? null : sessions.getLast(), coverage, pairs,
                aggregate, basis, reason);
        return new AlignedHistory(receipt, Collections.unmodifiableMap(aligned));
    }

    private static double roundedCorrelation(double[] first, double[] second) {
        double meanA = Arrays.stream(first).average().orElse(0);
        double meanB = Arrays.stream(second).average().orElse(0);
        double covariance = 0, varianceA = 0, varianceB = 0;
        for (int i = 0; i < first.length; i++) {
            double a = first[i] - meanA, b = second[i] - meanB;
            covariance += a * b;
            varianceA += a * a;
            varianceB += b * b;
        }
        double value = covariance / Math.sqrt(Math.max(1e-30, varianceA * varianceB));
        return Math.round(Math.clamp(value, -1, 1) * 1_000_000.0) / 1_000_000.0;
    }

    private static void validateJointLaneEvidence(Scope scope, List<HistoryInput> histories) {
        for (HistoryInput history : histories == null ? List.<HistoryInput>of() : histories) {
            DataProvenance provenance = history.evidence().provenance();
            boolean allowed;
            if ("observed".equalsIgnoreCase(scope.worldId())) {
                allowed = provenance == DataProvenance.OBSERVED || provenance == DataProvenance.BROKER;
            } else if ("demo".equalsIgnoreCase(scope.worldId())) {
                allowed = provenance == DataProvenance.DEMO;
            } else if (scope.analysis().synthetic()) {
                allowed = provenance == DataProvenance.MODELED || provenance == DataProvenance.OBSERVED;
            } else {
                allowed = provenance == DataProvenance.SIMULATED;
            }
            if (!allowed) {
                throw new DataUnavailableException("History for " + history.symbol() + " has "
                        + provenance + " provenance and cannot enter the " + scope.worldId()
                        + " joint book lane.");
            }
        }
    }

    private static String jointFingerprint(Map<String, Ensemble> members,
                                           CorrelationEvidence evidence,
                                           ScenarioSpec spec) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((JOINT_MODEL_VERSION + "|" + spec + "|" + evidence.firstSession()
                    + "|" + evidence.lastSession() + "|" + evidence.alignedSessions())
                    .getBytes(StandardCharsets.UTF_8));
            ByteBuffer number = ByteBuffer.allocate(Long.BYTES);
            for (var entry : members.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                for (double[] path : entry.getValue().paths()) {
                    for (double value : path) {
                        number.clear();
                        number.putLong(Double.doubleToLongBits(value));
                        digest.update(number.array());
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException("Could not identify joint path artifact", impossible);
        }
    }

    /** Build from the exact Plan-owned study result instead of silently re-deriving after data changes. */
    public Ensemble fromStudy(Scope scope, Basis basis, ScenarioSpec raw,
                              ResearchQuestionEngine.QuestionResult study, double spot) {
        if (basis != Basis.HISTORICAL_ANALOGS && basis != Basis.CONDITIONAL_BOOTSTRAP) {
            throw new IllegalArgumentException("a historical study requires a historical path basis");
        }
        if (study == null) throw new IllegalArgumentException("historical study result is required");
        if (!(spot > 0)) throw new IllegalArgumentException("path anchor must be positive");
        // These paths are already measured in the Plan-owned historical study. Validate the
        // request boundary, then publish a canonical EFFECTIVE spec containing only inputs that
        // can actually alter this artifact. Parametric drift/vol/tail/shape fields must not create
        // distinct fingerprints for byte-identical empirical paths.
        ScenarioSpec requested = raw == null ? null : raw.validated();
        if (requested == null) throw new IllegalArgumentException("scenario specification is required");
        if (!requested.waypoints().isEmpty()) {
            throw new IllegalArgumentException("historical study paths do not accept authored waypoints");
        }
        List<List<Double>> analogs = study.analogPaths();
        if (analogs == null || analogs.size() < 5) {
            throw new IllegalArgumentException("Only " + (analogs == null ? 0 : analogs.size())
                    + " historical analogs match this condition — at least 5 are required.");
        }
        if (basis == Basis.CONDITIONAL_BOOTSTRAP) {
            analogs = BootstrapSampler.resamplePaths(analogs,
                    Math.max(analogs.size(), requested.paths()), requested.seed());
        }
        if (study.forwardDays() < 1 || study.forwardDays() > 756 || analogs.size() > 5000) {
            throw new IllegalArgumentException("historical study exceeds the scenario work bounds");
        }
        long effectiveSeed = basis == Basis.CONDITIONAL_BOOTSTRAP ? requested.seed() : 0L;
        ScenarioSpec empiricalSpec = new ScenarioSpec(
                ScenarioSpec.PathModel.BLOCK_BOOTSTRAP, ScenarioSpec.Shape.CHOP,
                study.forwardDays(), 1, 0, 0, 0, 0, 0, 0, null,
                effectiveSeed, analogs.size()).validated();
        double[][] absolute = new double[analogs.size()][];
        for (int i = 0; i < analogs.size(); i++) {
            List<Double> relative = analogs.get(i);
            if (relative == null || relative.size() != study.forwardDays() + 1) {
                throw new IllegalArgumentException("historical analog " + i
                        + " does not match the study's forward-session horizon");
            }
            absolute[i] = new double[relative.size()];
            for (int k = 0; k < relative.size(); k++) {
                Double ratio = relative.get(k);
                if (ratio == null || !(ratio > 0) || !Double.isFinite(ratio)) {
                    throw new IllegalArgumentException("historical analog " + i
                            + " contains an invalid relative price");
                }
                absolute[i][k] = spot * ratio;
            }
        }
        String version = basis == Basis.HISTORICAL_ANALOGS
                ? "historical-analogs-2" : "conditional-bootstrap-2";
        return new Ensemble(basis, scope, spot, empiricalSpec, absolute, study, version, anchorDate(scope));
    }

    /** Lane date used by generation, valuation, session labels, and the immutable receipt. */
    private LocalDate anchorDate(Scope scope) {
        return market.laneToday(scope.worldId(), clock);
    }

    /** Lane- and dataset-aware inputs for the block-bootstrap path model. */
    public double[] historicalLogReturns(Scope scope) {
        try {
            LocalDate to = market.laneToday(scope.worldId(), clock);
            List<Candle> candles = market.candleSeries(scope.symbol(), to.minusYears(2), to,
                    scope.worldId(), scope.analysis()).candles();
            if (candles.size() < 30) return null;
            double[] prices = new double[candles.size()];
            for (int i = 0; i < candles.size(); i++) {
                prices[i] = candles.get(i).close().doubleValue();
            }
            return LogReturnStatistics.fromPrices(prices).returns();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
