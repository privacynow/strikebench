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
    private Javalin app;
    private Db db;   // owned pool; closed on stop()
    private java.util.concurrent.ScheduledExecutorService snapshotScheduler;   // started iff SNAPSHOT_ENABLED
    private final String startedAt = java.time.Instant.now().toString();

    public ApiServer(AppConfig cfg, Clock clock, MarketDataService market, AuditLog audit,
                     AccountService accounts, TradeService trades, RecommendationEngine engine,
                     AutoRecommender auto, BrokerService broker, Backtester backtester,
                     PositionsService positions, io.liftandshift.strikebench.market.UniverseService universe,
                     io.liftandshift.strikebench.market.SnapshotService snapshots,
                     io.liftandshift.strikebench.auth.AuthService auth) {
        this.positions = positions;
        this.universe = universe;
        this.snapshots = snapshots;
        this.auth = auth;
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

        // Priority: E*TRADE -> Cboe -> AlphaVantage/Polygon -> Stooq -> Fixture (always last resort)
        if (!cfg.fixturesOnly()) {
            if (etrade.configured()) providers.add(etrade);
            providers.add(new CboeProvider(cfg));
            if (!cfg.alphaVantageApiKey().isBlank()) providers.add(new AlphaVantageProvider(cfg));
            if (!cfg.polygonApiKey().isBlank()) providers.add(new PolygonProvider(cfg));
            providers.add(new StooqProvider(cfg));
            newsProviders.add(new EdgarProvider(cfg));
            if (!cfg.fredApiKey().isBlank()) ratesProviders.add(new FredProvider(cfg));
            ratesProviders.add(new TreasuryRatesProvider(cfg));
        }
        providers.add(fixture);
        newsProviders.add(fixture);
        ratesProviders.add(fixture);

        MarketDataService market = new MarketDataService(providers, newsProviders, ratesProviders);
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
        } else if (!cfg.polygonApiKey().isBlank()) {
            historical.add(new PolygonProvider(cfg));
        }
        Backtester backtester = new Backtester(market, historical, cfg, db, clock);
        io.liftandshift.strikebench.market.UniverseService universe = new io.liftandshift.strikebench.market.UniverseService(db, cfg, clock);
        io.liftandshift.strikebench.market.SnapshotService snapshots = new io.liftandshift.strikebench.market.SnapshotService(market, universe, db, clock);
        io.liftandshift.strikebench.auth.AuthService auth = buildAuth(cfg, db, clock);
        ApiServer server = new ApiServer(cfg, clock, market, audit, accounts, trades, engine, auto, broker, backtester, positions, universe, snapshots, auth);
        server.db = db;
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

            c.routes.get("/api/status", this::status);
            c.routes.get("/api/config", this::config);
            c.routes.get("/api/health", this::health);
            c.routes.get("/api/universe", ctx -> ctx.json(universe.describe()));
            c.routes.put("/api/universe", this::universeSelect);
            c.routes.get("/api/quotes", this::quotesBatch);

            c.routes.get("/api/account", this::account);
            c.routes.post("/api/account/reset", this::accountReset);

            c.routes.get("/api/research/{symbol}", this::research);
            c.routes.get("/api/research/{symbol}/expirations", this::expirations);
            c.routes.get("/api/research/{symbol}/chain", this::chain);
            c.routes.get("/api/research/{symbol}/history", this::history);
            c.routes.get("/api/research/{symbol}/news", this::news);
            c.routes.get("/api/lookup", this::lookup);

            c.routes.post("/api/recommend", this::recommend);
            c.routes.post("/api/recommend/auto", this::recommendAuto);
            c.routes.post("/api/recommend/ladder", this::recommendLadder);

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
                    ctx.json(backtester.run(requireBody(bodyOrNull(ctx, Backtester.BacktestRequest.class)))));
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
        if (snapshotScheduler != null) snapshotScheduler.shutdownNow();
        if (app != null) app.stop();
        if (db != null) db.close();
    }

    /** Manually records a forward snapshot of the active universe. Returns per-run counts. */
    private void adminSnapshot(Context ctx) {
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
        ctx.json(Map.of(
                "port", cfg.port(),
                "fixturesOnly", cfg.fixturesOnly(),
                "feePerContractCents", cfg.feePerContractCents(),
                "feePerOrderCents", cfg.feePerOrderCents(),
                "defaultStartingCashCents", cfg.defaultStartingCashCents(),
                "brand", Map.of("name", cfg.brandName(), "tagline", cfg.brandTagline()),
                "disclaimer", RecommendationEngine.DISCLAIMER));
    }

    /**
     * Liveness + staleness beacon. jarChangedSinceBoot=true means the application jar was
     * rewritten under this running server — the classic "some screens fail to load" cause —
     * and the UI turns it into a restart banner instead of a mystery. Never 500s.
     */
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
        List<Map<String, Object>> out = new ArrayList<>();
        for (String s : symbols) {
            var q = market.quote(s).orElse(null);
            if (q == null || q.last() == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", q.symbol());
            row.put("description", q.description());
            row.put("last", q.last().toPlainString());
            row.put("prevClose", q.prevClose() == null ? null : q.prevClose().toPlainString());
            row.put("optionable", q.optionable());
            row.put("freshness", q.freshness().name());
            out.add(row);
        }
        ctx.json(Map.of("quotes", out, "requested", symbols.size()));
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
        List<LocalDate> exps = market.expirations(symbol);

        Double ivAtm = null;
        if (!exps.isEmpty()) {
            ivAtm = market.chain(symbol, exps.getFirst())
                    .flatMap(ch -> atmIv(ch))
                    .orElse(null);
        }
        var candleSeries = market.candleSeries(symbol, today.minusDays(120), today);
        double hv30 = HistoricalVol.annualized(candleSeries.candles(), 30);
        boolean demoHistory = candleSeries.isFixture() && !cfg.fixturesOnly();

        List<Map<String, Object>> benchmarks = new ArrayList<>();
        for (String bench : List.of("SPY", "QQQ")) {
            if (bench.equals(symbol)) continue;
            market.quote(bench).ifPresent(b -> benchmarks.add(Map.of(
                    "symbol", b.symbol(), "last", b.last(), "freshness", b.freshness().name())));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("quote", q);
        out.put("optionable", q.optionable());
        out.put("ivAtm", ivAtm);
        out.put("hv30", Double.isNaN(hv30) ? null : hv30);
        out.put("historyDemo", demoHistory);
        out.put("expirations", exps.stream().map(LocalDate::toString).toList());
        out.put("benchmarks", benchmarks);
        out.put("freshness", q.freshness().name());
        ctx.json(out);
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
        var series = market.candleSeries(symbol, today.minusDays(days), today);
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
        ctx.json(engine.recommend(req, acct.buyingPowerCents()));
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
                                   String intent, Boolean useHeldShares) {}

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
        TradeService.OpenRequest req = toOpenRequest(bodyOrNull(ctx, TradeOpenRequest.class), acct);
        Verdict verdict = guardrailCheck(req, acct);
        if (verdict.blocked()) {
            audit.log(acct.id(), null, "TRADE_REJECTED", "BLOCK",
                    Map.of("symbol", req.symbol(), "strategy", req.strategy(), "reasons", verdict.blockReasons()));
            throw new TradeRejectedException(verdict.blockReasons());
        }
        TradeRecord t = trades.create(req);
        ctx.status(201).json(Map.of("trade", TradeView.of(t), "warnings", verdict.warnings()));
    }

    private void tradeList(Context ctx) {
        Account acct = currentAccount(ctx);
        int page = intParam(ctx, "page", 0);
        int size = Math.clamp(intParam(ctx, "size", 20), 1, 100);
        TradeService.Page result = trades.list(acct.id(), ctx.queryParam("status"),
                ctx.queryParam("symbol"), ctx.queryParam("intent"), page, size);
        ctx.json(Map.of(
                "trades", result.trades().stream().map(TradeView::of).toList(),
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
        ctx.json(Map.of("trade", TradeView.of(result.trade()), "realizedPnlCents", result.realizedPnlCents()));
    }

    private void tradeSettle(Context ctx) {
        ensureOwnedTrade(ctx, ctx.pathParam("id"));
        ConfirmRequest req = bodyOrNull(ctx, ConfirmRequest.class);
        TradeService.CloseResult result = trades.settle(ctx.pathParam("id"),
                req != null && Boolean.TRUE.equals(req.confirm()));
        ctx.json(Map.of("trade", TradeView.of(result.trade()), "realizedPnlCents", result.realizedPnlCents()));
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
