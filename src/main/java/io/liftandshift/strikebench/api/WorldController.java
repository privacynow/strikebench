package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.auth.AuthService;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataEngine;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.sim.SimulationSessions;
import io.liftandshift.strikebench.paper.AccountService;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.plan.PlanRehearsalService;
import io.liftandshift.strikebench.plan.PlanService;
import io.liftandshift.strikebench.util.EventBus;
import io.liftandshift.strikebench.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Owns market-world selection and the complete simulated-session lifecycle. */
final class WorldController {
    private static final Logger log = LoggerFactory.getLogger(WorldController.class);

    private final AppConfig cfg;
    private final Clock clock;
    private final MarketDataService market;
    private final MarketDataEngine marketEngine;
    private final SimulationSessions simSessions;
    private final AccountService accounts;
    private final PositionsService positions;
    private final TradeService trades;
    private final AuthService auth;
    private final EventBus events;
    private final WorldTransitionService worldTransitions;
    private final PlanRehearsalService planRehearsals;
    private final PlanService planService;
    private final Function<Context, String> ownerId;
    private final Function<Context, String> activeWorld;

    WorldController(AppConfig cfg, Clock clock, MarketDataService market,
                    MarketDataEngine marketEngine, SimulationSessions simSessions,
                    AccountService accounts, PositionsService positions, TradeService trades,
                    AuthService auth, EventBus events, WorldTransitionService worldTransitions,
                    PlanRehearsalService planRehearsals, PlanService planService,
                    Function<Context, String> ownerId,
                    Function<Context, String> activeWorld) {
        this.cfg = cfg;
        this.clock = clock;
        this.market = market;
        this.marketEngine = marketEngine;
        this.simSessions = simSessions;
        this.accounts = accounts;
        this.positions = positions;
        this.trades = trades;
        this.auth = auth;
        this.events = events;
        this.worldTransitions = worldTransitions;
        this.planRehearsals = planRehearsals;
        this.planService = planService;
        this.ownerId = ownerId;
        this.activeWorld = activeWorld;
    }

