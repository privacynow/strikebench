package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.DatasetService;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.WorkspaceContext;
import io.liftandshift.strikebench.db.WorkspaceService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.sim.SimulationSessions;
import io.liftandshift.strikebench.market.sim.SimulatedWorld;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.EventBus;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.OwnerScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldTransitionServiceTest {
    private Db db;
    private EventBus events;
    private DatasetService datasets;
    private WorkspaceService workspace;
    private SimulationSessions sessions;

    @AfterEach void close() { if (db != null) db.close(); }

    private WorldTransitionService service(java.util.function.BiFunction<String, String, Object> universe) {
        db = TestDb.fresh();
        Clock clock = Clock.systemUTC();
        events = new EventBus();
        datasets = new DatasetService(db, clock);
        workspace = new WorkspaceService(db, clock);
        workspace.setEvents(events);
        sessions = new SimulationSessions(db, events);
        return new WorldTransitionService(new AppConfig(Map.of()), clock, db,
                datasets, new MarketDataService(List.of(), List.of(), List.of()),
                sessions, events, workspace, universe, this::testTarget,
                "test-epoch");
    }

    private WorkspaceContext.ActiveMarket testTarget(String world, String owner) {
        String accountId = "acct_" + world
                + ("owner-a".equals(owner) ? "" : "_" + owner);
        boolean simulated = io.liftandshift.strikebench.market.MarketLane
                .isSimulatedWorld(world);
        String type = "demo".equals(world) ? "DEMO" : simulated ? "SIMULATION" : "PAPER";
        String worldId = simulated ? world : null;
        db.tx(connection -> {
            OwnerScope.ensure(connection, owner);
            Db.execOn(connection, """
                    INSERT INTO accounts(
                      id,user_id,name,type,starting_cash_cents,cash_cents,
                      reserved_cents,has_traded,created_at,updated_at,world_id)
                    VALUES (?,?,?,?,10000000,10000000,0,0,now(),now(),?)
                    ON CONFLICT(id) DO NOTHING
                    """, accountId, owner, "Identity test", type, worldId);
            return null;
        });
        return new WorkspaceContext.ActiveMarket(world,
                "demo".equals(world) ? "DEMO" : simulated ? "SIMULATED" : "OBSERVED",
                accountId);
    }

    private SimulatedWorld createWorld(String owner) {
        return sessions.create(new SimulatedWorld.Config(null, "Identity test",
                Map.of("SPY", 1.0), Map.of("SPY", 500.0), "BASE", 0.2, 7L,
                "2026-07-20T09:30:00", 60.0, null, null), owner);
    }

    private void select(String owner, String key, String value) {
        db.exec("INSERT INTO settings(k,v,updated_at) VALUES (?,?,now()) "
                + "ON CONFLICT (k) DO UPDATE SET v=excluded.v,updated_at=excluded.updated_at",
                key + ":" + owner, value);
    }

    private String selected(String owner, String key) {
        return db.query("SELECT v FROM settings WHERE k=?", row -> row.str("v"), key + ":" + owner)
                .getFirst();
    }

    @Test
    void successfulTransitionUpdatesTheOwnerCacheUsedByHotFrameReads() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        assertThat(transitions.active("owner-a")).isEqualTo("observed");
        assertThat(transitions.transition("demo", "owner-a").world()).isEqualTo("demo");

        db.close();
        db = null;
        assertThat(transitions.activeCached("owner-a")).isEqualTo("demo");
    }

    @Test
    void failedHydrationLeavesWorldDatasetAndWorkspaceUntouchedAndPublishesNothing() {
        WorldTransitionService transitions = service((world, owner) -> {
            if ("demo".equals(world)) throw new IllegalStateException("hydrate failed");
            return Map.of("world", world);
        });
        select("owner-a", "active_world", "observed");
        String scenario = datasets.create(
                "Keep this dataset", "SCENARIO", "SPY", 11L, Map.of(), "owner-a");
        datasets.setActive(scenario, "owner-a");
        var before = workspace.patch("owner-a", Json.read("""
                {"version":1,"world":"observed","goal":"INCOME","scopeType":"SYMBOL",
                 "focusedSymbol":"AAPL"}
                """, WorkspaceContext.Patch.class),
                new WorkspaceContext.ActiveMarket(
                        "observed", scenario, "SCENARIO", "acct_observed"));
        long eventBoundary = events.currentSeq();

        assertThatThrownBy(() -> transitions.transition("demo", "owner-a"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hydrate failed");

        assertThat(transitions.active("owner-a")).isEqualTo("observed");
        assertThat(selected("owner-a", "active_world")).isEqualTo("observed");
        assertThat(selected("owner-a", "active_dataset")).isEqualTo(scenario);
        var after = workspace.context("owner-a",
                new WorkspaceContext.ActiveMarket(
                        "observed", scenario, "SCENARIO", "acct_observed"));
        assertThat(after.rev()).isEqualTo(before.rev());
        assertThat(after.context().focusedSymbol()).isEqualTo("AAPL");
        assertThat(after.context().goal()).isEqualTo("INCOME");
        assertThat(events.since(eventBoundary)).isEmpty();
    }

    @Test
    void ownerMutexSerializesAFirstWorkspaceWriteWithAWorldTransition() throws Exception {
        CountDownLatch ownerLocked = new CountDownLatch(1);
        CountDownLatch writeFirstContext = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
            // Resolve the target account before deliberately holding the owner row; otherwise the
            // FK check for a first account insert is what waits, obscuring the workspace mutex.
            testTarget("demo", "owner-a");
            db.tx(connection -> {
                OwnerScope.ensure(connection, "owner-a");
                return null;
            });
            WorkspaceContext firstContext = WorkspaceContext.empty(
                            new WorkspaceContext.ActiveMarket(
                                    "observed", "OBSERVED", "acct_observed"))
                    .merge(Json.read("""
                            {"version":1,"world":"observed","goal":"INCOME",
                             "scopeType":"SYMBOL","focusedSymbol":"AAPL"}
                            """, WorkspaceContext.Patch.class))
                    .validated();

            /*
             * This is the exact absent-row race the owner mutex exists to close. The first writer
             * has locked the durable owner but has not created workspace yet. A transition begins
             * while that optional row is still absent and must wait on the same owner row. Once
             * the first write commits, the transition must discover it and reconcile it rather
             * than returning "nothing stored" and leaving an Observed context behind Demo.
             */
            Future<?> firstWrite = pool.submit(() -> db.tx(connection -> {
                OwnerScope.lock(connection, "owner-a");
                ownerLocked.countDown();
                try {
                    if (!writeFirstContext.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release the first workspace write");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("first workspace write was interrupted", interrupted);
                }
                Db.execOn(connection,
                        "INSERT INTO workspace(user_id,state,rev,updated_at) "
                                + "VALUES (?,?::jsonb,1,now())",
                        "owner-a", firstContext.toJson());
                return null;
            }));

            assertThat(ownerLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<WorldTransitionService.Result> moved =
                    pool.submit(() -> transitions.transition("demo", "owner-a"));
            awaitOwnerMutexWait();
            writeFirstContext.countDown();

            firstWrite.get(10, TimeUnit.SECONDS);
            WorldTransitionService.Result result = moved.get(10, TimeUnit.SECONDS);
            assertThat(result.workspace().rev()).isEqualTo(2);
            assertThat(result.workspace().world()).isEqualTo("demo");
            assertThat(result.workspace().context().world()).isEqualTo("demo");
            assertThat(result.workspace().context().goal()).isEqualTo("INCOME");
            assertThat(result.workspace().context().focusedSymbol()).isNull();
            assertThat(result.workspace().transition().fromWorld()).isEqualTo("observed");
            assertThat(result.workspace().transition().toWorld()).isEqualTo("demo");
            assertThat(selected("owner-a", "active_world")).isEqualTo("demo");
            assertThat(events.since(0)).extracting(EventBus.Event::type)
                    .containsExactly("workspace.updated", "world.selected");
        } finally {
            writeFirstContext.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitOwnerMutexWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long waiting = db.query("""
                    SELECT count(*) n
                    FROM pg_stat_activity
                    WHERE datname=current_database()
                      AND wait_event_type='Lock'
                      AND query LIKE '%SELECT id FROM users%'
                    """, row -> row.lng("n")).getFirst();
            if (waiting > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("world transition never waited on the durable owner mutex");
    }

    @Test
    void explicitTransitionReturnsTheContextCommittedBesideItsSelectors() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        var before = workspace.patch("owner-a", Json.read("""
                {"version":1,"world":"observed","goal":"INCOME","view":"NEUTRAL",
                 "horizonDays":45,"riskPosture":"BALANCED","scopeType":"SYMBOL",
                 "focusedSymbol":"AAPL","routeState":"#/idea/AAPL"}
                """, WorkspaceContext.Patch.class),
                new WorkspaceContext.ActiveMarket("observed", "OBSERVED", "acct_observed"));
        long boundary = events.currentSeq();

        WorldTransitionService.Result result = transitions.transition("demo", "owner-a");

        assertThat(result.world()).isEqualTo("demo");
        assertThat(result.baselineWorld()).isEqualTo("observed");
        assertThat(result.workspace().world()).isEqualTo("demo");
        assertThat(result.workspace().marketLane()).isEqualTo("DEMO");
        assertThat(result.workspace().accountId()).isEqualTo("acct_demo");
        assertThat(result.workspace().rev()).isEqualTo(before.rev() + 1);
        assertThat(result.workspace().context().world()).isEqualTo("demo");
        assertThat(result.workspace().context().focusedSymbol()).isNull();
        assertThat(result.workspace().context().routeState()).isNull();
        assertThat(result.workspace().context().goal()).isEqualTo("INCOME");
        assertThat(result.workspace().context().view()).isEqualTo("NEUTRAL");
        assertThat(result.workspace().context().horizonDays()).isEqualTo(45);
        assertThat(result.workspace().context().riskPosture()).isEqualTo("BALANCED");
        assertThat(result.workspace().transition().fromWorld()).isEqualTo("observed");
        assertThat(result.workspace().transition().toWorld()).isEqualTo("demo");

        // The next ordinary workspace read observes exactly that committed revision; it does not
        // perform the second half of the transition or advance generation again.
        var again = workspace.context("owner-a",
                new WorkspaceContext.ActiveMarket("demo", "DEMO", "acct_demo"));
        assertThat(again.rev()).isEqualTo(result.workspace().rev());
        assertThat(again.transition()).isNull();
        assertThat(again.context()).isEqualTo(result.workspace().context());

        assertThat(events.since(boundary)).extracting(EventBus.Event::type)
                .containsExactly("workspace.updated", "world.selected");
        assertThat(events.since(boundary).getLast().data())
                .containsEntry("workspace", result.workspace());
    }

    @Test
    void transitionDoesNotCreateAnUndeclaredWorkspace() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));

        WorldTransitionService.Result result = transitions.transition("demo", "owner-a");

        assertThat(result.workspace().rev()).isZero();
        assertThat(result.workspace().context()).isNull();
        assertThat(workspace.context("owner-a",
                new WorkspaceContext.ActiveMarket("demo", "DEMO", "acct_demo")).rev()).isZero();
    }

    @Test
    void currentReceiptKeepsTheActiveDatasetLaneWhileTransitionUsesPostResetLane() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        workspace.patch("owner-a", Json.read("""
                {"version":1,"world":"observed","goal":"INCOME"}
                """, WorkspaceContext.Patch.class),
                new WorkspaceContext.ActiveMarket("observed", "OBSERVED", "acct_observed"));
        String scenario = datasets.create("Dataset lens", "SCENARIO", "SPY", 11L, Map.of(), "owner-a");
        datasets.setActive(scenario, "owner-a");

        WorldTransitionService.Current current = transitions.current("owner-a");
        assertThat(current.baselineWorld()).isEqualTo("observed");
        assertThat(current.workspace().marketLane()).isEqualTo("SCENARIO");
        assertThat(current.workspace().context().marketLane()).isEqualTo("SCENARIO");

        WorldTransitionService.Result moved = transitions.transition("observed", "owner-a");
        assertThat(moved.datasetReset()).isTrue();
        assertThat(moved.workspace().marketLane()).isEqualTo("OBSERVED");
        assertThat(moved.workspace().context().marketLane()).isEqualTo("OBSERVED");
    }

    @Test
    void datasetActivationCommitsSelectorWorkspaceIdentityAndOneOwnerScopedReceipt() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        workspace.patch("owner-a", Json.read("""
                {"version":1,"world":"observed","goal":"ACQUIRE","scopeType":"SYMBOL",
                 "focusedSymbol":"AMD","targetCents":45000,"shareQuantity":500,
                 "routeState":"#/idea/AMD"}
                """, WorkspaceContext.Patch.class),
                new WorkspaceContext.ActiveMarket("observed", "OBSERVED", "acct_observed"));
        String scenario = datasets.create(
                "Scenario A", "SCENARIO", "AMD", 41L, Map.of(), "owner-a");
        long boundary = events.currentSeq();

        WorldTransitionService.DatasetResult result =
                transitions.activateDataset(scenario, "owner-a");

        assertThat(selected("owner-a", "active_dataset")).isEqualTo(scenario);
        assertThat(result.active()).isEqualTo(scenario);
        assertThat(result.marketLane()).isEqualTo("SCENARIO");
        assertThat(result.workspace().datasetId()).isEqualTo(scenario);
        assertThat(result.workspace().context().generation()).isEqualTo(2);
        assertThat(result.workspace().context().focusedSymbol()).isNull();
        assertThat(result.workspace().context().targetCents()).isNull();
        assertThat(result.workspace().context().goal()).isEqualTo("ACQUIRE");
        assertThat(events.since(boundary)).extracting(EventBus.Event::type)
                .containsExactly("workspace.updated", "dataset.selected");
        assertThat(events.since(boundary).getLast().data())
                .containsEntry("user", "owner-a")
                .containsEntry("active", scenario)
                .containsEntry("workspace", result.workspace());
    }

    @Test
    void deletingTheActiveDatasetCannotRaceItsCacheOrLeaveAWorkspaceGhost() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        String scenario = datasets.create(
                "Scenario A", "SCENARIO", "AMD", 42L, Map.of(), "owner-a");
        transitions.activateDataset(scenario, "owner-a");
        workspace.patch("owner-a", Json.read("""
                {"version":1,"world":"observed","scopeType":"SYMBOL",
                 "focusedSymbol":"AMD","routeState":"#/idea/AMD"}
                """, WorkspaceContext.Patch.class),
                transitions.activeMarket("owner-a"));
        long boundary = events.currentSeq();

        WorldTransitionService.DatasetDeleteResult deleted =
                transitions.deleteDataset(scenario, "owner-a");

        assertThat(deleted.selectionChanged()).isTrue();
        assertThat(deleted.active()).isEqualTo(DatasetService.OBSERVED);
        assertThat(datasets.activeId("owner-a")).isEqualTo(DatasetService.OBSERVED);
        assertThat(db.query("SELECT id FROM dataset WHERE id=?",
                row -> row.str("id"), scenario)).isEmpty();
        assertThat(deleted.workspace().datasetId()).isEqualTo(DatasetService.OBSERVED);
        assertThat(deleted.workspace().context().focusedSymbol()).isNull();
        assertThat(events.since(boundary)).extracting(EventBus.Event::type)
                .containsExactly("workspace.updated", "dataset.selected");
    }

    @Test
    void datasetMutationFailureRollsBackSelectorWorkspaceAndEventsTogether() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        var before = workspace.patch("owner-a", Json.read("""
                {"version":1,"world":"observed","goal":"INCOME","scopeType":"SYMBOL",
                 "focusedSymbol":"AAPL"}
                """, WorkspaceContext.Patch.class),
                new WorkspaceContext.ActiveMarket("observed", "OBSERVED", "acct_observed"));
        long boundary = events.currentSeq();

        assertThatThrownBy(() -> transitions.activateDataset("ds_not_owned", "owner-a"))
                .isInstanceOf(io.liftandshift.strikebench.util.ResourceNotFoundException.class);

        assertThat(datasets.activeId("owner-a")).isEqualTo(DatasetService.OBSERVED);
        var after = workspace.context("owner-a",
                new WorkspaceContext.ActiveMarket("observed", "OBSERVED", "acct_observed"));
        assertThat(after.rev()).isEqualTo(before.rev());
        assertThat(after.context().focusedSymbol()).isEqualTo("AAPL");
        assertThat(events.since(boundary)).isEmpty();
    }

    @Test
    void simulationFinishCommitsBaselineAndWorkspaceInsideTheSessionTransaction() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        SimulatedWorld simulated = createWorld("owner-a");
        transitions.transition(simulated.worldId(), "owner-a");
        workspace.patch("owner-a", Json.read("""
                {"version":1,"scopeType":"SYMBOL","focusedSymbol":"SPY",
                 "goal":"HEDGE","routeState":"#/world"}
                """, WorkspaceContext.Patch.class), transitions.activeMarket("owner-a"));
        long boundary = events.currentSeq();

        WorldTransitionService.FinishTransition finish =
                transitions.prepareFinish(simulated.worldId(), "owner-a");
        sessions.finish(simulated.worldId(), "owner-a",
                (connection, worldId, world) -> finish.beforeFinish(connection));
        WorldTransitionService.FinishResult result = finish.afterCommit();

        assertThat(result.worldReset()).isTrue();
        assertThat(result.world()).isEqualTo("observed");
        assertThat(selected("owner-a", "active_world")).isEqualTo("observed");
        assertThat(result.workspace().context().world()).isEqualTo("observed");
        assertThat(result.workspace().context().focusedSymbol()).isNull();
        assertThat(result.workspace().context().goal()).isEqualTo("HEDGE");
        assertThat(db.query("SELECT status FROM sim_session WHERE id=?",
                row -> row.str("status"), simulated.worldId())).containsExactly("FINISHED");
        assertThat(events.since(boundary)).extracting(EventBus.Event::type)
                .containsExactly("workspace.updated", "world.selected");
        long publishedBoundary = events.currentSeq();
        assertThatThrownBy(finish::afterCommit)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already published");
        assertThat(events.since(publishedBoundary)).isEmpty();
    }

    @Test
    void failedFinishTransactionPublishesNothingAndLeavesMarketIdentityUnchanged() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        SimulatedWorld simulated = createWorld("owner-a");
        transitions.transition(simulated.worldId(), "owner-a");
        long boundary = events.currentSeq();
        WorldTransitionService.FinishTransition finish =
                transitions.prepareFinish(simulated.worldId(), "owner-a");

        assertThatThrownBy(() -> db.tx(connection -> {
            finish.beforeFinish(connection);
            throw new IllegalStateException("finish failed after identity write");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finish failed");

        assertThat(selected("owner-a", "active_world")).isEqualTo(simulated.worldId());
        assertThat(transitions.active("owner-a")).isEqualTo(simulated.worldId());
        assertThat(events.since(boundary)).isEmpty();
    }

    @Test
    void finishOnlyResetsTheSelectorWhenTheFinishedWorldIsStillActive() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        SimulatedWorld first = createWorld("owner-a");
        SimulatedWorld second = createWorld("owner-a");
        transitions.transition(first.worldId(), "owner-a");
        WorldTransitionService.FinishTransition finish =
                transitions.prepareFinish(first.worldId(), "owner-a");
        transitions.transition(second.worldId(), "owner-a");
        long boundary = events.currentSeq();

        sessions.finish(first.worldId(), "owner-a",
                (connection, worldId, world) -> finish.beforeFinish(connection));
        WorldTransitionService.FinishResult result = finish.afterCommit();

        assertThat(result.worldReset()).isFalse();
        assertThat(selected("owner-a", "active_world")).isEqualTo(second.worldId());
        assertThat(transitions.active("owner-a")).isEqualTo(second.worldId());
        assertThat(db.query("SELECT status FROM sim_session WHERE id=?",
                row -> row.str("status"), first.worldId())).containsExactly("FINISHED");
        assertThat(events.since(boundary)).isEmpty();
    }

    @Test
    void finishPreparedWhileInactiveStillResetsIfThatWorldWinsBeforeCommit() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        SimulatedWorld simulated = createWorld("owner-a");
        WorldTransitionService.FinishTransition finish =
                transitions.prepareFinish(simulated.worldId(), "owner-a");
        transitions.transition(simulated.worldId(), "owner-a");
        long boundary = events.currentSeq();

        sessions.finish(simulated.worldId(), "owner-a",
                (connection, worldId, world) -> finish.beforeFinish(connection));
        WorldTransitionService.FinishResult result = finish.afterCommit();

        assertThat(result.worldReset()).isTrue();
        assertThat(result.world()).isEqualTo("observed");
        assertThat(selected("owner-a", "active_world")).isEqualTo("observed");
        assertThat(events.since(boundary)).extracting(EventBus.Event::type)
                .containsExactly("world.selected");
    }

    @Test
    void failedFinishRestoresTheResidentRunningState() {
        service((world, owner) -> Map.of("world", world));
        SimulatedWorld simulated = createWorld("owner-a");
        sessions.start(simulated.worldId(), "owner-a");

        assertThatThrownBy(() -> sessions.finish(simulated.worldId(), "owner-a",
                (connection, worldId, world) -> {
                    throw new IllegalStateException("terminal write failed");
                })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal write failed");

        assertThat(sessions.getOrRestore(simulated.worldId(), "owner-a")
                .orElseThrow().running()).isTrue();
        assertThat(db.query("SELECT status FROM sim_session WHERE id=?",
                row -> row.str("status"), simulated.worldId())).containsExactly("RUNNING");
    }

    @Test
    void transitionRechecksSimulationReadinessInsideTheSelectorTransaction() {
        AtomicReference<String> terminalDuringHydration = new AtomicReference<>();
        WorldTransitionService transitions = service((world, owner) -> {
            if (world.equals(terminalDuringHydration.get())) {
                db.exec("UPDATE sim_session SET status='FINISHED' WHERE id=?", world);
            }
            return Map.of("world", world);
        });
        SimulatedWorld simulated = createWorld("owner-a");
        terminalDuringHydration.set(simulated.worldId());
        long boundary = events.currentSeq();

        assertThatThrownBy(() -> transitions.transition(simulated.worldId(), "owner-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finished and cannot be entered");

        assertThat(transitions.active("owner-a")).isEqualTo("observed");
        assertThat(events.since(boundary)).isEmpty();
    }

    @Test
    void marketRevisionIsPrivateToEachOwner() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));

        WorldTransitionService.Result a1 = transitions.transition("demo", "owner-a");
        WorldTransitionService.Result b1 = transitions.transition("demo", "owner-b");
        WorldTransitionService.Result a2 = transitions.transition("observed", "owner-a");

        assertThat(a1.revision()).isEqualTo(1);
        assertThat(b1.revision()).isEqualTo(1);
        assertThat(a2.revision()).isEqualTo(2);
        assertThat(transitions.current("owner-b").revision()).isEqualTo(1);
    }

    @Test
    void repeatingTheAlreadyCommittedMarketIsEventAndRevisionIdempotent() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        WorldTransitionService.Result first = transitions.transition("demo", "owner-a");
        long boundary = events.currentSeq();

        WorldTransitionService.Result again = transitions.transition("demo", "owner-a");

        assertThat(again.revision()).isEqualTo(first.revision());
        assertThat(events.since(boundary)).isEmpty();
    }

    @Test
    void foreignOrDanglingActiveDatasetIsDurablyRepairedAndNamed() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        String foreign = datasets.create(
                "Owner B only", "SCENARIO", "SPY", 77L, Map.of(), "owner-b");
        select("owner-a", "active_dataset", foreign);
        long boundary = events.currentSeq();

        WorldTransitionService.Current current = transitions.current("owner-a");

        assertThat(current.workspace().datasetId()).isEqualTo(DatasetService.OBSERVED);
        assertThat(current.workspace().marketLane()).isEqualTo("OBSERVED");
        assertThat(selected("owner-a", "active_dataset")).isEqualTo(DatasetService.OBSERVED);
        assertThat(datasets.activeId("owner-a")).isEqualTo(DatasetService.OBSERVED);
        assertThat(current.revision()).isEqualTo(1);
        assertThat(events.since(boundary)).extracting(EventBus.Event::type)
                .containsExactly("dataset.selected");
        assertThat(events.since(boundary).getFirst().data())
                .containsEntry("active", DatasetService.OBSERVED)
                .containsEntry("previous", foreign)
                .containsEntry("repairReason",
                        "ACTIVE_DATASET_UNAVAILABLE_OR_NOT_OWNED");
    }

    @Test
    void oldWorldPatchCannotMoveTheAtomicContextBackward() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        workspace.patch("owner-a", Json.read("""
                {"version":1,"world":"observed","goal":"INCOME","focusedSymbol":"AAPL"}
                """, WorkspaceContext.Patch.class),
                new WorkspaceContext.ActiveMarket("observed", "OBSERVED", "acct_observed"));
        WorldTransitionService.Result moved = transitions.transition("demo", "owner-a");

        assertThatThrownBy(() -> workspace.patch("owner-a", Json.read("""
                {"version":1,"world":"observed","focusedSymbol":"AMD"}
                """, WorkspaceContext.Patch.class),
                new WorkspaceContext.ActiveMarket("observed", "OBSERVED", "acct_observed")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("market moved to 'demo'");
        var after = workspace.context("owner-a",
                new WorkspaceContext.ActiveMarket("demo", "DEMO", "acct_demo"));
        assertThat(after.rev()).isEqualTo(moved.workspace().rev());
        assertThat(after.context().world()).isEqualTo("demo");
        assertThat(after.context().focusedSymbol()).isNull();
    }

    @Test
    void explicitTransitionCommitsWorldAndDatasetAsOneUnmarkedEvent() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world, "owner", owner));
        String scenario = datasets.create("Temporary analysis", "SCENARIO", "SPY", 7L, Map.of(), "owner-a");
        datasets.setActive(scenario, "owner-a");

        WorldTransitionService.Result result = transitions.transition("demo", "owner-a");

        assertThat(result.world()).isEqualTo("demo");
        assertThat(result.datasetReset()).isTrue();
        assertThat(selected("owner-a", "active_world")).isEqualTo("demo");
        assertThat(selected("owner-a", "active_dataset")).isEqualTo(DatasetService.OBSERVED);
        assertThat(events.since(0)).extracting(EventBus.Event::type)
                .containsExactly("dataset.selected", "world.selected");
        EventBus.Event world = events.since(0).getLast();
        assertThat(world.data()).containsEntry("revision", result.revision())
                .containsEntry("epoch", "test-epoch")
                .doesNotContainKey("repair");
        assertThat(transitions.current("owner-a").repair()).isNull();
    }

    @Test
    void missingSavedScenarioRepairsAtomicallyAndMarksTheReceiptAndEvent() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        workspace.patch("owner-a", Json.read("""
                {"version":1,"world":"sim_missing_session","goal":"INCOME","scopeType":"SYMBOL",
                 "focusedSymbol":"AAPL"}
                """, WorkspaceContext.Patch.class),
                new WorkspaceContext.ActiveMarket(
                        "sim_missing_session", "SIMULATED", "acct_sim_missing_session"));
        String scenario = datasets.create("Temporary analysis", "SCENARIO", "SPY", 8L, Map.of(), "owner-a");
        datasets.setActive(scenario, "owner-a");
        select("owner-a", "active_world", "sim_missing_session");

        WorldTransitionService.Current current = transitions.current("owner-a");

        assertThat(current.world()).isEqualTo("observed");
        assertThat(current.repair()).isNotNull();
        assertThat(current.repair().previousWorld()).isEqualTo("sim_missing_session");
        assertThat(current.repair().reason()).isEqualTo("SAVED_SCENARIO_UNAVAILABLE");
        assertThat(current.repair().message()).contains("sim_missing_session", "returned you to the observed market",
                "no Plan or accounting records were rewritten");
        assertThat(selected("owner-a", "active_world")).isEqualTo("observed");
        assertThat(selected("owner-a", "active_dataset")).isEqualTo(DatasetService.OBSERVED);
        assertThat(current.workspace().context().world()).isEqualTo("observed");
        assertThat(current.workspace().context().goal()).isEqualTo("INCOME");
        assertThat(current.workspace().context().focusedSymbol()).isNull();
        assertThat(current.workspace().transition().fromWorld()).isEqualTo("sim_missing_session");
        assertThat(current.workspace().transition().toWorld()).isEqualTo("observed");
        EventBus.Event world = events.since(0).stream()
                .filter(event -> "world.selected".equals(event.type())).findFirst().orElseThrow();
        assertThat(world.data()).containsEntry("repair", current.repair());
        assertThat(transitions.current("owner-a").repair()).isNull();
    }

    @Test
    void pendingRepairNoticeNeverReplacesANewerAtomicWorkspaceSnapshot() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world));
        String scenario = datasets.create(
                "Chosen after repair", "SCENARIO", "SPY", 91L, Map.of(), "owner-a");
        workspace.patch("owner-a", Json.read("""
                {"version":1,"world":"sim_missing_session","goal":"INCOME",
                 "scopeType":"SYMBOL","focusedSymbol":"AAPL"}
                """, WorkspaceContext.Patch.class),
                new WorkspaceContext.ActiveMarket(
                        "sim_missing_session", "SIMULATED",
                        "acct_sim_missing_session"));
        select("owner-a", "active_world", "sim_missing_session");

        // First request repairs the unavailable world and leaves its one-shot explanation pending.
        assertThat(transitions.active("owner-a")).isEqualTo("observed");
        // A newer dataset selection wins before the client consumes that explanation.
        WorldTransitionService.DatasetResult selected =
                transitions.activateDataset(scenario, "owner-a");
        WorldTransitionService.Current current = transitions.current("owner-a");

        assertThat(current.repair()).isNotNull();
        assertThat(current.repair().reason()).isEqualTo("SAVED_SCENARIO_UNAVAILABLE");
        assertThat(current.workspace().rev()).isEqualTo(selected.workspace().rev());
        assertThat(current.workspace().datasetId()).isEqualTo(scenario);
        assertThat(current.workspace().marketLane()).isEqualTo("SCENARIO");
        assertThat(current.workspace().context().datasetId()).isEqualTo(scenario);
    }

    @Test
    void failedRepairHydrationChangesNoSelectorAndPublishesNoEvent() {
        WorldTransitionService transitions = service((world, owner) -> {
            throw new IllegalStateException("baseline hydrate failed");
        });
        String scenario = datasets.create("Temporary analysis", "SCENARIO", "SPY", 9L, Map.of(), "owner-a");
        datasets.setActive(scenario, "owner-a");
        select("owner-a", "active_world", "sim_missing_session");

        assertThatThrownBy(() -> transitions.current("owner-a"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("baseline hydrate failed");
        assertThat(selected("owner-a", "active_world")).isEqualTo("sim_missing_session");
        assertThat(selected("owner-a", "active_dataset")).isEqualTo(scenario);
        assertThat(events.since(0)).isEmpty();
    }

    @Test
    void repairCompareAndSetFailureReturnsTheCompetingWinnerWithoutPublishing() {
        AtomicBoolean chooseWinnerDuringHydration = new AtomicBoolean(true);
        WorldTransitionService transitions = service((world, owner) -> {
            if (chooseWinnerDuringHydration.compareAndSet(true, false)) {
                select(owner, "active_world", "demo");
            }
            return Map.of("world", world);
        });
        String scenario = datasets.create("Temporary analysis", "SCENARIO", "SPY", 10L, Map.of(), "owner-a");
        datasets.setActive(scenario, "owner-a");
        select("owner-a", "active_world", "sim_missing_session");

        WorldTransitionService.Current current = transitions.current("owner-a");

        assertThat(current.world()).isEqualTo("demo");
        assertThat(current.repair()).isNull();
        assertThat(current.revision()).isZero();
        assertThat(selected("owner-a", "active_world")).isEqualTo("demo");
        assertThat(selected("owner-a", "active_dataset")).isEqualTo(scenario);
        assertThat(events.since(0)).isEmpty();
        assertThat(transitions.activeCached("owner-a")).isEqualTo("demo");
    }

    @Test
    void dataResetReconcilesPrivateOwnersAndBroadcastsNoPrivateReceipt() {
        WorldTransitionService transitions = service((world, owner) -> Map.of("world", world, "owner", owner));
        select("owner-a", "active_world", "demo");
        select("owner-b", "active_world", "demo");
        workspace.patch("owner-a", Json.read("""
                {"version":1,"goal":"INCOME","scopeType":"SYMBOL","focusedSymbol":"AMD"}
                """, WorkspaceContext.Patch.class),
                new WorkspaceContext.ActiveMarket("demo", "DEMO", "acct_demo"));
        workspace.patch("owner-b", Json.read("""
                {"version":1,"goal":"HEDGE","scopeType":"SYMBOL","focusedSymbol":"SPY"}
                """, WorkspaceContext.Patch.class),
                new WorkspaceContext.ActiveMarket("demo", "DEMO", "acct_demo"));
        long boundary = events.currentSeq();

        List<String> warnings =
                transitions.resetAfterDataReset(List.of("owner-a", "owner-b"));

        assertThat(warnings).isEmpty();
        assertThat(selected("owner-a", "active_world")).isEqualTo("observed");
        assertThat(selected("owner-b", "active_world")).isEqualTo("observed");
        assertThat(datasets.activeId("owner-a")).isEqualTo(DatasetService.OBSERVED);
        List<EventBus.Event> resetEvents = events.since(boundary);
        assertThat(resetEvents).extracting(EventBus.Event::type)
                .containsExactly("dataset.selected", "workspace.updated", "world.selected",
                        "dataset.selected", "workspace.updated", "world.selected", "world.reset");
        assertThat(resetEvents.stream()
                .filter(event -> "world.selected".equals(event.type())).toList())
                .allSatisfy(event -> {
                    assertThat(event.data()).containsKeys("user", "workspace");
                    String owner = String.valueOf(event.data().get("user"));
                    ApiResponses.Workspace receipt =
                            (ApiResponses.Workspace) event.data().get("workspace");
                    assertThat(receipt.context().goal())
                            .isEqualTo("owner-a".equals(owner) ? "INCOME" : "HEDGE");
                });
        EventBus.Event global = resetEvents.getLast();
        assertThat(global.data()).containsEntry("scope", "ALL")
                .containsEntry("world", "observed")
                .containsEntry("epoch", "test-epoch")
                .doesNotContainKeys("user", "workspace", "accountId");
    }
}
