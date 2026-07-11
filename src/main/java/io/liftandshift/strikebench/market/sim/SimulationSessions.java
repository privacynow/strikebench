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
 * The simulated-market session registry. Security and durability rules (adversarial review):
 * <ul>
 *   <li><b>Ownership is checked on EVERY lookup</b>, including memory-resident worlds — a world id
 *       is never a capability. World ids are server-generated only.</li>
 *   <li><b>world.tick events carry the owner</b>, so the SSE stream scopes them per user.</li>
 *   <li><b>Caps are per-owner and enforced where running begins</b> (create AND start, atomically);
 *       resident paused worlds are bounded with LRU eviction (they restore deterministically).</li>
 *   <li><b>Sessions are replayable</b>: every injected event and speed change is persisted at its
 *       path quantum; restore rebuilds the world from config and REPLAYS to the persisted quantum,
 *       so a JVM restart resumes the exact world its trades were placed in. RUNNING sessions
 *       resume ticking on restore. FINISHED sessions are terminal — never resurrected.</li>
 * </ul>
 * The OBSERVED engine is never touched — observed is always the fail-safe lane.
 */
public final class SimulationSessions {

    private static final int MAX_ACTIVE_PER_OWNER = 3;
    private static final int MAX_RESIDENT_WORLDS = 16;
    private static final int MAX_SYMBOLS = 40;
    private static final long TICK_MS = 1000;
    private static final long CHECKPOINT_EVERY_MS = 30_000; // real time — slow worlds checkpoint too

    private volatile Db db; // late-wired: ApiServer owns the pool and attaches it after construction
    private final EventBus events;
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sim-market-loop");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, SimulatedWorld> worlds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> owners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastTouch = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> loops = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastHint = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastCheckpoint = new ConcurrentHashMap<>();

    public SimulationSessions(Db db, EventBus events) {
        this.db = db;
        this.events = events;
    }

    /** ApiServer.create assigns the pool AFTER the constructor runs — same late wiring as setEvents. */
    public void attachDb(Db db) { this.db = db; }

    private static String owner(String userId) { return userId == null || userId.isBlank() ? "local" : userId; }

    /** Persisted checkpoint: display state + the authoritative replay coordinates. */
    private record Checkpoint(long quantum, String simTime, double speed, boolean running) {}

    public synchronized SimulatedWorld create(SimulatedWorld.Config raw, String userId) {
        if (raw.symbolBetas().size() > MAX_SYMBOLS) {
            throw new IllegalArgumentException("at most " + MAX_SYMBOLS + " symbols per simulated session");
        }
        enforceActiveCap(userId);
        // World ids are SERVER-GENERATED only — a client-chosen id would be a forgeable capability.
        String id = Ids.newId("simw");
        // Rebuild with the FULL ctor: the 9-arg compat ctor would silently drop the per-symbol
        // calibration maps (the record-adapter defect class pinned in CP-1).
        SimulatedWorld.Config cfg = new SimulatedWorld.Config(id, raw.name(), raw.symbolBetas(),
                raw.startSpots() == null ? Map.of() : raw.startSpots(), raw.scenario(), raw.volAnnual(),
                raw.seed(), raw.startSimTime(), raw.speed(), raw.symbolVols(), raw.symbolIvs());
        SimulatedWorld w = new SimulatedWorld(cfg);
        admit(id, w, owner(userId));
        db.exec("INSERT INTO sim_session(id,name,user_id,config,status,model_version,events) "
                        + "VALUES (?,?,?,?::jsonb,'CREATED',?,'[]'::jsonb)",
                id, cfg.name() == null ? id : cfg.name(), owner(userId), Json.write(cfg),
                SimulatedWorld.MODEL_VERSION);
        return w;
    }

    private void admit(String id, SimulatedWorld w, String owner) {
        worlds.put(id, w);
        owners.put(id, owner);
        lastTouch.put(id, System.nanoTime());
        // Bound resident worlds: evict the least-recently-touched PAUSED world (replay restores it).
        if (worlds.size() > MAX_RESIDENT_WORLDS) {
            worlds.entrySet().stream()
                    .filter(e -> !e.getValue().running() && !e.getKey().equals(id))
                    .min(java.util.Comparator.comparingLong(e -> lastTouch.getOrDefault(e.getKey(), 0L)))
                    .ifPresent(e -> {
                        // Evict ONLY after a durable checkpoint — dropping a world whose latest
                        // state failed to persist would silently rewind it on restore.
                        try {
                            persistOrThrow(e.getKey(), e.getValue());
                            evict(e.getKey());
                        } catch (RuntimeException ex) { /* keep resident; retry next admit */ }
                    });
        }
    }

    private void evict(String id) {
        worlds.remove(id);
        owners.remove(id);
        lastTouch.remove(id);
        ScheduledFuture<?> f = loops.remove(id);
        if (f != null) f.cancel(false);
    }

