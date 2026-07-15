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

    @AfterEach void close() { if (db != null) db.close(); }

    private WorldTransitionService service(java.util.function.BiFunction<String, String, Object> universe) {
        db = TestDb.fresh();
        Clock clock = Clock.systemUTC();
        EventBus events = new EventBus();
        return new WorldTransitionService(new AppConfig(Map.of()), clock, db,
                new DatasetService(db, clock), new MarketDataService(List.of(), List.of(), List.of()),
                new SimulationSessions(db, events), events, universe, "test-epoch");
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
}
