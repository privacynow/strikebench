package io.liftandshift.strikebench.api;
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

    @FunctionalInterface
    interface PlanEligibility {
        PlanController.PlanSymbolEligibility evaluate(String symbol, MarketLane lane, Quote quote,
                List<LocalDate> expirations, DataEvidence optionEvidence);
    }

    private final AppConfig cfg;
    private final Clock clock;
    private final MarketDataService market;
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
                       EventService events,
                       EvaluationService evaluations,
                       Function<Context, String> ownerId,
                       Function<Context, String> activeWorld,
                       Function<Context, AnalysisContext> analysisContext,
                       PlanEligibility planEligibility) {
        this.cfg = cfg;
        this.clock = clock;
        this.market = market;
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
                this::symbolResearch, this::expirations, this::chain, this::history, this::news, this::lookup,
                ctx -> ctx.json(new ApiResponses.StrategyCatalog<>(
                        Arrays.stream(StrategyFamily.values()).map(Enum::name).toList(),
                        StrategyCatalog.families(), StrategyCatalog.templates())),
                this::identifyStrategy,
                this::sizeExposure,
                ctx -> ctx.json(new ApiResponses.Evaluations<>(
                        evaluations.recent(ownerId.apply(ctx), 50))),
                ctx -> ctx.json(evaluations.calibrationReport(ownerId.apply(ctx))),
                this::resolveCalibration));
    }

    /** Pure exact-leg classification through the one server-owned strategy catalog. */
    private void identifyStrategy(Context ctx) {
        TradeOpenRequest body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, TradeOpenRequest.class));
        if (body.symbol() == null || body.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (body.qty() == null || body.qty() < 1) {
            throw new IllegalArgumentException("qty must be positive");
        }
        if (body.legs() == null || body.legs().isEmpty()) {
            throw new IllegalArgumentException("legs are required");
        }
        ctx.json(StrategyCatalog.identify(body.symbol().trim().toUpperCase(Locale.ROOT), body.qty(),
                body.legs().stream().map(io.liftandshift.strikebench.recommend.LegView::toLeg).toList()));
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
        Optional<Quote> quote = market.quote(symbol, world);
        if (quote.isEmpty()) {
            ctx.attribute("apiErrorWritten", true);
            ctx.status(404).json(new ApiResponses.ErrorBody("unknown_symbol", "No data for " + symbol));
            return;
        }
        Quote current = quote.get();
        AnalysisContext context = analysisContext.apply(ctx);
        MarketLane lane = MarketLane.of(world, cfg.fixturesOnly(), context);
        MarketLane requiredEvidence = lane == MarketLane.SCENARIO ? MarketLane.OBSERVED : lane;
        if (!current.evidence().usableIn(requiredEvidence)) {
            ctx.attribute("apiErrorWritten", true);
            ctx.status(409).json(new ApiResponses.ErrorBody("market_lane_mismatch",
                    "The " + lane + " workflow cannot use " + current.evidence().provenance()
                            + " quote data from " + current.evidence().source()));
            return;
        }
        LocalDate today = market.simInstant(worldParam(world))
                .map(instant -> LocalDate.ofInstant(instant, MarketHours.EASTERN))
                .orElseGet(() -> LocalDate.now(clock));

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
                    market.quote(benchmark, world)
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
            inputs.put("quote", current.evidence());
            inputs.put("history", candles.isEmpty()
                    ? DataEvidence.missing("daily history") : candles.evidence());
            if (current.optionable()) inputs.put("options", option.evidence());
            var evidence = new ApiResponses.EvidenceSummary<>(
                    DataEvidence.aggregate(inputs.values()), inputs);
            MarketLane planLane = MarketLane.of(world, cfg.fixturesOnly());
            var eligibility = planEligibility.evaluate(
                    symbol, planLane, current, option.expirations(), option.evidence());
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
            ctx.json(new ApiResponses.ResearchDetail<>(symbol, current, current.mark(),
                    current.usesPreviousCloseFallback(), lane.name(), current.optionable(), option.atmIv(),
                    volatility.ivRankPct() != null, volatility.ivRankPct(), volatility.ivPercentilePct(),
                    volatility.historyDays(), io.liftandshift.strikebench.eval.VolatilityProfiler.MIN_HISTORY,
                    volatility.source(), earnings,
                    events.unavailableForContext(symbol, EventService.EventType.EX_DIVIDEND,
                            "no keyless ex-dividend source — connect a licensed calendar for confirmed dates"),
                    realizedVol30, candles.candles().size(), HistoricalVol.MIN_OBSERVATIONS,
                    demoHistory, candles.barBasis(), candles.priceBasis(), evidence,
                    option.expirations().stream().map(LocalDate::toString).toList(),
                    eligibility.eligible(), eligibility.detail(), benchmarkFuture.get(),
                    current.markFreshness().name(), today.toString(), regime));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new RuntimeException(cause == null ? e : cause);
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
        java.time.Instant now = market.simInstant(worldParam(world)).orElse(clock.instant());
        return activeExpirations(market.expirations(symbol, world), now);
    }

    private void expirations(Context ctx) {
        String symbol = symbol(ctx);
        String world = activeWorld.apply(ctx);
        java.time.Instant now = market.simInstant(worldParam(world)).orElse(clock.instant());
        ctx.json(new ApiResponses.Expirations<>(symbol,
                LocalDate.ofInstant(now, MarketHours.EASTERN).toString(),
                activeExpirations(market.expirations(symbol, world), now).stream()
                        .map(LocalDate::toString).toList()));
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
        java.time.Instant now = market.simInstant(worldParam(world)).orElse(clock.instant());
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
        LocalDate today = market.simInstant(worldParam(world))
                .map(i -> LocalDate.ofInstant(i, MarketHours.EASTERN))
                .orElseGet(() -> LocalDate.now(clock));
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
                CandleCoverage.assess(series.candles(), requestedFrom, today)));
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
        return ctx.pathParam("symbol").trim().toUpperCase(Locale.ROOT);
    }

}
