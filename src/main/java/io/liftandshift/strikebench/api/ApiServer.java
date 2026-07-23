package io.liftandshift.strikebench.api;
import static io.liftandshift.strikebench.market.MarketLane.worldParam;

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
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountService;
import io.liftandshift.strikebench.paper.AuditLog;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TradeRejectedException;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.recommend.AutoRecommender;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.recommend.SignalEngine;
import io.liftandshift.strikebench.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP surface. All routes are registered inside Javalin.create (Javalin 7 style).
 * Errors are JSON {error, detail}; unsafe trades map to 422; /api/status can never 500.
 */
public final class ApiServer {

    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

    /** The StrikeBench brand mark, served for /favicon.ico (same art as the inline &lt;link rel=icon&gt;). */
    private static final String FAVICON_SVG =
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'>"
            + "<defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'>"
            + "<stop offset='0' stop-color='#2f6bde'/><stop offset='1' stop-color='#7c4fe0'/>"
            + "</linearGradient></defs>"
            + "<rect width='24' height='24' rx='6' fill='url(#g)'/>"
            + "<path d='M5 19h14' stroke='rgba(255,255,255,.4)' stroke-width='1.2' stroke-linecap='round' stroke-dasharray='1.4 2.6'/>"
            + "<path d='M5 15.5h6l7.5-8.5' stroke='#fff' stroke-width='2.2' fill='none' stroke-linecap='round' stroke-linejoin='round'/>"
            + "<circle cx='11' cy='15.5' r='1.9' fill='#fff'/></svg>";

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
    private io.liftandshift.strikebench.paper.MarksSource portfolioMarks; // shared with BookRiskService
    private io.liftandshift.strikebench.paper.CampaignService campaigns;
    private io.liftandshift.strikebench.paper.AlertCenterService alertCenter;
    private Javalin app;
    private Db db;   // owned pool; closed on stop()
    private io.liftandshift.strikebench.market.MarketDataEngine marketEngine;   // in-memory feed; warm + background refresh
    private io.liftandshift.strikebench.db.DataJobService dataJobs;             // Data Center background jobs
    private io.liftandshift.strikebench.db.DataCoverage dataCoverage;           // Data Center coverage matrix
    private io.liftandshift.strikebench.db.DataResetService dataReset;          // Data Center tiered wipe
    private io.liftandshift.strikebench.db.DataConnectorCatalog dataConnectors;
    private io.liftandshift.strikebench.db.DataSyncState dataSyncState;
    private io.liftandshift.strikebench.db.DataSyncScheduler dataSyncScheduler;
    private io.liftandshift.strikebench.db.MarketDataMaintenanceGate marketDataMaintenance;
    private io.liftandshift.strikebench.db.ArtifactRetentionService artifactRetention;
    private CboeProvider cboe;                                                  // for Data Center throttle display
    private io.liftandshift.strikebench.db.DatasetService datasets;             // observed + synthetic dataset registry
    private io.liftandshift.strikebench.sim.SimulationEngine simEngine;         // scenario previews + dataset runs
    private io.liftandshift.strikebench.sim.PathEnsembleService pathEnsembles;   // one lane-aware path source
    private java.util.concurrent.ScheduledExecutorService snapshotScheduler;   // started iff SNAPSHOT_ENABLED
    private java.util.concurrent.ScheduledExecutorService portfolioValuationScheduler;
    private final String startedAt = java.time.Instant.now().toString();
    private WorldTransitionService worldTransitions;
    private TradeController tradeController;
    private TradeController.ExactAssessment exactAssessmentOverride;
    private DiscoveryController discoveryController;
    private OutcomeController outcomeController;
    private PlanController planController;
    private final MarketUniverseView universeViews;
    private CoreController coreController;

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
        this.eventCalendar = evaluations.eventCalendar();
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
        this.universeViews = new MarketUniverseView(cfg, market, universe, simSessions);
    }

    /** Wires the whole app from config: schema migrations + provider chain + services. */
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
            // Owner-authorized Yahoo equity candles. They remain explicitly attributable, persist
            // through the canonical observed store, and can be revoked with YAHOO_ENABLED=false.
            // Ahead of Stooq (which is bot-blocked for us) so history does not fall into an outage.
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
        var marketDataMaintenance = new io.liftandshift.strikebench.db.MarketDataMaintenanceGate();
        MarketDataService market = new MarketDataService(providers, newsProviders, ratesProviders,
                new io.liftandshift.strikebench.db.StoredCandleStore(db, marketDataMaintenance));
        market.setDemoSources(fixture, fixture, fixture);
        // Warm option chains: a scan reads the last-known observed option_bar chain when the live
        // provider is exhausted/rate-limited/absent, instead of an empty "no listed options".
        market.setWarmOptionStore(new io.liftandshift.strikebench.db.StoredOptionChainStore(db));
        AuditLog audit = new AuditLog(db, clock);
        AccountService accounts = new AccountService(db, cfg, audit, clock);
        MarketDataMarks marksSource = new MarketDataMarks(market, cfg.fixturesOnly());
        TradeService trades = new TradeService(db, cfg, marksSource, audit, clock);
        PositionsService positions = new PositionsService(db, marksSource, audit, clock, cfg.fixturesOnly());
        var eventCalendar = new io.liftandshift.strikebench.market.EventService(market, db, clock);
        RecommendationEngine engine = new RecommendationEngine(market, clock, eventCalendar)
                .withFees(cfg.feePerContractCents(), cfg.feePerOrderCents());
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
        io.liftandshift.strikebench.market.SnapshotService snapshots =
                new io.liftandshift.strikebench.market.SnapshotService(
                        market, universe, db, clock, marketDataMaintenance);
        io.liftandshift.strikebench.auth.AuthService auth = buildAuth(cfg, db, clock);
        io.liftandshift.strikebench.eval.EvaluationService evaluations = new io.liftandshift.strikebench.eval.EvaluationService(
                market, db, clock, eventCalendar)
                .withFees(cfg.feePerContractCents(), cfg.feePerOrderCents()); // decision EV matches the REAL commission
        AutoRecommender auto = new AutoRecommender(new SignalEngine(market, clock, cfg.fixturesOnly()),
                engine, evaluations, cfg, clock);
        ApiServer server = new ApiServer(cfg, clock, market, audit, accounts, trades, engine, auto, broker, backtester, positions, universe, snapshots, auth, evaluations);
        server.marketDataMaintenance = marketDataMaintenance;
        marksSource.setEngine(server.marketEngine);
        server.db = db;
        server.portfolioBooks = new io.liftandshift.strikebench.paper.PortfolioAccountingService(db, clock, marksSource);
        server.portfolioExports = new io.liftandshift.strikebench.paper.PortfolioExportService(server.portfolioBooks);
        server.portfolioMarks = marksSource;
        server.campaigns = new io.liftandshift.strikebench.paper.CampaignService(db, clock);
        // The alert center reuses the trade/marks services the Manage band uses — one trigger
        // evaluator, no second math — and announces material changes on the existing event bus.
        server.alertCenter = new io.liftandshift.strikebench.paper.AlertCenterService(db, clock,
                trades, marksSource, server.eventCalendar::earnings, server.events,
                cfg.feePerContractCents());
        audit.setAccountChangedHook(server.alertCenter::invalidateAccount);
        server.portfolioBooks.setOwnerChangedHook(server.alertCenter::invalidateOwner);
        server.simSessions.attachDb(db);
        // Data Center services (need db, which is set above; reuse the constructor-built engine).
        var backfill = new io.liftandshift.strikebench.db.UnderlyingBackfill(market, db, clock);
        server.dataConnectors = new io.liftandshift.strikebench.db.DataConnectorCatalog(cfg, providerBudget);
        server.dataSyncState = new io.liftandshift.strikebench.db.DataSyncState(db, clock);
        server.dataJobs = new io.liftandshift.strikebench.db.DataJobService(db, clock, server.marketEngine,
                snapshots, backfill, universe, cfg, server.dataConnectors, marketDataMaintenance);
        server.dataSyncScheduler = new io.liftandshift.strikebench.db.DataSyncScheduler(
                cfg, clock, server.dataSyncState, server.dataJobs, universe);
        server.artifactRetention = new io.liftandshift.strikebench.db.ArtifactRetentionService(db, clock, cfg);
        server.dataCoverage = new io.liftandshift.strikebench.db.DataCoverage(db);
        server.dataReset = new io.liftandshift.strikebench.db.DataResetService(
                db, accounts, server.dataJobs, server.dataSyncScheduler, marketDataMaintenance);
        server.cboe = cboeRef[0];
        var settingsStore = new io.liftandshift.strikebench.db.SettingsStore(db);
        var quoteSnapshots = new io.liftandshift.strikebench.db.MarketSnapshotStore(db, marketDataMaintenance);
        market.setQuoteSnapshotStore(quoteSnapshots);
        server.marketEngine.setSnapshotStore(quoteSnapshots);
        server.datasets = datasetSvc;
        server.pathEnsembles = new io.liftandshift.strikebench.sim.PathEnsembleService(market, clock);
        server.simEngine = new io.liftandshift.strikebench.sim.SimulationEngine(
                market, datasetSvc, db, clock, server.pathEnsembles, marketDataMaintenance);
        server.worldTransitions = new WorldTransitionService(cfg, clock, db, datasetSvc, market,
                server.simSessions, server.events,
                (world, owner) -> server.universeViews.describe(worldParam(world), owner),
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
        datasetSvc.setEvents(server.events);
        if (cboeRef[0] != null) cboeRef[0].setEvents(server.events);
        if (yahooRef[0] != null) yahooRef[0].setEvents(server.events);
        // Provider breakers survive restarts: an ordinary restart must not resume traffic inside
        // an upstream-requested quiet period. This generalizes the original Cboe-only mechanism to
        // every mounted provider that publishes the shared provider.cooldown event.
        var cooldownProviders = new java.util.LinkedHashMap<String, java.util.function.LongConsumer>();
        if (cboeRef[0] != null) cooldownProviders.put("cboe", cboeRef[0]::seedCooldown);
        if (yahooRef[0] != null) cooldownProviders.put("yahoo", yahooRef[0]::seedCooldown);
        io.liftandshift.strikebench.db.ProviderCooldownState.wire(
                settingsStore, server.events, cooldownProviders);
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

    ApiServer exactAssessmentForTest(TradeController.ExactAssessment assessment) {
        if (tradeController != null) throw new IllegalStateException("server already started");
        this.exactAssessmentOverride = assessment;
        return this;
    }

    public Javalin start(int port) {
        accounts.getOrCreateDefault();
        var accountObjectives = new io.liftandshift.strikebench.paper.AccountObjectiveService(db, clock);
        var bookRisk = new io.liftandshift.strikebench.paper.BookRiskService(
                db, clock, portfolioMarks, portfolioBooks, accountObjectives, trades,
                positions, pathEnsembles,
                new io.liftandshift.strikebench.market.EtfLookThroughService(clock));
        var heldPositionEconomics = new io.liftandshift.strikebench.position.HeldPositionEconomicsService(
                clock, eventCalendar);
        var bookActionProjections = new io.liftandshift.strikebench.paper.BookActionProjectionService(
                portfolioBooks, bookRisk, accounts, trades, positions, clock);
        var lifecycleDecisions = new io.liftandshift.strikebench.position.PositionLifecycleDecisionService(
                db, clock);
        var trackedPackageAnalyses = new TrackedPackageAnalysisService(
                portfolioBooks, accounts, trades, evaluations, accountObjectives, heldPositionEconomics,
                bookActionProjections, lifecycleDecisions);
        PortfolioController portfolioController = new PortfolioController(db, clock, portfolioBooks,
                portfolioExports, positions, trades, trackedPackageAnalyses,
                accountObjectives, bookRisk, lifecycleDecisions,
                this::ownerId, this::currentAccount);
        CampaignController campaignController = new CampaignController(campaigns, this::ownerId);
        tradeController = new TradeController(cfg, clock, db, accounts, market, eventCalendar, audit,
                trades, positions, evaluations, snapshots, auth, this::currentAccount,
                this::ownerId, this::activeWorld, this::analysisCtx, this::requireAdmin,
                exactAssessmentOverride, trackedPackageAnalyses);
        var marketVolatility = new io.liftandshift.strikebench.sim.MarketVolatilityResolver(market, clock);
        var opportunityScanner = new io.liftandshift.strikebench.recommend.OpportunityScanner(engine, evaluations);
        discoveryController = new DiscoveryController(db, market, evaluations, opportunityScanner, engine, auto,
                positions, trades, portfolioBooks, accountObjectives, bookRisk, lifecycleDecisions,
                universe, simSessions, clock, marketVolatility, this::currentAccount, this::ownerId,
                this::activeWorld, tradeController::riskCapCents);
        outcomeController = new OutcomeController(cfg, clock, market, simEngine, pathEnsembles,
                marketVolatility, planOutcomes, this::activeWorld, this::ownerId, this::analysisCtx,
                discoveryController::decisionCompetition);
        var positionArtifacts = new io.liftandshift.strikebench.position.PositionArtifactStore(db);
        var brokerImports = new io.liftandshift.strikebench.paper.BrokerImportService(
                db, clock, portfolioBooks, portfolioMarks, positionArtifacts, campaigns);
        brokerImports.setOwnerChangedHook(alertCenter::invalidateOwner);
        var planPromotions = new io.liftandshift.strikebench.plan.PlanPromotionService(
                db, planDecisions, portfolioBooks, positionArtifacts);
        var planAdoptions = new io.liftandshift.strikebench.plan.PlanAdoptionService(
                db, clock, planSvc, positionArtifacts, portfolioMarks);
        var planAdoptionReviews = new PlanAdoptionReviewService(db,
                trackedPackageAnalyses::analyze, trackedPackageAnalyses::surface,
                campaigns, accountObjectives,
                portfolioBooks, heldPositionEconomics);
        planController = new PlanController(cfg, clock, db, market, eventCalendar,
                positions, trades, backtester, auto, evaluations, planSvc, planEvidence,
                planStrategy, planOutcomes, planRehearsals, planDecisions, planManagement,
                planPromotions, planAdoptions, planAdoptionReviews,
                pathEnsembles, simEngine, discoveryController, outcomeController,
                tradeController, this::currentAccount, this::ownerId, this::activeWorld, this::analysisCtx);
        PositionTransformationController positionTransformations = new PositionTransformationController(
                clock, trades, tradeController, planController, planSvc, planManagement,
                positionArtifacts);
        BrokerImportController brokerImportController = new BrokerImportController(brokerImports, this::ownerId);
        SparklineController sparklineController = new SparklineController(clock, market, universe,
                evaluations, this::activeWorld, this::analysisCtx);
        dataJobs.setDataChangedHook(sparklineController::invalidate);
        coreController = new CoreController(cfg, clock, market, marketEngine, universe,
                universeViews, datasets, workspaceSvc, accounts, auth, cboe, sparklineController,
                simSessions, events, this::ownerId, this::activeWorld, this::cachedActiveWorldFor,
                this::currentAccount, this::requireAdmin, () -> !jarChangedHint().isEmpty(), startedAt);
        DataController dataController = new DataController(cfg, clock, db, market, eventCalendar, marketEngine,
                universe, dataJobs, dataCoverage, dataReset, marketDataMaintenance,
                dataConnectors, dataSyncState,
                datasets, cboe, simSessions, worldTransitions, audit, this::ownerId,
                this::activeWorld, this::isAdmin, this::requireAdmin,
                sparklineController::invalidate, outcomeController::generateDataset);
        ResearchController researchController = new ResearchController(cfg, db, clock, market, eventCalendar,
                evaluations, this::ownerId, this::activeWorld, this::analysisCtx,
                planController::planSymbolEligibility);
        ApiTelemetry telemetry = new ApiTelemetry(cfg, marketEngine);
        BrokerController brokerController = new BrokerController(broker);
        WorldController worldController = new WorldController(cfg, clock, market, marketEngine,
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
                    sh.setMaxInactiveInterval(cfg.authSessionIdleSeconds());
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
            // Browsers request /favicon.ico unconditionally, even with an inline <link rel=icon>;
            // serve the brand mark so those requests are a 200, not a console-noise 404.
            c.routes.get("/favicon.ico", ctx -> {
                ctx.contentType("image/svg+xml");
                ctx.header("Cache-Control", "public, max-age=86400");
                ctx.result(FAVICON_SVG);
            });
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

            telemetry.register(c);
            // Prefetch governor: speculative requests (X-Priority: prefetch) are welcome only when
            // the heavy providers have spare budget. A denied prefetch is a quiet 204 — the client
            // simply doesn't warm its cache; the user never waits on (or behind) a guess.
            c.routes.before("/api/research/*", ctx -> {
                if ("prefetch".equalsIgnoreCase(ctx.header("X-Priority")) && !coreController.prefetchBudget()) {
                    ctx.status(204);
                    ctx.header("X-Prefetch", "denied");
                    ctx.skipRemainingHandlers();
                }
            });

            coreController.register(c, telemetry);

            planController.register(c);

            positionTransformations.register(c);

            dataController.register(c);

            researchController.register(c);

            discoveryController.register(c);

            outcomeController.register(c);

            // ---- The simulated market (Block S): per-user runtime switch, never a boot flag ----
            worldController.register(c);

            tradeController.register(c);

            portfolioController.register(c);
            brokerImportController.register(c);

            // Campaigns: the Book's interpretation layer over recorded activity.
            campaignController.register(c);

            // The alert center: the Desk's needs-attention list, computed from real state.
            new AlertController(alertCenter, this::ownerId).register(c);

            // Historical replays are Plan-owned. The report id remains readable so a Plan can
            // restore its full normalized summary plus the existing detailed replay artifact.

            brokerController.register(c);

            c.routes.exception(io.liftandshift.strikebench.auth.UnauthorizedException.class, (e, ctx) ->
                    ctx.status(401).json(new ApiResponses.AuthErrorBody("auth_required",
                            String.valueOf(e.getMessage()), "/auth/login")));
            c.routes.exception(io.liftandshift.strikebench.auth.ForbiddenException.class, (e, ctx) ->
                    ctx.status(403).json(new ApiResponses.ErrorBody("forbidden",
                            String.valueOf(e.getMessage()))));
            c.routes.exception(TradeRejectedException.class, (e, ctx) ->
                    ctx.status(422).json(new ApiResponses.TradeRejectedBody("trade_rejected",
                            e.getMessage(), e.reasons())));
            c.routes.exception(IllegalArgumentException.class, (e, ctx) ->
                    ctx.status(400).json(new ApiResponses.ErrorBody(
                            "bad_request", String.valueOf(e.getMessage()))));
            c.routes.exception(java.time.format.DateTimeParseException.class, (e, ctx) ->
                    ctx.status(400).json(new ApiResponses.ErrorBody(
                            "bad_request", "Invalid date: " + e.getParsedString())));
            // Domain records validate in their constructors; when Jackson trips one during body
            // binding, surface the record's own units-bearing reason, never a generic shrug.
            c.routes.exception(com.fasterxml.jackson.databind.exc.ValueInstantiationException.class, (e, ctx) ->
                    ctx.status(400).json(new ApiResponses.ErrorBody("bad_request",
                            e.getCause() instanceof IllegalArgumentException reason && reason.getMessage() != null
                                    ? reason.getMessage()
                                    : "Malformed request body (expected JSON matching this endpoint's schema)")));
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
                telemetry.recordError();
                boolean changed = !jarChangedHint().isEmpty();
                log.error("Request failed on {} {}{}", ctx.method(), ctx.path(),
                        changed ? " because the running build changed; restart StrikeBench" : "", e);
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
        if (coreController != null) coreController.close();
        if (snapshotScheduler != null) snapshotScheduler.shutdownNow();
        if (portfolioValuationScheduler != null) portfolioValuationScheduler.shutdownNow();
        if (app != null) app.stop();
        if (alertCenter != null) alertCenter.close();
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
        if (io.liftandshift.strikebench.market.MarketLane.isSimulatedWorld(world)) {
            throw new IllegalStateException(what + " \u2014 it has no meaning inside a simulated session. "
                    + "Return to the baseline market to run it; your simulated session keeps running.");
        }
    }

    /** Normalizes the route-level world to the service-layer convention (null = observed). */

    /** The caller's active market world: 'observed' unless they switched to a sim session. */
    private String activeWorld(Context ctx) {
        return activeWorldFor(ownerId(ctx));
    }

    private String activeWorldFor(String owner) {
        return worldTransitions.active(owner);
    }

    private String cachedActiveWorldFor(String owner) {
        return worldTransitions.activeCached(owner);
    }


    private io.liftandshift.strikebench.db.AnalysisContext analysisCtx(Context ctx) {
        return resolveAnalysisContext(datasets, ownerId(ctx));
    }

    static io.liftandshift.strikebench.db.AnalysisContext resolveAnalysisContext(
            io.liftandshift.strikebench.db.DatasetService datasets, String owner) {
        // A dataset-resolution failure is not evidence that the caller selected Observed.
        // Falling through here would splice real-market analysis into a scenario workspace.
        String datasetId = datasets == null
                ? io.liftandshift.strikebench.db.DatasetService.OBSERVED
                : datasets.activeId(owner);
        return new io.liftandshift.strikebench.db.AnalysisContext(owner, datasetId);
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
            // FAIL CLOSED: admin requires an EXPLICIT AUTH_ADMIN_EMAILS entry. We do NOT fall back to
            // the entry allowlist (with the default "any verified Google account" that would make every
            // signed-in user an admin for destructive ops).
            return auth.userIsAdmin(auth.currentUserId(ctx));
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
            throw new io.liftandshift.strikebench.auth.ForbiddenException(
                "Admin access required. On a public deployment, enable AUTH (+ AUTH_ADMIN_EMAILS), or set ADMIN_TOKEN and send it as X-Admin-Token.");
        }
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
