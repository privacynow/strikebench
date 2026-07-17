package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.research.BootstrapSampler;
import io.liftandshift.strikebench.research.ResearchQuestionEngine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import io.liftandshift.strikebench.util.DataUnavailableException;

/**
 * The one source of price-path ensembles. It owns lane-aware anchors, bootstrap history and
 * empirical analog reconstruction; valuation engines consume the resulting immutable matrix.
 * Statistical interpretations remain explicit and are never blended.
 */
public final class PathEnsembleService {

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
                           String modelVersion) {
        public Ensemble {
            if (paths == null || paths.length == 0) throw new IllegalArgumentException("no paths in the ensemble");
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
            return new Ensemble(basis, scope, spot, spec,
                    generator.generate(spec, spot, historicalLogReturns(scope)), null,
                    PathGenerator.MODEL_VERSION);
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
        return new Ensemble(basis, scope, spot, empiricalSpec, absolute, study, version);
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
