package io.liftandshift.strikebench.sim;
import static io.liftandshift.strikebench.util.Numbers.round4;
import static io.liftandshift.strikebench.util.Numbers.round2;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.util.Money;
import io.liftandshift.strikebench.util.Quantiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reprices many same-symbol packages on one already-built ensemble for the unified canvas.  It is
 * intentionally a consumer of {@link PathEnsembleService.Ensemble} and
 * {@link PathValuationKernel}; it never generates a second path set or implements another option
 * pricing rule.
 */
public final class ScenarioCanvasValuator {

    private static final long MAX_CANVAS_VALUATION_WORK = 40_000_000L;
    public static final String JOINT_BOOK_MODEL_VERSION = ScenarioCanvasSpec.MODEL_VERSION
            + "+joint-book-1";

    public record PositionInput(String key, String label, String lane, String source,
                                PathPosition position, int qty, Long entryCostCents,
                                boolean proposed) {
        public PositionInput {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("canvas position key is required");
            if (label == null || label.isBlank()) label = key;
            if (lane == null || lane.isBlank()) throw new IllegalArgumentException("canvas position lane is required");
            if (source == null || source.isBlank()) throw new IllegalArgumentException("canvas position source is required");
            if (position == null) throw new IllegalArgumentException("canvas position is required");
            qty = Math.clamp(qty, 1, 10_000);
        }
    }

    public record UnderlyingDay(int day, String sessionDate, double p10, double p50, double p90,
                                double focusPrice, double atmIv) {}
    /**
     * One focus-path point on the ensemble's stored simulation grid, and one ANIMATION FRAME of the
     * desk's scenario scrub. {@code sessionProgress} is {@code step / stepsPerDay};
     * {@code sessionDate} names the session containing that point.
     *
     * <p>{@code moveFromSpotPct} and {@code ivShiftPoints} exist so that no client has to derive
     * them: the desk used to compute "SPX → 5,830 (−2%) · IV +3" from a spot and a baseline it
     * chose itself, which made the browser the author of three displayed financial facts. Both are
     * stated here against the anchors named in {@link AnimationTrack} — percent move against
     * {@code anchorSpot} (frame 0 is exactly {@code 0.0} because {@code paths[*][0] == spot}), and
     * vol points against {@code baselineAtmIv}. They are never absent when the frame exists,
     * because both are exact functions of {@code focusPrice}/{@code atmIv} on this same frame.
     */
    public record UnderlyingStep(int step, double sessionProgress, String sessionDate,
                                 double focusPrice, double atmIv,
                                 double moveFromSpotPct, double ivShiftPoints) {}
    /**
     * THE animation contract for one canvas: the desk scrubs a continuous handle, and this names
     * the exact discrete frames it is allowed to display.
     *
     * <p>The law (program §3.1/§3.2): a client may interpolate VISUAL COORDINATES between two
     * supplied facts, but it must never print an interpolation as a new financial fact. So the desk
     * does not blend two frames; it SELECTS one. Given a scrub position {@code t} in {@code [0,1]}
     * and the focused package's {@link PositionAnimation#terminalFrameIndex()}:
     *
     * <pre>index = Math.round(t * terminalFrameIndex)</pre>
     *
     * <p>and every value it then prints comes from {@code underlyingSteps[index]} (market facts)
     * and {@code positions[i].steps[index]} (package facts). The two arrays are built from one
     * shared {@link PathEnsembleService#displayStepIndices(int, int...)} projection, so index {@code k}
     * names the same instant in both — the join is a keyed lookup, never a derivation.
     *
     * <p>Cost: the whole track ships once inside the response the desk already fetches, so a 60fps
     * scrub is {@code frameCount} pre-valued facts and zero extra backend calls.
     *
     * @param frameRule          {@code "SELECT_NEAREST_FRAME_NO_INTERPOLATION"} — the indexing law above.
     * @param frameSource        the array carrying the market side of each frame ({@code "underlyingSteps"}).
     * @param positionFrameSource the array carrying each package's side ({@code "positions[].steps"}).
     * @param frameCount         frames in the track; indices run {@code 0..frameCount-1}.
     * @param sourceStepCount    stored simulation steps behind those frames ({@code totalSteps}).
     * @param stepsPerDay        simulation steps per session, so {@code sessionProgress = step / stepsPerDay}.
     * @param anchorSpot         the underlying price {@code moveFromSpotPct} is measured from.
     * @param horizonSessions    {@code sessionProgress} of the last frame.
     * @param baselineAtmIv      the annualized ATM vol {@code ivShiftPoints} is measured from.
     * @param baselineAtmIvSource why that baseline is the right one ({@code "TRACK_FRAME_0"}).
     */
    public record AnimationTrack(String frameRule, String frameSource, String positionFrameSource,
                                 int frameCount, int sourceStepCount, int stepsPerDay,
                                 double anchorSpot, double horizonSessions,
                                 double baselineAtmIv, String baselineAtmIvSource) {}
    /**
     * THE greeks view — the single shape every backend surface serializes and every client reads:
     * a held position, one of its legs, an idea candidate, a package preview and this canvas. The
     * unit is in each name (share-equivalent delta, share-per-dollar gamma, CENTS per day of theta,
     * CENTS per vol point of vega), so nothing has to be converted or guessed downstream. There is
     * deliberately no second greeks record and no dollar-named variant (§3.1, §3.8, §5.3).
     */
    public record Greeks(double deltaShares, double gammaSharesPerDollar,
                         double thetaCentsPerDay, double vegaCentsPerPoint) {}
    public record LegDay(int day, long valueCents, long optionPriceCents, Greeks greeks, String state) {}
    /** One leg repriced at one stored focus-path step through {@link PathValuationKernel}. */
    public record LegStep(int step, double sessionProgress, long valueCents,
                          long optionPriceCents, Greeks greeks, String state) {}
    public record LegPath(int legNo, String label, String expiration, int multiplier,
                          List<LegDay> days, List<LegStep> steps) {
        public LegPath(int legNo, String label, String expiration, int multiplier,
                       List<LegDay> days) {
            this(legNo, label, expiration, multiplier, days, List.of());
        }
    }
    public record PositionDay(int day, String sessionDate,
                              long valueP10Cents, long valueP50Cents, long valueP90Cents,
                              Long pnlP10Cents, Long pnlP50Cents, Long pnlP90Cents,
                              long focusValueCents, Long focusPnlCents,
                              Greeks greeks) {}
    /** One package repriced at one stored focus-path step; probability bands remain daily. */
    public record PositionStep(int step, double sessionProgress, String sessionDate,
                               long focusValueCents, Long focusPnlCents, Greeks greeks) {}
    /**
     * Full-ensemble package-value quantiles on the bounded display grid.  These are valued by the
     * same kernel as the daily canvas and focus path; the browser only interpolates them.
     */
    public record PositionStepBand(int step, double sessionProgress,
                                   long pnlP10Cents, long pnlP25Cents, long pnlP50Cents,
                                   long pnlP75Cents, long pnlP90Cents) {}
    /** One source-indexed package P/L trajectory from the immutable underlying ensemble. */
    public record DisplayPositionStep(int step, double sessionProgress, long pnlCents) {}
    public record DisplayPositionPath(int sourcePathIndex, String role,
                                      List<DisplayPositionStep> steps) {}
    /** Controller-supplied path identities from PathEnsembleService's deterministic projection. */
    public record DisplayPathSelection(int sourcePathIndex, String role) {
        public DisplayPathSelection {
            if (sourcePathIndex < 0) throw new IllegalArgumentException("display source path index must be non-negative");
            role = role == null || role.isBlank() ? "CONTEXT" : role;
        }
    }
    public record Transformation(int day, String sessionDate, int legNo, String leg,
                                 String settlementPolicy, String exercisePolicy, String note) {}
    /**
     * Where THIS package's animation ends, as a backend fact.
     *
     * <p>A cash-only option package resolves at its final option settlement. Explicit stock and
     * physically settled option exposure remain live through the requested horizon; an option whose
     * final expiry lies beyond that horizon remains unresolved there. The browser consumes this
     * boundary directly and never re-derives it from dates or leg shape.
     *
     * @param frameCount          frames available for this package ({@code steps.size()}).
     * @param terminalFrameIndex  exact terminal frame index; {@code -1} when unavailable.
     * @param terminalSessionProgress {@code sessionProgress} of that frame; null when unavailable.
     * @param finalOptionExpiration latest option-leg expiration (ISO date), or null for stock-only.
     * @param boundaryReason      one of the named lifecycle reasons documented by this contract.
     * @param exposureResolvedAtBoundary whether all package exposure has become terminal cash there.
     * @param unavailableReason   null when frames exist; otherwise names why none do.
     */
    public record PositionAnimation(int frameCount, int terminalFrameIndex,
                                    Double terminalSessionProgress, String finalOptionExpiration,
                                    String boundaryReason, boolean exposureResolvedAtBoundary,
                                    String unavailableReason) {}
    public record PositionPath(String key, String label, String lane, String source, boolean proposed,
                               Long entryCostCents, List<PositionDay> days, List<PositionStep> steps,
                               List<PositionStepBand> stepBands,
                               List<DisplayPositionPath> displayPaths,
                               List<LegPath> legs,
                               List<Transformation> transformations,
                               PositionAnimation animation) {
        public PositionPath(String key, String label, String lane, String source, boolean proposed,
                            Long entryCostCents, List<PositionDay> days, List<LegPath> legs,
                            List<Transformation> transformations) {
            this(key, label, lane, source, proposed, entryCostCents, days, List.of(), List.of(),
                    List.of(), legs, transformations,
                    new PositionAnimation(0, -1, null, null, "NO_FRAMES", false,
                            "This package was valued on the daily grid only, without per-step "
                                    + "animation frames."));
        }
    }
    public record ComparisonRow(String key, String label, String lane, boolean proposed,
                                Long entryCostCents, long horizonP5Cents, long horizonP50Cents,
                                long horizonP95Cents, long expectedHorizonCents,
                                double chanceOfGainPct, Long versusStockP50Cents) {}
    public record Report(int focusSourcePathIndex, List<UnderlyingDay> underlying,
                         List<UnderlyingStep> underlyingSteps, AnimationTrack animation,
                         List<PositionPath> positions, List<ComparisonRow> comparison,
                         List<String> notes) {
        public Report(int focusSourcePathIndex, List<UnderlyingDay> underlying,
                      List<PositionPath> positions, List<ComparisonRow> comparison,
                      List<String> notes) {
            this(focusSourcePathIndex, underlying, List.of(), null, positions, comparison, notes);
        }
    }

