package io.liftandshift.strikebench.recommend;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OpportunityScanKernelTest {

    @Test
    void bothPoliciesShareCanonicalTraversalAndSerializedMonotonicProgress() {
        OpportunityScanKernel kernel = new OpportunityScanKernel();
        OpportunityScanKernel.Universe universe =
                kernel.prepare(List.of(" spy ", "AAPL", "SPY", "", "qqq"));

        assertThat(universe.symbols()).containsExactly("SPY", "AAPL", "QQQ");

        for (OpportunityScanKernel.Policy policy : OpportunityScanKernel.Policy.values()) {
            List<OpportunityScanKernel.Completion<String>> frames =
                    Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean delivering = new AtomicBoolean();
            AtomicBoolean overlapped = new AtomicBoolean();

            OpportunityScanKernel.Traversal<String> traversal = kernel.traverse(
                    universe, policy,
                    symbol -> {
                        sleep("SPY".equals(symbol) ? 30 : "AAPL".equals(symbol) ? 15 : 2);
                        return symbol + "-ready";
                    },
                    frame -> {
                        if (!delivering.compareAndSet(false, true)) overlapped.set(true);
                        try {
                            sleep(3);
                            frames.add(frame);
                        } finally {
                            delivering.set(false);
                        }
                    });

            assertThat(traversal.universe()).isEqualTo(universe);
            assertThat(traversal.items()).extracting(OpportunityScanKernel.Item::symbol)
                    .containsExactly("SPY", "AAPL", "QQQ");
            assertThat(traversal.items()).extracting(OpportunityScanKernel.Item::value)
                    .containsExactly("SPY-ready", "AAPL-ready", "QQQ-ready");
            assertThat(traversal.items()).allSatisfy(item -> assertThat(item.succeeded()).isTrue());
            assertThat(frames).hasSize(universe.size());
            assertThat(frames).extracting(OpportunityScanKernel.Completion::completed)
                    .containsExactly(1, 2, 3);
            assertThat(frames).allSatisfy(frame -> {
                assertThat(frame.policy()).isEqualTo(policy);
                assertThat(frame.total()).isEqualTo(universe.size());
            });
            assertThat(overlapped).isFalse();
        }
    }

    @Test
    void bothPoliciesUseTheSameBoundAndIsolateOneSymbolFailure() {
        OpportunityScanKernel kernel = new OpportunityScanKernel();
        OpportunityScanKernel.Universe universe = kernel.prepare(
                java.util.stream.IntStream.range(0, 24)
                        .mapToObj(i -> "S" + i).toList());

        for (OpportunityScanKernel.Policy policy : OpportunityScanKernel.Policy.values()) {
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger highWater = new AtomicInteger();
            OpportunityScanKernel.Traversal<Integer> traversal = kernel.traverse(
                    universe, policy,
                    symbol -> {
                        int active = inFlight.incrementAndGet();
                        highWater.accumulateAndGet(active, Math::max);
                        try {
                            sleep(12);
                            if ("S11".equals(symbol)) throw new IllegalStateException("unavailable");
                            return Integer.parseInt(symbol.substring(1));
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    });

            assertThat(highWater.get()).isLessThanOrEqualTo(8);
            assertThat(traversal.items()).hasSize(24);
            assertThat(traversal.items().get(11).succeeded()).isFalse();
            assertThat(traversal.items().get(11).failure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("unavailable");
            assertThat(traversal.items().stream().filter(OpportunityScanKernel.Item::succeeded))
                    .hasSize(23);
        }
    }

    @Test
    void aBrokenProgressTransportCannotChangeTheTraversal() {
        OpportunityScanKernel kernel = new OpportunityScanKernel();
        OpportunityScanKernel.Traversal<String> traversal = kernel.traverse(
                kernel.prepare(List.of("AAPL", "SPY")),
                OpportunityScanKernel.Policy.EVIDENCE_FIELD,
                String::toLowerCase,
                frame -> { throw new IllegalStateException("browser disconnected"); });

        assertThat(traversal.items()).extracting(OpportunityScanKernel.Item::value)
                .containsExactly("aapl", "spy");
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
