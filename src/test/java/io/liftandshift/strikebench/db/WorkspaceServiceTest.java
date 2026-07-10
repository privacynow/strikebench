package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Workspace blob: versioning, per-user isolation, size cap, event announcements. */
class WorkspaceServiceTest {

    private Db db;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-09T14:00:00Z"), ZoneOffset.UTC);

    @AfterEach void close() { if (db != null) db.close(); }

    @Test
    void putGetRoundTripWithIncrementingRevisions() {
        db = TestDb.fresh();
        WorkspaceService ws = new WorkspaceService(db, clock);
        assertThat(ws.get(null)).isEmpty();
        long r1 = ws.put(null, "{\"symbol\":\"AAPL\"}");
        long r2 = ws.put(null, "{\"symbol\":\"QQQ\"}");
        assertThat(r2).isEqualTo(r1 + 1);
        var got = ws.get(null).orElseThrow();
        assertThat(got.rev()).isEqualTo(r2);
        assertThat(got.stateJson()).contains("QQQ");
    }

    @Test
    void usersAreIsolatedAndAnonymousSharesTheLocalKey() {
        db = TestDb.fresh();
        WorkspaceService ws = new WorkspaceService(db, clock);
        ws.put("user-a", "{\"symbol\":\"AAPL\"}");
        ws.put("user-b", "{\"symbol\":\"TSLA\"}");
        ws.put(null, "{\"symbol\":\"SPY\"}");
        assertThat(ws.get("user-a").orElseThrow().stateJson()).contains("AAPL");
        assertThat(ws.get("user-b").orElseThrow().stateJson()).contains("TSLA");
        // null and blank both mean the anonymous local workspace.
        assertThat(ws.get("").orElseThrow().stateJson()).contains("SPY");
    }

    @Test
    void oversizedBlobsAreRejected() {
        db = TestDb.fresh();
        WorkspaceService ws = new WorkspaceService(db, clock);
        String big = "{\"x\":\"" + "y".repeat(WorkspaceService.MAX_STATE_BYTES) + "\"}";
        assertThatThrownBy(() -> ws.put(null, big)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void writesAnnounceOnTheBus() {
        db = TestDb.fresh();
        WorkspaceService ws = new WorkspaceService(db, clock);
        EventBus bus = new EventBus();
        ws.setEvents(bus);
        List<EventBus.Event> seen = new CopyOnWriteArrayList<>();
        bus.subscribe(seen::add);
        long rev = ws.put("user-a", "{\"symbol\":\"AAPL\"}");
        assertThat(seen).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("workspace.updated");
            assertThat(e.data()).containsEntry("rev", rev);
        });
    }

    @Test
    void eventBusReplaysSinceAndSurvivesBadSubscribers() {
        EventBus bus = new EventBus();
        bus.subscribe(e -> { throw new RuntimeException("bad subscriber"); });
        List<EventBus.Event> seen = new CopyOnWriteArrayList<>();
        bus.subscribe(seen::add);
        var e1 = bus.publish("job.progress", Map.of("id", "j1", "done", 1));
        var e2 = bus.publish("job.complete", Map.of("id", "j1", "status", "DONE"));
        assertThat(seen).hasSize(2); // the throwing subscriber didn't break delivery
        assertThat(bus.since(e1.seq())).containsExactly(e2);
        assertThat(bus.since(0)).hasSize(2);
        // The replay ring is capped — old events fall off instead of growing forever.
        for (int i = 0; i < 400; i++) bus.publish("tick", Map.of("i", i));
        assertThat(bus.since(0)).hasSizeLessThanOrEqualTo(256);
        // Unsubscribe actually stops delivery.
        int before = seen.size();
        Runnable unsub = bus.subscribe(seen::add);
        unsub.run();
        bus.publish("after", Map.of());
        assertThat(seen.size()).isEqualTo(before + 1); // only the original subscriber saw 'after'
    }
}
