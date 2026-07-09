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

import java.math.BigDecimal;
import java.time.Clock;
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
    private final RecommendationEngine engine;
    private final Db db;
    private final Clock clock;
    private final StrategyEvaluator evaluator = new StrategyEvaluator();
    private final EvaluationStore store;
    private final CalibrationService calibration;

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

    /** One symbol's competition, across the top opportunities of a universe scan. */
    public record ScanResult(List<StrategyEvaluation> ranked, List<String> notes, int scanned) {}

    /** Evaluates + ranks the candidates for one request, and (optionally) persists them. */
    public List<StrategyEvaluation> evaluate(String symbol, String intent, String thesis, String horizon,
                                             String riskMode, List<Candidate> candidates,
                                             long buyingPowerCents, String userId, boolean persist) {
        List<StrategyEvaluation> ranked = rank(symbol, intent, thesis, horizon, riskMode, candidates, buyingPowerCents);
        if (persist && !ranked.isEmpty()) store.saveAll(ranked, userId);
        return ranked;
    }

    /**
     * Universe-scale competition: evaluates each symbol, keeps its single best VIABLE idea, then
     * ranks those cross-symbol so the strongest opportunities float to the top. Persists the
     * winners for later review/calibration. One symbol failing never aborts the scan.
     */
    public ScanResult scan(List<String> symbols, String intent, String thesis, String horizon, String riskMode,
                           long buyingPowerCents, String userId, int topN) {
        List<StrategyEvaluation> best = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int scanned = 0;
        for (String raw : symbols == null ? List.<String>of() : symbols) {
            String sym = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (sym.isEmpty()) continue;
            scanned++;
            try {
                var req = new RecommendationEngine.Request(sym, thesis, horizon, riskMode, null, null, null,
                        null, false, false, intent, null, null);
                var result = engine.recommend(req, buyingPowerCents);
                if (result.candidates().isEmpty()) { notes.add(sym + ": no candidates"); continue; }
                rank(sym, result.intent(), result.thesis(), result.horizon(), result.riskMode(),
                        result.candidates(), buyingPowerCents).stream()
                        .filter(StrategyEvaluation::viable).findFirst().ifPresent(best::add);
            } catch (RuntimeException e) {
                notes.add(sym + ": " + e.getClass().getSimpleName());
            }
        }
        best.sort(Comparator.comparingDouble(StrategyEvaluation::rankScore).reversed());
        List<StrategyEvaluation> top = best.stream().limit(Math.max(1, topN)).toList();
        if (!top.isEmpty()) store.saveAll(top, userId);
        return new ScanResult(top, notes, scanned);
    }

    private List<StrategyEvaluation> rank(String symbol, String intent, String thesis, String horizon,
                                          String riskMode, List<Candidate> candidates, long buyingPowerCents) {
        EvalContext ctx = buildContext(symbol, candidates, buyingPowerCents);
        String family = candidates.isEmpty() ? null : candidates.getFirst().strategy();
        StrategySpec spec = new StrategySpec(symbol, family, intent, horizon, thesis, riskMode, "risk_adjusted");
        return evaluator.evaluateAndRank(candidates, spec, ctx);
    }

    public List<java.util.Map<String, Object>> recent(String userId, int limit) {
        return store.recent(userId, limit);
    }

    private EvalContext buildContext(String symbol, List<Candidate> candidates, long buyingPowerCents) {
        LocalDate today = LocalDate.now(clock);
        long underlyingCents = market.quote(symbol)
                .map(q -> q.mark() == null ? 0L : Money.toCents(q.mark())).orElse(0L);

        LocalDate frontExp = frontExpiration(candidates);
        int dte = frontExp != null ? Math.max(0, (int) ChronoUnit.DAYS.between(today, frontExp)) : 30;

        Double atmIv = atmIv(symbol, underlyingCents, frontExp);
        Double realizedVol = realizedVol30(symbol, today);
        List<Double> ivHistory = ivHistory(symbol);
        boolean open = MarketHours.isRegularSession(Instant.now(clock));

        return new EvalContext(symbol, underlyingCents, dte, atmIv, realizedVol, ivHistory, buyingPowerCents, open);
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

    private Double atmIv(String symbol, long underlyingCents, LocalDate frontExp) {
        LocalDate exp = frontExp;
        if (exp == null) {
            List<LocalDate> exps = market.expirations(symbol);
            if (exps.isEmpty()) return null;
            exp = exps.getFirst();
        }
        OptionChain chain = market.chain(symbol, exp).orElse(null);
        if (chain == null || chain.isEmpty()) return null;
        BigDecimal underlying = Money.priceFromCents(underlyingCents);
        return chain.calls().stream()
                .filter(q -> q.iv() != null && q.strike() != null)
                .min(Comparator.comparing(q -> q.strike().subtract(underlying).abs()))
                .map(OptionQuote::iv)
                .orElse(null);
    }

    private Double realizedVol30(String symbol, LocalDate today) {
        List<Candle> candles = market.candles(symbol, today.minusDays(60), today);
        if (candles == null || candles.size() < 20) return null;
        double v = HistoricalVol.annualized(candles, 30);
        return Double.isFinite(v) && v > 0 ? v : null;
    }

    /**
     * Observed near-the-money IV per snapshot day from our own recordings (iv_source='vendor'
     * only, so fixtures/models are excluded and a fresh DB honestly yields an empty history ->
     * IV rank/percentile stay null rather than fabricated).
     */
    private List<Double> ivHistory(String symbol) {
        return db.query("""
                SELECT avg(iv) AS iv FROM option_bar
                WHERE symbol = ? AND iv IS NOT NULL AND iv_source = 'vendor'
                  AND underlying IS NOT NULL AND abs(strike - underlying) <= underlying * 0.05
                GROUP BY asof ORDER BY asof DESC LIMIT 90
                """, r -> r.dbl("iv"), symbol);
    }
}
