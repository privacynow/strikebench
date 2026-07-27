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
    private static List<QuoteBatchComposer.RequestedSymbol> symbols(String... symbols) {
        return java.util.Arrays.stream(symbols)
                .map(symbol -> new QuoteBatchComposer.RequestedSymbol(symbol, symbol, null))
                .toList();
    }

    private static MarketFrameBroadcaster.Draft frame(String world, String simTime) {
        return new MarketFrameBroadcaster.Draft(world,
                List.of(Map.of("symbol", "AAPL", "last", "100.00")), simTime, 1_000L);
    }

    @Test
    void defaultScopesFollowTheActiveUniverseInsteadOfConnectionTimeSymbols() {
        var first = new MarketFrameBroadcaster.Request("owner-a", symbols("AAPL"), false);
        var changed = new MarketFrameBroadcaster.Request("owner-a", symbols("SPY", "QQQ"), false);
        var explicit = new MarketFrameBroadcaster.Request("owner-a", symbols("SPY", "QQQ"), true);

        assertThat(first).isEqualTo(changed);
        assertThat(first.symbols()).isEmpty();
        assertThat(explicit.symbols()).extracting(QuoteBatchComposer.RequestedSymbol::canonical)
                .containsExactly("SPY", "QQQ");
        assertThat(explicit).isNotEqualTo(first);
    }

    @Test
    void clientsWithTheSameScopeShareOneComputedFrame() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        var request = new MarketFrameBroadcaster.Request("owner-a", symbols("AAPL"), true);
        try (var broadcaster = new MarketFrameBroadcaster(3600, ignored -> {
            loads.incrementAndGet();
            return frame("observed", null);
        })) {
            CountDownLatch first = new CountDownLatch(1);
            Runnable closeFirst = broadcaster.subscribe(request, ignored -> first.countDown());
            assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch second = new CountDownLatch(1);
            Runnable closeSecond = broadcaster.subscribe(request, ignored -> second.countDown());
            assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
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
        var request = new MarketFrameBroadcaster.Request("owner-a", symbols("AAPL"), false);
        List<MarketFrameBroadcaster.Frame> received = new CopyOnWriteArrayList<>();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch two = new CountDownLatch(2);
        try (var broadcaster = new MarketFrameBroadcaster(3600, ignored -> frame("sim-1", simTime.get()))) {
            broadcaster.subscribe(request, value -> { received.add(value); first.countDown(); two.countDown(); });
            assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();
            simTime.set("2026-07-14T09:31:00-04:00");
            broadcaster.refreshNow();
            assertThat(two.await(5, TimeUnit.SECONDS)).isTrue();
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
        var request = new MarketFrameBroadcaster.Request("owner-a", symbols("AAPL"), false);
        try (var broadcaster = new MarketFrameBroadcaster(3600, ignored -> {
            int call = loads.incrementAndGet();
            if (call == 1) {
                oldEntered.countDown();
                try { releaseOld.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return frame("observed", null);
            }
            return frame("sim-1", "2026-07-14T09:30:00-04:00");
        })) {
            broadcaster.subscribe(request, value -> { received.add(value); delivered.countDown(); });
            assertThat(oldEntered.await(5, TimeUnit.SECONDS)).isTrue();
            broadcaster.invalidateOwner("owner-a");
            releaseOld.countDown();
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(loads).hasValue(2);
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().draft().world()).isEqualTo("sim-1");
    }

    @Test
    void slowClientDropsOldFramesWithoutBlockingOtherClientsOrFrameLoads() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch slowCaughtUp = new CountDownLatch(1);
        CountDownLatch fastCaughtUp = new CountDownLatch(1);
        List<Long> slowSequences = new CopyOnWriteArrayList<>();
        var request = new MarketFrameBroadcaster.Request("owner-a", symbols("AAPL"), false);

        try (var broadcaster = new MarketFrameBroadcaster(3600, ignored -> {
            int n = loads.incrementAndGet();
            return frame("sim-1", "tick-" + n);
        })) {
            broadcaster.subscribe(request, value -> {
                slowSequences.add(value.sequence());
                if (value.sequence() == 1L) {
                    slowEntered.countDown();
                    try { releaseSlow.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                if (value.sequence() == 10L) slowCaughtUp.countDown();
            });
            assertThat(slowEntered.await(5, TimeUnit.SECONDS)).isTrue();
            broadcaster.subscribe(request, value -> {
                if (value.sequence() == 10L) fastCaughtUp.countDown();
            });

            for (int target = 2; target <= 10; target++) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (loads.get() < target && System.nanoTime() < deadline) {
                    broadcaster.refreshNow();
                    Thread.sleep(5);
                }
                assertThat(loads).hasValue(target);
            }
            assertThat(fastCaughtUp.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(loads).hasValue(10);

            releaseSlow.countDown();
            assertThat(slowCaughtUp.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseSlow.countDown();
        }
        assertThat(slowSequences).containsExactly(1L, 9L, 10L);
    }

    @Test
    void sourceFailurePublishesOneTypedRetryableErrorFrameInsteadOfGoingSilent() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        var request = new MarketFrameBroadcaster.Request("owner-a", symbols("AAPL"), true);
        List<MarketFrameBroadcaster.Frame> received = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);
        try (var broadcaster = new MarketFrameBroadcaster(3600, ignored -> {
            loads.incrementAndGet();
            throw new IllegalStateException("provider detail must not leak");
        })) {
            broadcaster.subscribe(request, value -> {
                received.add(value);
                delivered.countDown();
            });
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();

            broadcaster.refreshNow();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (loads.get() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(loads).hasValue(2);
            Thread.sleep(50);
        }

        assertThat(received).hasSize(1);
        MarketFrameBroadcaster.StreamError error = received.getFirst().draft().error();
        assertThat(error.code()).isEqualTo("MARKET_STREAM_REFRESH_FAILED");
        assertThat(error.retryable()).isTrue();
        assertThat(error.detail()).contains("retry scheduled").doesNotContain("provider detail");
        assertThat(received.getFirst().draft().quotes()).isEmpty();
    }
}
