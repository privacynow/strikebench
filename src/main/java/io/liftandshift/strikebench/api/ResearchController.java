package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.model.Symbol;
import static io.liftandshift.strikebench.market.MarketLane.worldParam;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.market.CandleCoverage;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketDataEngine;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.market.MarketLane;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.DataProvenance;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.pricing.HistoricalVol;
import io.liftandshift.strikebench.recommend.NewsSentimentScorer;
import io.liftandshift.strikebench.research.NotebookService;
import io.liftandshift.strikebench.research.ResearchQuestionEngine;
import io.liftandshift.strikebench.strategy.ExposureSizer;
import io.liftandshift.strikebench.strategy.StrategyCatalog;
import io.liftandshift.strikebench.strategy.StrategyFamily;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** HTTP controller for Research data, notebooks, studies, and calibration records. */
final class ResearchController {
    record NoteRequest(String title, String body, String tags) {}
    record ResolveRequest(String recommendationId, String status, Long pnlCents) {}

    /**
     * The backend-owned expected-move receipt (B4): the risk-neutral 1σ range for one expiry, with
     * its anchor price + freshness so the desk HIDES the cone (available=false) rather than falling
     * back to any client math when inputs are missing/stale. It is never a predicted path.
     */
    record ExpectedMove(String symbol, boolean available, String reason,
                        Double atmIv, String expiration, Integer horizonSessions, Integer expirationCalendarDays,
                        Double p16, Double p50, Double p84,
                        Double p16MovePct, Double p84MovePct, String basis,
                        Double anchorSpot, String anchorSource, String anchorFreshness, String asOf) {
        static ExpectedMove unavailable(String symbol, String reason) {
            return new ExpectedMove(symbol, false, reason, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null);
        }
    }

    @FunctionalInterface
    interface PlanEligibility {
        PlanController.PlanSymbolEligibility evaluate(String symbol, MarketLane lane, Quote quote,
                List<LocalDate> expirations, DataEvidence optionEvidence);
    }

    private final AppConfig cfg;
    private final Clock clock;
    private final MarketDataService market;
    private final MarketDataEngine currentQuotes;
    private final EventService events;
    private final EvaluationService evaluations;
    private final ResearchQuestionEngine questions;
    private final NotebookService notes;
    private final ExposureSizer exposure;
    private final Function<Context, String> ownerId;
    private final Function<Context, String> activeWorld;
    private final Function<Context, AnalysisContext> analysisContext;
    private final PlanEligibility planEligibility;

    ResearchController(AppConfig cfg, Db db, Clock clock, MarketDataService market,
                       MarketDataEngine currentQuotes,
                       EventService events,
                       EvaluationService evaluations,
                       Function<Context, String> ownerId,
                       Function<Context, String> activeWorld,
                       Function<Context, AnalysisContext> analysisContext,
                       PlanEligibility planEligibility) {
        this.cfg = cfg;
        this.clock = clock;
        this.market = market;
        this.currentQuotes = java.util.Objects.requireNonNull(currentQuotes, "current quote authority");
        this.events = java.util.Objects.requireNonNull(events, "events");
        this.evaluations = evaluations;
        this.questions = new ResearchQuestionEngine(market, clock);
        this.notes = new NotebookService(db, clock);
        this.exposure = new ExposureSizer(market);
        this.ownerId = ownerId;
        this.activeWorld = activeWorld;
        this.analysisContext = analysisContext;
        this.planEligibility = planEligibility;
    }

    void register(JavalinConfig config) {
        ResearchRoutes.register(config, new ResearchRoutes.Handlers(
                ctx -> ctx.json(new ApiResponses.Questions<>(questions.catalog())),
                this::runEventStudy,
                this::createNote, this::listNotes, this::getNote, this::updateNote, this::deleteNote,
                this::symbolResearch, this::expirations, this::chain, this::expectedMove, this::history, this::news, this::lookup,
                ctx -> ctx.json(new ApiResponses.StrategyCatalog<>(
                        Arrays.stream(StrategyFamily.values()).map(Enum::name).toList(),
                        StrategyCatalog.families(), StrategyCatalog.templates())),
                this::sizeExposure,
                ctx -> ctx.json(new ApiResponses.Evaluations<>(
                        evaluations.recent(ownerId.apply(ctx), 50))),
                ctx -> ctx.json(evaluations.calibrationReport(ownerId.apply(ctx))),
                this::resolveCalibration));
    }

