package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.pricing.HistoricalVol;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.util.Money;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns a set of engine candidates into a ranked competition of {@link StrategyEvaluation}s by
 * assembling the live {@link EvalContext} from market data (underlying, DTE, ATM IV, realized vol,
 * and — the moat paying off — the OBSERVED IV history from our own recorded snapshots), running the
 * pure {@link StrategyEvaluator}, and persisting the result for later review/calibration.
 */
public final class EvaluationService {

    private final MarketDataService market;
    private final Db db;
    private final Clock clock;
    private final StrategyEvaluator evaluator = new StrategyEvaluator();
    private final EvaluationStore store;
    private final CalibrationService calibration;

    /** Memoizes the one uncached read in {@link #buildContext}: the observed-IV-history DB
     *  aggregation. Everything else it needs (quote, chain, candles) is already Caffeine-cached
     *  inside {@link MarketDataService}. Date-stable, so a 60s TTL is safe; keyed by symbol. */
    private final Cache<String, List<Double>> ivHistoryCache =
            Caffeine.newBuilder().maximumSize(256).expireAfterWrite(Duration.ofSeconds(60)).build();

    private long feePerContractCents = 65;
    private long feePerOrderCents;

    /** Wire the complete platform commission so decision EV matches the eventual ticket. */
    public EvaluationService withFees(long perContractCents, long perOrderCents) {
        this.feePerContractCents = Math.max(0, perContractCents);
        this.feePerOrderCents = Math.max(0, perOrderCents);
        return this;
    }

    public EvaluationService(MarketDataService market, Db db, Clock clock) {
        this.market = market;
        this.db = db;
        this.clock = clock;
        this.store = new EvaluationStore(db);
        this.calibration = new CalibrationService(db, clock);
    }

    /** Records that an evaluation was surfaced (the calibration sample); requires it to be persisted. */
    public String recordSurfaced(String evaluationId, String userId) {
        return calibration.record(evaluationId, userId, "evaluate");
    }

    public void resolveOutcome(String recommendationId, String status, Long pnlCents) {
        calibration.resolveOutcome(recommendationId, status, pnlCents);
    }

    public void linkTrade(String recommendationId, String tradeId) {
        calibration.linkTrade(recommendationId, tradeId);
    }

    /** Auto-resolves any recommendation tied to a closed trade (best-effort). */
    public void resolveByTrade(String tradeId, String status, Long pnlCents) {
        calibration.resolveByTrade(tradeId, status, pnlCents);
    }

    public java.util.Map<String, Object> calibrationReport(String userId) {
        return calibration.report(userId);
    }

    /** Research uses the same IV-rank history and thresholds as candidate evaluation. A generated
     * world never borrows observed IV history; its missing rank is an honest lane property. */
    public VolatilityProfile volatilitySnapshot(String symbol, Double atmIv, Double realizedVol30,
                                                 int daysToExpiry, String worldId) {
        List<Double> history = worldId == null ? ivHistory(symbol) : List.of();
        return new VolatilityProfiler().profile(atmIv, realizedVol30, history, daysToExpiry);
    }

    /**
     * WORLD-AWARE variant (review P0): the decision score must be computed from the SAME market
     * that priced the candidates — inside a simulated session the spot, ATM IV, realized vol and
     * clock all come from that world. worldId null = observed.
     */
    public List<StrategyEvaluation> evaluate(String symbol, String intent, String thesis, String horizon,
                                             String riskMode, List<Candidate> candidates,
                                             long buyingPowerCents, String userId, boolean persist,
                                             io.liftandshift.strikebench.db.AnalysisContext actx, String worldId,
                                             PortfolioExposureContext portfolioExposure) {
        return evaluate(symbol, intent, thesis, horizon, riskMode, candidates, buyingPowerCents,
                userId, persist, actx, worldId, portfolioExposure, null);
    }

    /** Ranking variant that also carries the DECLARED assignment preference (objective lens). */
    public List<StrategyEvaluation> evaluate(String symbol, String intent, String thesis, String horizon,
                                             String riskMode, List<Candidate> candidates,
                                             long buyingPowerCents, String userId, boolean persist,
                                             io.liftandshift.strikebench.db.AnalysisContext actx, String worldId,
                                             PortfolioExposureContext portfolioExposure,
                                             String assignmentPreference) {
        List<StrategyEvaluation> ranked = rank(symbol, intent, thesis, horizon, riskMode, candidates,
                buyingPowerCents, actx, worldId, portfolioExposure, assignmentPreference);
        if (persist && !ranked.isEmpty()) store.saveAll(ranked, userId);
        return ranked;
    }

