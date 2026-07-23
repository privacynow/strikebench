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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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
    void writesAnnounceOnTheBus() throws InterruptedException {
        db = TestDb.fresh();
        WorkspaceService ws = new WorkspaceService(db, clock);
        EventBus bus = new EventBus();
        ws.setEvents(bus);
        List<EventBus.Event> seen = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);
        bus.subscribe(event -> { seen.add(event); delivered.countDown(); });
        long rev = ws.put("user-a", "{\"symbol\":\"AAPL\"}");
        assertThat(delivered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).anyMatch(e ->
                e.type().equals("workspace.updated") && Long.valueOf(rev).equals(e.data().get("rev")));
    }

    @Test
    void eventBusReplaysSinceAndSurvivesBadSubscribers() throws InterruptedException {
        EventBus bus = new EventBus();
        bus.subscribe(e -> { throw new RuntimeException("bad subscriber"); });
        List<EventBus.Event> seen = new CopyOnWriteArrayList<>();
        CountDownLatch firstTwoDelivered = new CountDownLatch(2);
        bus.subscribe(event -> { seen.add(event); firstTwoDelivered.countDown(); });
        var e1 = bus.publish("job.progress", Map.of("id", "j1", "done", 1));
        var e2 = bus.publish("job.complete", Map.of("id", "j1", "status", "DONE"));
        assertThat(firstTwoDelivered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).containsExactly(e1, e2); // the throwing subscriber didn't break delivery
        // The replay ring is synchronous truth regardless of the async pump.
        assertThat(bus.since(e1.seq())).containsExactly(e2);
        assertThat(bus.since(0)).hasSize(2);
        assertThat(bus.currentSeq()).isEqualTo(e2.seq());
        // The replay ring is capped — old events fall off instead of growing forever.
        for (int i = 0; i < 400; i++) bus.publish("tick", Map.of("i", i));
        assertThat(bus.since(0)).hasSize(256);
        // Per-subscriber queues are also deliberately capped at 256 hints and may drop the
        // oldest items during this 400-event burst. Do not require lossless async delivery here.
        // A fresh subscriber gives the unsubscribe check a deterministic delivery barrier.
        List<EventBus.Event> markerSeen = new CopyOnWriteArrayList<>();
        CountDownLatch markerDelivered = new CountDownLatch(1);
        bus.subscribe(event -> { markerSeen.add(event); markerDelivered.countDown(); });
        List<EventBus.Event> removedSeen = new CopyOnWriteArrayList<>();
        Runnable unsub = bus.subscribe(removedSeen::add);
        unsub.run();
        var after = bus.publish("after", Map.of());
        assertThat(markerDelivered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(markerSeen).anyMatch(event -> event.seq() == after.seq());
        assertThat(removedSeen).isEmpty();
    }
}
