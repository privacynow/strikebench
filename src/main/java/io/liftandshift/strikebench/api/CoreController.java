package io.liftandshift.strikebench.api;
import static io.liftandshift.strikebench.market.MarketLane.worldParam;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.auth.AuthService;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.DatasetService;
import io.liftandshift.strikebench.db.WorkspaceContext;
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
                this::workspaceGet, this::workspacePut, this::workspacePatch,
                this::account, this::accountReset));
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

    private String activeDataset(String owner) {
        return datasets == null ? DatasetService.OBSERVED : datasets.activeId(owner);
    }

    /**
     * THE lane the caller is in. One definition, shared by /api/config and the workspace context,
     * so the header's mode and the workspace's mode cannot drift into two dialects.
     */
    private String laneFor(String activeDataset, String world) {
        return !DatasetService.OBSERVED.equals(activeDataset) && "observed".equals(world)
                ? "SCENARIO" : MarketLane.of(world, cfg.fixturesOnly()).name();
    }

    private void config(Context ctx) {
        String owner = ownerId.apply(ctx);
        String active = activeDataset(owner);
        String world = activeWorld.apply(ctx);
        String lane = laneFor(active, world);
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

    /**
     * ONE batch-quote authority (§5.5). Every requested symbol gets exactly one
     * {@link ApiResponses.QuoteView} — the same typed receipt the single-symbol research document
     * publishes — in every lane. A symbol the market cannot price still gets a row, stating why,
     * so a caller never has to infer a price from `last` and a previous close, and never sees a
     * symbol silently disappear from the answer.
     */
    private void quotesBatch(Context ctx) {
        String raw = ctx.queryParam("symbols");
        String world = worldParam(activeWorld.apply(ctx));
        int limit = SimulationSessions.MAX_SYMBOLS;
        if (world != null) {
            List<String> symbols = raw == null || raw.isBlank()
                    ? MarketUniverseView.symbolsForWorld(market, universe, world)
                    : parseSymbols(raw);
            int requested = symbols.size();
            List<String> bounded = symbols.stream().limit(limit).toList();
            MarketLane lane = market.lane(world);
            List<ApiResponses.QuoteView> rows = new ArrayList<>();
            for (String symbol : bounded) {
                rows.add(market.quote(symbol, world)
                        .map(quote -> ApiResponses.QuoteView.of(quote, false))
                        .orElseGet(() -> ApiResponses.QuoteView.unavailable(symbol,
                                worldUnavailableReason(symbol, world, lane))));
            }
            ctx.json(new ApiResponses.WorldQuotes<>(rows, requested, bounded.size(),
                    requested > limit, limit, world, lane.name()));
            return;
        }
        List<String> symbols = raw == null || raw.isBlank()
                ? universe.active().symbols() : parseSymbols(raw);
        int requested = symbols.size();
        if (symbols.size() > limit) symbols = symbols.subList(0, limit);
        Map<String, MarketDataEngine.MarketSnapshot> priced = new LinkedHashMap<>();
        for (var snapshot : engine.quotes(symbols)) priced.put(snapshot.symbol(), snapshot);
        List<ApiResponses.QuoteView> rows = new ArrayList<>();
        for (String symbol : symbols) {
            var snapshot = priced.get(symbol);
            rows.add(snapshot == null
                    ? ApiResponses.QuoteView.unavailable(symbol, engine.unavailableReason(symbol))
                    : ApiResponses.QuoteView.of(snapshot.toQuote(), snapshot.refreshing()));
        }
        ctx.json(new ApiResponses.Quotes<>(rows, requested, symbols.size(), requested > limit,
                limit, cfg.fixturesOnly() ? "DEMO" : "OBSERVED"));
    }

    /** Why a non-observed market has no quote for a symbol — named per lane, never guessed. */
    private static String worldUnavailableReason(String symbol, String world, MarketLane lane) {
        if (lane == MarketLane.DEMO) {
            return "the demo market has no teaching quote for " + symbol;
        }
        return "simulated world " + world + " does not price " + symbol
                + " — its symbol set was fixed when the world was created";
    }

    /**
     * The caller's authoritative market for workspace purposes. World, lane and the account that
     * owns the book are ALL derived here — never read from a request body — so the browser cannot
     * declare which market it is in, and the mode it renders is the mode the context was committed
     * against.
     */
    private WorkspaceContext.ActiveMarket activeMarket(Context ctx) {
        String world = activeWorld.apply(ctx);
        return new WorkspaceContext.ActiveMarket(world,
                laneFor(activeDataset(ownerId.apply(ctx)), world),
                currentAccount.apply(ctx).id());
    }

    private void workspaceGet(Context ctx) {
        if (workspace == null) {
            ctx.json(new ApiResponses.Workspace(0, null, WorkspaceContext.CURRENT_VERSION,
                    null, null, null, null, null,
                    new WorkspaceContext.Unreadable(null, WorkspaceContext.CURRENT_VERSION,
                            "the workspace store is unavailable in this build; nothing was restored")));
            return;
        }
        WorkspaceContext.ActiveMarket market = activeMarket(ctx);
        workspaceReceipt(ctx, workspace.context(ownerId.apply(ctx), market), market);
    }

    /** Full replace. Every client-owned field the body omits becomes undeclared. */
    private void workspacePut(Context ctx) {
        if (requireWorkspaceStore(ctx)) return;
        WorkspaceContext requested = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, WorkspaceContext.class));
        WorkspaceContext.ActiveMarket market = activeMarket(ctx);
        workspaceReceipt(ctx, workspace.replace(ownerId.apply(ctx), requested, market), market);
    }

    /**
     * Partial write. Omitted fields keep their stored value; only {@code clear} un-declares. This
     * is what Import Trade must use so it cannot destroy goal, view, horizon, risk or the world.
     */
    private void workspacePatch(Context ctx) {
        if (requireWorkspaceStore(ctx)) return;
        WorkspaceContext.Patch patch = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, WorkspaceContext.Patch.class));
        WorkspaceContext.ActiveMarket market = activeMarket(ctx);
        workspaceReceipt(ctx, workspace.patch(ownerId.apply(ctx), patch, market), market);
    }

    private boolean requireWorkspaceStore(Context ctx) {
        if (workspace != null) return false;
        ctx.status(503).json(new ApiResponses.ErrorOnly("workspace store unavailable"));
        return true;
    }

    private static void workspaceReceipt(Context ctx, WorkspaceService.ContextState state,
                                         WorkspaceContext.ActiveMarket market) {
        WorkspaceContext context = state.context();
        ctx.json(new ApiResponses.Workspace(state.rev(), state.updatedAt(),
                WorkspaceContext.CURRENT_VERSION,
                context == null ? market.world() : context.world(),
                context == null ? market.lane() : context.marketLane(),
                context == null ? market.accountId() : context.accountId(),
                context, state.transition(), state.unreadable()));
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
