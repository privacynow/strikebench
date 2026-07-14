package io.liftandshift.strikebench.market.sim;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.util.EventBus;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.OwnerScope;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;
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
    /** Room for a full curated sector + positions + benchmarks; simulating spots is cheap —
     *  anchor resolution and calibration are the bounded parts (tiered in the creator). */
    public static final int MAX_SYMBOLS = 120;
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
    private final java.util.Set<String> preparingWorlds = ConcurrentHashMap.newKeySet();

    public SimulationSessions(Db db, EventBus events) {
        this.db = db;
        this.events = events;
    }

    /** ApiServer.create assigns the pool AFTER the constructor runs — same late wiring as setEvents. */
    public void attachDb(Db db) { this.db = db; }

    private static String owner(String userId) { return OwnerScope.id(userId); }

    /** Persisted checkpoint: display state + the authoritative replay coordinates. */
    private record Checkpoint(long quantum, String simTime, double speed, boolean running) {}

    public synchronized SimulatedWorld create(SimulatedWorld.Config raw, String userId) {
        return createAtomic(raw, userId, null, null, null, false).world();
    }

    public record Created(SimulatedWorld world, String accountId) {}

    @FunctionalInterface
    public interface SessionHook {
        void afterCreate(Connection c, String worldId, String accountId) throws SQLException;
    }

    @FunctionalInterface
    public interface FinishHook {
        void beforeFinish(Connection c, String worldId, SimulatedWorld world) throws SQLException;
    }

    /** F1: capacity is checked BEFORE any anchor/provider work — never after seconds of it. */
    public void ensureCapacity(String userId) { enforceActiveCap(userId); }

    /**
     * F2 ATOMIC CREATE: the session row (config + durable anchors) and the simulation account
     * commit in ONE transaction; the world is admitted to memory only afterwards. A failure
     * anywhere leaves NOTHING behind — no half-created world a client retry could double.
     */
    public synchronized Created createAtomic(SimulatedWorld.Config raw, String userId, String anchorsJson,
                                             String accountName,
                                             io.liftandshift.strikebench.paper.AccountService accounts,
                                             boolean preparing) {
        return createAtomic(raw, userId, anchorsJson, accountName, accounts, preparing, null, null);
    }

    /** Exact Plan rehearsal creation. The world, replay source, isolated account and Plan link
     * commit together; an acknowledged rehearsal can never exist in only half of those places. */
    public synchronized Created createReplayAtomic(SimulatedWorld.Config raw, String userId,
                                                    String anchorsJson, String accountName,
                                                    io.liftandshift.strikebench.paper.AccountService accounts,
                                                    SimulatedWorld.ReplaySource replay,
                                                    SessionHook hook) {
        if (replay == null) throw new IllegalArgumentException("replay source is required");
        return createAtomic(raw, userId, anchorsJson, accountName, accounts, false, replay, hook);
    }

    private Created createAtomic(SimulatedWorld.Config raw, String userId, String anchorsJson,
                                 String accountName,
                                 io.liftandshift.strikebench.paper.AccountService accounts,
                                 boolean preparing, SimulatedWorld.ReplaySource replay,
                                 SessionHook hook) {
        if (raw.symbolBetas().size() > MAX_SYMBOLS) {
            throw new IllegalArgumentException("at most " + MAX_SYMBOLS + " symbols per simulated session");
        }
        enforceActiveCap(userId);
        // World ids are SERVER-GENERATED only — a client-chosen id would be a forgeable capability.
        String id = Ids.newId("simw");
        // Rebuild the server-owned identity while preserving every calibrated input.
        SimulatedWorld.Config cfg = new SimulatedWorld.Config(id, raw.name(), raw.symbolBetas(),
                raw.startSpots() == null ? Map.of() : raw.startSpots(), raw.scenario(), raw.volAnnual(),
                raw.seed(), raw.startSimTime(), raw.speed(), raw.symbolVols(), raw.symbolIvs());
        SimulatedWorld w = new SimulatedWorld(cfg, replay); // validate BEFORE any write
        String[] acctId = new String[1];
        db.tx(c -> {
            OwnerScope.ensure(c, userId);
            Db.execOn(c, "INSERT INTO sim_session(id,name,user_id,config,status,model_version,anchors) "
                            + "VALUES (?,?,?,?::jsonb,?,?,?::jsonb)",
                    id, cfg.name() == null ? id : cfg.name(), owner(userId), Json.write(cfg),
                    preparing ? "PREPARING" : "CREATED", SimulatedWorld.MODEL_VERSION, anchorsJson);
            if (accounts != null) {
                acctId[0] = accounts.createForWorldOn(c, id,
                        accountName == null ? "Simulation account" : accountName);
            }
            if (replay != null) {
                Db.execOn(c, "INSERT INTO sim_replay_source(sim_session_id,plan_id,ensemble_id,fingerprint," +
                                "path_index,selection_kind,symbol,model_version,n_steps,step_seconds,rate_annual," +
                                "spot_path,iv_path) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        id, replay.planId(), replay.ensembleId(), replay.fingerprint(), replay.pathIndex(),
                        replay.selection(), replay.symbol(), replay.modelVersion(), replay.spotPath().length - 1,
                        replay.stepSeconds(), replay.rateAnnual(), encodeVector(replay.spotPath()),
                        encodeVector(replay.ivPath()));
            }
            if (hook != null) hook.afterCreate(c, id, acctId[0]);
            return null;
        });
        if (preparing) preparingWorlds.add(id);
        admit(id, w, owner(userId));
        return new Created(w, acctId[0]);
    }

    /**
     * F1 ASYNC RESOLUTION SEAM: background anchor/calibration work may ENRICH a world that has
     * not started ticking (quantum 0, paused) by replacing it wholesale — config, anchors, and
     * the resident instance. Once a world has stepped, its config is immutable (replay identity).
     * Returns false (with no changes) when the world already moved.
     */
    public synchronized boolean replaceUnstarted(String worldId, String userId,
                                                 SimulatedWorld.Config newCfg, String anchorsJson) {
        SimulatedWorld cur = get(worldId, userId).orElse(null);
        if (cur == null || cur.replaySource() != null || cur.running() || cur.ticks() > 0) return false;
        SimulatedWorld.Config cfg = new SimulatedWorld.Config(worldId, newCfg.name(), newCfg.symbolBetas(),
                newCfg.startSpots() == null ? Map.of() : newCfg.startSpots(), newCfg.scenario(),
                newCfg.volAnnual(), newCfg.seed(), newCfg.startSimTime(), newCfg.speed(),
                newCfg.symbolVols(), newCfg.symbolIvs());
        SimulatedWorld w = new SimulatedWorld(cfg);
        db.exec("UPDATE sim_session SET config=?::jsonb, anchors=?::jsonb, status='CREATED' WHERE id=?",
                Json.write(cfg), anchorsJson, worldId);
        worlds.put(worldId, w);
        preparingWorlds.remove(worldId);
        if (events != null) {
            events.publish("world.resolving", Map.of("world", worldId, "user", owner(userId),
                    "state", "complete"));
        }
        return true;
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
        lastHint.remove(id);
        lastCheckpoint.remove(id);
        ScheduledFuture<?> f = loops.remove(id);
        if (f != null) f.cancel(false);
    }

    /**
     * A global Paper/Everything reset deletes every persisted simulation session and account.
     * Cancel the matching resident loops as the in-memory half of that same lifecycle change;
     * otherwise a deleted world can keep ticking until process restart.
     */
    public synchronized void clearResident() {
        new ArrayList<>(worlds.keySet()).forEach(this::evict);
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

    /** F8: the full durable anchor/provenance document for one owned session. */
    public Map<String, Object> anchors(String worldId, String userId) {
        var rows = db.query("SELECT anchors::text a FROM sim_session WHERE id=? AND user_id=?",
                r -> r.str("a"), worldId, owner(userId));
        if (rows.isEmpty()) throw new io.liftandshift.strikebench.util.ResourceNotFoundException("no such simulated session: " + worldId);
        String a = rows.getFirst();
        if (a == null) return Map.of("anchors", List.of(), "excluded", List.of(), "pending", List.of());
        return Json.read(a, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
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
        StoredWorld stored = db.tx(c -> {
            var rows = Db.queryOn(c, "SELECT config::text c,status,state::text st FROM sim_session "
                            + "WHERE id=? AND user_id=? AND status<>'FINISHED' FOR SHARE",
                    r -> new StoredWorld(r.str("c"), r.str("status"), r.str("st"), List.of()),
                    worldId, owner(userId));
            if (rows.isEmpty()) return null;
            StoredWorld row = rows.getFirst();
            return new StoredWorld(row.config(), row.status(), row.state(), loadEvents(c, worldId));
        });
        if (stored == null) return java.util.Optional.empty();
        SimulatedWorld restored = new SimulatedWorld(Json.read(stored.config(), SimulatedWorld.Config.class),
                loadReplaySource(worldId));
        Checkpoint cp = stored.state() == null ? null : Json.read(stored.state(), Checkpoint.class);
        restored.replayTo(cp == null ? 0 : cp.quantum(), stored.events());
        if (cp != null) {
            restored.setSpeedSilently(cp.speed());
        }
        admit(worldId, restored, owner(userId));
        // A session the DB says is RUNNING resumes ticking — restarts must not freeze the clock.
        if ("RUNNING".equals(stored.status())) start(worldId, userId);
        return java.util.Optional.of(restored);
    }

    public synchronized void start(String worldId, String userId) {
        ensureReady(worldId, userId);
        SimulatedWorld w = require(worldId, userId);
        if (w.replayComplete()) throw new IllegalStateException("This exact Plan rehearsal has reached the end of its stored path.");
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
                if (completeReplayIfNeeded(id, owner, ww)) return;
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
        ensureReady(worldId, userId);
        SimulatedWorld w = require(worldId, userId);
        w.pause();
        persistOrThrow(worldId, w);
        db.exec("UPDATE sim_session SET status='PAUSED' WHERE id=?", worldId);
    }

    /** Step = exactly ONE quantum (30 sim-seconds): the button must always move the world
     *  visibly — at real-time speed a plain tick advances less than a quantum. */
    public void step(String worldId, String userId) {
        ensureReady(worldId, userId);
        SimulatedWorld w = require(worldId, userId);
        if (w.replayComplete()) throw new IllegalStateException("This exact Plan rehearsal has reached the end of its stored path.");
        w.stepQuanta(1);
        persistOrThrow(worldId, w);
        completeReplayIfNeeded(worldId, owner(userId), w);
        hint(worldId, userId, w); // a user stepping expects every screen to move NOW, not on the next loop hint
    }

    public void setSpeed(String worldId, String userId, double speed) {
        ensureReady(worldId, userId);
        SimulatedWorld w = require(worldId, userId);
        w.setSpeed(speed);
        persistOrThrow(worldId, w);
    }

    public void injectMove(String worldId, String userId, String symbol, double pct) {
        ensureReady(worldId, userId);
        SimulatedWorld w = require(worldId, userId);
        w.injectMove(symbol, pct);
        persistOrThrow(worldId, w);
        hint(worldId, userId, w);
    }

    public void injectVol(String worldId, String userId, double points) {
        ensureReady(worldId, userId);
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
    public void finish(String worldId, String userId) { finish(worldId, userId, null); }

    public void finish(String worldId, String userId, FinishHook hook) {
        SimulatedWorld w = require(worldId, userId);
        w.pause();
        Checkpoint cp = new Checkpoint(w.ticks(), w.simTime().toString(), w.speed(), false);
        db.tx(c -> {
            Db.execOn(c, "UPDATE sim_session SET state=?::jsonb,status='FINISHED', "
                            + "finished_at=now() WHERE id=?",
                    Json.write(cp), worldId);
            persistEvents(c, worldId, w.eventLog());
            if (hook != null) hook.beforeFinish(c, worldId, w);
            return null;
        });
        preparingWorlds.remove(worldId);
        evict(worldId);
    }

    /**
     * USER-TRIGGERED mutations persist or FAIL — an inject/pause/speed that returns success
     * without durable state silently forks the replayable record (release blocker #2). Only the
     * background tick loop's periodic checkpoint is best-effort (persistQuietly).
     */
    private void persistOrThrow(String worldId, SimulatedWorld w) {
        Checkpoint cp = new Checkpoint(w.ticks(), w.simTime().toString(), w.speed(), w.running());
        db.tx(c -> {
            if (Db.execOn(c, "UPDATE sim_session SET state=?::jsonb WHERE id=?", Json.write(cp), worldId) != 1) {
                throw new io.liftandshift.strikebench.util.ResourceNotFoundException("no such simulated session: " + worldId);
            }
            persistEvents(c, worldId, w.eventLog());
            return null;
        });
    }

    private void persistQuietly(String worldId, SimulatedWorld w) {
        try { persistOrThrow(worldId, w); }
        catch (RuntimeException e) { /* the next periodic checkpoint retries */ }
    }

    private boolean completeReplayIfNeeded(String worldId, String userId, SimulatedWorld world) {
        if (!world.replayComplete()) return false;
        world.pause();
        persistOrThrow(worldId, world);
        db.exec("UPDATE sim_session SET status='PAUSED' WHERE id=? AND status<>'FINISHED'", worldId);
        if (events != null) {
            events.publish("world.rehearsal.complete", Map.of("world", worldId, "user", userId,
                    "simTime", world.simTime().toString(), "ticks", world.ticks()));
            events.publish("world.control", Map.of("world", worldId, "user", userId, "running", false));
        }
        return true;
    }

    /** A world cannot be entered or mutated until its promised symbols and calibration are final. */
    public void ensureReady(String worldId, String userId) {
        var rows = db.query("SELECT status FROM sim_session WHERE id=? AND user_id=?",
                r -> r.str("status"), worldId, owner(userId));
        if (rows.isEmpty()) throw new io.liftandshift.strikebench.util.ResourceNotFoundException("no such simulated session: " + worldId);
        String status = rows.getFirst();
        if ("PREPARING".equals(status)) {
            throw new IllegalStateException("This simulated market is still preparing its symbols and volatility. Wait for READY before entering or starting it.");
        }
        if ("FAILED".equals(status)) {
            throw new IllegalStateException("This simulated market could not finish preparing. Review its anchors, then finish it and create a new session.");
        }
        if ("FINISHED".equals(status)) {
            throw new IllegalStateException("This simulated market is finished and cannot be entered or changed.");
        }
    }

    public void preparationFailed(String worldId, String userId, String anchorsJson) {
        db.exec("UPDATE sim_session SET status='FAILED', anchors=?::jsonb WHERE id=? AND user_id=? AND status='PREPARING'",
                anchorsJson, worldId, owner(userId));
        preparingWorlds.remove(worldId);
        if (events != null) events.publish("world.resolving", Map.of(
                "world", worldId, "user", owner(userId), "state", "failed"));
    }

    /** All of this owner's sessions — FINISHED rows included (the report must stay reachable). */
    public synchronized List<Map<String, Object>> list(String userId) {
        // A PREPARING row without a resolver in this process means the process restarted during
        // provider work. Its partial config must never masquerade as a world that may still finish.
        for (String id : db.query("SELECT id FROM sim_session WHERE user_id=? AND status='PREPARING'",
                r -> r.str("id"), owner(userId))) {
            if (!preparingWorlds.contains(id)) {
                db.exec("UPDATE sim_session SET status='FAILED' WHERE id=? AND status='PREPARING'", id);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        db.query("SELECT s.id, s.name, s.status, s.config::text c, s.model_version, s.created_at::text ca, "
                        + "s.state::text st,s.anchors::text an,(SELECT count(*) FROM sim_session_event e "
                        + "WHERE e.sim_session_id=s.id) event_count,rs.plan_id,rs.ensemble_id," +
                        "rs.fingerprint,rs.path_index,rs.selection_kind,rs.symbol replay_symbol,rs.model_version replay_model " +
                        "FROM sim_session s LEFT JOIN sim_replay_source rs ON rs.sim_session_id=s.id "
                        + "WHERE s.user_id=? ORDER BY (s.status='FINISHED'), s.created_at DESC",
                r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", r.str("id"));
                    m.put("name", r.str("name"));
                    m.put("status", r.str("status"));
                    m.put("config", Json.parse(r.str("c")));
                    m.put("modelVersion", r.str("model_version"));
                    m.put("createdAt", r.str("ca"));
                    if (r.str("plan_id") != null) {
                        m.put("rehearsal", Map.of("planId", r.str("plan_id"), "ensembleId", r.str("ensemble_id"),
                                "fingerprint", r.str("fingerprint"), "pathIndex", r.intv("path_index"),
                                "selection", r.str("selection_kind"), "symbol", r.str("replay_symbol"),
                                "modelVersion", r.str("replay_model")));
                    }
                    m.put("eventCount", r.lng("event_count"));
                    // F8: anchor COVERAGE rides on every row (counts, not the full provenance —
                    // the detail endpoint serves that), so the UI can show what this world is
                    // anchored to before it starts and throughout.
                    String an = r.str("an");
                    if (an != null) {
                        var doc = Json.parse(an);
                        Map<String, Object> cov = new java.util.LinkedHashMap<>();
                        cov.put("anchored", doc.has("anchors") ? doc.get("anchors").size() : 0);
                        cov.put("excluded", doc.has("excluded") ? doc.get("excluded").size() : 0);
                        cov.put("pending", doc.has("pending") ? doc.get("pending").size() : 0);
                        if (doc.has("note")) cov.put("note", doc.get("note").asText());
                        m.put("anchorSummary", cov);
                    }
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
        ReplayRecordRow row = db.tx(c -> {
            var rows = Db.queryOn(c, "SELECT s.model_version,rs.plan_id,rs.ensemble_id,rs.fingerprint," +
                            "rs.path_index,rs.selection_kind,rs.symbol,rs.model_version replay_model,rs.rate_annual " +
                            "FROM sim_session s LEFT JOIN sim_replay_source rs ON rs.sim_session_id=s.id " +
                            "WHERE s.id=? AND s.user_id=? FOR SHARE OF s", r -> new ReplayRecordRow(
                            r.str("model_version"), r.str("plan_id"), r.str("ensemble_id"), r.str("fingerprint"),
                            r.lngOrNull("path_index"), r.str("selection_kind"), r.str("symbol"),
                            r.str("replay_model"), r.dblOrNull("rate_annual"), List.of()), worldId, owner(userId));
            if (rows.isEmpty()) return null;
            ReplayRecordRow head = rows.getFirst();
            return new ReplayRecordRow(head.modelVersion(), head.planId(), head.ensembleId(), head.fingerprint(),
                    head.pathIndex(), head.selection(), head.symbol(), head.replayModel(), head.rateAnnual(),
                    loadEvents(c, worldId));
        });
        if (row == null) return Map.of();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("modelVersion", row.modelVersion());
        m.put("events", row.events());
        if (row.planId() != null) {
            Map<String, Object> replay = new java.util.LinkedHashMap<>();
            replay.put("planId", row.planId()); replay.put("ensembleId", row.ensembleId());
            replay.put("fingerprint", row.fingerprint()); replay.put("pathIndex", row.pathIndex());
            replay.put("selection", row.selection()); replay.put("symbol", row.symbol());
            replay.put("modelVersion", row.replayModel()); replay.put("rateAnnual", row.rateAnnual());
            m.put("rehearsal", replay);
        }
        return m;
    }

    private SimulatedWorld.ReplaySource loadReplaySource(String worldId) {
        var rows = db.query("SELECT plan_id,ensemble_id,fingerprint,path_index,selection_kind,symbol," +
                        "model_version,n_steps,step_seconds,rate_annual,spot_path,iv_path " +
                        "FROM sim_replay_source WHERE sim_session_id=?", r -> new ReplayRow(
                        r.str("plan_id"), r.str("ensemble_id"), r.str("fingerprint"), r.intv("path_index"),
                        r.str("selection_kind"), r.str("symbol"), r.str("model_version"), r.intv("n_steps"),
                        r.dbl("step_seconds"), r.dbl("rate_annual"), r.bytes("spot_path"), r.bytes("iv_path")), worldId);
        if (rows.isEmpty()) return null;
        ReplayRow r = rows.getFirst();
        return new SimulatedWorld.ReplaySource(r.planId(), r.ensembleId(), r.fingerprint(), r.pathIndex(),
                r.selection(), r.symbol(), r.modelVersion(), decodeVector(r.spotPath(), r.steps() + 1),
                decodeVector(r.ivPath(), r.steps() + 1), r.stepSeconds(), r.rateAnnual());
    }

    private static byte[] encodeVector(double[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(values.length, Double.BYTES));
        for (double value : values) buffer.putDouble(value);
        return buffer.array();
    }

    private static double[] decodeVector(byte[] bytes, int size) {
        if (bytes == null || bytes.length != Math.multiplyExact(size, Double.BYTES)) {
            throw new IllegalStateException("Stored rehearsal vector has the wrong length");
        }
        double[] values = new double[size];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (int i = 0; i < size; i++) values[i] = buffer.getDouble();
        return values;
    }

    private record ReplayRow(String planId, String ensembleId, String fingerprint, int pathIndex,
                             String selection, String symbol, String modelVersion, int steps,
                             double stepSeconds, double rateAnnual, byte[] spotPath, byte[] ivPath) {}
    private record StoredWorld(String config, String status, String state,
                               List<SimulatedWorld.WorldEvent> events) {}
    private record ReplayRecordRow(String modelVersion, String planId, String ensembleId,
                                   String fingerprint, Long pathIndex, String selection, String symbol,
                                   String replayModel, Double rateAnnual,
                                   List<SimulatedWorld.WorldEvent> events) {}

    private static List<SimulatedWorld.WorldEvent> loadEvents(Connection c, String worldId) throws SQLException {
        return Db.queryOn(c, "SELECT quantum,kind,symbol,value FROM sim_session_event "
                        + "WHERE sim_session_id=? ORDER BY event_index",
                r -> new SimulatedWorld.WorldEvent(r.lng("quantum"), r.str("kind"), r.str("symbol"), r.dbl("value")),
                worldId);
    }

    private static void persistEvents(Connection c, String worldId,
                                      List<SimulatedWorld.WorldEvent> current) throws SQLException {
        List<SimulatedWorld.WorldEvent> stored = loadEvents(c, worldId);
        if (stored.size() > current.size() || !stored.equals(current.subList(0, stored.size()))) {
            throw new IllegalStateException("The simulated-session event log is append-only");
        }
        for (int i = stored.size(); i < current.size(); i++) {
            SimulatedWorld.WorldEvent event = current.get(i);
            Db.execOn(c, "INSERT INTO sim_session_event(sim_session_id,event_index,quantum,kind,symbol,value) "
                            + "VALUES(?,?,?,?,?,?)",
                    worldId, i, event.quantum(), event.kind(), event.symbol(), event.value());
        }
    }

    private SimulatedWorld require(String worldId, String userId) {
        return getOrRestore(worldId, userId)
                .orElseThrow(() -> new io.liftandshift.strikebench.util.ResourceNotFoundException("no such simulated session: " + worldId));
    }
}
