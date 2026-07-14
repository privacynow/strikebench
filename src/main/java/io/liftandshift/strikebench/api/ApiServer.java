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
    private DiscoveryController discoveryController;
    private OutcomeController outcomeController;
    private PlanController planController;

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
        discoveryController = new DiscoveryController(db, market, evaluations, engine, auto,
                positions, universe, simSessions, auth, this::currentAccount, this::ownerId,
                this::activeWorld, tradeController::riskCapCents);
        outcomeController = new OutcomeController(cfg, clock, market, simEngine, pathEnsembles,
                this::activeWorld, this::ownerId, this::analysisCtx, this::decisionEvaluationResult);
        planController = new PlanController(cfg, clock, db, market,
                positions, trades, backtester, auto, evaluations, planSvc, planEvidence,
                planStrategy, planOutcomes, planRehearsals, planDecisions, planManagement,
                pathEnsembles, simEngine, discoveryController, outcomeController, tradeController,
                this::currentAccount, this::ownerId, this::activeWorld, this::analysisCtx);
        DataController dataController = new DataController(cfg, clock, db, market, marketEngine,
                universe, dataJobs, dataCoverage, dataReset, dataConnectors, dataSyncState,
                datasets, cboe, simSessions, worldTransitions, audit, this::ownerId,
                this::activeWorld, this::isAdmin, this::requireAdmin,
                this::invalidateHistoricalViews, outcomeController::generateDataset);
        ResearchController researchController = new ResearchController(db, clock, market,
                evaluations, this::ownerId, this::activeWorld, this::analysisCtx,
                this::research);
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

            planController.register(c);

            dataController.register(c);

            researchController.register(c);

            discoveryController.register(c);

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
            var planEligibility = planController.planSymbolEligibility(
                    symbol, planLane, q, ie.exps(), ie.evidence());
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


    private Object decisionEvaluationResult(Context ctx, RecommendationEngine.Request decision) {
        RecommendationEngine.Result result = discoveryController.resolveAndRecommend(ctx, decision);
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
