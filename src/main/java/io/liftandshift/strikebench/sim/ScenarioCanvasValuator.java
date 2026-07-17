package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.model.Leg;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reprices many same-symbol packages on one already-built ensemble for the unified canvas.  It is
 * intentionally a consumer of {@link PathEnsembleService.Ensemble} and
 * {@link PathValuationKernel}; it never generates a second path set or implements another option
 * pricing rule.
 */
public final class ScenarioCanvasValuator {

    private static final long MAX_CANVAS_VALUATION_WORK = 40_000_000L;

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
                                double atmIv) {}
    public record Greeks(double deltaShares, double gammaSharesPerDollar,
                         double thetaCentsPerDay, double vegaCentsPerPoint) {}
    public record LegDay(int day, long valueCents, long optionPriceCents, Greeks greeks, String state) {}
    public record LegPath(int legNo, String label, String expiration, int multiplier,
                          List<LegDay> days) {}
    public record PositionDay(int day, String sessionDate,
                              long valueP10Cents, long valueP50Cents, long valueP90Cents,
                              Long pnlP10Cents, Long pnlP50Cents, Long pnlP90Cents,
                              Greeks greeks) {}
    public record Transformation(int day, String sessionDate, int legNo, String leg,
                                 String settlementPolicy, String exercisePolicy, String note) {}
    public record PositionPath(String key, String label, String lane, String source, boolean proposed,
                               Long entryCostCents, List<PositionDay> days, List<LegPath> legs,
                               List<Transformation> transformations) {}
    public record ComparisonRow(String key, String label, String lane, boolean proposed,
                                Long entryCostCents, long horizonP5Cents, long horizonP50Cents,
                                long horizonP95Cents, long expectedHorizonCents,
                                double chanceOfGainPct, Long versusStockP50Cents) {}
    public record Report(List<UnderlyingDay> underlying, List<PositionPath> positions,
                         List<ComparisonRow> comparison, List<String> notes) {}

    public Report value(PathEnsembleService.Ensemble ensemble, IvSpec legacyIv,
                        ScenarioCanvasSpec rawCanvas, double annualRate,
                        List<PositionInput> rawPositions) {
        try (AutoCloseable permit = SimBudget.acquire()) {
            return valuePermitted(ensemble, legacyIv, rawCanvas, annualRate, rawPositions);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Report valuePermitted(PathEnsembleService.Ensemble ensemble, IvSpec legacyIv,
                                  ScenarioCanvasSpec rawCanvas, double annualRate,
                                  List<PositionInput> rawPositions) {
        if (ensemble == null) throw new IllegalArgumentException("canvas ensemble is required");
        if (rawPositions == null || rawPositions.isEmpty()) {
            return new Report(underlying(ensemble.paths(), ensemble,
                    (legacyIv == null ? IvSpec.flat(ensemble.spec().volAnnual()) : legacyIv).sane(),
                    rawCanvas == null ? ScenarioCanvasSpec.defaults() : rawCanvas.sane(ensemble.spec().horizonDays())),
                    List.of(), List.of(), List.of("No same-symbol positions were available to reprice."));
        }
        if (rawPositions.size() > 32) throw new IllegalArgumentException("at most 32 positions can share one canvas");
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
        IvSpec iv = (legacyIv == null ? IvSpec.flat(spec.volAnnual()) : legacyIv).sane();
        double[] stepYears = spec.calendarStepYears(ensemble.anchorDate());
        double[] elapsed = PathValuationKernel.elapsed(stepYears);
        int steps = spec.totalSteps(), spd = Math.max(1, spec.stepsPerDay()), days = steps / spd;
        double[] legacyPath = iv.path(steps, sum(stepYears) / steps, spd);
        List<LocalDate> sessionDates = ScenarioSpec.sessionDates(ensemble.anchorDate(), days);
        double[][] paths = ensemble.paths();
        List<UnderlyingDay> underlying = new ArrayList<>();
        int representativePath = rank(paths, steps).indexAt(0.50);
        for (int day = 0; day <= days; day++) {
            int step = Math.min(steps, day * spd);
            Ranked ranked = rank(paths, step);
            double atm = canvas.atmIv(day, days, legacyPath[step]);
            underlying.add(new UnderlyingDay(day, date(day, ensemble.anchorDate(), sessionDates),
                    round2(ranked.valueAt(0.10)), round2(ranked.valueAt(0.50)),
                    round2(ranked.valueAt(0.90)), round4(atm)));
        }

        List<PositionPath> positionPaths = new ArrayList<>();
        List<ComparisonRow> comparisons = new ArrayList<>();
        LinkedHashMap<String, long[]> terminalPnl = new LinkedHashMap<>();
        for (PositionInput input : rawPositions) {
            PositionRun run = valuePosition(input, ensemble, canvas, annualRate, elapsed,
                    legacyPath, sessionDates, representativePath);
            positionPaths.add(run.path());
            terminalPnl.put(input.key(), run.terminalPnl());
        }
        Long stockMedian = null;
        for (PositionInput input : rawPositions) {
            if ("STOCK_BASELINE".equals(input.source())) {
                long[] sorted = terminalPnl.get(input.key()).clone(); Arrays.sort(sorted);
                stockMedian = pct(sorted, 0.50); break;
            }
        }
        for (PositionInput input : rawPositions) {
            long[] terminal = terminalPnl.get(input.key()).clone();
            long sum = 0; int wins = 0;
            for (long value : terminal) { sum += value; if (value > 0) wins++; }
            Arrays.sort(terminal);
            long median = pct(terminal, 0.50);
            comparisons.add(new ComparisonRow(input.key(), input.label(), input.lane(), input.proposed(),
                    input.entryCostCents(), pct(terminal, 0.05), median, pct(terminal, 0.95),
                    Math.round((double) sum / terminal.length), Math.round(wins * 1000.0 / terminal.length) / 10.0,
                    stockMedian == null || "STOCK_BASELINE".equals(input.source()) ? null : median - stockMedian));
        }
        comparisons.sort((a, b) -> Long.compare(b.horizonP50Cents(), a.horizonP50Cents()));
        List<String> notes = new ArrayList<>();
        notes.add("Authored paths are the user's hypothesis, never a forecast. Every position is repriced on one identical stored ensemble.");
        notes.add("Option values and Greeks are MODELED from the declared per-day IV surface; underlying bands come from the stored path matrix.");
        notes.add("P&L bands use every stored path. Daily Greeks, leg detail, and transformation events follow one representative path whose horizon price is the ensemble median, so they remain a coherent trace rather than a probability band.");
        notes.add(canvas.dividendBasis());
        if (canvas.template() != null) notes.add(canvas.template().legDayProvenance());
        return new Report(List.copyOf(underlying), List.copyOf(positionPaths),
                List.copyOf(comparisons), List.copyOf(notes));
    }

    private PositionRun valuePosition(PositionInput input, PathEnsembleService.Ensemble ensemble,
                                      ScenarioCanvasSpec canvas, double annualRate, double[] elapsed,
                                      double[] legacyPath, List<LocalDate> sessionDates,
                                      int representativePath) {
        ScenarioSpec spec = ensemble.spec();
        int steps = spec.totalSteps(), spd = Math.max(1, spec.stepsPerDay()), days = steps / spd;
        double[][] paths = ensemble.paths();
        int[][] resolvedTransformations = new int[paths.length][];
        for (int p = 0; p < paths.length; p++) {
            resolvedTransformations[p] = PathValuationKernel.transformationSteps(input.position(),
                    paths[p], steps, spd, elapsed, legacyPath, canvas, annualRate);
        }
        long entry = input.entryCostCents() == null
                ? cents(PathValuationKernel.valueCanvas(input.position(), paths[0], 0, steps, spd,
                    elapsed, legacyPath, canvas, annualRate, resolvedTransformations[0]) * input.qty())
                : input.entryCostCents();
        List<PositionDay> timeline = new ArrayList<>();
        List<List<LegDay>> legDays = new ArrayList<>();
        for (int i = 0; i < input.position().legs().size(); i++) legDays.add(new ArrayList<>());
        long[] terminal = new long[paths.length];
        for (int day = 0; day <= days; day++) {
            int step = Math.min(steps, day * spd);
            long[] values = new long[paths.length];
            for (int p = 0; p < paths.length; p++) {
                values[p] = cents(PathValuationKernel.valueCanvas(input.position(), paths[p], step,
                        steps, spd, elapsed, legacyPath, canvas, annualRate,
                        resolvedTransformations[p]) * input.qty());
            }
            long[] sorted = values.clone(); Arrays.sort(sorted);
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
                legDays.get(legNo).add(new LegDay(day, cents(point.valueDollars() * q),
                        cents(point.optionPrice()), new Greeks(round4(point.deltaShares() * q),
                        round4(point.gammaSharesPerDollar() * q), cents(point.thetaDollarsPerDay() * q),
                        cents(point.vegaDollarsPerPoint() * q)), point.state()));
            }
            timeline.add(new PositionDay(day, date(day, ensemble.anchorDate(), sessionDates),
                    pct(sorted, 0.10), pct(sorted, 0.50), pct(sorted, 0.90),
                    pct(sorted, 0.10) - entry, pct(sorted, 0.50) - entry, pct(sorted, 0.90) - entry,
                    new Greeks(round4(dd), round4(gg), cents(tt), cents(vv))));
        }
        List<LegPath> legs = new ArrayList<>();
        List<Transformation> transformationRows = new ArrayList<>();
        for (int i = 0; i < input.position().legs().size(); i++) {
            Leg leg = input.position().legs().get(i);
            String label = leg.isStock() ? leg.action() + " " + leg.ratio() * leg.multiplier() + " shares"
                    : leg.action() + " " + leg.type() + " " + leg.strike();
            legs.add(new LegPath(i, label, leg.isStock() ? null : leg.expiration().toString(),
                    leg.multiplier(), List.copyOf(legDays.get(i))));
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
                input.proposed(), input.entryCostCents(), List.copyOf(timeline), List.copyOf(legs),
                List.copyOf(transformationRows)), terminal);
    }

    private record PositionRun(PositionPath path, long[] terminalPnl) {}
    private record Pair(double value, int index) {}
    private record Ranked(Pair[] sorted) {
        double valueAt(double p) { return sorted[index(p)].value(); }
        int indexAt(double p) { return sorted[index(p)].index(); }
        private int index(double p) { return Math.max(0, Math.min(sorted.length - 1,
                (int) Math.floor(p * (sorted.length - 1)))); }
    }

    private static Ranked rank(double[][] paths, int step) {
        Pair[] pairs = new Pair[paths.length];
        for (int i = 0; i < paths.length; i++) pairs[i] = new Pair(paths[i][step], i);
        Arrays.sort(pairs, java.util.Comparator.comparingDouble(Pair::value));
        return new Ranked(pairs);
    }

    private static List<UnderlyingDay> underlying(double[][] paths, PathEnsembleService.Ensemble ensemble,
                                                   IvSpec iv, ScenarioCanvasSpec canvas) {
        int days = ensemble.spec().horizonDays(), spd = ensemble.spec().stepsPerDay();
        double[] stepYears = ensemble.spec().calendarStepYears(ensemble.anchorDate());
        double[] legacy = iv.path(ensemble.spec().totalSteps(), sum(stepYears) / stepYears.length, spd);
        List<LocalDate> dates = ScenarioSpec.sessionDates(ensemble.anchorDate(), days);
        List<UnderlyingDay> out = new ArrayList<>();
        for (int day = 0; day <= days; day++) {
            int step = Math.min(ensemble.spec().totalSteps(), day * spd);
            Ranked r = rank(paths, step);
            out.add(new UnderlyingDay(day, date(day, ensemble.anchorDate(), dates), round2(r.valueAt(.1)),
                    round2(r.valueAt(.5)), round2(r.valueAt(.9)), round4(canvas.atmIv(day, days, legacy[step]))));
        }
        return out;
    }

    private static String date(int day, LocalDate anchor, List<LocalDate> sessions) {
        return day == 0 ? anchor.toString() : sessions.get(Math.min(day, sessions.size()) - 1).toString();
    }
    private static long pct(long[] sorted, double p) {
        return sorted[Math.max(0, Math.min(sorted.length - 1,
                (int) Math.floor(p * (sorted.length - 1))))];
    }
    private static double sum(double[] values) { double s = 0; for (double v : values) s += v; return s; }
    private static long cents(double dollars) { return Math.round(dollars * 100); }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
