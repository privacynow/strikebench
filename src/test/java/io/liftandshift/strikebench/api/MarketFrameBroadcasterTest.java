package io.liftandshift.strikebench.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MarketFrameBroadcasterTest {
    private static MarketFrameBroadcaster.Draft frame(String world, String simTime) {
        return new MarketFrameBroadcaster.Draft(world,
                List.of(Map.of("symbol", "AAPL", "last", "100.00")), simTime, 1_000L);
    }

    @Test
    void clientsWithTheSameScopeShareOneComputedFrame() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        var request = new MarketFrameBroadcaster.Request("owner-a", List.of("AAPL"), true);
        try (var broadcaster = new MarketFrameBroadcaster(3600, ignored -> {
            loads.incrementAndGet();
            return frame("observed", null);
        })) {
            CountDownLatch first = new CountDownLatch(1);
            Runnable closeFirst = broadcaster.subscribe(request, ignored -> first.countDown());
            assertThat(first.await(1, TimeUnit.SECONDS)).isTrue();

            CountDownLatch second = new CountDownLatch(1);
            Runnable closeSecond = broadcaster.subscribe(request, ignored -> second.countDown());
            assertThat(second.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(loads).hasValue(1);
            assertThat(broadcaster.groupCount()).isEqualTo(1);

            closeFirst.run();
            closeSecond.run();
            assertThat(broadcaster.groupCount()).isZero();
        }
    }

    @Test
    void simulatedClockChangesProduceAFrameEvenWhenQuotesDoNotMove() throws Exception {
        AtomicReference<String> simTime = new AtomicReference<>("2026-07-14T09:30:00-04:00");
        var request = new MarketFrameBroadcaster.Request("owner-a", List.of("AAPL"), false);
        List<MarketFrameBroadcaster.Frame> received = new CopyOnWriteArrayList<>();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch two = new CountDownLatch(2);
        try (var broadcaster = new MarketFrameBroadcaster(3600, ignored -> frame("sim-1", simTime.get()))) {
            broadcaster.subscribe(request, value -> { received.add(value); first.countDown(); two.countDown(); });
            assertThat(first.await(1, TimeUnit.SECONDS)).isTrue();
            simTime.set("2026-07-14T09:31:00-04:00");
            broadcaster.refreshNow();
            assertThat(two.await(1, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(received).extracting(value -> value.draft().simTime())
                .containsExactly("2026-07-14T09:30:00-04:00", "2026-07-14T09:31:00-04:00");
        assertThat(received).extracting(MarketFrameBroadcaster.Frame::sequence).containsExactly(1L, 2L);
    }

    @Test
    void worldInvalidationDiscardsAnOlderInflightFrame() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch oldEntered = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        List<MarketFrameBroadcaster.Frame> received = new CopyOnWriteArrayList<>();
        var request = new MarketFrameBroadcaster.Request("owner-a", List.of("AAPL"), false);
        try (var broadcaster = new MarketFrameBroadcaster(3600, ignored -> {
            int call = loads.incrementAndGet();
            if (call == 1) {
                oldEntered.countDown();
                try { releaseOld.await(1, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return frame("observed", null);
            }
            return frame("sim-1", "2026-07-14T09:30:00-04:00");
        })) {
            broadcaster.subscribe(request, value -> { received.add(value); delivered.countDown(); });
            assertThat(oldEntered.await(1, TimeUnit.SECONDS)).isTrue();
            broadcaster.invalidateOwner("owner-a");
            releaseOld.countDown();
            assertThat(delivered.await(1, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(loads).hasValue(2);
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().draft().world()).isEqualTo("sim-1");
    }
}