    /** Reuses the complete candidate pipeline for the exact package on Ticket Review. */
    public StrategyEvaluation assessExact(String symbol, Candidate candidate, long buyingPowerCents,
                                          io.liftandshift.strikebench.db.AnalysisContext actx, String worldId,
                                          boolean mechanicallyEligible, List<String> mechanicalFailures,
                                          long roundTripFeesCents,
                                          PortfolioExposureContext portfolioExposure) {
        return assessExact(symbol, candidate, buyingPowerCents, actx, worldId, mechanicallyEligible,
                mechanicalFailures, roundTripFeesCents, portfolioExposure, null);
    }

    /** Exact assessment judged against what the user DECLARED (a plan view or account objective). */
    public StrategyEvaluation assessExact(String symbol, Candidate candidate, long buyingPowerCents,
                                          io.liftandshift.strikebench.db.AnalysisContext actx, String worldId,
                                          boolean mechanicallyEligible, List<String> mechanicalFailures,
                                          long roundTripFeesCents,
                                          PortfolioExposureContext portfolioExposure,
                                          DeclaredObjective declared) {
        EvalContext ctx = buildContext(symbol, List.of(candidate), buyingPowerCents, actx, worldId,
                portfolioExposure, declared);
        StrategySpec spec = new StrategySpec(symbol, candidate.strategy(), candidate.intent(),
                ctx.daysToExpiry() + "d", null, null, "exact-position");
        return evaluator.assessExact(candidate, spec, ctx, mechanicallyEligible, mechanicalFailures,
                roundTripFeesCents);
    }

    /** Persists an already-ranked competition; empty/generated scans are harmless no-ops. */
    public void persist(List<StrategyEvaluation> ranked, String userId) {
        if (ranked != null && !ranked.isEmpty() && userId != null) store.saveAll(ranked, userId);
    }

    private List<StrategyEvaluation> rank(String symbol, String intent, String thesis, String horizon,
                                          String riskMode, List<Candidate> candidates, long buyingPowerCents,
                                          io.liftandshift.strikebench.db.AnalysisContext actx, String worldId,
                                          PortfolioExposureContext portfolioExposure) {
        return rank(symbol, intent, thesis, horizon, riskMode, candidates, buyingPowerCents,
                actx, worldId, portfolioExposure, null);
    }

    private List<StrategyEvaluation> rank(String symbol, String intent, String thesis, String horizon,
                                          String riskMode, List<Candidate> candidates, long buyingPowerCents,
                                          io.liftandshift.strikebench.db.AnalysisContext actx, String worldId,
                                          PortfolioExposureContext portfolioExposure,
                                          String assignmentPreference) {
        // The ranked field is evaluated AGAINST the declared view: the coherence diagnostic
        // (Program ONE folded Phase 9) compares what the user said with each structure's stance.
        DeclaredObjective declared = (intent == null && thesis == null && horizon == null
                && assignmentPreference == null) ? null
                : new DeclaredObjective(intent, thesis, horizonSessions(horizon), assignmentPreference,
                        "this Plan's declared view");
        EvalContext ctx = buildContext(symbol, candidates, buyingPowerCents, actx, worldId,
                portfolioExposure, declared);
        String family = candidates.isEmpty() ? null : candidates.getFirst().strategy();
        StrategySpec spec = new StrategySpec(symbol, family, intent, horizon, thesis, riskMode, "decision");
        return evaluator.evaluateAndRank(candidates, spec, ctx);
    }

