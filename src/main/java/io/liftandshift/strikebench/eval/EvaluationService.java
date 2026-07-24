package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.EventService;
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
    private final EventService events;
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
        this(market, db, clock, new EventService(market, clock));
    }

    /** Uses the platform's one canonical event calendar; production injects the shared instance. */
    public EvaluationService(MarketDataService market, Db db, Clock clock, EventService events) {
        this.market = market;
        this.db = db;
        this.clock = clock;
        this.events = java.util.Objects.requireNonNull(events, "events");
        this.store = new EvaluationStore(db);
        this.calibration = new CalibrationService(db, clock);
    }

    /** The canonical calendar shared with Research, trade guardrails, alerts, and Scout. */
    public EventService eventCalendar() { return events; }

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

    /**
     * THE per-symbol ranking primitive: evaluate the candidates, then collapse to the single best
     * package per family, ordered by the canonical decision score. Every ranked surface — Scout,
     * Decision, the Portfolio scan — funnels through this ONE call so the SAME symbol yields the
     * SAME best idea everywhere, instead of each orchestrator re-spelling evaluate + best-per-family
     * with its own (divergent) selection rule.
     */
    public List<StrategyEvaluation> evaluateBestPerFamily(String symbol, String intent, String thesis,
            String horizon, String riskMode, List<Candidate> candidates, long buyingPowerCents,
            io.liftandshift.strikebench.db.AnalysisContext actx, String worldId,
            PortfolioExposureContext portfolioExposure) {
        return evaluateBestPerFamily(symbol, intent, thesis, horizon, riskMode, candidates,
                buyingPowerCents, actx, worldId, portfolioExposure, null);
    }

    /** Best-per-family ranking carrying the DECLARED assignment preference (objective lens). */
    public List<StrategyEvaluation> evaluateBestPerFamily(String symbol, String intent, String thesis,
            String horizon, String riskMode, List<Candidate> candidates, long buyingPowerCents,
            io.liftandshift.strikebench.db.AnalysisContext actx, String worldId,
            PortfolioExposureContext portfolioExposure, String assignmentPreference) {
        return StrategyEvaluator.bestPackagePerFamily(
                rank(symbol, intent, thesis, horizon, riskMode, candidates, buyingPowerCents,
                        actx, worldId, portfolioExposure, assignmentPreference));
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
        // Expiry is financial context, not presentation metadata. A field that retains several
        // expirations cannot price/rank every package with the earliest contract's DTE, ATM IV,
        // rate and event window. Reuse contexts only when both edges of the package's lifetime
        // match (single-expiry packages naturally share one key), then rank the exact evaluations.
        record ContextKey(LocalDate front, LocalDate last) {}
        java.util.Map<ContextKey, EvalContext> contexts = new java.util.HashMap<>();
        List<StrategyEvaluation> evaluated = new ArrayList<>(candidates.size());
        StrategySpec competition = new StrategySpec(symbol, null, intent, horizon, thesis, riskMode,
                "decision");
        for (Candidate candidate : candidates) {
            ContextKey key = new ContextKey(frontExpiration(List.of(candidate)),
                    lastExpiration(List.of(candidate)));
            EvalContext ctx = contexts.computeIfAbsent(key, ignored -> buildContext(symbol,
                    List.of(candidate), buyingPowerCents, actx, worldId, portfolioExposure, declared));
            evaluated.add(evaluator.evaluateAndRank(List.of(candidate), competition, ctx).getFirst());
        }
        evaluated.sort(StrategyEvaluator.RANKING);
        return List.copyOf(evaluated);
    }

    private static Integer horizonSessions(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return io.liftandshift.strikebench.model.Horizon.tradingSessions(value);
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
        Instant laneNow = market.laneNow(worldId, clock);
        LocalDate today = LocalDate.ofInstant(laneNow, MarketHours.EASTERN);
        long underlyingCents = market.quote(symbol, worldId)
                .map(q -> q.mark() == null ? 0L : Money.toCents(q.mark())).orElse(0L);

        LocalDate frontExp = frontExpiration(candidates);
        int dte = frontExp != null ? Math.max(0, (int) ChronoUnit.DAYS.between(today, frontExp)) : 30;

        Double atmIv = atmIv(symbol, underlyingCents, frontExp, worldId);
        // One lane-specific history artifact owns realized volatility, regime framing,
        // history-fit closes, and their provenance. Re-reading separate 60/126-day series made
        // those consumers vulnerable to cache/provider changes and discarded the evidence that
        // distinguishes observed bars from synthetic/scenario bars.
        CandleSeries historySeries = market.candleSeries(symbol, today.minusDays(126), today,
                worldId, actx);
        Double realizedVol = realizedVol30(historySeries);
        boolean generatedHistoryLane = worldId != null || (actx != null && actx.synthetic());
        List<Double> ivHistory = generatedHistoryLane ? List.of() : ivHistory(symbol);
        // Neither a simulated world nor a synthetic scenario dataset may borrow observed IV rank.
        boolean open = worldId != null || MarketHours.isRegularSession(laneNow);

        var rate = market.riskFreeRateQuote(Math.max(1, dte), worldId);

        // Regime is a framing lens over the SAME lane's history: vol profile from this
        // context's own inputs, trend/drawdown from this lane's candles (folded Phase 10.3).
        EvalContext preRegime = new EvalContext(symbol, underlyingCents, today, dte, atmIv, realizedVol,
                ivHistory, buyingPowerCents, open, feePerContractCents, feePerOrderCents,
                rate.annualRate(), rate.evidence(), portfolioExposure, declared, null, List.of(),
                historySeries.evidence());
        VolatilityProfile volProfile = new VolatilityProfiler().profile(preRegime);
        List<Candle> regimeCandles = historySeries.candles();
        LocalDate eventThrough = lastExpiration(candidates);
        if (eventThrough == null) eventThrough = today.plusDays(Math.max(1, dte));
        EventService.EarningsProximity event = eventProximity(symbol, eventThrough, worldId);
        RegimeSnapshot regime = RegimeProfiler.profile(regimeCandles, volProfile,
                event.available() ? event.likelyBefore() : null, event.note(),
                historyBasis(historySeries));
        List<Double> trailingCloses = regimeCandles == null ? List.of() : regimeCandles.stream()
                .map(candle -> candle.close() == null ? null : candle.close().doubleValue())
                .filter(java.util.Objects::nonNull).toList();
        return new EvalContext(symbol, underlyingCents, today, dte, atmIv, realizedVol, ivHistory,
                buyingPowerCents, open, feePerContractCents, feePerOrderCents, rate.annualRate(),
                rate.evidence(), portfolioExposure, declared, regime, trailingCloses,
                historySeries.evidence());
    }

    private static String historyBasis(CandleSeries series) {
        var provenance = series == null || series.evidence() == null
                ? io.liftandshift.strikebench.model.DataProvenance.MISSING
                : series.evidence().provenance();
        return switch (provenance) {
            case SIMULATED -> "this simulated world's sessions";
            case MODELED -> "this scenario dataset's sessions";
            case DEMO -> "this Demo market's sessions";
            case OBSERVED, BROKER -> "observed sessions";
            case MIXED, MISSING -> "sessions with unavailable provenance";
        };
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

    private static LocalDate lastExpiration(List<Candidate> candidates) {
        LocalDate max = null;
        for (Candidate c : candidates) {
            for (LegView l : c.legs()) {
                if (l.expiration() == null) continue;
                try {
                    LocalDate d = LocalDate.parse(l.expiration());
                    if (max == null || d.isAfter(max)) max = d;
                } catch (RuntimeException ignored) { /* skip unparseable */ }
            }
        }
        return max;
    }

    /**
     * Lane-aware access to the same canonical event evidence used by evaluation framing and Scout.
     * Generated worlds have no issuer calendar; they must not borrow an Observed filing estimate.
     */
    public EventService.EarningsProximity eventProximity(String symbol, LocalDate throughDate,
                                                          String worldId) {
        if (worldId != null) {
            return new EventService.EarningsProximity(false, false, null,
                    "earnings proximity unavailable in this simulated market — issuer events from "
                            + "Observed are not borrowed; treated as unknown");
        }
        try {
            return events.earningsProximity(symbol, throughDate);
        } catch (RuntimeException unavailable) {
            return new EventService.EarningsProximity(false, false, null,
                    "earnings proximity unavailable — the canonical SEC filing-cadence evidence "
                            + "could not be read; this is not a no-event claim");
        }
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

    private static Double realizedVol30(CandleSeries series) {
        if (series.isEmpty() || series.candles().size() < 20) return null;
        double v = HistoricalVol.annualized(series.candles(), 30);
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

    /**
     * Fraction of sessions opening with a gap of more than 2% from the prior close, over the
     * trailing ~90 sessions of the lane's own candles. Null when the history is too thin —
     * a missing gap record is honest, a zero would be a claim.
     */
    public Double gapFrequency(String symbol, String worldId) {
        LocalDate today = market.laneToday(worldId, clock);
        List<Candle> candles = worldId != null
                ? market.candleSeries(symbol, today.minusDays(180), today, worldId, null).candles()
                : market.candles(symbol, today.minusDays(180), today,
                        io.liftandshift.strikebench.db.AnalysisContext.OBSERVED);
        if (candles == null || candles.size() < 30) return null;
        int gaps = 0, measured = 0;
        for (int i = 1; i < candles.size(); i++) {
            var prev = candles.get(i - 1).close();
            var open = candles.get(i).open();
            if (prev == null || open == null || prev.signum() <= 0) continue;
            measured++;
            double gap = Math.abs(open.doubleValue() / prev.doubleValue() - 1.0);
            if (gap > 0.02) gaps++;
        }
        return measured < 30 ? null : (double) gaps / measured;
    }

    /** Stored option history changed or was wiped; no cached IV statistic may survive it. */
    public void invalidateHistoricalData() {
        ivHistoryCache.invalidateAll();
    }
}
