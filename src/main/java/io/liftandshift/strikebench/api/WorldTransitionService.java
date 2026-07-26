package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.DatasetService;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.SettingsStore;
import io.liftandshift.strikebench.db.WorkspaceContext;
import io.liftandshift.strikebench.db.WorkspaceService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketLane;
import io.liftandshift.strikebench.market.sim.SimulationSessions;
import io.liftandshift.strikebench.util.EventBus;
import io.liftandshift.strikebench.util.OwnerScope;
import io.liftandshift.strikebench.util.ResourceNotFoundException;

import java.time.Clock;
import java.time.Instant;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

    public record Current(String world, long revision, String epoch, RepairNotice repair,
                          ApiResponses.Workspace workspace) {}

    public record Result(String world, boolean datasetReset, Object universe,
                         long revision, String epoch, ApiResponses.Workspace workspace) {}

    public record DatasetResult(boolean ok, String active, boolean scenarioMode,
                                String world, String marketLane, String accountId,
                                long revision, String epoch, ApiResponses.Workspace workspace) {}

    public record DatasetDeleteResult(boolean ok, boolean selectionChanged, String active,
                                      String world, String marketLane, String accountId,
                                      Long revision, String epoch,
                                      ApiResponses.Workspace workspace) {}

    public record FinishResult(boolean ok, boolean worldReset, String world,
                               Boolean datasetReset, Long revision, String epoch,
                               Object universe, ApiResponses.Workspace workspace) {
        public static FinishResult unchanged() {
            return new FinishResult(true, false, null, null, null, null, null, null);
        }

        public static FinishResult reset(Result result) {
            return new FinishResult(true, true, result.world(), result.datasetReset(),
                    result.revision(), result.epoch(), result.universe(), result.workspace());
        }
    }

    /**
     * Prepared baseline transition for a simulation finish. The selector/workspace writes run on
     * SimulationSessions' transaction; cache invalidation and events are deliberately impossible
     * until {@link #afterCommit()} is called after that transaction returns successfully.
     */
    public final class FinishTransition {
        private final String owner;
        private final String finishingWorld;
        private final String world;
        private final Object universe;
        private final WorkspaceContext.ActiveMarket target;
        private final AtomicReference<Persisted> committed = new AtomicReference<>();
        private final AtomicBoolean published = new AtomicBoolean();

        private FinishTransition(String owner, String finishingWorld, String world, Object universe,
                                 WorkspaceContext.ActiveMarket target) {
            this.owner = owner;
            this.finishingWorld = finishingWorld;
            this.world = world;
            this.universe = universe;
            this.target = target;
        }

        public void beforeFinish(Connection connection) throws SQLException {
            // Compare-and-set inside the SAME transaction that makes the session terminal. A
            // controller-side "was active" snapshot can become stale in either direction.
            committed.set(persistOn(connection, owner, world, target, finishingWorld));
        }

        public FinishResult afterCommit() {
            Persisted persisted = committed.get();
            if (persisted == null) {
                throw new IllegalStateException(
                        "simulation finish committed without its baseline market transition");
            }
            if (!published.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "simulation finish transition was already published");
            }
            if (!persisted.changed()) return FinishResult.unchanged();
            activeByOwner.put(owner, world);
            pendingRepairs.remove(owner);
            return FinishResult.reset(publish(owner, world, universe, persisted, false, null));
        }
    }

    private final AppConfig config;
    private final Clock clock;
    private final Db db;
    private final DatasetService datasets;
    private final MarketDataService market;
    private final SimulationSessions sessions;
    private final EventBus events;
    private final WorkspaceService workspace;
    private final BiFunction<String, String, Object> universeResolver;
    private final BiFunction<String, String, WorkspaceContext.ActiveMarket> targetMarketResolver;
    private final String epoch;
    /** Market identity revisions are owner-scoped; another user's click cannot invalidate mine. */
    private final Map<String, AtomicLong> revisionsByOwner = new ConcurrentHashMap<>();
    private final Map<String, String> activeByOwner = new ConcurrentHashMap<>();
    private record PendingRepair(RepairNotice notice, ApiResponses.Workspace workspace) {}
    private final Map<String, PendingRepair> pendingRepairs = new ConcurrentHashMap<>();

    private record RepairContext(String previousWorld, String reason, String message) {}

    public WorldTransitionService(AppConfig config, Clock clock, Db db, DatasetService datasets,
                                  MarketDataService market, SimulationSessions sessions,
                                  EventBus events, WorkspaceService workspace,
                                  BiFunction<String, String, Object> universeResolver,
                                  BiFunction<String, String, WorkspaceContext.ActiveMarket> targetMarketResolver,
                                  String epoch) {
        this.config = config;
        this.clock = clock;
        this.db = db;
        this.datasets = datasets;
        this.market = market;
        this.sessions = sessions;
        this.events = events;
        this.workspace = workspace;
        this.universeResolver = universeResolver;
        this.targetMarketResolver = targetMarketResolver;
        this.epoch = epoch;
    }

    public Current current(String rawOwner) {
        String owner = OwnerScope.id(rawOwner);
        MarketSnapshot snapshot = marketSnapshot(owner);
        ApiResponses.Workspace snapshotReceipt =
                ApiResponses.Workspace.from(snapshot.workspace().state(), snapshot.market());
        PendingRepair pending = pendingRepairs.remove(owner);
        if (pending != null) {
            ApiResponses.Workspace repairReceipt = pending.workspace();
            ApiResponses.Workspace receipt = sameWorkspaceSnapshot(repairReceipt, snapshotReceipt)
                    ? repairReceipt : snapshotReceipt;
            return new Current(snapshot.market().world(), currentRevision(owner),
                    epoch, pending.notice(), receipt);
        }
        return new Current(snapshot.market().world(), currentRevision(owner), epoch, null,
                snapshotReceipt);
    }

    private static boolean sameWorkspaceSnapshot(ApiResponses.Workspace left,
                                                 ApiResponses.Workspace right) {
        return left != null && right != null
                && left.rev() == right.rev()
                && Objects.equals(left.world(), right.world())
                && Objects.equals(left.datasetId(), right.datasetId())
                && Objects.equals(left.marketLane(), right.marketLane())
                && Objects.equals(left.accountId(), right.accountId());
    }

    /** Canonical identity for every workspace read/write: world + dataset + lane + account. */
    public WorkspaceContext.ActiveMarket activeMarket(String rawOwner) {
        return marketSnapshot(OwnerScope.id(rawOwner)).market();
    }

    private record MarketSnapshot(WorkspaceContext.ActiveMarket market,
                                  WorkspaceService.TransactionCommit workspace,
                                  DatasetService.ActiveSelection dataset) {}

    /**
     * Reads world, dataset, lane, account, and workspace under one owner lock. Account creation owns
     * an independent transaction, so it is resolved first and the durable world is then checked
     * under the lock; a concurrent transition causes a retry rather than a torn receipt.
     */
    private MarketSnapshot marketSnapshot(String owner) {
        for (int attempt = 0; attempt < 8; attempt++) {
            String candidateWorld = active(owner);
            WorkspaceContext.ActiveMarket resolvedAccount =
                    targetMarket(candidateWorld, owner, DatasetService.OBSERVED);
            Instant now = clock.instant();
            MarketSnapshot snapshot = db.tx(connection -> {
                OwnerScope.lock(connection, owner);
                String durableWorld = activeWorldOn(connection, owner, candidateWorld);
                if (!durableWorld.equals(candidateWorld)) return null;
                DatasetService.ActiveSelection selected =
                        datasets.resolveActiveOn(connection, owner, now);
                WorkspaceContext.ActiveMarket target = targetMarket(
                        durableWorld, owner, selected.activeId(), resolvedAccount.accountId());
                if (!targetAccountValidOn(connection, owner, target)) return null;
                WorkspaceService.TransactionCommit reconciled =
                        workspace.reconcileOn(connection, owner, target, now);
                return new MarketSnapshot(target, reconciled, selected);
            });
            if (snapshot == null) continue;
            activeByOwner.put(owner, snapshot.market().world());
            if (snapshot.dataset().repaired()) {
                datasets.invalidateActiveCache(owner);
                publishDataset(owner, new DatasetCommit(true, snapshot.market(),
                        snapshot.workspace(), snapshot.dataset().previousId(),
                        snapshot.dataset().repairReason()));
            } else {
                workspace.announceCommitted(owner, snapshot.workspace());
            }
            return snapshot;
        }
        throw new IllegalStateException(
                "the active market kept changing while its atomic identity was being read");
    }

    private long currentRevision(String owner) {
        AtomicLong value = revisionsByOwner.get(owner);
        return value == null ? 0L : value.get();
    }

    private long advanceRevision(String owner) {
        return revisionsByOwner.computeIfAbsent(owner, ignored -> new AtomicLong())
                .incrementAndGet();
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
        return transition(requestedWorld, rawOwner, false);
    }

    /** Owners whose persisted market/workspace identity must be reconciled after a global reset. */
    public List<String> affectedOwners(String requestingOwner) {
        Set<String> owners = new java.util.LinkedHashSet<>();
        owners.add(OwnerScope.id(requestingOwner));
        owners.addAll(db.query("""
                SELECT owner FROM (
                  SELECT user_id owner FROM workspace
                  UNION
                  SELECT substring(k from position(':' in k)+1) owner
                  FROM settings
                  WHERE k LIKE 'active_world:%' OR k LIKE 'active_dataset:%'
                ) affected
                WHERE owner IS NOT NULL AND owner <> ''
                """, row -> row.str("owner")));
        return List.copyOf(owners);
    }

    /**
     * A global reset reconciles each private workspace through an owner-scoped event. The final
     * broadcast is only an invalidation hint and deliberately contains no workspace, account, or
     * owner receipt.
     */
    public List<String> resetAfterDataReset(Collection<String> affectedOwners) {
        datasets.invalidateActiveCache();
        Collection<String> owners = affectedOwners == null ? List.of() : affectedOwners;
        List<String> warnings = new java.util.ArrayList<>();
        for (String owner : owners) {
            try {
                transition(baseline(), owner, true);
            } catch (RuntimeException failure) {
                warnings.add("Workspace market reconciliation needs a service restart for owner "
                        + OwnerScope.id(owner) + ": " + failure.getMessage());
            }
        }
        market.invalidateAll();
        events.publish("world.reset", Map.of(
                "scope", "ALL", "world", baseline(), "epoch", epoch));
        return List.copyOf(warnings);
    }

    /** MARKET_DATA reset changes only the dataset axis; simulated worlds remain selected. */
    public List<String> resetDatasetsAfterDataReset(Collection<String> affectedOwners) {
        datasets.invalidateActiveCache();
        Collection<String> owners = affectedOwners == null ? List.of() : affectedOwners;
        List<String> warnings = new java.util.ArrayList<>();
        for (String owner : owners) {
            try {
                activateDataset(DatasetService.OBSERVED, owner);
            } catch (RuntimeException failure) {
                warnings.add("Workspace dataset reconciliation needs a service restart for owner "
                        + OwnerScope.id(owner) + ": " + failure.getMessage());
            }
        }
        market.invalidateAll();
        events.publish("dataset.reset", Map.of(
                "scope", "ALL", "active", DatasetService.OBSERVED, "epoch", epoch));
        return List.copyOf(warnings);
    }

    public FinishTransition prepareFinish(String finishingWorld, String rawOwner) {
        String owner = OwnerScope.id(rawOwner);
        String expected = normalize(finishingWorld);
        if (!MarketLane.isSimulatedWorld(expected)) {
            throw new IllegalArgumentException(
                    "only a simulated market can use the terminal world transition");
        }
        String world = baseline();
        validateTarget(world, owner);
        Object universe = universeResolver.apply(world, owner);
        return new FinishTransition(owner, expected, world, universe,
                targetMarket(world, owner, DatasetService.OBSERVED));
    }

    /** One owner-scoped dataset switch and workspace generation. */
    public DatasetResult activateDataset(String id, String rawOwner) {
        String owner = OwnerScope.id(rawOwner);
        datasets.invalidateActiveCache(owner);
        String world = active(owner);
        if (!DatasetService.OBSERVED.equals(id) && MarketLane.isSimulatedWorld(world)) {
            throw new IllegalStateException("You are inside a simulated market session — return to the "
                    + "baseline market before activating a scenario dataset (they are separate worlds).");
        }
        WorkspaceContext.ActiveMarket account = targetMarket(
                world, owner, DatasetService.OBSERVED);
        Instant now = clock.instant();
        DatasetCommit commit = db.tx(connection -> {
            OwnerScope.lock(connection, owner);
            String durableWorld = activeWorldOn(connection, owner, world);
            if (!durableWorld.equals(world)) {
                throw new IllegalStateException("the active market moved while its dataset was changing");
            }
            DatasetService.SelectionMutation selected =
                    datasets.selectOn(connection, id, owner, now);
            WorkspaceContext.ActiveMarket target = targetMarket(
                    world, owner, selected.activeId(), account.accountId());
            requireTargetAccountOn(connection, owner, target);
            WorkspaceService.TransactionCommit workspaceCommit =
                    workspace.reconcileOn(connection, owner, target, now);
            return new DatasetCommit(selected.changed() || workspaceCommit.wrote(),
                    target, workspaceCommit, null, null);
        });
        datasets.invalidateActiveCache(owner);
        return publishDataset(owner, commit);
    }

    /** Dataset deletion and any active-selector fallback are one owner-scoped commit. */
    public DatasetDeleteResult deleteDataset(String id, String rawOwner) {
        String owner = OwnerScope.id(rawOwner);
        datasets.invalidateActiveCache(owner);
        String world = active(owner);
        WorkspaceContext.ActiveMarket account = targetMarket(
                world, owner, DatasetService.OBSERVED);
        Instant now = clock.instant();
        DatasetDeleteCommit commit = db.tx(connection -> {
            OwnerScope.lock(connection, owner);
            String durableWorld = activeWorldOn(connection, owner, world);
            if (!durableWorld.equals(world)) {
                throw new IllegalStateException("the active market moved while its dataset was deleted");
            }
            DatasetService.DeleteMutation deleted =
                    datasets.deleteOn(connection, id, owner, now);
            WorkspaceContext.ActiveMarket target = targetMarket(
                    world, owner, deleted.activeId(), account.accountId());
            requireTargetAccountOn(connection, owner, target);
            WorkspaceService.TransactionCommit workspaceCommit = deleted.selectionChanged()
                    ? workspace.reconcileOn(connection, owner, target, now) : null;
            return new DatasetDeleteCommit(deleted, target, workspaceCommit);
        });
        datasets.invalidateActiveCache(owner);
        if (!commit.deleted().selectionChanged()) {
            events.publish("dataset.deleted", Map.of(
                    "id", id, "active", commit.deleted().activeId(),
                    "user", owner, "epoch", epoch));
            return new DatasetDeleteResult(true, false, commit.deleted().activeId(),
                    commit.target().world(), commit.target().lane(), commit.target().accountId(),
                    null, epoch, null);
        }
        DatasetResult result = publishDataset(owner,
                new DatasetCommit(true, commit.target(), commit.workspace(), null, null));
        return new DatasetDeleteResult(true, true, result.active(), result.world(),
                result.marketLane(), result.accountId(), result.revision(), result.epoch(),
                result.workspace());
    }

    private record DatasetCommit(boolean changed, WorkspaceContext.ActiveMarket target,
                                 WorkspaceService.TransactionCommit workspace,
                                 String previousDataset, String repairReason) {}
    private record DatasetDeleteCommit(DatasetService.DeleteMutation deleted,
                                       WorkspaceContext.ActiveMarket target,
                                       WorkspaceService.TransactionCommit workspace) {}

    private Result transition(String requestedWorld, String rawOwner, boolean forceDatasetEvent) {
        String owner = OwnerScope.id(rawOwner);
        String world = normalize(requestedWorld);
        validateTarget(world, owner);

        // Resolve first. No selector changes if target hydration fails.
        Object universe = universeResolver.apply(world, owner);
        WorkspaceContext.ActiveMarket target =
                targetMarket(world, owner, DatasetService.OBSERVED);
        Persisted persisted = persist(owner, world, target, null);
        activeByOwner.put(owner, world);
        pendingRepairs.remove(owner);
        if (!persisted.changed()) {
            return new Result(world, false, universe, currentRevision(owner), epoch,
                    ApiResponses.Workspace.from(persisted.workspace().state(), target));
        }
        return publish(owner, world, universe, persisted, forceDatasetEvent, null);
    }

    private String repair(String owner, String expected, String fallback, String reason) {
        // Repair follows the same hydrate-before-commit and dataset-isolation rules as an explicit
        // transition. The compare-and-set prevents an old request from overwriting a newer choice.
        Object universe = universeResolver.apply(fallback, owner);
        WorkspaceContext.ActiveMarket target =
                targetMarket(fallback, owner, DatasetService.OBSERVED);
        Persisted persisted = persist(owner, fallback, target, expected);
        if (!persisted.changed()) {
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
        publish(owner, fallback, universe, persisted, false,
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

    private record Persisted(boolean changed, boolean datasetReset,
                             WorkspaceContext.ActiveMarket target,
                             WorkspaceService.TransactionCommit workspace) {}

    /** @param expectedWorld null for an unconditional upsert; otherwise compare-and-set repair */
    private Persisted persist(String owner, String world, WorkspaceContext.ActiveMarket target,
                              String expectedWorld) {
        return db.tx(connection -> persistOn(connection, owner, world, target, expectedWorld));
    }

    private Persisted persistOn(Connection connection, String owner, String world,
                                WorkspaceContext.ActiveMarket target, String expectedWorld)
            throws SQLException {
        Instant now = clock.instant();
        OwnerScope.lock(connection, owner);
        requireTargetAccountOn(connection, owner, target);
        if (MarketLane.isSimulatedWorld(world)) {
            // Same transaction/lock order as finish: owner first, terminal session row second.
            sessions.ensureReadyOn(connection, world, owner);
        }
        String currentWorld = SettingsStore
                .readOn(connection, SettingsStore.activeWorldKey(owner))
                .filter(value -> !value.isBlank()).orElse(null);
        boolean selectorChanged;
        if (expectedWorld == null) {
            selectorChanged = !world.equals(currentWorld);
            if (selectorChanged) {
                SettingsStore.upsertOn(connection, SettingsStore.activeWorldKey(owner), world, now);
            }
        } else {
            selectorChanged = SettingsStore.casOn(connection,
                    SettingsStore.activeWorldKey(owner), expectedWorld, world, now) == 1;
        }
        if (!selectorChanged && expectedWorld != null) {
            return new Persisted(false, false, target, null);
        }
        String selectedDataset = activeDatasetOn(connection, owner);
        boolean datasetReset = !DatasetService.OBSERVED.equals(selectedDataset);
        if (datasetReset) {
            SettingsStore.upsertOn(connection, SettingsStore.activeDatasetKey(owner),
                    DatasetService.OBSERVED, now);
        }
        WorkspaceService.TransactionCommit workspaceCommit =
                workspace.reconcileOn(connection, owner, target, now);
        return new Persisted(selectorChanged || datasetReset || workspaceCommit.wrote(),
                datasetReset, target, workspaceCommit);
    }

    private Result publish(String owner, String world, Object universe, Persisted persisted,
                           boolean forceDatasetEvent, RepairContext repair) {
        if (persisted.datasetReset()) datasets.invalidateActiveCache(owner);
        market.invalidateAll();
        long next = advanceRevision(owner);
        ApiResponses.Workspace workspaceReceipt = ApiResponses.Workspace.from(
                persisted.workspace().state(), persisted.target());
        if (persisted.datasetReset() || forceDatasetEvent) {
            Map<String, Object> datasetEvent = new LinkedHashMap<>();
            datasetEvent.put("active", DatasetService.OBSERVED);
            datasetEvent.put("user", owner);
            datasetEvent.put("world", persisted.target().world());
            datasetEvent.put("marketLane", persisted.target().lane());
            datasetEvent.put("accountId", persisted.target().accountId());
            datasetEvent.put("revision", next);
            datasetEvent.put("epoch", epoch);
            datasetEvent.put("workspace", workspaceReceipt);
            events.publish("dataset.selected", datasetEvent);
        }
        workspace.announceCommitted(owner, persisted.workspace());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("world", world);
        event.put("user", owner);
        event.put("revision", next);
        event.put("epoch", epoch);
        event.put("universe", universe);
        event.put("workspace", workspaceReceipt);
        if (repair != null) {
            RepairNotice notice = new RepairNotice(epoch + ":" + next, repair.previousWorld(), world,
                    repair.reason(), repair.message());
            event.put("repair", notice);
            pendingRepairs.put(owner, new PendingRepair(notice, workspaceReceipt));
        }
        events.publish("world.selected", event);
        return new Result(world, persisted.datasetReset(), universe, next, epoch, workspaceReceipt);
    }

    private DatasetResult publishDataset(String owner, DatasetCommit committed) {
        if (!committed.changed()) {
            ApiResponses.Workspace receipt = ApiResponses.Workspace.from(
                    committed.workspace().state(), committed.target());
            return new DatasetResult(true, committed.target().datasetId(),
                    !DatasetService.OBSERVED.equals(committed.target().datasetId()),
                    committed.target().world(), committed.target().lane(),
                    committed.target().accountId(), currentRevision(owner), epoch, receipt);
        }
        market.invalidateAll();
        long next = advanceRevision(owner);
        workspace.announceCommitted(owner, committed.workspace());
        ApiResponses.Workspace workspaceReceipt = ApiResponses.Workspace.from(
                committed.workspace().state(), committed.target());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("active", committed.target().datasetId());
        event.put("user", owner);
        event.put("world", committed.target().world());
        event.put("marketLane", committed.target().lane());
        event.put("accountId", committed.target().accountId());
        event.put("revision", next);
        event.put("epoch", epoch);
        event.put("workspace", workspaceReceipt);
        if (committed.previousDataset() != null) {
            event.put("previous", committed.previousDataset());
            event.put("repairReason", committed.repairReason());
        }
        events.publish("dataset.selected", event);
        return new DatasetResult(true, committed.target().datasetId(),
                !DatasetService.OBSERVED.equals(committed.target().datasetId()),
                committed.target().world(), committed.target().lane(),
                committed.target().accountId(), next, epoch, workspaceReceipt);
    }

    private String read(String owner) {
        return SettingsStore.read(db, SettingsStore.activeWorldKey(owner)).orElse(null);
    }

    private WorkspaceContext.ActiveMarket targetMarket(String world, String owner, String dataset) {
        WorkspaceContext.ActiveMarket resolved = targetMarketResolver.apply(world, owner);
        if (resolved == null || !world.equals(resolved.world())
                || resolved.accountId() == null || resolved.accountId().isBlank()) {
            throw new IllegalStateException("the target market resolver did not return market '"
                    + world + "' with its account");
        }
        return targetMarket(world, owner, dataset, resolved.accountId());
    }

    private WorkspaceContext.ActiveMarket targetMarket(String world, String owner, String dataset,
                                                        String accountId) {
        String selected = dataset == null || dataset.isBlank()
                ? DatasetService.OBSERVED : dataset;
        String lane = MarketLane.of(world, config.fixturesOnly(),
                new AnalysisContext(owner, selected)).name();
        return new WorkspaceContext.ActiveMarket(world, selected, lane, accountId);
    }

    private static String activeWorldOn(Connection connection, String owner, String fallback)
            throws SQLException {
        return SettingsStore.readOn(connection, SettingsStore.activeWorldKey(owner))
                .filter(value -> !value.isBlank()).orElse(fallback);
    }

    private static String activeDatasetOn(Connection connection, String owner) throws SQLException {
        return SettingsStore.readOn(connection, SettingsStore.activeDatasetKey(owner))
                .filter(value -> !value.isBlank()).orElse(DatasetService.OBSERVED);
    }

    private record AccountIdentity(String type, String worldId) {}

    /**
     * The account is one axis of the market identity, not a decorative receipt. Holding a shared
     * row lock until the selector/workspace transaction commits prevents a concurrent Paper reset
     * from deleting the account after it was resolved but before its id is stamped into context.
     */
    private static boolean targetAccountValidOn(Connection connection, String owner,
                                                WorkspaceContext.ActiveMarket target)
            throws SQLException {
        List<AccountIdentity> rows = Db.queryOn(connection,
                "SELECT type,world_id FROM accounts WHERE id=? AND user_id=? FOR SHARE",
                row -> new AccountIdentity(row.str("type"), row.str("world_id")),
                target.accountId(), owner);
        if (rows.isEmpty()) return false;
        AccountIdentity account = rows.getFirst();
        if (MarketLane.isSimulatedWorld(target.world())) {
            return "SIMULATION".equals(account.type())
                    && Objects.equals(target.world(), account.worldId());
        }
        if ("demo".equals(target.world())) {
            return "DEMO".equals(account.type()) && account.worldId() == null;
        }
        return "PAPER".equals(account.type()) && account.worldId() == null;
    }

    private static void requireTargetAccountOn(Connection connection, String owner,
                                               WorkspaceContext.ActiveMarket target)
            throws SQLException {
        if (!targetAccountValidOn(connection, owner, target)) {
            throw new IllegalStateException("the account '" + target.accountId()
                    + "' no longer belongs to market '" + target.world()
                    + "' for this owner; resolve the active market again");
        }
    }
}
