package io.liftandshift.strikebench.market.sim;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.util.EventBus;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The simulated-market session registry: creates reproducible worlds (persisted config, V11),
 * runs each active session's deterministic tick loop on ONE shared scheduler, enforces resource
 * limits, and publishes throttled per-world hints on the event bus. The OBSERVED engine is
 * never touched — real and simulated engines coexist, and observed is always the fail-safe.
 */
public final class SimulationSessions {

    private static final int MAX_ACTIVE = 3;
    private static final int MAX_SYMBOLS = 40;
    private static final long TICK_MS = 1000;

    private volatile Db db; // late-wired: ApiServer owns the pool and attaches it after construction
    private final EventBus events;
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sim-market-loop");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, SimulatedWorld> worlds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> loops = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastHint = new ConcurrentHashMap<>();

    public SimulationSessions(Db db, EventBus events) {
        this.db = db;
        this.events = events;
    }

    /** ApiServer.create assigns the pool AFTER the constructor runs — same late wiring as setEvents. */
    public void attachDb(Db db) { this.db = db; }

    private static String owner(String userId) { return userId == null || userId.isBlank() ? "local" : userId; }

    public SimulatedWorld create(SimulatedWorld.Config raw, String userId) {
        if (raw.symbolBetas() == null || raw.symbolBetas().isEmpty()) {
            throw new IllegalArgumentException("a simulated market needs at least one symbol");
        }
        if (raw.symbolBetas().size() > MAX_SYMBOLS) {
            throw new IllegalArgumentException("at most " + MAX_SYMBOLS + " symbols per simulated session");
        }
        long active = worlds.values().stream().filter(SimulatedWorld::running).count();
        if (active >= MAX_ACTIVE) {
            throw new IllegalStateException("at most " + MAX_ACTIVE + " simulated sessions may run at once — pause or finish one");
        }
        String id = raw.worldId() != null && !raw.worldId().isBlank() ? raw.worldId() : Ids.newId("simw");
        SimulatedWorld.Config cfg = new SimulatedWorld.Config(id, raw.name(), raw.symbolBetas(),
                raw.startSpots() == null ? Map.of() : raw.startSpots(), raw.scenario(), raw.volAnnual(),
                raw.seed(), raw.startSimTime(), raw.speed());
        SimulatedWorld w = new SimulatedWorld(cfg);
        worlds.put(id, w);
        db.exec("INSERT INTO sim_session(id,name,user_id,config,status) VALUES (?,?,?,?::jsonb,'CREATED') "
                        + "ON CONFLICT (id) DO UPDATE SET config=excluded.config",
                id, cfg.name() == null ? id : cfg.name(), owner(userId), Json.write(cfg));
        return w;
    }

    public java.util.Optional<SimulatedWorld> get(String worldId) {
        return java.util.Optional.ofNullable(worlds.get(worldId));
    }

    /** Restores a persisted-but-not-loaded session (deterministic: same config = same world). */
    public java.util.Optional<SimulatedWorld> getOrRestore(String worldId, String userId) {
        SimulatedWorld w = worlds.get(worldId);
        if (w != null) return java.util.Optional.of(w);
        var rows = db.query("SELECT config::text c FROM sim_session WHERE id=? AND user_id=?",
                r -> r.str("c"), worldId, owner(userId));
        if (rows.isEmpty()) return java.util.Optional.empty();
        SimulatedWorld restored = new SimulatedWorld(Json.read(rows.getFirst(), SimulatedWorld.Config.class));
        worlds.put(worldId, restored);
        return java.util.Optional.of(restored);
    }

    public void start(String worldId, String userId) {
        SimulatedWorld w = require(worldId, userId);
        w.start();
        db.exec("UPDATE sim_session SET status='RUNNING' WHERE id=?", worldId);
        loops.computeIfAbsent(worldId, id -> loop.scheduleAtFixedRate(() -> {
            try {
                SimulatedWorld ww = worlds.get(id);
                if (ww == null || !ww.running()) return;
                ww.tick();
                long now = System.currentTimeMillis();
                Long last = lastHint.get(id);
                if (last == null || now - last > 4000) { // throttled hint; GETs stay truth
                    lastHint.put(id, now);
                    events.publish("world.tick", Map.of("world", id, "simTime", ww.simTime().toString(),
                            "ticks", ww.ticks()));
                }
            } catch (RuntimeException e) { /* one bad tick never kills the loop */ }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS));
    }

    public void pause(String worldId, String userId) {
        require(worldId, userId).pause();
        db.exec("UPDATE sim_session SET status='PAUSED' WHERE id=?", worldId);
    }

    public void step(String worldId, String userId) { require(worldId, userId).tick(); }

    public void setSpeed(String worldId, String userId, double speed) { require(worldId, userId).setSpeed(speed); }

    public void injectMove(String worldId, String userId, String symbol, double pct) {
        require(worldId, userId).injectMove(symbol, pct);
    }

    public void injectVol(String worldId, String userId, double points) {
        require(worldId, userId).injectVolShift(points);
    }

    public void finish(String worldId, String userId) {
        SimulatedWorld w = require(worldId, userId);
        w.pause();
        ScheduledFuture<?> f = loops.remove(worldId);
        if (f != null) f.cancel(false);
        worlds.remove(worldId);
        db.exec("UPDATE sim_session SET status='FINISHED', finished_at=now() WHERE id=?", worldId);
    }

    public List<Map<String, Object>> list(String userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        db.query("SELECT id, name, status, config::text c, created_at::text ca FROM sim_session "
                        + "WHERE user_id=? AND status <> 'FINISHED' ORDER BY created_at DESC",
                r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", r.str("id"));
                    m.put("name", r.str("name"));
                    m.put("status", r.str("status"));
                    m.put("config", Json.parse(r.str("c")));
                    m.put("createdAt", r.str("ca"));
                    SimulatedWorld w = worlds.get(r.str("id"));
                    if (w != null) {
                        m.put("running", w.running());
                        m.put("simTime", w.simTime().toString());
                        m.put("speed", w.speed());
                        m.put("ticks", w.ticks());
                    }
                    out.add(m);
                    return null;
                }, owner(userId));
        return out;
    }

    private SimulatedWorld require(String worldId, String userId) {
        return getOrRestore(worldId, userId)
                .orElseThrow(() -> new java.util.NoSuchElementException("no such simulated session: " + worldId));
    }
}
