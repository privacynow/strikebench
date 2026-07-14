package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.DatasetService;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.sim.SimulationSessions;
import io.liftandshift.strikebench.util.EventBus;
import io.liftandshift.strikebench.util.OwnerScope;
import io.liftandshift.strikebench.util.ResourceNotFoundException;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * The one state transition for market lanes. A target universe is hydrated before persistence;
 * world and dataset selectors commit together; caches, revisions, and owner-scoped events follow
 * the same result. Missing saved sessions are repaired through this contract instead of being
 * interpreted as Observed on one request.
 */
public final class WorldTransitionService {
    public record Current(String world, long revision, String epoch) {}

    public record Result(String world, boolean datasetReset, Object universe,
                         long revision, String epoch) {}

    public record FinishResult(boolean ok, boolean worldReset, String world,
                               Boolean datasetReset, Long revision, String epoch,
                               Object universe) {
        public static FinishResult unchanged() {
            return new FinishResult(true, false, null, null, null, null, null);
        }

        public static FinishResult reset(Result result) {
            return new FinishResult(true, true, result.world(), result.datasetReset(),
                    result.revision(), result.epoch(), result.universe());
        }
    }

    private final AppConfig config;
    private final Clock clock;
    private final Db db;
    private final DatasetService datasets;
    private final MarketDataService market;
    private final SimulationSessions sessions;
    private final EventBus events;
    private final BiFunction<String, String, Object> universeResolver;
    private final String epoch;
    private final AtomicLong revision = new AtomicLong();

    public WorldTransitionService(AppConfig config, Clock clock, Db db, DatasetService datasets,
                                  MarketDataService market, SimulationSessions sessions,
                                  EventBus events,
                                  BiFunction<String, String, Object> universeResolver,
                                  String epoch) {
        this.config = config;
        this.clock = clock;
        this.db = db;
        this.datasets = datasets;
        this.market = market;
        this.sessions = sessions;
        this.events = events;
        this.universeResolver = universeResolver;
        this.epoch = epoch;
    }

    public Current current(String rawOwner) {
        return new Current(active(rawOwner), revision.get(), epoch);
    }

    public String baseline() {
        return config.fixturesOnly() ? "demo" : "observed";
    }

    public String active(String rawOwner) {
        String owner = OwnerScope.id(rawOwner);
        String saved = read(owner);
        String fallback = baseline();
        if (saved == null || saved.isBlank()) return fallback;
        if (config.fixturesOnly() && "observed".equals(saved)) {
            return repair(owner, saved, fallback);
        }
        if (!"observed".equals(saved) && !"demo".equals(saved)
                && sessions.getOrRestore(saved, owner).isEmpty()) {
            return repair(owner, saved, fallback);
        }
        return saved;
    }

    public Result transition(String requestedWorld, String rawOwner) {
        return transition(requestedWorld, rawOwner, false, false);
    }

    /** Re-establishes and announces the baseline after a destructive reset removed selectors. */
    public Result resetAfterDataReset(String rawOwner) {
        datasets.invalidateActiveCache();
        return transition(baseline(), rawOwner, true, true);
    }

    public FinishResult afterFinish(boolean wasActive, String rawOwner) {
        return wasActive ? FinishResult.reset(transition(baseline(), rawOwner)) : FinishResult.unchanged();
    }

    private Result transition(String requestedWorld, String rawOwner, boolean forceDatasetEvent,
                              boolean globalEvent) {
        String owner = OwnerScope.id(rawOwner);
        String world = normalize(requestedWorld);
        validateTarget(world, owner);

        // Resolve first. No selector changes if target hydration fails.
        Object universe = universeResolver.apply(world, owner);
        boolean datasetReset = !DatasetService.OBSERVED.equals(datasets.activeId(owner));
        persist(owner, world, datasetReset, null);
        return publish(owner, world, universe, datasetReset, forceDatasetEvent, globalEvent);
    }

    private String repair(String owner, String expected, String fallback) {
        // Repair follows the same hydrate-before-commit and dataset-isolation rules as an explicit
        // transition. The compare-and-set prevents an old request from overwriting a newer choice.
        Object universe = universeResolver.apply(fallback, owner);
        boolean datasetReset = !DatasetService.OBSERVED.equals(datasets.activeId(owner));
        boolean changed = persist(owner, fallback, datasetReset, expected);
        if (!changed) {
            String current = read(owner);
            return current == null || current.isBlank() ? fallback : current;
        }
        publish(owner, fallback, universe, datasetReset, false, false);
        return fallback;
    }

    private String normalize(String requestedWorld) {
        return requestedWorld == null || requestedWorld.isBlank() ? "observed" : requestedWorld;
    }

    private void validateTarget(String world, String owner) {
        if (config.fixturesOnly() && "observed".equals(world)) {
            throw new IllegalStateException("Observed market is unavailable in this explicit demo build");
        }
        if (!"observed".equals(world) && !"demo".equals(world)) {
            sessions.ensureReady(world, owner);
            sessions.getOrRestore(world, owner)
                    .orElseThrow(() -> new ResourceNotFoundException("no such simulated session: " + world));
        }
    }

    /**
     * @param expectedWorld null for an unconditional upsert; otherwise compare-and-set repair
     * @return true when the selector was committed
     */
    private boolean persist(String owner, String world, boolean datasetReset, String expectedWorld) {
        String now = clock.instant().toString();
        return db.tx(connection -> {
            int changed;
            if (expectedWorld == null) {
                Db.execOn(connection, "INSERT INTO settings(k,v,updated_at) VALUES (?,?,?) "
                                + "ON CONFLICT (k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at",
                        worldKey(owner), world, now);
                changed = 1;
            } else {
                changed = Db.execOn(connection,
                        "UPDATE settings SET v=?,updated_at=? WHERE k=? AND v=?",
                        world, now, worldKey(owner), expectedWorld);
            }
            if (changed > 0 && datasetReset) {
                Db.execOn(connection, "INSERT INTO settings(k,v,updated_at) VALUES (?,?,?) "
                                + "ON CONFLICT (k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at",
                        datasetKey(owner), DatasetService.OBSERVED, now);
            }
            return changed > 0;
        });
    }

    private Result publish(String owner, String world, Object universe, boolean datasetReset,
                           boolean forceDatasetEvent, boolean globalEvent) {
        if (datasetReset) datasets.invalidateActiveCache();
        market.invalidateAll();
        long next = revision.incrementAndGet();
        if (datasetReset || forceDatasetEvent) {
            Map<String, Object> datasetEvent = new LinkedHashMap<>();
            datasetEvent.put("active", DatasetService.OBSERVED);
            if (!globalEvent) datasetEvent.put("user", owner);
            events.publish("dataset.selected", datasetEvent);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("world", world);
        if (!globalEvent) event.put("user", owner);
        event.put("revision", next);
        event.put("epoch", epoch);
        event.put("universe", universe);
        events.publish("world.selected", event);
        return new Result(world, datasetReset, universe, next, epoch);
    }

    private String read(String owner) {
        var rows = db.query("SELECT v FROM settings WHERE k=?", row -> row.str("v"), worldKey(owner));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String worldKey(String owner) {
        return "active_world:" + OwnerScope.id(owner);
    }

    private static String datasetKey(String owner) {
        return "active_dataset:" + OwnerScope.id(owner);
    }
}
