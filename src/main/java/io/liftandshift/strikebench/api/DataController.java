package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.UploadedFile;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.DataConnectorCatalog;
import io.liftandshift.strikebench.db.DataCoverage;
import io.liftandshift.strikebench.db.DataJobService;
import io.liftandshift.strikebench.db.DataResetService;
import io.liftandshift.strikebench.db.DataSyncScheduler;
import io.liftandshift.strikebench.db.DataSyncState;
import io.liftandshift.strikebench.db.DatasetService;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.MissingRangePlanner;
import io.liftandshift.strikebench.db.MarketDataMaintenanceGate;
import io.liftandshift.strikebench.db.UnderlyingCsvIngest;
import io.liftandshift.strikebench.market.MarketDataEngine;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.market.MarketLane;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.market.providers.CboeProvider;
import io.liftandshift.strikebench.market.sim.SimulationSessions;
import io.liftandshift.strikebench.paper.AuditLog;
import io.liftandshift.strikebench.util.ResourceNotFoundException;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** HTTP controller for Data Center sources, jobs, resets, and analysis datasets. */
final class DataController {
    record DataSyncPlanRequest(List<String> symbols, String source, String from, String to, Integer years) {}
    record DataSyncScheduleRequest(Boolean enabled, String source, List<String> symbols, Integer years) {}
    record JobRequest(String kind, Map<String, Object> params) {}
    record ActiveDatasetRequest(String id) {}
    record DataResetRequest(String tier, Boolean confirm) {}

    private final AppConfig cfg;
    private final Clock clock;
    private final Db db;
    private final MarketDataService market;
    private final MarketDataEngine marketEngine;
    private final UniverseService universe;
    private final DataJobService dataJobs;
    private final DataCoverage dataCoverage;
    private final DataResetService dataReset;
    private final MarketDataMaintenanceGate marketDataMaintenance;
    private final DataConnectorCatalog dataConnectors;
    private final DataSyncState dataSyncState;
    private final DatasetService datasets;
    private final CboeProvider cboe;
    private final SimulationSessions simSessions;
    private final WorldTransitionService worldTransitions;
    private final AuditLog audit;
    private final Function<Context, String> ownerId;
    private final Function<Context, String> activeWorld;
    private final Predicate<Context> isAdmin;
    private final Consumer<Context> requireAdmin;
    private final Runnable invalidateHistoricalViews;
    private final Handler generateDataset;

    DataController(AppConfig cfg, Clock clock, Db db, MarketDataService market,
                   MarketDataEngine marketEngine, UniverseService universe,
                   DataJobService dataJobs, DataCoverage dataCoverage,
                   DataResetService dataReset, MarketDataMaintenanceGate marketDataMaintenance,
                   DataConnectorCatalog dataConnectors,
                   DataSyncState dataSyncState, DatasetService datasets,
                   CboeProvider cboe, SimulationSessions simSessions,
                   WorldTransitionService worldTransitions, AuditLog audit,
                   Function<Context, String> ownerId,
                   Function<Context, String> activeWorld,
                   Predicate<Context> isAdmin,
                   Consumer<Context> requireAdmin,
                   Runnable invalidateHistoricalViews,
                   Handler generateDataset) {
        this.cfg = cfg;
        this.clock = clock;
        this.db = db;
        this.market = market;
        this.marketEngine = marketEngine;
        this.universe = universe;
        this.dataJobs = dataJobs;
        this.dataCoverage = dataCoverage;
        this.dataReset = dataReset;
        this.marketDataMaintenance = marketDataMaintenance;
        this.dataConnectors = dataConnectors;
        this.dataSyncState = dataSyncState;
        this.datasets = datasets;
        this.cboe = cboe;
        this.simSessions = simSessions;
        this.worldTransitions = worldTransitions;
        this.audit = audit;
        this.ownerId = ownerId;
        this.activeWorld = activeWorld;
        this.isAdmin = isAdmin;
        this.requireAdmin = requireAdmin;
        this.invalidateHistoricalViews = invalidateHistoricalViews;
        this.generateDataset = generateDataset;
    }

    void register(JavalinConfig config) {
        DataRoutes.register(config, new DataRoutes.Handlers(
                this::overview, this::coverage, this::sources,
                this::syncStatus, this::planSync, this::updateSyncSchedule,
                this::importUnderlying, this::listJobs, this::getJob,
                this::startJob, this::cancelJob, this::retryJob, this::reset,
                ctx -> ctx.json(datasets.describe(ownerId.apply(ctx))), this::activateDataset,
                this::deleteDataset, generateDataset));
    }

