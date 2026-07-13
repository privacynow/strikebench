package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.recommend.RiskBudgetPolicy;
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
    private final io.liftandshift.strikebench.market.EventService eventCalendar;
    private final io.liftandshift.strikebench.market.sim.SimulationSessions simSessions;
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
    io.liftandshift.strikebench.plan.PlanService planSvc;
    io.liftandshift.strikebench.plan.PlanEvidenceService planEvidence;
    io.liftandshift.strikebench.plan.PlanStrategyService planStrategy;
    io.liftandshift.strikebench.plan.PlanOutcomeService planOutcomes;
    io.liftandshift.strikebench.plan.PlanRehearsalService planRehearsals;
    io.liftandshift.strikebench.plan.PlanDecisionService planDecisions;
    io.liftandshift.strikebench.plan.PlanManagementService planManagement;
    private Javalin app;
    private Db db;   // owned pool; closed on stop()
    private io.liftandshift.strikebench.market.MarketDataEngine marketEngine;   // in-memory feed; warm + background refresh
    private io.liftandshift.strikebench.db.DataJobService dataJobs;             // Data Center background jobs
    private io.liftandshift.strikebench.db.DataCoverage dataCoverage;           // Data Center coverage matrix
    private io.liftandshift.strikebench.db.DataResetService dataReset;          // Data Center tiered wipe
    private io.liftandshift.strikebench.db.DataConnectorCatalog dataConnectors;
    private io.liftandshift.strikebench.db.DataSyncState dataSyncState;
    private io.liftandshift.strikebench.db.DataSyncScheduler dataSyncScheduler;
    private CboeProvider cboe;                                                  // for Data Center throttle display
    private io.liftandshift.strikebench.db.DatasetService datasets;             // observed + synthetic dataset registry
    private io.liftandshift.strikebench.sim.SimulationEngine simEngine;         // scenario previews + dataset runs
    private io.liftandshift.strikebench.sim.PathEnsembleService pathEnsembles;   // one lane-aware path source
    private java.util.concurrent.ScheduledExecutorService streamScheduler;     // pushes SSE market frames
    private java.util.concurrent.ScheduledExecutorService snapshotScheduler;   // started iff SNAPSHOT_ENABLED
    private final String startedAt = java.time.Instant.now().toString();
    private final java.util.concurrent.atomic.AtomicLong worldRevision = new java.util.concurrent.atomic.AtomicLong();

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
        this.eventCalendar = new io.liftandshift.strikebench.market.EventService(market, clock);
        this.simSessions = new io.liftandshift.strikebench.market.sim.SimulationSessions(db, events);
        market.setWorldResolver(id -> simSessions.resolveForData(id));
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
        var providerBudget = new io.liftandshift.strikebench.db.ProviderRequestBudget(db, clock);
        ETradeProvider etrade = new ETradeProvider(cfg, secretsStore, clock);
        final CboeProvider[] cboeRef = new CboeProvider[1]; // captured so the Data Center can show throttle state
        final io.liftandshift.strikebench.market.providers.YahooFinanceProvider[] yahooRef =
                new io.liftandshift.strikebench.market.providers.YahooFinanceProvider[1]; // captured for event wiring

        // Observed providers only. Fixtures are mounted separately behind the explicit Demo lane;
        // they are never a fallback for an observed quote, chain, candle, headline, or rate.
        if (!cfg.fixturesOnly()) {
            if (etrade.configured()) providers.add(etrade);
            cboeRef[0] = new CboeProvider(cfg);
            providers.add(cboeRef[0]);
            if (!cfg.polygonApiKey().isBlank()) providers.add(new PolygonProvider(cfg, providerBudget));
            if (!cfg.alphaVantageApiKey().isBlank()) providers.add(new AlphaVantageProvider(cfg, providerBudget));
            // Yahoo keyless equity candles — PERSONAL/LOCAL-CLONE opt-in only (see AppConfig.yahooEnabled).
            // Ahead of Stooq (which is bot-blocked for us) so a self-hoster gets real underlying history.
            if (cfg.yahooEnabled() && cfg.yahooAutomationPermissionConfirmed()) {
                yahooRef[0] = new io.liftandshift.strikebench.market.providers.YahooFinanceProvider(cfg, providerBudget);
                providers.add(yahooRef[0]);
            }
            if (cfg.stooqEnabled()) providers.add(new StooqProvider(cfg));
            if (!cfg.newsRssBaseUrl().isBlank()) newsProviders.add(new NewsRssProvider(cfg)); // keyless headlines
            newsProviders.add(new EdgarProvider(cfg));                                          // SEC filings
            if (!cfg.fredApiKey().isBlank()) ratesProviders.add(new FredProvider(cfg));
            ratesProviders.add(new TreasuryRatesProvider(cfg));
        } else {
            providers.add(fixture);
            newsProviders.add(fixture);
            ratesProviders.add(fixture);
        }

        // Persisted daily bars (Data Center backfills / snapshots / CSV ingest / synthetic datasets)
        // feed the read path; the active-dataset switch decides which dataset serves.
        io.liftandshift.strikebench.db.DatasetService datasetSvc = new io.liftandshift.strikebench.db.DatasetService(db, clock);
        MarketDataService market = new MarketDataService(providers, newsProviders, ratesProviders,
                new io.liftandshift.strikebench.db.StoredCandleStore(db));
        market.setDemoSources(fixture, fixture, fixture);
        AuditLog audit = new AuditLog(db, clock);
        AccountService accounts = new AccountService(db, cfg, audit, clock);
        MarketDataMarks marksSource = new MarketDataMarks(market, cfg.fixturesOnly());
        TradeService trades = new TradeService(db, cfg, marksSource, audit, clock);
        PositionsService positions = new PositionsService(db, marksSource, audit, clock, cfg.fixturesOnly());
        RecommendationEngine engine = new RecommendationEngine(market, clock)
                .withFees(cfg.feePerContractCents(), cfg.feePerOrderCents());
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
            if (!cfg.polygonApiKey().isBlank()) historical.add(new PolygonProvider(cfg, providerBudget));
        }
        Backtester backtester = new Backtester(market, historical, cfg, db, clock);
        io.liftandshift.strikebench.market.UniverseService universe = new io.liftandshift.strikebench.market.UniverseService(db, cfg, clock);
        io.liftandshift.strikebench.market.SnapshotService snapshots = new io.liftandshift.strikebench.market.SnapshotService(market, universe, db, clock);
        io.liftandshift.strikebench.auth.AuthService auth = buildAuth(cfg, db, clock);
        io.liftandshift.strikebench.eval.EvaluationService evaluations = new io.liftandshift.strikebench.eval.EvaluationService(market, engine, db, clock)
                .withFees(cfg.feePerContractCents(), cfg.feePerOrderCents()); // decision EV matches the REAL commission
        auto.withEvaluationService(evaluations);
        ApiServer server = new ApiServer(cfg, clock, market, audit, accounts, trades, engine, auto, broker, backtester, positions, universe, snapshots, auth, evaluations);
        server.db = db;
        server.simSessions.attachDb(db);
        // Data Center services (need db, which is set above; reuse the constructor-built engine).
        var backfill = new io.liftandshift.strikebench.db.UnderlyingBackfill(market, db, clock);
        server.dataConnectors = new io.liftandshift.strikebench.db.DataConnectorCatalog(cfg, providerBudget);
        server.dataSyncState = new io.liftandshift.strikebench.db.DataSyncState(db, clock);
        server.dataJobs = new io.liftandshift.strikebench.db.DataJobService(db, clock, server.marketEngine,
                snapshots, backfill, universe, cfg, server.dataConnectors);
        server.dataSyncScheduler = new io.liftandshift.strikebench.db.DataSyncScheduler(
                cfg, clock, server.dataSyncState, server.dataJobs);
        server.dataCoverage = new io.liftandshift.strikebench.db.DataCoverage(db);
        server.dataReset = new io.liftandshift.strikebench.db.DataResetService(db, accounts);
        server.cboe = cboeRef[0];
        server.marketEngine.setSnapshotStore(new io.liftandshift.strikebench.db.MarketSnapshotStore(db));
        server.datasets = datasetSvc;
        server.pathEnsembles = new io.liftandshift.strikebench.sim.PathEnsembleService(market, clock);
        server.simEngine = new io.liftandshift.strikebench.sim.SimulationEngine(
                market, datasetSvc, db, clock, server.pathEnsembles);
        // Workspace continuity + the event bus: services announce, /api/events streams to the browser.
        server.workspaceSvc = new io.liftandshift.strikebench.db.WorkspaceService(db, clock);
        server.workspaceSvc.setEvents(server.events);
        server.planSvc = new io.liftandshift.strikebench.plan.PlanService(db, clock);
        server.planSvc.setEvents(server.events);
        server.planEvidence = new io.liftandshift.strikebench.plan.PlanEvidenceService(db,
                new io.liftandshift.strikebench.research.ResearchQuestionEngine(market, clock), clock);
        server.planStrategy = new io.liftandshift.strikebench.plan.PlanStrategyService(db, clock);
        server.planOutcomes = new io.liftandshift.strikebench.plan.PlanOutcomeService(db, clock);
        server.planRehearsals = new io.liftandshift.strikebench.plan.PlanRehearsalService(db, clock,
                server.planOutcomes, server.simSessions, accounts);
        server.planDecisions = new io.liftandshift.strikebench.plan.PlanDecisionService(db, clock);
        server.planManagement = new io.liftandshift.strikebench.plan.PlanManagementService(db, clock);
        server.dataJobs.setEvents(server.events);
        server.dataJobs.setDataChangedHook(server::invalidateHistoricalViews);
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
                boolean changed = !jarChangedHint().isEmpty();
                log.error("A request could not be served{}", changed ? " because the running build changed; restart StrikeBench" : "");
                log.debug("Request failure detail", error);
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
            c.routes.after("/api/*", ctx -> {
                Object t0 = ctx.attribute("reqStartNanos");
                if (t0 instanceof Long tl) recordLatency(ctx.path(), (System.nanoTime() - tl) / 1000);
            });
            c.routes.before("/api/*", ctx -> {
                ctx.attribute("reqStartNanos", System.nanoTime());
                apiRequests.incrementAndGet();
                if (cfg.fixturesOnly()) return;
                String path = ctx.path();
                if (path.equals("/api/health") || path.equals("/api/metrics")
                        || path.equals("/api/events") || path.equals("/api/market/stream")) return;
                // X-Forwarded-For is client-controlled: honor it ONLY when the direct peer is a
                // trusted proxy (loopback, i.e. our nginx on the same box, or TRUSTED_PROXY=true).
                String remote = ctx.ip();
                boolean trustedProxy = cfg.trustedProxy()
                        || "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote);
                String xff = ctx.header("X-Forwarded-For");
                String ip = trustedProxy && xff != null ? xff.split(",")[0].trim() : remote;
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
            c.routes.get("/api/universe", ctx ->
                ctx.json(universeViewFor(worldParam(activeWorld(ctx)), ownerId(ctx))));
            c.routes.put("/api/universe", this::universeSelect);
            c.routes.get("/api/quotes", this::quotesBatch);
            c.routes.get("/api/sparklines", this::sparklines);
            c.routes.get("/api/market/engine", ctx -> ctx.json(marketEngine.status())); // Data Center: engine health
            c.routes.sse("/api/market/stream", this::marketStream);                     // live-ish quote deltas from memory
            c.routes.sse("/api/events", this::eventStream);                             // typed workspace events (jobs/datasets/providers)

            // ---- Workspace continuity: the client-owned UX state blob, versioned per user ----
            c.routes.get("/api/workspace", this::workspaceGet);
            c.routes.put("/api/workspace", this::workspacePut);

            // ---- Plans: server-owned journey facts; workspace JSON keeps presentation only ----
            c.routes.get("/api/plans", this::plansList);
            c.routes.post("/api/plans", this::planCreate);
            c.routes.get("/api/plans/portfolio", this::plansPortfolio);
            c.routes.get("/api/plans/{id}", this::planGet);
            c.routes.put("/api/plans/{id}/context", this::planContextPut);
            c.routes.put("/api/plans/{id}/intent", this::planIntentPut);
            c.routes.put("/api/plans/{id}/stage", this::planStagePut);
            c.routes.put("/api/plans/{id}/open", this::planOpenPut);
            c.routes.post("/api/plans/{id}/archive", this::planArchive);
            c.routes.get("/api/plans/{id}/evidence/latest", this::planEvidenceLatest);
            c.routes.post("/api/plans/{id}/evidence/study", this::planEvidenceStudy);
            c.routes.get("/api/plans/{id}/strategy/latest", this::planStrategyLatest);
            c.routes.post("/api/plans/{id}/strategy/run", this::planStrategyRun);
            c.routes.post("/api/plans/{id}/strategy/fit", this::planStrategyFit);
            c.routes.post("/api/plans/{id}/strategy/custom", this::planStrategyCustom);
            c.routes.put("/api/plans/{id}/strategy/select", this::planStrategySelect);
            c.routes.get("/api/plans/{id}/scout/latest", this::planScoutLatest);
            c.routes.post("/api/plans/{id}/scout/run", this::planScoutRun);
            c.routes.post("/api/plans/{id}/scout/spawn", this::planScoutSpawn);
            c.routes.get("/api/plans/{id}/outcomes/latest", this::planOutcomesLatest);
            c.routes.post("/api/plans/{id}/outcomes/ensemble", this::planEnsembleRun);
            c.routes.post("/api/plans/{id}/outcomes/run", this::planOutcomeRun);
            c.routes.post("/api/plans/{id}/outcomes/compare", this::planOutcomeCompare);
            c.routes.post("/api/plans/{id}/outcomes/backtest", this::planBacktestRun);
            c.routes.get("/api/plans/{id}/outcomes/backtests/{backtestId}", this::planBacktestGet);
            c.routes.get("/api/plans/{id}/rehearsals", this::planRehearsalsList);
            c.routes.post("/api/plans/{id}/rehearsals", this::planRehearsalCreate);
            c.routes.get("/api/plans/{id}/decision/latest", this::planDecisionLatest);
            c.routes.post("/api/plans/{id}/decision/preview", this::planDecisionPreview);
            c.routes.post("/api/plans/{id}/decision/trade", this::planDecisionTrade);
            c.routes.post("/api/plans/{id}/decision/cash", this::planDecisionCash);
            c.routes.get("/api/plans/{id}/manage", this::planManageGet);
            c.routes.post("/api/plans/{id}/manage/refresh", this::planManageRefresh);
            c.routes.post("/api/plans/{id}/manage/unwind", this::planManageUnwind);
            c.routes.post("/api/plans/{id}/manage/settle", this::planManageSettle);
            c.routes.post("/api/plans/{id}/manage/roll", this::planManageRoll);
            c.routes.post("/api/plans/{id}/manage/void", this::planManageVoid);
            c.routes.post("/api/plans/{id}/manage/review", this::planManageReview);

            // ---- Data Center ----
            c.routes.get("/api/data/overview", this::dataOverview);
            c.routes.get("/api/data/coverage", this::dataCoverageRoute);
            c.routes.get("/api/data/sources", this::dataSources);
            c.routes.get("/api/data/sync", this::dataSyncStatus);
            c.routes.post("/api/data/sync/plan", this::dataSyncPlan);
            c.routes.put("/api/data/sync/schedule", this::dataSyncSchedulePut);
            c.routes.post("/api/data/import/underlying", this::dataUnderlyingImport);
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
            // Pure outcome work has ONE contract: POST /api/evaluate. Dataset generation is a
            // separate command because it persists durable analysis material.
            c.routes.post("/api/datasets/generate", this::simDataset);

            c.routes.get("/api/account", this::account);
            c.routes.post("/api/account/reset", this::accountReset);

            // Historical event studies live under Research. There is no legacy Lab destination:
            // every capability has one canonical owner and route.
            io.javalin.http.Handler questionsHandler = ctx ->
                    ctx.json(Map.of("questions", new io.liftandshift.strikebench.research.ResearchQuestionEngine(market, clock).catalog()));
            io.javalin.http.Handler studyHandler = ctx ->
                    ctx.json(new io.liftandshift.strikebench.research.ResearchQuestionEngine(market, clock)
                            .run(requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.research.ResearchQuestionEngine.RunRequest.class)),
                                    analysisCtx(ctx), worldParam(activeWorld(ctx))));
            c.routes.get("/api/research/questions", questionsHandler);
            c.routes.post("/api/research/event-studies", studyHandler);
            c.routes.post("/api/research/notes", this::noteCreate);
            c.routes.get("/api/research/notes", this::noteList);
            c.routes.get("/api/research/notes/{id}", this::noteGet);
            c.routes.put("/api/research/notes/{id}", this::noteUpdate);
            c.routes.delete("/api/research/notes/{id}", this::noteDelete);
            c.routes.get("/api/research/{symbol}", this::research);
            c.routes.get("/api/research/{symbol}/expirations", this::expirations);
            c.routes.get("/api/research/{symbol}/chain", this::chain);
            c.routes.get("/api/research/{symbol}/history", this::history);
            c.routes.get("/api/research/{symbol}/news", this::news);
            c.routes.get("/api/lookup", this::lookup);

            // The single source of truth for strategy families and concrete constructions.
            // Frontend surfaces retain only the functions that build legs; identity, wording,
            // ordering and surface eligibility all come from this registry.
            c.routes.get("/api/strategies", ctx -> ctx.json(Map.of(
                    "families",
                    java.util.Arrays.stream(io.liftandshift.strikebench.strategy.StrategyFamily.values())
                            .map(Enum::name).toList(),
                    "catalog", io.liftandshift.strikebench.strategy.StrategyCatalog.families(),
                    "templates", io.liftandshift.strikebench.strategy.StrategyCatalog.templates())));
            c.routes.get("/api/welcome/teaching-example", this::welcomeTeachingExample);
            c.routes.post("/api/research/scout", this::researchScout);
            c.routes.post("/api/research/{symbol}/intent-ladder", this::researchIntentLadder);
            c.routes.post("/api/evaluate", this::evaluate);
            c.routes.post("/api/opportunities", this::opportunities);
            c.routes.post("/api/optimize", this::optimize);
            c.routes.post("/api/builder/exposure", ctx ->
                    ctx.json(new io.liftandshift.strikebench.strategy.ExposureSizer(market)
                            .size(requireBody(bodyOrNull(ctx,
                                    io.liftandshift.strikebench.strategy.ExposureSizer.Request.class)),
                                    worldParam(activeWorld(ctx)))));
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

            // ---- The simulated market (Block S): per-user runtime switch, never a boot flag ----
            c.routes.get("/api/world", ctx -> ctx.json(Map.of(
                    "world", activeWorld(ctx), "revision", worldRevision.get(), "epoch", startedAt)));
            c.routes.put("/api/world", ctx -> {
                var body = requireBody(bodyOrNull(ctx, java.util.Map.class));
                String w = String.valueOf(body.getOrDefault("world", "observed"));
                String owner = ownerId(ctx);
                if (cfg.fixturesOnly() && "observed".equals(w)) {
                    throw new IllegalStateException("Observed market is unavailable in this explicit demo build");
                }
                if (!"observed".equals(w) && !"demo".equals(w)) {
                    simSessions.ensureReady(w, owner);
                    simSessions.getOrRestore(w, owner)
                            .orElseThrow(() -> new java.util.NoSuchElementException("no such simulated session: " + w));
                }
                // Resolve the full target before changing either server-side selector. If this
                // cannot be built, the caller remains wholly in the old market.
                Object bootstrap = universeViewFor("observed".equals(w) ? null : w, owner);
                // ONE MARKET MODE AT A TIME (M10 + holistic review P0): leaving a sim world drops
                // any active SYNTHETIC dataset ('back to the real market' must never land on a
                // forgotten scenario), and ENTERING a world does too (a world layered on a scenario
                // dataset would silently blend two generated markets). setActive publishes
                // dataset.selected so the scenario banner clears everywhere.
                boolean datasetReset = !"observed".equals(datasets.activeId(owner));
                String now = clock.instant().toString();
                db.tx(conn -> {
                    Db.execOn(conn, "INSERT INTO settings(k,v,updated_at) VALUES (?,?,?) "
                                    + "ON CONFLICT (k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at",
                            "active_world:" + owner, w, now);
                    if (datasetReset) {
                        String datasetOwner = owner == null || owner.isBlank() ? "local" : owner;
                        Db.execOn(conn, "INSERT INTO settings(k,v,updated_at) VALUES (?,?,?) "
                                        + "ON CONFLICT (k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at",
                                "active_dataset:" + datasetOwner, "observed", now);
                    }
                    return null;
                });
                if (datasetReset) datasets.invalidateActiveCache();
                market.invalidateAll();
                long revision = worldRevision.incrementAndGet();
                String eventOwner = owner == null ? "local" : owner;
                if (datasetReset) events.publish("dataset.selected", Map.of(
                        "active", "observed", "user", eventOwner));
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("world", w);
                event.put("user", eventOwner);
                event.put("revision", revision);
                event.put("epoch", startedAt);
                event.put("universe", bootstrap);
                events.publish("world.selected", event);
                ctx.json(Map.of("world", w, "datasetReset", datasetReset,
                        "universe", bootstrap, "revision", revision, "epoch", startedAt));
            });
            c.routes.get("/api/sim/market", ctx -> ctx.json(Map.of("sessions", simSessions.list(ownerId(ctx)))));
            c.routes.get("/api/sim/market/{id}/anchors", ctx ->
                    ctx.json(simSessions.anchors(ctx.pathParam("id"), ownerId(ctx)))); // F8: provenance detail
            c.routes.post("/api/sim/market", this::simMarketCreate);
            c.routes.post("/api/sim/market/{id}/start", ctx -> {
                String id = ctx.pathParam("id");
                String owner = ownerId(ctx);
                simSessions.start(id, owner);
                events.publish("world.control", Map.of("world", id,
                        "user", owner == null ? "local" : owner, "running", true));
                ctx.json(Map.of("ok", true, "running", true));
            });
            c.routes.post("/api/sim/market/{id}/pause", ctx -> {
                String id = ctx.pathParam("id");
                String owner = ownerId(ctx);
                simSessions.pause(id, owner);
                events.publish("world.control", Map.of("world", id,
                        "user", owner == null ? "local" : owner, "running", false));
                ctx.json(Map.of("ok", true, "running", false));
            });
            c.routes.post("/api/sim/market/{id}/step", ctx -> { simSessions.step(ctx.pathParam("id"), ownerId(ctx)); ctx.json(Map.of("ok", true)); });
            c.routes.post("/api/sim/market/{id}/speed", ctx -> {
                var b = requireBody(bodyOrNull(ctx, java.util.Map.class));
                String id = ctx.pathParam("id");
                String owner = ownerId(ctx);
                double speed = Double.parseDouble(String.valueOf(b.get("speed")));
                simSessions.setSpeed(id, owner, speed);
                events.publish("world.control", Map.of("world", id,
                        "user", owner == null ? "local" : owner, "speed", speed));
                ctx.json(Map.of("ok", true, "speed", speed));
            });
            c.routes.post("/api/sim/market/{id}/event", ctx -> {
                var b = requireBody(bodyOrNull(ctx, java.util.Map.class));
                if (b.containsKey("movePct") && b.containsKey("symbol")) {
                    simSessions.injectMove(ctx.pathParam("id"), ownerId(ctx),
                            String.valueOf(b.get("symbol")), Double.parseDouble(String.valueOf(b.get("movePct"))));
                }
                if (b.containsKey("volShift")) {
                    simSessions.injectVol(ctx.pathParam("id"), ownerId(ctx),
                            Double.parseDouble(String.valueOf(b.get("volShift"))));
                }
                ctx.json(Map.of("ok", true));
            });
            c.routes.delete("/api/sim/market/{id}", ctx -> {
                String id = ctx.pathParam("id");
                String owner = ownerId(ctx);
                boolean wasActive = id.equals(activeWorld(ctx));
                var rehearsalFinish = planRehearsals.finishHook(owner, id);
                simSessions.finish(id, owner, (conn, worldId, world) -> {
                    if (rehearsalFinish != null) rehearsalFinish.beforeFinish(conn, worldId, world);
                    planSvc.closeFinishedWorldPlansOn(conn, owner, worldId);
                });
                // Finishing the ACTIVE world IS a world transition: flip the setting, drop any
                // synthetic dataset, and PUBLISH it — every tab (including the caller's own SSE)
                // reconciles through the same event as an explicit return-to-real (review P0 #2).
                if (wasActive) {
                    String baseline = cfg.fixturesOnly() ? "demo" : "observed";
                    Object bootstrap = universeViewFor(worldParam(baseline), owner);
                    boolean datasetReset = !io.liftandshift.strikebench.db.DatasetService.OBSERVED
                            .equals(datasets.activeId(owner));
                    String now = clock.instant().toString();
                    db.tx(conn -> {
                        Db.execOn(conn, "INSERT INTO settings(k,v,updated_at) VALUES (?,?,?) "
                                        + "ON CONFLICT (k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at",
                                "active_world:" + owner, baseline, now);
                        if (datasetReset) {
                            String datasetOwner = owner == null || owner.isBlank() ? "local" : owner;
                            Db.execOn(conn, "INSERT INTO settings(k,v,updated_at) VALUES (?,?,?) "
                                            + "ON CONFLICT (k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at",
                                    "active_dataset:" + datasetOwner,
                                    io.liftandshift.strikebench.db.DatasetService.OBSERVED, now);
                        }
                        return null;
                    });
                    if (datasetReset) datasets.invalidateActiveCache();
                    market.invalidateAll();
                    long revision = worldRevision.incrementAndGet();
                    String eventOwner = owner == null ? "local" : owner;
                    if (datasetReset) events.publish("dataset.selected", Map.of(
                            "active", "observed", "user", eventOwner));
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("world", baseline);
                    event.put("user", eventOwner);
                    event.put("revision", revision);
                    event.put("epoch", startedAt);
                    event.put("universe", bootstrap);
                    events.publish("world.selected", event);
                    ctx.json(Map.of("ok", true, "worldReset", true, "world", baseline,
                            "datasetReset", datasetReset, "revision", revision,
                            "epoch", startedAt, "universe", bootstrap));
                    return;
                }
                ctx.json(Map.of("ok", true, "worldReset", false));
            });
            c.routes.get("/api/sim/market/{id}/report", this::simMarketReport);

            c.routes.post("/api/trades/preview", this::tradePreview);
            c.routes.post("/api/trades/external", ctx -> {
                Account acct = currentAccount(ctx);
                TradeOpenRequest body = bodyOrNull(ctx, TradeOpenRequest.class);
                var req = toOpenRequest(body, acct);
                var meta = new io.liftandshift.strikebench.paper.TradeService.ExternalMeta(
                        body.executedAt(), body.broker(), body.orderRef(), Boolean.TRUE.equals(body.historical()));
                ctx.status(201).json(trades.createExternal(req, meta));
            });
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
            c.routes.post("/api/positions/preview", this::positionsPreview);
            c.routes.post("/api/positions/buy", this::positionsBuy);
            c.routes.post("/api/positions/sell", this::positionsSell);
            c.routes.get("/api/portfolio/summary", this::portfolioSummary);
            c.routes.get("/api/portfolio/heat", ctx ->
                    ctx.json(trades.portfolioHeat(currentAccount(ctx).id())));
            c.routes.get("/api/account/risk-context", ctx ->
                    ctx.json(io.liftandshift.strikebench.paper.AccountRiskContext.load(db, ownerId(ctx))));
            c.routes.put("/api/account/risk-context", ctx -> {
                var rc = requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.paper.AccountRiskContext.class));
                io.liftandshift.strikebench.paper.AccountRiskContext.save(db, ownerId(ctx), rc);
                ctx.json(rc);
            });
            c.routes.get("/api/risk-budget", this::riskBudget);
            c.routes.get("/api/portfolio/greeks", ctx ->
                    ctx.json(trades.portfolioGreeks(currentAccount(ctx).id())));

            // Historical replays are Plan-owned. The report id remains readable so a Plan can
            // restore its full normalized summary plus the existing detailed replay artifact.

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
                boolean changed = !jarChangedHint().isEmpty();
                log.error("Request failed on {} {}{}", ctx.method(), ctx.path(),
                        changed ? " because the running build changed; restart StrikeBench" : "");
                log.debug("Request failure detail on " + ctx.method() + " " + ctx.path(), e);
                String detail = changed
                        ? "StrikeBench changed while it was running. Restart it and try again."
                        : "The request failed unexpectedly. Retry it, then check Data health if the problem persists.";
                ctx.status(500).json(Map.of("error", "internal", "detail", detail));
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
        if (dataSyncScheduler != null) dataSyncScheduler.start();
        log.info("StrikeBench listening on http://localhost:{} (market mode: {})",
                app.port(), cfg.fixturesOnly() ? "demo" : "observed");
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
            catch (RuntimeException e) {
                log.warn("Scheduled market snapshot did not complete; the next run will retry");
                log.debug("Scheduled market-snapshot detail", e);
            }
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
            log.warn("Startup request checks did not complete; restart if requests fail");
            log.debug("Startup request-check detail", e);
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
        if (dataSyncScheduler != null) dataSyncScheduler.close();
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

    /**
     * The paper account for the current request's user (the single local account when auth is off).
     * Inside a simulated world the SIMULATION account is the account — the real practice account is
     * never visible from a sim session and vice versa, so neither can pollute the other.
     */
    private Account currentAccount(Context ctx) {
        String w = activeWorld(ctx);
        if ("demo".equals(w)) return accounts.getOrCreateDemoForUser(auth.currentUserId(ctx));
        if (!"observed".equals(w)) return accounts.getOrCreateForWorld(w, "Simulation account");
        return accounts.getOrCreateDefaultForUser(auth.currentUserId(ctx));
    }

    /** The persistence owner id for the current user (null = local/anonymous). */
    /**
     * The caller's explicit analysis context: identity + their active dataset. Built per request
     * and PASSED to engines — never stored in a ThreadLocal (virtual-thread fan-outs would lose it,
     * and background work must always read observed).
     */
    /** Historical replay works in Observed/Scenario and explicit Demo, but never inside a
     *  moving simulated exchange (that lane has its own verification surface). */
    private void requireObservedLane(Context ctx, String what) {
        String world = activeWorld(ctx);
        if (!"observed".equals(world) && !"demo".equals(world)) {
            throw new IllegalStateException(what + " \u2014 it has no meaning inside a simulated session. "
                    + "Return to the baseline market to run it; your simulated session keeps running.");
        }
    }

    /** Normalizes the route-level world to the service-layer convention (null = observed). */
    private static String worldParam(String world) {
        return world == null || "observed".equals(world) ? null : world;
    }

    /** The caller's active market world: 'observed' unless they switched to a sim session. */
    private String activeWorld(Context ctx) {
        return activeWorldFor(ownerId(ctx));
    }

    private String activeWorldFor(String owner) {
        var rows = db.query("SELECT v FROM settings WHERE k=?", r -> r.str("v"), "active_world:" + owner);
        String fallback = cfg.fixturesOnly() ? "demo" : "observed";
        String w = rows.isEmpty() || rows.getFirst() == null || rows.getFirst().isBlank() ? fallback : rows.getFirst();
        if (cfg.fixturesOnly() && "observed".equals(w)) return "demo";
        if (!"observed".equals(w) && !"demo".equals(w)
                && simSessions.getOrRestore(w, owner).isEmpty()) return fallback; // fail-safe
        return w;
    }

    public record SimMarketCreate(String name, java.util.Map<String, Double> symbols,
                                  java.util.Map<String, Double> spots, String scenario,
                                  Double volAnnual, Long seed, String startSimTime, Double speed,
                                  String sectorKey /* include this curated sector as the background tier */,
                                  Boolean includePositions /* default true: held symbols ride along */,
                                  Boolean allowFictional /* F3: $100 demo instruments only on explicit request */) {}

    private void simMarketCreate(Context ctx) {
        SimMarketCreate b = requireBody(bodyOrNull(ctx, SimMarketCreate.class));
        String owner = ownerId(ctx);
        // F1: the ACTIVE-SESSION CAP is checked before ANY resolution work — a user at the cap
        // must hear "no" in milliseconds, not after seconds of provider traffic.
        simSessions.ensureCapacity(owner);
        String start = b.startSimTime() != null && !b.startSimTime().isBlank() ? b.startSimTime()
                : nextSimOpen().toString();
        boolean allowFictional = Boolean.TRUE.equals(b.allowFictional());
        // ---- WORLD UNIVERSE BUILDER (holistic review Phase 1) ----
        java.util.LinkedHashMap<String, Double> active = new java.util.LinkedHashMap<>();
        if (b.symbols() != null) {
            b.symbols().forEach((k, v) -> active.put(k.trim().toUpperCase(Locale.ROOT), v == null ? 1.0 : v));
        }
        if (b.includePositions() == null || b.includePositions()) {
            try {
                var realAcct = accounts.getOrCreateDefaultForUser(auth.currentUserId(ctx));
                for (var pos : positions.list(realAcct.id())) {
                    if (pos.shares() > 0) active.putIfAbsent(pos.symbol(), 1.0);
                }
            } catch (RuntimeException ignored) { /* no positions — the tiers below still work */ }
        }
        active.putIfAbsent("SPY", 1.0);
        active.putIfAbsent("QQQ", 1.0);
        java.util.LinkedHashMap<String, Double> all = new java.util.LinkedHashMap<>(active);
        List<String> trimmed = new ArrayList<>();
        if (b.sectorKey() != null && !b.sectorKey().isBlank()) {
            var sector = io.liftandshift.strikebench.market.Universes.SECTORS
                    .get(b.sectorKey().trim().toUpperCase(Locale.ROOT));
            if (sector == null) throw new IllegalArgumentException("unknown sector: " + b.sectorKey());
            for (String sym : sector.symbols()) {
                String u = sym.trim().toUpperCase(Locale.ROOT);
                if (all.containsKey(u)) continue;
                if (all.size() >= io.liftandshift.strikebench.market.sim.SimulationSessions.MAX_SYMBOLS) {
                    trimmed.add(u); continue; // disclosed, never silent
                }
                all.put(u, 1.0);
            }
        }
        if (all.isEmpty()) throw new IllegalArgumentException("a simulated world needs at least one symbol");

        // ---- ANCHOR RESOLVER ----
        // F1: the request path does ONLY instant work. Fixture mode is local data, so everything
        // (anchors + calibration) resolves inline. Against LIVE providers the request reads engine
        // MEMORY only; cold active-tier symbols go to a governed BACKGROUND job that enriches the
        // world before it starts (never a blocking provider loop under the Create button).
        // F3: fictional status is never inferred — an unrecognized, unresolvable symbol becomes a
        // $100 demo instrument ONLY when the request explicitly allows it; otherwise excluded.
        java.util.Map<String, Double> spots = new java.util.LinkedHashMap<>(
                b.spots() == null ? java.util.Map.of() : b.spots());
        java.util.Map<String, String> spotBasis = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> symVols = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> symIvs = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> calibrationBasis = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> anchors = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        java.util.Set<String> curated = new java.util.HashSet<>();
        io.liftandshift.strikebench.market.Universes.SECTORS.values()
                .forEach(sec -> sec.symbols().forEach(x -> curated.add(x.toUpperCase(Locale.ROOT))));
        boolean cheapData = cfg.fixturesOnly();
        java.util.Map<String, io.liftandshift.strikebench.market.MarketDataEngine.MarketSnapshot> snaps
                = new java.util.HashMap<>();
        if (cheapData) {
            // Local fixtures: one instant batch primes everything.
            List<String> needQuote = all.keySet().stream().filter(sym -> !spots.containsKey(sym)).toList();
            if (!needQuote.isEmpty()) {
                for (var snap : marketEngine.quotes(needQuote)) snaps.put(snap.symbol(), snap);
            }
        } else {
            // Live: MEMORY ONLY on the request path — zero provider calls under the button.
            for (String sym : all.keySet()) {
                if (spots.containsKey(sym)) continue;
                marketEngine.peek(sym).ifPresent(snap -> snaps.put(snap.symbol(), snap));
            }
        }
        long nowMs = clock.millis();
        java.time.LocalDate today = java.time.LocalDate.now(clock);
        for (String sym : new ArrayList<>(all.keySet())) {
            boolean isActive = active.containsKey(sym);
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("symbol", sym);
            a.put("tier", isActive ? "active" : "background");
            if (spots.containsKey(sym)) {
                a.put("price", spots.get(sym));
                a.put("source", "user");
                a.put("basis", "user-set start price");
                spotBasis.put(sym, "user-set start price");
                anchors.add(a);
                continue;
            }
            var snap = snaps.get(sym);
            var mark = snap == null || snap.last() == null ? null : snap.last();
            boolean realQuote = snap != null && switch (snap.freshness()) {
                case REALTIME, DELAYED, EOD, STALE -> true;
                default -> false;
            };
            if (mark != null && mark.signum() > 0) {
                spots.put(sym, mark.doubleValue());
                String basis = realQuote
                        ? "anchored to the real market's last " + snap.freshness().name().toLowerCase() + " price"
                        : "anchored to a built-in DEMO quote — not a live price";
                spotBasis.put(sym, basis);
                a.put("price", mark.doubleValue());
                a.put("source", snap.source());
                a.put("freshness", snap.freshness().name());
                a.put("sourceAsOf", snap.asOfEpochMs());
                a.put("ageSeconds", Math.max(0, (nowMs - snap.asOfEpochMs()) / 1000));
                a.put("bid", snap.bid() == null ? null : snap.bid().toPlainString());
                a.put("ask", snap.ask() == null ? null : snap.ask().toPlainString());
                a.put("prevClose", snap.prevClose() == null ? null : snap.prevClose().toPlainString());
                a.put("basis", basis);
                anchors.add(a);
            } else if (!cheapData && isActive && curated.contains(sym)) {
                // Cold but real: the background resolver will fetch it (governed) and enrich the
                // world before it starts. It is NOT in the initial world.
                all.remove(sym);
                pending.add(sym);
                a.put("pending", true);
                a.put("basis", "resolving in the background — will join before the session starts");
                spotBasis.put(sym, "RESOLVING — joins when its price arrives");
            } else if (curated.contains(sym)) {
                all.remove(sym);
                a.put("excluded", true);
                a.put("reason", isActive
                        ? "no price available from any source right now — excluded rather than invented"
                        : "not warm in the engine — background symbols never trigger provider calls; excluded");
                excluded.add(a);
                spotBasis.put(sym, "EXCLUDED — " + a.get("reason"));
            } else if (allowFictional) {
                spots.put(sym, 100.0);
                spotBasis.put(sym, "made-up ticker — starts at $100 (you allowed fictional symbols)");
                a.put("price", 100.0);
                a.put("source", "synthetic");
                a.put("basis", "made-up ticker — starts at $100 (explicitly allowed)");
                anchors.add(a);
            } else {
                // F3: never INFER that an unknown symbol is fictional.
                all.remove(sym);
                a.put("excluded", true);
                a.put("reason", "unrecognized ticker with no price — enable 'made-up tickers start at $100' "
                        + "or set an explicit start price to include it");
                excluded.add(a);
                spotBasis.put(sym, "EXCLUDED — " + a.get("reason"));
            }
        }
        if (all.isEmpty() && pending.isEmpty()) {
            throw new IllegalStateException("No symbol in this world could be priced — check the data "
                    + "sources on the Data screen, set explicit start prices, or allow made-up tickers.");
        }
        // ---- Calibration: inline only when data is LOCAL; live calibration is background work. ----
        if (cheapData) {
            calibrateInto(all, active, spots, spotBasis, symVols, symIvs, calibrationBasis, anchors, today);
        }
        java.util.Map<String, Double> spotsInWorld = new java.util.LinkedHashMap<>();
        all.keySet().forEach(sym -> { if (spots.containsKey(sym)) spotsInWorld.put(sym, spots.get(sym)); });
        long seed = b.seed() != null ? b.seed() : Math.floorMod(clock.millis(), 1_000_000L);
        var worldCfg = new io.liftandshift.strikebench.market.sim.SimulatedWorld.Config(null,
                b.name() == null ? "Simulated session" : b.name(),
                all, spotsInWorld,
                b.scenario() == null ? "CHOP" : b.scenario(),
                b.volAnnual() == null || b.volAnnual() <= 0 ? 0.3 : b.volAnnual(),
                seed, start, b.speed() == null ? 1 : b.speed(),
                symVols.isEmpty() ? null : symVols, symIvs.isEmpty() ? null : symIvs);
        boolean needsResolution = !cheapData && (!pending.isEmpty() || !active.isEmpty());
        Map<String, Object> anchorDoc = new LinkedHashMap<>();
        anchorDoc.put("anchors", anchors);
        anchorDoc.put("excluded", excluded);
        anchorDoc.put("pending", pending);
        anchorDoc.put("resolving", needsResolution);
        anchorDoc.put("trimmed", trimmed);
        anchorDoc.put("resolvedAt", clock.instant().toString());
        // F2: session + anchors + account in ONE transaction; memory admission after commit.
        var created = simSessions.createAtomic(worldCfg, owner, Json.write(anchorDoc),
                (b.name() == null ? "Simulation" : b.name()) + " account", accounts, needsResolution);
        var w = created.world();
        // F1: the governed BACKGROUND resolver — cold actives + live calibration enrich the world
        // while it is still unstarted; a started world keeps its immutable config (disclosed).
        if (needsResolution) {
            final var fActive = new java.util.LinkedHashMap<>(active);
            final var fAll = new java.util.LinkedHashMap<>(all);
            final var fSpots = new java.util.LinkedHashMap<>(spots);
            final var fSpotBasis = new java.util.LinkedHashMap<>(spotBasis);
            final var fPending = List.copyOf(pending);
            final var fAnchors = new ArrayList<>(anchors);
            final var fExcluded = new ArrayList<>(excluded);
            final String worldId = w.worldId();
            Thread.startVirtualThread(() -> resolveWorldInBackground(worldId, owner, worldCfg,
                    fActive, fAll, fSpots, fSpotBasis, fPending, fAnchors, fExcluded, trimmed));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("worldId", w.worldId());
        resp.put("accountId", created.accountId());
        resp.put("config", w.config());
        resp.put("simTime", w.simTime().toString());
        resp.put("spotBasis", spotBasis);
        resp.put("calibration", calibrationBasis);
        resp.put("anchors", anchors);
        resp.put("excluded", excluded);
        resp.put("pending", pending);
        resp.put("resolving", needsResolution);
        resp.put("trimmed", trimmed);
        resp.put("modelVersion", w.modelVersion());
        ctx.status(201).json(resp);
    }

    /** Per-symbol HV30 + chain-ATM-IV calibration for the ACTIVE tier (capped, basis disclosed). */
    private void calibrateInto(java.util.Map<String, Double> all, java.util.Map<String, Double> active,
                               java.util.Map<String, Double> spots, java.util.Map<String, String> spotBasis,
                               java.util.Map<String, Double> symVols, java.util.Map<String, Double> symIvs,
                               java.util.Map<String, String> calibrationBasis,
                               List<Map<String, Object>> anchors, java.time.LocalDate today) {
        int calBudget = 12;
        for (String sym : active.keySet()) {
            if (!all.containsKey(sym) || !spots.containsKey(sym) || calBudget <= 0) continue;
            calBudget--;
            StringBuilder cal = new StringBuilder();
            try {
                var series = market.candleSeries(sym, today.minusDays(90), today);
                if (series != null && series.candles() != null && series.candles().size() >= 20) {
                    double hv = io.liftandshift.strikebench.pricing.HistoricalVol.annualized(series.candles(), 30);
                    if (Double.isFinite(hv) && hv > 0 && hv <= 5.0) {
                        symVols.put(sym, hv);
                        var ev = series.evidence();
                        cal.append("vol ").append(Math.round(hv * 100)).append("% from HV30 (")
                                .append(ev.provenance().name()).append(" · ").append(ev.age().name()).append(")");
                    }
                }
            } catch (RuntimeException ignored) { /* no candle source — session knob applies */ }
            try {
                var exps = market.expirations(sym);
                if (!exps.isEmpty()) {
                    var chain = market.chain(sym, exps.getFirst()).orElse(null);
                    if (chain != null && !chain.isEmpty()) {
                        var spotBd = java.math.BigDecimal.valueOf(spots.get(sym));
                        Double atm = chain.calls().stream()
                                .filter(oq -> oq.iv() != null && oq.strike() != null)
                                .min(java.util.Comparator.comparing(oq -> oq.strike().subtract(spotBd).abs()))
                                .map(io.liftandshift.strikebench.model.OptionQuote::iv).orElse(null);
                        if (atm != null && Double.isFinite(atm) && atm > 0 && atm <= 5.0) {
                            symIvs.put(sym, atm);
                            if (!cal.isEmpty()) cal.append(" · ");
                            cal.append("IV ").append(Math.round(atm * 100)).append("% from the ")
                                    .append(chain.evidence().provenance().name()).append(" · ")
                                    .append(chain.evidence().age().name()).append(" chain's ATM strike");
                        }
                    }
                }
            } catch (RuntimeException ignored) { /* chainless symbol — session knob applies */ }
            calibrationBasis.put(sym, cal.isEmpty() ? "session vol knob (no per-symbol data)" : cal.toString());
            for (var a : anchors) {
                if (sym.equals(a.get("symbol"))) { a.put("calibration", calibrationBasis.get(sym)); break; }
            }
        }
    }

    /**
     * F1: the governed background resolver. Fetches the pending cold symbols through the
     * politeness-governed engine, calibrates the active tier, and ENRICHES the world if it has
     * not started ticking. A started world keeps its immutable config; the durable anchor record
     * says what arrived too late. Publishes world.resolving hints either way.
     */
    private void resolveWorldInBackground(String worldId, String owner,
                                          io.liftandshift.strikebench.market.sim.SimulatedWorld.Config baseCfg,
                                          java.util.Map<String, Double> active, java.util.Map<String, Double> all,
                                          java.util.Map<String, Double> spots, java.util.Map<String, String> spotBasis,
                                          List<String> pending, List<Map<String, Object>> anchors,
                                          List<Map<String, Object>> excluded, List<String> trimmed) {
        try {
            long nowMs = clock.millis();
            if (!pending.isEmpty()) {
                for (var snap : marketEngine.quotes(pending)) { // governed: priorities + politeness apply
                    var mark = snap.last();
                    if (mark == null || mark.signum() <= 0) continue;
                    String sym = snap.symbol();
                    all.put(sym, active.getOrDefault(sym, 1.0));
                    spots.put(sym, mark.doubleValue());
                    boolean realQuote = switch (snap.freshness()) {
                        case REALTIME, DELAYED, EOD, STALE -> true;
                        default -> false;
                    };
                    String basis = (realQuote ? "anchored to the real market's last "
                            + snap.freshness().name().toLowerCase() + " price"
                            : "anchored to a built-in DEMO quote — not a live price") + " (resolved in background)";
                    spotBasis.put(sym, basis);
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("symbol", sym);
                    a.put("tier", "active");
                    a.put("price", mark.doubleValue());
                    a.put("source", snap.source());
                    a.put("freshness", snap.freshness().name());
                    a.put("sourceAsOf", snap.asOfEpochMs());
                    a.put("ageSeconds", Math.max(0, (nowMs - snap.asOfEpochMs()) / 1000));
                    a.put("basis", basis);
                    anchors.removeIf(x -> sym.equals(x.get("symbol")));
                    anchors.add(a);
                }
                for (String sym : pending) {
                    if (all.containsKey(sym)) continue;
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("symbol", sym);
                    a.put("excluded", true);
                    a.put("reason", "background resolution found no price — excluded rather than invented");
                    excluded.add(a);
                    anchors.removeIf(x -> sym.equals(x.get("symbol")));
                }
            }
            java.util.Map<String, Double> symVols = new java.util.LinkedHashMap<>();
            java.util.Map<String, Double> symIvs = new java.util.LinkedHashMap<>();
            java.util.Map<String, String> calibrationBasis = new java.util.LinkedHashMap<>();
            calibrateInto(all, active, spots, spotBasis, symVols, symIvs, calibrationBasis, anchors,
                    java.time.LocalDate.now(clock));
            java.util.Map<String, Double> spotsInWorld = new java.util.LinkedHashMap<>();
            all.keySet().forEach(sym -> { if (spots.containsKey(sym)) spotsInWorld.put(sym, spots.get(sym)); });
            var enriched = new io.liftandshift.strikebench.market.sim.SimulatedWorld.Config(worldId,
                    baseCfg.name(), all, spotsInWorld, baseCfg.scenario(), baseCfg.volAnnual(),
                    baseCfg.seed(), baseCfg.startSimTime(), baseCfg.speed(),
                    symVols.isEmpty() ? null : symVols, symIvs.isEmpty() ? null : symIvs);
            Map<String, Object> anchorDoc = new LinkedHashMap<>();
            anchorDoc.put("anchors", anchors);
            anchorDoc.put("excluded", excluded);
            anchorDoc.put("pending", List.of());
            anchorDoc.put("resolving", false);
            anchorDoc.put("trimmed", trimmed);
            anchorDoc.put("calibration", calibrationBasis);
            anchorDoc.put("resolvedAt", clock.instant().toString());
            boolean applied = simSessions.replaceUnstarted(worldId, owner, enriched, Json.write(anchorDoc));
            if (!applied) {
                // The world already moved: record the truth without touching its immutable config.
                anchorDoc.put("note", "resolved AFTER the session started — not applied; finish and "
                        + "recreate to include the late symbols/calibration");
                db.exec("UPDATE sim_session SET anchors=?::jsonb WHERE id=?", Json.write(anchorDoc), worldId);
                events.publish("world.resolving", Map.of("world", worldId,
                        "user", owner == null ? "local" : owner, "state", "late"));
            }
        } catch (RuntimeException e) {
            log.warn("Simulated market {} could not finish preparing", worldId);
            log.debug("Simulated-market preparation detail for " + worldId, e);
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("anchors", anchors);
            failed.put("excluded", excluded);
            failed.put("pending", pending);
            failed.put("trimmed", trimmed);
            failed.put("resolving", false);
            failed.put("note", "Preparation failed before this market could start. No unresolved symbol was admitted.");
            simSessions.preparationFailed(worldId, owner, Json.write(failed));
        }
    }

    /** The next sim session open: today 09:30 ET if a trading day and before close, else next open. */
    private java.time.LocalDateTime nextSimOpen() {
        java.time.ZonedDateTime nowEt = clock.instant().atZone(io.liftandshift.strikebench.market.MarketHours.EASTERN);
        java.time.LocalDate d = nowEt.toLocalDate();
        // After today's close, "next open" is the NEXT trading day (F13: the old version returned
        // a 09:30 start for a session that had already ended).
        if (io.liftandshift.strikebench.market.MarketHours.isTradingDay(d)
                && !nowEt.toLocalTime().isBefore(java.time.LocalTime.of(16, 0))) {
            d = d.plusDays(1);
        }
        while (!io.liftandshift.strikebench.market.MarketHours.isTradingDay(d)) d = d.plusDays(1);
        return java.time.LocalDateTime.of(d, java.time.LocalTime.of(9, 30));
    }

    /** The reviewer report: what was decided and how it went, inside this world. */
    private void simMarketReport(Context ctx) {
        String worldId = ctx.pathParam("id");
        var w = simSessions.getOrRestore(worldId, ownerId(ctx))
                .orElseThrow(() -> new java.util.NoSuchElementException("no such simulated session: " + worldId));
        var acctRows = db.query("SELECT id FROM accounts WHERE world_id=?", r -> r.str("id"), worldId);
        List<Map<String, Object>> tradeRows = new ArrayList<>();
        long realized = 0; int wins = 0, resolved = 0;
        // POP vs outcome: did higher-POP entries actually win more often? (decision quality,
        // separated from single-trade outcomes — one loss on a 70% trade is not a bad decision.)
        int hiPop = 0, hiPopWins = 0, loPop = 0, loPopWins = 0;
        if (!acctRows.isEmpty()) {
            var page = trades.list(acctRows.getFirst(), null, 0, 200);
            for (var t : page.trades()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.id()); m.put("symbol", t.symbol()); m.put("strategy", t.strategy());
                m.put("status", t.status()); m.put("qty", t.qty());
                m.put("entryNetPremiumCents", t.entryNetPremiumCents());
                m.put("realizedPnlCents", t.realizedPnlCents());
                Long decisionPnl = t.decisionPnlCents() != null ? t.decisionPnlCents() : t.realizedPnlCents();
                m.put("decisionPnlCents", decisionPnl);
                m.put("maxLossCents", t.maxLossCents()); m.put("popEntry", t.popEntry());
                m.put("closeReason", t.closeReason());
                m.put("openedAt", t.createdAt()); m.put("closedAt", t.closedAt());
                // DUAL CLOCKS: wall time above; the SIMULATED time the decision was made below.
                try {
                    var snap = io.liftandshift.strikebench.util.Json.read(t.entrySnapshotJson(),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    Object lane = snap == null ? null : snap.get("laneTime");
                    if (lane != null) m.put("laneEntryTime", lane.toString());
                } catch (RuntimeException ignored) { /* legacy snapshot without lane time */ }
                // MAE/MFE from the trade's own mark history: how far it went against/for the
                // trader while open — the difference between a bad outcome and a bad decision.
                var excursion = db.query(
                        "SELECT MIN(COALESCE(decision_unrealized_cents, unrealized_cents)) mae, "
                                + "MAX(COALESCE(decision_unrealized_cents, unrealized_cents)) mfe "
                                + "FROM trade_marks WHERE trade_id=?",
                        r -> new Long[]{r.lngOrNull("mae"), r.lngOrNull("mfe")}, t.id());
                if (!excursion.isEmpty() && excursion.getFirst()[0] != null) {
                    m.put("maeCents", excursion.getFirst()[0]);
                    m.put("mfeCents", excursion.getFirst()[1]);
                }
                tradeRows.add(m);
                if (decisionPnl != null) {
                    resolved++; realized += decisionPnl;
                    boolean win = decisionPnl > 0;
                    if (win) wins++;
                    if (t.popEntry() != null) {
                        if (t.popEntry() >= 0.5) { hiPop++; if (win) hiPopWins++; }
                        else { loPop++; if (win) loPopWins++; }
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("worldId", worldId);
        out.put("config", w.config());
        out.put("simTime", w.simTime().toString());
        out.put("ticks", w.ticks());
        out.put("trades", tradeRows);
        out.put("resolved", resolved);
        out.put("winRate", resolved > 0 ? Math.round(100.0 * wins / resolved) : null);
        out.put("decisionPnlCents", realized);
        // Decision-vs-outcome: predicted odds against realized frequency, per POP band.
        Map<String, Object> popVsOutcome = new LinkedHashMap<>();
        popVsOutcome.put("highPopTrades", hiPop);
        popVsOutcome.put("highPopWinRate", hiPop > 0 ? Math.round(100.0 * hiPopWins / hiPop) : null);
        popVsOutcome.put("lowPopTrades", loPop);
        popVsOutcome.put("lowPopWinRate", loPop > 0 ? Math.round(100.0 * loPopWins / loPop) : null);
        popVsOutcome.put("note", "Entries with POP ≥ 50% vs below — with few trades this is noise, not a verdict.");
        out.put("popVsOutcome", popVsOutcome);
        // Replay record: model version + every injected event/speed change — WITHOUT this the
        // seed line below would overclaim (injections are not derivable from the seed).
        var replay = simSessions.replayRecord(worldId, ownerId(ctx));
        out.put("modelVersion", replay.getOrDefault("modelVersion", "sim-1"));
        out.put("events", replay.getOrDefault("events", List.of()));
        if (replay.get("rehearsal") != null) out.put("rehearsal", replay.get("rehearsal"));
        int eventCount = replay.get("events") instanceof List<?> l ? l.size() : 0;
        if (replay.get("rehearsal") instanceof Map<?, ?> source) {
            out.put("note", "This session replayed exact path " + (((Number) source.get("pathIndex")).intValue() + 1) + " ("
                    + String.valueOf(source.get("selection")).toLowerCase(Locale.ROOT) + ") from Plan ensemble "
                    + source.get("ensembleId") + " · receipt " + source.get("fingerprint")
                    + ". Prices and IV follow that stored realization; outcomes measure management decisions, not a forecast.");
        } else {
            out.put("note", "Every price in this world was generated (model " + replay.getOrDefault("modelVersion", "sim-1")
                    + ", seed " + w.config().seed() + ", scenario " + w.config().scenario() + ")"
                    + (eventCount > 0 ? " plus " + eventCount + " manually injected event" + (eventCount == 1 ? "" : "s")
                            + " listed below — replay needs the seed AND the event log" : "")
                    + " — outcomes measure DECISIONS, not the market.");
        }
        ctx.json(out);
    }

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
            log.warn("Market-data status is temporarily unavailable");
            log.debug("Market-data status failure detail", e);
            out.put("ok", false);
            out.put("error", "Market-data status is temporarily unavailable");
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
        String world = activeWorld(ctx);
        out.put("world", world);
        out.put("marketLane", !io.liftandshift.strikebench.db.DatasetService.OBSERVED.equals(active)
                && "observed".equals(world) ? "SCENARIO"
                : io.liftandshift.strikebench.market.MarketLane.of(world, cfg.fixturesOnly()).name());
        ctx.json(out);
    }

    /**
     * Liveness + staleness beacon. jarChangedSinceBoot=true means the application jar was
     * rewritten under this running server — the classic "some screens fail to load" cause —
     * and the UI turns it into a restart banner instead of a mystery. Never 500s.
     */
    // ---- Material-risk acknowledgment contract (R2/R4) ----
    private final byte[] ackSecret = new byte[32];
    { new java.security.SecureRandom().nextBytes(ackSecret); }

    /** The server's list of risks the user MUST acknowledge for this package (id + label). */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> requiredAcksFor(io.liftandshift.strikebench.paper.TradePreview p,
                                                      long effectiveRiskBudgetCents) {
        List<Map<String, String>> out = new ArrayList<>();
        if (p == null || p.analytics() == null) return out;
        Long marketEvAfterCosts = p.expectedValueCents() == null ? null
                : p.expectedValueCents() - Math.multiplyExact(p.feesOpenCents(), 2L);
        if (marketEvAfterCosts != null && marketEvAfterCosts < 0) {
            out.add(Map.of("id", "ack-ev", "label", "The model expects this trade to LOSE "
                    + io.liftandshift.strikebench.util.Money.fmt(-marketEvAfterCosts)
                    + " on average at the market's own volatility."));
        }
        Object execO = p.analytics().get("executionQuality");
        if (execO instanceof Map<?, ?> exec) {
            Object pct = exec.get("concessionPctOfMid");
            if (pct instanceof Double d && Math.abs(d) > 0.10) {
                out.add(Map.of("id", "ack-exec", "label", "Entering surrenders "
                        + Math.round(Math.abs(d) * 100) + "% of the package midpoint to the bid/ask spread."));
            }
        }
        Object planO = p.analytics().get("managementPlan");
        if (planO instanceof Map<?, ?> plan && String.valueOf(plan.get("regime")).contains("near-expiry")) {
            out.add(Map.of("id", "ack-dte", "label", "Only " + plan.get("sessions")
                    + " trading session(s) remain — gamma, weekend gaps and pin risk dominate."));
        }
        if (effectiveRiskBudgetCents > 0 && p.maxLossCents() > effectiveRiskBudgetCents) {
            out.add(Map.of("id", "ack-capital", "label", "The theoretical worst case "
                    + io.liftandshift.strikebench.util.Money.fmt(p.maxLossCents())
                    + " exceeds your selected per-trade risk budget ("
                    + io.liftandshift.strikebench.util.Money.fmt(effectiveRiskBudgetCents) + ")."));
        }
        return out;
    }

    /** Token = ts.hmac(package-identity + ts): proves a preview of THIS package was seen recently. */
    private String ackToken(TradeService.OpenRequest req) {
        long ts = clock.millis();
        return ts + "." + ackHmac(req, ts);
    }

    private boolean verifyAckToken(String token, TradeService.OpenRequest req) {
        if (token == null || !token.contains(".")) return false;
        try {
            long ts = Long.parseLong(token.substring(0, token.indexOf('.')));
            if (Math.abs(clock.millis() - ts) > 15 * 60_000L) return false; // stale preview
            String expect = ackHmac(req, ts);
            return java.security.MessageDigest.isEqual(
                    expect.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    token.substring(token.indexOf('.') + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) { return false; }
    }

    private String ackHmac(TradeService.OpenRequest req, long ts) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(ackSecret, "HmacSHA256"));
            String payload = req.symbol() + "|" + req.strategy() + "|" + req.qty() + "|"
                    + io.liftandshift.strikebench.util.Json.canonical(req.legs()) + "|"
                    + req.proposedNetCents() + "|" + req.feesOverrideCents() + "|"
                    + req.accountId() + "|" + ts;
            return java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ---- Operational metrics + throttle ----
    private final java.util.concurrent.atomic.AtomicLong apiRequests = new java.util.concurrent.atomic.AtomicLong();
    /**
     * Route-class latency rings (review P2 #8: one global ring let fast health calls hide slow
     * chain/scan requests). Writes are synchronized per ring (cheap at these request rates and
     * removes the incremented-slot-before-write race); percentiles computed on read.
     */
    static final class LatencyRing {
        private final long[] ring = new long[1024];
        private long count;
        synchronized void record(long micros) { ring[(int) (count++ % ring.length)] = micros; }
        synchronized Map<String, Object> percentiles() {
            long n = Math.min(count, ring.length);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("samples", n);
            if (n == 0) return out;
            long[] copy = java.util.Arrays.copyOf(ring, (int) n);
            java.util.Arrays.sort(copy);
            out.put("p50Micros", copy[(int) (n / 2)]);
            out.put("p95Micros", copy[(int) Math.min(n - 1, (long) Math.ceil(n * 0.95) - 1)]);
            out.put("maxMicros", copy[(int) n - 1]);
            return out;
        }
    }
    private final java.util.Map<String, LatencyRing> latencyByClass = new java.util.concurrent.ConcurrentHashMap<>();
    private static String latencyClass(String path) {
        if (path == null) return "other";
        if (path.contains("/chain")) return "chain";
        // Heavy analysis routes get their OWN buckets (review P2: backtests hiding inside
        // 'other' made /api/metrics look healthy while Research stayed slow).
        if (path.startsWith("/api/backtest")) return "backtest";
        if (path.startsWith("/api/sim/market")) return "world";
        if (path.startsWith("/api/datasets") || path.startsWith("/api/data")) return "data-ops";
        if (path.startsWith("/api/broker")) return "broker";
        if (path.startsWith("/api/sparklines")) return "quotes";
        if (path.startsWith("/api/research/scout") || path.contains("/intent-ladder")
                || path.contains("/strategy/run") || path.contains("/strategy/fit")
                || path.startsWith("/api/evaluate") || path.startsWith("/api/opportunities")
                || path.startsWith("/api/optimize")) return "compute";
        if (path.startsWith("/api/research")) return "research";
        if (path.startsWith("/api/quotes") || path.startsWith("/api/market")) return "quotes";
        if (path.startsWith("/api/sim")) return "compute";
        if (path.startsWith("/api/trades") || path.startsWith("/api/portfolio")
                || path.startsWith("/api/positions")) return "trading";
        return "other";
    }
    private void recordLatency(String path, long micros) {
        latencyByClass.computeIfAbsent(latencyClass(path), k -> new LatencyRing()).record(micros);
    }
    private Map<String, Object> latencyPercentiles() {
        Map<String, Object> out = new LinkedHashMap<>();
        latencyByClass.forEach((k, v) -> out.put(k, v.percentiles()));
        return out;
    }
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
        out.put("latency", latencyPercentiles());
        out.put("errors", apiErrors.get());
        out.put("throttled", apiThrottled.get());
        out.put("throttleActive", !cfg.fixturesOnly());
        try {
            out.put("engine", marketEngine.status());
        } catch (Exception e) {
            log.debug("Market-engine metrics failure detail", e);
            out.put("engine", Map.of("error", "Market engine status is temporarily unavailable"));
        }
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
        if (!"observed".equals(activeWorld(ctx))) {
            throw new IllegalStateException("This market owns its symbol list. Return to Observed market before changing sectors.");
        }
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
        String world = worldParam(activeWorld(ctx));
        if (world != null) {
            // ONE LANE PER SCREEN: inside a simulated session the dashboard tiles quote the
            // WORLD's symbols from the world — observed AAPL under a SIMULATED band was the
            // 'quiet lie' the honesty rules forbid (review P1).
            List<String> syms = raw == null || raw.isBlank()
                    ? market.worldSymbols(world).map(x -> List.copyOf(x)).orElse(List.of())
                    : java.util.Arrays.stream(raw.split(",")).map(x -> x.trim().toUpperCase(Locale.ROOT))
                        .filter(x -> !x.isBlank()).distinct().toList();
            List<Map<String, Object>> rows = new ArrayList<>();
            // F7: world quotes are LOCAL memory — serve the whole world (<=120), not a 40-cap
            // that made the tape and SSE disagree about which symbols exist.
            for (String sym : syms.stream()
                    .limit(io.liftandshift.strikebench.market.sim.SimulationSessions.MAX_SYMBOLS).toList()) {
                market.quote(sym, world).ifPresent(q -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("symbol", q.symbol());
                    m.put("description", q.description());
                    m.put("last", q.last());
                    m.put("bid", q.bid());
                    m.put("ask", q.ask());
                    m.put("prevClose", q.prevClose());
                    m.put("optionable", q.optionable());
                    m.put("asOf", q.asOfEpochMs());
                    m.put("freshness", q.markFreshness().name());
                    m.put("source", q.source());
                    m.put("evidence", q.evidence());
                    rows.add(m);
                });
            }
            ctx.json(Map.of("quotes", rows, "requested", syms.size(), "world", world,
                    "marketLane", market.lane(world).name()));
            return;
        }
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
        ctx.json(Map.of("quotes", out, "requested", symbols.size(),
                "marketLane", cfg.fixturesOnly() ? "DEMO" : "OBSERVED"));
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
        final String streamOwner = ownerId(client.ctx()); // captured at open; identity never changes mid-stream
        client.keepAlive();
        // The task ref lets the push cancel ITSELF when it notices the client died — onClose is
        // the normal path, but a client that vanishes without firing it must not keep a scheduled
        // write alive forever.
        var taskRef = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        var seq = new java.util.concurrent.atomic.AtomicLong();
        var lastFrameHash = new java.util.concurrent.atomic.AtomicInteger();
        Runnable push = () -> {
            if (client.terminated()) {
                var t = taskRef.get();
                if (t != null) t.cancel(false);
                return;
            }
            try {
                // LANE-AWARE (weekend-handoff M5): inside a simulated session this stream quotes the
                // WORLD's symbols from the world — the same channel that drives the tape/live tiles
                // in the real market drives them in the sim, with SIMULATED freshness disclosed.
                // Re-resolved per frame so entering/leaving a world retargets without reconnecting.
                String world = activeWorldFor(streamOwner);
                List<Map<String, Object>> rows = new ArrayList<>();
                long asOf = clock.millis();
                if ("observed".equals(world)) {
                    for (var snap : marketEngine.quotes(symbols)) {
                        rows.add(io.liftandshift.strikebench.market.MarketDataEngine.toRow(snap));
                    }
                } else if ("demo".equals(world)) {
                    List<String> demoSymbols = raw == null || raw.isBlank()
                            ? market.worldSymbols("demo").map(List::copyOf).orElse(List.of())
                            : symbols;
                    for (String sym : demoSymbols) {
                        market.quote(sym, "demo").ifPresent(q -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("symbol", q.symbol());
                            row.put("description", q.description());
                            row.put("last", q.last() == null ? null : q.last().toPlainString());
                            row.put("bid", q.bid() == null ? null : q.bid().toPlainString());
                            row.put("ask", q.ask() == null ? null : q.ask().toPlainString());
                            row.put("prevClose", q.prevClose() == null ? null : q.prevClose().toPlainString());
                            row.put("optionable", q.optionable());
                            row.put("freshness", q.markFreshness().name());
                            row.put("evidence", q.evidence());
                            row.put("asOf", q.asOfEpochMs());
                            row.put("refreshing", false);
                            rows.add(row);
                        });
                    }
                } else {
                    var wOpt = simSessions.getOrRestore(world, streamOwner);
                    if (wOpt.isPresent()) {
                        var w = wOpt.get();
                        for (String sym : w.config().symbolBetas().keySet()) {
                            market.quote(sym, world).ifPresent(q -> {
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("symbol", q.symbol());
                                row.put("description", q.description());
                                row.put("last", q.last() == null ? null : q.last().toPlainString());
                                row.put("bid", q.bid() == null ? null : q.bid().toPlainString());
                                row.put("ask", q.ask() == null ? null : q.ask().toPlainString());
                                row.put("prevClose", q.prevClose() == null ? null : q.prevClose().toPlainString());
                                row.put("optionable", q.optionable());
                                row.put("freshness", q.markFreshness().name());
                                row.put("evidence", q.evidence());
                                row.put("asOf", q.asOfEpochMs());
                                row.put("refreshing", false);
                                rows.add(row);
                            });
                        }
                    }
                }
                // Frame discipline (holistic review Phase 2): sequence-numbered MarketState
                // frames; identical frames are skipped (a closed observed market streams nothing),
                // and world frames carry the SIM clock so consumers can place them on the lane.
                int hash = rows.hashCode();
                if (hash == lastFrameHash.get() && seq.get() > 0) return;
                lastFrameHash.set(hash);
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("seq", seq.incrementAndGet());
                frame.put("quotes", rows);
                frame.put("asOf", asOf);
                frame.put("world", world);
                if (!"observed".equals(world)) {
                    simSessions.getOrRestore(world, streamOwner)
                            .ifPresent(w -> frame.put("simTime", w.simTime().toString()));
                }
                client.sendEvent("quotes", frame);
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

    // ---- Plan-centered journey ----

    private io.liftandshift.strikebench.plan.Plan.MarketKind activePlanMarket(Context ctx) {
        String world = activeWorld(ctx);
        if ("demo".equals(world)) return io.liftandshift.strikebench.plan.Plan.MarketKind.DEMO;
        if ("observed".equals(world)) return io.liftandshift.strikebench.plan.Plan.MarketKind.OBSERVED;
        return io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED;
    }

    private record PlanSymbolEligibility(boolean eligible, String detail) {}

    /** A Plan may start only when its active market can supply a lane-owned option surface. */
    private PlanSymbolEligibility planSymbolEligibility(String rawSymbol, String world) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        if (symbol.isBlank()) return new PlanSymbolEligibility(false, "Choose a ticker symbol first.");
        var lane = io.liftandshift.strikebench.market.MarketLane.of(world, cfg.fixturesOnly());
        var quote = market.quote(symbol, world).orElse(null);
        if (quote == null) return new PlanSymbolEligibility(false,
                symbol + " is not available in the active " + lane.name().toLowerCase(Locale.ROOT) + " market.");
        var expirations = activeExpirations(symbol, world);
        var chain = expirations.isEmpty() ? null : market.chain(symbol, expirations.getFirst(), world).orElse(null);
        return planSymbolEligibility(symbol, lane, quote, expirations,
                chain == null || chain.isEmpty() ? io.liftandshift.strikebench.model.DataEvidence.missing("option chain")
                        : chain.evidence());
    }

    private PlanSymbolEligibility planSymbolEligibility(String symbol,
            io.liftandshift.strikebench.market.MarketLane lane, Quote quote, List<LocalDate> expirations,
            io.liftandshift.strikebench.model.DataEvidence optionEvidence) {
        if (!quote.evidence().usableIn(lane)) return new PlanSymbolEligibility(false,
                symbol + " does not have " + lane.name().toLowerCase(Locale.ROOT) + " market evidence.");
        if (!quote.optionable()) return new PlanSymbolEligibility(false,
                symbol + " has no listed options in this market. Its stock research remains available.");
        if (expirations.isEmpty()) return new PlanSymbolEligibility(false,
                symbol + " has no active option expirations in this market.");
        if (optionEvidence == null || !optionEvidence.usableIn(lane)) {
            return new PlanSymbolEligibility(false,
                    "An option surface for " + symbol + " is unavailable in this market right now.");
        }
        return new PlanSymbolEligibility(true, "Ready to build an options Plan in the active market.");
    }

    private void plansList(Context ctx) {
        if (planSvc == null) throw new IllegalStateException("plan store unavailable");
        boolean allMarkets = "all".equalsIgnoreCase(ctx.queryParam("scope"));
        boolean openOnly = !"false".equalsIgnoreCase(ctx.queryParam("openOnly"));
        var market = allMarkets ? null : activePlanMarket(ctx);
        String world = market == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED
                ? activeWorld(ctx) : null;
        ctx.json(Map.of("plans", planSvc.list(ownerId(ctx), market, world, openOnly),
                "market", activePlanMarket(ctx).name(), "world", activeWorld(ctx)));
    }

    private void planCreate(Context ctx) {
        if (planSvc == null) throw new IllegalStateException("plan store unavailable");
        var request = requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.plan.Plan.CreateRequest.class));
        var market = activePlanMarket(ctx);
        String world = market == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED
                ? activeWorld(ctx) : null;
        String marketWorld = market == io.liftandshift.strikebench.plan.Plan.MarketKind.DEMO ? "demo" : world;
        if (request.symbol() != null && !request.symbol().isBlank()) {
            var eligibility = planSymbolEligibility(request.symbol(), marketWorld);
            if (!eligibility.eligible()) {
                ctx.attribute("apiErrorWritten", true);
                ctx.status(422).json(Map.of("error", "plan_symbol_unavailable",
                        "detail", eligibility.detail(), "market", market.name()));
                return;
            }
        }
        // Market and account are server-derived. A client cannot create an observed plan while
        // looking at generated quotes or bind a plan to somebody else's simulation account.
        Account account = currentAccount(ctx);
        request = snapshotPlanHoldings(account, request);
        ctx.status(201).json(planSvc.create(ownerId(ctx), market, world, account.id(), request));
    }

    /**
     * Held-share goals start from the account that owns the Plan. When the caller did not supply
     * a hypothetical holding, capture the currently free shares and basis as durable Plan context.
     * ACQUIRE deliberately does not use this path: its shares field means shares the user wants.
     */
    private io.liftandshift.strikebench.plan.Plan.CreateRequest snapshotPlanHoldings(
            Account account, io.liftandshift.strikebench.plan.Plan.CreateRequest request) {
        String intent = request.intent() == null ? "" : request.intent().trim().toUpperCase(Locale.ROOT);
        if (request.holdingsShares() != null || !java.util.Set.of("EXIT", "HEDGE", "INCOME").contains(intent)) {
            return request;
        }
        var holding = positions.list(account.id()).stream()
                .filter(row -> row.symbol().equalsIgnoreCase(request.symbol()))
                .filter(row -> row.freeShares() > 0)
                .findFirst().orElse(null);
        if (holding == null) return request;
        return new io.liftandshift.strikebench.plan.Plan.CreateRequest(
                request.clientRequestId(), request.symbol(), request.intent(), request.originPlanId(), request.title(),
                request.thesis(), request.horizonDays(), request.targetCents(), request.riskMode(),
                holding.freeShares(), request.costBasisCents() == null ? holding.avgCostCents() : request.costBasisCents(),
                request.priceAssumptionCents());
    }

    private void planGet(Context ctx) {
        if (planSvc == null) throw new IllegalStateException("plan store unavailable");
        ctx.json(planSvc.get(ownerId(ctx), ctx.pathParam("id")));
    }

    private void planContextPut(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.plan.Plan.ContextUpdateRequest.class));
        ctx.json(planSvc.updateContext(ownerId(ctx), ctx.pathParam("id"), request));
    }

    private void planIntentPut(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.plan.Plan.IntentRequest.class));
        ctx.json(planSvc.claimIntent(ownerId(ctx), ctx.pathParam("id"), request));
    }

    private void planStagePut(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.plan.Plan.StageRequest.class));
        ctx.json(planSvc.setStage(ownerId(ctx), ctx.pathParam("id"), request));
    }

    private void planOpenPut(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.plan.Plan.OpenRequest.class));
        ctx.json(planSvc.setOpen(ownerId(ctx), ctx.pathParam("id"), request));
    }

    private void planArchive(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, io.liftandshift.strikebench.plan.Plan.ArchiveRequest.class));
        ctx.json(planSvc.archive(ownerId(ctx), ctx.pathParam("id"), request));
    }

    private void requireActivePlanMarket(Context ctx, io.liftandshift.strikebench.plan.Plan.View plan) {
        var active = activePlanMarket(ctx);
        String activeWorld = active == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED
                ? activeWorld(ctx) : null;
        if (plan.marketKind() != active || !java.util.Objects.equals(plan.worldId(), activeWorld)) {
            throw new IllegalStateException("Open this plan's market before running its evidence");
        }
    }

    private void planEvidenceLatest(Context ctx) {
        var saved = planEvidence.latest(ownerId(ctx), ctx.pathParam("id"), analysisCtx(ctx));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evidence", saved);
        ctx.json(out);
    }

    private void planEvidenceStudy(Context ctx) {
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        var request = requireBody(bodyOrNull(ctx,
                io.liftandshift.strikebench.research.ResearchQuestionEngine.RunRequest.class));
        ctx.json(planEvidence.run(ownerId(ctx), plan, request, analysisCtx(ctx),
                worldParam(activeWorld(ctx))));
    }

    public record PlanStrategyRunRequest(Boolean allow0dte, Long maxLossCents, List<String> allowedStrategies,
                                         RecommendationEngine.Filters filters) {}
    public record PlanStrategyFitRequest(Long expectedVersion, String strategy, Boolean allow0dte,
                                         Long maxLossCents, RecommendationEngine.Filters filters) {}
    public record PlanStrategySelectRequest(String candidateId, Long expectedVersion) {}
    public record PlanStrategyCustomRequest(Long expectedVersion, TradeOpenRequest position) {}
    public record PlanScoutRequest(String scope, Integer maxPicks, Boolean allow0dte) {}
    public record PlanScoutSpawnRequest(String clientRequestId, String candidateId, String role) {}
    public record PlanEnsembleRequest(Long expectedVersion,
                                      io.liftandshift.strikebench.sim.ScenarioSpec over,
                                      io.liftandshift.strikebench.sim.IvSpec iv,
                                      List<io.liftandshift.strikebench.sim.SimulationEngine.DecisionLevel> levels) {}
    public record PlanOutcomeRunRequest(Long expectedVersion, String basis, String ensembleId,
                                       io.liftandshift.strikebench.sim.ScenarioSpec over,
                                       io.liftandshift.strikebench.sim.IvSpec iv) {}
    public record PlanOutcomeCompareRequest(Long expectedVersion, String basis, String ensembleId,
                                            List<String> candidateIds,
                                            io.liftandshift.strikebench.sim.ScenarioSpec over,
                                            io.liftandshift.strikebench.sim.IvSpec iv) {}
    public record PlanBacktestRequest(Long expectedVersion, String engine, String from, String to,
                                      Integer targetDte, Integer entryEveryDays, Integer maxConcurrent,
                                      Integer qty, Double slippagePct, Long startingCashCents,
                                      Double shortDelta, Double widthPct, Double profitTargetPct,
                                      Double stopFraction, Integer rollDte) {}
    public record PlanDecisionRequest(Long expectedVersion, Integer qty, Long proposedNetCents,
                                      Long feesOverrideCents, List<String> acknowledgedRisks,
                                      String ackToken, String note) {}
    public record PlanManageRequest(Long expectedVersion, Boolean confirm) {}

    private void planStrategyLatest(Context ctx) {
        var saved = planStrategy.latestCompetition(ownerId(ctx), ctx.pathParam("id"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", saved);
        out.put("selected", planStrategy.selectedCandidate(ownerId(ctx), ctx.pathParam("id")));
        ctx.json(out);
    }

    private void planStrategyRun(Context ctx) {
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        if (plan.intent() == null || plan.intent().isBlank()) {
            throw new IllegalStateException("Choose what this plan should do before comparing strategies");
        }
        PlanStrategyRunRequest controls = bodyOrNull(ctx, PlanStrategyRunRequest.class);
        RecommendationEngine.Request request = planStrategyRequest(plan, controls);
        RecommendationEngine.Result recommended = resolveAndRecommend(ctx, request);
        JsonNode ranked = Json.MAPPER.valueToTree(decisionRanked(recommended, currentAccount(ctx), activeWorld(ctx)));
        JsonNode input = Json.MAPPER.valueToTree(request);
        var saved = planStrategy.saveCompetition(ownerId(ctx), plan, input, ranked);
        ctx.json(Map.of("plan", planSvc.get(ownerId(ctx), plan.id()), "strategy", saved));
    }

    /** Price one named Builder structure against this Plan without creating a second strategy workflow. */
    private void planStrategyFit(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanStrategyFitRequest.class));
        if (body.expectedVersion() == null || body.strategy() == null || body.strategy().isBlank()) {
            throw new IllegalArgumentException("expectedVersion and strategy are required");
        }
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        if (plan.intent() == null || plan.intent().isBlank()) {
            throw new IllegalStateException("Choose what this plan should do before fitting a structure");
        }
        var controls = new PlanStrategyRunRequest(body.allow0dte(), body.maxLossCents(),
                List.of(body.strategy().trim().toUpperCase(Locale.ROOT)), body.filters());
        var request = planStrategyRequest(plan, controls);
        var ranked = Json.MAPPER.valueToTree(decisionRanked(resolveAndRecommend(ctx, request),
                currentAccount(ctx), activeWorld(ctx)));
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.set("plan", Json.MAPPER.valueToTree(plan));
        out.set("result", ranked);
        JsonNode candidates = ranked.path("candidates");
        if (candidates.isArray() && !candidates.isEmpty()) out.set("candidate", candidates.get(0));
        ctx.json(out);
    }

    private static RecommendationEngine.Request planStrategyRequest(
            io.liftandshift.strikebench.plan.Plan.View plan, PlanStrategyRunRequest controls) {
        var c = plan.context();
        RecommendationEngine.Holdings holdings = c.holdingsShares() == null && c.costBasisCents() == null
                && c.targetCents() == null ? null
                : new RecommendationEngine.Holdings(c.holdingsShares() == null ? null
                        : Math.toIntExact(Math.min(Integer.MAX_VALUE, c.holdingsShares())),
                        c.costBasisCents(), c.targetCents());
        return new RecommendationEngine.Request(plan.symbol(), c.thesis(), planHorizon(c.horizonDays()),
                c.riskMode(), controls == null ? null : controls.maxLossCents(), null, null,
                controls == null ? null : controls.allowedStrategies(), true,
                controls != null && Boolean.TRUE.equals(controls.allow0dte()), plan.intent(), holdings,
                controls == null ? null : controls.filters());
    }

    private void planStrategySelect(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, PlanStrategySelectRequest.class));
        if (request.candidateId() == null || request.candidateId().isBlank() || request.expectedVersion() == null) {
            throw new IllegalArgumentException("candidateId and expectedVersion are required");
        }
        var selected = planStrategy.select(ownerId(ctx), ctx.pathParam("id"), request.candidateId(),
                request.expectedVersion());
        ctx.json(Map.of("selection", selected, "plan", planSvc.get(ownerId(ctx), ctx.pathParam("id"))));
    }

    private void planStrategyCustom(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanStrategyCustomRequest.class));
        if (body.expectedVersion() == null || body.position() == null) {
            throw new IllegalArgumentException("expectedVersion and position are required");
        }
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        if (plan.intent() == null || plan.intent().isBlank()) {
            throw new IllegalStateException("Choose what this plan should do before saving a structure");
        }
        TradeOpenRequest supplied = body.position();
        if (supplied.symbol() != null && !supplied.symbol().isBlank()
                && !plan.symbol().equalsIgnoreCase(supplied.symbol())) {
            throw new IllegalArgumentException("A Plan can contain structures for " + plan.symbol() + " only");
        }
        var c = plan.context();
        TradeOpenRequest exactBody = new TradeOpenRequest(plan.symbol(), supplied.strategy(), supplied.qty(),
                supplied.legs(), c.thesis(), planHorizon(c.horizonDays()), c.riskMode(), plan.intent(),
                supplied.useHeldShares(), supplied.recommendationId(), supplied.proposedNetCents(),
                supplied.feesOverrideCents(), "BUILDER", null, null, null, null, null, null);
        Account account = currentAccount(ctx);
        TradeService.OpenRequest request = toOpenRequest(exactBody, account);
        var preview = trades.preview(request);
        Candidate candidate = exactPreviewCandidate(request, preview);
        ObjectNode candidateJson = Json.MAPPER.valueToTree(candidate);
        long roundTripFees = Math.multiplyExact(preview.feesOpenCents(), 2L);
        io.liftandshift.strikebench.eval.EconomicAssessment economics;
        try {
            economics = evaluations.assessExact(plan.symbol(), candidate, account.buyingPowerCents(),
                    analysisCtx(ctx), worldParam(activeWorld(ctx)), preview.ok(), preview.blockReasons(),
                    roundTripFees);
        } catch (RuntimeException e) {
            log.debug("Plan custom-package economic assessment is unavailable", e);
            var provenance = preview.evidence().provenance();
            boolean observed = provenance == io.liftandshift.strikebench.model.DataProvenance.OBSERVED
                    || provenance == io.liftandshift.strikebench.model.DataProvenance.BROKER;
            economics = new io.liftandshift.strikebench.eval.EconomicAssessment(
                    io.liftandshift.strikebench.eval.EconomicAssessment.Verdict.UNAVAILABLE,
                    "MECHANICS_ONLY", "Economics unavailable",
                    "The exact package passed through the mechanical preview, but its evidence cannot support an economic verdict.",
                    preview.expectedValueCents() == null ? null : preview.expectedValueCents() - roundTripFees,
                    null, roundTripFees, null, observed,
                    List.of("No favorable claim is made without complete economic evidence."));
        }
        candidateJson.set("economics", Json.MAPPER.valueToTree(economics));
        candidateJson.put("economicVerdict", economics.verdict().name());
        candidateJson.put("economicPlacement", economics.placement());
        candidateJson.put("decisionViable", preview.ok());
        candidateJson.put("structurallyEligible", preview.ok());
        JsonNode requestJson = Json.MAPPER.valueToTree(exactBody);
        var saved = planStrategy.saveCustom(ownerId(ctx), plan, requestJson, candidateJson, body.expectedVersion());
        ctx.json(Map.of("plan", planSvc.get(ownerId(ctx), plan.id()), "strategy", saved,
                "preview", preview));
    }

    private void planScoutLatest(Context ctx) {
        String scope = java.util.Objects.requireNonNullElse(ctx.queryParam("scope"), "PEERS");
        var saved = planStrategy.latestScout(ownerId(ctx), ctx.pathParam("id"), scope);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scout", saved);
        ctx.json(out);
    }

    private void planScoutRun(Context ctx) {
        PlanScoutRequest controls = bodyOrNull(ctx, PlanScoutRequest.class);
        String scope = controls == null || controls.scope() == null ? "PEERS" : controls.scope().trim().toUpperCase(Locale.ROOT);
        if (!List.of("PEERS", "ALTERNATIVES", "HEDGES").contains(scope)) {
            throw new IllegalArgumentException("scope must be PEERS, ALTERNATIVES, or HEDGES");
        }
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        List<String> scanUniverse;
        if (cfg.fixturesOnly()) {
            scanUniverse = io.liftandshift.strikebench.market.Universes.SECTORS.get("DEMO").symbols().stream()
                    .filter(symbol -> !symbol.equals(plan.symbol())).toList();
        } else if ("HEDGES".equals(scope)) {
            scanUniverse = io.liftandshift.strikebench.market.Universes.complementsFor(plan.symbol());
        } else {
            scanUniverse = io.liftandshift.strikebench.market.Universes.peersOf(plan.symbol());
        }
        String world = worldParam(activeWorld(ctx));
        if (world != null) {
            var available = market.worldSymbols(world).map(java.util.HashSet::new).orElseGet(java.util.HashSet::new);
            scanUniverse = scanUniverse.stream().filter(available::contains).toList();
        }
        if (scanUniverse.isEmpty()) {
            throw new IllegalStateException("This market has no eligible " + scope.toLowerCase(Locale.ROOT)
                    + " for " + plan.symbol());
        }
        Account account = currentAccount(ctx);
        List<AutoRecommender.HoldingInfo> held = positions.list(account.id()).stream()
                .map(p -> new AutoRecommender.HoldingInfo(p.symbol(),
                        (int) Math.min(Integer.MAX_VALUE, p.freeShares()), p.avgCostCents())).toList();
        String requestedIntent = plan.intent();
        if ("HEDGES".equals(scope) || "HEDGE".equals(requestedIntent) || "EXIT".equals(requestedIntent)) {
            // A cross-symbol complement is not a covered-share hedge. Screen it as a
            // directional package, then preserve HEDGE as the typed relationship.
            requestedIntent = "DIRECTIONAL";
        }
        boolean allow0 = controls != null && Boolean.TRUE.equals(controls.allow0dte());
        var request = new AutoRecommender.AutoRequest(scanUniverse, List.of(planHorizon(plan.context().horizonDays())),
                controls == null ? 4 : controls.maxPicks(), null, null, null, null,
                plan.context().riskMode(), allow0, List.of(requestedIntent), null);
        AutoRecommender.AutoResult raw = auto.run(request, account.buyingPowerCents(), held, world);
        ObjectNode result = flattenPlanScout(plan, scope, raw);
        var saved = planStrategy.saveScout(ownerId(ctx), plan, scope, Json.MAPPER.valueToTree(request), result);
        ctx.json(Map.of("plan", plan, "scout", saved));
    }

    private static ObjectNode flattenPlanScout(io.liftandshift.strikebench.plan.Plan.View plan, String scope,
                                               AutoRecommender.AutoResult raw) {
        ObjectNode result = Json.MAPPER.createObjectNode();
        result.put("symbol", plan.symbol()); result.put("scope", scope);
        if (plan.context().thesis() != null) result.put("thesis", plan.context().thesis());
        result.put("horizon", planHorizon(plan.context().horizonDays()));
        result.put("riskMode", plan.context().riskMode());
        result.put("intent", plan.intent()); result.put("riskBudgetCents", raw.riskBudgetCents());
        result.put("disclaimer", raw.disclaimer());
        ArrayNode candidates = result.putArray("candidates");
        int favorable = 0, mixed = 0, unfavorable = 0, unavailable = 0;
        String wantedThesis = plan.context().thesis() == null ? null : plan.context().thesis().toUpperCase(Locale.ROOT);
        for (AutoRecommender.Pick pick : raw.picks()) {
            if ("PEERS".equals(scope) && wantedThesis != null
                    && !wantedThesis.equalsIgnoreCase(pick.signals().thesis())) continue;
            for (AutoRecommender.HorizonIdeas horizon : pick.horizons()) {
                for (AutoRecommender.ScoredCandidate scored : horizon.candidates()) {
                    ObjectNode candidate = Json.MAPPER.valueToTree(scored.candidate());
                    candidate.put("symbol", pick.symbol()); candidate.put("scoutThesis", pick.signals().thesis());
                    candidate.put("scoutScope", scope); candidate.put("scoutHorizon", horizon.horizon());
                    candidate.put("opportunityScore", pick.opportunityScore());
                    candidate.put("rankingScore", scored.rankingScore());
                    if (scored.targetFit() != null) candidate.put("targetFit", scored.targetFit());
                    if (scored.decisionScore() != null) candidate.put("decisionScore", scored.decisionScore());
                    if (scored.economics() != null) {
                        candidate.set("economics", Json.MAPPER.valueToTree(scored.economics()));
                        candidate.put("economicVerdict", scored.economics().verdict().name());
                        candidate.put("economicPlacement", scored.economics().placement());
                        switch (scored.economics().verdict()) {
                            case FAVORABLE -> favorable++;
                            case MIXED -> mixed++;
                            case UNFAVORABLE -> unfavorable++;
                            case UNAVAILABLE -> unavailable++;
                        }
                    } else unavailable++;
                    candidates.add(candidate);
                }
            }
        }
        result.put("favorableCount", favorable); result.put("mixedCount", mixed);
        result.put("unfavorableCount", unfavorable); result.put("unavailableCount", unavailable);
        result.put("economicMessage", candidates.isEmpty()
                ? "No related symbol matched this Plan's evidence and mechanical screens."
                : "Related symbols remain separate Plans; compare their evidence, tail risk, and capital use before treating one as an alternative.");
        ArrayNode notes = result.putArray("notes");
        raw.notes().forEach(notes::add);
        raw.skipped().stream().limit(8).forEach(skip -> notes.add("Skipped: " + skip));
        return result;
    }

    private void planScoutSpawn(Context ctx) {
        var request = requireBody(bodyOrNull(ctx, PlanScoutSpawnRequest.class));
        if (request.clientRequestId() == null || request.clientRequestId().isBlank()
                || request.candidateId() == null || request.candidateId().isBlank()) {
            throw new IllegalArgumentException("clientRequestId and candidateId are required");
        }
        var origin = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, origin);
        ObjectNode candidate = planStrategy.scoutedCandidate(ownerId(ctx), origin.id(), request.candidateId());
        String symbol = candidate.path("symbol").asText();
        String role = request.role() == null ? "ALTERNATIVE" : request.role().trim().toUpperCase(Locale.ROOT);
        String childIntent = "HEDGE".equals(role) ? "DIRECTIONAL" : candidate.path("intent").asText(origin.intent());
        var childRequest = new io.liftandshift.strikebench.plan.Plan.CreateRequest(request.clientRequestId(),
                symbol, childIntent, origin.id(), null, candidate.path("scoutThesis").asText(origin.context().thesis()),
                origin.context().horizonDays(), origin.context().targetCents(), origin.context().riskMode(),
                null, null, origin.context().priceAssumptionCents());
        var child = planSvc.create(ownerId(ctx), origin.marketKind(), origin.worldId(), origin.accountId(), childRequest);
        planSvc.linkRelated(ownerId(ctx), origin.id(), child.id(), role);
        if (planStrategy.selectedCandidate(ownerId(ctx), child.id()) == null) {
            planStrategy.copyScoutSelection(ownerId(ctx), origin.id(), request.candidateId(), child);
        }
        child = planSvc.get(ownerId(ctx), child.id());
        ctx.json(Map.of("origin", origin, "plan", child, "role", role));
    }

    private void planOutcomesLatest(Context ctx) {
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        ObjectNode out = planOutcomes.latest(ownerId(ctx), plan, analysisCtx(ctx));
        JsonNode selected = planStrategy.selectedCandidate(ownerId(ctx), plan.id());
        if (selected != null) out.set("selected", selected);
        ctx.json(out);
    }

    /** Evidence owns path generation; Outcomes later values the exact selected package on this artifact. */
    private void planEnsembleRun(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanEnsembleRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        var spec = planScenarioSpec(plan, body.over());
        var world = worldParam(activeWorld(ctx));
        var marketVol = atmVolOf(plan.symbol(), world, spec.horizonDays());
        var calibrated = spec.volAnnual() > 0 || marketVol == null ? spec : spec.withVol(marketVol.atmIv());
        double rate = market.riskFreeRateQuote(Math.max(1, calibrated.horizonDays()), world).annualRate();
        var run = simEngine.previewRun(plan.symbol(), calibrated, world, analysisCtx(ctx),
                body.levels() == null ? List.of() : body.levels(), marketVol, rate);
        var iv = body.iv() == null ? defaultPlanIv(run.ensemble().spec(), marketVol) : body.iv();
        JsonNode input = Json.MAPPER.valueToTree(body);
        var stored = planOutcomes.saveEnsemble(ownerId(ctx), plan, run.ensemble(), iv, rate, run.preview(), input);
        ObjectNode preview = Json.MAPPER.valueToTree(run.preview());
        preview.put("planEnsembleId", stored.id());
        preview.put("planEnsembleFingerprint", stored.fingerprint());
        if (preview.path("receipt") instanceof ObjectNode receipt) receipt.put("fingerprint", stored.fingerprint());
        ctx.json(Map.of("plan", plan, "ensemble", Map.of("id", stored.id(), "fingerprint", stored.fingerprint(),
                "basis", stored.basis()), "preview", preview));
    }

    private void planOutcomeRun(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanOutcomeRunRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
        var position = planOutcomePosition(candidate);
        String basisName = body.basis() == null ? "PARAMETRIC" : body.basis().trim().toUpperCase(Locale.ROOT);
        JsonNode input = Json.MAPPER.valueToTree(body);
        if ("RISK_NEUTRAL".equals(basisName)) {
            var request = new io.liftandshift.strikebench.outcomes.OutcomeContract.Request(
                    io.liftandshift.strikebench.outcomes.OutcomeContract.Operation.POSITION,
                    io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.RISK_NEUTRAL,
                    planOutcomeContext(ctx, plan), position, null, null, null, null, null, null);
            var evaluated = evaluateOutcomes(ctx, request);
            JsonNode result = Json.MAPPER.valueToTree(evaluated.result());
            var saved = planOutcomes.saveRiskNeutral(ownerId(ctx), plan, body.expectedVersion(),
                    candidate.path("id").asText(), result, input, evaluated.interpretation(), analysisCtx(ctx));
            ctx.json(Map.of("plan", plan, "outcome", saved));
            return;
        }
        io.liftandshift.strikebench.sim.PathEnsembleService.Basis basis;
        try { basis = io.liftandshift.strikebench.sim.PathEnsembleService.Basis.valueOf(basisName); }
        catch (Exception e) { throw new IllegalArgumentException("basis must be RISK_NEUTRAL, PARAMETRIC, HISTORICAL_ANALOGS, or CONDITIONAL_BOOTSTRAP"); }
        var stored = resolvePlanEnsemble(ctx, plan, body, basis);
        var legs = toSimLegs(ctx, position.legs());
        var simRequest = new StrategySimRequest(plan.symbol(), legs, position.qty(), stored.ensemble().spec(),
                stored.iv(), basis, null, position.entryCostCents(), contractExpirations(position.legs()));
        JsonNode result = Json.MAPPER.valueToTree(simStrategyResult(ctx, simRequest, stored.ensemble()));
        String interpretation = switch (basis) {
            case PARAMETRIC -> "The exact selected package is repriced on the same stored model ensemble shown in Evidence.";
            case HISTORICAL_ANALOGS -> "The exact selected package is repriced over the Plan's stored matching historical occurrences.";
            case CONDITIONAL_BOOTSTRAP -> "The exact selected package is repriced over whole-path resamples of the Plan's stored analog sample.";
        };
        var saved = planOutcomes.savePathOutcome(ownerId(ctx), plan, body.expectedVersion(),
                candidate.path("id").asText(), stored, result, input, interpretation);
        ctx.json(Map.of("plan", plan, "outcome", saved,
                "ensemble", Map.of("id", stored.id(), "fingerprint", stored.fingerprint(), "basis", stored.basis())));
    }

    /** Compare the Plan's current proposals on one exact stored path artifact. */
    private void planOutcomeCompare(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanOutcomeCompareRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        String basisName = body.basis() == null ? "PARAMETRIC" : body.basis().trim().toUpperCase(Locale.ROOT);
        io.liftandshift.strikebench.sim.PathEnsembleService.Basis basis;
        try { basis = io.liftandshift.strikebench.sim.PathEnsembleService.Basis.valueOf(basisName); }
        catch (Exception e) {
            throw new IllegalArgumentException("comparison basis must be PARAMETRIC, HISTORICAL_ANALOGS, or CONDITIONAL_BOOTSTRAP");
        }
        var stored = resolvePlanEnsemble(ctx, plan,
                new PlanOutcomeRunRequest(body.expectedVersion(), basisName, body.ensembleId(), body.over(), body.iv()), basis);

        java.util.Set<String> wanted = body.candidateIds() == null || body.candidateIds().isEmpty()
                ? null : body.candidateIds().stream().filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        LinkedHashMap<String, ObjectNode> field = new LinkedHashMap<>();
        var competition = planStrategy.latestCompetition(ownerId(ctx), plan.id());
        if (competition != null) for (JsonNode node : competition.result().path("candidates")) {
            if (!(node instanceof ObjectNode candidate)) continue;
            String id = candidate.path("id").asText();
            if (!id.isBlank() && (wanted == null || wanted.contains(id))) field.put(id, candidate);
        }
        JsonNode selectedNode = planStrategy.selectedCandidate(ownerId(ctx), plan.id());
        if (selectedNode instanceof ObjectNode selected) {
            String id = selected.path("id").asText();
            if (!id.isBlank() && (wanted == null || wanted.contains(id))) field.putIfAbsent(id, selected);
        }
        if (field.isEmpty()) throw new IllegalStateException("Run the Strategy comparison before comparing proposal outcomes.");
        if (field.size() > 32) throw new IllegalArgumentException("at most 32 current Plan proposals can be compared together");

        List<io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem> simItems = new ArrayList<>();
        LinkedHashMap<String, PlanComparisonMeta> metadata = new LinkedHashMap<>();
        LinkedHashMap<String, String> earlyRefusals = new LinkedHashMap<>();
        for (ObjectNode candidate : field.values()) {
            String id = candidate.path("id").asText();
            int qty = Math.clamp(candidate.path("qty").asInt(1), 1, 100);
            var position = planOutcomePosition(candidate);
            long fees = candidate.path("economics").path("estimatedRoundTripFeesCents").isNumber()
                    ? candidate.path("economics").path("estimatedRoundTripFeesCents").longValue()
                    : position.legs().stream().filter(leg -> !"STOCK".equalsIgnoreCase(leg.type()))
                    .mapToLong(leg -> Math.max(1, leg.ratio())).sum() * qty * cfg.feePerContractCents() * 2L
                    + (position.legs().isEmpty() ? 0 : cfg.feePerOrderCents() * 2L);
            metadata.put(id, new PlanComparisonMeta(candidate, position, qty, fees));
            try {
                var simLegs = toSimLegs(ctx, position.legs());
                String problem = validateSimLegs(simLegs);
                if (problem != null) {
                    earlyRefusals.put(id, problem);
                    continue;
                }
                simItems.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem(
                        id, simLegs, position.entryCostCents(),
                        "entry fixed to the Plan proposal's captured executable package", fees, qty));
            } catch (RuntimeException e) {
                earlyRefusals.put(id, io.liftandshift.strikebench.sim.ScenarioSimulator.publicReason(e));
            }
        }

        LinkedHashMap<String, io.liftandshift.strikebench.sim.ScenarioSimulator.SimResult> results = new LinkedHashMap<>();
        LinkedHashMap<String, String> refusals = new LinkedHashMap<>(earlyRefusals);
        if (!simItems.isEmpty()) {
            var compared = new io.liftandshift.strikebench.sim.ScenarioSimulator().compare(
                    stored.ensemble(), simItems, 1, stored.iv(), stored.rateAnnual());
            for (var outcome : compared.report().results()) results.put(outcome.key(), outcome.result());
            for (var refusal : compared.report().refused()) refusals.put(refusal.key(), refusal.reason());
        }

        List<io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem> items = new ArrayList<>();
        for (var entry : metadata.entrySet()) {
            String id = entry.getKey();
            PlanComparisonMeta meta = entry.getValue();
            ObjectNode candidate = meta.candidate();
            var result = results.get(id);
            Long p5 = result == null ? null : result.p5Cents();
            Long expected = result == null ? null : result.expectedPnlCents();
            Double tailScore = expected == null || p5 == null ? null
                    : expected.doubleValue() / Math.max(100.0, Math.max(0.0, -p5.doubleValue()));
            String display = candidate.path("displayName").asText(candidate.path("strategy").asText("Structure"));
            items.add(new io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem(
                    id, id, 0, candidate.path("strategy").asText("CUSTOM"), display, meta.qty(),
                    meta.position().entryCostCents(), candidate.hasNonNull("maxLossCents")
                            ? candidate.path("maxLossCents").longValue() : null,
                    result == null ? null : result.winRatePct(), expected, p5,
                    result == null ? null : result.p50Cents(), result == null ? null : result.p95Cents(),
                    tailScore, meta.roundTripFees(), candidate.path("economicVerdict").asText(null),
                    candidate.path("economicPlacement").asText(null),
                    candidate.hasNonNull("structurallyEligible") ? candidate.path("structurallyEligible").asBoolean() : null,
                    candidate.hasNonNull("decisionScore") ? candidate.path("decisionScore").doubleValue() : null,
                    candidate.path("selected").asBoolean(false), refusals.get(id)));
        }
        items.add(new io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem(
                "CASH", null, 0, "CASH", "Keep cash", 0, 0L, 0L,
                null, 0L, 0L, 0L, 0L, 0.0, 0L, null, "BASELINE", true, null, false, null));
        items.sort((a, b) -> {
            if ((a.refusalReason() != null) != (b.refusalReason() != null)) return a.refusalReason() == null ? -1 : 1;
            double as = a.tailReturnScore() == null ? Double.NEGATIVE_INFINITY : a.tailReturnScore();
            double bs = b.tailReturnScore() == null ? Double.NEGATIVE_INFINITY : b.tailReturnScore();
            int score = Double.compare(bs, as);
            if (score != 0) return score;
            return a.displayName().compareToIgnoreCase(b.displayName());
        });
        List<io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem> ranked = new ArrayList<>();
        int rank = 0;
        for (var item : items) ranked.add(new io.liftandshift.strikebench.plan.PlanOutcomeService.ComparisonItem(
                item.key(), item.candidateId(), ++rank, item.strategy(), item.displayName(), item.qty(),
                item.entryCostCents(), item.maxLossCents(), item.winRatePct(), item.expectedPnlCents(),
                item.p5Cents(), item.p50Cents(), item.p95Cents(), item.tailReturnScore(),
                item.roundTripFeesCents(), item.economicVerdict(), item.economicPlacement(),
                item.mechanicallyEligible(), item.decisionScore(), item.selected(), item.refusalReason()));

        String interpretation = switch (basis) {
            case PARAMETRIC -> "Every current Plan proposal repriced on the exact stored model futures.";
            case HISTORICAL_ANALOGS -> "Every current Plan proposal repriced on the exact matching historical occurrences.";
            case CONDITIONAL_BOOTSTRAP -> "Every current Plan proposal repriced on whole-path resamples of the same analog sample.";
        };
        String fairness = "Same ensemble " + stored.fingerprint() + ", captured proposal entries, quantities, and after-cost convention; cash is the zero-risk baseline.";
        var saved = planOutcomes.saveComparison(ownerId(ctx), plan, body.expectedVersion(), stored, ranked,
                Json.MAPPER.valueToTree(body), interpretation, fairness);
        ctx.json(Map.of("plan", plan, "comparison", saved,
                "ensemble", Map.of("id", stored.id(), "fingerprint", stored.fingerprint(), "basis", stored.basis())));
    }

    private record PlanComparisonMeta(ObjectNode candidate,
                                      io.liftandshift.strikebench.outcomes.OutcomeContract.Position position,
                                      int qty, long roundTripFees) {}

    private void planBacktestRun(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanBacktestRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
        String family = candidate.path("strategy").asText();
        if (family.isBlank() || "CUSTOM".equals(family)) {
            throw new IllegalArgumentException("Historical replay needs a named strategy rule; model futures still test the exact custom package.");
        }
        String engineKind = body.engine() == null ? "single" : body.engine().trim().toLowerCase(Locale.ROOT);
        Object report;
        if ("portfolio".equals(engineKind)) {
            report = backtester.runPortfolio(new Backtester.PortfolioRequest(plan.symbol(), family, body.from(), body.to(),
                    body.targetDte() == null ? plan.context().horizonDays() : body.targetDte(), body.entryEveryDays(),
                    body.maxConcurrent(), body.qty(), body.shortDelta(), body.widthPct(), body.profitTargetPct(),
                    body.stopFraction(), body.rollDte(), body.startingCashCents()), analysisCtx(ctx));
        } else if ("single".equals(engineKind)) {
            report = backtester.run(new Backtester.BacktestRequest(plan.symbol(), family, body.from(), body.to(),
                    body.targetDte() == null ? plan.context().horizonDays() : body.targetDte(), body.entryEveryDays(),
                    body.qty(), body.slippagePct(), body.startingCashCents()), analysisCtx(ctx));
        } else throw new IllegalArgumentException("engine must be single or portfolio");
        JsonNode reportJson = Json.MAPPER.valueToTree(report);
        var saved = planOutcomes.saveBacktest(ownerId(ctx), plan, body.expectedVersion(),
                candidate.path("id").asText(), engineKind, reportJson, Json.MAPPER.valueToTree(body), analysisCtx(ctx));
        ctx.json(Map.of("plan", plan, "backtest", saved, "report", report));
    }

    private void planBacktestGet(Context ctx) {
        String planId = ctx.pathParam("id");
        String backtestId = ctx.pathParam("backtestId");
        planOutcomes.requireBacktest(ownerId(ctx), planId, backtestId);
        ctx.json(backtester.get(backtestId));
    }

    private void planRehearsalsList(Context ctx) {
        ctx.json(planRehearsals.list(ownerId(ctx), ctx.pathParam("id")));
    }

    private void planRehearsalCreate(Context ctx) {
        var body = requireBody(bodyOrNull(ctx,
                io.liftandshift.strikebench.plan.PlanRehearsalService.Request.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        var created = planRehearsals.create(ownerId(ctx), plan, body, analysisCtx(ctx));
        ctx.status(201).json(Map.of("rehearsal", created,
                "plan", planSvc.get(ownerId(ctx), plan.id())));
    }

    private void planDecisionLatest(Context ctx) {
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", plan);
        out.put("selected", planStrategy.selectedCandidate(ownerId(ctx), plan.id()));
        out.put("decision", planDecisions.latest(ownerId(ctx), plan.id()));
        ctx.json(out);
    }

    private void planDecisionPreview(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body);
        Map<String, Object> payload = tradePreviewPayload(ctx, order);
        var first = (io.liftandshift.strikebench.paper.TradePreview) payload.get("preview");
        if (order.proposedNetCents() == null) {
            body = new PlanDecisionRequest(body.expectedVersion(), body.qty(), first.entryNetPremiumCents(),
                    body.feesOverrideCents(), body.acknowledgedRisks(), body.ackToken(), body.note());
            order = planDecisionOrder(plan, candidate, body);
            payload = tradePreviewPayload(ctx, order);
        }
        Map<String, Object> out = new LinkedHashMap<>(payload);
        out.put("plan", plan);
        out.put("selected", candidate);
        out.put("order", Map.of("qty", order.qty(), "proposedNetCents", order.proposedNetCents(),
                "feesOverrideCents", order.feesOverrideCents() == null ? 0L : order.feesOverrideCents()));
        ctx.json(out);
    }

    private void planDecisionTrade(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body);
        Map<String, Object> payload = tradePreviewPayload(ctx, order);
        var decisionInput = planDecisionInput(ctx, plan, body, candidate, payload);
        var prepared = planDecisions.prepareTrade(decisionInput);
        CreatedTrade created = executeTrade(ctx, order, prepared.hook());
        var updated = planSvc.get(ownerId(ctx), plan.id());
        ctx.status(201).json(Map.of("plan", updated, "trade", TradeView.of(created.trade()),
                "warnings", created.verdict().warnings(), "decision", planDecisions.latest(ownerId(ctx), plan.id())));
    }

    private void planDecisionCash(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body);
        Map<String, Object> payload = tradePreviewPayload(ctx, order);
        ObjectNode decision = planDecisions.chooseCash(planDecisionInput(ctx, plan, body, candidate, payload));
        var updated = planSvc.get(ownerId(ctx), plan.id());
        ctx.status(201).json(Map.of("plan", updated, "decision", decision));
    }

    private TradeOpenRequest planDecisionOrder(io.liftandshift.strikebench.plan.Plan.View plan,
                                               ObjectNode candidate, PlanDecisionRequest body) {
        List<LegView> legs = new ArrayList<>();
        for (JsonNode leg : candidate.withArray("legs")) {
            legs.add(new LegView(leg.path("action").asText(), leg.path("type").asText(),
                    leg.path("strike").isMissingNode() || leg.path("strike").isNull() ? null : leg.path("strike").asText(),
                    leg.path("expiration").isMissingNode() || leg.path("expiration").isNull() ? null : leg.path("expiration").asText(),
                    leg.path("ratio").asInt(1), leg.path("entryPrice").isMissingNode() ? null : leg.path("entryPrice").asText(null)));
        }
        int qty = body.qty() == null ? candidate.path("qty").asInt(1) : body.qty();
        return new TradeOpenRequest(plan.symbol(), candidate.path("strategy").asText("CUSTOM"), qty, legs,
                plan.context().thesis(), (plan.context().horizonDays() == null ? 30 : plan.context().horizonDays()) + "d",
                plan.context().riskMode(),
                plan.intent(), candidate.path("usesHeldShares").asBoolean(false),
                candidate.path("recommendationId").asText(null), body.proposedNetCents(), body.feesOverrideCents(),
                "PLAN", null, null, null, false, body.acknowledgedRisks(), body.ackToken());
    }

    @SuppressWarnings("unchecked")
    private io.liftandshift.strikebench.plan.PlanDecisionService.Input planDecisionInput(
            Context ctx, io.liftandshift.strikebench.plan.Plan.View plan, PlanDecisionRequest body,
            ObjectNode candidate, Map<String, Object> payload) {
        return new io.liftandshift.strikebench.plan.PlanDecisionService.Input(ownerId(ctx), plan,
                body.expectedVersion(), candidate.path("id").asText(), currentAccount(ctx),
                (io.liftandshift.strikebench.paper.TradePreview) payload.get("preview"),
                (io.liftandshift.strikebench.eval.EconomicAssessment) payload.get("economics"),
                io.liftandshift.strikebench.paper.AccountRiskContext.load(db, ownerId(ctx)),
                body.qty(),
                body.acknowledgedRisks() == null ? List.of() : body.acknowledgedRisks(), body.note(), analysisCtx(ctx));
    }

    private void planManageGet(Context ctx) {
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        ObjectNode management = planManagement.latest(ownerId(ctx), plan.id());
        String tradeId = management.path("activeTradeId").asText(null);
        if (tradeId == null) {
            for (JsonNode link : management.withArray("links")) if (link.hasNonNull("tradeId")) tradeId = link.get("tradeId").asText();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", plan);
        out.put("decision", planDecisions.latest(ownerId(ctx), plan.id()));
        out.put("management", management);
        if (tradeId != null) out.put("trade", tradeDetailData(tradeId));
        ctx.json(out);
    }

    private void plansPortfolio(Context ctx) {
        String world = worldParam(activeWorld(ctx));
        var marketKind = activePlanMarket(ctx);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var plan : planSvc.list(ownerId(ctx), marketKind,
                marketKind == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED ? world : null, false)) {
            if (plan.status() == io.liftandshift.strikebench.plan.Plan.Status.ARCHIVED) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("plan", plan);
            ObjectNode decision = planDecisions.latest(ownerId(ctx), plan.id());
            if (decision != null) row.put("decision", decision);
            String tradeId = planManagement.activeTradeId(ownerId(ctx), plan.id());
            if (tradeId != null) {
                row.put("tradeId", tradeId);
                try { row.put("mark", trades.currentMark(tradeId)); }
                catch (RuntimeException e) { row.put("markUnavailable", true); }
            }
            rows.add(row);
        }
        ctx.json(Map.of("plans", rows, "market", marketKind.name()));
    }

    private void planManageRefresh(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanManageRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan); requirePlanVersion(plan, body.expectedVersion());
        String tradeId = requirePlanActiveTrade(ctx, plan.id());
        var mark = trades.refresh(tradeId);
        planManagement.recordMark(ownerId(ctx), plan.id(), body.expectedVersion(), tradeId, mark);
        ctx.json(Map.of("plan", planSvc.get(ownerId(ctx), plan.id()), "mark", mark,
                "management", planManagement.latest(ownerId(ctx), plan.id())));
    }

    private void planManageUnwind(Context ctx) { planManageClose(ctx, "CLOSE", false); }
    private void planManageSettle(Context ctx) { planManageClose(ctx, "SETTLE", false); }
    private void planManageRoll(Context ctx) { planManageClose(ctx, "ROLL", true); }

    private void planManageClose(Context ctx, String kind, boolean roll) {
        var body = requireBody(bodyOrNull(ctx, PlanManageRequest.class));
        if (!Boolean.TRUE.equals(body.confirm())) throw new IllegalArgumentException("confirm=true is required");
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan); requirePlanVersion(plan, body.expectedVersion());
        String tradeId = requirePlanActiveTrade(ctx, plan.id());
        var hook = planManagement.lifecycleHook(ownerId(ctx), plan.id(), body.expectedVersion(), kind, roll);
        TradeService.CloseResult result = "SETTLE".equals(kind)
                ? trades.settle(tradeId, true, hook) : trades.unwind(tradeId, true, hook);
        resolveRecommendationForTrade(tradeId, "SETTLE".equals(kind) ? "SETTLED" : "CLOSED",
                decisionPnl(result.trade(), result.realizedPnlCents()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", planSvc.get(ownerId(ctx), plan.id())); out.put("trade", TradeView.of(result.trade()));
        out.put("realizedPnlCents", result.realizedPnlCents());
        out.put("management", planManagement.latest(ownerId(ctx), plan.id()));
        if (roll) out.put("rolledPosition", rolledPosition(result.trade(), worldParam(activeWorld(ctx))));
        ctx.json(out);
    }

    private void planManageVoid(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanManageRequest.class));
        if (!Boolean.TRUE.equals(body.confirm())) throw new IllegalArgumentException("confirm=true is required");
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan); requirePlanVersion(plan, body.expectedVersion());
        String tradeId = requirePlanActiveTrade(ctx, plan.id());
        var hook = planManagement.lifecycleHook(ownerId(ctx), plan.id(), body.expectedVersion(), "VOID", false);
        TradeRecord deleted = trades.delete(tradeId, true, hook);
        ctx.json(Map.of("plan", planSvc.get(ownerId(ctx), plan.id()), "trade", TradeView.of(deleted),
                "management", planManagement.latest(ownerId(ctx), plan.id())));
    }

    private void planManageReview(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanManageRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan); requirePlanVersion(plan, body.expectedVersion());
        ObjectNode decision = planDecisions.latest(ownerId(ctx), plan.id());
        if (decision == null || !"CASH".equals(decision.path("action").asText())) {
            throw new IllegalStateException("This Plan does not have a cash decision to review.");
        }
        ObjectNode metrics = decision.with("metrics");
        int horizon = decision.path("reviewHorizonDays").asInt(30);
        java.time.Instant decidedAt = java.time.OffsetDateTime.parse(decision.path("createdAt").asText()).toInstant();
        java.time.Instant dueAt = decidedAt.plus(java.time.Duration.ofDays(horizon));
        String world = plan.marketKind() == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED
                ? plan.worldId() : plan.marketKind() == io.liftandshift.strikebench.plan.Plan.MarketKind.DEMO ? "demo" : "observed";
        java.time.Instant laneNow = market.simInstant(world).orElse(clock.instant());
        if (laneNow.isBefore(dueAt)) {
            throw new IllegalStateException("This opportunity review is scheduled for "
                    + java.time.LocalDate.ofInstant(dueAt, io.liftandshift.strikebench.market.MarketHours.EASTERN) + ".");
        }
        java.time.LocalDate dueDate = java.time.LocalDate.ofInstant(dueAt,
                io.liftandshift.strikebench.market.MarketHours.EASTERN);
        var series = market.candleSeries(plan.symbol(), dueDate.minusDays(14), dueDate, world,
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED);
        io.liftandshift.strikebench.model.Candle dueBar = series.candles().stream()
                .filter(c -> !c.date().isAfter(dueDate)).max(java.util.Comparator.comparing(
                        io.liftandshift.strikebench.model.Candle::date))
                .orElseThrow(() -> new IllegalStateException("No lane-owned closing price is available for the review horizon."));
        long startUnderlying = metrics.path("underlyingCents").asLong(0);
        if (startUnderlying <= 0) throw new IllegalStateException("The frozen decision has no underlying anchor.");
        long endUnderlying = io.liftandshift.strikebench.util.Money.toCents(dueBar.close());
        long riskCapital = Math.max(startUnderlying, decision.path("maxLossCents").asLong(startUnderlying));
        long shares = Math.max(1, Math.min(10_000, riskCapital / startUnderlying));
        long stockPnl = Math.multiplyExact(endUnderlying - startUnderlying, shares);
        int qty = Math.max(1, (int) Math.round(metrics.path("decisionQty").asDouble(1)));
        double rate = metrics.path("riskFreeRateAnnual").asDouble(Double.NaN);
        if (!Double.isFinite(rate)) throw new IllegalStateException("The frozen decision has no pricing-rate snapshot.");
        long packageEnd = modeledRejectedPackageValue(decision.withArray("legs"), dueBar.close(), dueDate, rate, qty);
        long entry = decision.path("proposedNetCents").asLong();
        long fees = metrics.path("feesOpenCents").asLong(0);
        long rejectedPnl = entry + packageEnd - Math.multiplyExact(fees, 2L);
        ObjectNode management = planManagement.recordCashReview(ownerId(ctx), plan.id(), body.expectedVersion(),
                new io.liftandshift.strikebench.plan.PlanManagementService.CashReview(startUnderlying, endUnderlying,
                        stockPnl, entry, packageEnd, rejectedPnl, horizon,
                        decision.hasNonNull("pop") ? decision.get("pop").asDouble() : null,
                        "Frozen-IV modeled value at the lane-owned horizon close; kept outside trade calibration"));
        ctx.json(Map.of("plan", planSvc.get(ownerId(ctx), plan.id()), "management", management));
    }

    private static long modeledRejectedPackageValue(ArrayNode legs, BigDecimal spot, java.time.LocalDate horizon,
                                                     double rate, int qty) {
        long value = 0;
        for (JsonNode leg : legs) {
            int ratio = Math.max(1, leg.path("ratio").asInt(1));
            long units = Math.multiplyExact(100L, Math.multiplyExact((long) ratio, qty));
            double price;
            if ("STOCK".equals(leg.path("type").asText())) {
                price = spot.doubleValue();
            } else {
                if (!leg.hasNonNull("strikePrice") || !leg.hasNonNull("expiration") || !leg.hasNonNull("iv")) {
                    throw new IllegalStateException("The rejected package lacks a frozen strike, expiration, or IV.");
                }
                double strike = leg.get("strikePrice").asDouble();
                java.time.LocalDate expiry = java.time.LocalDate.parse(leg.get("expiration").asText());
                double years = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(horizon, expiry) / 365.0);
                price = io.liftandshift.strikebench.pricing.BlackScholes.price(
                        "CALL".equals(leg.path("type").asText()), spot.doubleValue(), strike, years,
                        rate, 0, leg.get("iv").asDouble());
            }
            long cents = io.liftandshift.strikebench.util.Money.centsFromPrice(BigDecimal.valueOf(price), units);
            value += "BUY".equals(leg.path("action").asText()) ? cents : -cents;
        }
        return value;
    }

    private String requirePlanActiveTrade(Context ctx, String planId) {
        String tradeId = planManagement.activeTradeId(ownerId(ctx), planId);
        if (tradeId == null) throw new IllegalStateException("This Plan has no active linked position.");
        return tradeId;
    }

    private Map<String, Object> rolledPosition(TradeRecord trade, String world) {
        List<LocalDate> listed = market.expirations(trade.symbol(), world).stream().sorted().toList();
        List<Map<String, Object>> legs = new ArrayList<>();
        for (Leg leg : trade.legs()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", leg.action().name()); row.put("type", leg.isStock() ? "STOCK" : leg.type().name());
            row.put("ratio", leg.ratio());
            if (!leg.isStock()) {
                LocalDate target = leg.expiration().plusDays(28);
                LocalDate next = listed.stream().filter(date -> !date.isBefore(target)).findFirst()
                        .orElseGet(() -> listed.stream().filter(date -> date.isAfter(leg.expiration()))
                                .reduce((a, b) -> b).orElseThrow(() -> new IllegalStateException(
                                        "No later listed expiration is available for this roll.")));
                OptionChain chain = market.chain(trade.symbol(), next, world)
                        .orElseThrow(() -> new IllegalStateException("The later expiration has no option book."));
                List<OptionQuote> side = leg.type() == io.liftandshift.strikebench.model.OptionType.CALL
                        ? chain.calls() : chain.puts();
                BigDecimal strike = side.stream().map(OptionQuote::strike)
                        .min(java.util.Comparator.comparing(value -> value.subtract(leg.strike()).abs()))
                        .orElseThrow(() -> new IllegalStateException("The later expiration has no "
                                + leg.type().name().toLowerCase(Locale.ROOT) + " strikes."));
                row.put("strike", strike.toPlainString());
                row.put("expiration", next.toString());
            }
            legs.add(row);
        }
        return Map.of("symbol", trade.symbol(), "strategy", trade.strategy(), "qty", trade.qty(), "legs", legs,
                "intent", trade.intent() == null ? "DIRECTIONAL" : trade.intent(), "source", "PLAN");
    }

    private io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble resolvePlanEnsemble(
            Context ctx, io.liftandshift.strikebench.plan.Plan.View plan, PlanOutcomeRunRequest body,
            io.liftandshift.strikebench.sim.PathEnsembleService.Basis basis) {
        if (body.ensembleId() != null && !body.ensembleId().isBlank()) {
            var stored = planOutcomes.loadCurrentEnsemble(ownerId(ctx), plan, body.ensembleId(), analysisCtx(ctx));
            if (!basis.name().equals(stored.basis())) throw new IllegalArgumentException("Stored ensemble basis does not match the requested basis");
            return stored;
        }
        var existing = planOutcomes.latestEnsemble(ownerId(ctx), plan, basis.name(), analysisCtx(ctx));
        if (existing != null && basis == io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC
                && body.over() == null && body.iv() == null) return existing;
        var spec = planScenarioSpec(plan, body.over());
        String world = worldParam(activeWorld(ctx));
        double spot = pathEnsembles.anchorSpot(new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(
                plan.symbol(), world, analysisCtx(ctx)));
        io.liftandshift.strikebench.sim.PathEnsembleService.Ensemble ensemble;
        if (basis == io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC) {
            var marketVol = atmVolOf(plan.symbol(), world, spec.horizonDays());
            if (spec.volAnnual() <= 0 && marketVol != null) spec = spec.withVol(marketVol.atmIv());
            ensemble = pathEnsembles.build(new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(
                    plan.symbol(), world, analysisCtx(ctx)), basis, spec, null, spot);
        } else {
            var evidence = planEvidence.latest(ownerId(ctx), plan.id(), analysisCtx(ctx));
            if (evidence == null) throw new IllegalStateException("Run Past evidence in this Plan before using historical analog outcomes.");
            ensemble = pathEnsembles.fromStudy(new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(
                    plan.symbol(), world, analysisCtx(ctx)), basis, spec, evidence.result(), spot);
        }
        double rate = market.riskFreeRateQuote(Math.max(1, ensemble.spec().horizonDays()), world).annualRate();
        var marketVol = atmVolOf(plan.symbol(), world, ensemble.spec().horizonDays());
        var iv = body.iv() == null ? defaultPlanIv(ensemble.spec(), marketVol) : body.iv();
        return planOutcomes.saveEnsemble(ownerId(ctx), plan, ensemble, iv, rate, null, Json.MAPPER.valueToTree(body));
    }

    private ObjectNode selectedPlanCandidate(Context ctx, io.liftandshift.strikebench.plan.Plan.View plan) {
        JsonNode selected = planStrategy.selectedCandidate(ownerId(ctx), plan.id());
        if (!(selected instanceof ObjectNode candidate)) {
            throw new IllegalStateException("Select a structure in Strategy before running Outcomes.");
        }
        return candidate;
    }

    private static io.liftandshift.strikebench.outcomes.OutcomeContract.Position planOutcomePosition(JsonNode candidate) {
        List<io.liftandshift.strikebench.outcomes.OutcomeContract.Leg> legs = new ArrayList<>();
        for (JsonNode leg : candidate.path("legs")) {
            String type = leg.path("type").asText();
            legs.add(new io.liftandshift.strikebench.outcomes.OutcomeContract.Leg(
                    leg.path("action").asText(), type,
                    "STOCK".equalsIgnoreCase(type) ? BigDecimal.ZERO : new BigDecimal(leg.path("strike").asText()),
                    leg.path("expiration").asText(null), null, Math.max(1, leg.path("ratio").asInt(1))));
        }
        Long entryNet = candidate.hasNonNull("entryNetPremiumCents") ? candidate.path("entryNetPremiumCents").longValue() : null;
        return new io.liftandshift.strikebench.outcomes.OutcomeContract.Position(candidate.path("id").asText(), legs,
                Math.max(1, candidate.path("qty").asInt(1)), entryNet == null ? null : -entryNet);
    }

    private io.liftandshift.strikebench.outcomes.OutcomeContract.MarketContext planOutcomeContext(
            Context ctx, io.liftandshift.strikebench.plan.Plan.View plan) {
        var analysis = analysisCtx(ctx);
        return new io.liftandshift.strikebench.outcomes.OutcomeContract.MarketContext(plan.symbol(),
                activePlanMarket(ctx).name(), activeWorld(ctx), analysis.datasetId(), null);
    }

    private static void requirePlanVersion(io.liftandshift.strikebench.plan.Plan.View plan, Long expected) {
        if (expected == null) throw new IllegalArgumentException("expectedVersion is required");
        if (plan.version() != expected) throw new IllegalStateException("This Plan changed in another tab. Reload it before running Outcomes.");
    }

    private static io.liftandshift.strikebench.sim.ScenarioSpec planScenarioSpec(
            io.liftandshift.strikebench.plan.Plan.View plan,
            io.liftandshift.strikebench.sim.ScenarioSpec raw) {
        int days = plan.context().horizonDays() == null ? 30 : plan.context().horizonDays();
        var base = raw == null
                ? io.liftandshift.strikebench.sim.ScenarioSpec.preset(
                    io.liftandshift.strikebench.sim.ScenarioSpec.Shape.CHOP, days, 0, 4242L, 500)
                : raw;
        return new io.liftandshift.strikebench.sim.ScenarioSpec(base.model(), base.shape(), days,
                base.stepsPerDay(), base.driftAnnual(), base.volAnnual(), base.jumpsPerYear(),
                base.jumpMean(), base.jumpVol(), base.tailNu(), base.heston(), base.seed(), base.paths()).sane();
    }

    private static io.liftandshift.strikebench.sim.IvSpec defaultPlanIv(
            io.liftandshift.strikebench.sim.ScenarioSpec spec,
            io.liftandshift.strikebench.sim.SimulationEngine.MarketVolInput marketVol) {
        double anchor = marketVol != null && marketVol.atmIv() > 0 ? marketVol.atmIv() : spec.sane().volAnnual();
        return spec.sane().shape() == io.liftandshift.strikebench.sim.ScenarioSpec.Shape.EVENT_JUMP
                ? io.liftandshift.strikebench.sim.IvSpec.eventCrushAround(anchor,
                    Math.max(1, Math.round(spec.sane().horizonDays() / 3.0f)))
                : io.liftandshift.strikebench.sim.IvSpec.flat(anchor);
    }

    private static String planHorizon(Integer days) {
        if (days == null) return "month";
        if (days <= 1) return "0DTE";
        if (days <= 10) return "week";
        if (days <= 45) return "month";
        return "quarter";
    }

    // ---- Data Center ----

    /** One call for the Data Center header: engine health + coverage summary + mode. Never 500s. */
    private void dataOverview(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        try { out.put("engine", marketEngine.status()); } catch (Exception e) { out.put("engine", null); }
        try { out.put("coverage", dataCoverage.summary()); } catch (Exception e) { out.put("coverage", null); }
        try { out.put("jobs", dataJobs.recent(ownerId(ctx), isAdmin(ctx), 8)); } catch (Exception e) { out.put("jobs", List.of()); }
        out.put("fixturesOnly", cfg.fixturesOnly());
        out.put("marketLane", io.liftandshift.strikebench.market.MarketLane
                .of(activeWorld(ctx), cfg.fixturesOnly()).name());
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
        boolean yahooEligible = cfg.yahooEnabled() && cfg.yahooAutomationPermissionConfirmed();
        sources.add(source("Yahoo Finance automation", "Equity/ETF/index candles", yahooEligible,
                "permission-required · PERSONAL / local-clone only",
                yahooEligible
                        ? "Authorized local automation is enabled and governed by a durable request budget."
                        : "Off by default. Enabling the endpoint alone is insufficient; automated collection requires permission confirmation. Prefer a user-exported CSV or official keyed API."));
        sources.add(source("Alpha Vantage", "Daily equity/ETF candles", !cfg.alphaVantageApiKey().isBlank(),
                "official keyed API · plan terms apply", "Set ALPHAVANTAGE_API_KEY. Compact access supplies recent daily rows; full history requires an entitled plan."));
        sources.add(source("Polygon", "Historical option chains", !cfg.polygonApiKey().isBlank(),
                "keyed · internal-use", "Set POLYGON_API_KEY for real historical chains used by the backtester's observed tier."));
        sources.add(source("Stooq", "Equity EOD candles", !fx, "keyless (bot-blocked for us)",
                "Keyless CSV, but Stooq serves an anti-bot wall to non-browser clients — usually falls through."));
        sources.add(source("SEC EDGAR", "Filings (10-K/10-Q/8-K)", !fx, "keyless · public", "Keyless with a contact User-Agent. Corporate filings for the news feed."));
        sources.add(source("Google News RSS", "Headlines", !fx && !cfg.newsRssBaseUrl().isBlank(), "keyless · public", "Keyless per-symbol headlines."));
        sources.add(source("Treasury / FRED", "Risk-free rates", !fx, "keyless / keyed", "Treasury is keyless; FRED needs FRED_API_KEY."));
        sources.add(source("Historical options CSV", "Owned options history", true, "licensed · internal-use (no redistribution)",
                "Import a licensed vendor CSV (ORATS / Cboe DataShop / Databento) via a backfill job — the 'own the past' path."));
        sources.add(source("Built-in demo market", "Deterministic fabricated teaching data", fx, "demo",
                "Available only when you explicitly enter Demo market; never used as an Observed fallback."));
        ctx.json(Map.of("sources", sources, "connectors", dataConnectors.all(),
                "recommendedCandleSource", dataConnectors.recommendedSource(), "fixturesOnly", fx));
    }

    public record DataSyncPlanRequest(List<String> symbols, String source, String from, String to, Integer years) {}
    public record DataSyncScheduleRequest(Boolean enabled, String source, List<String> symbols,
                                          Integer years) {}

    private void dataSyncStatus(Context ctx) {
        var schedule = dataSyncState.schedule(ownerId(ctx));
        ctx.json(Map.of(
                "connectors", dataConnectors.all(),
                "recommendedSource", dataConnectors.recommendedSource(),
                "cursors", dataSyncState.cursors(ownerId(ctx)),
                "schedule", schedule,
                "quarantine", dataSyncState.quarantineSummary(ownerId(ctx)),
                "latestCompletedSession", io.liftandshift.strikebench.db.DataSyncScheduler
                        .latestCompletedSession(clock).toString(),
                "note", "Daily price maintenance runs once after a completed market session. Hourly daily-bar downloads are intentionally avoided."));
    }

    private void dataSyncPlan(Context ctx) {
        DataSyncPlanRequest b = requireBody(bodyOrNull(ctx, DataSyncPlanRequest.class));
        LocalDate to = b.to() == null || b.to().isBlank()
                ? io.liftandshift.strikebench.db.DataSyncScheduler.latestCompletedSession(clock)
                : LocalDate.parse(b.to());
        LocalDate latestCompleted = io.liftandshift.strikebench.db.DataSyncScheduler.latestCompletedSession(clock);
        boolean futureCapped = to.isAfter(latestCompleted);
        if (futureCapped) to = latestCompleted;
        int years = b.years() == null ? 5 : Math.max(1, Math.min(20, b.years()));
        LocalDate from = b.from() == null || b.from().isBlank() ? to.minusYears(years) : LocalDate.parse(b.from());
        String source = b.source() == null || b.source().isBlank() ? "auto" : b.source();
        var connector = dataConnectors.requireAutomated(source);
        LocalDate effectiveFrom = from;
        String limitation = null;
        if ("alphavantage".equals(connector.key()) && !cfg.alphaVantageFullHistoryEnabled()
                && effectiveFrom.isBefore(to.minusDays(160))) {
            effectiveFrom = to.minusDays(160);
            limitation = "Compact Alpha Vantage access covers only the recent window. Use an entitled full-history plan or a user-owned CSV for older dates.";
        }
        List<String> symbols = normalizeSymbols(b.symbols());
        if (symbols.isEmpty()) symbols = universe.active().symbols();
        var planner = new io.liftandshift.strikebench.db.MissingRangePlanner(db);
        List<Map<String, Object>> plans = new ArrayList<>();
        int requests = 0, missing = 0;
        for (String symbol : symbols.stream().distinct().limit(120).toList()) {
            var plan = planner.plan(symbol, effectiveFrom, to, connector.key());
            int symbolRequests = "alphavantage".equals(connector.key()) && !plan.complete()
                    ? 1 : plan.ranges().size();
            requests += symbolRequests; missing += plan.missingSessions();
            plans.add(Map.of("symbol", symbol, "existingSessions", plan.existingSessions(),
                    "missingSessions", plan.missingSessions(), "requests", symbolRequests,
                    "complete", plan.complete(), "ranges", plan.ranges()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", connector); out.put("requestedFrom", from); out.put("effectiveFrom", effectiveFrom);
        out.put("to", to); out.put("symbols", symbols.size()); out.put("missingSessions", missing);
        out.put("estimatedRequests", requests); out.put("plans", plans);
        if (limitation != null) out.put("limitation", limitation);
        if (futureCapped) out.put("dateNote", "The end date was capped at the latest completed market session.");
        ctx.json(out);
    }

    private void dataSyncSchedulePut(Context ctx) {
        requireAdmin(ctx); // schedules mutate the app-wide observed store and consume provider allowance
        DataSyncScheduleRequest b = requireBody(bodyOrNull(ctx, DataSyncScheduleRequest.class));
        boolean enabled = Boolean.TRUE.equals(b.enabled());
        String source = b.source() == null ? "auto" : b.source();
        if (enabled) dataConnectors.requireAutomated(source);
        List<String> symbols = normalizeSymbols(b.symbols());
        if (symbols.isEmpty()) symbols = universe.active().symbols();
        ctx.json(dataSyncState.saveSchedule(ownerId(ctx), enabled, source, symbols,
                b.years() == null ? 5 : b.years()));
    }

    private void dataUnderlyingImport(Context ctx) throws java.io.IOException {
        requireAdmin(ctx); // observed history is app-wide; public users may not mutate it
        ctx.multipartConfig().maxFileSize(25, io.javalin.config.SizeUnit.MB);
        ctx.multipartConfig().maxTotalRequestSize(26, io.javalin.config.SizeUnit.MB);
        io.javalin.http.UploadedFile file = ctx.uploadedFile("file");
        if (file == null) throw new IllegalArgumentException("CSV file is required");
        if (file.size() > 25L * 1024 * 1024) throw new IllegalArgumentException("CSV file exceeds 25 MB");
        String basisRaw = ctx.formParam("basis");
        io.liftandshift.strikebench.db.UnderlyingCsvIngest.Basis basis;
        try { basis = io.liftandshift.strikebench.db.UnderlyingCsvIngest.Basis.valueOf(
                basisRaw == null ? "AUTO" : basisRaw.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("basis must be AUTO, RAW, or ADJUSTED"); }
        var result = io.liftandshift.strikebench.db.UnderlyingCsvIngest.run(file.content(), file.filename(),
                ctx.formParam("symbol"), ctx.formParam("sourceLabel"), basis, db, dataSyncState, clock, ownerId(ctx));
        invalidateHistoricalViews();
        ctx.json(result);
    }

    private static List<String> normalizeSymbols(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream().filter(java.util.Objects::nonNull).map(String::trim)
                .filter(s -> !s.isBlank()).map(s -> s.toUpperCase(Locale.ROOT))
                .filter(s -> s.matches("[A-Z0-9.^_-]{1,20}"))
                .distinct().limit(120).toList();
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
        // Both jobs mutate app-wide observed history. The server-path import also reads a local file.
        if (privilegedDataJobKind(b.kind())) requireAdmin(ctx);
        ctx.json(dataJobs.start(b.kind(), b.params(), ownerId(ctx)));
    }

    private void dataJobCancel(Context ctx) {
        String id = ctx.pathParam("id");
        requireJobAccess(ctx, id);
        if (privilegedDataJobKind(dataJobs.kindOf(id))) requireAdmin(ctx);
        dataJobs.cancel(id);
        ctx.json(Map.of("ok", true));
    }

    private void dataJobRetry(Context ctx) {
        String id = ctx.pathParam("id");
        requireJobAccess(ctx, id);
        // Re-running a privileged CSV import is itself privileged, even for the job's owner.
        if (privilegedDataJobKind(dataJobs.kindOf(id))) requireAdmin(ctx);
        ctx.json(dataJobs.retry(id, ownerId(ctx)));
    }

    static boolean privilegedDataJobKind(String kind) {
        return "import_options_csv".equalsIgnoreCase(kind)
                || "sync_underlying".equalsIgnoreCase(kind);
    }

    // ---- Datasets & scenario simulation ----

    public record ScenarioRequest(String symbol, io.liftandshift.strikebench.sim.ScenarioSpec spec,
                                  List<io.liftandshift.strikebench.sim.SimulationEngine.DecisionLevel> levels) {}

    public record StrategySimRequest(String symbol,
                                     java.util.List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> legs,
                                     Integer qty,
                                     io.liftandshift.strikebench.sim.ScenarioSpec spec,
                                     io.liftandshift.strikebench.sim.IvSpec iv,
                                     io.liftandshift.strikebench.sim.PathEnsembleService.Basis pathBasis,
                                     io.liftandshift.strikebench.research.ResearchQuestionEngine.RunRequest study,
                                     // Signed TOTAL opening value: debit positive, credit negative.
                                     // Builder/Review can preserve the exact displayed package price.
                                     Long entryCostCents,
                                     // Optional leg-aligned ISO expirations. When present, the
                                     // listed package is exact: no neighboring expiry/strike snap.
                                     java.util.List<String> contractExpirations) {}

    public record ActiveDatasetRequest(String id) {}

    private void datasetSetActive(Context ctx) {
        // PER-USER selection: activating a dataset changes only the CALLER's read path, so no
        // admin gate is needed anymore — ownership (yours or observed) is the whole rule.
        ActiveDatasetRequest b = requireBody(bodyOrNull(ctx, ActiveDatasetRequest.class));
        String owner = ownerId(ctx);
        // ONE market mode at a time (holistic review P0): a saved scenario dataset and a live
        // simulated world are different worlds — layering one on the other silently blends them.
        String activeMarket = activeWorld(ctx);
        if (!io.liftandshift.strikebench.db.DatasetService.OBSERVED.equals(b.id())
                && !"observed".equals(activeMarket) && !"demo".equals(activeMarket)) {
            throw new IllegalStateException("You are inside a simulated market session — return to the "
                    + "baseline market before activating a scenario dataset (they are separate worlds).");
        }
        datasets.setActive(b.id(), owner);
        String nowActive = datasets.activeId(owner);
        ctx.json(Map.of("ok", true, "active", nowActive,
                "scenarioMode", !io.liftandshift.strikebench.db.DatasetService.OBSERVED.equals(nowActive)));
    }

    public record CompareStructure(String key,
                                   List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> legs,
                                   Long entryCostCents,
                                   List<String> contractExpirations) {}
    public record CompareRequest(String symbol, io.liftandshift.strikebench.sim.ScenarioSpec spec,
                                 io.liftandshift.strikebench.sim.IvSpec iv, Integer qty,
                                 List<CompareStructure> structures,
                                 io.liftandshift.strikebench.sim.PathEnsembleService.Basis pathBasis,
                                 io.liftandshift.strikebench.research.ResearchQuestionEngine.RunRequest study) {}

    /**
     * The comparative-evidence engine: every requested structure priced on the SAME seeded path
     * set (one generation, one budget permit), entries resolved to exact listed contracts where a
     * chain matches, refusals reported by name. One COMPARE operation replaces N sequential
     * POSITION evaluations that would each regenerate identical paths.
     */
    private Object simCompareResult(Context ctx, CompareRequest b) {
        if (b.spec() == null) throw new IllegalArgumentException("spec is required");
        if (b.structures() == null || b.structures().isEmpty()) throw new IllegalArgumentException("structures are required");
        if (b.structures().size() > 30) throw new IllegalArgumentException("at most 30 structures");
        String sym = b.symbol() == null ? "" : b.symbol().trim().toUpperCase(Locale.ROOT);
        if (sym.isEmpty()) throw new IllegalArgumentException("symbol is required");
        String world = worldParam(activeWorld(ctx));
        EntryBook book = new EntryBook(sym, world); // one captured entry book for every structure
        double spot = book.quote()
                .map(q -> q.mark()).filter(java.util.Objects::nonNull)
                .map(java.math.BigDecimal::doubleValue).filter(v -> v > 0)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No price for " + sym + " — a simulation needs a real (or demo) quote to anchor on."));
        int qty = b.qty() == null ? 1 : Math.clamp(b.qty(), 1, 100);
        double r = market.riskFreeRateQuote(Math.max(1, b.spec().sane().horizonDays()), world).annualRate();
        io.liftandshift.strikebench.sim.ScenarioSpec spec = calibrateVol(sym, b.spec(), world);
        io.liftandshift.strikebench.sim.IvSpec iv = b.iv();
        if (iv == null) {
            Double atm = atmIvOf(sym, world);
            iv = io.liftandshift.strikebench.sim.IvSpec.flat(atm != null ? atm : spec.sane().volAnnual());
        }
        List<io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem> items = new ArrayList<>();
        List<Map<String, Object>> refusedEarly = new ArrayList<>();
        for (CompareStructure st : b.structures()) {
            String legProblem = validateSimLegs(st.legs());
            if (legProblem != null) {
                refusedEarly.add(Map.of("key", st.key() == null ? "?" : st.key(), "reason", legProblem));
                continue;
            }
            MarketEntry me = marketEntry(sym, st.legs(), qty, world, book,
                    st.contractExpirations());
            if (st.contractExpirations() != null && me == null) {
                refusedEarly.add(Map.of("key", st.key() == null ? "?" : st.key(),
                        "reason", "one of the exact listed contracts is unavailable at an executable price"));
                continue;
            }
            var legsToRun = me != null && me.resolvedLegs() != null ? me.resolvedLegs() : st.legs();
            items.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem(
                    st.key(), legsToRun,
                    st.entryCostCents() != null ? st.entryCostCents() : me == null ? null : me.entryCents(),
                    st.entryCostCents() != null ? "entry fixed to the supplied package price"
                            : me == null ? null : "entry at " + me.source() + " executable quotes",
                    scenarioRoundTripFees(legsToRun, qty)));
        }
        var pathBasis = b.pathBasis() == null
                ? io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC : b.pathBasis();
        var comparison = new io.liftandshift.strikebench.sim.ScenarioSimulator().compare(
                pathEnsembles,
                new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(sym, world, analysisCtx(ctx)),
                pathBasis, spec, b.study(), spot, items, qty, iv, r);
        var report = comparison.report();
        var evStudy = comparison.ensemble().study();
        List<Map<String, Object>> refused = new ArrayList<>(refusedEarly);
        report.refused().forEach(x -> refused.add(Map.of("key", x.key(), "reason", x.reason())));
        // R9: fees ride each outcome so the ranking can be judged NET of round-trip commissions.
        List<Map<String, Object>> feeAware = new ArrayList<>();
        for (var oc : report.results()) {
            // ONE fee convention product-wide (ledger rule): OPTION contracts only, ratio-aware —
            // a 1x2 backspread pays for 3 contracts, a buy-write's stock leg pays none.
            long fees = items.stream().filter(it -> it.key().equals(oc.key()))
                    .findFirst().map(io.liftandshift.strikebench.sim.ScenarioSimulator.CompareItem::roundTripFeesCents)
                    .orElse(0L);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", oc.key());
            m.put("result", oc.result());
            m.put("feesCents", fees);
            feeAware.add(m);
        }
        Map<String, Object> outCmp = new LinkedHashMap<>();
        outCmp.put("results", feeAware);
        outCmp.put("refused", refused);
        outCmp.put("volAnnual", spec.sane().volAnnual());
        outCmp.put("pathModelVersion", comparison.ensemble().modelVersion());
        // The alternatives every structure must beat + the fairness contract, disclosed.
        outCmp.put("cashBaseline", Map.of("key", "CASH", "note",
                "Doing nothing: $0 expected, $0 at risk, zero costs — any structure below a coin flip after costs loses to this."));
        if (evStudy != null) {
            outCmp.put("pathSource", pathBasis.name());
            outCmp.put("studyKey", evStudy.studyKey());
            outCmp.put("analogEvents", evStudy.eventDates() == null ? 0 : evStudy.eventDates().size());
            outCmp.put("observed", evStudy.observed());
            outCmp.put("fairness", "one quote snapshot, ONE historical analog ensemble ("
                    + (evStudy.observed() ? "real past occurrences" : "demo/generated history — not real")
                    + ") — every structure judged on the same conditional sample");
        } else {
            outCmp.put("fairness", "one quote snapshot, one seeded path set — every structure judged on identical futures");
        }
        outCmp.put("snapshotAt", book.snapshotAt); // the ENFORCED shared book, identified
        return outCmp;
    }

    /** Uniform structural validation for simulation legs; null = fine, else the refusal reason. */
    private String validateSimLegs(List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> legs) {
        if (legs == null || legs.isEmpty()) return "no legs";
        if (legs.size() > 8) return "more than 8 legs";
        for (var l : legs) {
            if (l == null) return "null leg";
            if (l.ratio() < 1 || l.ratio() > 10) return "leg ratio out of 1..10";
            boolean stock = "STOCK".equalsIgnoreCase(l.type());
            if (!stock) {
                if (l.strike() <= 0) return "non-positive strike";
                if (l.expiryDay() < 0) return "negative expiry day";
                if (!"CALL".equalsIgnoreCase(l.type()) && !"PUT".equalsIgnoreCase(l.type())) return "unknown leg type";
            }
            if (!"BUY".equalsIgnoreCase(l.action()) && !"SELL".equalsIgnoreCase(l.action())) return "unknown leg action";
        }
        return null;
    }

    private long scenarioRoundTripFees(
            List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> legs, int qty) {
        long contracts = (legs == null ? List.<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg>of() : legs)
                .stream().filter(l -> !"STOCK".equalsIgnoreCase(l.type()))
                .mapToLong(l -> Math.max(1, l.ratio())).sum() * Math.max(1, qty);
        long orderFees = legs == null || legs.isEmpty() ? 0 : cfg.feePerOrderCents() * 2L;
        return contracts * cfg.feePerContractCents() * 2L + orderFees;
    }

    private io.liftandshift.strikebench.sim.SimulationEngine.PreviewRun simScenarioRun(Context ctx, ScenarioRequest b) {
        if (b.spec() == null) throw new IllegalArgumentException("spec is required");
        String world = worldParam(activeWorld(ctx));
        int horizon = Math.max(1, b.spec().sane().horizonDays());
        var marketVol = atmVolOf(b.symbol(), world, horizon);
        Double marketIv = marketVol == null ? null : marketVol.atmIv();
        var calibrated = b.spec().volAnnual() > 0 || marketIv == null ? b.spec() : b.spec().withVol(marketIv);
        double rate = market.riskFreeRateQuote(horizon, world).annualRate();
        return simEngine.previewRun(b.symbol(), calibrated, world, analysisCtx(ctx), b.levels(), marketVol, rate);
    }

    /** volAnnual<=0 = "use market vol": the chain's ATM IV, so every symbol gets ITS OWN wildness. */
    private io.liftandshift.strikebench.sim.ScenarioSpec calibrateVol(String symbol,
            io.liftandshift.strikebench.sim.ScenarioSpec spec, String worldId) {
        if (spec.volAnnual() > 0) return spec;
        Double atm = atmIvOf(symbol, worldId, Math.max(1, spec.sane().horizonDays()));
        return atm != null ? spec.withVol(atm) : spec; // sane() falls back to its own default if truly nothing
    }

    private Double atmIvOf(String symbol) { return atmIvOf(symbol, null); }

    private Double atmIvOf(String symbol, String worldId) {
        return atmIvOf(symbol, worldId, 30);
    }

    private Double atmIvOf(String symbol, String worldId, int horizonDays) {
        var input = atmVolOf(symbol, worldId, horizonDays);
        return input == null ? null : input.atmIv();
    }

    private io.liftandshift.strikebench.sim.SimulationEngine.MarketVolInput atmVolOf(
            String symbol, String worldId, int horizonSessions) {
        try {
            var exps = market.expirations(symbol, worldId);
            if (exps.isEmpty()) return null;
            // Scenario horizons are trading sessions. Resolve that date through the same exchange
            // calendar used by DTE and then choose the nearest listed expiry. Using plusDays here
            // silently selected an earlier option week whenever a weekend sat inside the horizon.
            java.time.LocalDate laneToday = market.simInstant(worldId)
                    .map(i -> java.time.LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                    .orElseGet(() -> java.time.LocalDate.now(clock));
            int sessions = Math.max(1, horizonSessions);
            java.time.LocalDate target = io.liftandshift.strikebench.market.MarketHours
                    .tradingDateAfter(laneToday, sessions);
            java.time.LocalDate exp = exps.stream()
                    .min(java.util.Comparator.comparingLong(e -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(e, target))))
                    .orElse(null);
            var chain = exp == null ? null : market.chain(symbol, exp, worldId).orElse(null);
            if (chain == null || chain.isEmpty() || chain.underlyingPrice() == null) return null;
            final java.math.BigDecimal spot = chain.underlyingPrice();
            Double iv = chain.calls().stream()
                    .filter(o -> o.iv() != null && o.iv() > 0.01)
                    .min(java.util.Comparator.comparingDouble(o -> Math.abs(o.strike().subtract(spot).doubleValue())))
                    .map(io.liftandshift.strikebench.model.OptionQuote::iv).orElse(null);
            if (iv == null) return null;
            int calendarDays = Math.max(0, (int) java.time.temporal.ChronoUnit.DAYS.between(laneToday, exp));
            return new io.liftandshift.strikebench.sim.SimulationEngine.MarketVolInput(
                    iv, exp, calendarDays);
        } catch (Exception e) { return null; }
    }

    private void simDataset(Context ctx) {
        ScenarioRequest b = requireBody(bodyOrNull(ctx, ScenarioRequest.class));
        if (b.spec() == null) throw new IllegalArgumentException("spec is required");
        io.liftandshift.strikebench.sim.ScenarioSpec spec = calibrateVol(b.symbol(), b.spec(), worldParam(activeWorld(ctx))); // resolve ONCE
        ctx.json(simEngine.toJson(simEngine.runAndPersist(b.symbol(), spec, ownerId(ctx),
                worldParam(activeWorld(ctx)), analysisCtx(ctx))));
    }

    private Object simStrategyResult(Context ctx, StrategySimRequest b) {
        return simStrategyResult(ctx, b, null);
    }

    private Object simStrategyResult(Context ctx, StrategySimRequest b,
                                     io.liftandshift.strikebench.sim.PathEnsembleService.Ensemble fixedEnsemble) {
        if (b.spec() == null) throw new IllegalArgumentException("spec is required");
        if (b.legs() == null || b.legs().isEmpty()) throw new IllegalArgumentException("legs are required");
        String sym = b.symbol() == null ? "" : b.symbol().trim().toUpperCase(Locale.ROOT);
        if (sym.isEmpty()) throw new IllegalArgumentException("symbol is required");
        String world = worldParam(activeWorld(ctx));
        EntryBook entryBook = new EntryBook(sym, world);
        // Loud refusal on a missing quote — a strategy simulated against an invented $100 stock
        // would be fixture-masquerade all over again (404 via the NoSuchElementException mapper).
        double spot = fixedEnsemble != null ? fixedEnsemble.spot() : entryBook.quote()
                    .map(q -> q.mark()).filter(java.util.Objects::nonNull)
                    .map(java.math.BigDecimal::doubleValue).filter(v -> v > 0)
                    .orElseThrow(() -> new java.util.NoSuchElementException(
                            "No price for " + sym + " — a simulation needs a real (or demo) quote to anchor on. Check the ticker."));
        double r = market.riskFreeRateQuote(Math.max(1, b.spec().sane().horizonDays()), world).annualRate();
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
        if (b.entryCostCents() != null && Math.abs(b.entryCostCents()) > 1_000_000_000L) {
            throw new IllegalArgumentException("entry cost is outside the supported range");
        }
        if (b.contractExpirations() != null) {
            if (b.contractExpirations().size() != b.legs().size()) {
                throw new IllegalArgumentException("contract expirations must align with the legs");
            }
            for (String exp : b.contractExpirations()) {
                if (exp != null && !exp.isBlank()) java.time.LocalDate.parse(exp);
            }
        }
        MarketEntry me = marketEntry(sym, b.legs(), qty, world, entryBook,
                b.contractExpirations());
        if (b.contractExpirations() != null && me == null) {
            throw new IllegalArgumentException(
                    "One of the exact listed contracts is no longer available at an executable price; refresh the position first.");
        }
        // CALIBRATION: volAnnual<=0 is the "use market vol" sentinel — replace with the chain's
        // ATM IV so a caller with no view on wildness gets THIS symbol's, not a canned 25%.
        io.liftandshift.strikebench.sim.ScenarioSpec spec = fixedEnsemble != null ? fixedEnsemble.spec() : b.spec();
        if (fixedEnsemble == null && spec.volAnnual() <= 0 && me != null && me.atmIv() != null) spec = spec.withVol(me.atmIv());
        io.liftandshift.strikebench.sim.IvSpec iv = b.iv();
        boolean marketCalibratedIv = iv == null;
        double ivAnchor = me != null && me.atmIv() != null ? me.atmIv() : spec.sane().volAnnual();
        if (iv == null) {
            iv = spec.sane().shape() == io.liftandshift.strikebench.sim.ScenarioSpec.Shape.EVENT_JUMP
                    ? io.liftandshift.strikebench.sim.IvSpec.eventCrushAround(ivAnchor,
                        Math.max(1, Math.round(spec.sane().horizonDays() / 3.0f)))
                    : io.liftandshift.strikebench.sim.IvSpec.flat(ivAnchor);
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
        Long entryCost = b.entryCostCents() != null ? b.entryCostCents()
                : (me == null ? null : me.entryCents());
        if (b.entryCostCents() != null) {
            entryNote = "Entry fixed to the exact package price already shown on this screen; "
                    + "path exits are modeled from the listed contracts.";
        }
        String ivBasis = marketCalibratedIv
                ? (me != null && me.atmIv() != null
                    ? "IV path anchored to the active lane's nearest-horizon ATM option volatility"
                    : "IV path anchored to the scenario volatility because no eligible ATM option volatility was available")
                : "IV path set explicitly by the scenario controls";
        entryNote = (entryNote == null || entryNote.isBlank() ? "" : entryNote + " ") + ivBasis + ".";
        var pathBasis = b.pathBasis() == null
                ? io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC : b.pathBasis();
        io.liftandshift.strikebench.sim.ScenarioSimulator.EnsembleRun evaluated;
        var simulator = new io.liftandshift.strikebench.sim.ScenarioSimulator();
        if (fixedEnsemble != null) {
            if (fixedEnsemble.basis() != pathBasis) {
                throw new IllegalArgumentException("the supplied position must use the stored ensemble's path basis");
            }
            var result = simulator.runOnPaths(fixedEnsemble.paths(), fixedEnsemble.spot(), legsToRun, qty,
                    fixedEnsemble.spec(), iv, r, entryCost, entryNote,
                    scenarioRoundTripFees(legsToRun, qty));
            evaluated = new io.liftandshift.strikebench.sim.ScenarioSimulator.EnsembleRun(fixedEnsemble, result);
        } else {
            evaluated = simulator.run(pathEnsembles,
                    new io.liftandshift.strikebench.sim.PathEnsembleService.Scope(sym, world, analysisCtx(ctx)),
                    pathBasis, spec, b.study(), spot, legsToRun, qty, iv, r, entryCost, entryNote,
                    scenarioRoundTripFees(legsToRun, qty));
        }
        var studyRes = evaluated.ensemble().study();
        String pathModelVersion = evaluated.ensemble().modelVersion();
        if (studyRes != null) {
            var eresult = evaluated.result();
            // The interpretation is DIFFERENT and must say so: conditional history, not a model.
            var out = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(eresult);
            out.put("pathSource", pathBasis.name());
            out.put("pathModelVersion", pathModelVersion);
            out.put("ivStart", iv.sane().startIv());
            out.put("ivLongRun", iv.sane().longRunIv());
            out.put("ivBasis", ivBasis);
            out.put("studyKey", studyRes.studyKey());
            out.put("analogEvents", studyRes.eventDates() == null ? 0 : studyRes.eventDates().size());
            out.put("evidence", studyRes.evidence());
            out.put("observed", studyRes.observed());
            // HONEST BASIS (holistic review P0): "REAL past occurrences" is only true when the
            // candles behind the study are observed market history. Demo fixtures and synthetic
            // scenario datasets are labeled as exactly what they are — never as real history.
            String occurrences = studyRes.observed() ? "REAL past occurrences"
                    : "DEMO_FIXTURE".equals(studyRes.evidence())
                        ? "DEMO-data occurrences (built-in demo history, NOT real market history)"
                        : "GENERATED-scenario occurrences (synthetic dataset, NOT real market history)";
            out.put("sourceNote", pathBasis == io.liftandshift.strikebench.sim.PathEnsembleService.Basis.HISTORICAL_ANALOGS
                    ? "Priced over " + evaluated.ensemble().paths().length + " " + occurrences + " of this condition ("
                        + studyRes.from() + " to " + studyRes.to() + ") — conditional history, not a model's odds, and not a forecast."
                    : "Priced over " + evaluated.ensemble().paths().length + " whole-path resamples of " + studyRes.eventDates().size()
                        + " " + occurrences + " (conditional bootstrap) — empirical shape preserved; sampling uncertainty, not a model.");
            return out;
        }
        var out = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(evaluated.result());
        out.put("pathModelVersion", pathModelVersion);
        out.put("ivStart", iv.sane().startIv());
        out.put("ivLongRun", iv.sane().longRunIv());
        out.put("ivBasis", ivBasis);
        return out;
    }

    private record MarketEntry(long entryCents, Double atmIv, Double averageIv,
                               String source, String freshness,
                               List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> resolvedLegs,
                               List<Leg> pricedLegs, List<String> snaps) {}

    /**
     * Prices the position's entry from the live chain at EXECUTABLE sides. Returns null when any
     * option leg can't be matched to a real quote (unknown expiration/strike, one-sided book) —
     * the simulator then falls back to a model-priced entry, honestly labeled.
     */
    private static String trimNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /**
     * F5: ONE immutable quote/chain snapshot for a whole comparison. Without it every structure
     * refetched the book independently — in a RUNNING simulated (or live) market prices advance
     * between structures and "identical futures, identical entry book" was prose, not a property.
     * First access per expiration fills the book; every later structure reuses the same object.
     */
    final class EntryBook {
        final String symbol; final String worldId;
        final String snapshotAt;
        private final java.util.Map<java.time.LocalDate, java.util.Optional<io.liftandshift.strikebench.model.OptionChain>> chains
                = new java.util.HashMap<>();
        private java.util.Optional<io.liftandshift.strikebench.model.Quote> quote;
        private List<java.time.LocalDate> exps;
        EntryBook(String symbol, String worldId) {
            this.symbol = symbol; this.worldId = worldId;
            this.snapshotAt = market.simInstant(worldId).orElse(clock.instant()).toString();
        }
        synchronized List<java.time.LocalDate> expirations() {
            if (exps == null) exps = market.expirations(symbol, worldId);
            return exps;
        }
        synchronized java.util.Optional<io.liftandshift.strikebench.model.Quote> quote() {
            if (quote == null) quote = market.quote(symbol, worldId);
            return quote;
        }
        synchronized java.util.Optional<io.liftandshift.strikebench.model.OptionChain> chain(java.time.LocalDate exp) {
            return chains.computeIfAbsent(exp, e -> market.chain(symbol, e, worldId));
        }
    }

    private MarketEntry marketEntry(String symbol, List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> legs,
                                    int qty, String worldId, EntryBook book, List<String> contractExpirations) {
        try {
            List<java.time.LocalDate> exps = book != null ? book.expirations() : market.expirations(symbol, worldId);
            if (exps.isEmpty()) return null;
            java.time.LocalDate today = market.simInstant(worldId)
                    .map(i -> java.time.LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                    .orElseGet(() -> java.time.LocalDate.now(clock));
            double entryPerUnit = 0;
            Double atmIv = null;
            String source = null;
            String freshness = null;
            java.math.BigDecimal spotBd = null;
            List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> resolved = new ArrayList<>();
            List<Leg> priced = new ArrayList<>();
            List<Double> marketIvs = new ArrayList<>();
            List<String> snaps = new ArrayList<>();
            for (int legIndex = 0; legIndex < legs.size(); legIndex++) {
                var leg = legs.get(legIndex);
                if ("STOCK".equalsIgnoreCase(leg.type())) {
                    var q = (book != null ? book.quote() : market.quote(symbol, worldId)).orElse(null);
                    if (q == null || q.mark() == null) return null;
                    double sign = "SELL".equalsIgnoreCase(leg.action()) ? -1 : 1;
                    entryPerUnit += sign * Math.max(1, leg.ratio()) * 100 * q.mark().doubleValue();
                    resolved.add(leg);
                    priced.add(Leg.stock(io.liftandshift.strikebench.model.LegAction.valueOf(
                            leg.action().trim().toUpperCase(Locale.ROOT)), Math.max(1, leg.ratio()), q.mark()));
                    continue;
                }
                String exactRaw = contractExpirations != null ? contractExpirations.get(legIndex) : null;
                boolean exactContract = exactRaw != null && !exactRaw.isBlank();
                java.time.LocalDate exp;
                if (exactContract) {
                    exp = java.time.LocalDate.parse(exactRaw);
                    if (!exps.contains(exp)) return null;
                } else {
                    // Generic scenario: nearest listed expiration to the requested trading-session horizon.
                    java.time.LocalDate target = io.liftandshift.strikebench.market.MarketHours
                            .tradingDateAfter(today, leg.expiryDay());
                    exp = exps.stream()
                            .min(java.util.Comparator.comparingLong(e2 -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(e2, target))))
                            .orElse(null);
                }
                if (exp == null) return null;
                var chain = (book != null ? book.chain(exp) : market.chain(symbol, exp, worldId)).orElse(null);
                if (chain == null || chain.isEmpty()) return null;
                if (spotBd == null) spotBd = chain.underlyingPrice();
                boolean call = "CALL".equalsIgnoreCase(leg.type());
                var side = call ? chain.calls() : chain.puts();
                var quote = side.stream()
                        .min(java.util.Comparator.comparingDouble(o -> Math.abs(o.strike().doubleValue() - leg.strike())))
                        .orElse(null);
                // The nearest listed strike must be reasonably close, or this isn't the same trade.
                double strikeGap = quote == null ? Double.POSITIVE_INFINITY
                        : Math.abs(quote.strike().doubleValue() - leg.strike());
                if (quote == null || (exactContract ? strikeGap > 1e-9
                        : strikeGap > Math.max(2.5, leg.strike() * 0.03))) return null;
                boolean buy = !"SELL".equalsIgnoreCase(leg.action());
                java.math.BigDecimal px = buy ? quote.ask() : quote.bid(); // executable sides
                if (px == null || px.signum() <= 0) return null;
                if (source == null) source = chain.source();
                if (freshness == null && chain.freshness() != null) freshness = chain.freshness().name();
                if (quote.iv() != null && quote.iv() > 0.01) marketIvs.add(quote.iv());
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
                int listedDays = Math.max(1, io.liftandshift.strikebench.market.MarketHours
                        .tradingDaysBetween(today, exp));
                resolved.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg(
                        leg.action(), leg.type(), listedStrike, listedDays, leg.ratio()));
                priced.add(Leg.option(io.liftandshift.strikebench.model.LegAction.valueOf(
                                leg.action().trim().toUpperCase(Locale.ROOT)),
                        io.liftandshift.strikebench.model.OptionType.valueOf(
                                leg.type().trim().toUpperCase(Locale.ROOT)),
                        quote.strike(), exp, Math.max(1, leg.ratio()), px));
                if (Math.abs(listedStrike - leg.strike()) > 1e-9 || Math.abs(listedDays - leg.expiryDay()) > 1) {
                    snaps.add(leg.type() + " " + trimNum(leg.strike()) + "\u2192" + trimNum(listedStrike) + " exp " + exp);
                }
            }
            Double averageIv = marketIvs.isEmpty() ? null
                    : marketIvs.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            return new MarketEntry(Math.round(entryPerUnit * qty * 100), atmIv, averageIv,
                    source == null ? "live" : source, freshness, resolved, priced, snaps);
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
        if (tier == io.liftandshift.strikebench.db.DataResetService.Tier.PAPER
                || tier == io.liftandshift.strikebench.db.DataResetService.Tier.EVERYTHING) {
            // The rows and simulation accounts are gone; cancel their resident loops and make
            // every connected tab reconcile to the surviving baseline market immediately.
            simSessions.clearResident();
            String baseline = cfg.fixturesOnly() ? "demo" : "observed";
            long revision = worldRevision.incrementAndGet();
            events.publish("world.selected", Map.of(
                    "world", baseline, "revision", revision, "epoch", startedAt));
        }
        datasets.invalidateActiveCache(); // the settings row is gone; in-memory state must follow
        market.invalidateAll();
        invalidateHistoricalViews();
        try { audit.log(null, null, "DATA_RESET", "WARN", Map.of("tier", result.tier(), "areas", result.areasCleared())); }
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
        String world = activeWorld(ctx);
        Optional<Quote> quote = market.quote(symbol, world);
        if (quote.isEmpty()) {
            ctx.attribute("apiErrorWritten", true);
            ctx.status(404).json(Map.of("error", "unknown_symbol", "detail", "No data for " + symbol));
            return;
        }
        Quote q = quote.get();
        var lane = io.liftandshift.strikebench.market.MarketLane.of(world, cfg.fixturesOnly(), analysisCtx(ctx));
        if (!q.evidence().usableIn(lane == io.liftandshift.strikebench.market.MarketLane.SCENARIO
                ? io.liftandshift.strikebench.market.MarketLane.OBSERVED : lane)) {
            ctx.attribute("apiErrorWritten", true);
            ctx.status(409).json(Map.of("error", "market_lane_mismatch",
                    "detail", "The " + lane + " workflow cannot use " + q.evidence().provenance()
                            + " quote data from " + q.evidence().source()));
            return;
        }
        // The chart window ends at the LANE's today: a fast-forwarded world's own rolled bars
        // must render and feed HV — windowing on the real date silently hid the whole session.
        LocalDate today = market.simInstant(worldParam(world))
                .map(i -> LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                .orElseGet(() -> LocalDate.now(clock));

        // The three remaining pieces are independent provider calls — the old serial waterfall
        // (expirations→chain→candles→SPY→QQQ) made the endpoint as slow as the SUM of them, which in
        // live mode meant several multi-MB Cboe downloads back-to-back. Run them CONCURRENTLY on
        // virtual threads so the endpoint costs the slowest ONE, not the sum.
        record IvExp(List<LocalDate> exps, Double ivAtm,
                     io.liftandshift.strikebench.model.DataEvidence evidence) {}
        try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var ivExpF = exec.submit(() -> {
                List<LocalDate> e = activeExpirations(symbol, world);
                OptionChain ch = e.isEmpty() ? null : market.chain(symbol, e.getFirst(), world).orElse(null);
                boolean allowed = ch != null && ch.evidence().usableIn(
                        lane == io.liftandshift.strikebench.market.MarketLane.SCENARIO
                                ? io.liftandshift.strikebench.market.MarketLane.OBSERVED : lane);
                Double iv = allowed ? atmIv(ch).orElse(null) : null;
                return new IvExp(e, iv, allowed ? ch.evidence()
                        : io.liftandshift.strikebench.model.DataEvidence.missing("option chain"));
            });
            var actx = analysisCtx(ctx); // capture BEFORE the fan-out: explicit context survives vthreads
            var candlesF = exec.submit(() -> market.candleSeries(symbol, today.minusDays(120), today, world, actx));
            var benchF = exec.submit(() -> {
                List<Map<String, Object>> b = new ArrayList<>();
                for (String bench : List.of("SPY", "QQQ")) {
                    if (bench.equals(symbol)) continue;
                    // Benchmarks come from the SAME world as the symbol — inside a simulated
                    // session there is no observed SPY, and mixing markets would be a quiet lie.
                    market.quote(bench, world).filter(x -> x.evidence().usableIn(
                            lane == io.liftandshift.strikebench.market.MarketLane.SCENARIO
                                    ? io.liftandshift.strikebench.market.MarketLane.OBSERVED : lane)).ifPresent(x -> {
                        Map<String, Object> bm = new LinkedHashMap<>();
                        bm.put("symbol", x.symbol());
                        bm.put("last", x.mark());
                        bm.put("freshness", x.markFreshness().name());
                        bm.put("evidence", x.evidence());
                        b.add(bm);
                    });
                }
                return b;
            });

            IvExp ie = ivExpF.get();
            var candleSeries = candlesF.get();
            double hv30 = HistoricalVol.annualized(candleSeries.candles(), 30);
            boolean demoHistory = candleSeries.evidence().provenance()
                    == io.liftandshift.strikebench.model.DataProvenance.DEMO;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", symbol);
            out.put("quote", q);
            out.put("displayPrice", q.mark());
            out.put("priceIsPreviousClose", q.usesPreviousCloseFallback());
            out.put("marketLane", lane.name());
            out.put("optionable", q.optionable());
            out.put("ivAtm", ie.ivAtm());
            // The event model: an ESTIMATED earnings window from the issuer's SEC filing cadence
            // (never keywords), and an HONESTLY-ABSENT ex-div (no keyless source exists).
            // A simulated world has NO earnings — a real company's calendar attached to a
            // generated market would be a phantom event (review P2).
            if ("demo".equals(world)) {
                out.put("earningsEstimate", Map.of("available", false,
                        "note", "demo market — its companies and events are fabricated teaching data"));
            } else if (!"observed".equals(world)) {
                out.put("earningsEstimate", Map.of("available", false,
                        "note", "simulated market — no earnings exist in this world"));
            } else {
                eventCalendar.nextEarnings(symbol).ifPresentOrElse(
                        e -> out.put("earningsEstimate", Map.of(
                                "date", e.estimated().toString(), "windowDays", e.windowDays(),
                                "basis", e.basis(), "confirmed", e.confirmed())),
                        () -> out.put("earningsEstimate", Map.of("available", false,
                                "note", "not enough SEC quarterly filings to project a cadence")));
            }
            out.put("exDividend", Map.of("available", false,
                    "note", "no keyless ex-dividend source \u2014 connect a licensed calendar for confirmed dates"));
            out.put("hv30", Double.isNaN(hv30) ? null : hv30);
            out.put("historyDemo", demoHistory);
            out.put("historyBarBasis", candleSeries.barBasis());
            out.put("historyPriceBasis", candleSeries.priceBasis());
            Map<String, io.liftandshift.strikebench.model.DataEvidence> evidenceInputs = new LinkedHashMap<>();
            evidenceInputs.put("quote", q.evidence());
            evidenceInputs.put("history", candleSeries.isEmpty()
                    ? io.liftandshift.strikebench.model.DataEvidence.missing("daily history")
                    : candleSeries.evidence());
            if (q.optionable()) evidenceInputs.put("options", ie.evidence());
            out.put("evidence", Map.of(
                    "summary", io.liftandshift.strikebench.model.DataEvidence.aggregate(evidenceInputs.values()),
                    "inputs", evidenceInputs));
            out.put("expirations", ie.exps().stream().map(LocalDate::toString).toList());
            var planLane = io.liftandshift.strikebench.market.MarketLane.of(world, cfg.fixturesOnly());
            var planEligibility = planSymbolEligibility(symbol, planLane, q, ie.exps(), ie.evidence());
            out.put("planEligible", planEligibility.eligible());
            out.put("planEligibility", planEligibility.detail());
            out.put("benchmarks", benchF.get());
            out.put("freshness", q.markFreshness().name());
            out.put("asOfDate", today.toString()); // the LANE's today — client DTE math uses this, never Date.now()
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
        String world = activeWorld(ctx);
        java.time.Instant now = market.simInstant(worldParam(world)).orElse(clock.instant());
        ctx.json(Map.of("symbol", symbol,
                "asOfDate", LocalDate.ofInstant(now, io.liftandshift.strikebench.market.MarketHours.EASTERN).toString(),
                "expirations", activeExpirations(market.expirations(symbol, world), now).stream()
                        .map(LocalDate::toString).toList()));
    }

    private List<LocalDate> activeExpirations(String symbol, String world) {
        java.time.Instant now = market.simInstant(worldParam(world)).orElse(clock.instant());
        return activeExpirations(market.expirations(symbol, world), now);
    }

    static List<LocalDate> activeExpirations(List<LocalDate> expirations, java.time.Instant now) {
        if (expirations == null || expirations.isEmpty()) return List.of();
        return expirations.stream()
                .filter(exp -> !io.liftandshift.strikebench.market.MarketHours.contractDead(exp, now))
                .sorted().toList();
    }

    private void chain(Context ctx) {
        String symbol = ctx.pathParam("symbol").trim().toUpperCase(Locale.ROOT);
        String expStr = ctx.queryParam("expiration");
        if (expStr == null || expStr.isBlank()) {
            throw new IllegalArgumentException("expiration query parameter is required (YYYY-MM-DD)");
        }
        LocalDate exp = LocalDate.parse(expStr.trim());
        String world = activeWorld(ctx);
        java.time.Instant now = market.simInstant(worldParam(world)).orElse(clock.instant());
        if (io.liftandshift.strikebench.market.MarketHours.contractDead(exp, now)) {
            throw new IllegalArgumentException("expiration is no longer active: " + exp);
        }
        Optional<OptionChain> chain = market.chain(symbol, exp, world);
        if (chain.isEmpty()) {
            ctx.attribute("apiErrorWritten", true);
            ctx.status(404).json(Map.of("error", "no_chain", "detail", "No option chain for " + symbol + " " + exp));
            return;
        }
        ctx.json(chain.get());
    }

    /** ONE stable universe schema for every market mode (P0-1); world = null means observed. */
    private Object universeViewFor(String worldOrNull, String owner) {
        if (worldOrNull != null) {
            var syms = market.worldSymbols(worldOrNull).map(x -> List.copyOf(x)).orElse(List.<String>of());
            boolean demo = "demo".equals(worldOrNull);
            String name = demo ? "Built-in demo market" : simSessions.getOrRestore(worldOrNull, owner)
                    .map(x -> x.config().name() == null ? "Simulated session" : x.config().name())
                    .orElse("Simulated session");
            String qualifier = demo ? "fabricated demo data" : "simulated";
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("active", Map.of("source", "world", "sectorKey", "world",
                    "label", name + " (" + qualifier + ")", "symbols", syms));
            out.put("sectors", List.of(Map.of("key", "world", "label", name + " (" + qualifier + ")",
                    "symbols", syms)));
            out.put("world", worldOrNull);
            out.put("lane", demo ? "DEMO" : "SIMULATED");
            return out;
        }
        Map<String, Object> observed = new LinkedHashMap<>(universe.describe());
        observed.put("world", "observed");
        observed.put("lane", cfg.fixturesOnly() ? "DEMO" : "OBSERVED");
        return observed;
    }

    /**
     * Batch sparkline closes for the Research/Home cards — ONE http call for the whole grid,
     * served from the shared candles cache (store-first, then lane-eligible providers) or the
     * simulated world's memory. Never a per-card provider fan-out from the client. A symbol with
     * no candle source in live keyless mode answers available:false with an honest note (and a
     * 15-minute negative memo so a dead symbol cannot re-trigger the provider chain per render).
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> sparklineEmptyMemo =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(java.time.Duration.ofMinutes(15)).maximumSize(500).build();
    private final java.util.concurrent.atomic.AtomicLong historicalDataVersion =
            new java.util.concurrent.atomic.AtomicLong();

    private void invalidateHistoricalViews() {
        historicalDataVersion.incrementAndGet();
        sparklineEmptyMemo.invalidateAll();
        market.invalidateHistoricalData();
        evaluations.invalidateHistoricalData();
    }

    private void sparklines(Context ctx) {
        String raw = ctx.queryParam("symbols");
        String requested = ctx.queryParam("range") == null ? "3m" : ctx.queryParam("range").toLowerCase(Locale.ROOT);
        String range = switch (requested) {
            case "1m", "3m", "6m", "ytd", "1y" -> requested;
            default -> "3m";
        };
        String world = worldParam(activeWorld(ctx));
        // EXACTLY history's window computation so the candlesCache key (dataset|sym|from|to) is
        // shared between /history and /sparklines — one warms the other for free.
        LocalDate today = market.simInstant(world)
                .map(i -> LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                .orElseGet(() -> LocalDate.now(clock));
        int days = switch (range) {
            case "1m" -> 30;
            case "6m" -> 182;
            case "ytd" -> today.getDayOfYear();
            case "1y" -> 365;
            default -> 91;
        };
        LocalDate from = today.minusDays(days);
        List<String> symbols = raw == null || raw.isBlank()
                ? (world != null
                    ? market.worldSymbols(world).map(List::copyOf).orElse(List.of())
                    : universe.active().symbols())
                : java.util.Arrays.stream(raw.split(",")).map(x -> x.trim().toUpperCase(Locale.ROOT))
                    .filter(x -> !x.isBlank()).distinct().toList();
        int totalRequested = symbols.size();
        if (totalRequested > 16) symbols = symbols.subList(0, 16);
        var actx = analysisCtx(ctx);
        String lane = world != null ? world : "observed";
        long dataVersion = historicalDataVersion.get();
        var rows = new java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>>();
        var gate = new java.util.concurrent.Semaphore(2); // optional screen decoration never fans out aggressively
        List<Thread> workers = new ArrayList<>();
        for (String sym : symbols) {
            workers.add(Thread.startVirtualThread(() -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("symbol", sym);
                String memoKey = dataVersion + "|" + lane + "|" + actx + "|" + sym + "|" + range;
                // Simulated worlds advance under their own clock and are memory-cheap: never pin
                // a temporary empty result while the world is creating its next candle.
                if (world == null && sparklineEmptyMemo.getIfPresent(memoKey) != null) {
                    row.put("available", false);
                    row.put("closes", List.of());
                    row.put("note", "No daily-candle source for this symbol right now \u2014 quotes still work.");
                    row.put("evidence", io.liftandshift.strikebench.model.DataEvidence.missing("daily history unavailable"));
                    rows.put(sym, row);
                    return;
                }
                try {
                    gate.acquire();
                    try {
                        var series = market.candleSeries(sym, from, today, world, actx);
                        var candles = series == null ? List.<io.liftandshift.strikebench.model.Candle>of() : series.candles();
                        if (candles.size() < 2) {
                            if (world == null) sparklineEmptyMemo.put(memoKey, Boolean.TRUE);
                            row.put("available", false);
                            row.put("closes", List.of());
                            row.put("note", "No daily-candle source for this symbol right now \u2014 quotes still work.");
                            row.put("evidence", series == null
                                    ? io.liftandshift.strikebench.model.DataEvidence.missing("daily history unavailable")
                                    : series.evidence());
                        } else {
                            // Trim to <=64 points: a sparkline needs shape, not every bar.
                            int stride = Math.max(1, (int) Math.ceil(candles.size() / 64.0));
                            List<String> dates = new ArrayList<>();
                            List<java.math.BigDecimal> closes = new ArrayList<>();
                            for (int i = 0; i < candles.size(); i += stride) {
                                var cnd = candles.get(i);
                                dates.add(cnd.date().toString());
                                closes.add(cnd.close());
                            }
                            var last = candles.getLast();
                            if (!dates.getLast().equals(last.date().toString())) { // always end on the latest bar
                                dates.add(last.date().toString());
                                closes.add(last.close());
                            }
                            row.put("available", true);
                            row.put("dates", dates);
                            row.put("closes", closes);
                            row.put("source", series.source());
                            row.put("freshness", series.freshness().name());
                            row.put("evidence", series.evidence());
                        }
                    } finally {
                        gate.release();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    row.put("available", false);
                    row.put("closes", List.of());
                    row.put("note", "History lookup was interrupted \u2014 try again.");
                    row.put("evidence", io.liftandshift.strikebench.model.DataEvidence.missing("history interrupted"));
                } catch (RuntimeException e) {
                    row.put("available", false);
                    row.put("closes", List.of());
                    row.put("note", "History lookup failed \u2014 try again or check Data source health.");
                    row.put("evidence", io.liftandshift.strikebench.model.DataEvidence.missing("history lookup failed"));
                }
                rows.put(sym, row);
            }));
        }
        for (Thread t : workers) { try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String sym : symbols) { var r = rows.get(sym); if (r != null) out.add(r); }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("range", range);
        body.put("sparklines", out);
        body.put("totalRequested", totalRequested);
        if (world != null) body.put("world", world);
        ctx.json(body);
    }

    private void history(Context ctx) {
        String symbol = ctx.pathParam("symbol").trim().toUpperCase(Locale.ROOT);
        String requested = ctx.queryParam("range") == null ? "1y" : ctx.queryParam("range").toLowerCase(Locale.ROOT);
        String range = switch (requested) {
            case "1m", "3m", "6m", "ytd", "1y", "2y", "5y", "max" -> requested;
            default -> "1y";
        };
        // The lane's today: inside a world the chart must include the session's own rolled bars.
        LocalDate today = market.simInstant(worldParam(activeWorld(ctx)))
                .map(i -> LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                .orElseGet(() -> LocalDate.now(clock));
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
        var series = market.candleSeries(symbol, today.minusDays(days), today, activeWorld(ctx), analysisCtx(ctx));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("range", range);
        out.put("candles", series.candles());
        out.put("source", series.source());
        out.put("freshness", series.freshness().name());
        out.put("barBasis", series.barBasis());
        out.put("priceBasis", series.priceBasis());
        out.put("evidence", series.evidence());
        ctx.json(out);
    }

    private void news(Context ctx) {
        String symbol = ctx.pathParam("symbol").trim().toUpperCase(Locale.ROOT);
        String world = activeWorld(ctx);
        if (!"observed".equals(world) && !"demo".equals(world)) {
            ctx.json(Map.of("symbol", symbol, "items", List.of(),
                    "note", "simulated market \u2014 there is no news in this world; headlines belong to the real market"));
            return;
        }
        ctx.json(Map.of("symbol", symbol, "items", market.news(symbol, world)));
    }

    private void lookup(Context ctx) {
        String q = ctx.queryParam("q");
        ctx.json(Map.of("matches", q == null ? List.of() : market.lookup(q, activeWorld(ctx))));
    }

    // ---- Product-owned strategy discovery ----

    private void welcomeTeachingExample(Context ctx) {
        var request = new RecommendationEngine.Request("AAPL", "bullish", "month", "conservative",
                null, null, null, null, true, false, "DIRECTIONAL", null, null);
        RecommendationEngine.Result result = resolveAndRecommend(ctx, request);
        ctx.json(decisionRanked(result, currentAccount(ctx), activeWorld(ctx)));
    }

    /**
     * ONE ranking everywhere: candidates leave this API ordered by the DECISION score (the full
     * StrategyEvaluation composite — gates, capital, tail risk, evidence haircut), the same score
     * the Decision page and the opportunity scan use. The engine's quick screen score stays on each
     * candidate as a disclosed component. Evaluation trouble falls back to screen order, labeled.
     */
    private Object decisionRanked(RecommendationEngine.Result result, Account acct, String world) {
        if (result.candidates() == null || result.candidates().isEmpty()) return result;
        try {
            // The decision score is computed from the SAME market that priced the candidates —
            // inside a simulated session that is the world's spot/IV/vol, never observed (review P0).
            var evals = evaluations.evaluate(result.symbol(), result.intent(), result.thesis(), result.horizon(),
                    result.riskMode(), result.candidates(), acct.buyingPowerCents(), null, false,
                    io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, worldParam(world));
            if (evals.size() != result.candidates().size()) return result; // partial evaluation: keep screen order
            com.fasterxml.jackson.databind.node.ObjectNode out =
                    (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(result);
            com.fasterxml.jackson.databind.node.ArrayNode cands = out.putArray("candidates");
            int favorable = 0, actionableFavorable = 0, mixed = 0, unfavorable = 0, unavailable = 0;
            for (var e : evals) { // evaluateAndRank order is exactly the monotonic Decision score
                com.fasterxml.jackson.databind.node.ObjectNode m =
                        (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(e.candidate());
                m.put("decisionScore", e.decisionScore());
                m.put("decisionViable", e.viable());
                m.put("structurallyEligible", e.viable());
                if (e.economics() != null) {
                    m.set("economics", Json.MAPPER.valueToTree(e.economics()));
                    m.put("economicVerdict", e.economics().verdict().name());
                    m.put("economicPlacement", e.economics().placement());
                    switch (e.economics().verdict()) {
                        case FAVORABLE -> {
                            favorable++;
                            if (e.economics().actionableFavorable()) actionableFavorable++;
                        }
                        case MIXED -> mixed++;
                        case UNFAVORABLE -> unfavorable++;
                        case UNAVAILABLE -> unavailable++;
                    }
                }
                if (e.evCents() != null && e.evCents() < 0) {
                    m.put("negativeEv", true); // never an unlabeled recommendation
                }
                attachEvaluationReceipt(m, e);
                cands.add(m);
            }
            out.put("ranking", "decision"); // disclosed: what ordered this list
            out.put("economicPolicy", "decision_score");
            out.put("favorableCount", favorable);
            out.put("actionableFavorableCount", actionableFavorable);
            out.put("mixedCount", mixed);
            out.put("unfavorableCount", unfavorable);
            out.put("unavailableCount", unavailable);
            out.put("economicMessage", actionableFavorable > 0
                    ? actionableFavorable + " setup" + (actionableFavorable == 1 ? "" : "s")
                            + " worth investigating on end-to-end observed evidence; compare costs and alternatives before acting."
                    : favorable > 0
                        ? favorable + " setup" + (favorable == 1 ? "" : "s")
                            + " favorable inside an explicit generated teaching market. That is useful practice, not evidence of a live-market edge."
                    : "No setup currently shows a robust after-cost edge. Mixed and unfavorable structures remain available for comparison and learning.");
            return out;
        } catch (RuntimeException e) {
            log.warn("Decision ranking is temporarily unavailable; showing the screened order");
            log.debug("Decision-ranking failure detail", e);
            return result;
        }
    }

    private static void attachEvaluationReceipt(ObjectNode candidate,
                                                 io.liftandshift.strikebench.eval.StrategyEvaluation evaluation) {
        ObjectNode receipt = candidate.putObject("evaluation");
        if (evaluation.capital() != null) receipt.set("capital", Json.MAPPER.valueToTree(evaluation.capital()));
        if (evaluation.risk() != null) receipt.set("risk", Json.MAPPER.valueToTree(evaluation.risk()));
        if (evaluation.evidence() != null) receipt.set("evidence", Json.MAPPER.valueToTree(evaluation.evidence()));
        if (evaluation.management() != null) receipt.set("management", Json.MAPPER.valueToTree(evaluation.management()));
        if (evaluation.score() != null) receipt.set("score", Json.MAPPER.valueToTree(evaluation.score()));
        if (evaluation.explanation() != null) receipt.set("explanation", Json.MAPPER.valueToTree(evaluation.explanation()));
    }

    /** Parses the recommend request (injecting real holdings for hold-based intents) and runs the engine. */
    private RecommendationEngine.Result resolveAndRecommend(Context ctx) {
        return resolveAndRecommend(ctx, bodyOrNull(ctx, RecommendationEngine.Request.class));
    }

    private RecommendationEngine.Result resolveAndRecommend(Context ctx, RecommendationEngine.Request req) {
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
        // R4 via THE policy (review IC-1): the declared risk-capital line caps the engine's
        // per-trade budget — one translation shared by every recommending surface.
        Long capGov = RiskBudgetPolicy.effectiveMaxLossCents(req.maxLossCents(), riskCapCents(ctx));
        if (!java.util.Objects.equals(capGov, req.maxLossCents())) {
            req = new RecommendationEngine.Request(req.symbol(), req.thesis(), req.horizon(), req.riskMode(),
                    capGov, req.maxRiskPctOfAccount(), req.minConfidence(), req.allowedStrategies(),
                    req.avoidEarnings(), req.allow0dte(), req.intent(), req.holdings(), req.filters());
        }
        return engine.recommend(req, acct.buyingPowerCents(), activeWorld(ctx));
    }

    /**
     * Recommendations-as-a-competition: runs the engine, then wraps each candidate in a full
     * StrategyEvaluation (capital / volatility / risk / evidence / management / score / explanation),
     * ranks them, and persists the competition for later review. Feeds the Phase-3 decision UI.
     */
    private void evaluate(Context ctx) {
        ctx.json(evaluateOutcomes(ctx, requireBody(bodyOrNull(ctx,
                io.liftandshift.strikebench.outcomes.OutcomeContract.Request.class))));
    }

    private Object decisionEvaluationResult(Context ctx, RecommendationEngine.Request decision) {
        RecommendationEngine.Result result = resolveAndRecommend(ctx, decision);
        Account acct = currentAccount(ctx);
        String world = activeWorld(ctx);
        boolean inWorld = !"observed".equals(world);
        String uid = auth.currentUserId(ctx);
        String ownerId = io.liftandshift.strikebench.auth.AuthService.LOCAL_USER.equals(uid) ? null : uid;
        var evals = evaluations.evaluate(result.symbol(), result.intent(), result.thesis(), result.horizon(),
                result.riskMode(), result.candidates(), acct.buyingPowerCents(), ownerId, !inWorld,
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, worldParam(world));
        // Record the surfaced pick as a calibration sample — REAL LANE ONLY: a simulated market's
        // outcomes are self-graded homework and must never touch 'Your record' (review P0).
        String recommendationId = null;
        if (!evals.isEmpty() && !inWorld) {
            try { recommendationId = evaluations.recordSurfaced(evals.getFirst().id(), ownerId); }
            catch (RuntimeException e) {
                log.warn("A recommendation could not be added to the learning record");
                log.debug("Recommendation-record detail", e);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", result.symbol());
        out.put("intent", String.valueOf(result.intent()));
        out.put("evaluations", evals);
        out.put("rejected", result.rejected());
        // R10: the alternatives are EVALUATED, not described — real capital, real tail numbers,
        // the same horizon, so 'nothing here beats cash/shares' is a first-class ranked outcome.
        List<Map<String, Object>> baselines = new ArrayList<>();
        Map<String, Object> cash = new LinkedHashMap<>();
        cash.put("key", "CASH");
        cash.put("evCents", 0L); cash.put("maxLossCents", 0L); cash.put("cvar95Cents", 0L);
        cash.put("capitalCents", 0L); cash.put("viable", true);
        cash.put("note", "Do nothing: $0 expected, $0 at risk, zero costs — every idea above must beat this after fees and spreads.");
        baselines.add(cash);
        try {
            var q = market.quote(result.symbol(), worldParam(world)).orElse(null);
            if (q != null && q.mark() != null) {
                double spot = q.mark().doubleValue();
                long lot = Math.round(spot * 100) * 100; // 100 shares, cents
                // The baseline is judged over the COMPETITION'S OWN horizon (front-expiration DTE),
                // not a fixed month — a 7-DTE contest vs a 30-day tail was apples-to-oranges (P3).
                int horizonDays = 30;
                java.time.LocalDate frontExp = result.candidates().stream()
                        .flatMap(c -> c.legs().stream())
                        .map(l -> { try { return java.time.LocalDate.parse(l.expiration()); }
                                    catch (RuntimeException e) { return null; } })
                        .filter(java.util.Objects::nonNull)
                        .min(java.time.LocalDate::compareTo).orElse(null);
                String baselineWorld = worldParam(world);
                java.time.LocalDate laneToday = market.simInstant(baselineWorld)
                        .map(i -> java.time.LocalDate.ofInstant(i,
                                io.liftandshift.strikebench.market.MarketHours.EASTERN))
                        .orElseGet(() -> java.time.LocalDate.now(clock));
                if (frontExp != null) {
                    horizonDays = (int) Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(
                            laneToday, frontExp));
                }
                Double iv = null;
                try { iv = atmIvOf(result.symbol(), baselineWorld); } catch (RuntimeException ignored) { }
                var stockLeg = io.liftandshift.strikebench.model.Leg.stock(
                        io.liftandshift.strikebench.model.LegAction.BUY, 1, q.mark());
                var curve = io.liftandshift.strikebench.pricing.PayoffCurve.of(List.of(stockLeg), 1);
                var rateQuote = market.riskFreeRateQuote(horizonDays, baselineWorld);
                double rate = rateQuote.annualRate();
                var map = io.liftandshift.strikebench.pricing.ProbabilityMap.of(curve, spot,
                        iv != null ? iv : 0.3, horizonDays / 365.0, rate, List.of());
                Map<String, Object> bh = new LinkedHashMap<>();
                bh.put("key", "BUY_AND_HOLD");
                bh.put("evCents", curve.riskNeutralExpectedValueCents(spot,
                        iv != null ? iv : 0.3, horizonDays / 365.0, rate));
                bh.put("capitalCents", lot);
                bh.put("cvar95Cents", map.cvar95Cents());
                bh.put("stressLossCents", map.stressLossCents());
                bh.put("pAnyProfit", map.pAnyProfit());
                bh.put("viable", true);
                bh.put("marketLane", market.lane(baselineWorld).name());
                bh.put("asOfDate", laneToday.toString());
                bh.put("horizonDays", horizonDays);
                bh.put("volatility", iv != null ? iv : 0.3);
                bh.put("volatilityBasis", iv != null ? "same-market ATM IV" : "30% modeled fallback");
                bh.put("rateEvidence", rateQuote.evidence());
                bh.put("note", "Own 100 shares (" + io.liftandshift.strikebench.util.Money.fmt(lot)
                        + "): present-value risk-neutral EV is approximately $0 before costs (r="
                        + String.format(java.util.Locale.ROOT, "%.2f", rate * 100)
                        + "%, q=0 assumed), with no expiry or option spread; the tail numbers are its modeled "
                        + horizonDays + "-day downside at the chain's own vol.");
                baselines.add(bh);
            }
        } catch (RuntimeException ignored) { /* baseline is advisory */ }
        out.put("baselines", baselines);
        if (recommendationId != null) out.put("recommendationId", recommendationId);
        if (inWorld) out.put("calibrationNote",
                "Simulated market — this competition is NOT recorded in your calibration record.");
        return out;
    }

    /** One private cross-surface contract over the shared outcome kernels. */
    private io.liftandshift.strikebench.outcomes.OutcomeContract.Response evaluateOutcomes(
            Context ctx, io.liftandshift.strikebench.outcomes.OutcomeContract.Request request) {
        if (request == null) throw new IllegalArgumentException("outcome request is required");
        if (request.operation() == null) throw new IllegalArgumentException("operation is required");
        var basis = request.basis() == null
                ? request.operation() == io.liftandshift.strikebench.outcomes.OutcomeContract.Operation.DECISION
                    ? io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.DECISION_POLICY
                    : io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.PARAMETRIC
                : request.basis();
        Map<String, Object> resolved = resolveOutcomeContext(ctx, request.context());
        String symbol = String.valueOf(resolved.get("symbol"));
        Object result;
        String interpretation;

        switch (request.operation()) {
            case DECISION -> {
                if (basis != io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.DECISION_POLICY) {
                    throw new IllegalArgumentException("DECISION uses DECISION_POLICY basis");
                }
                if (request.decision() == null) throw new IllegalArgumentException("decision inputs are required");
                if (request.decision().symbol() == null
                        || !symbol.equalsIgnoreCase(request.decision().symbol())) {
                    throw new IllegalArgumentException("decision symbol must match context.symbol");
                }
                result = decisionEvaluationResult(ctx, request.decision());
                interpretation = "One decision policy ranks mechanically eligible structures by after-cost economics, evidence and risk.";
            }
            case PATHS -> {
                if (basis != io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.PARAMETRIC) {
                    throw new IllegalArgumentException("PATHS currently uses PARAMETRIC basis; historical paths come from a Research study");
                }
                List<io.liftandshift.strikebench.sim.SimulationEngine.DecisionLevel> levels = request.levels() == null
                        ? List.of() : request.levels().stream().map(l ->
                            new io.liftandshift.strikebench.sim.SimulationEngine.DecisionLevel(
                                    l.key(), l.price() == null ? Double.NaN : l.price().doubleValue())).toList();
                var run = simScenarioRun(ctx, new ScenarioRequest(symbol, requireOutcomeSpec(request.over()), levels));
                var pathResult = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(run.preview());
                if (request.position() != null) {
                    var position = requireOutcomePosition(request.position());
                    var legs = toSimLegs(ctx, position.legs());
                    Object positionOutcome = simStrategyResult(ctx, new StrategySimRequest(symbol, legs,
                            position.qty(), run.ensemble().spec(), request.iv(),
                            io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC,
                            null, position.entryCostCents(), contractExpirations(position.legs())), run.ensemble());
                    pathResult.set("positionOutcome", Json.MAPPER.valueToTree(positionOutcome));
                    pathResult.put("positionEnsembleFingerprint", run.preview().receipt().fingerprint());
                }
                result = pathResult;
                interpretation = "Model-generated price paths: possible futures, never a forecast or historical frequency.";
            }
            case POSITION -> {
                var position = requireOutcomePosition(request.position());
                if (basis == io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.RISK_NEUTRAL) {
                    result = riskNeutralPositionResult(ctx, symbol, position,
                            new EntryBook(symbol, worldParam(activeWorld(ctx))));
                    interpretation = "Market-implied terminal odds from the exact listed package and executable entry; not a forecast.";
                    break;
                }
                var legs = toSimLegs(ctx, position.legs());
                result = simStrategyResult(ctx, new StrategySimRequest(symbol, legs,
                        position.qty(), requireOutcomeSpec(request.over()), request.iv(), pathBasis(basis),
                        request.study(), position.entryCostCents(), contractExpirations(position.legs())));
                interpretation = basis == io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.PARAMETRIC
                        ? "The exact position is repriced over model-generated paths; probabilities are scenario-conditional, not a forecast."
                        : basis == io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.HISTORICAL_ANALOGS
                            ? "The exact position is repriced over matching historical occurrences; this is conditional history, not model odds."
                            : "The exact position is repriced over whole-path resamples of matching history; this measures sampling uncertainty.";
            }
            case COMPARE -> {
                if (basis == io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.RISK_NEUTRAL) {
                    result = riskNeutralComparisonResult(ctx, symbol, request.positions());
                    interpretation = "Every listed package is judged from one captured market book under the same risk-neutral convention.";
                    break;
                }
                if (request.positions() == null || request.positions().isEmpty()) {
                    throw new IllegalArgumentException("positions are required for COMPARE");
                }
                if (request.positions().size() > 30) throw new IllegalArgumentException("at most 30 positions");
                int qty = request.positions().getFirst().qty() == null ? 1 : request.positions().getFirst().qty();
                List<CompareStructure> structures = new ArrayList<>();
                for (var position : request.positions()) {
                    requireOutcomePosition(position);
                    int pq = position.qty() == null ? 1 : position.qty();
                    if (pq != qty) throw new IllegalArgumentException("COMPARE positions must use the same quantity");
                    structures.add(new CompareStructure(position.key(), toSimLegs(ctx, position.legs()),
                            position.entryCostCents(), contractExpirations(position.legs())));
                }
                result = simCompareResult(ctx, new CompareRequest(symbol, requireOutcomeSpec(request.over()),
                        request.iv(), qty, structures, pathBasis(basis), request.study()));
                interpretation = basis == io.liftandshift.strikebench.outcomes.OutcomeContract.Basis.PARAMETRIC
                        ? "Every position uses one quote snapshot and the same seeded model paths."
                        : "Every position uses one quote snapshot and the same conditional historical ensemble.";
            }
            default -> throw new IllegalArgumentException("Unknown outcome operation");
        }
        return new io.liftandshift.strikebench.outcomes.OutcomeContract.Response(
                request.operation(), basis, resolved, interpretation, result);
    }

    private Map<String, Object> riskNeutralComparisonResult(Context ctx, String symbol,
            List<io.liftandshift.strikebench.outcomes.OutcomeContract.Position> positions) {
        if (positions == null || positions.isEmpty()) {
            throw new IllegalArgumentException("positions are required for COMPARE");
        }
        if (positions.size() > 30) throw new IllegalArgumentException("at most 30 positions");
        EntryBook book = new EntryBook(symbol, worldParam(activeWorld(ctx)));
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> refused = new ArrayList<>();
        for (var position : positions) {
            requireOutcomePosition(position);
            try {
                results.add(Map.of("key", position.key() == null ? "POSITION" : position.key(),
                        "result", riskNeutralPositionResult(ctx, symbol, position, book)));
            } catch (RuntimeException e) {
                refused.add(Map.of("key", position.key() == null ? "POSITION" : position.key(),
                        "reason", io.liftandshift.strikebench.sim.ScenarioSimulator.publicReason(e)));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        out.put("refused", refused);
        out.put("snapshotAt", book.snapshotAt);
        out.put("cashBaseline", Map.of("key", "CASH", "expectedValueCents", 0L,
                "maxLossCents", 0L, "note", "Doing nothing has no modeled market risk or execution cost."));
        out.put("fairness", "one captured quote/chain book and one risk-neutral convention for every listed package");
        return out;
    }

    private Map<String, Object> riskNeutralPositionResult(Context ctx, String symbol,
            io.liftandshift.strikebench.outcomes.OutcomeContract.Position position, EntryBook book) {
        List<String> expirations = contractExpirations(position.legs());
        if (expirations == null || position.legs().stream()
                .filter(l -> l != null && !"STOCK".equalsIgnoreCase(l.type()))
                .anyMatch(l -> l.expiration() == null || l.expiration().isBlank())) {
            throw new IllegalArgumentException("risk-neutral evaluation needs the exact listed expiration on every option leg");
        }
        var distinct = position.legs().stream()
                .filter(l -> l != null && !"STOCK".equalsIgnoreCase(l.type()))
                .map(io.liftandshift.strikebench.outcomes.OutcomeContract.Leg::expiration)
                .distinct().toList();
        if (distinct.size() != 1) {
            throw new IllegalArgumentException("risk-neutral terminal odds support one expiration; use path evaluation for calendars and diagonals");
        }
        int qty = position.qty() == null ? 1 : position.qty();
        List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> simLegs = toSimLegs(ctx, position.legs());
        MarketEntry entry = marketEntry(symbol, simLegs, qty, worldParam(activeWorld(ctx)), book, expirations);
        if (entry == null || entry.pricedLegs() == null || entry.pricedLegs().isEmpty()) {
            throw new IllegalArgumentException("the exact listed package is unavailable at executable prices");
        }
        double iv = entry.averageIv() != null && entry.averageIv() > 0 ? entry.averageIv()
                : entry.atmIv() != null && entry.atmIv() > 0 ? entry.atmIv() : Double.NaN;
        if (!(iv > 0)) throw new IllegalArgumentException("market IV is unavailable for this package");
        var baseCurve = PayoffCurve.of(entry.pricedLegs(), qty);
        long desiredNet = position.entryCostCents() == null ? -entry.entryCents() : -position.entryCostCents();
        long adjustment = desiredNet - baseCurve.entryNetPremiumCents();
        var curve = PayoffCurve.of(entry.pricedLegs(), qty, adjustment);
        java.time.LocalDate today = market.simInstant(worldParam(activeWorld(ctx)))
                .map(i -> java.time.LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                .orElseGet(() -> java.time.LocalDate.now(clock));
        var time = io.liftandshift.strikebench.market.OptionTime.nearest(entry.pricedLegs(), today);
        String outcomeWorld = worldParam(activeWorld(ctx));
        double rate = market.riskFreeRateQuote((int) Math.max(1, time.calendarDays()), outcomeWorld).annualRate();
        var shorts = entry.pricedLegs().stream()
                .filter(l -> !l.isStock() && l.action() == io.liftandshift.strikebench.model.LegAction.SELL)
                .map(Leg::strike).toList();
        var analyzed = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.analyze(
                curve, book.quote().orElseThrow().mark().doubleValue(), iv, time.years(), rate, shorts);
        long contracts = entry.pricedLegs().stream().filter(l -> !l.isStock())
                .mapToLong(Leg::ratio).sum() * qty;
        long roundTripFees = contracts * cfg.feePerContractCents() * 2L
                + cfg.feePerOrderCents() * 2L;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("probabilityMap", analyzed.probabilityMap());
        out.put("expectedValueCents", analyzed.expectedValueCents());
        out.put("expectedValueAfterFeesCents", analyzed.expectedValueCents() - roundTripFees);
        out.put("evSensitivity", analyzed.sensitivity());
        out.put("entryCostCents", -desiredNet);
        out.put("roundTripFeesCents", roundTripFees);
        out.put("marketIv", iv);
        out.put("riskFreeRate", rate);
        out.put("time", time);
        out.put("theoreticalMaxProfitCents", curve.maxProfitUnbounded() ? null : curve.maxProfitCents());
        out.put("theoreticalMaxLossCents", curve.maxLossUnbounded() ? null : curve.maxLossCents());
        out.put("maxProfitUnbounded", curve.maxProfitUnbounded());
        out.put("maxLossUnbounded", curve.maxLossUnbounded());
        out.put("breakevens", curve.breakevens());
        out.put("payoff", curve.chartPoints(book.quote().orElseThrow().mark()));
        out.put("source", entry.source());
        out.put("freshness", entry.freshness());
        out.put("snapshotAt", book.snapshotAt);
        return out;
    }

    private io.liftandshift.strikebench.sim.ScenarioSpec requireOutcomeSpec(
            io.liftandshift.strikebench.sim.ScenarioSpec spec) {
        if (spec == null) throw new IllegalArgumentException("over (scenario specification) is required");
        return spec;
    }

    private static io.liftandshift.strikebench.sim.PathEnsembleService.Basis pathBasis(
            io.liftandshift.strikebench.outcomes.OutcomeContract.Basis basis) {
        return switch (basis) {
            case PARAMETRIC -> io.liftandshift.strikebench.sim.PathEnsembleService.Basis.PARAMETRIC;
            case HISTORICAL_ANALOGS -> io.liftandshift.strikebench.sim.PathEnsembleService.Basis.HISTORICAL_ANALOGS;
            case CONDITIONAL_BOOTSTRAP -> io.liftandshift.strikebench.sim.PathEnsembleService.Basis.CONDITIONAL_BOOTSTRAP;
            default -> throw new IllegalArgumentException(basis + " is not a path-ensemble basis");
        };
    }

    private io.liftandshift.strikebench.outcomes.OutcomeContract.Position requireOutcomePosition(
            io.liftandshift.strikebench.outcomes.OutcomeContract.Position position) {
        if (position == null || position.legs() == null || position.legs().isEmpty()) {
            throw new IllegalArgumentException("position with at least one leg is required");
        }
        if (position.qty() != null && (position.qty() < 1 || position.qty() > 100)) {
            throw new IllegalArgumentException("position quantity must be 1..100");
        }
        return position;
    }

    private List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> toSimLegs(
            Context ctx, List<io.liftandshift.strikebench.outcomes.OutcomeContract.Leg> legs) {
        List<io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg> out = new ArrayList<>();
        java.time.LocalDate laneToday = market.simInstant(worldParam(activeWorld(ctx)))
                .map(i -> java.time.LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                .orElseGet(() -> java.time.LocalDate.now(clock));
        for (var leg : legs) {
            if (leg == null || leg.action() == null || leg.type() == null) {
                throw new IllegalArgumentException("each position leg needs action and type");
            }
            String type = leg.type().trim().toUpperCase(Locale.ROOT);
            int ratio = leg.ratio() == null ? 1 : leg.ratio();
            if ("STOCK".equals(type)) {
                out.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg(
                        leg.action().trim().toUpperCase(Locale.ROOT), "STOCK", 0, 0, ratio));
                continue;
            }
            if (leg.strike() == null || leg.strike().signum() <= 0) {
                throw new IllegalArgumentException("option legs need a positive strike");
            }
            int expiryDay;
            if (leg.expiryDay() != null) expiryDay = leg.expiryDay();
            else if (leg.expiration() != null && !leg.expiration().isBlank()) {
                expiryDay = outcomeExpiryDay(laneToday, java.time.LocalDate.parse(leg.expiration()));
            } else throw new IllegalArgumentException("option legs need expiration or expiryDay");
            out.add(new io.liftandshift.strikebench.sim.ScenarioSimulator.SimLeg(
                    leg.action().trim().toUpperCase(Locale.ROOT), type,
                    leg.strike().doubleValue(), expiryDay, ratio));
        }
        return out;
    }

    static int outcomeExpiryDay(java.time.LocalDate today, java.time.LocalDate expiration) {
        return Math.max(1, io.liftandshift.strikebench.market.MarketHours
                .tradingDaysBetween(today, expiration));
    }

    private List<String> contractExpirations(
            List<io.liftandshift.strikebench.outcomes.OutcomeContract.Leg> legs) {
        boolean any = legs.stream().anyMatch(l -> l != null && l.expiration() != null && !l.expiration().isBlank());
        if (!any) return null;
        return legs.stream().map(l -> l == null ? null : l.expiration()).toList();
    }

    private Map<String, Object> resolveOutcomeContext(Context ctx,
            io.liftandshift.strikebench.outcomes.OutcomeContract.MarketContext requested) {
        if (requested == null || requested.symbol() == null || requested.symbol().isBlank()) {
            throw new IllegalArgumentException("context.symbol is required");
        }
        String symbol = requested.symbol().trim().toUpperCase(Locale.ROOT);
        String world = activeWorld(ctx);
        var analysis = analysisCtx(ctx);
        String lane = io.liftandshift.strikebench.market.MarketLane
                .of(world, cfg.fixturesOnly(), analysis).name();
        if (requested.worldId() != null && !requested.worldId().isBlank()
                && !world.equals(requested.worldId())) {
            throw new IllegalStateException("Evaluation context changed: active market is " + world);
        }
        if (requested.datasetId() != null && !requested.datasetId().isBlank()
                && !analysis.datasetId().equals(requested.datasetId())) {
            throw new IllegalStateException("Evaluation context changed: active dataset is " + analysis.datasetId());
        }
        if (requested.marketLane() != null && !requested.marketLane().isBlank()
                && !lane.equalsIgnoreCase(requested.marketLane())) {
            throw new IllegalStateException("Evaluation context changed: active lane is " + lane);
        }
        var quote = market.quote(symbol, worldParam(world)).orElse(null);
        String asOf = quote == null || quote.asOfEpochMs() <= 0 ? null
                : java.time.Instant.ofEpochMilli(quote.asOfEpochMs()).toString();
        if (requested.asOf() != null && !requested.asOf().isBlank()) {
            java.time.Instant expected = java.time.Instant.parse(requested.asOf());
            if (asOf == null || !expected.equals(java.time.Instant.parse(asOf))) {
                throw new IllegalStateException("Evaluation context changed: the quote snapshot advanced; refresh before comparing");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("marketLane", lane);
        out.put("worldId", world);
        out.put("datasetId", analysis.datasetId());
        if (asOf != null) out.put("asOf", asOf);
        out.put("serverTime", market.simInstant(worldParam(world)).orElse(clock.instant()).toString());
        return out;
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
        String world = worldParam(activeWorld(ctx));
        // Inside a simulated session the scan covers the WORLD's symbols through the world-routed
        // engine — an observed-universe scan sized with sim capital was a cross-lane blend (P1).
        List<String> symbols = (req.universe() != null && !req.universe().isEmpty())
                ? req.universe()
                : world != null
                        ? market.worldSymbols(world).map(List::copyOf).orElse(List.of())
                        : universe.active().symbols();
        Account acct = currentAccount(ctx);
        String uid = auth.currentUserId(ctx);
        String ownerId = io.liftandshift.strikebench.auth.AuthService.LOCAL_USER.equals(uid) ? null : uid;
        int topN = req.topN() != null ? req.topN() : 8;
        var rcScan = io.liftandshift.strikebench.paper.AccountRiskContext.load(db, ownerId(ctx));
        var result = evaluations.scan(symbols, req.intent(),
                req.thesis() != null ? req.thesis() : "neutral",
                req.horizon() != null ? req.horizon() : "month",
                req.riskMode() != null ? req.riskMode() : "balanced",
                acct.buyingPowerCents(), world != null ? null : ownerId, topN, world, rcScan.riskCapitalCents());
        ctx.json(Map.of("ranked", result.ranked(), "notes", result.notes(), "scanned", result.scanned()));
    }

    public record OptimizeRequest(List<String> universe, String thesis, String horizon, String riskMode,
                                  String intent, Long totalCapitalCents, Long maxPerPositionCents,
                                  Integer maxPositions, Double maxSymbolPct, String objective, Boolean diagnostic) {}

    /** Portfolio construction: scan a universe, then allocate a budget across the winners. */
    private void optimize(Context ctx) {
        OptimizeRequest req = bodyOrNull(ctx, OptimizeRequest.class);
        if (req == null) req = new OptimizeRequest(null, null, null, null, null, null, null, null, null, null, null);
        String optWorld = worldParam(activeWorld(ctx));
        List<String> symbols = (req.universe() != null && !req.universe().isEmpty())
                ? req.universe()
                : optWorld != null
                        ? market.worldSymbols(optWorld).map(List::copyOf).orElse(List.of())
                        : universe.active().symbols();
        Account acct = currentAccount(ctx);
        String uid = auth.currentUserId(ctx);
        String ownerId = io.liftandshift.strikebench.auth.AuthService.LOCAL_USER.equals(uid) ? null : uid;
        var rcOpt = io.liftandshift.strikebench.paper.AccountRiskContext.load(db, ownerId(ctx));
        var scan = evaluations.scan(symbols, req.intent(),
                req.thesis() != null ? req.thesis() : "neutral",
                req.horizon() != null ? req.horizon() : "month",
                req.riskMode() != null ? req.riskMode() : "balanced",
                acct.buyingPowerCents(), optWorld != null ? null : ownerId, Math.max(1, symbols.size()),
                optWorld, rcOpt.riskCapitalCents());
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

    private void researchIntentLadder(Context ctx) {
        String symbol = ctx.pathParam("symbol").trim().toUpperCase(Locale.ROOT);
        RecommendationEngine.Request req = bodyOrNull(ctx, RecommendationEngine.Request.class);
        if (req == null) {
            req = new RecommendationEngine.Request(symbol, null, "month", null, null, null, null,
                    null, true, false, null, null, null);
        } else {
            if (req.symbol() != null && !req.symbol().isBlank()
                    && !symbol.equalsIgnoreCase(req.symbol())) {
                throw new IllegalArgumentException("The ladder symbol must match the Research workspace");
            }
            req = new RecommendationEngine.Request(symbol, req.thesis(), req.horizon(), req.riskMode(),
                    req.maxLossCents(), req.maxRiskPctOfAccount(), req.minConfidence(), req.allowedStrategies(),
                    req.avoidEarnings(), req.allow0dte(), req.intent(), req.holdings(), req.filters());
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
        // R4 via THE policy: ladders obey the same declared-capital translation as every surface.
        Long capLad = RiskBudgetPolicy.effectiveMaxLossCents(req.maxLossCents(), riskCapCents(ctx));
        if (!java.util.Objects.equals(capLad, req.maxLossCents())) {
            req = new RecommendationEngine.Request(req.symbol(), req.thesis(), req.horizon(), req.riskMode(),
                    capLad, req.maxRiskPctOfAccount(), req.minConfidence(), req.allowedStrategies(),
                    req.avoidEarnings(), req.allow0dte(), req.intent(), req.holdings(), req.filters());
        }
        var ladder = engine.ladder(req, acct.buyingPowerCents(), activeWorld(ctx));
        // R9: the SAME decision policy annotates every rung — no ranked surface escapes it.
        try {
            var rungEvals = evaluations.evaluate(req.symbol(), req.intent(), req.thesis(), req.horizon(),
                    req.riskMode(), ladder.rungs(), acct.buyingPowerCents(), null, false,
                    io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, worldParam(activeWorld(ctx)));
            if (rungEvals.size() == ladder.rungs().size()) {
                com.fasterxml.jackson.databind.node.ObjectNode out =
                        (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(ladder);
                com.fasterxml.jackson.databind.node.ArrayNode arr = out.putArray("rungs");
                // ladder ORDER is the strike ladder (its meaning) — decision score is an annotation
                var byCand = new java.util.IdentityHashMap<Object, io.liftandshift.strikebench.eval.StrategyEvaluation>();
                for (var e : rungEvals) byCand.put(e.candidate(), e);
                for (var c : ladder.rungs()) {
                    com.fasterxml.jackson.databind.node.ObjectNode m =
                            (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(c);
                    var e = byCand.get(c);
                    if (e != null) {
                        m.put("decisionScore", e.decisionScore());
                        m.put("decisionViable", e.viable());
                        m.put("structurallyEligible", e.viable());
                        if (e.economics() != null) {
                            m.set("economics", Json.MAPPER.valueToTree(e.economics()));
                            m.put("economicVerdict", e.economics().verdict().name());
                            m.put("economicPlacement", e.economics().placement());
                        }
                        attachEvaluationReceipt(m, e);
                    }
                    arr.add(m);
                }
                ctx.json(out);
                return;
            }
        } catch (RuntimeException e) {
            log.warn("Ladder decision details are temporarily unavailable");
            log.debug("Ladder decision-detail failure", e);
        }
        ctx.json(ladder);
    }

    private void researchScout(Context ctx) {
        AutoRecommender.AutoRequest req = bodyOrNull(ctx, AutoRecommender.AutoRequest.class);
        if (req == null) { // absent body (or the literal document "null") means defaults
            req = new AutoRecommender.AutoRequest(null, null, null, null, null, null, null, null, null, null, null);
        }
        String world = activeWorld(ctx);
        if (req.universe() == null || req.universe().isEmpty()) {
            // Default scan list: the selected universe — or, inside a simulated session, the
            // world's OWN symbols (the observed universe does not exist in that market).
            List<String> scan = "observed".equals(world) ? universe.active().symbols()
                    : simSessions.getOrRestore(world, ownerId(ctx))
                            .map(w -> List.copyOf(w.config().symbolBetas().keySet()))
                            .orElseGet(() -> universe.active().symbols());
            req = new AutoRecommender.AutoRequest(scan, req.horizons(), req.maxPicks(),
                    req.targetProfitCents(), req.maxLossCents(), req.maxRiskPctOfAccount(), req.minConfidence(),
                    req.riskMode(), req.allow0dte(), req.intents(), req.filters());
        }
        Account acct = currentAccount(ctx);
        // THE policy applies to the scout too (review IC-1): auto and manual recommendations
        // must size under the identical declared-capital cap.
        Long capAuto = RiskBudgetPolicy.effectiveMaxLossCents(req.maxLossCents(), riskCapCents(ctx));
        if (!java.util.Objects.equals(capAuto, req.maxLossCents())) {
            req = new AutoRecommender.AutoRequest(req.universe(), req.horizons(), req.maxPicks(),
                    req.targetProfitCents(), capAuto, req.maxRiskPctOfAccount(), req.minConfidence(),
                    req.riskMode(), req.allow0dte(), req.intents(), req.filters());
        }
        // Real holdings feed the EXIT/HEDGE scans and let INCOME write against held shares
        List<AutoRecommender.HoldingInfo> held = positions.list(acct.id()).stream()
                .map(p -> new AutoRecommender.HoldingInfo(p.symbol(),
                        (int) Math.min(Integer.MAX_VALUE, p.freeShares()), p.avgCostCents()))
                .toList();
        ctx.json(auto.run(req, acct.buyingPowerCents(), held, world));
    }

    // ---- Trades ----

    public record TradeOpenRequest(String symbol, String strategy, Integer qty, List<LegView> legs,
                                   String thesis, String horizon, String riskMode,
                                   String intent, Boolean useHeldShares, String recommendationId,
                                   Long proposedNetCents, Long feesOverrideCents, String source,
                                   String executedAt, String broker, String orderRef, Boolean historical,
                                   List<String> acknowledgedRisks, String ackToken) {}

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
                body.intent(), body.useHeldShares(),
                body.proposedNetCents(), body.feesOverrideCents(),
                body.source() == null || body.source().isBlank() ? "TICKET" : body.source());
    }

    /** The caller's declared per-trade risk capital (AccountRiskContext), or null when unset. */
    private Long riskCapCents(Context ctx) {
        var rc = io.liftandshift.strikebench.paper.AccountRiskContext.load(db, ownerId(ctx));
        return rc.riskCapitalCents() != null && rc.riskCapitalCents() > 0 ? rc.riskCapitalCents() : null;
    }

    /**
     * ONE SOURCE OF TRUTH for the per-idea capital budget (review P0): the server computes it,
     * the header, the ticket reconciliation, the guardrail advisory and the screening engine all
     * speak these numbers. Basis is the CALLER'S CURRENT account's buying power (cash minus
     * reserves — paper and simulation accounts are cash-only, so no margin figure can silently
     * inflate the denominator), capped by the user's declared risk capital when set.
     */
    private void riskBudget(Context ctx) {
        Account acct = currentAccount(ctx);
        Long cap = riskCapCents(ctx);
        java.util.List<Map<String, Object>> modes = new ArrayList<>();
        for (RecommendationEngine.RiskMode m : RecommendationEngine.RiskMode.values()) {
            var b = RiskBudgetPolicy.compute(m, acct.buyingPowerCents(), cap);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mode", b.mode());
            row.put("label", b.label());
            row.put("percent", b.percent());
            row.put("policyBudgetCents", b.policyBudgetCents());
            row.put("effectiveBudgetCents", b.effectiveBudgetCents());
            row.put("capped", b.capped());
            modes.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("basisType", "BUYING_POWER");
        out.put("basisCents", acct.buyingPowerCents());
        out.put("accountType", acct.type());
        out.put("explicitCapCents", cap);
        out.put("capSource", cap != null ? "RISK_CAPITAL" : null);
        out.put("modes", modes);
        out.put("note", "Per-idea budget = percent \u00d7 buying power (cash minus reserves; this practice "
                + "account is cash-only, no margin). Your declared risk capital, when set, caps every mode. "
                + "The screening engine enforces these same numbers server-side.");
        out.put("acquireException", "Buy-shares-at-a-discount ideas are capped by buying power instead \u2014 "
                + "a cash-secured put sets aside the full purchase price by design.");
        ctx.json(out);
    }

    private Verdict guardrailCheck(TradeService.OpenRequest req, Account acct, Long riskCapCents) {
        StrategyFamily family = null;
        try { family = StrategyFamily.valueOf(req.strategy()); } catch (IllegalArgumentException ignored) {}
        List<OptionQuote> quotes = new ArrayList<>();
        Freshness worst = Freshness.REALTIME;
        String accountWorld = "DEMO".equals(acct.type()) ? "demo" : acct.worldId();
        var lane = io.liftandshift.strikebench.market.MarketLane.of(accountWorld, cfg.fixturesOnly());
        List<String> integrityBlocks = new ArrayList<>();
        // Earnings proximity from the CALENDAR ESTIMATE (SEC filing cadence), never keywords —
        // the MU incident's warning fired on stale results headlines two weeks AFTER earnings.
        // Keyword hits become a separate, honestly-labeled advisory below. Ex-div still has no
        // keyless source and stays false — documented limitation.
        java.time.LocalDate latestExp = req.legs().stream()
                .filter(l -> !l.isStock()).map(Leg::expiration).filter(java.util.Objects::nonNull)
                .max(java.time.LocalDate::compareTo).orElse(null);
        // A simulated world has NO earnings and NO news — real-company events attached to a
        // generated market were phantom risks (review P2). Observed lane keeps both.
        boolean inWorld = accountWorld != null;
        boolean earningsSoon = !inWorld && latestExp != null
                && eventCalendar.earningsLikelyBefore(req.symbol(), latestExp);
        boolean eventLikeNews = !inWorld && market.news(req.symbol()).stream().anyMatch(n -> {
            String h = n.headline() == null ? "" : n.headline().toLowerCase(Locale.ROOT);
            return h.contains("earnings") || h.contains("guidance") || h.contains("results");
        });
        Quote underlyingQuote = market.quote(req.symbol(), accountWorld).orElse(null);
        if (underlyingQuote != null && !underlyingQuote.evidence().usableIn(lane)) {
            integrityBlocks.add("The " + lane + " market cannot use " + underlyingQuote.evidence().provenance()
                    + " underlying data from " + underlyingQuote.evidence().source());
        }
        BigDecimal spot = underlyingQuote == null ? null : underlyingQuote.mark();
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
            OptionChain legChain = market.chain(req.symbol(), leg.expiration(), accountWorld).orElse(null);
            if (legChain != null && !legChain.evidence().executableIn(lane)) {
                integrityBlocks.add("The " + lane + " market cannot execute " + leg.strike() + " "
                        + leg.type() + " " + leg.expiration() + " from "
                        + legChain.evidence().provenance() + " data (" + legChain.evidence().source() + ")");
            }
            OptionQuote q = legChain == null ? null : legChain.find(leg.type(), leg.strike()).orElse(null);
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
        LocalDate laneToday = market.simInstant(accountWorld)
                .map(i -> LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                .orElseGet(() -> LocalDate.now(clock));
        Verdict verdict = Guardrails.check(new Guardrails.Proposal(family, priced, req.qty(), quotes, spot,
                worst, laneToday, acct.buyingPowerCents(), false, earningsSoon, false, lockedLots));
        if (!integrityBlocks.isEmpty()) {
            List<String> blocks = new ArrayList<>(verdict.blockReasons());
            blocks.addAll(0, integrityBlocks);
            verdict = Verdict.of(blocks, verdict.warnings());
        }
        if (shareShortfall != null) {
            List<String> blocks = new ArrayList<>(verdict.blockReasons());
            blocks.addFirst(shareShortfall);
            verdict = Verdict.of(blocks, verdict.warnings());
        }
        if (earningsSoon) {
            var est = eventCalendar.nextEarnings(req.symbol()).orElse(null);
            if (est != null) {
                List<String> warnings = new ArrayList<>(verdict.warnings());
                warnings.add("Earnings ESTIMATED around " + est.estimated() + " \u00b1" + est.windowDays()
                        + "d (" + est.basis() + ") \u2014 not a confirmed date, but it lands inside this trade");
                verdict = Verdict.of(verdict.blockReasons(), warnings);
            }
        } else if (eventLikeNews) {
            List<String> warnings = new ArrayList<>(verdict.warnings());
            warnings.add("Event-like news in recent headlines (earnings/guidance keywords) \u2014 a news signal only; "
                    + "no earnings event is ESTIMATED before this trade's expiration");
            verdict = Verdict.of(verdict.blockReasons(), warnings);
        }
        // The manual ticket is not bound by risk-mode budgets (deliberate freedom), but an
        // oversized trade deserves a loud flag against the mode the user chose in the header.
        try {
            // The guardrail judges the SAME repriced package the analytics show (R5/CP-3): a
            // proposed net shifts max loss here exactly as it does in the preview (review P3).
            Long gAdjust = null;
            if (req.proposedNetCents() != null) {
                long pricedNet = 0;
                for (Leg l : priced) {
                    long shares = (long) Leg.SHARES_PER_CONTRACT * l.ratio() * req.qty();
                    long cents = io.liftandshift.strikebench.util.Money.centsFromPrice(l.entryPrice(), shares);
                    pricedNet += l.action() == io.liftandshift.strikebench.model.LegAction.SELL ? cents : -cents;
                }
                gAdjust = req.proposedNetCents() - pricedNet;
            }
            io.liftandshift.strikebench.pricing.PayoffCurve curve =
                    io.liftandshift.strikebench.pricing.PayoffCurve.of(priced, req.qty(), gAdjust);
            if (!curve.maxLossUnbounded() && curve.maxLossCents() > 0) {
                RecommendationEngine.RiskMode mode = RecommendationEngine.RiskMode.parse(req.riskMode());
                // The SAME effective budget /api/risk-budget publishes — THE policy (review IC-1).
                long budget = RiskBudgetPolicy.compute(mode, acct.buyingPowerCents(), riskCapCents)
                        .effectiveBudgetCents();
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
        ctx.json(tradePreviewPayload(ctx, bodyOrNull(ctx, TradeOpenRequest.class)));
    }

    private Map<String, Object> tradePreviewPayload(Context ctx, TradeOpenRequest body) {
        Account acct = currentAccount(ctx);
        TradeService.OpenRequest req = toOpenRequest(body, acct);
        Verdict verdict = guardrailCheck(req, acct, riskCapCents(ctx));
        var preview = trades.preview(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preview", preview);
        Candidate exact = exactPreviewCandidate(req, preview);
        io.liftandshift.strikebench.eval.EconomicAssessment economics;
        long roundTripFees = Math.multiplyExact(preview.feesOpenCents(), 2L);
        try {
            economics = evaluations.assessExact(req.symbol(), exact, acct.buyingPowerCents(),
                    analysisCtx(ctx), worldParam(activeWorld(ctx)), preview.ok(), preview.blockReasons(),
                    roundTripFees);
        } catch (RuntimeException e) {
            log.warn("Exact-ticket economics are unavailable for this preview");
            log.debug("Exact-ticket economic-assessment failure", e);
            var provenance = preview.evidence().provenance();
            boolean observed = provenance == io.liftandshift.strikebench.model.DataProvenance.OBSERVED
                    || provenance == io.liftandshift.strikebench.model.DataProvenance.BROKER;
            economics = new io.liftandshift.strikebench.eval.EconomicAssessment(
                    io.liftandshift.strikebench.eval.EconomicAssessment.Verdict.UNAVAILABLE,
                    "MECHANICS_ONLY", "Economics unavailable",
                    "The package was checked mechanically, but the available volatility/history inputs cannot support an economic verdict right now.",
                    preview.expectedValueCents() == null ? null : preview.expectedValueCents() - roundTripFees,
                    null, roundTripFees, null, observed,
                    List.of("Exact economic comparison is unavailable; no favorable claim is made."));
        }
        out.put("economics", economics);
        out.put("guardrails", Map.of(
                "level", verdict.level().name(),
                "blockReasons", verdict.blockReasons(),
                "warnings", verdict.warnings()));
        // The REAL denominators, when the user declared them: risk judged against paper cash was
        // the MU lesson's sizing blind spot ('sane vs \$100k paper' was 6.5% of the real account).
        var rc = io.liftandshift.strikebench.paper.AccountRiskContext.load(db, ownerId(ctx));
        // R2: material-risk acknowledgments are a SERVER contract, not a UI courtesy — the same
        // list is recomputed at create and enforced with a signed token proving the user saw
        // a preview of THIS exact package.
        long effectiveRiskBudget = RiskBudgetPolicy.compute(
                RecommendationEngine.RiskMode.parse(req.riskMode()), acct.buyingPowerCents(), riskCapCents(ctx))
                .effectiveBudgetCents();
        List<Map<String, String>> requiredAcks = requiredAcksFor(preview, effectiveRiskBudget);
        if (!requiredAcks.isEmpty()) {
            out.put("requiredAcks", requiredAcks);
            out.put("ackToken", ackToken(req));
        }
        if (!rc.isEmpty() && preview.maxLossCents() > 0) {
            Map<String, Object> fit = new LinkedHashMap<>();
            if (rc.nlvCents() != null && rc.nlvCents() > 0)
                fit.put("pctOfNlv", Math.round(1000.0 * preview.maxLossCents() / rc.nlvCents()) / 10.0);
            if (rc.cashBpCents() != null && rc.cashBpCents() > 0)
                fit.put("pctOfCashBp", Math.round(1000.0 * preview.maxLossCents() / rc.cashBpCents()) / 10.0);
            if (rc.marginBpCents() != null && rc.marginBpCents() > 0)
                fit.put("pctOfMarginBp", Math.round(1000.0 * preview.maxLossCents() / rc.marginBpCents()) / 10.0);
            if (rc.riskCapitalCents() != null && rc.riskCapitalCents() > 0) {
                fit.put("pctOfRiskCapital", Math.round(1000.0 * preview.maxLossCents() / rc.riskCapitalCents()) / 10.0);
                if (preview.maxLossCents() > rc.riskCapitalCents()) {
                    fit.put("overRiskCapital", true);
                }
            }
            out.put("accountFit", fit);
        }
        return out;
    }

    /** Builds the evaluator's position contract from the SERVER-priced preview. Package entry,
     * fills, fees and combined held-share risk all come from that exact snapshot; the client does
     * not get to assert an economic verdict. */
    private static Candidate exactPreviewCandidate(TradeService.OpenRequest req,
                                                   io.liftandshift.strikebench.paper.TradePreview preview) {
        StrategyFamily family = null;
        try { family = StrategyFamily.valueOf(req.strategy().trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ignored) { /* explicit custom package */ }
        String display = family == null ? "Custom position" : family.display();
        String group = family == null ? "custom" : family.structureGroup();
        String intent = req.intent() == null || req.intent().isBlank()
                ? family == null ? StrategyIntent.DIRECTIONAL.name() : family.primaryIntent().name()
                : StrategyIntent.parse(req.intent()).name();
        List<String> intents = family == null ? List.of(intent)
                : family.intents().stream().map(Enum::name).sorted().toList();
        List<LegView> legs = preview.legs().stream().map(m -> new LegView(
                java.util.Objects.toString(m.get("action"), null),
                java.util.Objects.toString(m.get("type"), null),
                java.util.Objects.toString(m.get("strike"), null),
                java.util.Objects.toString(m.get("expiration"), null),
                m.get("ratio") instanceof Number n ? n.intValue() : 1,
                java.util.Objects.toString(m.get("fill"), null))).toList();
        boolean liquid = preview.legs().stream().filter(m -> !"STOCK".equals(m.get("type")))
                .allMatch(m -> m.get("bid") != null && m.get("ask") != null);
        Long combinedMaxLoss = preview.analytics().get("combinedMaxLossCents") instanceof Number n
                ? n.longValue() : null;
        Integer sharesNeeded = null;
        if (req.heldShares()) {
            int lots = Math.max(1,
                    io.liftandshift.strikebench.strategy.CoverageCheck.callCoverLotsNeeded(req.legs()));
            sharesNeeded = Math.multiplyExact(Math.multiplyExact(lots, Leg.SHARES_PER_CONTRACT), req.qty());
        }
        return new Candidate(req.strategy(), display, group, display, legs, req.qty(),
                preview.entryNetPremiumCents(), preview.maxProfitCents(), preview.maxLossCents(),
                preview.breakevens(), preview.popEntry(), preview.expectedValueCents(), liquid ? 1.0 : 0.0,
                preview.freshness(), preview.warnings(), 0, 1, "Exact ticket", "", "", "", "",
                intent, intents, preview.assignmentProb(), null, null, null,
                req.heldShares() ? Boolean.TRUE : null, sharesNeeded, combinedMaxLoss);
    }

    private void tradeCreate(Context ctx) {
        CreatedTrade created = executeTrade(ctx, bodyOrNull(ctx, TradeOpenRequest.class), null);
        ctx.status(201).json(Map.of("trade", TradeView.of(created.trade()), "warnings", created.verdict().warnings()));
    }

    private record CreatedTrade(TradeRecord trade, Verdict verdict) {}

    private CreatedTrade executeTrade(Context ctx, TradeOpenRequest body, TradeService.TransactionHook hook) {
        Account acct = currentAccount(ctx);
        TradeService.OpenRequest req = toOpenRequest(body, acct);
        // Structural eligibility comes before discretionary risk acknowledgments. An impossible
        // covered call, stale book, or undefined-risk package must say WHY it cannot be placed;
        // asking the user to acknowledge EV on an order that can never pass is incoherent.
        Verdict verdict = guardrailCheck(req, acct, riskCapCents(ctx));
        if (verdict.blocked()) {
            audit.log(acct.id(), null, "TRADE_REJECTED", "BLOCK",
                    Map.of("symbol", req.symbol(), "strategy", req.strategy(), "reasons", verdict.blockReasons()));
            throw new TradeRejectedException(verdict.blockReasons());
        }
        // R2: recompute the material risks for THIS package and enforce acknowledgment + the
        // signed token — a raw API call can no longer skip what the Review made explicit.
        long effectiveRiskBudget = RiskBudgetPolicy.compute(
                RecommendationEngine.RiskMode.parse(req.riskMode()), acct.buyingPowerCents(), riskCapCents(ctx))
                .effectiveBudgetCents();
        List<Map<String, String>> required = requiredAcksFor(trades.preview(req), effectiveRiskBudget);
        if (!required.isEmpty()) {
            java.util.Set<String> acked = body.acknowledgedRisks() == null
                    ? java.util.Set.of() : new java.util.HashSet<>(body.acknowledgedRisks());
            List<String> missing = required.stream().map(m -> m.get("id"))
                    .filter(id -> !acked.contains(id)).toList();
            if (!missing.isEmpty()) {
                throw new TradeRejectedException(List.of("This trade carries material risks that must be "
                        + "acknowledged first: " + String.join(", ", missing) + " — preview it to see them"));
            }
            if (!verifyAckToken(body.ackToken(), req)) {
                throw new TradeRejectedException(List.of("Acknowledgment token missing or stale — preview this exact package again"));
            }
        }
        TradeRecord t = trades.create(req, hook);
        // Close the calibration loop: link the placed trade to the recommendation it came from —
        // REAL LANE ONLY: a simulated market's outcomes never feed 'Your record' (review P0).
        if (body != null && body.recommendationId() != null && !body.recommendationId().isBlank()
                && acct.worldId() == null) {
            try { evaluations.linkTrade(body.recommendationId(), t.id()); }
            catch (RuntimeException e) {
                log.warn("The paper trade could not be linked to its recommendation record");
                log.debug("Recommendation-link detail", e);
            }
        }
        return new CreatedTrade(t, verdict);
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
                        row.put("decisionUnrealizedPnlCents",
                                mark.decisionUnrealizedCents() != null
                                        ? mark.decisionUnrealizedCents() : mark.unrealizedCents());
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
    public record StockOrderPreviewRequest(String side, String symbol, Long shares) {}

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

    private void positionsPreview(Context ctx) {
        StockOrderPreviewRequest req = bodyOrNull(ctx, StockOrderPreviewRequest.class);
        if (req == null) throw new IllegalArgumentException("request body is required");
        validateStockOrder(new StockOrderRequest(req.symbol(), req.shares()));
        if (req.side() == null || req.side().isBlank()) throw new IllegalArgumentException("side is required");
        Account acct = currentAccount(ctx);
        ctx.json(positions.preview(acct.id(), req.side(), req.symbol(), req.shares()));
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
        ctx.json(tradeDetailData(id));
    }

    private Map<String, Object> tradeDetailData(String id) {
        TradeRecord t = trades.get(id);
        TradeService.MarkView current = null;
        if (TradeRecord.ACTIVE.equals(t.status())) {
            try {
                current = trades.currentMark(id);
            } catch (Exception e) {
                log.warn("Current paper-trade mark is unavailable for {}", id);
                log.debug("Paper-trade mark detail for " + id, e);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade", TradeView.of(t));
        out.put("current", current);
        out.put("marksHistory", trades.marksHistory(id, 50));
        out.put("audit", audit.forTrade(id, 50));
        out.put("payoff", payoffPoints(t));
        return out;
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
        long tradedLegEntry = PayoffCurve.of(t.legs(), t.qty()).entryNetPremiumCents();
        long packageAdjustment = t.entryNetPremiumCents() - tradedLegEntry;
        PayoffCurve curve = PayoffCurve.of(chartLegs, t.qty(), packageAdjustment);
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
        long decisionPnl = decisionPnl(result.trade(), result.realizedPnlCents());
        resolveRecommendationForTrade(ctx.pathParam("id"), "CLOSED", decisionPnl);
        ctx.json(Map.of("trade", TradeView.of(result.trade()),
                "realizedPnlCents", result.realizedPnlCents(), "decisionPnlCents", decisionPnl));
    }

    private void tradeSettle(Context ctx) {
        ensureOwnedTrade(ctx, ctx.pathParam("id"));
        ConfirmRequest req = bodyOrNull(ctx, ConfirmRequest.class);
        TradeService.CloseResult result = trades.settle(ctx.pathParam("id"),
                req != null && Boolean.TRUE.equals(req.confirm()));
        long decisionPnl = decisionPnl(result.trade(), result.realizedPnlCents());
        resolveRecommendationForTrade(ctx.pathParam("id"), "SETTLED", decisionPnl);
        ctx.json(Map.of("trade", TradeView.of(result.trade()),
                "realizedPnlCents", result.realizedPnlCents(), "decisionPnlCents", decisionPnl));
    }

    private static long decisionPnl(TradeRecord trade, long packagePnlCents) {
        return trade.decisionPnlCents() != null ? trade.decisionPnlCents() : packagePnlCents;
    }

    /** Best-effort: only observed/broker outcomes enter recommendation calibration. Generated
     *  Demo and simulated markets remain separate Plan-review and rehearsal evidence lanes. */
    private void resolveRecommendationForTrade(String tradeId, String status, Long pnlCents) {
        try {
            var t = trades.get(tradeId);
            record AccountLane(String type, String worldId) {}
            var acctRows = db.query("SELECT type,world_id FROM accounts WHERE id=?",
                    r -> new AccountLane(r.str("type"), r.str("world_id")), t.accountId());
            if (acctRows.isEmpty()) return;
            AccountLane lane = acctRows.getFirst();
            if (lane.worldId() != null || "DEMO".equals(lane.type()) || "SIMULATION".equals(lane.type())) return;
            if (!("OBSERVED".equals(t.dataProvenance()) || "BROKER".equals(t.dataProvenance()))) return;
            evaluations.resolveByTrade(tradeId, status, pnlCents);
        }
        catch (RuntimeException e) {
            log.warn("Recommendation context is unavailable for trade {}", tradeId);
            log.debug("Recommendation-context detail for " + tradeId, e);
        }
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