    private void enforceActiveCap(String userId) {
        String o = owner(userId);
        long mem = worlds.entrySet().stream()
                .filter(e -> o.equals(owners.get(e.getKey())) && e.getValue().running()).count();
        // Persisted RUNNING rows not currently resident count too (post-restart truth).
        var ids = new java.util.HashSet<>(db.query(
                "SELECT id FROM sim_session WHERE user_id=? AND status='RUNNING'", r -> r.str("id"), o));
        worlds.keySet().forEach(ids::remove);
        if (mem + ids.size() >= MAX_ACTIVE_PER_OWNER) {
            throw new IllegalStateException("at most " + MAX_ACTIVE_PER_OWNER
                    + " simulated sessions may run at once — pause or finish one of yours");
        }
    }

    /** Owner-checked lookup — memory-resident worlds are checked exactly like restored ones. */
    public java.util.Optional<SimulatedWorld> get(String worldId, String userId) {
        SimulatedWorld w = worlds.get(worldId);
        if (w != null) {
            if (!owner(userId).equals(owners.get(worldId))) return java.util.Optional.empty();
            lastTouch.put(worldId, System.nanoTime());
            return java.util.Optional.of(w);
        }
        return java.util.Optional.empty();
    }

    /**
     * Ownerless resolver for the MARKET ROUTER only (quotes/chains/clock by world id). Safe because
     * the world id reaching the router comes from the caller's OWN validated active_world setting —
     * every API-facing path resolves through the owner-checked variants first.
     */
    public java.util.Optional<SimulatedWorld> resolveForData(String worldId) {
        return java.util.Optional.ofNullable(worlds.get(worldId));
    }

    /** Restores a persisted session by REPLAY: same config + same event log = the same world. */
    public synchronized java.util.Optional<SimulatedWorld> getOrRestore(String worldId, String userId) {
        var existing = get(worldId, userId);
        if (existing.isPresent()) return existing;
        var rows = db.query("SELECT config::text c, status, events::text e, state::text st "
                        + "FROM sim_session WHERE id=? AND user_id=? AND status <> 'FINISHED'",
                r -> new String[]{r.str("c"), r.str("status"), r.str("e"), r.str("st")},
                worldId, owner(userId));
        if (rows.isEmpty()) return java.util.Optional.empty();
        String[] row = rows.getFirst();
        SimulatedWorld restored = new SimulatedWorld(Json.read(row[0], SimulatedWorld.Config.class));
        List<SimulatedWorld.WorldEvent> log = row[2] == null ? List.of()
                : Json.read(row[2], new com.fasterxml.jackson.core.type.TypeReference<List<SimulatedWorld.WorldEvent>>() {});
        if (row[3] != null) {
            Checkpoint cp = Json.read(row[3], Checkpoint.class);
            restored.replayTo(cp.quantum(), log);
            restored.setSpeedSilently(cp.speed());
        }
        admit(worldId, restored, owner(userId));
        // A session the DB says is RUNNING resumes ticking — restarts must not freeze the clock.
        if ("RUNNING".equals(row[1])) start(worldId, userId);
        return java.util.Optional.of(restored);
    }