    /** One call for the Data Center header. Optional panels fail independently. */
    private void overview(Context ctx) {
        Object engineStatus;
        Object coverage;
        Object jobs;
        try { engineStatus = marketEngine.status(); } catch (Exception e) { engineStatus = null; }
        try { coverage = dataCoverage.summary(); } catch (Exception e) { coverage = null; }
        try { jobs = dataJobs.recent(ownerId.apply(ctx), isAdmin.test(ctx), 8); }
        catch (Exception e) { jobs = List.of(); }
        ctx.json(new ApiResponses.DataOverview<>(engineStatus, coverage, jobs, cfg.fixturesOnly(),
                MarketLane.of(activeWorld.apply(ctx), cfg.fixturesOnly()).name(),
                MarketHours.isRegularSession(clock.instant()), DataJobService.KINDS,
                isAdmin.test(ctx)));
    }

    private void coverage(Context ctx) {
        ctx.json(new ApiResponses.Coverage<>(dataCoverage.bySymbol(), dataCoverage.summary()));
    }

    private void sources(Context ctx) {
        List<ApiResponses.DataSource> sources = new ArrayList<>();
        boolean fixtures = cfg.fixturesOnly();
        boolean cboeThrottled = cboe != null && cboe.coolingDown();
        String cboeHint = "Keyless, HEAVY (each request is a full option-chain payload). Current chains with "
                + "bid/ask/IV/greeks, 15-min delayed. Used for option workflows + a slow active-sector refresh.";
        if (cboeThrottled) {
            cboeHint = "THROTTLED — Cboe rate-limited us (429/1015); cooling down. Serving stale/other "
                    + "sources until it clears. " + cboeHint;
        }
        sources.add(source(cboeThrottled ? "Cboe (delayed chains) — THROTTLED" : "Cboe (delayed chains)",
                "Option chains + greeks", !fixtures && !cboeThrottled,
                "keyless · delayed · display-only", cboeHint));
        boolean edgarOn = !fixtures && cfg.edgarConfigured();
        sources.add(source("SEC EDGAR", "Filings (10-K/10-Q/8-K)", edgarOn,
                "public · contact required",
                edgarOn
                        ? "Configured with this installation's contact User-Agent. Corporate filings feed Research."
                        : "Set EDGAR_USER_AGENT to your app name and contact email, then restart. StrikeBench never sends another person's identity."));
        sources.add(source("Google News RSS", "Headlines", !fixtures && !cfg.newsRssBaseUrl().isBlank(),
                "keyless · public", "Keyless per-symbol headlines."));
        sources.add(source("Treasury / FRED", "Risk-free rates", !fixtures,
                "keyless / keyed", "Treasury is keyless; FRED needs FRED_API_KEY."));
        sources.add(source("Historical options CSV", "Owned options history", true,
                "licensed · internal-use (no redistribution)",
                "Import a licensed vendor CSV (ORATS / Cboe DataShop / Databento) via a backfill job — the 'own the past' path."));
        ctx.json(new ApiResponses.DataSources<>(sources, dataConnectors.all(),
                dataConnectors.recommendedSource(), fixtures));
    }

    private void syncStatus(Context ctx) {
        var schedule = dataSyncState.schedule(ownerId.apply(ctx));
        ctx.json(new ApiResponses.DataSync<>(dataConnectors.all(), dataConnectors.recommendedSource(),
                dataSyncState.cursors(ownerId.apply(ctx)), schedule,
                dataSyncState.schedule(io.liftandshift.strikebench.util.OwnerScope.SYSTEM),
                dataSyncState.quarantineSummary(ownerId.apply(ctx)),
                DataSyncScheduler.latestCompletedSession(clock).toString(),
                "Owner-authorized Yahoo maintenance covers the canonical universe once after a completed market session. "
                        + "Hourly daily-bar downloads are intentionally avoided."));
    }

