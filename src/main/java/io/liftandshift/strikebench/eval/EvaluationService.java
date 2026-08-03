package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.market.MarketMode;
import io.liftandshift.strikebench.market.OptionTime;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.pricing.HistoricalVol;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.HoldingsEvidence;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.Money;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

    /** Complete inputs for ranking one exact candidate field. Nothing is supplied by an overload. */
    public record RankingRequest(
            String symbol,
            String intent,
            String thesis,
            String horizon,
            String riskMode,
            List<Candidate> candidates,
            long buyingPowerCents,
            AnalysisContext analysisContext,
            String worldId,
            PortfolioExposureContext portfolioExposure,
            String assignmentPreference,
            Long lossAppetiteCents) {
        public RankingRequest {
            candidates = List.copyOf(java.util.Objects.requireNonNull(candidates, "candidates"));
            analysisContext = java.util.Objects.requireNonNull(analysisContext, "analysisContext");
            worldId = io.liftandshift.strikebench.market.MarketMode.worldParam(worldId);
        }
    }

    /** Complete inputs for assessing one already-priced package. */
    public record ExactAssessmentRequest(
            String symbol,
            Candidate candidate,
            long buyingPowerCents,
            AnalysisContext analysisContext,
            String worldId,
            boolean mechanicallyEligible,
            List<String> mechanicalFailures,
            Long roundTripFeesCents,
            PortfolioExposureContext portfolioExposure,
            DeclaredObjective declaredObjective) {
        public ExactAssessmentRequest {
            candidate = java.util.Objects.requireNonNull(candidate, "candidate");
            analysisContext = java.util.Objects.requireNonNull(analysisContext, "analysisContext");
            worldId = io.liftandshift.strikebench.market.MarketMode.worldParam(worldId);
            mechanicalFailures = List.copyOf(java.util.Objects.requireNonNull(
                    mechanicalFailures, "mechanicalFailures"));
        }
    }

    /** Explicit owner and market identity for persisting an immutable ranked field. */
    public record PersistenceRequest(List<StrategyEvaluation> evaluations, String userId,
                                     String worldId) {
        public PersistenceRequest {
            evaluations = List.copyOf(java.util.Objects.requireNonNull(evaluations, "evaluations"));
            userId = java.util.Objects.requireNonNull(userId, "userId");
            worldId = io.liftandshift.strikebench.market.MarketMode.worldParam(worldId);
        }
    }

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

    /** Uses the platform's one normalized event calendar; production injects the shared instance. */
    public EvaluationService(MarketDataService market, Db db, Clock clock, EventService events) {
        this.market = market;
        this.db = db;
        this.clock = clock;
        this.events = java.util.Objects.requireNonNull(events, "events");
        this.store = new EvaluationStore(db);
        this.calibration = new CalibrationService(db, clock);
    }

    /** The normalized calendar shared with Research, trade guardrails, alerts, and Scout. */
    public EventService eventCalendar() { return events; }

    /** Exact mode clock for held-position lifecycle composition, including Practice simulations. */
    public OptionTime.Measure optionTime(List<io.liftandshift.strikebench.model.Leg> legs,
                                         String worldId) {
        return OptionTime.nearest(legs, market.marketNow(worldId, clock));
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

    /**
     * Reads the immutable share-context result attached to a recommendation. Placement uses this
     * only as an eligibility gate; it never reconstructs or reprices the candidate.
     */
    public java.util.Optional<HoldingsEvidence> holdingsEvidence(
            String recommendationId, String userId, String worldId) {
        return store.result(recommendationId, userId, worldId).flatMap(json -> {
            var result = Json.parse(json);
            var candidate = result.path("candidate");
            var node = candidate.path("holdingsEvidence");
            if (node.isMissingNode() || node.isNull()) {
                return java.util.Optional.empty();
            }
            try {
                return java.util.Optional.of(Json.MAPPER.treeToValue(node, HoldingsEvidence.class));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(
                        "The stored recommendation has an invalid holdings-evidence result", e);
            }
        });
    }

    /** Auto-resolves any recommendation tied to a closed trade (best-effort). */
    public void resolveByTrade(String tradeId, String status, Long pnlCents) {
        calibration.resolveByTrade(tradeId, status, pnlCents);
    }

    public java.util.Map<String, Object> calibrationReport(String userId) {
        return calibration.report(userId);
    }

    /** Research uses the same IV-rank history and thresholds as candidate evaluation. A generated
     * world never borrows observed IV history; its missing rank is an honest mode property. */
    public VolatilityProfile volatilitySnapshot(String symbol, Double atmIv, Double realizedVol30,
                                                 OptionTime.Measure timeToExpiry, String worldId) {
        List<Double> history = MarketMode.isObservedWorld(worldId) ? ivHistory(symbol) : List.of();
        return new VolatilityProfiler().profile(new VolatilityProfiler.Input(
                atmIv, realizedVol30, history, timeToExpiry));
    }

    /** Ranks every candidate using the market and declarations named in the request. */
    public List<StrategyEvaluation> evaluate(RankingRequest request) {
        return rank(request);
    }

    /**
     * THE per-symbol ranking primitive: evaluate the candidates, then collapse to the single best
     * package per family, ordered by the normalized decision score. Every ranked surface — Scout,
     * Decision, the Portfolio scan — funnels through this ONE call so the SAME symbol yields the
     * SAME best idea everywhere, instead of each orchestrator re-spelling evaluate + best-per-family
     * with its own (divergent) selection rule.
     */
    public List<StrategyEvaluation> evaluateBestPerFamily(RankingRequest request) {
        return StrategyEvaluator.bestPackagePerFamily(rank(request));
    }

    /** Reuses the complete candidate pipeline for the exact package on Ticket Review. */
    public StrategyEvaluation assessExact(ExactAssessmentRequest request) {
        EvalContext ctx = buildContext(request.symbol(), List.of(request.candidate()),
                request.buyingPowerCents(), request.analysisContext(), request.worldId(),
                request.portfolioExposure(), request.declaredObjective(), null);
        StrategySpec spec = new StrategySpec(request.symbol(), request.candidate().strategy(),
                request.candidate().intent(),
                ctx.calendarDaysToExpiry() + "d", null, null, "exact-position");
        return evaluator.assessExact(request.candidate(), spec, ctx, request.mechanicallyEligible(),
                request.mechanicalFailures(), request.roundTripFeesCents());
    }

    /** Persists an already-ranked field under the explicitly named owner and market. */
    public void persist(PersistenceRequest request) {
        if (!request.evaluations().isEmpty()) {
            store.saveAll(new EvaluationStore.SaveRequest(request.evaluations(), request.userId(),
                    request.worldId()));
        }
    }

    /**
     * The exact, immutable evaluation a scan surfaced — reloaded from its own persisted result so
     * a Scout row opens the package it showed instead of a freshly recomputed field. The owner and
     * the market mode must both match; nothing is re-priced, re-ranked, or re-derived here.
     */
    public java.util.Optional<StrategyEvaluation> persisted(String evaluationId, String userId,
                                                            String worldId) {
        return store.result(evaluationId, userId, worldId).map(result -> {
            StrategyEvaluation evaluation;
            try {
                evaluation = io.liftandshift.strikebench.util.Json.read(result, StrategyEvaluation.class);
            } catch (RuntimeException unreadable) {
                // A result that cannot be rebuilt is missing evidence, never a licence to
                // substitute a freshly computed package under the same row's name (§3.2).
                throw new io.liftandshift.strikebench.util.DataUnavailableException(
                        "The stored evaluation " + evaluationId + " could not be rebuilt from its own"
                                + " result, so its exact package is unavailable.", unreadable);
            }
            if (evaluation == null || evaluation.candidate() == null || evaluation.spec() == null) {
                throw new io.liftandshift.strikebench.util.DataUnavailableException(
                        "The stored evaluation " + evaluationId
                                + " no longer carries its exact package and the brief it was evaluated under.");
            }
            return evaluation;
        });
    }

    private List<StrategyEvaluation> rank(RankingRequest request) {
        // The ranked field is evaluated AGAINST the declared view: the coherence diagnostic
        // (Program ONE folded Phase 9) compares what the user said with each structure's stance.
        DeclaredObjective declared = (request.intent() == null && request.thesis() == null
                && request.horizon() == null && request.assignmentPreference() == null) ? null
                : new DeclaredObjective(request.intent(), request.thesis(),
                        horizonSessions(request.horizon()), request.assignmentPreference(),
                        "this Plan's declared view");
        // Expiry is financial context, not presentation metadata. A field that retains several
        // expirations cannot price/rank every package with the earliest contract's DTE, ATM IV,
        // rate and event window. Reuse contexts only when both edges of the package's lifetime
        // match (single-expiry packages naturally share one key), then rank the exact evaluations.
        record ContextKey(LocalDate front, LocalDate last) {}
        java.util.Map<ContextKey, EvalContext> contexts = new java.util.HashMap<>();
        List<StrategyEvaluation> evaluated = new ArrayList<>(request.candidates().size());
        StrategySpec competition = new StrategySpec(request.symbol(), null, request.intent(),
                request.horizon(), request.thesis(), request.riskMode(), "decision");
        for (Candidate candidate : request.candidates()) {
            ContextKey key = new ContextKey(frontExpiration(List.of(candidate)),
                    lastExpiration(List.of(candidate)));
            EvalContext ctx = contexts.computeIfAbsent(key, ignored -> buildContext(request.symbol(),
                    List.of(candidate), request.buyingPowerCents(), request.analysisContext(),
                    request.worldId(), request.portfolioExposure(), declared,
                    request.lossAppetiteCents()));
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
                                     AnalysisContext actx, String worldId,
                                     PortfolioExposureContext portfolioExposure,
                                     DeclaredObjective declared, Long lossAppetiteCents) {
        // ONE MARKET: spot, DTE clock, ATM IV and realized vol all come from the market that priced
        // the candidates — a sim world's numbers never blend with observed ones (review P0).
        Instant marketNow = market.marketNow(worldId, clock);
        LocalDate today = LocalDate.ofInstant(marketNow, MarketHours.EASTERN);
        var quote = market.quote(symbol, worldId).orElseThrow(() ->
                new io.liftandshift.strikebench.util.DataUnavailableException(
                        "Evaluation is unavailable because " + symbol
                                + " has no quote in the selected market."));
        if (quote.mark() == null) {
            throw new io.liftandshift.strikebench.util.DataUnavailableException(
                    "Evaluation is unavailable because " + symbol + " has no usable underlying mark"
                            + " (basis " + quote.markBasis() + ", source " + quote.source() + ").");
        }
        long underlyingCents = Money.toCents(quote.mark());

        LocalDate frontExp = frontExpiration(candidates);
        if (frontExp == null) {
            throw new io.liftandshift.strikebench.util.DataUnavailableException(
                    "Evaluation is unavailable because the exact option package has no parseable"
                            + " expiration. Stock-only orders use the share-order analysis owner.");
        }
        OptionTime.Measure timeToExpiry = OptionTime.toExpiry(marketNow, frontExp);
        if (timeToExpiry.state() == OptionTime.State.EXPIRED) {
            throw new io.liftandshift.strikebench.util.DataUnavailableException(
                    "Evaluation is unavailable because the exact option package expired at the"
                            + " selected market mode's final bell (" + frontExp + ").");
        }

        Double atmIv = atmIv(symbol, underlyingCents, frontExp, worldId);
        // One mode-specific history artifact owns realized volatility, regime framing,
        // history-fit closes, and their provenance. Re-reading separate 60/126-day series made
        // those consumers vulnerable to cache/provider changes and discarded the evidence that
        // distinguishes observed bars from synthetic/scenario bars.
        CandleSeries historySeries = market.candleSeries(symbol, today.minusDays(126), today,
                worldId, actx);
        Double realizedVol = realizedVol30(historySeries);
        boolean generatedHistoryMode = !MarketMode.isObservedWorld(worldId)
                || (actx != null && actx.synthetic());
        List<Double> ivHistory = generatedHistoryMode ? List.of() : ivHistory(symbol);
        // Neither a simulated world nor a synthetic scenario dataset may borrow observed IV rank.
        boolean open = !MarketMode.isObservedWorld(worldId) || MarketHours.isRegularSession(marketNow);

        var rate = market.riskFreeRateQuote(
                Math.max(1, Math.toIntExact(timeToExpiry.calendarDays())), worldId);

        // Regime is a framing lens over the SAME mode's history: vol profile from this
        // context's own inputs, trend/drawdown from this mode's candles (folded Phase 10.3).
        EvalContext preRegime = new EvalContext(symbol, underlyingCents, today, timeToExpiry,
                atmIv, realizedVol,
                ivHistory, buyingPowerCents, open, rate.annualRate(), rate.evidence(),
                portfolioExposure, declared, null, List.of(),
                historySeries.evidence(), null, lossAppetiteCents);
        VolatilityProfile volProfile = new VolatilityProfiler().profile(new VolatilityProfiler.Input(
                preRegime.atmIv(), preRegime.realizedVol30(), preRegime.ivHistory(),
                preRegime.timeToExpiry()));
        List<Candle> regimeCandles = historySeries.candles();
        LocalDate eventThrough = lastExpiration(candidates);
        if (eventThrough == null) eventThrough = frontExp;
        EventService.EarningsProximity event = eventProximity(symbol, eventThrough, worldId);
        RegimeSnapshot regime = RegimeProfiler.profile(regimeCandles, volProfile,
                event.available() ? event.likelyBefore() : null, event.note(),
                historyBasis(historySeries));
        List<Double> trailingCloses = regimeCandles == null ? List.of() : regimeCandles.stream()
                .map(candle -> candle.close() == null ? null : candle.close().doubleValue())
                .filter(java.util.Objects::nonNull).toList();
        return new EvalContext(symbol, underlyingCents, today, timeToExpiry, atmIv, realizedVol, ivHistory,
                buyingPowerCents, open, rate.annualRate(), rate.evidence(),
                portfolioExposure, declared, regime, trailingCloses,
                historySeries.evidence(), event, lossAppetiteCents);
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
        return expirationEdge(candidates, true);
    }

    private static LocalDate lastExpiration(List<Candidate> candidates) {
        return expirationEdge(candidates, false);
    }

    private static LocalDate expirationEdge(List<Candidate> candidates, boolean front) {
        LocalDate edge = null;
        for (Candidate c : candidates) {
            for (LegView l : c.legs()) {
                if ("STOCK".equalsIgnoreCase(l.type())) continue;
                if (l.expiration() == null || l.expiration().isBlank()) {
                    throw new io.liftandshift.strikebench.util.DataUnavailableException(
                            "Evaluation is unavailable because " + l.action() + " " + l.type()
                                    + " " + l.strike() + " has no expiration.");
                }
                try {
                    LocalDate d = LocalDate.parse(l.expiration());
                    if (edge == null || front && d.isBefore(edge) || !front && d.isAfter(edge)) {
                        edge = d;
                    }
                } catch (RuntimeException invalid) {
                    throw new io.liftandshift.strikebench.util.DataUnavailableException(
                            "Evaluation is unavailable because " + l.action() + " " + l.type()
                                    + " " + l.strike() + " has an invalid expiration '"
                                    + l.expiration() + "'.", invalid);
                }
            }
        }
        return edge;
    }

    /**
     * Mode-aware access to the same normalized event evidence used by evaluation framing and Scout.
     * Generated worlds have no issuer calendar; they must not borrow an Observed filing estimate.
     */
    public EventService.EarningsProximity eventProximity(String symbol, LocalDate throughDate,
                                                          String worldId) {
        if (!MarketMode.isObservedWorld(worldId)) {
            return new EventService.EarningsProximity(false, false, null,
                    "earnings proximity unavailable in this simulated market — issuer events from "
                            + "Observed are not borrowed; treated as unknown");
        }
        try {
            return events.earningsProximity(symbol, throughDate);
        } catch (RuntimeException unavailable) {
            return new EventService.EarningsProximity(false, false, null,
                    "earnings proximity unavailable — SEC filing cadence "
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
     * trailing ~90 sessions of the mode's own candles. Null when the history is too thin —
     * a missing gap record is honest, a zero would be a claim.
     */
    public Double gapFrequency(String symbol, String worldId) {
        LocalDate today = market.marketToday(worldId, clock);
        var series = market.candleSeries(symbol, today.minusDays(180), today, worldId,
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED);
        if (!series.hasFullOhlc()) return null;
        List<Candle> candles = series.candles();
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
