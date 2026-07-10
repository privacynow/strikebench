package io.liftandshift.strikebench.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import io.liftandshift.strikebench.backtest.Backtester;
import io.liftandshift.strikebench.broker.BrokerService;
import io.liftandshift.strikebench.broker.ETradeProvider;
import io.liftandshift.strikebench.broker.SecretsStore;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.Migrations;
import io.liftandshift.strikebench.market.MarketDataMarks;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.SnapshotService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.market.providers.AlphaVantageProvider;
import io.liftandshift.strikebench.market.providers.CboeProvider;
import io.liftandshift.strikebench.market.providers.EdgarProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.market.providers.FredProvider;
import io.liftandshift.strikebench.market.providers.NewsRssProvider;
import io.liftandshift.strikebench.market.providers.PolygonProvider;
import io.liftandshift.strikebench.market.providers.StooqProvider;
import io.liftandshift.strikebench.market.providers.TreasuryRatesProvider;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountService;
import io.liftandshift.strikebench.paper.AuditLog;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradeRejectedException;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.pricing.HistoricalVol;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.recommend.AutoRecommender;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.recommend.SignalEngine;
import io.liftandshift.strikebench.strategy.Guardrails;
import io.liftandshift.strikebench.strategy.StrategyFamily;
import io.liftandshift.strikebench.strategy.StrategyIntent;
import io.liftandshift.strikebench.strategy.Verdict;
import io.liftandshift.strikebench.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP surface. All routes are registered inside Javalin.create (Javalin 7 style).
 * Errors are JSON {error, detail}; unsafe trades map to 422; /api/status can never 500.
 */
public final class ApiServer {

    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

    private final AppConfig cfg;
    private final Clock clock;
    private final MarketDataService market;
    private final AuditLog audit;
    private final AccountService accounts;
    private final TradeService trades;
    private final RecommendationEngine engine;
    private final AutoRecommender auto;
    private final BrokerService broker;
    private final Backtester backtester;
    private final PositionsService positions;
    private final io.liftandshift.strikebench.market.UniverseService universe;
    private final io.liftandshift.strikebench.market.SnapshotService snapshots;
    private final io.liftandshift.strikebench.auth.AuthService auth;
    private final io.liftandshift.strikebench.eval.EvaluationService evaluations;
    /** In-process pub/sub feeding /api/events (jobs, datasets, provider cooldowns, workspace revs). */
    final io.liftandshift.strikebench.util.EventBus events = new io.liftandshift.strikebench.util.EventBus();
    io.liftandshift.strikebench.db.WorkspaceService workspaceSvc;
    private Javalin app;
    private Db db;   // owned pool; closed on stop()
    private io.liftandshift.strikebench.market.MarketDataEngine marketEngine;   // in-memory feed; warm + background refresh
    private io.liftandshift.strikebench.db.DataJobService dataJobs;             // Data Center background jobs
    private io.liftandshift.strikebench.db.DataCoverage dataCoverage;           // Data Center coverage matrix
    private io.liftandshift.strikebench.db.DataResetService dataReset;          // Data Center tiered wipe
    private CboeProvider cboe;                                                  // for Data Center throttle display
    private io.liftandshift.strikebench.db.DatasetService datasets;             // observed + synthetic dataset registry
    private io.liftandshift.strikebench.sim.SimulationEngine simEngine;         // scenario previews + dataset runs
    private java.util.concurrent.ScheduledExecutorService streamScheduler;     // pushes SSE market frames
    private java.util.concurrent.ScheduledExecutorService snapshotScheduler;   // started iff SNAPSHOT_ENABLED
    private final String startedAt = java.time.Instant.now().toString();

    public ApiServer(AppConfig cfg, Clock clock, MarketDataService market, AuditLog audit,
                     AccountService accounts, TradeService trades, RecommendationEngine engine,
                     AutoRecommender auto, BrokerService broker, Backtester backtester,
                     PositionsService positions, io.liftandshift.strikebench.market.UniverseService universe,
                     io.liftandshift.strikebench.market.SnapshotService snapshots,
                     io.liftandshift.strikebench.auth.AuthService auth,
                     io.liftandshift.strikebench.eval.EvaluationService evaluations) {
        this.positions = positions;
        this.universe = universe;
        this.snapshots = snapshots;
        this.auth = auth;
        this.evaluations = evaluations;
        this.cfg = cfg;
        this.clock = clock;
        this.market = market;
        this.audit = audit;
        this.accounts = accounts;
        this.trades = trades;
        this.engine = engine;
        this.auto = auto;
        this.broker = broker;
        this.backtester = backtester;
        this.marketEngine = new io.liftandshift.strikebench.market.MarketDataEngine(market, universe, cfg, clock);
    }

    /** Wires the whole app from config: DB + migrations + provider chain + services. */
    public static ApiServer create(AppConfig cfg, Clock clock) {
        Db db = Db.forConfig(cfg);
        Migrations.run(db);
        FixtureProvider fixture = new FixtureProvider(clock);
        List<MarketDataProvider> providers = new ArrayList<>();
        List<NewsFilingsProvider> newsProviders = new ArrayList<>();
        List<RatesProvider> ratesProviders = new ArrayList<>();

        SecretsStore secretsStore = new SecretsStore(db, clock);
        ETradeProvider etrade = new ETradeProvider(cfg, secretsStore, clock);
        final CboeProvider[] cboeRef = new CboeProvider[1]; // captured so the Data Center can show throttle state
        final io.liftandshift.strikebench.market.providers.YahooFinanceProvider[] yahooRef =
                new io.liftandshift.strikebench.market.providers.YahooFinanceProvider[1]; // captured for event wiring

        // Priority: E*TRADE -> Cboe -> AlphaVantage/Polygon -> Stooq -> Fixture (always last resort)
        if (!cfg.fixturesOnly()) {
            if (etrade.configured()) providers.add(etrade);
            cboeRef[0] = new CboeProvider(cfg);
            providers.add(cboeRef[0]);
            if (!cfg.alphaVantageApiKey().isBlank()) providers.add(new AlphaVantageProvider(cfg));
            if (!cfg.polygonApiKey().isBlank()) providers.add(new PolygonProvider(cfg));
            // Yahoo keyless equity candles — PERSONAL/LOCAL-CLONE opt-in only (see AppConfig.yahooEnabled).
            // Ahead of Stooq (which is bot-blocked for us) so a self-hoster gets real underlying history.
            if (cfg.yahooEnabled()) {
                yahooRef[0] = new io.liftandshift.strikebench.market.providers.YahooFinanceProvider(cfg);
                providers.add(yahooRef[0]);
            }
            providers.add(new StooqProvider(cfg));
            if (!cfg.newsRssBaseUrl().isBlank()) newsProviders.add(new NewsRssProvider(cfg)); // keyless headlines
            newsProviders.add(new EdgarProvider(cfg));                                          // SEC filings
            if (!cfg.fredApiKey().isBlank()) ratesProviders.add(new FredProvider(cfg));
            ratesProviders.add(new TreasuryRatesProvider(cfg));
        }
        providers.add(fixture);
        newsProviders.add(fixture);
        ratesProviders.add(fixture);

        // Persisted daily bars (Data Center backfills / snapshots / CSV ingest / synthetic datasets)
        // feed the read path; the active-dataset switch decides which dataset serves.
        io.liftandshift.strikebench.db.DatasetService datasetSvc = new io.liftandshift.strikebench.db.DatasetService(db, clock);
        MarketDataService market = new MarketDataService(providers, newsProviders, ratesProviders,
                new io.liftandshift.strikebench.db.StoredCandleStore(db, datasetSvc));
        AuditLog audit = new AuditLog(db, clock);
        AccountService accounts = new AccountService(db, cfg, audit, clock);
        MarketDataMarks marksSource = new MarketDataMarks(market, cfg.fixturesOnly());
        TradeService trades = new TradeService(db, cfg, marksSource, audit, clock);
        PositionsService positions = new PositionsService(db, marksSource, audit, clock);
        RecommendationEngine engine = new RecommendationEngine(market, clock);
        AutoRecommender auto = new AutoRecommender(new SignalEngine(market, clock, cfg.fixturesOnly()), engine, cfg, clock);
        BrokerService broker = new BrokerService(etrade, db, audit, clock);
        // Historical option data for backtests: real chains (Polygon) in live mode,
        // deterministic fixtures in fixture mode — never mixed.
        List<io.liftandshift.strikebench.market.ports.HistoricalOptionsProvider> historical = new ArrayList<>();
        if (cfg.fixturesOnly()) {
            historical.add(fixture);
        } else {
            // Owned option history FIRST (snapshots + licensed CSV ingest) — the "own the past" moat
            // now upgrades the single backtester too, then Polygon if keyed.
            historical.add(new io.liftandshift.strikebench.db.StoredHistoricalOptionsProvider(db));
            if (!cfg.polygonApiKey().isBlank()) historical.add(new PolygonProvider(cfg));
        }
        Backtester backtester = new Backtester(market, historical, cfg, db, clock);
        io.liftandshift.strikebench.market.UniverseService universe = new io.liftandshift.strikebench.market.UniverseService(db, cfg, clock);
        io.liftandshift.strikebench.market.SnapshotService snapshots = new io.liftandshift.strikebench.market.SnapshotService(market, universe, db, clock);
        io.liftandshift.strikebench.auth.AuthService auth = buildAuth(cfg, db, clock);
        io.liftandshift.strikebench.eval.EvaluationService evaluations = new io.liftandshift.strikebench.eval.EvaluationService(market, engine, db, clock);
        ApiServer server = new ApiServer(cfg, clock, market, audit, accounts, trades, engine, auto, broker, backtester, positions, universe, snapshots, auth, evaluations);
        server.db = db;
        // Data Center services (need db, which is set above; reuse the constructor-built engine).
        var backfill = new io.liftandshift.strikebench.db.UnderlyingBackfill(market, db, clock);
        server.dataJobs = new io.liftandshift.strikebench.db.DataJobService(db, clock, server.marketEngine, snapshots, backfill, universe, cfg);
        server.dataCoverage = new io.liftandshift.strikebench.db.DataCoverage(db);
        server.dataReset = new io.liftandshift.strikebench.db.DataResetService(db, accounts);
        server.cboe = cboeRef[0];
        server.marketEngine.setSnapshotStore(new io.liftandshift.strikebench.db.MarketSnapshotStore(db));
        server.datasets = datasetSvc;
        server.simEngine = new io.liftandshift.strikebench.sim.SimulationEngine(market, datasetSvc, db, clock);
        // Workspace continuity + the event bus: services announce, /api/events streams to the browser.
        server.workspaceSvc = new io.liftandshift.strikebench.db.WorkspaceService(db, clock);
        server.workspaceSvc.setEvents(server.events);
        server.dataJobs.setEvents(server.events);
        datasetSvc.setEvents(server.events);
        if (cboeRef[0] != null) cboeRef[0].setEvents(server.events);
        if (yahooRef[0] != null) yahooRef[0].setEvents(server.events);
        // The Cboe breaker survives restarts: persist trips, restore at boot (a restart used to
        // forget an active ban and resume traffic straight back into the rate limiter).
        if (cboeRef[0] != null) {
            try {
                var saved = db.query("SELECT v FROM settings WHERE k='cboe_cooldown_until'", r -> r.str("v"));
                if (!saved.isEmpty() && saved.getFirst() != null) cboeRef[0].seedCooldown(Long.parseLong(saved.getFirst()));
            } catch (Exception e) { /* best effort */ }
            server.events.subscribe(e -> {
                if ("provider.cooldown".equals(e.type()) && "cboe".equals(e.data().get("provider"))) {
                    try {
                        db.exec("INSERT INTO settings(k,v,updated_at) VALUES ('cboe_cooldown_until',?,now()) "
                              + "ON CONFLICT (k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at",
                                String.valueOf(e.data().get("untilMs")));
                    } catch (Exception ex) { /* best effort */ }
                }
            });
        }
        return server;
    }

    /** Builds the auth service; fails CLOSED if AUTH_ENABLED is set without OIDC credentials. */
    private static io.liftandshift.strikebench.auth.AuthService buildAuth(AppConfig cfg, Db db, Clock clock) {
        if (!cfg.authEnabled()) {
            return new io.liftandshift.strikebench.auth.AuthService(cfg, db, clock, null);
        }
        if (cfg.oidcClientId().isBlank() || cfg.oidcClientSecret().isBlank()) {
            throw new IllegalStateException("AUTH_ENABLED=true requires OIDC_CLIENT_ID and OIDC_CLIENT_SECRET");
        }
        var provider = new io.liftandshift.strikebench.auth.GoogleOidcProvider(
                cfg.oidcIssuer(), cfg.oidcClientId(), cfg.oidcClientSecret(), cfg.oidcCallbackUrl());
        return new io.liftandshift.strikebench.auth.AuthService(cfg, db, clock, provider);
    }