    private void runEventStudy(Context ctx) {
        var request = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, ResearchQuestionEngine.RunRequest.class));
        ctx.json(questions.run(request, analysisContext.apply(ctx), worldParam(activeWorld.apply(ctx))));
    }

    private void createNote(Context ctx) {
        NoteRequest body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, NoteRequest.class));
        ctx.json(notes.create(ownerId.apply(ctx), body.title(), body.body(), body.tags()));
    }

    private void listNotes(Context ctx) {
        ctx.json(new ApiResponses.Notes<>(notes.list(ownerId.apply(ctx))));
    }

    private void getNote(Context ctx) {
        ctx.json(notes.get(ownerId.apply(ctx), ctx.pathParam("id")));
    }

    private void updateNote(Context ctx) {
        NoteRequest body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, NoteRequest.class));
        ctx.json(notes.update(ownerId.apply(ctx), ctx.pathParam("id"),
                body.title(), body.body(), body.tags()));
    }

    private void deleteNote(Context ctx) {
        notes.delete(ownerId.apply(ctx), ctx.pathParam("id"));
        ctx.json(new ApiResponses.Ok(true));
    }

    private void symbolResearch(Context ctx) {
        String symbol = symbol(ctx);
        String world = activeWorld.apply(ctx);
        AnalysisContext context = analysisContext.apply(ctx);
        MarketLane lane = MarketLane.of(world, cfg.fixturesOnly(), context);
        MarketLane requiredEvidence = lane == MarketLane.SCENARIO ? MarketLane.OBSERVED : lane;
        LocalDate today = market.laneToday(worldParam(world), clock);
        // #10-backend: a missing or lane-mismatched quote no longer 404/409s the WHOLE bundle. The
        // quote is one input among several — mark it unavailable+reason and keep computing history,
        // options, benchmarks and regime, so each data slot reports its own state independently.
        Quote current = currentQuotes.currentQuote(symbol, world).orElse(null);
        String quoteUnavailableReason = null;
        if (current == null) {
            quoteUnavailableReason = "No " + lane.name().toLowerCase(Locale.ROOT)
                    + "-lane quote is available for " + symbol + " right now.";
        } else if (!current.evidence().usableIn(requiredEvidence)) {
            quoteUnavailableReason = "The " + lane + " workflow cannot use " + current.evidence().provenance()
                    + " quote data from " + current.evidence().source();
            current = null; // present but unusable in this lane — treat the quote slot as unavailable
        }

        record IvExp(List<LocalDate> expirations, Double atmIv, DataEvidence evidence) {}
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var optionFuture = executor.submit(() -> {
                List<LocalDate> expirations = activeExpirationsFor(symbol, world);
                OptionChain chain = expirations.isEmpty() ? null
                        : market.chain(symbol, expirations.getFirst(), world).orElse(null);
                boolean allowed = chain != null && chain.evidence().usableIn(requiredEvidence);
                Double iv = allowed ? atmIv(chain).orElse(null) : null;
                return new IvExp(expirations, iv, allowed ? chain.evidence()
                        : DataEvidence.missing("option chain"));
            });
            var candlesFuture = executor.submit(() -> market.candleSeries(
                    symbol, today.minusDays(120), today, world, context));
            var benchmarkFuture = executor.submit(() -> {
                List<ApiResponses.Benchmark<BigDecimal, DataEvidence>> benchmarks = new ArrayList<>();
                for (String benchmark : List.of("SPY", "QQQ")) {
                    if (benchmark.equals(symbol)) continue;
                    currentQuotes.currentQuote(benchmark, world)
                            .filter(value -> value.evidence().usableIn(requiredEvidence))
                            .ifPresent(value -> benchmarks.add(new ApiResponses.Benchmark<>(
                                    value.symbol(), value.mark(), value.markFreshness().name(),
                                    value.evidence())));
                }
                return benchmarks;
            });

            IvExp option = optionFuture.get();
            var candles = candlesFuture.get();
            double historicalVol = HistoricalVol.annualized(candles.candles(), 30);
            Double realizedVol30 = Double.isNaN(historicalVol) ? null : historicalVol;
            int volatilityHorizonDays = option.expirations().isEmpty() ? 30
                    : Math.max(1, (int) java.time.temporal.ChronoUnit.DAYS.between(
                            today, option.expirations().getFirst()));
            var volatility = evaluations.volatilitySnapshot(symbol, option.atmIv(), realizedVol30,
                    volatilityHorizonDays, worldParam(world));
            boolean demoHistory = candles.evidence().provenance() == DataProvenance.DEMO;

            EventService.EventEvidence earnings;
            if ("demo".equals(world)) {
                earnings = events.unavailableForContext(symbol,
                        "demo market — its companies and events are fabricated teaching data");
            } else if (!"observed".equals(world)) {
                earnings = events.unavailableForContext(symbol,
                        "simulated market — no earnings exist in this world");
            } else {
                earnings = events.earnings(symbol);
            }
            Map<String, DataEvidence> inputs = new LinkedHashMap<>();
            inputs.put("quote", current != null ? current.evidence() : DataEvidence.missing("quote"));
            inputs.put("history", candles.isEmpty()
                    ? DataEvidence.missing("daily history") : candles.evidence());
            // Include the option-surface evidence whenever we could not rule options out — i.e. the
            // quote is unavailable (optionable unknown) or the symbol is optionable.
            if (current == null || current.optionable()) inputs.put("options", option.evidence());
            var evidence = new ApiResponses.EvidenceSummary<>(
                    DataEvidence.aggregate(inputs.values()), inputs);
            PlanController.PlanSymbolEligibility eligibility;
            if (current == null) {
                eligibility = new PlanController.PlanSymbolEligibility(false,
                        symbol + " has no usable quote in the active market, so an options Plan "
                                + "cannot be built until the quote returns.");
            } else {
                MarketLane planLane = MarketLane.of(world, cfg.fixturesOnly());
                eligibility = planEligibility.evaluate(
                        symbol, planLane, current, option.expirations(), option.evidence());
            }
            EventService.EarningsProximity eventProximity;
            if ("observed".equals(world)) {
                eventProximity = events.earningsProximity(symbol,
                        today.plusDays(volatilityHorizonDays));
            } else {
                eventProximity = new EventService.EarningsProximity(false, false, null,
                        "earnings proximity unavailable in this "
                                + ("demo".equals(world) ? "demo" : "simulated")
                                + " market — Observed issuer events are not borrowed");
            }
            var volProfileForRegime = new io.liftandshift.strikebench.eval.VolatilityProfiler()
                    .profile(option.atmIv(), realizedVol30, List.of(), volatilityHorizonDays);
            var regime = ApiResponses.Regime.of(io.liftandshift.strikebench.eval.RegimeProfiler.profile(
                    candles.candles(),
                    new io.liftandshift.strikebench.eval.VolatilityProfile(
                            volProfileForRegime.atmIv(), volatility.ivRankPct(), volatility.ivPercentilePct(),
                            volProfileForRegime.realizedVol30(), volProfileForRegime.varianceRiskPremium(),
                            volProfileForRegime.expectedMovePct(), volatility.historyDays(),
                            volatility.source()),
                    eventProximity.available() ? eventProximity.likelyBefore() : null,
                    eventProximity.note(),
                    "demo".equals(world) ? "demo sessions (fabricated teaching data)"
                            : world != null && !"observed".equals(world) ? "this simulated world's sessions"
                            : "observed sessions"));
            // ONE price decision for this symbol: the same QuoteView the /api/quotes batch serves.
            // Research does not re-derive a display price, a day change or a basis of its own.
            ApiResponses.QuoteView quoteView = current == null
                    ? ApiResponses.QuoteView.unavailable(symbol, quoteUnavailableReason)
                    : ApiResponses.QuoteView.of(current, false);
            ctx.json(new ApiResponses.ResearchDetail<>(symbol, quoteView, lane.name(),
                    current != null && current.optionable(), option.atmIv(),
                    volatility.ivRankPct() != null, volatility.ivRankPct(), volatility.ivPercentilePct(),
                    volatility.historyDays(), io.liftandshift.strikebench.eval.VolatilityProfiler.MIN_HISTORY,
                    volatility.source(), earnings,
                    events.unavailableForContext(symbol, EventService.EventType.EX_DIVIDEND,
                            "no keyless ex-dividend source — connect a licensed calendar for confirmed dates"),
                    realizedVol30, candles.candles().size(), HistoricalVol.MIN_OBSERVATIONS,
                    demoHistory, candles.barBasis(), candles.priceBasis(), evidence,
                    option.expirations().stream().map(LocalDate::toString).toList(),
                    eligibility.eligible(), eligibility.detail(), benchmarkFuture.get(),
                    today.toString(), regime));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new RuntimeException(cause == null ? e : cause);
        }
    }

    /** B4: the backend expected-move receipt for one expiry (?expiry=YYYY-MM-DD, else the nearest listed). */
    private void expectedMove(Context ctx) {
        String symbol = symbol(ctx);
        String world = activeWorld.apply(ctx);
        LocalDate requestedExpiry = requestedExpiry(ctx.queryParam("expiry"));
        Optional<Quote> quote = currentQuotes.currentQuote(symbol, world);
        if (quote.isEmpty() || quote.get().mark() == null) {
            ctx.json(ExpectedMove.unavailable(symbol, "no authoritative quote")); return;
        }
        Quote current = quote.get();
        double spot = current.mark().doubleValue();
        java.time.Instant laneNow = market.laneNow(worldParam(world), clock);
        LocalDate today = LocalDate.ofInstant(laneNow, MarketHours.EASTERN);
        LocalDate expiry = requestedExpiry;
        if (expiry == null) {
            expiry = io.liftandshift.strikebench.market.OptionTime.selectListedExpiration(
                    activeExpirationsFor(symbol, world), laneNow, null).expiration();
        }
        if (expiry == null) { ctx.json(ExpectedMove.unavailable(symbol, "no listed expiry")); return; }
        OptionChain chain = market.chain(symbol, expiry, world).orElse(null);
        Double iv = chain == null ? null : atmIv(chain).orElse(null);
        if (iv == null) { ctx.json(ExpectedMove.unavailable(symbol, "no ATM implied volatility for " + expiry)); return; }
        var optionTime = io.liftandshift.strikebench.market.OptionTime.toExpiry(laneNow, expiry);
        if (!optionTime.hasModelTime()) {
            ctx.json(ExpectedMove.unavailable(symbol,
                    "listed expiry has no live option-model time: " + optionTime.basis()));
            return;
        }
        int calendarDays = Math.max(1, Math.toIntExact(optionTime.calendarDays()));
        double rate = market.riskFreeRateQuote(calendarDays, world).annualRate();
        var range = io.liftandshift.strikebench.sim.SimulationEngine.MarketImpliedRange
                .forListedExpiry(spot, iv, optionTime, rate);
        if (range == null) { ctx.json(ExpectedMove.unavailable(symbol, "insufficient inputs")); return; }
        ctx.json(new ExpectedMove(symbol, true, null, range.atmIv(), range.expiration(),
                range.horizonSessions(), range.expirationCalendarDays(), range.p16(), range.p50(), range.p84(),
                range.downMovePct(spot), range.upMovePct(spot),
                range.basis(), spot, current.source(), current.markFreshness().name(), today.toString()));
    }

    private static LocalDate requestedExpiry(String param) {
        if (param == null || param.isBlank()) return null;
        try {
            return LocalDate.parse(param.trim());
        } catch (java.time.format.DateTimeParseException malformed) {
            throw new io.javalin.http.BadRequestResponse(
                    "expiry must be a valid ISO date in YYYY-MM-DD form");
        }
    }

    private Optional<Double> atmIv(OptionChain chain) {
        BigDecimal spot = chain.underlyingPrice();
        return chain.calls().stream().filter(option -> option.iv() != null)
                .min(java.util.Comparator.comparingDouble(option ->
                        Math.abs(option.strike().doubleValue() - spot.doubleValue())))
                .map(OptionQuote::iv);
    }

    private List<LocalDate> activeExpirationsFor(String symbol, String world) {
        java.time.Instant now = market.laneNow(worldParam(world), clock);
        return activeExpirations(market.expirations(symbol, world), now);
    }

    private void expirations(Context ctx) {
        String symbol = symbol(ctx);
        String world = activeWorld.apply(ctx);
        java.time.Instant now = market.laneNow(worldParam(world), clock);
        LocalDate asOf = LocalDate.ofInstant(now, MarketHours.EASTERN);
        Integer horizonSessions = requestedHorizonSessions(ctx.queryParam("horizonSessions"));
        List<LocalDate> active = activeExpirations(market.expirations(symbol, world), now);
        var selected = io.liftandshift.strikebench.market.OptionTime
                .selectListedExpiration(active, now, horizonSessions);
        // §7.3: the distance to an expiration is a market-calendar fact, not a weekday count.
        // The browser used to walk Mon-Fri from asOfDate to decide which expiration the whole
        // Desk trades, so every market holiday shifted its choice away from the server's.
        ctx.json(new ApiResponses.Expirations<>(symbol, asOf.toString(),
                active.stream()
                        .map(date -> new ApiResponses.ExpirationDistance(date.toString(),
                                MarketHours.tradingDaysBetween(asOf, date),
                                (int) java.time.temporal.ChronoUnit.DAYS.between(asOf, date)))
                        .toList(),
                new ApiResponses.ExpirationSelection(
                        selected.expiration() == null ? null : selected.expiration().toString(),
                        selected.requestedHorizonSessions(),
                        selected.tradingSessions(),
                        selected.calendarDays(),
                        selected.basis())));
    }

    private static Integer requestedHorizonSessions(String raw) {
        if (raw == null || raw.isBlank()) return null;
        final int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException malformed) {
            throw new io.javalin.http.BadRequestResponse(
                    "horizonSessions must be an integer between 1 and 756");
        }
        if (value < 1 || value > 756) {
            throw new io.javalin.http.BadRequestResponse(
                    "horizonSessions must be between 1 and 756");
        }
        return value;
    }

    private void chain(Context ctx) {
        String symbol = symbol(ctx);
        String expiration = ctx.queryParam("expiration");
        if (expiration == null || expiration.isBlank()) {
            throw new IllegalArgumentException(
                    "expiration query parameter is required (YYYY-MM-DD)");
        }
        LocalDate date = LocalDate.parse(expiration.trim());
        String world = activeWorld.apply(ctx);
        java.time.Instant now = market.laneNow(worldParam(world), clock);
        if (MarketHours.contractDead(date, now)) {
            throw new IllegalArgumentException("expiration is no longer active: " + date);
        }
        Optional<OptionChain> result = market.chain(symbol, date, world);
        if (result.isEmpty()) {
            ctx.attribute("apiErrorWritten", true);
            ctx.status(404).json(new ApiResponses.ErrorBody(
                    "no_chain", "No option chain for " + symbol + " " + date));
            return;
        }
        ctx.json(result.get());
    }

    private void history(Context ctx) {
        String symbol = symbol(ctx);
        String requested = ctx.queryParam("range") == null
                ? "1y" : ctx.queryParam("range").toLowerCase(Locale.ROOT);
        String range = switch (requested) {
            case "1m", "3m", "6m", "ytd", "1y", "2y", "5y", "max" -> requested;
            default -> "1y";
        };
        String world = activeWorld.apply(ctx);
        LocalDate today = market.laneToday(worldParam(world), clock);
        int days = switch (range) {
            case "1m" -> 30;
            case "3m" -> 91;
            case "6m" -> 182;
            case "ytd" -> today.getDayOfYear();
            case "2y" -> 730;
            case "5y" -> 1826;
            case "max" -> 7300;
            default -> 365;
        };
        LocalDate requestedFrom = today.minusDays(days);
        var series = market.candleSeries(symbol, requestedFrom, today, world,
                analysisContext.apply(ctx));
        ctx.json(new ApiResponses.History<>(symbol, range, series.candles(), series.source(),
                series.freshness().name(), series.barBasis(), series.priceBasis(), series.evidence(),
                CandleCoverage.assess(series.candles(), requestedFrom, today),
                historyOverlays(series.candles())));
    }

    /** One trading month over a trading year — the horizon of the realized ±1σ price envelope. */
    private static final double ONE_MONTH_OF_YEAR = 21.0 / 252.0;

    /** RV(20) + SMA(20/50) over the SAME candles, one value per bar — the chart draws these instead
     *  of a second client-side estimator, so realized-vol and moving-average overlays trace to one
     *  backend source. The band is the realized one-month ±1σ envelope
     *  ({@code sma20 · exp(±rv20 · √(21/252))}), now server-computed off that SAME rv20/sma20 so the
     *  client plots the values instead of estimating them; null wherever either input is null. */
    private static ApiResponses.HistoryOverlays historyOverlays(
            List<io.liftandshift.strikebench.model.Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return new ApiResponses.HistoryOverlays(List.of(), List.of(), List.of(), List.of(), List.of());
        }
        double[] rv = HistoricalVol.rollingAnnualized(candles, 20);
        List<Double> rv20 = new ArrayList<>(candles.size());
        for (double v : rv) rv20.add(Double.isFinite(v) ? v : null);
        List<Double> sma20 = sma(candles, 20);
        double sigmaMonth = Math.sqrt(ONE_MONTH_OF_YEAR);
        List<Double> bandUp = new ArrayList<>(candles.size());
        List<Double> bandDn = new ArrayList<>(candles.size());
        for (int i = 0; i < candles.size(); i++) {
            Double m = sma20.get(i);
            Double v = rv20.get(i);
            if (m == null || v == null) {
                bandUp.add(null);
                bandDn.add(null);
            } else {
                bandUp.add(m * Math.exp(v * sigmaMonth));
                bandDn.add(m * Math.exp(-v * sigmaMonth));
            }
        }
        return new ApiResponses.HistoryOverlays(rv20, sma20, sma(candles, 50), bandUp, bandDn);
    }

    private static List<Double> sma(List<io.liftandshift.strikebench.model.Candle> candles, int p) {
        List<Double> out = new ArrayList<>(candles.size());
        double sum = 0;
        for (int i = 0; i < candles.size(); i++) {
            sum += candles.get(i).close().doubleValue();
            if (i >= p) sum -= candles.get(i - p).close().doubleValue();
            out.add(i >= p - 1 ? sum / p : null);
        }
        return out;
    }

    private void news(Context ctx) {
        String symbol = symbol(ctx);
        String world = activeWorld.apply(ctx);
        if (io.liftandshift.strikebench.market.MarketLane.isSimulatedWorld(world)) {
            var unavailable = NewsSentimentScorer.unavailable(List.of(),
                    NewsSentimentScorer.UNAVAILABLE_BASIS,
                    "Simulated market — there is no issuer-news feed in this world; Observed headlines are not borrowed.");
            ctx.json(researchNews(symbol, unavailable, "UNAVAILABLE", unavailable.aggregate().note()));
            return;
        }
        List<io.liftandshift.strikebench.model.NewsItem> raw = market.news(symbol, world);
        NewsSentimentScorer.Result sentiment = "demo".equals(world)
                ? NewsSentimentScorer.unavailable(raw, NewsSentimentScorer.DEMO_BASIS,
                        "Demo headlines are fabricated teaching catalysts; they are never scored as news evidence.")
                : NewsSentimentScorer.score(raw);
        String evidence = "demo".equals(world) ? "DEMO_FABRICATED"
                : sentiment.aggregate().available() ? "OBSERVED" : "UNAVAILABLE";
        ctx.json(researchNews(symbol, sentiment, evidence, sentiment.aggregate().note()));
    }

    private static ApiResponses.ResearchNews<List<NewsSentimentScorer.HeadlineSentiment>,
            NewsSentimentScorer.Aggregate> researchNews(String symbol,
                                                        NewsSentimentScorer.Result sentiment,
                                                        String evidence, String note) {
        List<NewsSentimentScorer.HeadlineSentiment> eventRisk = sentiment.headlines().stream()
                .filter(NewsSentimentScorer.HeadlineSentiment::eventRisk).toList();
        return new ApiResponses.ResearchNews<>(symbol, NewsSentimentScorer.VERSION,
                sentiment.headlines(), sentiment.aggregate(), eventRisk, evidence, note);
    }

    private void lookup(Context ctx) {
        String query = ctx.queryParam("q");
        ctx.json(new ApiResponses.Matches<>(query == null
                ? List.of() : market.lookup(query, activeWorld.apply(ctx))));
    }

    private void sizeExposure(Context ctx) {
        var request = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, ExposureSizer.Request.class));
        ctx.json(exposure.size(request, worldParam(activeWorld.apply(ctx))));
    }

    private void resolveCalibration(Context ctx) {
        ResolveRequest request = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, ResolveRequest.class));
        if (request.recommendationId() == null || request.recommendationId().isBlank()) {
            throw new IllegalArgumentException("recommendationId is required");
        }
        evaluations.resolveOutcome(request.recommendationId(), request.status(), request.pnlCents());
        ctx.json(new ApiResponses.Ok(true));
    }

    static List<LocalDate> activeExpirations(List<LocalDate> expirations, java.time.Instant now) {
        if (expirations == null || expirations.isEmpty()) return List.of();
        return expirations.stream()
                .filter(exp -> !MarketHours.contractDead(exp, now))
                .sorted().toList();
    }

    private static String symbol(Context ctx) {
        return Symbol.normalize(ctx.pathParam("symbol"));
    }

}