    private static Integer horizonSessions(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.matches("\\d+d")) return Integer.parseInt(value.substring(0, value.length() - 1));
        return io.liftandshift.strikebench.model.Horizon.parse(value).tradingSessions();
    }

    public List<java.util.Map<String, Object>> recent(String userId, int limit) {
        return store.recent(userId, limit);
    }

    private EvalContext buildContext(String symbol, List<Candidate> candidates, long buyingPowerCents,
                                     io.liftandshift.strikebench.db.AnalysisContext actx, String worldId,
                                     PortfolioExposureContext portfolioExposure) {
        return buildContext(symbol, candidates, buyingPowerCents, actx, worldId, portfolioExposure, null);
    }

    private EvalContext buildContext(String symbol, List<Candidate> candidates, long buyingPowerCents,
                                     io.liftandshift.strikebench.db.AnalysisContext actx, String worldId,
                                     PortfolioExposureContext portfolioExposure,
                                     DeclaredObjective declared) {
        // ONE LANE: spot, DTE clock, ATM IV and realized vol all come from the market that priced
        // the candidates — a sim world's numbers never blend with observed ones (review P0).
        Instant laneNow = market.simInstant(worldId).orElseGet(() -> Instant.now(clock));
        LocalDate today = LocalDate.ofInstant(laneNow, MarketHours.EASTERN);
        long underlyingCents = market.quote(symbol, worldId)
                .map(q -> q.mark() == null ? 0L : Money.toCents(q.mark())).orElse(0L);

        LocalDate frontExp = frontExpiration(candidates);
        int dte = frontExp != null ? Math.max(0, (int) ChronoUnit.DAYS.between(today, frontExp)) : 30;

        Double atmIv = atmIv(symbol, underlyingCents, frontExp, worldId);
        Double realizedVol = worldId != null
                ? realizedVolWorld(symbol, today, worldId)
                : realizedVol30(symbol, today, actx);
        List<Double> ivHistory = worldId != null ? List.of() : ivHistory(symbol); // no observed IV history in a world
        boolean open = worldId != null || MarketHours.isRegularSession(laneNow);

        var rate = market.riskFreeRateQuote(Math.max(1, dte), worldId);

        return new EvalContext(symbol, underlyingCents, today, dte, atmIv, realizedVol, ivHistory, buyingPowerCents, open,
                feePerContractCents, feePerOrderCents, rate.annualRate(), rate.evidence(), portfolioExposure, declared);
    }

    private static LocalDate frontExpiration(List<Candidate> candidates) {
        LocalDate min = null;
        for (Candidate c : candidates) {
            for (LegView l : c.legs()) {
                if (l.expiration() == null) continue;
                try {
                    LocalDate d = LocalDate.parse(l.expiration());
                    if (min == null || d.isBefore(min)) min = d;
                } catch (RuntimeException ignored) { /* skip unparseable */ }
            }
        }
        return min;
    }

    private Double atmIv(String symbol, long underlyingCents, LocalDate frontExp, String worldId) {
        LocalDate exp = frontExp;
        if (exp == null) {
            List<LocalDate> exps = market.expirations(symbol, worldId);
            if (exps.isEmpty()) return null;
            exp = exps.getFirst();
        }
        OptionChain chain = market.chain(symbol, exp, worldId).orElse(null);
        if (chain == null || chain.isEmpty()) return null;
        BigDecimal underlying = Money.priceFromCents(underlyingCents);
        return chain.calls().stream()
                .filter(q -> q.iv() != null && q.strike() != null)
                .min(Comparator.comparing(q -> q.strike().subtract(underlying).abs()))
                .map(OptionQuote::iv)
                .orElse(null);
    }

    private Double realizedVolWorld(String symbol, LocalDate today, String worldId) {
        var series = market.candleSeries(symbol, today.minusDays(60), today, worldId, null);
        if (series.isEmpty() || series.candles().size() < 20) return null;
        double v = HistoricalVol.annualized(series.candles(), 30);
        return Double.isFinite(v) && v > 0 ? v : null;
    }

    private Double realizedVol30(String symbol, LocalDate today,
                                 io.liftandshift.strikebench.db.AnalysisContext actx) {
        List<Candle> candles = market.candles(symbol, today.minusDays(60), today, actx);
        if (candles == null || candles.size() < 20) return null;
        double v = HistoricalVol.annualized(candles, 30);
        return Double.isFinite(v) && v > 0 ? v : null;
    }

    /**
     * Observed near-the-money IV per snapshot day from our own recordings (iv_source='vendor'
     * only, so fixtures/models are excluded and a fresh DB honestly yields an empty history ->
     * IV rank/percentile stay null rather than fabricated).
     */
    List<Double> ivHistory(String symbol) {
        return ivHistoryCache.get(symbol, this::queryIvHistory);
    }

    List<Double> queryIvHistory(String symbol) {
        return db.query("""
                SELECT avg(iv) AS iv FROM option_bar
                WHERE symbol = ? AND dataset_id = 'observed'
                  AND iv IS NOT NULL AND iv_source = 'vendor'
                  AND underlying IS NOT NULL AND abs(strike - underlying) <= underlying * 0.05
                GROUP BY CAST(asof AS date) ORDER BY CAST(asof AS date) DESC LIMIT 90
                """, r -> r.dbl("iv"), symbol);
    }

    /** Stored option history changed or was wiped; no cached IV statistic may survive it. */
    public void invalidateHistoricalData() {
        ivHistoryCache.invalidateAll();
    }
}
