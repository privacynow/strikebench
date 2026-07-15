package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.DatasetService;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.sim.SimulationSessions;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldTransitionServiceTest {
    private Db db;
    private EventBus events;
    private DatasetService datasets;

    @AfterEach void close() { if (db != null) db.close(); }

    private WorldTransitionService service(java.util.function.BiFunction<String, String, Object> universe) {
        db = TestDb.fresh();
        Clock clock = Clock.systemUTC();
        events = new EventBus();
        datasets = new DatasetService(db, clock);
        return new WorldTransitionService(new AppConfig(Map.of()), clock, db,
                datasets, new MarketDataService(List.of(), List.of(), List.of()),
                new SimulationSessions(db, events), events, universe, "test-epoch");
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
    void failedHydrationLeavesThePreviouslyCachedWorldUntouched() {
        WorldTransitionService transitions = service((world, owner) -> {
            if ("demo".equals(world)) throw new IllegalStateException("hydrate failed");
            return Map.of("world", world);
        });
        assertThat(transitions.active("owner-a")).isEqualTo("observed");
        assertThatThrownBy(() -> transitions.transition("demo", "owner-a"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hydrate failed");
        assertThat(transitions.active("owner-a")).isEqualTo("observed");
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
        EventBus.Event world = events.since(0).stream()
                .filter(event -> "world.selected".equals(event.type())).findFirst().orElseThrow();
        assertThat(world.data()).containsEntry("repair", current.repair());
        assertThat(transitions.current("owner-a").repair()).isNull();
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
}
