package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.DatasetService;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.SettingsStore;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.sim.SimulationSessions;
import io.liftandshift.strikebench.util.EventBus;
import io.liftandshift.strikebench.util.OwnerScope;
import io.liftandshift.strikebench.util.ResourceNotFoundException;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * The one state transition for market lanes. A target universe is hydrated before persistence;
 * world and dataset selectors commit together; caches, revisions, and owner-scoped events follow
 * the same result. Missing saved sessions are repaired through this contract instead of being
 * interpreted as Observed on one request.
 */
public final class WorldTransitionService {
    public record RepairNotice(String id, String previousWorld, String world,
                               String reason, String message) {}

    public record Current(String world, long revision, String epoch, RepairNotice repair) {}

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
    private final Map<String, String> activeByOwner = new ConcurrentHashMap<>();
    private final Map<String, RepairNotice> pendingRepairs = new ConcurrentHashMap<>();

    private record RepairContext(String previousWorld, String reason, String message) {}

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
        String owner = OwnerScope.id(rawOwner);
        String world = active(owner);
        return new Current(world, revision.get(), epoch, pendingRepairs.remove(owner));
    }

    public String baseline() {
        return config.fixturesOnly() ? "demo" : "observed";
    }

    public String active(String rawOwner) {
        String owner = OwnerScope.id(rawOwner);
        String saved = read(owner);
        String fallback = baseline();
        if (saved == null || saved.isBlank()) {
            activeByOwner.put(owner, fallback);
            return fallback;
        }
        if (config.fixturesOnly() && "observed".equals(saved)) {
            return repair(owner, saved, fallback, "OBSERVED_UNAVAILABLE_IN_DEMO_BUILD");
        }
        if (io.liftandshift.strikebench.market.MarketLane.isSimulatedWorld(saved)
                && sessions.getOrRestore(saved, owner).isEmpty()) {
            return repair(owner, saved, fallback, "SAVED_SCENARIO_UNAVAILABLE");
        }
        activeByOwner.put(owner, saved);
        return saved;
    }

    /**
     * Hot-path view for the shared market broadcaster. Explicit transitions and every ordinary
     * request reconcile this cache from durable state; frames can then avoid a settings query on
     * every tick without making the cache authoritative for request handling.
     */
    public String activeCached(String rawOwner) {
        String owner = OwnerScope.id(rawOwner);
        String cached = activeByOwner.get(owner);
        return cached == null ? active(owner) : cached;
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
        activeByOwner.put(owner, world);
        pendingRepairs.remove(owner);
        return publish(owner, world, universe, datasetReset, forceDatasetEvent, globalEvent, null);
    }

    private String repair(String owner, String expected, String fallback, String reason) {
        // Repair follows the same hydrate-before-commit and dataset-isolation rules as an explicit
        // transition. The compare-and-set prevents an old request from overwriting a newer choice.
        Object universe = universeResolver.apply(fallback, owner);
        boolean datasetReset = !DatasetService.OBSERVED.equals(datasets.activeId(owner));
        boolean changed = persist(owner, fallback, datasetReset, expected);
        if (!changed) {
            String current = read(owner);
            String resolved = current == null || current.isBlank() ? fallback : current;
            activeByOwner.put(owner, resolved);
            return resolved;
        }
        activeByOwner.put(owner, fallback);
        String baselineLabel = "demo".equals(fallback) ? "Demo baseline" : "observed market";
        String message = "SAVED_SCENARIO_UNAVAILABLE".equals(reason)
                ? "Your saved simulated market " + expected + " is no longer available. StrikeBench returned "
                    + "you to the " + baselineLabel + "; no Plan or accounting records were rewritten."
                : "Observed market is unavailable in this explicit Demo build. StrikeBench opened the Demo baseline instead.";
        publish(owner, fallback, universe, datasetReset, false, false,
                new RepairContext(expected, reason, message));
        return fallback;
    }

    private String normalize(String requestedWorld) {
        return requestedWorld == null || requestedWorld.isBlank() ? "observed" : requestedWorld;
    }

    private void validateTarget(String world, String owner) {
        if (config.fixturesOnly() && "observed".equals(world)) {
            throw new IllegalStateException("Observed market is unavailable in this explicit demo build");
        }
        if (io.liftandshift.strikebench.market.MarketLane.isSimulatedWorld(world)) {
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
        java.time.Instant now = clock.instant(); // one instant reused across all writes below
        return db.tx(connection -> {
            int changed;
            if (expectedWorld == null) {
                SettingsStore.upsertOn(connection, worldKey(owner), world, now);
                changed = 1;
            } else {
                changed = SettingsStore.casOn(connection, worldKey(owner), expectedWorld, world, now);
            }
            if (changed > 0 && datasetReset) {
                SettingsStore.upsertOn(connection, datasetKey(owner), DatasetService.OBSERVED, now);
            }
            return changed > 0;
        });
    }

    private Result publish(String owner, String world, Object universe, boolean datasetReset,
                           boolean forceDatasetEvent, boolean globalEvent, RepairContext repair) {
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
        if (repair != null) {
            RepairNotice notice = new RepairNotice(epoch + ":" + next, repair.previousWorld(), world,
                    repair.reason(), repair.message());
            event.put("repair", notice);
            pendingRepairs.put(owner, notice);
        }
        events.publish("world.selected", event);
        return new Result(world, datasetReset, universe, next, epoch);
    }

    private String read(String owner) {
        return SettingsStore.read(db, worldKey(owner)).orElse(null);
    }

    private static String worldKey(String owner) {
        return "active_world:" + OwnerScope.id(owner);
    }

    private static String datasetKey(String owner) {
        return "active_dataset:" + OwnerScope.id(owner);
    }
}
