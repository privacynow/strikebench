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
import io.liftandshift.strikebench.recommend.RecommendationEngine;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Turns a set of engine candidates into a ranked competition of {@link StrategyEvaluation}s by
 * assembling the live {@link EvalContext} from market data (underlying, DTE, ATM IV, realized vol,
 * and — the moat paying off — the OBSERVED IV history from our own recorded snapshots), running the
 * pure {@link StrategyEvaluator}, and persisting the result for later review/calibration.
 */
public final class EvaluationService {

    private final MarketDataService market;
    private final RecommendationEngine engine;
    private final Db db;
    private final Clock clock;
    private final StrategyEvaluator evaluator = new StrategyEvaluator();
    private final EvaluationStore store;
    private final CalibrationService calibration;

    /** Concurrency cap for a universe scan — I/O-bound, so a small cap overlaps provider calls
     *  without hammering them. */
    private static final int SCAN_CONCURRENCY = 8;

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

    public EvaluationService(MarketDataService market, RecommendationEngine engine, Db db, Clock clock) {
        this.market = market;
        this.engine = engine;
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

    /** One symbol's competition, across the top opportunities of a universe scan. */
    public record ScanResult(List<StrategyEvaluation> ranked, List<String> notes, int scanned) {}

    /** Evaluates + ranks the candidates for one request, and (optionally) persists them. */
    public List<StrategyEvaluation> evaluate(String symbol, String intent, String thesis, String horizon,
                                             String riskMode, List<Candidate> candidates,
                                             long buyingPowerCents, String userId, boolean persist) {
        return evaluate(symbol, intent, thesis, horizon, riskMode, candidates, buyingPowerCents, userId, persist,
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED);
    }

    /** Context-aware variant: realized-vol history follows the caller's analysis dataset. */
    public List<StrategyEvaluation> evaluate(String symbol, String intent, String thesis, String horizon,
                                             String riskMode, List<Candidate> candidates,
                                             long buyingPowerCents, String userId, boolean persist,
                                             io.liftandshift.strikebench.db.AnalysisContext actx) {
        return evaluate(symbol, intent, thesis, horizon, riskMode, candidates, buyingPowerCents,
                userId, persist, actx, null);
    }

    /**
     * WORLD-AWARE variant (review P0): the decision score must be computed from the SAME market
     * that priced the candidates — inside a simulated session the spot, ATM IV, realized vol and
     * clock all come from that world. worldId null = observed.
     */
    public List<StrategyEvaluation> evaluate(String symbol, String intent, String thesis, String horizon,
                                             String riskMode, List<Candidate> candidates,
                                             long buyingPowerCents, String userId, boolean persist,
                                             io.liftandshift.strikebench.db.AnalysisContext actx, String worldId) {
        List<StrategyEvaluation> ranked = rank(symbol, intent, thesis, horizon, riskMode, candidates,
                buyingPowerCents, actx, worldId);
        if (persist && !ranked.isEmpty()) store.saveAll(ranked, userId);
        return ranked;
    }

    /** Reuses the same context/risk/evidence spine for the exact package on Ticket Review. */
    public EconomicAssessment assessExact(String symbol, Candidate candidate, long buyingPowerCents,
                                          io.liftandshift.strikebench.db.AnalysisContext actx, String worldId,
                                          boolean mechanicallyEligible, List<String> mechanicalFailures,
                                          long roundTripFeesCents) {
        EvalContext ctx = buildContext(symbol, List.of(candidate), buyingPowerCents, actx, worldId);
        return evaluator.assessExact(candidate, ctx, mechanicallyEligible, mechanicalFailures,
                roundTripFeesCents);
    }

    /**
     * Universe-scale competition: evaluates each symbol, keeps its single best VIABLE idea, then
     * ranks those cross-symbol so the strongest opportunities float to the top. Persists the
     * winners for later review/calibration. One symbol failing never aborts the scan.
     */
    public ScanResult scan(List<String> symbols, String intent, String thesis, String horizon, String riskMode,
                           long buyingPowerCents, String userId, int topN) {
        return scan(symbols, intent, thesis, horizon, riskMode, buyingPowerCents, userId, topN, null, null);
    }

    /** World-aware + risk-governed scan: candidates AND their evaluation context share one lane,
     *  and the caller's declared risk capital caps the engine budget (R4). */
    public ScanResult scan(List<String> symbols, String intent, String thesis, String horizon, String riskMode,
                           long buyingPowerCents, String userId, int topN, String worldId, Long maxLossCents) {
        List<String> syms = new ArrayList<>();
        for (String raw : symbols == null ? List.<String>of() : symbols) {
            String sym = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            if (!sym.isEmpty()) syms.add(sym);
        }
        int scanned = syms.size();
        if (syms.isEmpty()) return new ScanResult(List.of(), List.of(), 0);

        // Per-symbol evaluation is independent and I/O-bound (provider calls). Run the symbols on
        // virtual threads under a small concurrency cap so a universe scan overlaps instead of
        // stacking into a serial waterfall; one symbol throwing only drops that symbol (isolation).
        // Results are reassembled in INPUT ORDER, so the ranked outcome is deterministic regardless
        // of which symbol happens to finish first.
        record PerSym(StrategyEvaluation best, String note) {}
        Semaphore gate = new Semaphore(Math.min(SCAN_CONCURRENCY, syms.size()));
        List<PerSym> out = new ArrayList<>(Collections.nCopies(syms.size(), null));
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<PerSym>> futures = new ArrayList<>();
            for (String sym : syms) {
                futures.add(exec.submit(() -> {
                    gate.acquire();
                    try {
                        var req = new RecommendationEngine.Request(sym, thesis, horizon, riskMode, maxLossCents,
                                null, null, null, false, false, intent, null, null);
                        var result = engine.recommend(req, buyingPowerCents, worldId);
                        if (result.candidates().isEmpty()) return new PerSym(null, sym + ": no candidates");
                        StrategyEvaluation top = rank(sym, result.intent(), result.thesis(), result.horizon(),
                                result.riskMode(), result.candidates(), buyingPowerCents,
                                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, worldId).stream()
                                .filter(StrategyEvaluation::viable).findFirst().orElse(null);
                        return new PerSym(top, null); // no-viable is silently dropped, as before
                    } catch (RuntimeException e) {
                        return new PerSym(null, sym + ": analysis unavailable right now");
                    } finally { gate.release(); }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try { out.set(i, futures.get(i).get()); }
                catch (Exception e) { out.set(i, new PerSym(null, syms.get(i) + ": analysis unavailable right now")); }
            }
        }

        List<StrategyEvaluation> best = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (PerSym p : out) {
            if (p == null) continue;
            if (p.best() != null) best.add(p.best());
            if (p.note() != null) notes.add(p.note());
        }
        best.sort(StrategyEvaluator.RANKING);
        List<StrategyEvaluation> top = best.stream().limit(Math.max(1, topN)).toList();
        if (!top.isEmpty()) store.saveAll(top, userId);
        return new ScanResult(top, notes, scanned);
    }

    private List<StrategyEvaluation> rank(String symbol, String intent, String thesis, String horizon,
                                          String riskMode, List<Candidate> candidates, long buyingPowerCents,
                                          io.liftandshift.strikebench.db.AnalysisContext actx, String worldId) {
        EvalContext ctx = buildContext(symbol, candidates, buyingPowerCents, actx, worldId);
        String family = candidates.isEmpty() ? null : candidates.getFirst().strategy();
        StrategySpec spec = new StrategySpec(symbol, family, intent, horizon, thesis, riskMode, "decision");
        return evaluator.evaluateAndRank(candidates, spec, ctx);
    }

    public List<java.util.Map<String, Object>> recent(String userId, int limit) {
        return store.recent(userId, limit);
    }

    private EvalContext buildContext(String symbol, List<Candidate> candidates, long buyingPowerCents,
                                     io.liftandshift.strikebench.db.AnalysisContext actx, String worldId) {
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

        return new EvalContext(symbol, underlyingCents, dte, atmIv, realizedVol, ivHistory, buyingPowerCents, open,
                feePerContractCents, feePerOrderCents, rate.annualRate(), rate.evidence());
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