    public Javalin start(int port) {
        accounts.getOrCreateDefault();
        app = Javalin.create(c -> {
            c.jetty.port = port;
            c.router.ignoreTrailingSlashes = true;
            // Server-side sessions are only needed for OIDC login; add the handler only when auth
            // is enabled so the default (auth-off) request path is byte-identical to before.
            // Harden the session cookie: HttpOnly (never readable by JS -> the fixation defense
            // isn't undone by XSS), SameSite=Lax (CSRF), and Secure when behind the TLS proxy.
            if (auth.enabled()) {
                c.jetty.modifyServletContextHandler(h -> {
                    var sh = new org.eclipse.jetty.ee10.servlet.SessionHandler();
                    sh.setHttpOnly(true);
                    sh.setSameSite(org.eclipse.jetty.http.HttpCookie.SameSite.LAX);
                    sh.getSessionCookieConfig().setHttpOnly(true);
                    sh.getSessionCookieConfig().setSecure(cfg.authCookieSecure());
                    h.setSessionHandler(sh);
                });
                // Behind a TLS-terminating proxy the app sees plain HTTP; honor X-Forwarded-Proto
                // so request.isSecure() is true and the Secure session cookie is actually emitted.
                // (nginx must send: proxy_set_header X-Forwarded-Proto $scheme;)
                c.jetty.modifyHttpConfiguration(httpConfig ->
                        httpConfig.addCustomizer(new org.eclipse.jetty.server.ForwardedRequestCustomizer()));
            }
            // Resident java.lang.Error handler: never throws, so Error storms (e.g. broken
            // classloading after the jar is rebuilt under a running JVM) end in a 500 instead
            // of wedging Javalin's task loop.
            c.router.javaLangErrorHandler((res, error) -> {
                res.setStatus(500);
                log.error("java.lang.Error while serving a request{}", jarChangedHint(), error);
            });
            c.jsonMapper(new JavalinJackson(Json.MAPPER, true));
            c.startup.showJavalinBanner = false;
            if (ApiServer.class.getResource("/public/index.html") != null) {
                c.staticFiles.add(sf -> {
                    sf.hostedPath = "/";
                    sf.directory = "/public";
                    sf.location = Location.CLASSPATH;
                    // Local-first app, tiny assets: never let a browser pair old ui.js with new
                    // views.js across a rebuild. no-store beats cache-busting with no build step.
                    sf.headers = Map.of("Cache-Control", "no-store");
                });
            }
            // Auth: sign-in flow lives outside /api; /api/auth/me is always readable so the SPA
            // can decide whether to show the sign-in screen.
            c.routes.get("/auth/login", auth::startLogin);
            c.routes.get("/auth/callback", auth::callback);
            c.routes.get("/auth/logout", auth::logout);
            c.routes.post("/auth/logout", auth::logout);
            c.routes.get("/api/auth/me", ctx -> ctx.json(auth.me(ctx)));
            // Gate every other /api route when auth is enabled. Health/config/status stay open so
            // the SPA can bootstrap and show a login screen instead of a mystery failure.
            c.routes.before("/api/*", ctx -> {
                if (!auth.enabled()) return;
                String p = ctx.path();
                if (p.startsWith("/api/auth/") || p.equals("/api/health")
                        || p.equals("/api/config") || p.equals("/api/status")) return;
                auth.requireUser(ctx);
            });
            // The caller's analysis dataset travels as an EXPLICIT AnalysisContext parameter
            // (analysisCtx(ctx)) — no ambient per-request state, so virtual-thread fan-outs keep
            // the caller's world and background machinery always reads observed.

            // LIVE-MODE per-IP throttle: this app fronts real third-party feeds, so one runaway
            // client (a stuck retry loop, a scraper) must not translate into provider hammering.
            // Token bucket per remote IP: 300 burst, ~50 req/s refill — invisible to humans and
            // to the DOM suites (fixture mode never throttles), decisive against a loop. SSE
            // streams and health stay exempt so monitoring keeps working while throttled.
            c.routes.before("/api/*", ctx -> {
                apiRequests.incrementAndGet();
                if (cfg.fixturesOnly()) return;
                String path = ctx.path();
                if (path.equals("/api/health") || path.equals("/api/metrics")
                        || path.equals("/api/events") || path.equals("/api/market/stream")) return;
                String ip = ctx.header("X-Forwarded-For") != null
                        ? ctx.header("X-Forwarded-For").split(",")[0].trim() : ctx.ip();
                if (!throttle.tryAcquire(ip)) {
                    apiThrottled.incrementAndGet();
                    ctx.status(429);
                    ctx.json(java.util.Map.of("error", "rate limited",
                            "detail", "too many requests from this address — slow down and retry"));
                    ctx.skipRemainingHandlers();
                }
            });
            c.routes.get("/api/metrics", this::metrics);

            // Prefetch governor: speculative requests (X-Priority: prefetch) are welcome only when
            // the heavy providers have spare budget. A denied prefetch is a quiet 204 — the client
            // simply doesn't warm its cache; the user never waits on (or behind) a guess.
            c.routes.before("/api/research/*", ctx -> {
                if ("prefetch".equalsIgnoreCase(ctx.header("X-Priority")) && !prefetchBudget()) {
                    ctx.status(204);
                    ctx.header("X-Prefetch", "denied");
                    ctx.skipRemainingHandlers();
                }
            });

            c.routes.get("/api/status", this::status);
            c.routes.get("/api/config", this::config);
            c.routes.get("/api/health", this::health);
            c.routes.get("/api/universe", ctx -> ctx.json(universe.describe()));
            c.routes.put("/api/universe", this::universeSelect);
            c.routes.get("/api/quotes", this::quotesBatch);
            c.routes.get("/api/market/engine", ctx -> ctx.json(marketEngine.status())); // Data Center: engine health
            c.routes.sse("/api/market/stream", this::marketStream);                     // live-ish quote deltas from memory
            c.routes.sse("/api/events", this::eventStream);                             // typed workspace events (jobs/datasets/providers)

            // ---- Workspace continuity: the client-owned UX state blob, versioned per user ----
            c.routes.get("/api/workspace", this::workspaceGet);
            c.routes.put("/api/workspace", this::workspacePut);

            // ---- Data Center ----
            c.routes.get("/api/data/overview", this::dataOverview);
            c.routes.get("/api/data/coverage", this::dataCoverageRoute);
            c.routes.get("/api/data/sources", this::dataSources);
            c.routes.get("/api/data/jobs", this::dataJobsList);
            c.routes.get("/api/data/jobs/{id}", this::dataJobGet);
            c.routes.post("/api/data/jobs", this::dataJobStart);
            c.routes.post("/api/data/jobs/{id}/cancel", this::dataJobCancel);
            c.routes.post("/api/data/jobs/{id}/retry", this::dataJobRetry);
            c.routes.post("/api/data/reset", this::dataResetRoute);

            // ---- Datasets & scenario simulation ----
            c.routes.get("/api/datasets", ctx -> ctx.json(datasets.describe(ownerId(ctx))));
            c.routes.put("/api/datasets/active", this::datasetSetActive);
            c.routes.delete("/api/datasets/{id}", ctx -> {
                datasets.delete(ctx.pathParam("id"), ownerId(ctx)); // ownership enforced in the service
                ctx.json(Map.of("ok", true));
            });
            c.routes.post("/api/sim/scenario", this::simScenario);   // pure compute: the fan preview
            c.routes.post("/api/sim/strategy", this::simStrategy);   // pure compute: strategy P&L distribution
            c.routes.post("/api/sim/compare", this::simCompare);     // pure compute: ALL structures on ONE path set
            c.routes.post("/api/sim/dataset", this::simDataset);     // persists a synthetic dataset

            c.routes.get("/api/account", this::account);
            c.routes.post("/api/account/reset", this::accountReset);

            c.routes.get("/api/research/{symbol}", this::research);
            c.routes.get("/api/research/{symbol}/expirations", this::expirations);
            c.routes.get("/api/research/{symbol}/chain", this::chain);
            c.routes.get("/api/research/{symbol}/history", this::history);
            c.routes.get("/api/research/{symbol}/news", this::news);
            c.routes.get("/api/lookup", this::lookup);

            // The single source of truth for strategy families — frontend catalogs (builder,
            // scenario) are checked against this in the DOM suite so they can never drift silently.
            c.routes.get("/api/strategies", ctx -> ctx.json(Map.of("families",
                    java.util.Arrays.stream(io.liftandshift.strikebench.strategy.StrategyFamily.values())
                            .map(Enum::name).toList())));
            c.routes.post("/api/recommend", this::recommend);
            c.routes.post("/api/recommend/auto", this::recommendAuto);
            c.routes.post("/api/recommend/ladder", this::recommendLadder);
            c.routes.post("/api/evaluate", this::evaluate);
            c.routes.post("/api/opportunities", this::opportunities);
            c.routes.post("/api/optimize", this::optimize);
            c.routes.post("/api/lab/hypothesis", ctx ->
                    ctx.json(new io.liftandshift.strikebench.research.HypothesisTester(market)
                            .test(requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.research.HypothesisTester.HypothesisRequest.class)),
                                    analysisCtx(ctx))));
            // Research-question workbench (replaces the degenerate momentum toy in the UI).
            c.routes.get("/api/lab/questions", ctx ->
                    ctx.json(Map.of("questions", new io.liftandshift.strikebench.research.ResearchQuestionEngine(market).catalog())));
            c.routes.post("/api/lab/question", ctx ->
                    ctx.json(new io.liftandshift.strikebench.research.ResearchQuestionEngine(market)
                            .run(requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.research.ResearchQuestionEngine.RunRequest.class)),
                                    analysisCtx(ctx))));
            c.routes.post("/api/lab/replicate", ctx ->
                    ctx.json(new io.liftandshift.strikebench.research.ETFReplicator(market)
                            .replicate(requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.research.ETFReplicator.ReplicationRequest.class)))));
            c.routes.post("/api/lab/notes", this::noteCreate);
            c.routes.get("/api/lab/notes", this::noteList);
            c.routes.get("/api/lab/notes/{id}", this::noteGet);
            c.routes.put("/api/lab/notes/{id}", this::noteUpdate);
            c.routes.delete("/api/lab/notes/{id}", this::noteDelete);
            c.routes.get("/api/evaluations", ctx -> {
                String uid = auth.currentUserId(ctx);
                String ownerId = io.liftandshift.strikebench.auth.AuthService.LOCAL_USER.equals(uid) ? null : uid;
                ctx.json(Map.of("evaluations", evaluations.recent(ownerId, 50)));
            });
            c.routes.get("/api/calibration", ctx -> {
                String uid = auth.currentUserId(ctx);
                String ownerId = io.liftandshift.strikebench.auth.AuthService.LOCAL_USER.equals(uid) ? null : uid;
                ctx.json(evaluations.calibrationReport(ownerId));
            });
            c.routes.post("/api/calibration/resolve", this::calibrationResolve);

            c.routes.post("/api/trades/preview", this::tradePreview);
            c.routes.post("/api/trades", this::tradeCreate);
            c.routes.get("/api/trades", this::tradeList);
            c.routes.get("/api/trades/{id}", this::tradeDetail);
            c.routes.post("/api/trades/{id}/refresh", this::tradeRefresh);
            c.routes.post("/api/trades/{id}/unwind", this::tradeUnwind);
            c.routes.post("/api/trades/{id}/settle", this::tradeSettle);
            c.routes.delete("/api/trades/{id}", this::tradeDelete);

            c.routes.post("/api/admin/snapshot", this::adminSnapshot);

            c.routes.get("/api/audit", this::auditPage);
            c.routes.get("/api/positions", this::positionsList);
            c.routes.post("/api/positions/buy", this::positionsBuy);
            c.routes.post("/api/positions/sell", this::positionsSell);
            c.routes.get("/api/portfolio/summary", this::portfolioSummary);
            c.routes.get("/api/portfolio/greeks", ctx ->
                    ctx.json(trades.portfolioGreeks(currentAccount(ctx).id())));

            c.routes.post("/api/backtest", ctx ->
                    ctx.json(backtester.run(requireBody(bodyOrNull(ctx, Backtester.BacktestRequest.class)), analysisCtx(ctx))));
            c.routes.post("/api/backtest/portfolio", ctx ->
                    ctx.json(new io.liftandshift.strikebench.backtest.PortfolioBacktester(market, cfg, clock, db)
                            .run(requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.backtest.PortfolioBacktester.PortfolioRequest.class)),
                                    analysisCtx(ctx))));
            c.routes.get("/api/backtests", ctx -> ctx.json(Map.of("backtests", backtester.list())));
            c.routes.get("/api/backtests/{id}", ctx -> ctx.json(backtester.get(ctx.pathParam("id"))));

            c.routes.get("/api/broker/status", ctx -> ctx.json(broker.status()));
            c.routes.post("/api/broker/connect/start", ctx -> ctx.json(Map.of("authorizeUrl", broker.startConnect())));
            c.routes.post("/api/broker/connect/verify", this::brokerVerify);
            c.routes.get("/api/broker/accounts", ctx -> ctx.json(Map.of("accounts", broker.accounts())));
            c.routes.get("/api/broker/accounts/{k}/balance", ctx -> ctx.json(broker.balance(ctx.pathParam("k"))));
            c.routes.get("/api/broker/accounts/{k}/positions", ctx -> ctx.json(Map.of("positions", broker.positions(ctx.pathParam("k")))));
            c.routes.get("/api/broker/orders", ctx -> {
                String k = ctx.queryParam("accountIdKey");
                if (k == null || k.isBlank()) throw new IllegalArgumentException("accountIdKey is required");
                ctx.json(Map.of("orders", broker.orders(k)));
            });
            c.routes.post("/api/broker/orders/preview", this::brokerPreview);
            c.routes.post("/api/broker/orders/place", this::brokerPlace);
            c.routes.put("/api/broker/orders/{id}/cancel", this::brokerCancel);

            c.routes.exception(io.liftandshift.strikebench.auth.UnauthorizedException.class, (e, ctx) ->
                    ctx.status(401).json(Map.of("error", "auth_required", "detail", String.valueOf(e.getMessage()), "loginUrl", "/auth/login")));
            c.routes.exception(TradeRejectedException.class, (e, ctx) ->
                    ctx.status(422).json(Map.of("error", "trade_rejected", "detail", e.getMessage(), "reasons", e.reasons())));
            c.routes.exception(IllegalArgumentException.class, (e, ctx) ->
                    ctx.status(400).json(Map.of("error", "bad_request", "detail", String.valueOf(e.getMessage()))));
            c.routes.exception(java.time.format.DateTimeParseException.class, (e, ctx) ->
                    ctx.status(400).json(Map.of("error", "bad_request", "detail", "Invalid date: " + e.getParsedString())));
            c.routes.exception(com.fasterxml.jackson.core.JacksonException.class, (e, ctx) ->
                    ctx.status(400).json(Map.of("error", "bad_request", "detail", "Malformed request body (expected JSON matching this endpoint's schema)")));
            c.routes.exception(java.util.NoSuchElementException.class, (e, ctx) ->
                    ctx.status(404).json(Map.of("error", "not_found", "detail", String.valueOf(e.getMessage()))));
            c.routes.exception(IllegalStateException.class, (e, ctx) ->
                    ctx.status(409).json(Map.of("error", "conflict", "detail", String.valueOf(e.getMessage()))));
            c.routes.exception(Exception.class, (e, ctx) -> {
                apiErrors.incrementAndGet();
                log.error("unhandled error on {} {}{}", ctx.method(), ctx.path(), jarChangedHint(), e);
                ctx.status(500).json(Map.of("error", "internal", "detail", String.valueOf(e.getMessage()) + jarChangedHint()));
            });
            c.routes.error(404, ctx -> {
                if (ctx.path().startsWith("/api") && ctx.attribute("apiErrorWritten") == null) {
                    ctx.json(Map.of("error", "not_found", "detail", ctx.path()));
                }
            });
        }).start();
        warmUpErrorPipeline(app.port());
        startSnapshotScheduler();
        streamScheduler = java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "market-stream"); t.setDaemon(true); return t;
        });
        if (dataJobs != null) dataJobs.reconcileOnBoot(); // fail orphaned jobs from a prior run so they can retry
        if (marketEngine != null) marketEngine.start(); // warm the universe + background refresh
        log.info("StrikeBench listening on http://localhost:{} (fixturesOnly={})", app.port(), cfg.fixturesOnly());
        return app;
    }

    /**
     * Starts the daily forward-snapshot job when SNAPSHOT_ENABLED is set. Off by default so
     * tests and the keyless demo never hammer live providers; production turns it on to build
     * the historical-evidence moat. A failing run is logged and the schedule continues.
     */
    private void startSnapshotScheduler() {
        if (!cfg.snapshotEnabled()) return;
        snapshotScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snapshot-scheduler");
            t.setDaemon(true);
            return t;
        });
        long initial = Math.max(0, cfg.snapshotInitialDelaySeconds());
        long periodSec = Math.max(1, (long) cfg.snapshotIntervalHours()) * 3600L;
        snapshotScheduler.scheduleWithFixedDelay(() -> {
            try { snapshots.snapshotActiveUniverse(); }
            catch (RuntimeException e) { log.warn("scheduled snapshot failed: {}", e.toString()); }
        }, initial, periodSec, java.util.concurrent.TimeUnit.SECONDS);
        log.info("snapshot scheduler on — first run in {}s, then every {}h", initial, cfg.snapshotIntervalHours());
    }

    /**
     * Forces the routing/404/exception classes to load NOW. If the jar is later rebuilt
     * underneath this JVM, lazy classloading breaks — a resident error pipeline turns that
     * into clean 500s instead of infinitely spinning worker threads.
     */
    private void warmUpErrorPipeline(int port) {
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
            for (String path : List.of("/api/__warmup__", "/__warmup__")) {
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create("http://localhost:" + port + path))
                        .header("Accept", "application/json")
                        .timeout(java.time.Duration.ofSeconds(5)).GET().build();
                client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
            }
        } catch (Exception e) {
            log.warn("error-pipeline warmup failed (non-fatal): {}", e.toString());
        }
    }

    private static final java.nio.file.Path JAR_PATH;
    private static final long JAR_MTIME_AT_BOOT;
    static {
        java.nio.file.Path p = null;
        long mtime = 0;
        try {
            var source = ApiServer.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                java.nio.file.Path candidate = java.nio.file.Path.of(source.getLocation().toURI());
                if (java.nio.file.Files.isRegularFile(candidate)) {
                    p = candidate;
                    mtime = java.nio.file.Files.getLastModifiedTime(candidate).toMillis();
                }
            }
        } catch (Exception ignored) { }
        JAR_PATH = p;
        JAR_MTIME_AT_BOOT = mtime;
    }

    /** Non-empty when the application jar was rewritten after boot — the classic wedge cause. */
    private static String jarChangedHint() {
        try {
            if (JAR_PATH != null
                    && java.nio.file.Files.getLastModifiedTime(JAR_PATH).toMillis() != JAR_MTIME_AT_BOOT) {
                return " [the application jar changed on disk since startup — RESTART the server]";
            }
        } catch (Exception ignored) { }
        return "";
    }

    public void stop() {
        if (dataJobs != null) dataJobs.shutdown();
        if (marketEngine != null) marketEngine.stop();
        if (streamScheduler != null) streamScheduler.shutdownNow();
        if (snapshotScheduler != null) snapshotScheduler.shutdownNow();
        if (app != null) app.stop();
        if (db != null) db.close();
    }

    /** Manually records a forward snapshot of the active universe. Returns per-run counts. */
    private void adminSnapshot(Context ctx) {
        requireAdmin(ctx);
        SnapshotService.SnapshotResult r = snapshots.snapshotActiveUniverse();
        ctx.json(Map.of(
                "asof", r.asof().toString(),
                "symbols", r.symbols(),
                "underlyingRows", r.underlyingRows(),
                "optionRows", r.optionRows(),
                "errors", r.errors(),
                "elapsedMs", r.elapsedMs()));
    }

    /** The paper account for the current request's user (the single local account when auth is off). */
    private Account currentAccount(Context ctx) {
        return accounts.getOrCreateDefaultForUser(auth.currentUserId(ctx));
    }

    /** The persistence owner id for the current user (null = local/anonymous). */
    /**
     * The caller's explicit analysis context: identity + their active dataset. Built per request
     * and PASSED to engines — never stored in a ThreadLocal (virtual-thread fan-outs would lose it,
     * and background work must always read observed).
     */
    private io.liftandshift.strikebench.db.AnalysisContext analysisCtx(Context ctx) {
        String owner = ownerId(ctx);
        String ds = io.liftandshift.strikebench.db.DatasetService.OBSERVED;
        if (datasets != null) {
            try { ds = datasets.activeId(owner); } catch (RuntimeException ignored) { /* observed */ }
        }
        return new io.liftandshift.strikebench.db.AnalysisContext(owner, ds);
    }

    private String ownerId(Context ctx) {
        String uid = auth.currentUserId(ctx);
        return io.liftandshift.strikebench.auth.AuthService.LOCAL_USER.equals(uid) ? null : uid;
    }

    /**
     * Admin gate for DESTRUCTIVE / privileged operations (data reset, server-path CSV import).
     * Fails CLOSED on a public deployment: with auth on, the caller must be an admin email
     * (AUTH_ADMIN_EMAILS, or the entry allowlist if that's unset); with auth off, either a matching
     * ADMIN_TOKEN header or a genuinely LOCAL request (not behind the TLS proxy — nginx sets
     * X-Forwarded-*). This is why /api/data/reset can't be triggered by an anonymous internet visitor.
     */
    private boolean isAdmin(Context ctx) {
        if (auth.enabled()) {
            String uid = auth.currentUserId(ctx);
            if (uid == null || io.liftandshift.strikebench.auth.AuthService.LOCAL_USER.equals(uid)) return false;
            String email = db.query("SELECT email FROM users WHERE id=?", r -> r.str("email"), uid)
                    .stream().findFirst().orElse(null);
            if (email == null) return false;
            // FAIL CLOSED: admin requires an EXPLICIT AUTH_ADMIN_EMAILS entry. We do NOT fall back to
            // the entry allowlist (with the default "any verified Google account" that would make every
            // signed-in user an admin for destructive ops).
            return cfg.authAdminEmails().contains(email.toLowerCase(Locale.ROOT));
        }
        String token = cfg.adminToken();
        if (!token.isBlank()) return token.equals(ctx.header("X-Admin-Token"));
        return isLocalRequest(ctx);
    }

    /**
     * A genuinely local request: no proxy headers (blocks anything behind the nginx TLS proxy, which
     * sets X-Forwarded-*) AND a loopback client IP. LAN/self-host access via a non-loopback IP must set
     * ADMIN_TOKEN — a deliberate fail-closed default for destructive ops.
     */
    private static boolean isLocalRequest(Context ctx) {
        if (!(blank(ctx.header("X-Forwarded-For")) && blank(ctx.header("X-Forwarded-Proto")) && blank(ctx.header("X-Real-IP")))) {
            return false;
        }
        try {
            String ip = ctx.ip();
            return ip != null && java.net.InetAddress.getByName(ip).isLoopbackAddress(); // 127.*, ::1, ::ffff:127.0.0.1
        } catch (Exception e) { return false; }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private void requireAdmin(Context ctx) {
        if (!isAdmin(ctx)) {
            throw new io.liftandshift.strikebench.auth.UnauthorizedException(
                "Admin access required. On a public deployment, enable AUTH (+ AUTH_ADMIN_EMAILS), or set ADMIN_TOKEN and send it as X-Admin-Token.");
        }
    }

    public record NoteRequest(String title, String body, String tags) {}

    private void noteCreate(Context ctx) {
        NoteRequest b = requireBody(bodyOrNull(ctx, NoteRequest.class));
        ctx.json(new io.liftandshift.strikebench.research.NotebookService(db, clock)
                .create(ownerId(ctx), b.title(), b.body(), b.tags()));
    }

    private void noteList(Context ctx) {
        ctx.json(Map.of("notes", new io.liftandshift.strikebench.research.NotebookService(db, clock).list(ownerId(ctx))));
    }

    private void noteGet(Context ctx) {
        ctx.json(new io.liftandshift.strikebench.research.NotebookService(db, clock).get(ownerId(ctx), ctx.pathParam("id")));
    }

    private void noteUpdate(Context ctx) {
        NoteRequest b = requireBody(bodyOrNull(ctx, NoteRequest.class));
        ctx.json(new io.liftandshift.strikebench.research.NotebookService(db, clock)
                .update(ownerId(ctx), ctx.pathParam("id"), b.title(), b.body(), b.tags()));
    }

    private void noteDelete(Context ctx) {
        new io.liftandshift.strikebench.research.NotebookService(db, clock).delete(ownerId(ctx), ctx.pathParam("id"));
        ctx.json(Map.of("ok", true));
    }

    /**
     * Ownership guard for trade-by-id routes: a signed-in user may only touch trades in their own
     * account. Missing OR another user's trade both surface as 404 (never leak existence). A no-op
     * when auth is off, since every trade belongs to the single local account.
     */
    private void ensureOwnedTrade(Context ctx, String tradeId) {
        TradeRecord t = trades.get(tradeId); // NoSuchElementException -> 404 when absent
        if (!t.accountId().equals(currentAccount(ctx).id())) {
            throw new java.util.NoSuchElementException("no such trade " + tradeId);
        }
    }

    // ---- Status / config ----

    private void status(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("ok", true);
            out.put("asOf", Instant.now(clock).toString());
            out.put("fixturesOnly", cfg.fixturesOnly());
            out.put("domains", market.status());
        } catch (Exception e) {
            log.warn("status assembly failed", e);
            out.put("ok", false);
            out.put("error", String.valueOf(e.getMessage()));
        }
        ctx.json(out); // 200 always, by contract
    }

    private void config(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("port", cfg.port());
        out.put("fixturesOnly", cfg.fixturesOnly());
        out.put("marketOpen", io.liftandshift.strikebench.market.MarketHours.isRegularSession(clock.instant()));
        out.put("authEnabled", auth.enabled());  // always-readable auth signal (config is in the auth-open allowlist)
        out.put("feePerContractCents", cfg.feePerContractCents());
        out.put("feePerOrderCents", cfg.feePerOrderCents());
        out.put("defaultStartingCashCents", cfg.defaultStartingCashCents());
        out.put("brand", Map.of("name", cfg.brandName(), "tagline", cfg.brandTagline()));
        out.put("disclaimer", RecommendationEngine.DISCLAIMER);
        // Scenario mode is PERSONAL: the signal reflects the CALLER's active dataset.
        String active = datasets == null ? io.liftandshift.strikebench.db.DatasetService.OBSERVED : datasets.activeId(ownerId(ctx));
        out.put("activeDataset", active);
        out.put("activeDatasetName", datasets == null ? active : datasets.nameOf(active)); // banners show NAMES, not ds_… ids
        out.put("scenarioMode", !io.liftandshift.strikebench.db.DatasetService.OBSERVED.equals(active));
        ctx.json(out);
    }

    /**
     * Liveness + staleness beacon. jarChangedSinceBoot=true means the application jar was
     * rewritten under this running server — the classic "some screens fail to load" cause —
     * and the UI turns it into a restart banner instead of a mystery. Never 500s.
     */
    // ---- Operational metrics + throttle ----
    private final java.util.concurrent.atomic.AtomicLong apiRequests = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong apiErrors = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong apiThrottled = new java.util.concurrent.atomic.AtomicLong();
    private final IpThrottle throttle = new IpThrottle(300, 50.0);

    /** Simple per-IP token bucket. Buckets are pruned lazily past 10k addresses. */
    static final class IpThrottle {
        private final int burst; private final double perSecond;
        private final java.util.concurrent.ConcurrentHashMap<String, Bucket> buckets = new java.util.concurrent.ConcurrentHashMap<>();
        IpThrottle(int burst, double perSecond) { this.burst = burst; this.perSecond = perSecond; }
        boolean tryAcquire(String ip) {
            if (ip == null || ip.isBlank()) return true;
            if (buckets.size() > 10_000) buckets.clear(); // bounded memory beats per-entry bookkeeping here
            Bucket b = buckets.computeIfAbsent(ip, k -> new Bucket(burst));
            synchronized (b) {
                long now = System.nanoTime();
                b.tokens = Math.min(burst, b.tokens + (now - b.lastNs) / 1e9 * perSecond);
                b.lastNs = now;
                if (b.tokens < 1) return false;
                b.tokens -= 1;
                return true;
            }
        }
        static final class Bucket { double tokens; long lastNs = System.nanoTime(); Bucket(int t) { tokens = t; } }
    }

    /** Operational counters — request volume, error volume, throttle hits, engine health. */
    private void metrics(io.javalin.http.Context ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", apiRequests.get());
        out.put("errors", apiErrors.get());
        out.put("throttled", apiThrottled.get());
        out.put("throttleActive", !cfg.fixturesOnly());
        try { out.put("engine", marketEngine.status()); } catch (Exception e) { out.put("engine", Map.of("error", e.toString())); }
        ctx.json(out);
    }

    private void health(Context ctx) {
        boolean changed;
        try {
            changed = !jarChangedHint().isEmpty();
        } catch (RuntimeException e) {
            changed = false;
        }
        ctx.json(Map.of(
                "ok", true,
                "startedAt", startedAt,
                "jarChangedSinceBoot", changed));
    }

    public record UniverseSelectRequest(String sector, List<String> symbols) {}

    private void universeSelect(Context ctx) {
        // The universe is a documented APP-LEVEL setting (it drives the tape, the engine warm set,
        // and scan defaults for everyone) — with auth on, only an admin changes it. Making it a
        // true per-user preference means threading user context through the engine; that is the
        // follow-up recorded in DEVELOPER.md, and this gate closes the cross-user yank until then.
        if (auth.enabled()) requireAdmin(ctx);
        UniverseSelectRequest req = requireBody(bodyOrNull(ctx, UniverseSelectRequest.class));
        if (req.sector() != null && !req.sector().isBlank()) {
            universe.selectSector(req.sector());
        } else if (req.symbols() != null && !req.symbols().isEmpty()) {
            universe.selectCustom(req.symbols());
        } else {
            throw new IllegalArgumentException("Provide either a sector key or a symbols list");
        }
        ctx.json(universe.describe());
    }

    /** Light batch quotes for the ticker tape and dashboards — no chains, no news. */
    private void quotesBatch(Context ctx) {
        String raw = ctx.queryParam("symbols");
        List<String> symbols = raw == null || raw.isBlank()
                ? universe.active().symbols()
                : java.util.Arrays.stream(raw.split(",")).map(s -> s.trim().toUpperCase(Locale.ROOT))
                    .filter(s -> !s.isBlank()).distinct().toList();
        if (symbols.size() > 40) symbols = symbols.subList(0, 40);
        // Served from the in-memory engine: warm symbols answer instantly, cold ones are fetched in
        // parallel, and stale ones refresh in the background — no per-symbol sequential download.
        List<Map<String, Object>> out = new ArrayList<>();
        for (var snap : marketEngine.quotes(symbols)) {
            out.add(io.liftandshift.strikebench.market.MarketDataEngine.toRow(snap));
        }
        ctx.json(Map.of("quotes", out, "requested", symbols.size()));
    }

    /**
     * Server-Sent Events stream of live-ish quote/freshness frames straight from engine memory —
     * the tape and market-facing screens subscribe instead of polling. Each frame is the current
     * snapshot of the requested symbols (default: active universe); the browser falls back to
     * /api/quotes polling if EventSource fails. Cheap: reads warm memory, never a per-tick download.
     */
    private void marketStream(io.javalin.http.sse.SseClient client) {
        String raw = client.ctx().queryParam("symbols");
        final List<String> symbols = raw == null || raw.isBlank()
                ? universe.active().symbols()
                : java.util.Arrays.stream(raw.split(",")).map(s -> s.trim().toUpperCase(Locale.ROOT))
                    .filter(s -> !s.isBlank()).distinct().limit(60).toList();
        client.keepAlive();
        // The task ref lets the push cancel ITSELF when it notices the client died — onClose is
        // the normal path, but a client that vanishes without firing it must not keep a scheduled
        // write alive forever.
        var taskRef = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        Runnable push = () -> {
            if (client.terminated()) {
                var t = taskRef.get();
                if (t != null) t.cancel(false);
                return;
            }
            try {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (var snap : marketEngine.quotes(symbols)) {
                    rows.add(io.liftandshift.strikebench.market.MarketDataEngine.toRow(snap));
                }
                client.sendEvent("quotes", Map.of("quotes", rows, "asOf", clock.millis()));
            } catch (Exception e) { /* client gone or engine hiccup — the scheduled task keeps trying */ }
        };
        push.run(); // immediate first frame so the UI paints without waiting a full interval
        int interval = Math.max(1, cfg.engineStreamIntervalSeconds());
        var task = streamScheduler.scheduleWithFixedDelay(push, interval, interval, java.util.concurrent.TimeUnit.SECONDS);
        taskRef.set(task);
        client.onClose(() -> task.cancel(true));
    }

    // ---- Workspace continuity + the typed event stream ----

    /**
     * Whether heavy providers have budget for SPECULATIVE requests right now. Fixture mode is
     * always yes (local deterministic data); live mode defers to the Cboe breaker/permits —
     * a prefetch guess must never compete with something the user actually asked for.
     */
    private boolean prefetchBudget() {
        if (cfg.fixturesOnly() || cboe == null) return true;
        return cboe.prefetchBudget();
    }

    /**
     * /api/events: one SSE stream of small typed hints (job.progress, job.complete,
     * dataset.selected, provider.cooldown, workspace.updated). Events carry ids/versions,
     * not payloads — the client refetches what it cares about; GETs stay the source of truth.
     * Reconnects replay from Last-Event-ID via the bus's ring buffer.
     */
    private void eventStream(io.javalin.http.sse.SseClient client) {
        client.keepAlive();
        // No Last-Event-ID = a FRESH client: start from now (a history dump of up to 256 stale
        // hints would trigger pointless refetch storms). A reconnect replays only what it missed.
        long last = events.currentSeq();
        String lastId = client.ctx().header("Last-Event-ID");
        if (lastId != null) {
            try { last = Long.parseLong(lastId.trim()); } catch (NumberFormatException ignore) { /* stay at now */ }
        }
        // Per-user scope: events carrying a "user" key (jobs, workspace revs) go only to their
        // owner when auth is on. Global events (dataset.selected, provider.cooldown) go to all.
        // Auth off = single local user = everything. Resolve the caller ONCE — ctx is not
        // guaranteed usable from publisher threads later.
        final String caller = auth.enabled() ? ownerId(client.ctx()) : null;
        final boolean scoped = auth.enabled();
        java.util.function.Predicate<io.liftandshift.strikebench.util.EventBus.Event> visible = e -> {
            if (!scoped) return true;
            Object owner = e.data().get("user");
            return owner == null || owner.equals(caller);
        };
        // Subscribe FIRST, replay second: an event published in between is delivered live instead
        // of lost (a duplicate frame is harmless — events are idempotent hints). The subscriber
        // self-unsubscribes when the client is gone, so a death during replay can't leak it.
        final java.util.concurrent.atomic.AtomicReference<Runnable> unsub = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable unsubscribe = events.subscribe(e -> {
            if (client.terminated()) {
                Runnable u = unsub.get();
                if (u != null) u.run();
                return;
            }
            if (visible.test(e)) sendEvent(client, e);
        });
        unsub.set(unsubscribe);
        client.onClose(unsubscribe);
        for (var e : events.since(last)) if (visible.test(e)) sendEvent(client, e);
    }

    /** Serialized per client: the bus pump and the replay loop must not interleave SSE frames. */
    private void sendEvent(io.javalin.http.sse.SseClient client,
                           io.liftandshift.strikebench.util.EventBus.Event e) {
        try {
            synchronized (client) { client.sendEvent(e.type(), e.data(), String.valueOf(e.seq())); }
        } catch (Exception ignore) { /* client gone — onClose/self-unsubscribe cleans up */ }
    }

    private void workspaceGet(Context ctx) {
        if (workspaceSvc == null) { ctx.json(Map.of("rev", 0)); return; }
        var ws = workspaceSvc.get(ownerId(ctx));
        if (ws.isEmpty()) { ctx.json(Map.of("rev", 0)); return; }
        ctx.json(Map.of("rev", ws.get().rev(), "updatedAt", ws.get().updatedAt(),
                "state", Json.parse(ws.get().stateJson())));
    }

    /** Body IS the state object (client-owned shape). Validated as JSON, size-capped, last-write-wins. */
    private void workspacePut(Context ctx) {
        if (workspaceSvc == null) { ctx.status(503).json(Map.of("error", "workspace store unavailable")); return; }
        String body = ctx.body();
        if (body == null || body.isBlank()) throw new IllegalArgumentException("state body required");
        com.fasterxml.jackson.databind.JsonNode node;
        try { node = Json.parse(body); } catch (Exception e) { throw new IllegalArgumentException("state must be JSON"); }
        if (!node.isObject()) throw new IllegalArgumentException("state must be a JSON object");
        long rev = workspaceSvc.put(ownerId(ctx), body);
        ctx.json(Map.of("ok", true, "rev", rev));
    }

    // ---- Data Center ----

    /** One call for the Data Center header: engine health + coverage summary + mode. Never 500s. */
    private void dataOverview(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        try { out.put("engine", marketEngine.status()); } catch (Exception e) { out.put("engine", null); }
        try { out.put("coverage", dataCoverage.summary()); } catch (Exception e) { out.put("coverage", null); }
        try { out.put("jobs", dataJobs.recent(ownerId(ctx), isAdmin(ctx), 8)); } catch (Exception e) { out.put("jobs", List.of()); }
        out.put("fixturesOnly", cfg.fixturesOnly());
        out.put("marketOpen", io.liftandshift.strikebench.market.MarketHours.isRegularSession(clock.instant()));
        out.put("jobKinds", io.liftandshift.strikebench.db.DataJobService.KINDS);
        out.put("admin", isAdmin(ctx)); // UI hides destructive controls when the caller isn't admin
        ctx.json(out);
    }

    private void dataCoverageRoute(Context ctx) {
        ctx.json(Map.of("symbols", dataCoverage.bySymbol(), "summary", dataCoverage.summary()));
    }

    /** Connector setup cards: what each source covers, whether it's on, and its license/use mode. */
    private void dataSources(Context ctx) {
        List<Map<String, Object>> sources = new ArrayList<>();
        boolean fx = cfg.fixturesOnly();
        boolean cboeThrottled = cboe != null && cboe.coolingDown();
        String cboeHint = "Keyless, HEAVY (each request is a full option-chain payload). Current chains with "
                + "bid/ask/IV/greeks, 15-min delayed. Used for option workflows + a slow active-sector refresh.";
        if (cboeThrottled) cboeHint = "THROTTLED — Cboe rate-limited us (429/1015); cooling down. Serving stale/other "
                + "sources until it clears. " + cboeHint;
        sources.add(source(cboeThrottled ? "Cboe (delayed chains) — THROTTLED" : "Cboe (delayed chains)",
                "Option chains + greeks", !fx && !cboeThrottled, "keyless · delayed · display-only", cboeHint));
        sources.add(source("Yahoo Finance", "Equity/ETF/index candles", cfg.yahooEnabled(), "keyless · PERSONAL / local-clone only",
                "Underlying OHLCV for backtesting (NOT options). Off by default; set YAHOO_ENABLED=true to opt in — you own Yahoo's terms."));
        sources.add(source("Alpha Vantage", "Equity candles + historical options", !cfg.alphaVantageApiKey().isBlank(),
                "keyed · internal-use", "Set ALPHAVANTAGE_API_KEY. 15+ years of historical options with IV/greeks (premium tier)."));
        sources.add(source("Polygon", "Historical option chains", !cfg.polygonApiKey().isBlank(),
                "keyed · internal-use", "Set POLYGON_API_KEY for real historical chains used by the backtester's observed tier."));
        sources.add(source("Stooq", "Equity EOD candles", !fx, "keyless (bot-blocked for us)",
                "Keyless CSV, but Stooq serves an anti-bot wall to non-browser clients — usually falls through."));
        sources.add(source("SEC EDGAR", "Filings (10-K/10-Q/8-K)", !fx, "keyless · public", "Keyless with a contact User-Agent. Corporate filings for the news feed."));
        sources.add(source("Google News RSS", "Headlines", !fx && !cfg.newsRssBaseUrl().isBlank(), "keyless · public", "Keyless per-symbol headlines."));
        sources.add(source("Treasury / FRED", "Risk-free rates", !fx, "keyless / keyed", "Treasury is keyless; FRED needs FRED_API_KEY."));
        sources.add(source("Historical options CSV", "Owned options history", true, "licensed · internal-use (no redistribution)",
                "Import a licensed vendor CSV (ORATS / Cboe DataShop / Databento) via a backfill job — the 'own the past' path."));
        sources.add(source("Built-in fixtures", "Deterministic demo data", fx, "demo", "The offline demo dataset. Serves when nothing real answers."));
        ctx.json(Map.of("sources", sources, "fixturesOnly", fx));
    }

    private static Map<String, Object> source(String name, String covers, boolean enabled, String license, String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("covers", covers); m.put("enabled", enabled);
        m.put("license", license); m.put("hint", hint);
        return m;
    }

    private void dataJobsList(Context ctx) { ctx.json(Map.of("jobs", dataJobs.recent(ownerId(ctx), isAdmin(ctx), 30))); }

    /** Admin, or the job's owner (null==null for the local/anonymous case). Otherwise 404 (no leak). */
    private void requireJobAccess(Context ctx, String jobId) {
        if (isAdmin(ctx)) return;
        if (java.util.Objects.equals(dataJobs.ownerOf(jobId), ownerId(ctx))) return;
        throw new java.util.NoSuchElementException("no such job");
    }

    private void dataJobGet(Context ctx) {
        String id = ctx.pathParam("id");
        requireJobAccess(ctx, id);
        var v = dataJobs.get(id);
        if (v.job() == null) throw new java.util.NoSuchElementException("no such job");
        ctx.json(Map.of("job", v.job(), "items", v.items()));
    }

    public record JobRequest(String kind, Map<String, Object> params) {}

    private void dataJobStart(Context ctx) {
        JobRequest b = requireBody(bodyOrNull(ctx, JobRequest.class));
        // Importing a server-side CSV path reads a local file — gate it like a destructive op.
        if ("import_options_csv".equalsIgnoreCase(b.kind())) requireAdmin(ctx);
        ctx.json(dataJobs.start(b.kind(), b.params(), ownerId(ctx)));
    }

    private void dataJobCancel(Context ctx) {
        String id = ctx.pathParam("id");
        requireJobAccess(ctx, id);
        dataJobs.cancel(id);
        ctx.json(Map.of("ok", true));
    }

    private void dataJobRetry(Context ctx) {
        String id = ctx.pathParam("id");
        requireJobAccess(ctx, id);
        // Re-running a privileged CSV import is itself privileged, even for the job's owner.
        if ("import_options_csv".equalsIgnoreCase(dataJobs.kindOf(id))) requireAdmin(ctx);
        ctx.json(dataJobs.retry(id, ownerId(ctx)));
    }

    // ---- Datasets & scenario simulation ----

    public record ScenarioRequest(String symbol, io.liftandshift.strikebench.sim.ScenarioSpec spec) {}

    public record StrategySimRequest(String symbol,
                                     java.util.List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> legs,
                                     Integer qty,
                                     io.liftandshift.strikebench.sim.ScenarioSpec spec,
                                     io.liftandshift.strikebench.sim.IvSpec iv) {}

    public record ActiveDatasetRequest(String id) {}

    private void datasetSetActive(Context ctx) {
        // PER-USER selection: activating a dataset changes only the CALLER's read path, so no
        // admin gate is needed anymore — ownership (yours or observed) is the whole rule.
        ActiveDatasetRequest b = requireBody(bodyOrNull(ctx, ActiveDatasetRequest.class));
        String owner = ownerId(ctx);
        datasets.setActive(b.id(), owner);
        String nowActive = datasets.activeId(owner);
        ctx.json(Map.of("ok", true, "active", nowActive,
                "scenarioMode", !io.liftandshift.strikebench.db.DatasetService.OBSERVED.equals(nowActive)));
    }

    public record CompareStructure(String key, List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> legs) {}
    public record CompareRequest(String symbol, io.liftandshift.strikebench.sim.ScenarioSpec spec,
                                 io.liftandshift.strikebench.sim.IvSpec iv, Integer qty,
                                 List<CompareStructure> structures) {}

    /**
     * The comparative-evidence engine: every requested structure priced on the SAME seeded path
     * set (one generation, one budget permit), entries resolved to exact listed contracts where a
     * chain matches, refusals reported by name. This replaces N sequential /api/sim/strategy
     * calls that each re-generated identical paths.
     */
    private void simCompare(Context ctx) {
        CompareRequest b = requireBody(bodyOrNull(ctx, CompareRequest.class));
        if (b.spec() == null) throw new IllegalArgumentException("spec is required");
        if (b.structures() == null || b.structures().isEmpty()) throw new IllegalArgumentException("structures are required");
        if (b.structures().size() > 30) throw new IllegalArgumentException("at most 30 structures");
        String sym = b.symbol() == null ? "" : b.symbol().trim().toUpperCase(Locale.ROOT);
        if (sym.isEmpty()) throw new IllegalArgumentException("symbol is required");
        double spot = market.quote(sym)
                .map(q -> q.mark()).filter(java.util.Objects::nonNull)
                .map(java.math.BigDecimal::doubleValue).filter(v -> v > 0)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No price for " + sym + " — a simulation needs a real (or demo) quote to anchor on."));
        int qty = b.qty() == null ? 1 : Math.clamp(b.qty(), 1, 100);
        double r = market.riskFreeRate(Math.max(1, b.spec().sane().horizonDays()));
        io.liftandshift.strikebench.sim.ScenarioSpec spec = calibrateVol(sym, b.spec());
        io.liftandshift.strikebench.sim.IvSpec iv = b.iv();
        if (iv == null) {
            Double atm = atmIvOf(sym);
            iv = io.liftandshift.strikebench.sim.IvSpec.flat(atm != null ? atm : spec.sane().volAnnual());
        }
        List<io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem> items = new ArrayList<>();
        List<Map<String, Object>> refusedEarly = new ArrayList<>();
        for (CompareStructure st : b.structures()) {
            if (st.legs() == null || st.legs().isEmpty() || st.legs().size() > 8) {
                refusedEarly.add(Map.of("key", st.key(), "reason", "invalid legs"));
                continue;
            }
            MarketEntry me = marketEntry(sym, st.legs(), qty);
            var legsToRun = me != null && me.resolvedLegs() != null ? me.resolvedLegs() : st.legs();
            items.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem(
                    st.key(), legsToRun,
                    me == null ? null : me.entryCents(),
                    me == null ? null : "entry at " + me.source() + " executable quotes"));
        }
        var report = new io.liftandshift.strikebench.sim.ScenarioSimulator()
                .compare(spot, items, qty, spec, iv, r, simEngine.historicalLogReturns(sym, analysisCtx(ctx)));
        List<Map<String, Object>> refused = new ArrayList<>(refusedEarly);
        report.refused().forEach(x -> refused.add(Map.of("key", x.key(), "reason", x.reason())));
        ctx.json(Map.of("results", report.results(), "refused", refused,
                "volAnnual", spec.sane().volAnnual()));
    }

    private void simScenario(Context ctx) {
        ScenarioRequest b = requireBody(bodyOrNull(ctx, ScenarioRequest.class));
        if (b.spec() == null) throw new IllegalArgumentException("spec is required");
        ctx.json(simEngine.preview(b.symbol(), calibrateVol(b.symbol(), b.spec())));
    }

    /** volAnnual<=0 = "use market vol": the chain's ATM IV, so every symbol gets ITS OWN wildness. */
    private io.liftandshift.strikebench.sim.ScenarioSpec calibrateVol(String symbol, io.liftandshift.strikebench.sim.ScenarioSpec spec) {
        if (spec.volAnnual() > 0) return spec;
        Double atm = atmIvOf(symbol);
        return atm != null ? spec.withVol(atm) : spec; // sane() falls back to its own default if truly nothing
    }

    private Double atmIvOf(String symbol) {
        try {
            var exps = market.expirations(symbol);
            if (exps.isEmpty()) return null;
            // ~30d expiry is the conventional IV anchor.
            java.time.LocalDate target = java.time.LocalDate.now(clock).plusDays(30);
            java.time.LocalDate exp = exps.stream()
                    .min(java.util.Comparator.comparingLong(e -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(e, target))))
                    .orElse(null);
            var chain = exp == null ? null : market.chain(symbol, exp).orElse(null);
            if (chain == null || chain.isEmpty() || chain.underlyingPrice() == null) return null;
            final java.math.BigDecimal spot = chain.underlyingPrice();
            return chain.calls().stream()
                    .filter(o -> o.iv() != null && o.iv() > 0.01)
                    .min(java.util.Comparator.comparingDouble(o -> Math.abs(o.strike().subtract(spot).doubleValue())))
                    .map(io.liftandshift.strikebench.model.OptionQuote::iv).orElse(null);
        } catch (Exception e) { return null; }
    }

    private void simDataset(Context ctx) {
        ScenarioRequest b = requireBody(bodyOrNull(ctx, ScenarioRequest.class));
        if (calibrateVol(b.symbol(), b.spec()) == null) throw new IllegalArgumentException("spec is required");
        ctx.json(simEngine.toJson(simEngine.runAndPersist(b.symbol(), calibrateVol(b.symbol(), b.spec()), ownerId(ctx))));
    }

    private void simStrategy(Context ctx) {
        StrategySimRequest b = requireBody(bodyOrNull(ctx, StrategySimRequest.class));
        if (b.spec() == null) throw new IllegalArgumentException("spec is required");
        if (b.legs() == null || b.legs().isEmpty()) throw new IllegalArgumentException("legs are required");
        String sym = b.symbol() == null ? "" : b.symbol().trim().toUpperCase(Locale.ROOT);
        if (sym.isEmpty()) throw new IllegalArgumentException("symbol is required");
        // Loud refusal on a missing quote — a strategy simulated against an invented $100 stock
        // would be fixture-masquerade all over again (404 via the NoSuchElementException mapper).
        double spot = market.quote(sym)
                .map(q -> q.mark()).filter(java.util.Objects::nonNull)
                .map(java.math.BigDecimal::doubleValue).filter(v -> v > 0)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No price for " + sym + " — a simulation needs a real (or demo) quote to anchor on. Check the ticker."));
        double r = market.riskFreeRate(Math.max(1, b.spec().sane().horizonDays()));
        // ACTIONABILITY: price the ENTRY from live market quotes (executable sides) when a chain
        // is available, and default the IV path to the chain's ATM IV when the caller didn't set
        // one. A model-priced entry simulated against the same model converges to a coin flip by
        // construction; a market-priced entry measures YOUR SCENARIO vs THE MARKET'S PRICE.
        int qty = b.qty() == null ? 1 : b.qty();
        // Guard the FULL work product: paths×steps are capped in ScenarioSpec, but legs/qty/ratio
        // multiply the pricing loop and the exposure — bound them here too.
        if (b.legs().size() > 8) throw new IllegalArgumentException("at most 8 legs");
        if (qty < 1 || qty > 100) throw new IllegalArgumentException("qty must be 1..100");
        for (var lg : b.legs()) {
            if (lg.ratio() > 10) throw new IllegalArgumentException("ratio must be 1..10");
        }
        MarketEntry me = marketEntry(sym, b.legs(), qty);
        // CALIBRATION: volAnnual<=0 is the "use market vol" sentinel — replace with the chain's
        // ATM IV so a caller with no view on wildness gets THIS symbol's, not a canned 25%.
        io.liftandshift.strikebench.sim.ScenarioSpec spec = b.spec();
        if (spec.volAnnual() <= 0 && me != null && me.atmIv() != null) spec = spec.withVol(me.atmIv());
        io.liftandshift.strikebench.sim.IvSpec iv = b.iv();
        if (iv == null) {
            double atm = me != null && me.atmIv() != null ? me.atmIv() : spec.sane().volAnnual();
            iv = io.liftandshift.strikebench.sim.IvSpec.flat(atm);
        }
        // CONTRACT IDENTITY: when the entry was priced from listed contracts, SIMULATE THOSE
        // CONTRACTS — pricing one strike/expiry and simulating another silently compared two
        // different trades. The note names every snap so nothing shifts silently.
        var legsToRun = me != null && me.resolvedLegs() != null ? me.resolvedLegs() : b.legs();
        String entryNote = null;
        if (me != null) {
            String fresh = me.freshness() == null ? "" : me.freshness();
            String quality = "FIXTURE".equals(fresh) ? "built-in DEMO quotes"
                    : "DELAYED".equals(fresh) ? "delayed market quotes (~15 min)"
                    : "REALTIME".equals(fresh) ? "real-time market quotes"
                    : "market quotes" + (fresh.isEmpty() ? "" : " (" + fresh.toLowerCase(Locale.ROOT) + ")");
            entryNote = "Entry priced from " + me.source() + " " + quality + " at executable sides (buy at ask, sell at bid)"
                    + (me.snaps().isEmpty() ? "" : ". Snapped to listed contracts: " + String.join("; ", me.snaps()))
                    + ".";
        }
        var result = new io.liftandshift.strikebench.sim.ScenarioSimulator().run(
                spot, legsToRun, qty, spec, iv, r,
                simEngine.historicalLogReturns(sym, analysisCtx(ctx)),
                me == null ? null : me.entryCents(),
                entryNote);
        ctx.json(result);
    }

    private record MarketEntry(long entryCents, Double atmIv, String source, String freshness,
                               List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> resolvedLegs,
                               List<String> snaps) {}

    /**
     * Prices the position's entry from the live chain at EXECUTABLE sides. Returns null when any
     * option leg can't be matched to a real quote (unknown expiration/strike, one-sided book) —
     * the simulator then falls back to a model-priced entry, honestly labeled.
     */
    private static String trimNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private MarketEntry marketEntry(String symbol, List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> legs, int qty) {
        try {
            List<java.time.LocalDate> exps = market.expirations(symbol);
            if (exps.isEmpty()) return null;
            java.time.LocalDate today = java.time.LocalDate.now(clock);
            double entryPerUnit = 0;
            Double atmIv = null;
            String source = null;
            String freshness = null;
            java.math.BigDecimal spotBd = null;
            List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> resolved = new ArrayList<>();
            List<String> snaps = new ArrayList<>();
            for (var leg : legs) {
                if ("STOCK".equalsIgnoreCase(leg.type())) {
                    var q = market.quote(symbol).orElse(null);
                    if (q == null || q.mark() == null) return null;
                    double sign = "SELL".equalsIgnoreCase(leg.action()) ? -1 : 1;
                    entryPerUnit += sign * Math.max(1, leg.ratio()) * 100 * q.mark().doubleValue();
                    resolved.add(leg);
                    continue;
                }
                // Nearest listed expiration to the leg's trading-day horizon (~7/5 calendar days/step).
                java.time.LocalDate target = today.plusDays(Math.round(leg.expiryDay() * 7.0 / 5.0));
                java.time.LocalDate exp = exps.stream()
                        .min(java.util.Comparator.comparingLong(e2 -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(e2, target))))
                        .orElse(null);
                if (exp == null) return null;
                var chain = market.chain(symbol, exp).orElse(null);
                if (chain == null || chain.isEmpty()) return null;
                if (spotBd == null) spotBd = chain.underlyingPrice();
                boolean call = "CALL".equalsIgnoreCase(leg.type());
                var side = call ? chain.calls() : chain.puts();
                var quote = side.stream()
                        .min(java.util.Comparator.comparingDouble(o -> Math.abs(o.strike().doubleValue() - leg.strike())))
                        .orElse(null);
                // The nearest listed strike must be reasonably close, or this isn't the same trade.
                if (quote == null || Math.abs(quote.strike().doubleValue() - leg.strike()) > Math.max(2.5, leg.strike() * 0.03)) return null;
                boolean buy = !"SELL".equalsIgnoreCase(leg.action());
                java.math.BigDecimal px = buy ? quote.ask() : quote.bid(); // executable sides
                if (px == null || px.signum() <= 0) return null;
                if (source == null) source = chain.source();
                if (freshness == null && chain.freshness() != null) freshness = chain.freshness().name();
                // ATM IV = the quote closest to spot (first leg's chain is fine for a default).
                if (atmIv == null && spotBd != null) {
                    final java.math.BigDecimal spotF = spotBd;
                    atmIv = side.stream()
                            .filter(o -> o.iv() != null && o.iv() > 0.01)
                            .min(java.util.Comparator.comparingDouble(o -> Math.abs(o.strike().subtract(spotF).doubleValue())))
                            .map(io.liftandshift.strikebench.model.OptionQuote::iv).orElse(null);
                }
                entryPerUnit += (buy ? 1 : -1) * Math.max(1, leg.ratio()) * 100 * px.doubleValue();
                // THE SIMULATED LEG IS THE PRICED LEG: exact listed strike + that expiration's
                // trading-day horizon. Anything that moved is named in the snap note.
                double listedStrike = quote.strike().doubleValue();
                int listedDays = (int) Math.max(1, Math.round(
                        java.time.temporal.ChronoUnit.DAYS.between(today, exp) * 5.0 / 7.0));
                resolved.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg(
                        leg.action(), leg.type(), listedStrike, listedDays, leg.ratio()));
                if (Math.abs(listedStrike - leg.strike()) > 1e-9 || Math.abs(listedDays - leg.expiryDay()) > 1) {
                    snaps.add(leg.type() + " " + trimNum(leg.strike()) + "\u2192" + trimNum(listedStrike) + " exp " + exp);
                }
            }
            return new MarketEntry(Math.round(entryPerUnit * qty * 100), atmIv,
                    source == null ? "live" : source, freshness, resolved, snaps);
        } catch (Exception e) {
            return null; // no market pricing available — model entry, honestly labeled
        }
    }

    public record DataResetRequest(String tier, Boolean confirm) {}

    private void dataResetRoute(Context ctx) {
        requireAdmin(ctx); // destructive: never reachable by an anonymous internet visitor
        DataResetRequest b = requireBody(bodyOrNull(ctx, DataResetRequest.class));
        if (b.confirm() == null || !b.confirm()) {
            ctx.status(400).json(Map.of("error", "confirm_required", "detail", "Reset requires confirm:true"));
            return;
        }
        var tier = io.liftandshift.strikebench.db.DataResetService.parseTier(b.tier());
        var result = dataReset.reset(tier);
        datasets.invalidateActiveCache(); // the settings row is gone; in-memory state must follow
        try { audit.log(null, null, "DATA_RESET", "WARN", Map.of("tier", result.tier(), "tables", result.tablesCleared())); }
        catch (Exception e) { /* best-effort; the reset itself succeeded */ }
        ctx.json(result);
    }

    // ---- Account ----

    private void account(Context ctx) {
        Account acct = currentAccount(ctx);
        ctx.json(Map.of(
                "account", accountView(acct),
                "ledger", accounts.ledger(acct.id(), 0, 20)));
    }

    public record ResetRequest(Long startingCashCents, Boolean confirm, Boolean force) {}

    private void accountReset(Context ctx) {
        ResetRequest req = requireBody(bodyOrNull(ctx, ResetRequest.class));
        long cash = req.startingCashCents() == null ? cfg.defaultStartingCashCents() : req.startingCashCents();
        // Scope the reset to the CALLER's account so a user can never reset someone else's.
        Account acct = accounts.resetAccount(currentAccount(ctx).id(), cash,
                Boolean.TRUE.equals(req.confirm()), Boolean.TRUE.equals(req.force()));
        ctx.json(Map.of("account", accountView(acct)));
    }

    private static Map<String, Object> accountView(Account acct) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", acct.id());
        m.put("name", acct.name());
        m.put("type", acct.type());
        m.put("startingCashCents", acct.startingCashCents());
        m.put("cashCents", acct.cashCents());
        m.put("reservedCents", acct.reservedCents());
        m.put("buyingPowerCents", acct.buyingPowerCents());
        m.put("hasTraded", acct.hasTraded());
        m.put("createdAt", acct.createdAt());
        return m;
    }

    // ---- Research ----

    private void research(Context ctx) {
        String symbol = ctx.pathParam("symbol").trim().toUpperCase(Locale.ROOT);
        Optional<Quote> quote = market.quote(symbol);
        if (quote.isEmpty()) {
            ctx.attribute("apiErrorWritten", true);
            ctx.status(404).json(Map.of("error", "unknown_symbol", "detail", "No data for " + symbol));
            return;
        }
        Quote q = quote.get();
        LocalDate today = LocalDate.now(clock);

        // The three remaining pieces are independent provider calls — the old serial waterfall
        // (expirations→chain→candles→SPY→QQQ) made the endpoint as slow as the SUM of them, which in
        // live mode meant several multi-MB Cboe downloads back-to-back. Run them CONCURRENTLY on
        // virtual threads so the endpoint costs the slowest ONE, not the sum.
        record IvExp(List<LocalDate> exps, Double ivAtm) {}
        try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var ivExpF = exec.submit(() -> {
                List<LocalDate> e = market.expirations(symbol);
                Double iv = e.isEmpty() ? null : market.chain(symbol, e.getFirst()).flatMap(ch -> atmIv(ch)).orElse(null);
                return new IvExp(e, iv);
            });
            var actx = analysisCtx(ctx); // capture BEFORE the fan-out: explicit context survives vthreads
            var candlesF = exec.submit(() -> market.candleSeries(symbol, today.minusDays(120), today, actx));
            var benchF = exec.submit(() -> {
                List<Map<String, Object>> b = new ArrayList<>();
                for (String bench : List.of("SPY", "QQQ")) {
                    if (bench.equals(symbol)) continue;
                    market.quote(bench).ifPresent(x -> b.add(Map.of(
                            "symbol", x.symbol(), "last", x.last(), "freshness", x.freshness().name())));
                }
                return b;
            });

            IvExp ie = ivExpF.get();
            var candleSeries = candlesF.get();
            double hv30 = HistoricalVol.annualized(candleSeries.candles(), 30);
            boolean demoHistory = candleSeries.isFixture() && !cfg.fixturesOnly();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", symbol);
            out.put("quote", q);
            out.put("optionable", q.optionable());
            out.put("ivAtm", ie.ivAtm());
            out.put("hv30", Double.isNaN(hv30) ? null : hv30);
            out.put("historyDemo", demoHistory);
            out.put("expirations", ie.exps().stream().map(LocalDate::toString).toList());
            out.put("benchmarks", benchF.get());
            out.put("freshness", q.freshness().name());
            ctx.json(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) throw re;
            throw new RuntimeException(c == null ? e : c);
        }
    }

    private static Optional<Double> atmIv(OptionChain chain) {
        BigDecimal spot = chain.underlyingPrice();
        return chain.calls().stream()
                .filter(c -> c.iv() != null)
                .min(java.util.Comparator.comparingDouble(c -> Math.abs(c.strike().doubleValue() - spot.doubleValue())))
                .map(OptionQuote::iv);
    }

    private void expirations(Context ctx) {
        String symbol = ctx.pathParam("symbol").trim().toUpperCase(Locale.ROOT);
        ctx.json(Map.of("symbol", symbol,
                "expirations", market.expirations(symbol).stream().map(LocalDate::toString).toList()));
    }

    private void chain(Context ctx) {
        String symbol = ctx.pathParam("symbol").trim().toUpperCase(Locale.ROOT);
        String expStr = ctx.queryParam("expiration");
        if (expStr == null || expStr.isBlank()) {
            throw new IllegalArgumentException("expiration query parameter is required (YYYY-MM-DD)");
        }
        LocalDate exp = LocalDate.parse(expStr.trim());
        Optional<OptionChain> chain = market.chain(symbol, exp);
        if (chain.isEmpty()) {
            ctx.attribute("apiErrorWritten", true);
            ctx.status(404).json(Map.of("error", "no_chain", "detail", "No option chain for " + symbol + " " + exp));
            return;
        }
        ctx.json(chain.get());
    }

    private void history(Context ctx) {
        String symbol = ctx.pathParam("symbol").trim().toUpperCase(Locale.ROOT);
        String requested = ctx.queryParam("range") == null ? "1y" : ctx.queryParam("range").toLowerCase(Locale.ROOT);
        String range = switch (requested) {
            case "1m", "3m", "6m", "ytd", "1y", "2y", "5y", "max" -> requested;
            default -> "1y";
        };
        LocalDate today = LocalDate.now(clock);
        int days = switch (range) {
            case "1m" -> 30;
            case "3m" -> 91;
            case "6m" -> 182;
            case "ytd" -> today.getDayOfYear();
            case "2y" -> 730;
            case "5y" -> 1826;
            case "max" -> 7300; // providers return what they actually have
            default -> 365;
        };
        var series = market.candleSeries(symbol, today.minusDays(days), today, analysisCtx(ctx));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("range", range);
        out.put("candles", series.candles());
        out.put("source", series.source());
        out.put("freshness", series.freshness().name());
        out.put("demo", series.isFixture() && !cfg.fixturesOnly());
        ctx.json(out);
    }

    private void news(Context ctx) {
        String symbol = ctx.pathParam("symbol").trim().toUpperCase(Locale.ROOT);
        ctx.json(Map.of("symbol", symbol, "items", market.news(symbol)));
    }

    private void lookup(Context ctx) {
        String q = ctx.queryParam("q");
        ctx.json(Map.of("matches", q == null ? List.of() : market.lookup(q)));
    }

    // ---- Recommendations ----

    private void recommend(Context ctx) {
        RecommendationEngine.Result result = resolveAndRecommend(ctx);
        ctx.json(decisionRanked(result, currentAccount(ctx)));
    }

    /**
     * ONE ranking everywhere: candidates leave this API ordered by the DECISION score (the full
     * StrategyEvaluation composite — gates, capital, tail risk, evidence haircut), the same score
     * the Decision page and the opportunity scan use. The engine's quick screen score stays on each
     * candidate as a disclosed component. Evaluation trouble falls back to screen order, labeled.
     */
    private Object decisionRanked(RecommendationEngine.Result result, Account acct) {
        if (result.candidates() == null || result.candidates().size() < 2) return result;
        try {
            var evals = evaluations.evaluate(result.symbol(), result.intent(), result.thesis(), result.horizon(),
                    result.riskMode(), result.candidates(), acct.buyingPowerCents(), null, false,
                    io.liftandshift.strikebench.db.AnalysisContext.OBSERVED); // recommendations always price observed
            if (evals.size() != result.candidates().size()) return result; // partial evaluation: keep screen order
            com.fasterxml.jackson.databind.node.ObjectNode out =
                    (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(result);
            com.fasterxml.jackson.databind.node.ArrayNode cands = out.putArray("candidates");
            for (var e : evals) { // evaluateAndRank order: viable first, then risk-adjusted desc
                com.fasterxml.jackson.databind.node.ObjectNode m =
                        (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(e.candidate());
                m.put("decisionScore", Math.round(e.rankScore()));
                m.put("decisionViable", e.viable());
                cands.add(m);
            }
            out.put("ranking", "decision"); // disclosed: what ordered this list
            return out;
        } catch (RuntimeException e) {
            log.warn("decision ranking unavailable — serving screen order: {}", e.toString());
            return result;
        }
    }

    /** Parses the recommend request (injecting real holdings for hold-based intents) and runs the engine. */
    private RecommendationEngine.Result resolveAndRecommend(Context ctx) {
        RecommendationEngine.Request req = bodyOrNull(ctx, RecommendationEngine.Request.class);
        if (req == null || req.symbol() == null || req.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        Account acct = currentAccount(ctx);
        // Hold-based intents read the account's real position when the caller didn't supply
        // one: free shares + average basis feed strike selection and the intent framing.
        StrategyIntent intent = StrategyIntent.parse(req.intent());
        // ACQUIRE is excluded: holdings.sharesOwned means "shares I WANT" there, and injecting
        // the existing position would silently size new purchases to what is already owned.
        if (req.holdings() == null && intent != StrategyIntent.DIRECTIONAL && intent != StrategyIntent.ACQUIRE) {
            try {
                PositionsService.PositionView pos = positions.get(acct.id(), req.symbol());
                req = new RecommendationEngine.Request(req.symbol(), req.thesis(), req.horizon(), req.riskMode(),
                        req.maxLossCents(), req.maxRiskPctOfAccount(), req.minConfidence(), req.allowedStrategies(),
                        req.avoidEarnings(), req.allow0dte(), req.intent(),
                        new RecommendationEngine.Holdings((int) Math.min(Integer.MAX_VALUE, pos.freeShares()),
                                pos.avgCostCents(), null),
                        req.filters());
            } catch (java.util.NoSuchElementException ignored) { /* no position — engine handles it */ }
        }
        return engine.recommend(req, acct.buyingPowerCents());
    }

    /**
     * Recommendations-as-a-competition: runs the engine, then wraps each candidate in a full
     * StrategyEvaluation (capital / volatility / risk / evidence / management / score / explanation),
     * ranks them, and persists the competition for later review. Feeds the Phase-3 decision UI.
     */
    private void evaluate(Context ctx) {
        RecommendationEngine.Result result = resolveAndRecommend(ctx);
        Account acct = currentAccount(ctx);
        String uid = auth.currentUserId(ctx);
        String ownerId = io.liftandshift.strikebench.auth.AuthService.LOCAL_USER.equals(uid) ? null : uid;
        var evals = evaluations.evaluate(result.symbol(), result.intent(), result.thesis(), result.horizon(),
                result.riskMode(), result.candidates(), acct.buyingPowerCents(), ownerId, true);
        // Record the surfaced pick as a calibration sample; its id lets a placed trade link back.
        String recommendationId = null;
        if (!evals.isEmpty()) {
            try { recommendationId = evaluations.recordSurfaced(evals.getFirst().id(), ownerId); }
            catch (RuntimeException e) { log.warn("could not record recommendation: {}", e.toString()); }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", result.symbol());
        out.put("intent", String.valueOf(result.intent()));
        out.put("evaluations", evals);
        out.put("rejected", result.rejected());
        if (recommendationId != null) out.put("recommendationId", recommendationId);
        ctx.json(out);
    }

    public record OpportunitiesRequest(List<String> universe, String thesis, String horizon,
                                       String riskMode, String intent, Integer topN) {}

    /**
     * Universe-scale competition: scans a universe (the active one by default), keeps each symbol's
     * best viable idea, and ranks them cross-symbol so the strongest opportunities surface first.
     */
    private void opportunities(Context ctx) {
        OpportunitiesRequest req = bodyOrNull(ctx, OpportunitiesRequest.class);
        if (req == null) req = new OpportunitiesRequest(null, null, null, null, null, null);
        List<String> symbols = (req.universe() != null && !req.universe().isEmpty())
                ? req.universe() : universe.active().symbols();
        Account acct = currentAccount(ctx);
        String uid = auth.currentUserId(ctx);
        String ownerId = io.liftandshift.strikebench.auth.AuthService.LOCAL_USER.equals(uid) ? null : uid;
        int topN = req.topN() != null ? req.topN() : 8;
        var result = evaluations.scan(symbols, req.intent(),
                req.thesis() != null ? req.thesis() : "neutral",
                req.horizon() != null ? req.horizon() : "month",
                req.riskMode() != null ? req.riskMode() : "balanced",
                acct.buyingPowerCents(), ownerId, topN);
        ctx.json(Map.of("ranked", result.ranked(), "notes", result.notes(), "scanned", result.scanned()));
    }

    public record OptimizeRequest(List<String> universe, String thesis, String horizon, String riskMode,
                                  String intent, Long totalCapitalCents, Long maxPerPositionCents,
                                  Integer maxPositions, Double maxSymbolPct, String objective, Boolean diagnostic) {}

    /** Research lab: scan a universe, then allocate a budget across the winners under constraints. */
    private void optimize(Context ctx) {
        OptimizeRequest req = bodyOrNull(ctx, OptimizeRequest.class);
        if (req == null) req = new OptimizeRequest(null, null, null, null, null, null, null, null, null, null, null);
        List<String> symbols = (req.universe() != null && !req.universe().isEmpty())
                ? req.universe() : universe.active().symbols();
        Account acct = currentAccount(ctx);
        String uid = auth.currentUserId(ctx);
        String ownerId = io.liftandshift.strikebench.auth.AuthService.LOCAL_USER.equals(uid) ? null : uid;
        var scan = evaluations.scan(symbols, req.intent(),
                req.thesis() != null ? req.thesis() : "neutral",
                req.horizon() != null ? req.horizon() : "month",
                req.riskMode() != null ? req.riskMode() : "balanced",
                acct.buyingPowerCents(), ownerId, Math.max(1, symbols.size()));
        long budget = req.totalCapitalCents() != null ? req.totalCapitalCents() : acct.buyingPowerCents();
        var result = new io.liftandshift.strikebench.research.PortfolioOptimizer().optimize(scan.ranked(),
                new io.liftandshift.strikebench.research.PortfolioOptimizer.Constraints(
                        budget, req.maxPerPositionCents(), req.maxPositions(), req.maxSymbolPct(), req.objective(),
                        Boolean.TRUE.equals(req.diagnostic())));
        ctx.json(Map.of("optimization", result, "scanned", scan.scanned(), "scanNotes", scan.notes()));
    }

    public record ResolveRequest(String recommendationId, String status, Long pnlCents) {}

    /** Records the realized outcome of a surfaced recommendation (feeds the calibration report). */
    private void calibrationResolve(Context ctx) {
        ResolveRequest req = requireBody(bodyOrNull(ctx, ResolveRequest.class));
        if (req.recommendationId() == null || req.recommendationId().isBlank()) {
            throw new IllegalArgumentException("recommendationId is required");
        }
        evaluations.resolveOutcome(req.recommendationId(), req.status(), req.pnlCents());
        ctx.json(Map.of("ok", true));
    }

    private void recommendLadder(Context ctx) {
        RecommendationEngine.Request req = bodyOrNull(ctx, RecommendationEngine.Request.class);
        if (req == null || req.symbol() == null || req.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        Account acct = currentAccount(ctx);
        StrategyIntent intent = StrategyIntent.parse(req.intent());
        if (req.holdings() == null && intent != StrategyIntent.DIRECTIONAL && intent != StrategyIntent.ACQUIRE) {
            try {
                PositionsService.PositionView pos = positions.get(acct.id(), req.symbol());
                req = new RecommendationEngine.Request(req.symbol(), req.thesis(), req.horizon(), req.riskMode(),
                        req.maxLossCents(), req.maxRiskPctOfAccount(), req.minConfidence(), req.allowedStrategies(),
                        req.avoidEarnings(), req.allow0dte(), req.intent(),
                        new RecommendationEngine.Holdings((int) Math.min(Integer.MAX_VALUE, pos.freeShares()),
                                pos.avgCostCents(), null),
                        req.filters());
            } catch (java.util.NoSuchElementException ignored) { /* buy-write ladder */ }
        }
        ctx.json(engine.ladder(req, acct.buyingPowerCents()));
    }

    private void recommendAuto(Context ctx) {
        AutoRecommender.AutoRequest req = bodyOrNull(ctx, AutoRecommender.AutoRequest.class);
        if (req == null) { // absent body (or the literal document "null") means defaults
            req = new AutoRecommender.AutoRequest(null, null, null, null, null, null, null, null, null, null, null);
        }
        if (req.universe() == null || req.universe().isEmpty()) {
            // The globally selected universe (sector or custom) is the scout's default scan list
            req = new AutoRecommender.AutoRequest(universe.active().symbols(), req.horizons(), req.maxPicks(),
                    req.targetProfitCents(), req.maxLossCents(), req.maxRiskPctOfAccount(), req.minConfidence(),
                    req.riskMode(), req.allow0dte(), req.intents(), req.filters());
        }
        Account acct = currentAccount(ctx);
        // Real holdings feed the EXIT/HEDGE scans and let INCOME write against held shares
        List<AutoRecommender.HoldingInfo> held = positions.list(acct.id()).stream()
                .map(p -> new AutoRecommender.HoldingInfo(p.symbol(),
                        (int) Math.min(Integer.MAX_VALUE, p.freeShares()), p.avgCostCents()))
                .toList();
        ctx.json(auto.run(req, acct.buyingPowerCents(), held));
    }

    // ---- Trades ----

    public record TradeOpenRequest(String symbol, String strategy, Integer qty, List<LegView> legs,
                                   String thesis, String horizon, String riskMode,
                                   String intent, Boolean useHeldShares, String recommendationId) {}

    public record ConfirmRequest(Boolean confirm) {}

    private TradeService.OpenRequest toOpenRequest(TradeOpenRequest body, Account acct) {
        if (body == null) throw new IllegalArgumentException("request body is required");
        if (body.symbol() == null || body.symbol().isBlank()) throw new IllegalArgumentException("symbol is required");
        if (body.legs() == null || body.legs().isEmpty()) throw new IllegalArgumentException("legs are required");
        List<Leg> legs = body.legs().stream().map(LegView::toLeg).toList();
        if (body.intent() != null && !body.intent().isBlank()) {
            StrategyIntent.parse(body.intent()); // 400 on unknown intents before any money math
        }
        return new TradeService.OpenRequest(acct.id(), body.symbol().trim().toUpperCase(Locale.ROOT),
                body.strategy() == null ? "CUSTOM" : body.strategy().trim().toUpperCase(Locale.ROOT),
                body.qty() == null ? 1 : body.qty(), legs, body.thesis(), body.horizon(), body.riskMode(),
                body.intent(), body.useHeldShares());
    }

    private Verdict guardrailCheck(TradeService.OpenRequest req, Account acct) {
        StrategyFamily family = null;
        try { family = StrategyFamily.valueOf(req.strategy()); } catch (IllegalArgumentException ignored) {}
        List<OptionQuote> quotes = new ArrayList<>();
        Freshness worst = Freshness.FIXTURE;
        // Earnings proximity from the news/filings stream (best-effort; ex-div data has no
        // keyless source and stays false — documented limitation).
        boolean earningsSoon = market.news(req.symbol()).stream().anyMatch(n -> {
            String h = n.headline() == null ? "" : n.headline().toLowerCase(Locale.ROOT);
            return h.contains("earnings") || h.contains("guidance") || h.contains("results");
        });
        BigDecimal spot = market.quote(req.symbol()).map(Quote::mark).orElse(null);
        // Requests may omit entryPrice (the server fills at current mids) — price the legs
        // here too, or premium-sign rules (e.g. multi-expiration credit) misfire on zeros.
        List<Leg> priced = new ArrayList<>(req.legs().size());
        for (Leg leg : req.legs()) {
            if (leg.isStock()) {
                quotes.add(null);
                BigDecimal px = leg.entryPrice().signum() > 0 ? leg.entryPrice() : spot != null ? spot : BigDecimal.ZERO;
                priced.add(new Leg(leg.action(), null, null, null, leg.ratio(), px));
                continue;
            }
            OptionQuote q = market.chain(req.symbol(), leg.expiration())
                    .flatMap(ch -> ch.find(leg.type(), leg.strike())).orElse(null);
            quotes.add(q);
            if (q != null) worst = Freshness.worse(worst, q.freshness());
            BigDecimal mid = leg.entryPrice().signum() > 0 ? leg.entryPrice() : q != null && q.mid() != null ? q.mid() : BigDecimal.ZERO;
            priced.add(new Leg(leg.action(), leg.type(), leg.strike(), leg.expiration(), leg.ratio(), mid));
        }
        // Held-share coverage: when the request writes against shares the account owns, the
        // free lot count flows into the guardrails so a covered call is not misread as naked.
        long lockedLots = 0;
        String shareShortfall = null;
        if (req.heldShares()) {
            int lotsPerUnit = Math.max(0, io.liftandshift.strikebench.strategy.CoverageCheck.callCoverLotsNeeded(priced));
            long neededShares = Math.max((long) lotsPerUnit * 100 * req.qty(), 100L * req.qty());
            long free = positions.freeShares(acct.id(), req.symbol());
            if (free >= neededShares) {
                lockedLots = (long) lotsPerUnit * req.qty();
            } else {
                // Tell the truth ("you need N free shares"), not "add a protective wing"
                shareShortfall = "Needs " + neededShares + " free shares of " + req.symbol()
                        + " but only " + Math.max(0, free) + " are free (held minus already locked)";
            }
        }
        Verdict verdict = Guardrails.check(new Guardrails.Proposal(family, priced, req.qty(), quotes, spot,
                worst, LocalDate.now(clock), acct.buyingPowerCents(), false, earningsSoon, false, lockedLots));
        if (shareShortfall != null) {
            List<String> blocks = new ArrayList<>(verdict.blockReasons());
            blocks.addFirst(shareShortfall);
            verdict = Verdict.of(blocks, verdict.warnings());
        }
        // The manual ticket is not bound by risk-mode budgets (deliberate freedom), but an
        // oversized trade deserves a loud flag against the mode the user chose in the header.
        try {
            io.liftandshift.strikebench.pricing.PayoffCurve curve = io.liftandshift.strikebench.pricing.PayoffCurve.of(priced, req.qty());
            if (!curve.maxLossUnbounded() && curve.maxLossCents() > 0) {
                RecommendationEngine.RiskMode mode = RecommendationEngine.RiskMode.parse(req.riskMode());
                long budget = Math.round(acct.buyingPowerCents() * mode.defaultRiskPct());
                if (curve.maxLossCents() > budget) {
                    List<String> warnings = new ArrayList<>(verdict.warnings());
                    warnings.add("Max loss " + io.liftandshift.strikebench.util.Money.fmt(curve.maxLossCents())
                            + " exceeds your " + mode.name().toLowerCase(Locale.ROOT) + " risk budget of "
                            + io.liftandshift.strikebench.util.Money.fmt(budget) + " per trade — allowed, but oversized for your chosen risk mode");
                    verdict = Verdict.of(verdict.blockReasons(), warnings);
                }
            }
        } catch (RuntimeException ignored) { /* advisory only */ }
        return verdict;
    }

    private void tradePreview(Context ctx) {
        Account acct = currentAccount(ctx);
        TradeService.OpenRequest req = toOpenRequest(bodyOrNull(ctx, TradeOpenRequest.class), acct);
        Verdict verdict = guardrailCheck(req, acct);
        ctx.json(Map.of(
                "preview", trades.preview(req),
                "guardrails", Map.of(
                        "level", verdict.level().name(),
                        "blockReasons", verdict.blockReasons(),
                        "warnings", verdict.warnings())));
    }

    private void tradeCreate(Context ctx) {
        Account acct = currentAccount(ctx);
        TradeOpenRequest body = bodyOrNull(ctx, TradeOpenRequest.class);
        TradeService.OpenRequest req = toOpenRequest(body, acct);
        Verdict verdict = guardrailCheck(req, acct);
        if (verdict.blocked()) {
            audit.log(acct.id(), null, "TRADE_REJECTED", "BLOCK",
                    Map.of("symbol", req.symbol(), "strategy", req.strategy(), "reasons", verdict.blockReasons()));
            throw new TradeRejectedException(verdict.blockReasons());
        }
        TradeRecord t = trades.create(req);
        // Close the calibration loop: link the placed trade to the recommendation it came from.
        if (body != null && body.recommendationId() != null && !body.recommendationId().isBlank()) {
            try { evaluations.linkTrade(body.recommendationId(), t.id()); }
            catch (RuntimeException e) { log.warn("could not link recommendation: {}", e.toString()); }
        }
        ctx.status(201).json(Map.of("trade", TradeView.of(t), "warnings", verdict.warnings()));
    }

    private void tradeList(Context ctx) {
        Account acct = currentAccount(ctx);
        int page = intParam(ctx, "page", 0);
        int size = Math.clamp(intParam(ctx, "size", 20), 1, 100);
        TradeService.Page result = trades.list(acct.id(), ctx.queryParam("status"),
                ctx.queryParam("symbol"), ctx.queryParam("intent"), page, size);
        // ACTIVE rows carry a live unrealized P/L (a table without "how is it doing NOW" hides
        // the one number a position holder wants). Best-effort per row: a mark that can't price
        // just leaves the cell empty — never fails the list.
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var t : result.trades()) {
            Map<String, Object> row = new LinkedHashMap<>(Json.MAPPER.convertValue(TradeView.of(t),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            if ("ACTIVE".equals(t.status())) {
                try {
                    var mark = trades.currentMark(t.id());
                    if (mark != null && mark.unrealizedCents() != null) {
                        row.put("unrealizedPnlCents", mark.unrealizedCents());
                    }
                } catch (Exception e) { /* leave blank */ }
            }
            rows.add(row);
        }
        ctx.json(Map.of("trades", rows,
                "total", result.total(), "page", result.page(), "size", result.size()));
    }

    // ---- Equity positions ----

    public record StockOrderRequest(String symbol, Long shares) {}

    private void positionsList(Context ctx) {
        Account acct = currentAccount(ctx);
        ctx.json(Map.of("positions", positions.list(acct.id()),
                "note", "Shares locked as covered-call coverage cannot be sold until the covering trade closes"));
    }

    private void positionsBuy(Context ctx) {
        StockOrderRequest req = bodyOrNull(ctx, StockOrderRequest.class);
        validateStockOrder(req);
        Account acct = currentAccount(ctx);
        ctx.status(201).json(positions.buy(acct.id(), req.symbol(), req.shares()));
    }

    private void positionsSell(Context ctx) {
        StockOrderRequest req = bodyOrNull(ctx, StockOrderRequest.class);
        validateStockOrder(req);
        Account acct = currentAccount(ctx);
        ctx.json(positions.sell(acct.id(), req.symbol(), req.shares()));
    }

    private static void validateStockOrder(StockOrderRequest req) {
        if (req == null) throw new IllegalArgumentException("request body is required");
        if (req.symbol() == null || req.symbol().isBlank()) throw new IllegalArgumentException("symbol is required");
        if (req.shares() == null || req.shares() <= 0) throw new IllegalArgumentException("shares must be a positive number");
    }

    private void tradeDetail(Context ctx) {
        String id = ctx.pathParam("id");
        ensureOwnedTrade(ctx, id);
        TradeRecord t = trades.get(id);
        TradeService.MarkView current = null;
        if (TradeRecord.ACTIVE.equals(t.status())) {
            try { current = trades.currentMark(id); } catch (Exception e) { log.warn("mark failed for {}", id, e); }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade", TradeView.of(t));
        out.put("current", current);
        out.put("marksHistory", trades.marksHistory(id, 50));
        out.put("audit", audit.forTrade(id, 50));
        out.put("payoff", payoffPoints(t));
        ctx.json(out);
    }

    /** Payoff table for charting: profit at expiration across a price grid (empty for calendars). */
    private static List<Map<String, Object>> payoffPoints(TradeRecord t) {
        boolean mixed = t.legs().stream().filter(l -> !l.isStock()).map(Leg::expiration).distinct().count() > 1;
        if (mixed) return List.of();
        BigDecimal spot = BigDecimal.valueOf(t.entryUnderlyingCents()).movePointLeft(2);
        // Share-covered trades were risk-shaped WITH their held lot — chart the same combined
        // position, or the graph shows a naked short call next to "max loss $0".
        List<Leg> chartLegs = t.legs();
        int lotsPerUnit = t.qty() > 0 ? (int) (t.sharesLocked() / (100L * t.qty())) : 0;
        if (lotsPerUnit > 0) {
            chartLegs = new ArrayList<>(t.legs());
            chartLegs.add(Leg.stock(io.liftandshift.strikebench.model.LegAction.BUY, lotsPerUnit, spot));
        }
        PayoffCurve curve = PayoffCurve.of(chartLegs, t.qty());
        List<Map<String, Object>> out = new ArrayList<>();
        for (PayoffCurve.ChartPoint p : curve.chartPoints(spot)) {
            out.add(Map.of("price", p.price().toPlainString(), "profitCents", p.profitCents()));
        }
        return out;
    }

    /** One honest headline: cash + share value + what closing every open trade pays now.
     *  Reserve is a lien INSIDE cash (never added twice); all marks pre-close-fee. */
    private void portfolioSummary(Context ctx) {
        var acct = currentAccount(ctx);
        long sharesValue = 0;
        int sharesCount = 0;
        boolean complete = true;
        for (var p : positions.list(acct.id())) {
            sharesCount++;
            if (p.marketValueCents() == null) { complete = false; continue; }
            sharesValue += p.marketValueCents();
        }
        Map<String, Object> open = trades.openPositionsValue(acct.id());
        long openValue = (long) open.get("valueCents");
        if (!(boolean) open.get("complete")) complete = false;
        long total = acct.cashCents() + sharesValue + openValue;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cashCents", acct.cashCents());
        out.put("reservedCents", acct.reservedCents());
        out.put("buyingPowerCents", acct.buyingPowerCents());
        out.put("startingCashCents", acct.startingCashCents());
        out.put("sharesValueCents", sharesValue);
        out.put("sharesPositions", sharesCount);
        out.put("openTradesCount", open.get("openTradesCount"));
        out.put("openTradesValueCents", openValue);
        out.put("openTradesUnrealizedCents", open.get("unrealizedCents"));
        out.put("totalValueCents", total);
        out.put("totalPnlCents", total - acct.startingCashCents());
        out.put("complete", complete);
        out.put("freshness", open.get("freshness"));
        out.put("note", "Liquidation view at current marks: cash + shares + closing every open trade at executable prices, BEFORE close fees. Reserve is part of cash, never double-counted.");
        ctx.json(out);
    }

    private void tradeRefresh(Context ctx) {
        ensureOwnedTrade(ctx, ctx.pathParam("id"));
        ctx.json(trades.refresh(ctx.pathParam("id")));
    }

    private void tradeUnwind(Context ctx) {
        ensureOwnedTrade(ctx, ctx.pathParam("id"));
        ConfirmRequest req = bodyOrNull(ctx, ConfirmRequest.class);
        TradeService.CloseResult result = trades.unwind(ctx.pathParam("id"),
                req != null && Boolean.TRUE.equals(req.confirm()));
        resolveRecommendationForTrade(ctx.pathParam("id"), "CLOSED", result.realizedPnlCents());
        ctx.json(Map.of("trade", TradeView.of(result.trade()), "realizedPnlCents", result.realizedPnlCents()));
    }

    private void tradeSettle(Context ctx) {
        ensureOwnedTrade(ctx, ctx.pathParam("id"));
        ConfirmRequest req = bodyOrNull(ctx, ConfirmRequest.class);
        TradeService.CloseResult result = trades.settle(ctx.pathParam("id"),
                req != null && Boolean.TRUE.equals(req.confirm()));
        resolveRecommendationForTrade(ctx.pathParam("id"), "SETTLED", result.realizedPnlCents());
        ctx.json(Map.of("trade", TradeView.of(result.trade()), "realizedPnlCents", result.realizedPnlCents()));
    }

    /** Best-effort: auto-resolve any recommendation tied to a trade that just closed. */
    private void resolveRecommendationForTrade(String tradeId, String status, Long pnlCents) {
        try { evaluations.resolveByTrade(tradeId, status, pnlCents); }
        catch (RuntimeException e) { log.warn("could not resolve recommendation for {}: {}", tradeId, e.toString()); }
    }

    private void tradeDelete(Context ctx) {
        ensureOwnedTrade(ctx, ctx.pathParam("id"));
        boolean confirm = "true".equalsIgnoreCase(ctx.queryParam("confirm"));
        TradeRecord t = trades.delete(ctx.pathParam("id"), confirm);
        ctx.json(Map.of("trade", TradeView.of(t)));
    }

    // ---- Broker (live trading; heavily gated) ----

    public record BrokerVerifyRequest(String code) {}

    public record BrokerOrderRequest(String accountIdKey, Map<String, Object> order,
                                     String previewId, String clientOrderId, String confirmText) {}

    private void brokerVerify(Context ctx) {
        BrokerVerifyRequest req = requireBody(bodyOrNull(ctx, BrokerVerifyRequest.class));
        broker.verifyConnect(req.code());
        ctx.json(broker.status());
    }

    private void brokerPreview(Context ctx) {
        BrokerOrderRequest req = requireBody(bodyOrNull(ctx, BrokerOrderRequest.class));
        BrokerService.PreviewOutcome outcome = broker.preview(req.accountIdKey(), req.order());
        ctx.json(Map.of("localId", outcome.localId(), "preview", outcome.preview(),
                "confirmTextRequired", BrokerService.CONFIRM_TEXT));
    }

    private void brokerPlace(Context ctx) {
        BrokerOrderRequest req = requireBody(bodyOrNull(ctx, BrokerOrderRequest.class));
        BrokerService.PlaceOutcome outcome = broker.place(req.accountIdKey(), req.order(),
                req.previewId(), req.clientOrderId(), req.confirmText());
        ctx.json(outcome);
    }

    private void brokerCancel(Context ctx) {
        String accountIdKey = ctx.queryParam("accountIdKey");
        if (accountIdKey == null || accountIdKey.isBlank()) throw new IllegalArgumentException("accountIdKey is required");
        broker.cancel(accountIdKey, ctx.pathParam("id"));
        ctx.json(Map.of("cancelRequested", true,
                "note", "Cancels are asynchronous and can lose the race to a fill — confirm via the orders list"));
    }

    // ---- Audit ----

    private void auditPage(Context ctx) {
        int page = intParam(ctx, "page", 0);
        // Per-user: show only the caller's account audit trail when auth is on; global when off.
        var entries = auth.enabled()
                ? audit.pageForAccount(currentAccount(ctx).id(), page, 50)
                : audit.page(page, 50);
        ctx.json(Map.of("entries", entries));
    }

    /**
     * Body parse that treats the literal JSON document "null" (and blank) as an absent body —
     * JavalinJackson throws a bare NPE ("readValue(...) must not be null") on it, which would
     * surface as a 500 for what is plainly client input.
     */
    private static <T> T bodyOrNull(Context ctx, Class<T> type) {
        String raw = ctx.body();
        if (raw == null || raw.isBlank() || raw.trim().equals("null")) return null;
        return ctx.bodyAsClass(type);
    }

    private static <T> T requireBody(T body) {
        if (body == null) throw new IllegalArgumentException("request body is required");
        return body;
    }

    private static int intParam(Context ctx, String name, int def) {
        String v = ctx.queryParam(name);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(name + " must be an integer"); }
    }
}