    void register(JavalinConfig config) {
        WorldRoutes.register(config, new WorldRoutes.Handlers(
                ctx -> ctx.json(worldTransitions.current(ownerId.apply(ctx))),
                ctx -> {
                    var request = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, Map.class));
                    String world = String.valueOf(request.getOrDefault("world", "observed"));
                    ctx.json(worldTransitions.transition(world, ownerId.apply(ctx)));
                },
                ctx -> ctx.json(new ApiResponses.Sessions<>(simSessions.list(ownerId.apply(ctx)))),
                ctx -> ctx.json(simSessions.anchors(ctx.pathParam("id"), ownerId.apply(ctx))),
                this::simMarketCreate,
                ctx -> {
                    String id = ctx.pathParam("id");
                    String owner = ownerId.apply(ctx);
                    simSessions.start(id, owner);
                    events.publish("world.control", Map.of("world", id,
                            "user", owner == null ? "local" : owner, "running", true));
                    ctx.json(new ApiResponses.Running(true, true));
                },
                ctx -> {
                    String id = ctx.pathParam("id");
                    String owner = ownerId.apply(ctx);
                    simSessions.pause(id, owner);
                    events.publish("world.control", Map.of("world", id,
                            "user", owner == null ? "local" : owner, "running", false));
                    ctx.json(new ApiResponses.Running(true, false));
                },
                ctx -> {
                    simSessions.step(ctx.pathParam("id"), ownerId.apply(ctx));
                    ctx.json(new ApiResponses.Ok(true));
                },
                ctx -> {
                    var request = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, Map.class));
                    String id = ctx.pathParam("id");
                    String owner = ownerId.apply(ctx);
                    double speed = Double.parseDouble(String.valueOf(request.get("speed")));
                    simSessions.setSpeed(id, owner, speed);
                    events.publish("world.control", Map.of("world", id,
                            "user", owner == null ? "local" : owner, "speed", speed));
                    ctx.json(new ApiResponses.Speed(true, speed));
                },
                ctx -> {
                    var request = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, Map.class));
                    if (request.containsKey("movePct") && request.containsKey("symbol")) {
                        simSessions.injectMove(ctx.pathParam("id"), ownerId.apply(ctx),
                                String.valueOf(request.get("symbol")),
                                Double.parseDouble(String.valueOf(request.get("movePct"))));
                    }
                    if (request.containsKey("volShift")) {
                        simSessions.injectVol(ctx.pathParam("id"), ownerId.apply(ctx),
                                Double.parseDouble(String.valueOf(request.get("volShift"))));
                    }
                    ctx.json(new ApiResponses.Ok(true));
                },
                ctx -> {
                    String id = ctx.pathParam("id");
                    String owner = ownerId.apply(ctx);
                    boolean wasActive = id.equals(activeWorld.apply(ctx));
                    var rehearsalFinish = planRehearsals.finishHook(owner, id);
                    simSessions.finish(id, owner, (connection, worldId, world) -> {
                        if (rehearsalFinish != null) {
                            rehearsalFinish.beforeFinish(connection, worldId, world);
                        }
                        planService.closeFinishedWorldPlansOn(connection, owner, worldId);
                    });
                    ctx.json(worldTransitions.afterFinish(wasActive, owner));
                },
                this::simMarketReport));
    }

    public record SimMarketCreate(String name, java.util.Map<String, Double> symbols,
                                  java.util.Map<String, Double> spots, String scenario,
                                  Double volAnnual, Long seed, String startSimTime, Double speed,
                                  String sectorKey /* include this curated sector as the background tier */,
                                  Boolean includePositions /* default true: held symbols ride along */,
                                  Boolean allowFictional /* F3: $100 demo instruments only on explicit request */) {}

    private void simMarketCreate(Context ctx) {
        SimMarketCreate b = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, SimMarketCreate.class));
        String owner = ownerId.apply(ctx);
        String sourceWorld = activeWorld.apply(ctx);
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
        // A new session starts from the market the user is actually viewing. Demo and an
        // already-entered simulated world are local, deterministic sources; consulting the
        // Observed engine while the UI says Demo both delays creation and anchors to the wrong
        // market. Only an Observed source can require governed background provider work.
        boolean localData = cfg.fixturesOnly() || !"observed".equals(sourceWorld);
        java.util.Map<String, io.liftandshift.strikebench.market.MarketDataEngine.MarketSnapshot> snaps
                = new java.util.HashMap<>();
        if (localData) {
            // The active generated lane is already resident. Read it directly and never wait on
            // the unrelated Observed provider chain under a Demo/simulated Create button.
            for (String sym : all.keySet()) {
                if (spots.containsKey(sym)) continue;
                market.quote(sym, sourceWorld).ifPresent(quote ->
                        snaps.put(sym, snapshotOf(quote)));
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
            if (mark != null && mark.signum() > 0) {
                spots.put(sym, mark.doubleValue());
                String basis = anchorBasis(snap.freshness(), sourceWorld, "");
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
            } else if (!localData && isActive && curated.contains(sym)) {
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
        if (localData) {
            calibrateInto(all, active, spots, spotBasis, symVols, symIvs, calibrationBasis, anchors,
                    today, sourceWorld);
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
        boolean needsResolution = !localData && (!pending.isEmpty() || !active.isEmpty());
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

    private io.liftandshift.strikebench.market.MarketDataEngine.MarketSnapshot snapshotOf(
            io.liftandshift.strikebench.model.Quote quote) {
        return new io.liftandshift.strikebench.market.MarketDataEngine.MarketSnapshot(
                quote.symbol(), quote.description(), quote.mark(), quote.bid(), quote.ask(),
                quote.prevClose(), quote.optionable(), quote.markFreshness(), quote.source(),
                quote.asOfEpochMs(), clock.millis(), false, null);
    }

    private static String anchorBasis(io.liftandshift.strikebench.model.Freshness freshness,
                                      String sourceWorld, String suffix) {
        String base;
        if (freshness == io.liftandshift.strikebench.model.Freshness.REALTIME
                || freshness == io.liftandshift.strikebench.model.Freshness.DELAYED
                || freshness == io.liftandshift.strikebench.model.Freshness.EOD
                || freshness == io.liftandshift.strikebench.model.Freshness.STALE) {
            base = "anchored to the real market's last " + freshness.name().toLowerCase() + " price";
        } else if (freshness == io.liftandshift.strikebench.model.Freshness.SIMULATED
                || (sourceWorld != null && io.liftandshift.strikebench.market.MarketLane.isSimulatedWorld(sourceWorld))) {
            base = "anchored to the active simulated market's generated price";
        } else {
            base = "anchored to a built-in DEMO quote — not a live price";
        }
        return base + (suffix == null ? "" : suffix);
    }

    /** Per-symbol HV30 + chain-ATM-IV calibration for the ACTIVE tier (capped, basis disclosed). */
    private void calibrateInto(java.util.Map<String, Double> all, java.util.Map<String, Double> active,
                               java.util.Map<String, Double> spots, java.util.Map<String, String> spotBasis,
                               java.util.Map<String, Double> symVols, java.util.Map<String, Double> symIvs,
                               java.util.Map<String, String> calibrationBasis,
                               List<Map<String, Object>> anchors, java.time.LocalDate today,
                               String sourceWorld) {
        int calBudget = 12;
        for (String sym : active.keySet()) {
            if (!all.containsKey(sym) || !spots.containsKey(sym) || calBudget <= 0) continue;
            calBudget--;
            StringBuilder cal = new StringBuilder();
            try {
                var series = market.candleSeries(sym, today.minusDays(90), today, sourceWorld,
                        io.liftandshift.strikebench.db.AnalysisContext.OBSERVED);
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
                var exps = market.expirations(sym, sourceWorld);
                if (!exps.isEmpty()) {
                    var chain = market.chain(sym, exps.getFirst(), sourceWorld).orElse(null);
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
                    String basis = anchorBasis(snap.freshness(), "observed", " (resolved in background)");
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
                    java.time.LocalDate.now(clock), "observed");
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
                simSessions.recordLateAnchors(worldId, owner, Json.write(anchorDoc));
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
        var w = simSessions.getOrRestore(worldId, ownerId.apply(ctx))
                .orElseThrow(() -> new io.liftandshift.strikebench.util.ResourceNotFoundException("no such simulated session: " + worldId));
        var account = accounts.findForWorld(worldId);
        List<Map<String, Object>> tradeRows = new ArrayList<>();
        long realized = 0; int wins = 0, resolved = 0;
        // POP vs outcome: did higher-POP entries actually win more often? (decision quality,
        // separated from single-trade outcomes — one loss on a 70% trade is not a bad decision.)
        int hiPop = 0, hiPopWins = 0, loPop = 0, loPopWins = 0;
        if (account.isPresent()) {
            var page = trades.list(account.get().id(), null, 0, 200);
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
                var snap = io.liftandshift.strikebench.util.Json.parse(t.entrySnapshotJson());
                if (snap.hasNonNull("laneTime")) m.put("laneEntryTime", snap.get("laneTime").asText());
                // MAE/MFE from the trade's own mark history: how far it went against/for the
                // trader while open — the difference between a bad outcome and a bad decision.
                var excursion = trades.excursion(t.id());
                if (excursion.adverseCents() != null) {
                    m.put("maeCents", excursion.adverseCents());
                    m.put("mfeCents", excursion.favorableCents());
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
        ApiResponses.PopVsOutcome popVsOutcome = new ApiResponses.PopVsOutcome(hiPop,
                hiPop > 0 ? Math.round(100.0 * hiPopWins / hiPop) : null,
                loPop, loPop > 0 ? Math.round(100.0 * loPopWins / loPop) : null,
                "Entries with POP ≥ 50% vs below — with few trades this is noise, not a verdict.");
        // Replay record: model version + every injected event/speed change — WITHOUT this the
        // seed line below would overclaim (injections are not derivable from the seed).
        var replay = simSessions.replayRecord(worldId, ownerId.apply(ctx));
        int eventCount = replay.get("events") instanceof List<?> l ? l.size() : 0;
        String note;
        if (replay.get("rehearsal") instanceof Map<?, ?> source) {
            note = "This session replayed exact path " + (((Number) source.get("pathIndex")).intValue() + 1) + " ("
                    + String.valueOf(source.get("selection")).toLowerCase(Locale.ROOT) + ") from this Plan's saved futures. "
                    + "The exact source identity remains in the durable receipt. Prices and IV follow that stored realization; "
                    + "outcomes measure management decisions, not a forecast.";
        } else {
            note = "Every price in this world was generated (model " + replay.getOrDefault("modelVersion", "sim-1")
                    + ", seed " + w.config().seed() + ", scenario " + w.config().scenario() + ")"
                    + (eventCount > 0 ? " plus " + eventCount + " manually injected event" + (eventCount == 1 ? "" : "s")
                            + " listed below — replay needs the seed AND the event log" : "")
                    + " — outcomes measure DECISIONS, not the market.";
        }
        ctx.json(new ApiResponses.SimulationReport<>(worldId, w.config(), w.simTime().toString(), w.ticks(),
                tradeRows, resolved, resolved > 0 ? Math.round(100.0 * wins / resolved) : null,
                realized, popVsOutcome, String.valueOf(replay.getOrDefault("modelVersion", "sim-1")),
                replay.getOrDefault("events", List.of()), replay.get("rehearsal"), note));
    }
}