    private void planSync(Context ctx) {
        DataSyncPlanRequest body = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, DataSyncPlanRequest.class));
        LocalDate latestCompleted = DataSyncScheduler.latestCompletedSession(clock);
        LocalDate to = body.to() == null || body.to().isBlank()
                ? latestCompleted : LocalDate.parse(body.to());
        boolean futureCapped = to.isAfter(latestCompleted);
        if (futureCapped) to = latestCompleted;
        int years = body.years() == null ? 5 : Math.max(1, Math.min(20, body.years()));
        LocalDate from = body.from() == null || body.from().isBlank()
                ? to.minusYears(years) : LocalDate.parse(body.from());
        String source = body.source() == null || body.source().isBlank() ? "auto" : body.source();
        var connector = dataConnectors.requireAutomated(source);
        LocalDate effectiveFrom = from;
        String limitation = null;
        if ("alphavantage".equals(connector.key()) && !cfg.alphaVantageFullHistoryEnabled()
                && effectiveFrom.isBefore(to.minusDays(160))) {
            effectiveFrom = to.minusDays(160);
            limitation = "Compact Alpha Vantage access covers only the recent window. Use an entitled full-history plan or a user-owned CSV for older dates.";
        }
        List<String> symbols = normalizeSymbols(body.symbols());
        if (symbols.isEmpty()) symbols = universe.active().symbols();
        MissingRangePlanner planner = new MissingRangePlanner(db);
        List<ApiResponses.SyncSymbolPlan<?>> plans = new ArrayList<>();
        int requests = 0;
        int missing = 0;
        for (String symbol : symbols.stream().distinct().limit(120).toList()) {
            var plan = planner.plan(symbol, effectiveFrom, to, connector.key());
            int symbolRequests = "alphavantage".equals(connector.key()) && !plan.complete()
                    ? 1 : plan.ranges().size();
            requests += symbolRequests;
            missing += plan.missingSessions();
            plans.add(new ApiResponses.SyncSymbolPlan<>(symbol, plan.existingSessions(),
                    plan.missingSessions(), symbolRequests, plan.complete(), plan.ranges()));
        }
        ctx.json(new ApiResponses.DataSyncPlan<>(connector, from.toString(), effectiveFrom.toString(),
                to.toString(), symbols.size(), missing, requests, plans, limitation,
                futureCapped ? "The end date was capped at the latest completed market session." : null));
    }

    private void updateSyncSchedule(Context ctx) {
        requireAdmin.accept(ctx);
        DataSyncScheduleRequest body = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, DataSyncScheduleRequest.class));
        boolean enabled = Boolean.TRUE.equals(body.enabled());
        String source = body.source() == null ? "auto" : body.source();
        if (enabled) dataConnectors.requireAutomated(source);
        List<String> symbols = normalizeSymbols(body.symbols());
        if (symbols.isEmpty()) symbols = universe.active().symbols();
        List<String> scheduledSymbols = symbols;
        ctx.json(marketDataMaintenance.write(() -> dataSyncState.saveSchedule(
                ownerId.apply(ctx), enabled, source, scheduledSymbols,
                body.years() == null ? 5 : body.years())));
    }

    private void importUnderlying(Context ctx) throws IOException {
        requireAdmin.accept(ctx);
        ctx.multipartConfig().maxFileSize(25, io.javalin.config.SizeUnit.MB);
        ctx.multipartConfig().maxTotalRequestSize(26, io.javalin.config.SizeUnit.MB);
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) throw new IllegalArgumentException("CSV file is required");
        if (file.size() > 25L * 1024 * 1024) {
            throw new IllegalArgumentException("CSV file exceeds 25 MB");
        }
        String basisRaw = ctx.formParam("basis");
        UnderlyingCsvIngest.Basis basis;
        try {
            basis = UnderlyingCsvIngest.Basis.valueOf(
                    basisRaw == null ? "AUTO" : basisRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("basis must be AUTO, RAW, or ADJUSTED");
        }
        var result = marketDataMaintenance.writeChecked(() -> UnderlyingCsvIngest.run(
                file.content(), file.filename(), ctx.formParam("symbol"),
                ctx.formParam("sourceLabel"), basis, db, dataSyncState, clock,
                ownerId.apply(ctx)));
        invalidateHistoricalViews.run();
        ctx.json(result);
    }

    private void listJobs(Context ctx) {
        ctx.json(new ApiResponses.Jobs<>(dataJobs.recent(ownerId.apply(ctx), isAdmin.test(ctx), 30)));
    }

    private void requireJobAccess(Context ctx, String jobId) {
        if (isAdmin.test(ctx)) return;
        if (Objects.equals(dataJobs.ownerOf(jobId), ownerId.apply(ctx))) return;
        throw new ResourceNotFoundException("no such job");
    }

    private void getJob(Context ctx) {
        String id = ctx.pathParam("id");
        requireJobAccess(ctx, id);
        var view = dataJobs.get(id);
        if (view.job() == null) throw new ResourceNotFoundException("no such job");
        ctx.json(new ApiResponses.Job<>(view.job(), view.items()));
    }

    private void startJob(Context ctx) {
        JobRequest body = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, JobRequest.class));
        if (privilegedDataJobKind(body.kind())) requireAdmin.accept(ctx);
        ctx.json(dataJobs.start(body.kind(), body.params(), ownerId.apply(ctx)));
    }

    private void cancelJob(Context ctx) {
        String id = ctx.pathParam("id");
        requireJobAccess(ctx, id);
        if (privilegedDataJobKind(dataJobs.kindOf(id))) requireAdmin.accept(ctx);
        dataJobs.cancel(id);
        ctx.json(new ApiResponses.Ok(true));
    }

    private void retryJob(Context ctx) {
        String id = ctx.pathParam("id");
        requireJobAccess(ctx, id);
        if (privilegedDataJobKind(dataJobs.kindOf(id))) requireAdmin.accept(ctx);
        ctx.json(dataJobs.retry(id, ownerId.apply(ctx)));
    }

    static boolean privilegedDataJobKind(String kind) {
        return "import_options_csv".equalsIgnoreCase(kind)
                || "sync_underlying".equalsIgnoreCase(kind);
    }

    private void activateDataset(Context ctx) {
        ActiveDatasetRequest body = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, ActiveDatasetRequest.class));
        String owner = ownerId.apply(ctx);
        String activeMarket = activeWorld.apply(ctx);
        if (!DatasetService.OBSERVED.equals(body.id())
                && !"observed".equals(activeMarket) && !"demo".equals(activeMarket)) {
            throw new IllegalStateException("You are inside a simulated market session — return to the "
                    + "baseline market before activating a scenario dataset (they are separate worlds).");
        }
        marketDataMaintenance.write(() -> datasets.setActive(body.id(), owner));
        String nowActive = datasets.activeId(owner);
        ctx.json(new ApiResponses.DatasetActivation(true, nowActive,
                !DatasetService.OBSERVED.equals(nowActive)));
    }

    private void deleteDataset(Context ctx) {
        marketDataMaintenance.write(() -> datasets.delete(ctx.pathParam("id"), ownerId.apply(ctx)));
        ctx.json(new ApiResponses.Ok(true));
    }

    private void reset(Context ctx) {
        requireAdmin.accept(ctx);
        DataResetRequest body = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, DataResetRequest.class));
        if (!Boolean.TRUE.equals(body.confirm())) {
            ctx.status(400).json(new ApiResponses.ErrorBody(
                    "confirm_required", "Reset requires confirm:true"));
            return;
        }
        DataResetService.Tier tier = DataResetService.parseTier(body.tier());
        var result = dataReset.reset(tier);
        if (tier == DataResetService.Tier.PAPER || tier == DataResetService.Tier.EVERYTHING) {
            simSessions.clearResident();
            worldTransitions.resetAfterDataReset(ownerId.apply(ctx));
        } else {
            datasets.invalidateActiveCache();
            market.invalidateAll();
        }
        invalidateHistoricalViews.run();
        try {
            audit.log(null, null, "DATA_RESET", "WARN",
                    Map.of("tier", result.tier(), "areas", result.areasCleared(),
                            "maintenanceStatus", result.maintenanceStatus(),
                            "warnings", result.warnings()));
        } catch (Exception ignored) {
            // Reset has committed; audit is best-effort by design.
        }
        ctx.json(result);
    }

    private static List<String> normalizeSymbols(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isBlank()).map(s -> s.toUpperCase(Locale.ROOT))
                .filter(s -> s.matches("[A-Z0-9.^_-]{1,20}"))
                .distinct().limit(120).toList();
    }

    private static ApiResponses.DataSource source(String name, String covers, boolean enabled,
                                                   String license, String hint) {
        return new ApiResponses.DataSource(name, covers, enabled, license, hint);
    }
}
