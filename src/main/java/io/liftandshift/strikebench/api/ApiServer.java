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
    private io.liftandshift.strikebench.paper.PortfolioAccountingService portfolioBooks;
    private io.liftandshift.strikebench.paper.PortfolioExportService portfolioExports;
    private Javalin app;
    private Db db;   // owned pool; closed on stop()
    private io.liftandshift.strikebench.market.MarketDataEngine marketEngine;   // in-memory feed; warm + background refresh
    private io.liftandshift.strikebench.db.DataJobService dataJobs;             // Data Center background jobs
    private io.liftandshift.strikebench.db.DataCoverage dataCoverage;           // Data Center coverage matrix
    private io.liftandshift.strikebench.db.DataResetService dataReset;          // Data Center tiered wipe
    private io.liftandshift.strikebench.db.DataConnectorCatalog dataConnectors;
    private io.liftandshift.strikebench.db.DataSyncState dataSyncState;
    private io.liftandshift.strikebench.db.DataSyncScheduler dataSyncScheduler;
    private io.liftandshift.strikebench.db.ArtifactRetentionService artifactRetention;
    private CboeProvider cboe;                                                  // for Data Center throttle display
    private io.liftandshift.strikebench.db.DatasetService datasets;             // observed + synthetic dataset registry
    private io.liftandshift.strikebench.sim.SimulationEngine simEngine;         // scenario previews + dataset runs
    private io.liftandshift.strikebench.sim.PathEnsembleService pathEnsembles;   // one lane-aware path source
    private java.util.concurrent.ScheduledExecutorService streamScheduler;     // pushes SSE market frames
    private java.util.concurrent.ScheduledExecutorService snapshotScheduler;   // started iff SNAPSHOT_ENABLED
    private java.util.concurrent.ScheduledExecutorService portfolioValuationScheduler;
    private final String startedAt = java.time.Instant.now().toString();
    private WorldTransitionService worldTransitions;
    private TradeController tradeController;
    private OutcomeController outcomeController;

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
            if (cfg.edgarConfigured()) newsProviders.add(new EdgarProvider(cfg));                // SEC filings
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
        server.portfolioBooks = new io.liftandshift.strikebench.paper.PortfolioAccountingService(db, clock, marksSource);
        server.portfolioExports = new io.liftandshift.strikebench.paper.PortfolioExportService(server.portfolioBooks);
        server.simSessions.attachDb(db);
        // Data Center services (need db, which is set above; reuse the constructor-built engine).
        var backfill = new io.liftandshift.strikebench.db.UnderlyingBackfill(market, db, clock);
        server.dataConnectors = new io.liftandshift.strikebench.db.DataConnectorCatalog(cfg, providerBudget);
        server.dataSyncState = new io.liftandshift.strikebench.db.DataSyncState(db, clock);
        server.dataJobs = new io.liftandshift.strikebench.db.DataJobService(db, clock, server.marketEngine,
                snapshots, backfill, universe, cfg, server.dataConnectors);
        server.dataSyncScheduler = new io.liftandshift.strikebench.db.DataSyncScheduler(
                cfg, clock, server.dataSyncState, server.dataJobs);
        server.artifactRetention = new io.liftandshift.strikebench.db.ArtifactRetentionService(db, clock, cfg);
        server.dataCoverage = new io.liftandshift.strikebench.db.DataCoverage(db);
        server.dataReset = new io.liftandshift.strikebench.db.DataResetService(db, accounts);
        server.cboe = cboeRef[0];
        var quoteSnapshots = new io.liftandshift.strikebench.db.MarketSnapshotStore(db);
        market.setQuoteSnapshotStore(quoteSnapshots);
        server.marketEngine.setSnapshotStore(quoteSnapshots);
        server.datasets = datasetSvc;
        server.pathEnsembles = new io.liftandshift.strikebench.sim.PathEnsembleService(market, clock);
        server.simEngine = new io.liftandshift.strikebench.sim.SimulationEngine(
                market, datasetSvc, db, clock, server.pathEnsembles);
        server.worldTransitions = new WorldTransitionService(cfg, clock, db, datasetSvc, market,
                server.simSessions, server.events,
                (world, owner) -> server.universeViewFor(worldParam(world), owner),
                server.startedAt);
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
        PortfolioController portfolioController = new PortfolioController(db, clock, portfolioBooks,
                portfolioExports, positions, trades, this::ownerId, this::currentAccount);
        tradeController = new TradeController(cfg, clock, db, market, eventCalendar, audit,
                trades, positions, evaluations, snapshots, auth, this::currentAccount,
                this::ownerId, this::activeWorld, this::analysisCtx, this::requireAdmin);
        outcomeController = new OutcomeController(cfg, clock, market, simEngine, pathEnsembles,
                this::activeWorld, this::ownerId, this::analysisCtx, this::decisionEvaluationResult);
        DataController dataController = new DataController(cfg, clock, db, market, marketEngine,
                universe, dataJobs, dataCoverage, dataReset, dataConnectors, dataSyncState,
                datasets, cboe, simSessions, worldTransitions, audit, this::ownerId,
                this::activeWorld, this::isAdmin, this::requireAdmin,
                this::invalidateHistoricalViews, outcomeController::generateDataset);
        ResearchController researchController = new ResearchController(db, clock, market,
                evaluations, this::ownerId, this::activeWorld, this::analysisCtx,
                this::research, this::welcomeTeachingExample, this::researchScout,
                this::researchIntentLadder, this::opportunities, this::optimize);
        BrokerController brokerController = new BrokerController(broker);
        WorldController worldController = new WorldController(cfg, clock, db, market, marketEngine,
                simSessions, accounts, positions, trades, auth, events, worldTransitions,
                planRehearsals, planSvc, this::ownerId, this::activeWorld);
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
                    // index.html and build-stamped assets may be revalidated. The stamp changes on
                    // every build, so a browser cannot pair JavaScript or CSS from different jars.
                    sf.headers = Map.of("Cache-Control", "public, max-age=0, must-revalidate");
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
                    ctx.json(new ApiResponses.ErrorBody("rate limited",
                            "too many requests from this address — slow down and retry"));
                    ctx.skipRemainingHandlers();
                }
            });
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

            CoreRoutes.register(c, new CoreRoutes.Handlers(
                    this::metrics, this::status, this::config, this::health,
                    ctx -> ctx.json(universeViewFor(worldParam(activeWorld(ctx)), ownerId(ctx))),
                    this::universeSelect, this::quotesBatch, this::sparklines,
                    ctx -> ctx.json(marketEngine.status()), this::marketStream, this::eventStream,
                    this::workspaceGet, this::workspacePut, this::account, this::accountReset));

            // ---- Plans: server-owned journey facts; workspace JSON keeps presentation only ----
            PlanRoutes.register(c, new PlanRoutes.Handlers(
                    this::plansList, this::planCreate, this::plansPortfolio, this::planGet,
                    this::planContextPut, this::planIntentPut, this::planStagePut, this::planOpenPut,
                    this::planArchive, this::planDelete, this::planEvidenceLatest,
                    this::planEvidenceStudy, this::planStrategyLatest, this::planStrategyRun,
                    this::planStrategyFit, this::planStrategyCustom, this::planStrategySelect,
                    this::planStrategySelectionDelete, this::planScoutLatest, this::planScoutRun,
                    this::planScoutSpawn, this::planOutcomesLatest, this::planEnsembleRun,
                    this::planOutcomeRun, this::planOutcomeCompare, this::planBacktestRun,
                    this::planBacktestGet, this::planRehearsalsList, this::planRehearsalCreate,
                    this::planDecisionLatest, this::planDecisionPreview, this::planDecisionTrade,
                    this::planDecisionCash, this::planManageGet, this::planManageRefresh,
                    this::planManageUnwind, this::planManageSettle, this::planManageRoll,
                    this::planManageVoid, this::planManageReview));

            dataController.register(c);

            researchController.register(c);

            outcomeController.register(c);

            // ---- The simulated market (Block S): per-user runtime switch, never a boot flag ----
            worldController.register(c);

            tradeController.register(c);

            portfolioController.register(c);

            // Historical replays are Plan-owned. The report id remains readable so a Plan can
            // restore its full normalized summary plus the existing detailed replay artifact.

            brokerController.register(c);

            c.routes.exception(io.liftandshift.strikebench.auth.UnauthorizedException.class, (e, ctx) ->
                    ctx.status(401).json(new ApiResponses.AuthErrorBody("auth_required",
                            String.valueOf(e.getMessage()), "/auth/login")));
            c.routes.exception(TradeRejectedException.class, (e, ctx) ->
                    ctx.status(422).json(new ApiResponses.TradeRejectedBody("trade_rejected",
                            e.getMessage(), e.reasons())));
            c.routes.exception(PlanMarketMismatchException.class, (e, ctx) ->
                    ctx.status(409).json(new ApiResponses.PlanMarketMismatchBody(
                            "plan_market_mismatch", e.getMessage(), e.marketKind, e.targetWorld)));
            c.routes.exception(IllegalArgumentException.class, (e, ctx) ->
                    ctx.status(400).json(new ApiResponses.ErrorBody(
                            "bad_request", String.valueOf(e.getMessage()))));
            c.routes.exception(java.time.format.DateTimeParseException.class, (e, ctx) ->
                    ctx.status(400).json(new ApiResponses.ErrorBody(
                            "bad_request", "Invalid date: " + e.getParsedString())));
            c.routes.exception(com.fasterxml.jackson.core.JacksonException.class, (e, ctx) ->
                    ctx.status(400).json(new ApiResponses.ErrorBody("bad_request",
                            "Malformed request body (expected JSON matching this endpoint's schema)")));
            c.routes.exception(io.liftandshift.strikebench.util.ResourceNotFoundException.class, (e, ctx) ->
                    ctx.status(404).json(new ApiResponses.ErrorBody(
                            "not_found", String.valueOf(e.getMessage()))));
            c.routes.exception(io.liftandshift.strikebench.util.DataUnavailableException.class, (e, ctx) ->
                    ctx.status(422).json(new ApiResponses.ErrorBody(
                            "data_unavailable", String.valueOf(e.getMessage()))));
            c.routes.exception(IllegalStateException.class, (e, ctx) ->
                    ctx.status(409).json(new ApiResponses.ErrorBody(
                            "conflict", String.valueOf(e.getMessage()))));
            c.routes.exception(Exception.class, (e, ctx) -> {
                apiErrors.incrementAndGet();
                boolean changed = !jarChangedHint().isEmpty();
                log.error("Request failed on {} {}{}", ctx.method(), ctx.path(),
                        changed ? " because the running build changed; restart StrikeBench" : "");
                log.debug("Request failure detail on " + ctx.method() + " " + ctx.path(), e);
                String detail = changed
                        ? "StrikeBench changed while it was running. Restart it and try again."
                        : "The request failed unexpectedly. Retry it, then check Data health if the problem persists.";
                ctx.status(500).json(new ApiResponses.ErrorBody("internal", detail));
            });
            c.routes.error(404, ctx -> {
                if (ctx.path().startsWith("/api") && ctx.attribute("apiErrorWritten") == null) {
                    ctx.json(new ApiResponses.ErrorBody("not_found", ctx.path()));
                }
            });
        }).start();
        warmUpErrorPipeline(app.port());
        startSnapshotScheduler();
        startPortfolioValuationScheduler();
        streamScheduler = java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "market-stream"); t.setDaemon(true); return t;
        });
        if (dataJobs != null) dataJobs.reconcileOnBoot(); // fail orphaned jobs from a prior run so they can retry
        if (marketEngine != null) marketEngine.start(); // warm the universe + background refresh
        if (dataSyncScheduler != null) dataSyncScheduler.start();
        if (artifactRetention != null) artifactRetention.start();
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

    private void startPortfolioValuationScheduler() {
        if (!cfg.portfolioNavEnabled()) return;
        portfolioValuationScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "portfolio-valuation-scheduler");
            t.setDaemon(true);
            return t;
        });
        long initial = Math.max(0, cfg.portfolioNavInitialDelaySeconds());
        long period = Math.max(1, cfg.portfolioNavIntervalMinutes());
        portfolioValuationScheduler.scheduleWithFixedDelay(() -> {
            try {
                var run = portfolioBooks.recordActiveCalculatedValuations();
                if (run.failed() > 0) {
                    log.warn("Some tracked account values were unavailable; the next scheduled pass will retry");
                }
            } catch (RuntimeException e) {
                log.warn("Scheduled tracked-account valuation did not complete; the next run will retry");
                log.debug("Scheduled tracked-account valuation detail", e);
            }
        }, initial, Math.multiplyExact(period, 60L), java.util.concurrent.TimeUnit.SECONDS);
        log.info("tracked account history on — first pass in {}s, then every {}m", initial, period);
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
        if (artifactRetention != null) artifactRetention.close();
        if (dataSyncScheduler != null) dataSyncScheduler.close();
        if (dataJobs != null) dataJobs.shutdown();
        if (marketEngine != null) marketEngine.stop();
        if (streamScheduler != null) streamScheduler.shutdownNow();
        if (snapshotScheduler != null) snapshotScheduler.shutdownNow();
        if (portfolioValuationScheduler != null) portfolioValuationScheduler.shutdownNow();
        if (app != null) app.stop();
        if (db != null) db.close();
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

    /** The canonical persistence owner id for the current user. */
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
        return worldTransitions.active(owner);
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
        return io.liftandshift.strikebench.util.OwnerScope.id(auth.currentUserId(ctx));
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

    // ---- Status / config ----

    private void status(Context ctx) {
        try {
            ctx.json(new ApiResponses.Status<>(true, Instant.now(clock).toString(),
                    cfg.fixturesOnly(), market.status(), null));
        } catch (Exception e) {
            log.warn("Market-data status is temporarily unavailable");
            log.debug("Market-data status failure detail", e);
            ctx.json(new ApiResponses.Status<>(false, null, null, null,
                    "Market-data status is temporarily unavailable"));
        }
    }

    private void config(Context ctx) {
        // Scenario mode is PERSONAL: the signal reflects the CALLER's active dataset.
        String active = datasets == null ? io.liftandshift.strikebench.db.DatasetService.OBSERVED : datasets.activeId(ownerId(ctx));
        String world = activeWorld(ctx);
        String lane = !io.liftandshift.strikebench.db.DatasetService.OBSERVED.equals(active)
                && "observed".equals(world) ? "SCENARIO"
                : io.liftandshift.strikebench.market.MarketLane.of(world, cfg.fixturesOnly()).name();
        ctx.json(new ApiResponses.Config<>(cfg.port(), cfg.fixturesOnly(),
                io.liftandshift.strikebench.market.MarketHours.isRegularSession(clock.instant()), auth.enabled(),
                cfg.feePerContractCents(), cfg.feePerOrderCents(), cfg.defaultStartingCashCents(),
                new ApiResponses.Brand(cfg.brandName(), cfg.brandTagline()),
                io.liftandshift.strikebench.model.BroadBasedIndexOptions.AUTOMATIC_SYMBOLS,
                RecommendationEngine.DISCLAIMER, active,
                datasets == null ? active : datasets.nameOf(active),
                !io.liftandshift.strikebench.db.DatasetService.OBSERVED.equals(active), world, lane));
    }

    /**
     * Liveness + staleness beacon. jarChangedSinceBoot=true means the application jar was
     * rewritten under this running server — the classic "some screens fail to load" cause —
     * and the UI turns it into a restart banner instead of a mystery. Never 500s.
     */
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

    /** Per-IP token bucket with exact bounded LRU eviction; one attacker never resets every client. */
    static final class IpThrottle {
        private final int burst; private final double perSecond;
        private final int maximumEntries;
        private final java.util.LinkedHashMap<String, Bucket> buckets =
                new java.util.LinkedHashMap<>(128, 0.75f, true);
        IpThrottle(int burst, double perSecond) { this(burst, perSecond, 10_000); }
        IpThrottle(int burst, double perSecond, int maximumEntries) {
            this.burst = burst;
            this.perSecond = perSecond;
            this.maximumEntries = Math.max(1, maximumEntries);
        }
        boolean tryAcquire(String ip) {
            if (ip == null || ip.isBlank()) return true;
            synchronized (buckets) {
                Bucket b = buckets.get(ip);
                if (b == null) {
                    while (buckets.size() >= maximumEntries) {
                        var eldest = buckets.entrySet().iterator();
                        if (!eldest.hasNext()) break;
                        eldest.next();
                        eldest.remove();
                    }
                    b = new Bucket(burst);
                    buckets.put(ip, b);
                }
                long now = System.nanoTime();
                b.tokens = Math.min(burst, b.tokens + (now - b.lastNs) / 1e9 * perSecond);
                b.lastNs = now;
                if (b.tokens < 1) return false;
                b.tokens -= 1;
                return true;
            }
        }
        long activeBuckets() { synchronized (buckets) { return buckets.size(); } }
        static final class Bucket { double tokens; long lastNs = System.nanoTime(); Bucket(int t) { tokens = t; } }
    }

    /** Operational counters — request volume, error volume, throttle hits, engine health. */
    private void metrics(io.javalin.http.Context ctx) {
        Object engineStatus;
        try {
            engineStatus = marketEngine.status();
        } catch (Exception e) {
            log.debug("Market-engine metrics failure detail", e);
            engineStatus = new ApiResponses.ErrorOnly("Market engine status is temporarily unavailable");
        }
        ctx.json(new ApiResponses.Metrics<>(apiRequests.get(), latencyPercentiles(), apiErrors.get(),
                apiThrottled.get(), !cfg.fixturesOnly(), engineStatus));
    }

    private void health(Context ctx) {
        boolean changed;
        try {
            changed = !jarChangedHint().isEmpty();
        } catch (RuntimeException e) {
            changed = false;
        }
        ctx.json(new ApiResponses.Health(true, startedAt, changed));
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
            int requested = syms.size();
            int limit = io.liftandshift.strikebench.market.sim.SimulationSessions.MAX_SYMBOLS;
            List<String> bounded = syms.stream().limit(limit).toList();
            List<Map<String, Object>> rows = new ArrayList<>();
            // F7: world quotes are LOCAL memory — serve the whole world (<=120), not a 40-cap
            // that made the tape and SSE disagree about which symbols exist.
            for (String sym : bounded) {
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
            ctx.json(new ApiResponses.WorldQuotes<>(rows, requested, bounded.size(), requested > limit,
                    limit, world, market.lane(world).name()));
            return;
        }
        List<String> symbols = raw == null || raw.isBlank()
                ? universe.active().symbols()
                : java.util.Arrays.stream(raw.split(",")).map(s -> s.trim().toUpperCase(Locale.ROOT))
                    .filter(s -> !s.isBlank()).distinct().toList();
        int requested = symbols.size();
        int limit = io.liftandshift.strikebench.market.sim.SimulationSessions.MAX_SYMBOLS;
        if (symbols.size() > limit) symbols = symbols.subList(0, limit);
        // Served from the in-memory engine: warm symbols answer instantly, cold ones are fetched in
        // parallel, and stale ones refresh in the background — no per-symbol sequential download.
        List<Map<String, Object>> out = new ArrayList<>();
        for (var snap : marketEngine.quotes(symbols)) {
            out.add(io.liftandshift.strikebench.market.MarketDataEngine.toRow(snap));
        }
        ctx.json(new ApiResponses.Quotes<>(out, requested, symbols.size(), requested > limit,
                limit, cfg.fixturesOnly() ? "DEMO" : "OBSERVED"));
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
        if (workspaceSvc == null) { ctx.json(new ApiResponses.Revision(0)); return; }
        var ws = workspaceSvc.get(ownerId(ctx));
        if (ws.isEmpty()) { ctx.json(new ApiResponses.Revision(0)); return; }
        ctx.json(new ApiResponses.Workspace<>(ws.get().rev(), ws.get().updatedAt(),
                Json.parse(ws.get().stateJson())));
    }

    /** Body IS the state object (client-owned shape). Validated as JSON, size-capped, last-write-wins. */
    private void workspacePut(Context ctx) {
        if (workspaceSvc == null) {
            ctx.status(503).json(new ApiResponses.ErrorOnly("workspace store unavailable"));
            return;
        }
        String body = ctx.body();
        if (body == null || body.isBlank()) throw new IllegalArgumentException("state body required");
        com.fasterxml.jackson.databind.JsonNode node;
        try { node = Json.parse(body); } catch (Exception e) { throw new IllegalArgumentException("state must be JSON"); }
        if (!node.isObject()) throw new IllegalArgumentException("state must be a JSON object");
        long rev = workspaceSvc.put(ownerId(ctx), body);
        ctx.json(new ApiResponses.SavedRevision(true, rev));
    }

    // ---- Plan-centered journey ----

    private io.liftandshift.strikebench.plan.Plan.MarketKind activePlanMarket(Context ctx) {
        String world = activeWorld(ctx);
        if ("demo".equals(world)) return io.liftandshift.strikebench.plan.Plan.MarketKind.DEMO;
        if ("observed".equals(world)) return io.liftandshift.strikebench.plan.Plan.MarketKind.OBSERVED;
        return io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED;
    }

    private record PlanSymbolEligibility(boolean eligible, String detail) {}

    private static final class PlanMarketMismatchException extends IllegalStateException {
        private final String marketKind;
        private final String targetWorld;

        private PlanMarketMismatchException(String marketKind, String targetWorld, String detail) {
            super(detail);
            this.marketKind = marketKind;
            this.targetWorld = targetWorld;
        }
    }

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
        ctx.json(new ApiResponses.Plans<>(planSvc.list(ownerId(ctx), market, world, openOnly),
                activePlanMarket(ctx).name(), activeWorld(ctx)));
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
                ctx.status(422).json(new ApiResponses.PlanSymbolError(
                        "plan_symbol_unavailable", eligibility.detail(), market.name()));
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

    private void planDelete(Context ctx) {
        String rawVersion = ctx.queryParam("expectedVersion");
        if (rawVersion == null || rawVersion.isBlank()) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        long expectedVersion;
        try { expectedVersion = Long.parseLong(rawVersion); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("expectedVersion must be an integer"); }
        String id = ctx.pathParam("id");
        planSvc.deleteDraft(ownerId(ctx), id, expectedVersion);
        ctx.json(new ApiResponses.Deleted(id));
    }

    private void requireActivePlanMarket(Context ctx, io.liftandshift.strikebench.plan.Plan.View plan) {
        var active = activePlanMarket(ctx);
        String activeWorld = active == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED
                ? activeWorld(ctx) : null;
        if (plan.marketKind() != active || !java.util.Objects.equals(plan.worldId(), activeWorld)) {
            String label = switch (plan.marketKind()) {
                case DEMO -> "Demo market";
                case OBSERVED -> "Observed market";
                case SIMULATED -> "simulated market session";
            };
            String targetWorld = plan.marketKind() == io.liftandshift.strikebench.plan.Plan.MarketKind.SIMULATED
                    ? plan.worldId() : plan.marketKind().name().toLowerCase(Locale.ROOT);
            throw new PlanMarketMismatchException(plan.marketKind().name(), targetWorld,
                    "This Plan belongs to the " + label + ". Open that market before running this analysis.");
        }
    }

    private void planEvidenceLatest(Context ctx) {
        var saved = planEvidence.latest(ownerId(ctx), ctx.pathParam("id"), analysisCtx(ctx));
        ctx.json(new ApiResponses.Evidence<>(saved));
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
        ctx.json(new ApiResponses.StrategyState<>(saved,
                planStrategy.selectedCandidate(ownerId(ctx), ctx.pathParam("id"))));
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
        ctx.json(new ApiResponses.PlanStrategy<>(planSvc.get(ownerId(ctx), plan.id()), saved));
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
        JsonNode candidates = ranked.path("candidates");
        ctx.json(new ApiResponses.PlanStrategyFit<>(plan, ranked,
                candidates.isArray() && !candidates.isEmpty() ? candidates.get(0) : null));
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
        ctx.json(new ApiResponses.PlanSelection<>(selected,
                planSvc.get(ownerId(ctx), ctx.pathParam("id"))));
    }

    private void planStrategySelectionDelete(Context ctx) {
        String rawVersion = ctx.queryParam("expectedVersion");
        if (rawVersion == null || rawVersion.isBlank()) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        long expectedVersion;
        try { expectedVersion = Long.parseLong(rawVersion); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("expectedVersion must be an integer"); }
        String id = ctx.pathParam("id");
        var selection = planStrategy.clearSelection(ownerId(ctx), id, expectedVersion);
        ctx.json(new ApiResponses.PlanSelection<>(selection, planSvc.get(ownerId(ctx), id)));
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
        TradeService.OpenRequest request = tradeController.toOpenRequest(exactBody, account);
        var preview = trades.preview(request);
        Candidate candidate = TradeController.exactPreviewCandidate(request, preview);
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
        ctx.json(new ApiResponses.PlanStrategyPreview<>(
                planSvc.get(ownerId(ctx), plan.id()), saved, preview));
    }

    private void planScoutLatest(Context ctx) {
        String scope = java.util.Objects.requireNonNullElse(ctx.queryParam("scope"), "PEERS");
        var saved = planStrategy.latestScout(ownerId(ctx), ctx.pathParam("id"), scope);
        ctx.json(new ApiResponses.Scout<>(saved));
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
        String focusedThesis = "HEDGES".equals(scope) ? null : plan.context().thesis();
        var request = new AutoRecommender.AutoRequest(scanUniverse, List.of(planHorizon(plan.context().horizonDays())),
                controls == null ? 4 : controls.maxPicks(), null, null, null, null,
                plan.context().riskMode(), allow0, List.of(requestedIntent), null, focusedThesis);
        AutoRecommender.AutoResult raw = auto.run(request, account.buyingPowerCents(), held, world);
        ObjectNode result = flattenPlanScout(plan, scope, raw);
        var saved = planStrategy.saveScout(ownerId(ctx), plan, scope, Json.MAPPER.valueToTree(request), result);
        ctx.json(new ApiResponses.PlanScout<>(plan, saved));
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
        boolean anyEconomicAssessment = false, anyComparableAssessment = false;
        boolean anyRealizedVolLane = false, needsDailyHistory = false;
        List<String> collectedNotes = new ArrayList<>();
        String wantedThesis = plan.context().thesis() == null ? null : plan.context().thesis().toUpperCase(Locale.ROOT);
        for (AutoRecommender.Pick pick : raw.picks()) {
            for (AutoRecommender.HorizonIdeas horizon : pick.horizons()) {
                for (String note : horizon.notes()) if (!collectedNotes.contains(note)) collectedNotes.add(note);
                for (AutoRecommender.ScoredCandidate scored : horizon.candidates()) {
                    ObjectNode candidate = Json.MAPPER.valueToTree(scored.candidate());
                    candidate.put("symbol", pick.symbol());
                    candidate.put("scoutThesis", !"HEDGES".equals(scope) && wantedThesis != null
                            ? wantedThesis : pick.signals().thesis());
                    candidate.put("scoutScope", scope); candidate.put("scoutHorizon", horizon.horizon());
                    candidate.put("opportunityScore", pick.opportunityScore());
                    candidate.put("rankingScore", scored.rankingScore());
                    if (scored.targetFit() != null) candidate.put("targetFit", scored.targetFit());
                    if (scored.decisionScore() != null) candidate.put("decisionScore", scored.decisionScore());
                    if (scored.economics() != null) {
                        anyEconomicAssessment = true;
                        if (!"MECHANICALLY_INELIGIBLE".equals(scored.economics().placement())) {
                            anyComparableAssessment = true;
                        }
                        if (scored.economics().realizedVolEvAfterCostsCents() != null) anyRealizedVolLane = true;
                        if (scored.economics().needsDailyHistory()) needsDailyHistory = true;
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
        result.put("economicReadiness", anyEconomicAssessment && !anyComparableAssessment
                ? "MECHANICALLY_BLOCKED"
                : anyEconomicAssessment && !anyRealizedVolLane && needsDailyHistory
                    ? "NEEDS_DAILY_HISTORY" : "READY");
        result.put("economicMessage", candidates.isEmpty()
                ? "No related symbol matched this Plan's evidence and mechanical screens."
                : anyEconomicAssessment && !anyComparableAssessment
                    ? "The related symbols produced structures, but none passed the mechanical and account checks required for an economic comparison."
                : anyEconomicAssessment && !anyRealizedVolLane && needsDailyHistory
                    ? "These symbols can be compared mechanically and under market-implied pricing, but daily history is insufficient for a realized-volatility edge verdict."
                : "Related symbols remain separate Plans; compare their evidence, tail risk, and capital use before treating one as an alternative.");
        ArrayNode notes = result.putArray("notes");
        collectedNotes.forEach(notes::add);
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
        ctx.json(new ApiResponses.ScoutSpawn<>(origin, child, role));
    }

    private void planOutcomesLatest(Context ctx) {
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        ObjectNode out = planOutcomes.latest(ownerId(ctx), plan, analysisCtx(ctx));
        JsonNode selected = planStrategy.selectedCandidate(ownerId(ctx), plan.id());
        ctx.json(new ApiResponses.PlanOutcomesLatest<>(out.path("outcomes"), out.path("comparisons"),
                out.path("backtests"), selected));
    }

    /** Evidence owns path generation; Outcomes later values the exact selected package on this artifact. */
    private void planEnsembleRun(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanEnsembleRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        var spec = planScenarioSpec(plan, body.over());
        var world = worldParam(activeWorld(ctx));
        var marketVol = outcomeController.marketVol(plan.symbol(), world, spec.horizonDays());
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
        ctx.json(new ApiResponses.PlanEnsemble<>(plan,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis()), preview));
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
            var evaluated = outcomeController.evaluateOutcomes(ctx, request);
            JsonNode result = Json.MAPPER.valueToTree(evaluated.result());
            var saved = planOutcomes.saveRiskNeutral(ownerId(ctx), plan, body.expectedVersion(),
                    candidate.path("id").asText(), result, input, evaluated.interpretation(), analysisCtx(ctx));
            ctx.json(new ApiResponses.PlanOutcome<>(plan, saved));
            return;
        }
        io.liftandshift.strikebench.sim.PathEnsembleService.Basis basis;
        try { basis = io.liftandshift.strikebench.sim.PathEnsembleService.Basis.valueOf(basisName); }
        catch (Exception e) { throw new IllegalArgumentException("basis must be RISK_NEUTRAL, PARAMETRIC, HISTORICAL_ANALOGS, or CONDITIONAL_BOOTSTRAP"); }
        var stored = resolvePlanEnsemble(ctx, plan, body, basis);
        var legs = outcomeController.toSimLegs(ctx, position.legs());
        var simRequest = new OutcomeController.StrategySimRequest(plan.symbol(), legs, position.qty(),
                stored.ensemble().spec(), stored.iv(), basis, null, position.entryCostCents(),
                outcomeController.contractExpirations(position.legs()));
        JsonNode result = Json.MAPPER.valueToTree(
                outcomeController.simStrategyResult(ctx, simRequest, stored.ensemble()));
        String interpretation = switch (basis) {
            case PARAMETRIC -> "The exact selected package is repriced on the same stored model ensemble shown in Evidence.";
            case HISTORICAL_ANALOGS -> "The exact selected package is repriced over the Plan's stored matching historical occurrences.";
            case CONDITIONAL_BOOTSTRAP -> "The exact selected package is repriced over whole-path resamples of the Plan's stored analog sample.";
        };
        var saved = planOutcomes.savePathOutcome(ownerId(ctx), plan, body.expectedVersion(),
                candidate.path("id").asText(), stored, result, input, interpretation);
        ctx.json(new ApiResponses.PlanOutcomeWithEnsemble<>(plan, saved,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis())));
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
                var simLegs = outcomeController.toSimLegs(ctx, position.legs());
                String problem = outcomeController.validateSimLegs(simLegs);
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
        ctx.json(new ApiResponses.PlanComparison<>(plan, saved,
                new ApiResponses.EnsembleRef(stored.id(), stored.fingerprint(), stored.basis())));
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
                    body.stopFraction(), body.rollDte(), body.startingCashCents()), analysisCtx(ctx), ownerId(ctx));
        } else if ("single".equals(engineKind)) {
            report = backtester.run(new Backtester.BacktestRequest(plan.symbol(), family, body.from(), body.to(),
                    body.targetDte() == null ? plan.context().horizonDays() : body.targetDte(), body.entryEveryDays(),
                    body.qty(), body.slippagePct(), body.startingCashCents()), analysisCtx(ctx), ownerId(ctx));
        } else throw new IllegalArgumentException("engine must be single or portfolio");
        JsonNode reportJson = Json.MAPPER.valueToTree(report);
        var saved = planOutcomes.saveBacktest(ownerId(ctx), plan, body.expectedVersion(),
                candidate.path("id").asText(), engineKind, reportJson, Json.MAPPER.valueToTree(body), analysisCtx(ctx));
        ctx.json(new ApiResponses.PlanBacktest<>(plan, saved, report));
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
        ctx.status(201).json(new ApiResponses.PlanRehearsal<>(
                created, planSvc.get(ownerId(ctx), plan.id())));
    }

    private void planDecisionLatest(Context ctx) {
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        ctx.json(new ApiResponses.PlanDecisionState<>(plan,
                planStrategy.selectedCandidate(ownerId(ctx), plan.id()),
                planDecisions.latest(ownerId(ctx), plan.id())));
    }

    private void planDecisionPreview(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body);
        ApiResponses.TradePreviewResponse payload = tradeController.previewPayload(ctx, order);
        var first = payload.preview();
        if (order.proposedNetCents() == null) {
            body = new PlanDecisionRequest(body.expectedVersion(), body.qty(), first.entryNetPremiumCents(),
                    body.feesOverrideCents(), body.acknowledgedRisks(), body.ackToken(), body.note());
            order = planDecisionOrder(plan, candidate, body);
            payload = tradeController.previewPayload(ctx, order);
        }
        ctx.json(new ApiResponses.PlanDecisionPreview<>(payload.preview(), payload.economics(),
                payload.guardrails(), payload.requiredAcks(), payload.ackToken(), payload.accountFit(),
                plan, candidate, new ApiResponses.OrderSummary(order.qty(), order.proposedNetCents(),
                order.feesOverrideCents() == null ? 0L : order.feesOverrideCents())));
    }

    private void planDecisionTrade(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body);
        ApiResponses.TradePreviewResponse payload = tradeController.previewPayload(ctx, order);
        var decisionInput = planDecisionInput(ctx, plan, body, candidate, payload);
        var prepared = planDecisions.prepareTrade(decisionInput);
        TradeController.CreatedTrade created = tradeController.execute(ctx, order, prepared.hook());
        var updated = planSvc.get(ownerId(ctx), plan.id());
        ctx.status(201).json(new ApiResponses.PlanPlacedTrade<>(updated, TradeView.of(created.trade()),
                planDecisions.latest(ownerId(ctx), plan.id()), created.verdict().warnings()));
    }

    private void planDecisionCash(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanDecisionRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan);
        requirePlanVersion(plan, body.expectedVersion());
        ObjectNode candidate = selectedPlanCandidate(ctx, plan);
        TradeOpenRequest order = planDecisionOrder(plan, candidate, body);
        ApiResponses.TradePreviewResponse payload = tradeController.previewPayload(ctx, order);
        ObjectNode decision = planDecisions.chooseCash(planDecisionInput(ctx, plan, body, candidate, payload));
        var updated = planSvc.get(ownerId(ctx), plan.id());
        ctx.status(201).json(new ApiResponses.PlanDecision<>(updated, decision));
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
                plan.context().thesis(), (plan.context().horizonDays() == null
                        ? io.liftandshift.strikebench.model.Horizon.MONTH.tradingSessions()
                        : plan.context().horizonDays()) + "d",
                plan.context().riskMode(),
                plan.intent(), candidate.path("usesHeldShares").asBoolean(false),
                candidate.path("recommendationId").asText(null), body.proposedNetCents(), body.feesOverrideCents(),
                "PLAN", null, null, null, false, body.acknowledgedRisks(), body.ackToken());
    }

    private io.liftandshift.strikebench.plan.PlanDecisionService.Input planDecisionInput(
            Context ctx, io.liftandshift.strikebench.plan.Plan.View plan, PlanDecisionRequest body,
            ObjectNode candidate, ApiResponses.TradePreviewResponse payload) {
        return new io.liftandshift.strikebench.plan.PlanDecisionService.Input(ownerId(ctx), plan,
                body.expectedVersion(), candidate.path("id").asText(), currentAccount(ctx),
                payload.preview(), payload.economics(),
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
        ctx.json(new ApiResponses.PlanWorkspace<>(plan,
                planDecisions.latest(ownerId(ctx), plan.id()), management,
                tradeId == null ? null : tradeController.detailData(tradeId)));
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
        ctx.json(new ApiResponses.PlanRows<>(rows, marketKind.name()));
    }

    private void planManageRefresh(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanManageRequest.class));
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan); requirePlanVersion(plan, body.expectedVersion());
        String tradeId = requirePlanActiveTrade(ctx, plan.id());
        var mark = trades.refresh(tradeId);
        planManagement.recordMark(ownerId(ctx), plan.id(), body.expectedVersion(), tradeId, mark);
        ctx.json(new ApiResponses.PlanMark<>(planSvc.get(ownerId(ctx), plan.id()), mark,
                planManagement.latest(ownerId(ctx), plan.id())));
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
        tradeController.resolveRecommendation(tradeId,
                "SETTLE".equals(kind) ? "SETTLED" : "CLOSED",
                TradeController.decisionPnl(result.trade(), result.realizedPnlCents()));
        ctx.json(new ApiResponses.PlanClosedTrade<>(planSvc.get(ownerId(ctx), plan.id()),
                TradeView.of(result.trade()), result.realizedPnlCents(),
                planManagement.latest(ownerId(ctx), plan.id()),
                roll ? rolledPosition(result.trade(), worldParam(activeWorld(ctx))) : null));
    }

    private void planManageVoid(Context ctx) {
        var body = requireBody(bodyOrNull(ctx, PlanManageRequest.class));
        if (!Boolean.TRUE.equals(body.confirm())) throw new IllegalArgumentException("confirm=true is required");
        var plan = planSvc.get(ownerId(ctx), ctx.pathParam("id"));
        requireActivePlanMarket(ctx, plan); requirePlanVersion(plan, body.expectedVersion());
        String tradeId = requirePlanActiveTrade(ctx, plan.id());
        var hook = planManagement.lifecycleHook(ownerId(ctx), plan.id(), body.expectedVersion(), "VOID", false);
        TradeRecord deleted = trades.delete(tradeId, true, hook);
        ctx.json(new ApiResponses.PlanTrade<>(planSvc.get(ownerId(ctx), plan.id()),
                TradeView.of(deleted), planManagement.latest(ownerId(ctx), plan.id())));
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
        ctx.json(new ApiResponses.PlanManagement<>(planSvc.get(ownerId(ctx), plan.id()), management));
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
            var marketVol = outcomeController.marketVol(plan.symbol(), world, spec.horizonDays());
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
        var marketVol = outcomeController.marketVol(plan.symbol(), world, ensemble.spec().horizonDays());
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
        int days = plan.context().horizonDays() == null
                ? io.liftandshift.strikebench.model.Horizon.MONTH.tradingSessions()
                : plan.context().horizonDays();
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
        return io.liftandshift.strikebench.model.Horizon.fromTradingSessions(days).key();
    }


    // ---- Account ----

    private void account(Context ctx) {
        Account acct = currentAccount(ctx);
        ctx.json(new ApiResponses.AccountLedger<>(
                accountView(acct), accounts.ledger(acct.id(), 0, 20)));
    }

    public record ResetRequest(Long startingCashCents, Boolean confirm, Boolean force) {}

    private void accountReset(Context ctx) {
        ResetRequest req = requireBody(bodyOrNull(ctx, ResetRequest.class));
        long cash = req.startingCashCents() == null ? cfg.defaultStartingCashCents() : req.startingCashCents();
        // Scope the reset to the CALLER's account so a user can never reset someone else's.
        Account acct = accounts.resetAccount(currentAccount(ctx).id(), cash,
                Boolean.TRUE.equals(req.confirm()), Boolean.TRUE.equals(req.force()));
        ctx.json(new ApiResponses.Account<>(accountView(acct)));
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
            ctx.status(404).json(new ApiResponses.ErrorBody(
                    "unknown_symbol", "No data for " + symbol));
            return;
        }
        Quote q = quote.get();
        var lane = io.liftandshift.strikebench.market.MarketLane.of(world, cfg.fixturesOnly(), analysisCtx(ctx));
        if (!q.evidence().usableIn(lane == io.liftandshift.strikebench.market.MarketLane.SCENARIO
                ? io.liftandshift.strikebench.market.MarketLane.OBSERVED : lane)) {
            ctx.attribute("apiErrorWritten", true);
            ctx.status(409).json(new ApiResponses.ErrorBody("market_lane_mismatch",
                    "The " + lane + " workflow cannot use " + q.evidence().provenance()
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
                List<ApiResponses.Benchmark<BigDecimal, io.liftandshift.strikebench.model.DataEvidence>> b =
                        new ArrayList<>();
                for (String bench : List.of("SPY", "QQQ")) {
                    if (bench.equals(symbol)) continue;
                    // Benchmarks come from the SAME world as the symbol — inside a simulated
                    // session there is no observed SPY, and mixing markets would be a quiet lie.
                    market.quote(bench, world).filter(x -> x.evidence().usableIn(
                            lane == io.liftandshift.strikebench.market.MarketLane.SCENARIO
                                    ? io.liftandshift.strikebench.market.MarketLane.OBSERVED : lane)).ifPresent(x -> {
                        b.add(new ApiResponses.Benchmark<>(x.symbol(), x.mark(),
                                x.markFreshness().name(), x.evidence()));
                    });
                }
                return b;
            });

            IvExp ie = ivExpF.get();
            var candleSeries = candlesF.get();
            double hv30 = HistoricalVol.annualized(candleSeries.candles(), 30);
            Double realizedVol30 = Double.isNaN(hv30) ? null : hv30;
            int volatilityHorizonDays = ie.exps().isEmpty() ? 30
                    : Math.max(1, (int) java.time.temporal.ChronoUnit.DAYS.between(today, ie.exps().getFirst()));
            var volatility = evaluations.volatilitySnapshot(symbol, ie.ivAtm(), realizedVol30,
                    volatilityHorizonDays, worldParam(world));
            boolean demoHistory = candleSeries.evidence().provenance()
                    == io.liftandshift.strikebench.model.DataProvenance.DEMO;

            // The event model: an ESTIMATED earnings window from the issuer's SEC filing cadence
            // (never keywords), and an HONESTLY-ABSENT ex-div (no keyless source exists).
            // A simulated world has NO earnings — a real company's calendar attached to a
            // generated market would be a phantom event (review P2).
            ApiResponses.ResearchEvent earningsEstimate;
            if ("demo".equals(world)) {
                earningsEstimate = new ApiResponses.ResearchEvent(false, null, null, null, null,
                        "demo market — its companies and events are fabricated teaching data");
            } else if (!"observed".equals(world)) {
                earningsEstimate = new ApiResponses.ResearchEvent(false, null, null, null, null,
                        "simulated market — no earnings exist in this world");
            } else {
                var next = eventCalendar.nextEarnings(symbol);
                earningsEstimate = next.<ApiResponses.ResearchEvent>map(e ->
                        new ApiResponses.ResearchEvent(null, e.estimated().toString(), e.windowDays(),
                                e.basis(), e.confirmed(), null)).orElseGet(() ->
                        new ApiResponses.ResearchEvent(false, null, null, null, null,
                                "not enough SEC quarterly filings to project a cadence"));
            }
            Map<String, io.liftandshift.strikebench.model.DataEvidence> evidenceInputs = new LinkedHashMap<>();
            evidenceInputs.put("quote", q.evidence());
            evidenceInputs.put("history", candleSeries.isEmpty()
                    ? io.liftandshift.strikebench.model.DataEvidence.missing("daily history")
                    : candleSeries.evidence());
            if (q.optionable()) evidenceInputs.put("options", ie.evidence());
            var evidence = new ApiResponses.EvidenceSummary<>(
                    io.liftandshift.strikebench.model.DataEvidence.aggregate(evidenceInputs.values()),
                    evidenceInputs);
            var planLane = io.liftandshift.strikebench.market.MarketLane.of(world, cfg.fixturesOnly());
            var planEligibility = planSymbolEligibility(symbol, planLane, q, ie.exps(), ie.evidence());
            ctx.json(new ApiResponses.ResearchDetail<>(symbol, q, q.mark(),
                    q.usesPreviousCloseFallback(), lane.name(), q.optionable(), ie.ivAtm(),
                    volatility.ivRankPct() != null, volatility.ivRankPct(), volatility.ivPercentilePct(),
                    volatility.historyDays(), io.liftandshift.strikebench.eval.VolatilityProfiler.MIN_HISTORY,
                    volatility.source(), earningsEstimate,
                    new ApiResponses.ResearchEvent(false, null, null, null, null,
                            "no keyless ex-dividend source \u2014 connect a licensed calendar for confirmed dates"),
                    realizedVol30, candleSeries.candles().size(), HistoricalVol.MIN_OBSERVATIONS,
                    demoHistory, candleSeries.barBasis(), candleSeries.priceBasis(), evidence,
                    ie.exps().stream().map(LocalDate::toString).toList(), planEligibility.eligible(),
                    planEligibility.detail(), benchF.get(), q.markFreshness().name(), today.toString()));
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

    private List<LocalDate> activeExpirations(String symbol, String world) {
        java.time.Instant now = market.simInstant(worldParam(world)).orElse(clock.instant());
        return ResearchController.activeExpirations(market.expirations(symbol, world), now);
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
        ctx.json(new ApiResponses.Sparklines<>(range, out, totalRequested, world));
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
            } catch (io.liftandshift.strikebench.util.ResourceNotFoundException ignored) { /* no position — engine handles it */ }
        }
        // R4 via THE policy (review IC-1): the declared risk-capital line caps the engine's
        // per-trade budget — one translation shared by every recommending surface.
        Long capGov = RiskBudgetPolicy.effectiveMaxLossCents(
                req.maxLossCents(), tradeController.riskCapCents(ctx));
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

    private Object decisionEvaluationResult(Context ctx, RecommendationEngine.Request decision) {
        RecommendationEngine.Result result = resolveAndRecommend(ctx, decision);
        Account acct = currentAccount(ctx);
        String world = activeWorld(ctx);
        boolean inWorld = !"observed".equals(world);
        String uid = auth.currentUserId(ctx);
        String ownerId = io.liftandshift.strikebench.util.OwnerScope.id(uid);
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
                try { iv = outcomeController.atmIv(result.symbol(), baselineWorld); }
                catch (RuntimeException ignored) { }
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
        String ownerId = io.liftandshift.strikebench.util.OwnerScope.id(uid);
        int topN = req.topN() != null ? req.topN() : 8;
        var rcScan = io.liftandshift.strikebench.paper.AccountRiskContext.load(db, ownerId(ctx));
        var result = evaluations.scan(symbols, req.intent(),
                req.thesis() != null ? req.thesis() : "neutral",
                req.horizon() != null ? req.horizon() : "month",
                req.riskMode() != null ? req.riskMode() : "balanced",
                acct.buyingPowerCents(), world != null ? null : ownerId, topN, world, rcScan.riskCapitalCents());
        ctx.json(new ApiResponses.Opportunities<>(result.ranked(), result.notes(), result.scanned()));
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
        String ownerId = io.liftandshift.strikebench.util.OwnerScope.id(uid);
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
        ctx.json(new ApiResponses.Optimization<>(result, scan.scanned(), scan.notes()));
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
            } catch (io.liftandshift.strikebench.util.ResourceNotFoundException ignored) { /* buy-write ladder */ }
        }
        // R4 via THE policy: ladders obey the same declared-capital translation as every surface.
        Long capLad = RiskBudgetPolicy.effectiveMaxLossCents(
                req.maxLossCents(), tradeController.riskCapCents(ctx));
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
            req = new AutoRecommender.AutoRequest(null, null, null, null, null, null, null, null, null, null, null, null);
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
                    req.riskMode(), req.allow0dte(), req.intents(), req.filters(), req.thesisOverride());
        }
        Account acct = currentAccount(ctx);
        // THE policy applies to the scout too (review IC-1): auto and manual recommendations
        // must size under the identical declared-capital cap.
        Long capAuto = RiskBudgetPolicy.effectiveMaxLossCents(
                req.maxLossCents(), tradeController.riskCapCents(ctx));
        if (!java.util.Objects.equals(capAuto, req.maxLossCents())) {
            req = new AutoRecommender.AutoRequest(req.universe(), req.horizons(), req.maxPicks(),
                    req.targetProfitCents(), capAuto, req.maxRiskPctOfAccount(), req.minConfidence(),
                    req.riskMode(), req.allow0dte(), req.intents(), req.filters(), req.thesisOverride());
        }
        // Real holdings feed the EXIT/HEDGE scans and let INCOME write against held shares
        List<AutoRecommender.HoldingInfo> held = positions.list(acct.id()).stream()
                .map(p -> new AutoRecommender.HoldingInfo(p.symbol(),
                        (int) Math.min(Integer.MAX_VALUE, p.freeShares()), p.avgCostCents()))
                .toList();
        ctx.json(auto.run(req, acct.buyingPowerCents(), held, worldParam(world)));
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