    /** One held package bound to the matching member of a joint multi-symbol path artifact. */
    public record JointPositionInput(String symbol, PositionInput position, double atmIvAnnual) {
        public JointPositionInput {
            symbol = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
            if (symbol.isBlank()) throw new IllegalArgumentException("joint position symbol is required");
            if (position == null) throw new IllegalArgumentException("joint position package is required");
            if (!(atmIvAnnual >= .01 && atmIvAnnual <= 4) || !Double.isFinite(atmIvAnnual)) {
                throw new IllegalArgumentException("joint position ATM IV must be 1%..400%");
            }
        }
    }

    /** Same-path-index book P/L quantiles; unlike independent fans these may be summed honestly. */
    public record BookStepBand(int step, double sessionProgress,
                               long pnlP5Cents, long pnlP10Cents, long pnlP25Cents,
                               long pnlP50Cents, long pnlP75Cents, long pnlP90Cents,
                               long pnlP95Cents) {}
    public record BookPathStep(int step, double sessionProgress, long pnlCents) {}
    public record BookDisplayPath(int sourcePathIndex, String role, List<BookPathStep> steps) {
        public BookDisplayPath {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }
    public record BookPositionReceipt(String key, String symbol, String label, String source,
                                      long anchorValueCents, String anchorBasis,
                                      long horizonP10Cents, long horizonP50Cents,
                                      long horizonP90Cents, double atmIvAnnual) {}
    public record BookTailScenario(String role, int sourcePathIndex, long terminalPnlCents,
                                   long maxDrawdownCents, Map<String, Long> assignmentShares,
                                   long shortContractsAssigned) {
        public BookTailScenario {
            assignmentShares = assignmentShares == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(assignmentShares));
        }
    }
    public record AssignmentSummary(double chanceAnyAssignmentPct,
                                    long p50AbsoluteShares, long p90AbsoluteShares,
                                    long maximumAbsoluteShares, String basis) {}
    public record BookScenarioReport(String jointFingerprint, String modelVersion,
                                     int pathCount, int positionCount, int horizonSessions,
                                     List<BookStepBand> stepBands,
                                     List<BookDisplayPath> displayPaths,
                                     List<BookPositionReceipt> positions,
                                     long terminalP5Cents, long terminalP50Cents,
                                     long terminalP95Cents, long expectedTerminalPnlCents,
                                     double chanceOfGainPct, long p10MaxDrawdownCents,
                                     AssignmentSummary assignments,
                                     List<BookTailScenario> tailScenarios,
                                     List<String> notes) {
        public BookScenarioReport {
            stepBands = stepBands == null ? List.of() : List.copyOf(stepBands);
            displayPaths = displayPaths == null ? List.of() : List.copyOf(displayPaths);
            positions = positions == null ? List.of() : List.copyOf(positions);
            tailScenarios = tailScenarios == null ? List.of() : List.copyOf(tailScenarios);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    public Report value(PathEnsembleService.Ensemble ensemble, IvSpec legacyIv,
                        ScenarioCanvasSpec rawCanvas, double annualRate,
                        List<PositionInput> rawPositions) {
        return value(ensemble, legacyIv, rawCanvas, annualRate, rawPositions, null, List.of());
    }

    /**
     * Value a complete book only on a synchronized joint artifact. The implementation delegates
     * every leg/expiry transformation to {@link PathValuationKernel}; it merely sums values that
     * share the same source path index. Passing independent ensembles is structurally impossible.
     */
    public BookScenarioReport valueJointBook(PathEnsembleService.JointEnsemble joint,
                                             ScenarioCanvasSpec rawCanvas,
                                             double annualRate,
                                             List<JointPositionInput> rawPositions,
                                             int requestedDisplayPaths) {
        try (AutoCloseable permit = SimBudget.acquire()) {
            return valueJointBookPermitted(joint, rawCanvas, annualRate, rawPositions,
                    requestedDisplayPaths);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BookScenarioReport valueJointBookPermitted(PathEnsembleService.JointEnsemble joint,
                                                       ScenarioCanvasSpec rawCanvas,
                                                       double annualRate,
                                                       List<JointPositionInput> rawPositions,
                                                       int requestedDisplayPaths) {
        if (joint == null) throw new IllegalArgumentException("joint book ensemble is required");
        List<JointPositionInput> positions = rawPositions == null ? List.of() : List.copyOf(rawPositions);
        if (positions.isEmpty()) throw new IllegalArgumentException("joint book positions are required");
        if (positions.size() > 64) throw new IllegalArgumentException("at most 64 joint book positions");
        PathEnsembleService.Ensemble template = joint.members().values().iterator().next();
        ScenarioSpec spec = template.spec().sane();
        int pathCount = template.paths().length;
        int steps = spec.totalSteps(), spd = Math.max(1, spec.stepsPerDay());
        long legCount = positions.stream().mapToLong(row -> row.position().position().legs().size()).sum();
        long work = (long) pathCount * (steps + 1L) * Math.max(1, legCount);
        if (work > MAX_CANVAS_VALUATION_WORK) {
            throw new IllegalArgumentException("Joint book comparison is too large (" + work
                    + " leg-steps > " + MAX_CANVAS_VALUATION_WORK + ")");
        }
        ScenarioCanvasSpec canvas = (rawCanvas == null ? ScenarioCanvasSpec.defaults() : rawCanvas)
                .sane(spec.horizonDays());
        int[] displaySteps = PathEnsembleService.displayStepIndices(steps);
        long[][] aggregatePnl = new long[pathCount][displaySteps.length];
        @SuppressWarnings("unchecked")
        Map<String, Long>[] assignments = new Map[pathCount];
        long[] assignedContracts = new long[pathCount];
        for (int path = 0; path < pathCount; path++) assignments[path] = new LinkedHashMap<>();
        List<BookPositionReceipt> positionReceipts = new ArrayList<>();
        Set<String> keys = new java.util.LinkedHashSet<>();

        for (JointPositionInput row : positions) {
            PositionInput input = row.position();
            if (!keys.add(input.key())) throw new IllegalArgumentException("duplicate book position key " + input.key());
            PathEnsembleService.Ensemble member = joint.member(row.symbol());
            if (member == null) throw new IllegalArgumentException("joint artifact has no member for " + row.symbol());
            if (!input.position().asOf().equals(joint.anchorDate())) {
                throw new IllegalArgumentException(input.key() + " valuation date does not match joint anchor");
            }
            double[] stepYears = member.spec().calendarStepYears(member.anchorDate());
            double[] elapsed = PathValuationKernel.elapsed(stepYears);
            double[] legacyIv = IvSpec.flat(row.atmIvAnnual()).path(steps,
                    sum(stepYears) / Math.max(1, steps), spd);
            int[][] transformations = new int[pathCount][];
            for (int path = 0; path < pathCount; path++) {
                transformations[path] = PathValuationKernel.transformationSteps(input.position(),
                        member.paths()[path], steps, spd, elapsed, legacyIv, canvas, annualRate);
            }
            long anchorValue = input.entryCostCents() == null
                    ? Money.toCents(PathValuationKernel.valueCanvas(input.position(), member.paths()[0],
                        0, steps, spd, elapsed, legacyIv, canvas, annualRate,
                        transformations[0]) * input.qty())
                    : input.entryCostCents();
            long[] terminal = new long[pathCount];
            for (int point = 0; point < displaySteps.length; point++) {
                int step = displaySteps[point];
                for (int path = 0; path < pathCount; path++) {
                    long value = Money.toCents(PathValuationKernel.valueCanvas(input.position(),
                            member.paths()[path], step, steps, spd, elapsed, legacyIv,
                            canvas, annualRate, transformations[path]) * input.qty());
                    long pnl = Math.subtractExact(value, anchorValue);
                    aggregatePnl[path][point] = Math.addExact(aggregatePnl[path][point], pnl);
                    if (point == displaySteps.length - 1) terminal[path] = pnl;
                }
            }
            collectAssignments(row, member, spd, steps, assignments, assignedContracts);
            long[] sortedTerminal = terminal.clone();
            Arrays.sort(sortedTerminal);
            positionReceipts.add(new BookPositionReceipt(input.key(), row.symbol(), input.label(),
                    input.source(), anchorValue,
                    input.entryCostCents() == null ? "MODELED_CURRENT_VALUE" : "SUPPLIED_CURRENT_VALUE",
                    Quantiles.of(sortedTerminal, .10), Quantiles.of(sortedTerminal, .50), Quantiles.of(sortedTerminal, .90),
                    row.atmIvAnnual()));
        }

        List<BookStepBand> bands = new ArrayList<>(displaySteps.length);
        long[] terminalBook = new long[pathCount];
        long[] maxDrawdowns = new long[pathCount];
        for (int point = 0; point < displaySteps.length; point++) {
            long[] values = new long[pathCount];
            for (int path = 0; path < pathCount; path++) {
                values[path] = aggregatePnl[path][point];
                maxDrawdowns[path] = Math.min(maxDrawdowns[path], values[path]);
                if (point == displaySteps.length - 1) terminalBook[path] = values[path];
            }
            Arrays.sort(values);
            bands.add(new BookStepBand(displaySteps[point], sessionProgress(displaySteps[point], spd),
                    Quantiles.of(values, .05), Quantiles.of(values, .10), Quantiles.of(values, .25), Quantiles.of(values, .50),
                    Quantiles.of(values, .75), Quantiles.of(values, .90), Quantiles.of(values, .95)));
        }
        long[] sortedTerminal = terminalBook.clone(); Arrays.sort(sortedTerminal);
        long[] sortedDrawdowns = maxDrawdowns.clone(); Arrays.sort(sortedDrawdowns);
        long terminalSum = 0; int gains = 0, anyAssignment = 0;
        long[] absoluteAssignedShares = new long[pathCount];
        for (int path = 0; path < pathCount; path++) {
            terminalSum = Math.addExact(terminalSum, terminalBook[path]);
            if (terminalBook[path] > 0) gains++;
            if (assignedContracts[path] > 0) anyAssignment++;
            for (long shares : assignments[path].values()) {
                absoluteAssignedShares[path] = Math.addExact(absoluteAssignedShares[path], Math.abs(shares));
            }
        }
        long[] sortedAssignments = absoluteAssignedShares.clone(); Arrays.sort(sortedAssignments);
        AssignmentSummary assignmentSummary = new AssignmentSummary(
                round2(anyAssignment * 100.0 / pathCount), Quantiles.of(sortedAssignments, .50),
                Quantiles.of(sortedAssignments, .90), sortedAssignments[sortedAssignments.length - 1],
                "Short-option moneyness at each contract's own expiry on the same joint path; "
                        + "signed shares are +put assignment and -call assignment. Long-leg exercise "
                        + "and package value remain governed by the canonical valuation kernel.");
        List<Integer> selected = terminalQuantilePaths(terminalBook,
                Math.clamp(requestedDisplayPaths <= 0 ? 9 : requestedDisplayPaths,
                        1, PathEnsembleService.MAX_DISPLAY_PATHS));
        int medianSource = terminalQuantilePath(terminalBook, .50);
        List<BookDisplayPath> displayPaths = new ArrayList<>();
        for (int source : selected) {
            List<BookPathStep> pathSteps = new ArrayList<>(displaySteps.length);
            for (int point = 0; point < displaySteps.length; point++) {
                pathSteps.add(new BookPathStep(displaySteps[point],
                        sessionProgress(displaySteps[point], spd), aggregatePnl[source][point]));
            }
            displayPaths.add(new BookDisplayPath(source,
                    source == medianSource ? "FOCUS" : "CONTEXT", pathSteps));
        }
        int worstTerminal = indexOfMinimum(terminalBook);
        int worstDrawdown = indexOfMinimum(maxDrawdowns);
        int assignmentCluster = indexOfMaximum(assignedContracts, terminalBook);
        LinkedHashMap<Integer, String> tailRoles = new LinkedHashMap<>();
        tailRoles.put(worstTerminal, "WORST_TERMINAL");
        tailRoles.putIfAbsent(worstDrawdown, "WORST_DRAWDOWN");
        if (assignedContracts[assignmentCluster] > 0) tailRoles.putIfAbsent(assignmentCluster, "MOST_ASSIGNMENTS");
        List<BookTailScenario> tails = tailRoles.entrySet().stream()
                .map(entry -> new BookTailScenario(entry.getValue(), entry.getKey(),
                        terminalBook[entry.getKey()], maxDrawdowns[entry.getKey()],
                        assignments[entry.getKey()], assignedContracts[entry.getKey()]))
                .toList();
        List<String> notes = List.of(
                joint.interpretation(),
                "Every held package is repriced from today's modeled anchor value through "
                        + PathValuationKernel.class.getSimpleName() + "; historical P/L is not mixed into this forward fan.",
                "Option values use each symbol's disclosed ATM-IV input and the one shared Scenario Canvas surface rule. "
                        + "Underlying dependence is measured; future option marks remain modeled.",
                "P/L is aggregated only by synchronized source path index. Quantiles from independent position fans are never added.");
        return new BookScenarioReport(joint.fingerprint(), JOINT_BOOK_MODEL_VERSION,
                pathCount, positions.size(), spec.horizonDays(), List.copyOf(bands),
                List.copyOf(displayPaths), List.copyOf(positionReceipts),
                Quantiles.of(sortedTerminal, .05), Quantiles.of(sortedTerminal, .50), Quantiles.of(sortedTerminal, .95),
                Math.round((double) terminalSum / pathCount),
                round2(gains * 100.0 / pathCount), Quantiles.of(sortedDrawdowns, .10),
                assignmentSummary, tails, notes);
    }

    /** Value a deterministic display subset alongside the full-ensemble bands. */
    public Report value(PathEnsembleService.Ensemble ensemble, IvSpec legacyIv,
                        ScenarioCanvasSpec rawCanvas, double annualRate,
                        List<PositionInput> rawPositions,
                        List<DisplayPathSelection> displayPaths) {
        return value(ensemble, legacyIv, rawCanvas, annualRate, rawPositions, null, displayPaths);
    }

    /**
     * Reprice the canvas with daily bands and step detail pinned to one source row from the
     * immutable ensemble. Probability bands still use the full matrix; this overload only chooses
     * the coherent trace used by focus prices, Greeks, leg states, and transformation events.
     */
    public Report value(PathEnsembleService.Ensemble ensemble, IvSpec legacyIv,
                        ScenarioCanvasSpec rawCanvas, double annualRate,
                        List<PositionInput> rawPositions, int sourcePathIndex) {
        return value(ensemble, legacyIv, rawCanvas, annualRate, rawPositions,
                Integer.valueOf(sourcePathIndex), List.of());
    }

    /** Value a selected focus row and the exact deterministic display rows around it. */
    public Report value(PathEnsembleService.Ensemble ensemble, IvSpec legacyIv,
                        ScenarioCanvasSpec rawCanvas, double annualRate,
                        List<PositionInput> rawPositions, int sourcePathIndex,
                        List<DisplayPathSelection> displayPaths) {
        return value(ensemble, legacyIv, rawCanvas, annualRate, rawPositions,
                Integer.valueOf(sourcePathIndex), displayPaths);
    }

    private Report value(PathEnsembleService.Ensemble ensemble, IvSpec legacyIv,
                         ScenarioCanvasSpec rawCanvas, double annualRate,
                         List<PositionInput> rawPositions, Integer sourcePathIndex,
                         List<DisplayPathSelection> displayPaths) {
        try (AutoCloseable permit = SimBudget.acquire()) {
            return valuePermitted(ensemble, legacyIv, rawCanvas, annualRate, rawPositions,
                    sourcePathIndex, displayPaths);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Report valuePermitted(PathEnsembleService.Ensemble ensemble, IvSpec legacyIv,
                                  ScenarioCanvasSpec rawCanvas, double annualRate,
                                  List<PositionInput> rawPositions, Integer requestedSourcePathIndex,
                                  List<DisplayPathSelection> rawDisplayPaths) {
        if (ensemble == null) throw new IllegalArgumentException("canvas ensemble is required");
        double[][] paths = ensemble.paths();
        if (rawPositions == null || rawPositions.isEmpty()) {
            int representativePath = representativePath(paths, ensemble.spec().totalSteps(),
                    requestedSourcePathIndex);
            IvSpec iv = (legacyIv == null ? IvSpec.flat(ensemble.spec().volAnnual()) : legacyIv).sane();
            ScenarioCanvasSpec canvas = rawCanvas == null ? ScenarioCanvasSpec.defaults()
                    : rawCanvas.sane(ensemble.spec().horizonDays());
            int[] displaySteps = PathEnsembleService.displayStepIndices(
                    ensemble.spec().totalSteps());
            List<UnderlyingStep> bareFrames = underlyingSteps(paths, ensemble, iv, canvas,
                    representativePath, displaySteps);
            return new Report(representativePath, underlying(paths, ensemble, iv, canvas,
                    representativePath), bareFrames, animationTrack(ensemble, bareFrames),
                    List.of(), List.of(), List.of("No same-symbol positions were available to reprice."));
        }
        if (rawPositions.size() > 32) throw new IllegalArgumentException("at most 32 positions can share one canvas");
        List<DisplayPathSelection> displayPaths = saneDisplayPaths(rawDisplayPaths, paths.length);
        ScenarioSpec spec = ensemble.spec().sane();
        ScenarioCanvasSpec canvas = (rawCanvas == null ? ScenarioCanvasSpec.defaults() : rawCanvas)
                .sane(spec.horizonDays());
        long totalLegs = rawPositions.stream().mapToLong(input -> input.position().legs().size()).sum();
        long dailyAndTransformationSteps = (long) spec.totalSteps() + spec.horizonDays() + 2L;
        long work = (long) ensemble.paths().length * dailyAndTransformationSteps * Math.max(1L, totalLegs);
        if (work > MAX_CANVAS_VALUATION_WORK) {
            throw new IllegalArgumentException("Canvas comparison is too large (" + work
                    + " leg-steps > " + MAX_CANVAS_VALUATION_WORK
                    + ") — compare fewer same-symbol positions or reduce paths and horizon.");
        }
        int representativePath = representativePath(paths, spec.totalSteps(), requestedSourcePathIndex);
        IvSpec iv = (legacyIv == null ? IvSpec.flat(spec.volAnnual()) : legacyIv).sane();
        double[] stepYears = spec.calendarStepYears(ensemble.anchorDate());
        double[] elapsed = PathValuationKernel.elapsed(stepYears);
        int steps = spec.totalSteps(), spd = Math.max(1, spec.stepsPerDay()), days = steps / spd;
        List<PositionBoundary> boundaries = rawPositions.stream()
                .map(input -> positionBoundary(input, canvas, steps, spd))
                .toList();
        int[] displaySteps = PathEnsembleService.displayStepIndices(steps,
                boundaries.stream().mapToInt(PositionBoundary::terminalStep).toArray());
        double[] legacyPath = iv.path(steps, sum(stepYears) / steps, spd);
        List<LocalDate> sessionDates = ScenarioSpec.sessionDates(ensemble.anchorDate(), days);
        List<UnderlyingDay> underlying = new ArrayList<>();
        for (int day = 0; day <= days; day++) {
            int step = Math.min(steps, day * spd);
            Ranked ranked = rank(paths, step);
            double atm = canvas.atmIv(day, days, legacyPath[step]);
            underlying.add(new UnderlyingDay(day, date(day, ensemble.anchorDate(), sessionDates),
                    round2(ranked.valueAt(0.10)), round2(ranked.valueAt(0.50)),
                    round2(ranked.valueAt(0.90)), paths[representativePath][step],
                    round4(atm)));
        }
        List<UnderlyingStep> underlyingSteps = underlyingSteps(paths, ensemble, iv, canvas,
                representativePath, displaySteps);

        List<PositionPath> positionPaths = new ArrayList<>();
        List<ComparisonRow> comparisons = new ArrayList<>();
        LinkedHashMap<String, long[]> terminalPnl = new LinkedHashMap<>();
        for (int inputIndex = 0; inputIndex < rawPositions.size(); inputIndex++) {
            PositionInput input = rawPositions.get(inputIndex);
            PositionRun run = valuePosition(input, ensemble, canvas, annualRate, elapsed,
                    legacyPath, sessionDates, representativePath, displayPaths, displaySteps,
                    boundaries.get(inputIndex));
            positionPaths.add(run.path());
            terminalPnl.put(input.key(), run.terminalPnl());
        }
        Long stockMedian = null;
        for (PositionInput input : rawPositions) {
            if ("STOCK_BASELINE".equals(input.source())) {
                long[] sorted = terminalPnl.get(input.key()).clone(); Arrays.sort(sorted);
                stockMedian = Quantiles.of(sorted, 0.50); break;
            }
        }
        for (PositionInput input : rawPositions) {
            long[] terminal = terminalPnl.get(input.key()).clone();
            long sum = 0; int wins = 0;
            for (long value : terminal) { sum += value; if (value > 0) wins++; }
            Arrays.sort(terminal);
            long median = Quantiles.of(terminal, 0.50);
            comparisons.add(new ComparisonRow(input.key(), input.label(), input.lane(), input.proposed(),
                    input.entryCostCents(), Quantiles.of(terminal, 0.05), median, Quantiles.of(terminal, 0.95),
                    Math.round((double) sum / terminal.length), Math.round(wins * 1000.0 / terminal.length) / 10.0,
                    stockMedian == null || "STOCK_BASELINE".equals(input.source()) ? null : median - stockMedian));
        }
        comparisons.sort((a, b) -> Long.compare(b.horizonP50Cents(), a.horizonP50Cents()));
        List<String> notes = new ArrayList<>();
        notes.add("Authored paths are the user's hypothesis, never a forecast. Every position is repriced on one identical stored ensemble.");
        notes.add("Option values and Greeks are MODELED from the declared per-day IV surface; underlying bands come from the stored path matrix.");
        notes.add(requestedSourcePathIndex == null
                ? "P&L bands use every stored path. Daily Greeks, focus value, leg detail, and transformation events follow one representative path whose horizon price is the ensemble median. Per-step focus checkpoints reprice that same source path through the identical valuation kernel."
                : "P&L bands use every stored path. Daily Greeks, focus value, leg detail, and transformation events follow stored source path "
                    + representativePath + ", selected by the animation projection. Per-step focus checkpoints reprice that same source path through the identical valuation kernel.");
        if (!displayPaths.isEmpty()) {
            notes.add(displayPaths.size() + " bounded package P/L trajectories retain their source-row identity from the displayed underlying fan.");
        }
        int displayPointCount = displaySteps.length;
        if (displayPointCount < steps + 1) {
            notes.add("Animation output carries " + displayPointCount + " deterministic checkpoints from "
                    + (steps + 1) + " stored steps. Terminal and daily distributions still use the full ensemble.");
        }
        notes.add("Scenario animation frames are exact valued facts, not a curve to read between: "
                + "select frame Math.round(t x terminalFrameIndex) and display it. Interpolating two "
                + "frames may position pixels, never state a price, a vol, a P/L or a Greek.");
        notes.add(canvas.dividendBasis());
        if (canvas.template() != null) notes.add(canvas.template().legDayProvenance());
        return new Report(representativePath, List.copyOf(underlying), underlyingSteps,
                animationTrack(ensemble, underlyingSteps),
                List.copyOf(positionPaths), List.copyOf(comparisons), List.copyOf(notes));
    }

    private PositionRun valuePosition(PositionInput input, PathEnsembleService.Ensemble ensemble,
                                      ScenarioCanvasSpec canvas, double annualRate, double[] elapsed,
                                      double[] legacyPath, List<LocalDate> sessionDates,
                                      int representativePath,
                                      List<DisplayPathSelection> displaySelections,
                                      int[] displaySteps, PositionBoundary boundary) {
        ScenarioSpec spec = ensemble.spec();
        int steps = spec.totalSteps(), spd = Math.max(1, spec.stepsPerDay()), days = steps / spd;
        double[][] paths = ensemble.paths();
        int[][] resolvedTransformations = new int[paths.length][];
        for (int p = 0; p < paths.length; p++) {
            resolvedTransformations[p] = PathValuationKernel.transformationSteps(input.position(),
                    paths[p], steps, spd, elapsed, legacyPath, canvas, annualRate);
        }
        long entry = input.entryCostCents() == null
                ? Money.toCents(PathValuationKernel.valueCanvas(input.position(), paths[representativePath], 0, steps, spd,
                    elapsed, legacyPath, canvas, annualRate,
                    resolvedTransformations[representativePath]) * input.qty())
                : input.entryCostCents();
        List<PositionDay> timeline = new ArrayList<>();
        List<List<LegDay>> legDays = new ArrayList<>();
        List<List<LegStep>> legSteps = new ArrayList<>();
        for (int i = 0; i < input.position().legs().size(); i++) {
            legDays.add(new ArrayList<>());
            legSteps.add(new ArrayList<>());
        }
        long[] terminal = new long[paths.length];
        for (int day = 0; day <= days; day++) {
            int step = Math.min(steps, day * spd);
            long[] values = new long[paths.length];
            for (int p = 0; p < paths.length; p++) {
                values[p] = Money.toCents(PathValuationKernel.valueCanvas(input.position(), paths[p], step,
                        steps, spd, elapsed, legacyPath, canvas, annualRate,
                        resolvedTransformations[p]) * input.qty());
            }
            long[] sorted = values.clone(); Arrays.sort(sorted);
            long focusValue = values[representativePath];
            if (day == days) for (int p = 0; p < paths.length; p++) terminal[p] = values[p] - entry;
            double dd = 0, gg = 0, tt = 0, vv = 0;
            double[] median = paths[representativePath];
            for (int legNo = 0; legNo < input.position().legs().size(); legNo++) {
                Leg leg = input.position().legs().get(legNo);
                PathValuationKernel.LegPoint point = PathValuationKernel.legPoint(input.position(), leg,
                        median, step, steps, spd, elapsed, legacyPath, canvas, annualRate,
                        resolvedTransformations[representativePath][legNo]);
                double q = input.qty();
                dd += point.deltaShares() * q; gg += point.gammaSharesPerDollar() * q;
                tt += point.thetaDollarsPerDay() * q; vv += point.vegaDollarsPerPoint() * q;
                legDays.get(legNo).add(new LegDay(day, Money.toCents(point.valueDollars() * q),
                        Money.toCents(point.optionPrice()), new Greeks(round4(point.deltaShares() * q),
                        round4(point.gammaSharesPerDollar() * q), Money.toCents(point.thetaDollarsPerDay() * q),
                        Money.toCents(point.vegaDollarsPerPoint() * q)), point.state()));
            }
            timeline.add(new PositionDay(day, date(day, ensemble.anchorDate(), sessionDates),
                    Quantiles.of(sorted, 0.10), Quantiles.of(sorted, 0.50), Quantiles.of(sorted, 0.90),
                    Quantiles.of(sorted, 0.10) - entry, Quantiles.of(sorted, 0.50) - entry, Quantiles.of(sorted, 0.90) - entry,
                    focusValue, focusValue - entry,
                    new Greeks(round4(dd), round4(gg), Money.toCents(tt), Money.toCents(vv))));
        }
        List<PositionStep> focusSteps = new ArrayList<>(displaySteps.length);
        List<PositionStepBand> stepBands = new ArrayList<>(displaySteps.length);
        List<List<DisplayPositionStep>> selectedSteps = new ArrayList<>(displaySelections.size());
        for (int i = 0; i < displaySelections.size(); i++) selectedSteps.add(new ArrayList<>(displaySteps.length));
        for (int step : displaySteps) {
            long[] displayValues = new long[paths.length];
            for (int p = 0; p < paths.length; p++) {
                displayValues[p] = Money.toCents(PathValuationKernel.valueCanvas(input.position(),
                        paths[p], step, steps, spd, elapsed, legacyPath, canvas, annualRate,
                        resolvedTransformations[p]) * input.qty()) - entry;
            }
            long[] sortedDisplayValues = displayValues.clone();
            Arrays.sort(sortedDisplayValues);
            double progress = sessionProgress(step, spd);
            stepBands.add(new PositionStepBand(step, progress,
                    Quantiles.of(sortedDisplayValues, .10), Quantiles.of(sortedDisplayValues, .25),
                    Quantiles.of(sortedDisplayValues, .50), Quantiles.of(sortedDisplayValues, .75),
                    Quantiles.of(sortedDisplayValues, .90)));
            for (int selected = 0; selected < displaySelections.size(); selected++) {
                int sourceIndex = displaySelections.get(selected).sourcePathIndex();
                selectedSteps.get(selected).add(new DisplayPositionStep(step, progress,
                        displayValues[sourceIndex]));
            }
            long focusValue = Money.toCents(PathValuationKernel.valueCanvas(input.position(),
                    paths[representativePath], step, steps, spd, elapsed, legacyPath, canvas,
                    annualRate, resolvedTransformations[representativePath]) * input.qty());
            double dd = 0, gg = 0, tt = 0, vv = 0;
            for (int legNo = 0; legNo < input.position().legs().size(); legNo++) {
                Leg leg = input.position().legs().get(legNo);
                PathValuationKernel.LegPoint point = PathValuationKernel.legPoint(input.position(), leg,
                        paths[representativePath], step, steps, spd, elapsed, legacyPath, canvas,
                        annualRate, resolvedTransformations[representativePath][legNo]);
                double q = input.qty();
                dd += point.deltaShares() * q; gg += point.gammaSharesPerDollar() * q;
                tt += point.thetaDollarsPerDay() * q; vv += point.vegaDollarsPerPoint() * q;
                legSteps.get(legNo).add(new LegStep(step, progress,
                        Money.toCents(point.valueDollars() * q), Money.toCents(point.optionPrice()),
                        new Greeks(round4(point.deltaShares() * q),
                                round4(point.gammaSharesPerDollar() * q),
                                Money.toCents(point.thetaDollarsPerDay() * q),
                                Money.toCents(point.vegaDollarsPerPoint() * q)), point.state()));
            }
            focusSteps.add(new PositionStep(step, progress,
                    dateForStep(step, spd, days, ensemble.anchorDate(), sessionDates),
                    focusValue, focusValue - entry,
                    new Greeks(round4(dd), round4(gg), Money.toCents(tt), Money.toCents(vv))));
        }
        List<DisplayPositionPath> valuedDisplayPaths = new ArrayList<>(displaySelections.size());
        for (int i = 0; i < displaySelections.size(); i++) {
            DisplayPathSelection selection = displaySelections.get(i);
            valuedDisplayPaths.add(new DisplayPositionPath(selection.sourcePathIndex(),
                    selection.role(), List.copyOf(selectedSteps.get(i))));
        }
        List<LegPath> legs = new ArrayList<>();
        List<Transformation> transformationRows = new ArrayList<>();
        for (int i = 0; i < input.position().legs().size(); i++) {
            Leg leg = input.position().legs().get(i);
            String label = leg.isStock() ? leg.action() + " " + leg.ratio() * leg.multiplier() + " shares"
                    : leg.action() + " " + leg.type() + " " + leg.strike();
            legs.add(new LegPath(i, label, leg.isStock() ? null : leg.expiration().toString(),
                    leg.multiplier(), List.copyOf(legDays.get(i)), List.copyOf(legSteps.get(i))));
            if (!leg.isStock()) {
                int eventStep = resolvedTransformations[representativePath][i];
                int eventDay = eventStep / spd;
                if (eventStep >= 0 && eventStep <= steps && eventDay <= days) {
                    String state = legDays.get(i).get(eventDay).state();
                    boolean early = state.startsWith("EARLY_");
                    transformationRows.add(new Transformation(eventDay,
                            date(eventDay, ensemble.anchorDate(), sessionDates), i, label,
                            canvas.settlementPolicy().name(), canvas.exercisePolicy().name(),
                            early
                                    ? "On the representative path, the declared low-extrinsic exercise rule transforms this leg here; later legs continue."
                                    : canvas.settlementPolicy() == ScenarioCanvasSpec.SettlementPolicy.PHYSICAL_IF_ITM
                                    ? "If in the money, this leg transforms into its deliverable exposure; later legs continue."
                                    : "This leg settles to intrinsic cash here; later-expiring legs continue."));
                }
            }
        }
        return new PositionRun(new PositionPath(input.key(), input.label(), input.lane(), input.source(),
                input.proposed(), input.entryCostCents(), List.copyOf(timeline),
                List.copyOf(focusSteps), List.copyOf(stepBands), List.copyOf(valuedDisplayPaths),
                List.copyOf(legs), List.copyOf(transformationRows),
                positionAnimation(boundary, displaySteps, spd)), terminal);
    }

    private static void collectAssignments(JointPositionInput row,
                                           PathEnsembleService.Ensemble member,
                                           int stepsPerDay, int steps,
                                           Map<String, Long>[] assignmentShares,
                                           long[] assignedContracts) {
        PositionInput input = row.position();
        for (Leg leg : input.position().legs()) {
            if (leg.isStock() || leg.action() != LegAction.SELL) continue;
            int expiryDay = input.position().expiryDay(leg);
            int expiryStep = expiryDay <= 0 ? Math.min(stepsPerDay, steps)
                    : Math.multiplyExact(expiryDay, stepsPerDay);
            if (expiryStep > steps) continue;
            long contracts = Math.multiplyExact((long) input.qty(), leg.ratio());
            long shares = Math.multiplyExact(contracts, leg.multiplier());
            for (int path = 0; path < member.paths().length; path++) {
                double spot = member.paths()[path][expiryStep];
                boolean itm = leg.type() == OptionType.CALL
                        ? spot > leg.strike().doubleValue()
                        : spot < leg.strike().doubleValue();
                if (!itm) continue;
                long signedShares = leg.type() == OptionType.PUT ? shares : -shares;
                assignmentShares[path].merge(row.symbol(), signedShares, Math::addExact);
                assignedContracts[path] = Math.addExact(assignedContracts[path], contracts);
            }
        }
    }

    private static List<Integer> terminalQuantilePaths(long[] terminal, int requested) {
        int limit = Math.min(Math.max(1, requested), terminal.length);
        List<Integer> ranked = new ArrayList<>(terminal.length);
        for (int i = 0; i < terminal.length; i++) ranked.add(i);
        ranked.sort((left, right) -> {
            int compared = Long.compare(terminal[left], terminal[right]);
            return compared == 0 ? Integer.compare(left, right) : compared;
        });
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        if (limit == 1) selected.add(ranked.get(Quantiles.index(ranked.size(), .50)));
        else {
            for (int slot = 0; slot < limit; slot++) {
                int at = Quantiles.index(ranked.size(), (double) slot / (limit - 1));
                selected.add(ranked.get(at));
            }
            selected.add(ranked.get(Quantiles.index(ranked.size(), .50)));
        }
        if (selected.size() > limit) {
            Integer median = ranked.get(Quantiles.index(ranked.size(), .50));
            List<Integer> bounded = new ArrayList<>(selected);
            while (bounded.size() > limit) {
                int remove = bounded.size() - 2;
                if (bounded.get(remove).equals(median)) remove--;
                bounded.remove(Math.max(0, remove));
            }
            return List.copyOf(bounded);
        }
        for (int index : ranked) {
            if (selected.size() >= limit) break;
            selected.add(index);
        }
        return List.copyOf(selected);
    }

    private static int terminalQuantilePath(long[] terminal, double probability) {
        List<Integer> ranked = new ArrayList<>(terminal.length);
        for (int i = 0; i < terminal.length; i++) ranked.add(i);
        ranked.sort((left, right) -> {
            int compared = Long.compare(terminal[left], terminal[right]);
            return compared == 0 ? Integer.compare(left, right) : compared;
        });
        return ranked.get(Quantiles.index(ranked.size(), probability));
    }

    private static int indexOfMinimum(long[] values) {
        int at = 0;
        for (int i = 1; i < values.length; i++) if (values[i] < values[at]) at = i;
        return at;
    }

    private static int indexOfMaximum(long[] values, long[] tieBreaker) {
        int at = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[at]
                    || values[i] == values[at] && tieBreaker[i] < tieBreaker[at]) at = i;
        }
        return at;
    }

    private static List<DisplayPathSelection> saneDisplayPaths(
            List<DisplayPathSelection> raw, int sourcePathCount) {
        if (raw == null || raw.isEmpty()) return List.of();
        LinkedHashMap<Integer, DisplayPathSelection> unique = new LinkedHashMap<>();
        for (DisplayPathSelection selection : raw) {
            if (selection == null) continue;
            if (selection.sourcePathIndex() >= sourcePathCount) {
                throw new IllegalArgumentException("display source path index "
                        + selection.sourcePathIndex() + " lies outside this ensemble");
            }
            unique.putIfAbsent(selection.sourcePathIndex(), selection);
            if (unique.size() >= PathEnsembleService.MAX_DISPLAY_PATHS) break;
        }
        return List.copyOf(unique.values());
    }

    private record PositionRun(PositionPath path, long[] terminalPnl) {}
    private record PositionBoundary(int terminalStep, LocalDate finalOptionExpiration,
                                    String boundaryReason,
                                    boolean exposureResolvedAtBoundary) {}
    private record Pair(double value, int index) {}
    private record Ranked(Pair[] sorted) {
        double valueAt(double p) { return sorted[index(p)].value(); }
        int indexAt(double p) { return sorted[index(p)].index(); }
        private int index(double p) { return Quantiles.index(sorted.length, p); }
    }

    private static Ranked rank(double[][] paths, int step) {
        Pair[] pairs = new Pair[paths.length];
        for (int i = 0; i < paths.length; i++) pairs[i] = new Pair(paths[i][step], i);
        Arrays.sort(pairs, java.util.Comparator.comparingDouble(Pair::value));
        return new Ranked(pairs);
    }

    private static int representativePath(double[][] paths, int terminalStep, Integer requested) {
        int index = requested == null ? rank(paths, terminalStep).indexAt(0.50) : requested;
        if (index < 0 || index >= paths.length) {
            throw new IllegalArgumentException("focus source path index " + index
                    + " is outside the stored ensemble of " + paths.length + " paths");
        }
        if (paths[index] == null || paths[index].length <= terminalStep) {
            throw new IllegalArgumentException("focus source path " + index
                    + " does not contain the stored ensemble horizon");
        }
        return index;
    }

    private static List<UnderlyingDay> underlying(double[][] paths, PathEnsembleService.Ensemble ensemble,
                                                   IvSpec iv, ScenarioCanvasSpec canvas,
                                                   int representativePath) {
        int days = ensemble.spec().horizonDays(), spd = ensemble.spec().stepsPerDay();
        double[] stepYears = ensemble.spec().calendarStepYears(ensemble.anchorDate());
        double[] legacy = iv.path(ensemble.spec().totalSteps(), sum(stepYears) / stepYears.length, spd);
        List<LocalDate> dates = ScenarioSpec.sessionDates(ensemble.anchorDate(), days);
        List<UnderlyingDay> out = new ArrayList<>();
        for (int day = 0; day <= days; day++) {
            int step = Math.min(ensemble.spec().totalSteps(), day * spd);
            Ranked r = rank(paths, step);
            out.add(new UnderlyingDay(day, date(day, ensemble.anchorDate(), dates), round2(r.valueAt(.1)),
                    round2(r.valueAt(.5)), round2(r.valueAt(.9)),
                    paths[representativePath][step],
                    round4(canvas.atmIv(day, days, legacy[step]))));
        }
        return out;
    }

    private static List<UnderlyingStep> underlyingSteps(double[][] paths,
                                                        PathEnsembleService.Ensemble ensemble,
                                                        IvSpec iv, ScenarioCanvasSpec canvas,
                                                        int representativePath,
                                                        int[] displaySteps) {
        int steps = ensemble.spec().totalSteps();
        int spd = Math.max(1, ensemble.spec().stepsPerDay());
        int days = ensemble.spec().horizonDays();
        double[] stepYears = ensemble.spec().calendarStepYears(ensemble.anchorDate());
        double[] legacy = iv.path(steps, sum(stepYears) / steps, spd);
        List<LocalDate> dates = ScenarioSpec.sessionDates(ensemble.anchorDate(), days);
        List<UnderlyingStep> out = new ArrayList<>(displaySteps.length);
        double anchorSpot = ensemble.spot();
        Double baselineAtmIv = null;
        for (int step : displaySteps) {
            int valuationDay = Math.min(days, step / spd);
            double atmIv = round4(canvas.atmIv(valuationDay, days, legacy[step]));
            if (baselineAtmIv == null) baselineAtmIv = atmIv;
            double price = paths[representativePath][step];
            out.add(new UnderlyingStep(step, sessionProgress(step, spd),
                    dateForStep(step, spd, days, ensemble.anchorDate(), dates),
                    price, atmIv,
                    anchorSpot > 0 ? round4((price / anchorSpot - 1) * 100) : 0,
                    round4((atmIv - baselineAtmIv) * 100)));
        }
        return List.copyOf(out);
    }

    /** The animation contract for a built frame list; null when there are no frames to scrub. */
    private static AnimationTrack animationTrack(PathEnsembleService.Ensemble ensemble,
                                                 List<UnderlyingStep> frames) {
        if (frames.isEmpty()) return null;
        return new AnimationTrack("SELECT_NEAREST_FRAME_NO_INTERPOLATION", "underlyingSteps",
                "positions[].steps", frames.size(), ensemble.spec().totalSteps(),
                Math.max(1, ensemble.spec().stepsPerDay()), ensemble.spot(),
                frames.getLast().sessionProgress(), frames.getFirst().atmIv(), "TRACK_FRAME_0");
    }

    /** Resolve one package lifecycle before projecting the shared animation grid. */
    private static PositionBoundary positionBoundary(PositionInput input, ScenarioCanvasSpec canvas,
                                                     int steps, int stepsPerDay) {
        boolean hasStock = false;
        LocalDate latestExpiration = null;
        long latestExpiryStep = -1;
        for (Leg leg : input.position().legs()) {
            if (leg.isStock()) {
                hasStock = true;
                continue;
            }
            int expiryDay = input.position().expiryDay(leg);
            long legStep = expiryDay <= 0 ? Math.min(stepsPerDay, steps)
                    : Math.multiplyExact((long) expiryDay, stepsPerDay);
            if (latestExpiration == null || leg.expiration().isAfter(latestExpiration)) {
                latestExpiration = leg.expiration();
                latestExpiryStep = legStep;
            }
        }
        if (hasStock) {
            return new PositionBoundary(steps, latestExpiration,
                    "HORIZON_END_STOCK_EXPOSURE", false);
        }
        if (latestExpiration == null) {
            throw new IllegalArgumentException("canvas package has neither stock nor option exposure");
        }
        if (canvas.settlementPolicy() == ScenarioCanvasSpec.SettlementPolicy.PHYSICAL_IF_ITM) {
            return new PositionBoundary(steps, latestExpiration,
                    "HORIZON_END_PHYSICAL_EXPOSURE", false);
        }
        if (latestExpiryStep > steps) {
            return new PositionBoundary(steps, latestExpiration,
                    "HORIZON_END_OPTION_OUTLIVES_TRACK", false);
        }
        return new PositionBoundary((int) latestExpiryStep, latestExpiration,
                "FINAL_CASH_SETTLEMENT", true);
    }

    /** Serialize the already-resolved lifecycle against the exact shared display grid. */
    private static PositionAnimation positionAnimation(PositionBoundary boundary, int[] displaySteps,
                                                       int stepsPerDay) {
        if (displaySteps.length == 0) {
            return new PositionAnimation(0, -1, null,
                    boundary.finalOptionExpiration() == null ? null
                            : boundary.finalOptionExpiration().toString(),
                    "NO_FRAMES", false,
                    "This package produced no per-step animation frames on the stored fan.");
        }
        int terminal = Arrays.binarySearch(displaySteps, boundary.terminalStep());
        if (terminal < 0) {
            throw new IllegalStateException("shared animation grid omitted required terminal step "
                    + boundary.terminalStep());
        }
        return new PositionAnimation(displaySteps.length, terminal,
                sessionProgress(displaySteps[terminal], stepsPerDay),
                boundary.finalOptionExpiration() == null ? null
                        : boundary.finalOptionExpiration().toString(),
                boundary.boundaryReason(), boundary.exposureResolvedAtBoundary(), null);
    }

    private static String date(int day, LocalDate anchor, List<LocalDate> sessions) {
        return day == 0 ? anchor.toString() : sessions.get(Math.min(day, sessions.size()) - 1).toString();
    }
    private static String dateForStep(int step, int stepsPerDay, int days,
                                      LocalDate anchor, List<LocalDate> sessions) {
        int day = step == 0 ? 0 : Math.min(days,
                (step + Math.max(1, stepsPerDay) - 1) / Math.max(1, stepsPerDay));
        return date(day, anchor, sessions);
    }
    private static double sessionProgress(int step, int stepsPerDay) {
        return round4((double) step / Math.max(1, stepsPerDay));
    }
    private static double sum(double[] values) { double s = 0; for (double v : values) s += v; return s; }
}