    public synchronized void start(String worldId, String userId) {
        SimulatedWorld w = require(worldId, userId);
        if (!w.running()) {
            // The cap binds where running BEGINS, not just at create.
            String o = owner(userId);
            long running = worlds.entrySet().stream()
                    .filter(e -> o.equals(owners.get(e.getKey())) && e.getValue().running()).count();
            if (running >= MAX_ACTIVE_PER_OWNER) {
                throw new IllegalStateException("at most " + MAX_ACTIVE_PER_OWNER
                        + " simulated sessions may run at once — pause or finish one of yours");
            }
        }
        w.start();
        db.exec("UPDATE sim_session SET status='RUNNING' WHERE id=?", worldId);
        String owner = owner(userId);
        loops.computeIfAbsent(worldId, id -> loop.scheduleAtFixedRate(() -> {
            try {
                SimulatedWorld ww = worlds.get(id);
                if (ww == null || !ww.running()) return;
                ww.tick();
                long now = System.currentTimeMillis();
                Long last = lastHint.get(id);
                if (last == null || now - last > 4000) { // throttled hint; GETs stay truth
                    lastHint.put(id, now);
                    // The OWNER key scopes the event per user on the SSE stream.
                    events.publish("world.tick", Map.of("world", id, "user", owner,
                            "simTime", ww.simTime().toString(), "ticks", ww.ticks()));
                }
                Long cp = lastCheckpoint.get(id);
                if (cp == null || now - cp >= CHECKPOINT_EVERY_MS) {
                    lastCheckpoint.put(id, now);
                    persistQuietly(id, ww);
                }
            } catch (RuntimeException e) { /* one bad tick never kills the loop */ }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS));
    }

    public void pause(String worldId, String userId) {
        SimulatedWorld w = require(worldId, userId);
        w.pause();
        persistOrThrow(worldId, w);
        db.exec("UPDATE sim_session SET status='PAUSED' WHERE id=?", worldId);
    }

    /** Step = exactly ONE quantum (30 sim-seconds): the button must always move the world
     *  visibly — at real-time speed a plain tick advances less than a quantum. */
    public void step(String worldId, String userId) {
        SimulatedWorld w = require(worldId, userId);
        w.stepQuanta(1);
        persistOrThrow(worldId, w);
        hint(worldId, userId, w); // a user stepping expects every screen to move NOW, not on the next loop hint
    }

    public void setSpeed(String worldId, String userId, double speed) {
        SimulatedWorld w = require(worldId, userId);
        w.setSpeed(speed);
        persistOrThrow(worldId, w);
    }

    public void injectMove(String worldId, String userId, String symbol, double pct) {
        SimulatedWorld w = require(worldId, userId);
        w.injectMove(symbol, pct);
        persistOrThrow(worldId, w);
        hint(worldId, userId, w);
    }

    public void injectVol(String worldId, String userId, double points) {
        SimulatedWorld w = require(worldId, userId);
        w.injectVolShift(points);
        persistOrThrow(worldId, w);
        hint(worldId, userId, w);
    }

    /** Immediate (unthrottled) world.tick hint for user-triggered moves — same shape as the loop's. */
    private void hint(String worldId, String userId, SimulatedWorld w) {
        if (events == null) return;
        events.publish("world.tick", Map.of("world", worldId, "user", owner(userId),
                "simTime", w.simTime().toString(), "ticks", w.ticks()));
    }

    /** Finish persists state+events AND the terminal status BEFORE evicting — a finish that
     *  lost the latest events while acknowledging success was release blocker #2. */
    public void finish(String worldId, String userId) {
        SimulatedWorld w = require(worldId, userId);
        w.pause();
        Checkpoint cp = new Checkpoint(w.ticks(), w.simTime().toString(), w.speed(), false);
        db.exec("UPDATE sim_session SET state=?::jsonb, events=?::jsonb, status='FINISHED', "
                        + "finished_at=now() WHERE id=?",
                Json.write(cp), Json.write(w.eventLog()), worldId); // one atomic statement; throws on failure
        evict(worldId);
    }

    /**
     * USER-TRIGGERED mutations persist or FAIL — an inject/pause/speed that returns success
     * without durable state silently forks the replayable record (release blocker #2). Only the
     * background tick loop's periodic checkpoint is best-effort (persistQuietly).
     */
    private void persistOrThrow(String worldId, SimulatedWorld w) {
        Checkpoint cp = new Checkpoint(w.ticks(), w.simTime().toString(), w.speed(), w.running());
        db.exec("UPDATE sim_session SET state=?::jsonb, events=?::jsonb WHERE id=?",
                Json.write(cp), Json.write(w.eventLog()), worldId);
    }

    private void persistQuietly(String worldId, SimulatedWorld w) {
        try { persistOrThrow(worldId, w); }
        catch (RuntimeException e) { /* the next periodic checkpoint retries */ }
    }

    /** All of this owner's sessions — FINISHED rows included (the report must stay reachable). */
    public List<Map<String, Object>> list(String userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        db.query("SELECT id, name, status, config::text c, model_version, created_at::text ca, "
                        + "state::text st, events::text ev FROM sim_session "
                        + "WHERE user_id=? ORDER BY (status='FINISHED'), created_at DESC",
                r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", r.str("id"));
                    m.put("name", r.str("name"));
                    m.put("status", r.str("status"));
                    m.put("config", Json.parse(r.str("c")));
                    m.put("modelVersion", r.str("model_version"));
                    m.put("createdAt", r.str("ca"));
                    String ev = r.str("ev");
                    m.put("eventCount", ev == null ? 0 : Json.parse(ev).size());
                    SimulatedWorld w = worlds.get(r.str("id"));
                    if (w != null) {
                        m.put("running", w.running());
                        m.put("simTime", w.simTime().toString());
                        m.put("speed", w.speed());
                        m.put("ticks", w.ticks());
                    } else if (r.str("st") != null) {
                        // Not resident: the persisted checkpoint keeps the row honest, never blank.
                        Checkpoint cp = Json.read(r.str("st"), Checkpoint.class);
                        m.put("running", false);
                        m.put("simTime", cp.simTime());
                        m.put("speed", cp.speed());
                        m.put("ticks", cp.quantum());
                    }
                    out.add(m);
                    return null;
                }, owner(userId));
        return out;
    }

    /** The event log + model version for the session report (owner-checked). */
    public Map<String, Object> replayRecord(String worldId, String userId) {
        var rows = db.query("SELECT events::text e, model_version FROM sim_session WHERE id=? AND user_id=?",
                r -> new String[]{r.str("e"), r.str("model_version")}, worldId, owner(userId));
        if (rows.isEmpty()) return Map.of();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("modelVersion", rows.getFirst()[1]);
        m.put("events", rows.getFirst()[0] == null ? List.of() : Json.parse(rows.getFirst()[0]));
        return m;
    }

    private SimulatedWorld require(String worldId, String userId) {
        return getOrRestore(worldId, userId)
                .orElseThrow(() -> new java.util.NoSuchElementException("no such simulated session: " + worldId));
    }
}
