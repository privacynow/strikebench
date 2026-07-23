package io.liftandshift.strikebench.api;
import static io.liftandshift.strikebench.market.MarketLane.worldParam;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.auth.AuthService;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.DatasetService;
import io.liftandshift.strikebench.db.WorkspaceService;
import io.liftandshift.strikebench.market.MarketDataEngine;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.market.MarketLane;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.market.providers.CboeProvider;
import io.liftandshift.strikebench.market.sim.SimulationSessions;
import io.liftandshift.strikebench.model.BroadBasedIndexOptions;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountService;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Bootstrap, market-read, workspace-continuity, and practice-account HTTP owner. */
final class CoreController implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CoreController.class);

    private final AppConfig cfg;
    private final Clock clock;
    private final MarketDataService market;
    private final MarketDataEngine engine;
    private final UniverseService universe;
    private final MarketUniverseView universeView;
    private final DatasetService datasets;
    private final WorkspaceService workspace;
    private final AccountService accounts;
    private final AuthService auth;
    private final CboeProvider cboe;
    private final SparklineController sparklines;
    private final MarketStreamController streams;
    private final Function<Context, String> ownerId;
    private final Function<Context, String> activeWorld;
    private final Function<Context, Account> currentAccount;
    private final Consumer<Context> requireAdmin;
    private final BooleanSupplier jarChanged;
    private final String startedAt;

    CoreController(AppConfig cfg, Clock clock, MarketDataService market, MarketDataEngine engine,
                   UniverseService universe, MarketUniverseView universeView, DatasetService datasets,
                   WorkspaceService workspace, AccountService accounts, AuthService auth,
                   CboeProvider cboe, SparklineController sparklines,
                   SimulationSessions sessions, io.liftandshift.strikebench.util.EventBus events,
                   Function<Context, String> ownerId, Function<Context, String> activeWorld,
                   Function<String, String> activeWorldFor,
                   Function<Context, Account> currentAccount, Consumer<Context> requireAdmin,
                   BooleanSupplier jarChanged, String startedAt) {
        this.cfg = cfg;
        this.clock = clock;
        this.market = market;
        this.engine = engine;
        this.universe = universe;
        this.universeView = universeView;
        this.datasets = datasets;
        this.workspace = workspace;
        this.accounts = accounts;
        this.auth = auth;
        this.cboe = cboe;
        this.sparklines = sparklines;
        this.ownerId = ownerId;
        this.activeWorld = activeWorld;
        this.currentAccount = currentAccount;
        this.requireAdmin = requireAdmin;
        this.jarChanged = jarChanged;
        this.startedAt = startedAt;
        this.streams = new MarketStreamController(cfg, clock, market, engine, universe,
                sessions, events, auth, ownerId, activeWorldFor);
    }

    void register(JavalinConfig config, ApiTelemetry telemetry) {
        CoreRoutes.register(config, new CoreRoutes.Handlers(
                telemetry::metrics, this::status, this::config, this::health,
                ctx -> ctx.json(universeView.describe(worldParam(activeWorld.apply(ctx)), ownerId.apply(ctx))),
                this::universeSelect, this::quotesBatch, sparklines::sparklines,
                ctx -> ctx.json(engine.status()), streams::marketStream, streams::eventStream,
                this::workspaceGet, this::workspacePut, this::account, this::accountReset));
    }

    boolean prefetchBudget() {
        return cfg.fixturesOnly() || cboe == null || cboe.prefetchBudget();
    }

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
        String owner = ownerId.apply(ctx);
        String active = datasets == null ? DatasetService.OBSERVED : datasets.activeId(owner);
        String world = activeWorld.apply(ctx);
        String lane = !DatasetService.OBSERVED.equals(active) && "observed".equals(world)
                ? "SCENARIO" : MarketLane.of(world, cfg.fixturesOnly()).name();
        ctx.json(new ApiResponses.Config<>(cfg.port(), cfg.fixturesOnly(),
                MarketHours.isRegularSession(clock.instant()), auth.enabled(),
                cfg.feePerContractCents(), cfg.feePerOrderCents(), cfg.defaultStartingCashCents(),
                new ApiResponses.Brand(cfg.brandName(), cfg.brandTagline()),
                BroadBasedIndexOptions.AUTOMATIC_SYMBOLS, RecommendationEngine.DISCLAIMER, active,
                datasets == null ? active : datasets.nameOf(active),
                !DatasetService.OBSERVED.equals(active), world, lane));
    }

    private void health(Context ctx) {
        boolean changed;
        try {
            changed = jarChanged.getAsBoolean();
        } catch (RuntimeException e) {
            changed = false;
        }
        ctx.json(new ApiResponses.Health(true, startedAt, changed));
    }

    record UniverseSelectRequest(String sector, List<String> symbols) {}

    private void universeSelect(Context ctx) {
        if (!"observed".equals(activeWorld.apply(ctx))) {
            throw new IllegalStateException(
                    "This market owns its symbol list. Return to Observed market before changing sectors.");
        }
        if (auth.enabled()) requireAdmin.accept(ctx);
        UniverseSelectRequest request = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, UniverseSelectRequest.class));
        if (request.sector() != null && !request.sector().isBlank()) {
            universe.selectSector(request.sector());
        } else if (request.symbols() != null && !request.symbols().isEmpty()) {
            universe.selectCustom(request.symbols());
        } else {
            throw new IllegalArgumentException("Provide either a sector key or a symbols list");
        }
        ctx.json(universe.describe());
    }

    private void quotesBatch(Context ctx) {
        String raw = ctx.queryParam("symbols");
        String world = worldParam(activeWorld.apply(ctx));
        if (world != null) {
            List<String> symbols = raw == null || raw.isBlank()
                    ? market.worldSymbols(world).map(List::copyOf).orElse(List.of())
                    : parseSymbols(raw);
            int requested = symbols.size();
            int limit = SimulationSessions.MAX_SYMBOLS;
            List<String> bounded = symbols.stream().limit(limit).toList();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String symbol : bounded) {
                market.quote(symbol, world).ifPresent(quote -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("symbol", quote.symbol());
                    row.put("description", quote.description());
                    row.put("last", quote.last());
                    row.put("bid", quote.bid());
                    row.put("ask", quote.ask());
                    row.put("prevClose", quote.prevClose());
                    row.put("optionable", quote.optionable());
                    row.put("asOf", quote.asOfEpochMs());
                    row.put("freshness", quote.markFreshness().name());
                    row.put("source", quote.source());
                    row.put("evidence", quote.evidence());
                    rows.add(row);
                });
            }
            ctx.json(new ApiResponses.WorldQuotes<>(rows, requested, bounded.size(),
                    requested > limit, limit, world, market.lane(world).name()));
            return;
        }
        List<String> symbols = raw == null || raw.isBlank()
                ? universe.active().symbols() : parseSymbols(raw);
        int requested = symbols.size();
        int limit = SimulationSessions.MAX_SYMBOLS;
        if (symbols.size() > limit) symbols = symbols.subList(0, limit);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var snapshot : engine.quotes(symbols)) rows.add(MarketDataEngine.toRow(snapshot));
        ctx.json(new ApiResponses.Quotes<>(rows, requested, symbols.size(), requested > limit,
                limit, cfg.fixturesOnly() ? "DEMO" : "OBSERVED"));
    }

    private void workspaceGet(Context ctx) {
        if (workspace == null) {
            ctx.json(new ApiResponses.Revision(0));
            return;
        }
        var saved = workspace.get(ownerId.apply(ctx));
        if (saved.isEmpty()) {
            ctx.json(new ApiResponses.Revision(0));
            return;
        }
        ctx.json(new ApiResponses.Workspace<>(saved.get().rev(), saved.get().updatedAt(),
                Json.parse(saved.get().stateJson())));
    }

    private void workspacePut(Context ctx) {
        if (workspace == null) {
            ctx.status(503).json(new ApiResponses.ErrorOnly("workspace store unavailable"));
            return;
        }
        String body = ctx.body();
        if (body == null || body.isBlank()) throw new IllegalArgumentException("state body required");
        com.fasterxml.jackson.databind.JsonNode state;
        try {
            state = Json.parse(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("state must be JSON");
        }
        if (!state.isObject()) throw new IllegalArgumentException("state must be a JSON object");
        long revision = workspace.put(ownerId.apply(ctx), body);
        ctx.json(new ApiResponses.SavedRevision(true, revision));
    }

    private void account(Context ctx) {
        Account account = currentAccount.apply(ctx);
        ctx.json(new ApiResponses.AccountLedger<>(
                accountView(account), accounts.ledger(account.id(), 0, 20)));
    }

    record ResetRequest(Long startingCashCents, Boolean confirm, Boolean force) {}

    private void accountReset(Context ctx) {
        ResetRequest request = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, ResetRequest.class));
        long cash = request.startingCashCents() == null
                ? cfg.defaultStartingCashCents() : request.startingCashCents();
        Account account = accounts.resetAccount(currentAccount.apply(ctx).id(), cash,
                Boolean.TRUE.equals(request.confirm()), Boolean.TRUE.equals(request.force()));
        ctx.json(new ApiResponses.Account<>(accountView(account)));
    }

    private static Map<String, Object> accountView(Account account) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", account.id());
        result.put("name", account.name());
        result.put("type", account.type());
        result.put("startingCashCents", account.startingCashCents());
        result.put("cashCents", account.cashCents());
        result.put("reservedCents", account.reservedCents());
        result.put("buyingPowerCents", account.buyingPowerCents());
        result.put("hasTraded", account.hasTraded());
        result.put("createdAt", account.createdAt());
        return result;
    }

    private static List<String> parseSymbols(String raw) {
        return java.util.Arrays.stream(raw.split(","))
                .map(symbol -> symbol.trim().toUpperCase(Locale.ROOT))
                .filter(symbol -> !symbol.isBlank()).distinct().toList();
    }


    @Override
    public void close() {
        streams.close();
    }
}
