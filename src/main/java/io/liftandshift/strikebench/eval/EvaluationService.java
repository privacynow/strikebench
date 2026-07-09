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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

    public EvaluationService(MarketDataService market, Db db, Clock clock) {
        this.market = market;
        this.db = db;
        this.clock = clock;
        this.store = new EvaluationStore(db);
    }

    /** Evaluates + ranks the candidates for one request, and (optionally) persists them. */
    public List<StrategyEvaluation> evaluate(String symbol, String intent, String thesis, String horizon,
                                             String riskMode, List<Candidate> candidates,
                                             long buyingPowerCents, String userId, boolean persist) {
        EvalContext ctx = buildContext(symbol, candidates, buyingPowerCents);
        String family = candidates.isEmpty() ? null : candidates.getFirst().strategy();
        StrategySpec spec = new StrategySpec(symbol, family, intent, horizon, thesis, riskMode, "risk_adjusted");
        List<StrategyEvaluation> ranked = evaluator.evaluateAndRank(candidates, spec, ctx);
        if (persist && !ranked.isEmpty()) store.saveAll(ranked, userId);
        return ranked;
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
