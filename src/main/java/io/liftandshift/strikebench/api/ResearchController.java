package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.market.CandleCoverage;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.research.NotebookService;
import io.liftandshift.strikebench.research.ResearchQuestionEngine;
import io.liftandshift.strikebench.strategy.ExposureSizer;
import io.liftandshift.strikebench.strategy.StrategyCatalog;
import io.liftandshift.strikebench.strategy.StrategyFamily;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/** HTTP controller for Research data, notebooks, studies, and calibration records. */
final class ResearchController {
    record NoteRequest(String title, String body, String tags) {}
    record ResolveRequest(String recommendationId, String status, Long pnlCents) {}

    private final Clock clock;
    private final MarketDataService market;
    private final EvaluationService evaluations;
    private final ResearchQuestionEngine questions;
    private final NotebookService notes;
    private final ExposureSizer exposure;
    private final Function<Context, String> ownerId;
    private final Function<Context, String> activeWorld;
    private final Function<Context, AnalysisContext> analysisContext;
    private final Handler symbolResearch;
    private final Handler teachingExample;
    private final Handler scout;
    private final Handler intentLadder;
    private final Handler opportunities;
    private final Handler optimize;

    ResearchController(Db db, Clock clock, MarketDataService market,
                       EvaluationService evaluations,
                       Function<Context, String> ownerId,
                       Function<Context, String> activeWorld,
                       Function<Context, AnalysisContext> analysisContext,
                       Handler symbolResearch, Handler teachingExample,
                       Handler scout, Handler intentLadder,
                       Handler opportunities, Handler optimize) {
        this.clock = clock;
        this.market = market;
        this.evaluations = evaluations;
        this.questions = new ResearchQuestionEngine(market, clock);
        this.notes = new NotebookService(db, clock);
        this.exposure = new ExposureSizer(market);
        this.ownerId = ownerId;
        this.activeWorld = activeWorld;
        this.analysisContext = analysisContext;
        this.symbolResearch = symbolResearch;
        this.teachingExample = teachingExample;
        this.scout = scout;
        this.intentLadder = intentLadder;
        this.opportunities = opportunities;
        this.optimize = optimize;
    }

    void register(JavalinConfig config) {
        ResearchRoutes.register(config, new ResearchRoutes.Handlers(
                ctx -> ctx.json(new ApiResponses.Questions<>(questions.catalog())),
                this::runEventStudy,
                this::createNote, this::listNotes, this::getNote, this::updateNote, this::deleteNote,
                symbolResearch, this::expirations, this::chain, this::history, this::news, this::lookup,
                ctx -> ctx.json(new ApiResponses.StrategyCatalog<>(
                        Arrays.stream(StrategyFamily.values()).map(Enum::name).toList(),
                        StrategyCatalog.families(), StrategyCatalog.templates())),
                teachingExample, scout, intentLadder, opportunities, optimize,
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
        if (!"observed".equals(world) && !"demo".equals(world)) {
            ctx.json(new ApiResponses.SymbolItems<>(symbol, List.of(), null,
                    "simulated market — there is no news in this world; headlines belong to the real market"));
            return;
        }
        ctx.json(new ApiResponses.SymbolItems<>(symbol, market.news(symbol, world), null, null));
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

    private static String worldParam(String world) {
        return world == null || "observed".equals(world) ? null : world;
    }
}
