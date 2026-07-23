package io.liftandshift.strikebench.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedFanoutTest {

    @Test
    void resultsAreInInputOrderRegardlessOfCompletionOrder() {
        // Item 0 sleeps longest (finishes last) but must still land at index 0.
        List<Integer> items = List.of(0, 1, 2, 3, 4);
        List<Integer> out = BoundedFanout.map(items, BoundedFanout.UNBOUNDED,
                i -> { sleep((5 - i) * 12L); return i; },
                (i, e) -> -1);
        assertThat(out).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void maxConcurrencyCapsPeakInFlight() {
        int max = 3, n = 24;
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger highWater = new AtomicInteger();
        List<Integer> items = IntStream.range(0, n).boxed().toList();
        List<Integer> out = BoundedFanout.map(items, max, i -> {
            int now = inFlight.incrementAndGet();
            highWater.accumulateAndGet(now, Math::max);
            sleep(15);
            inFlight.decrementAndGet();
            return i * 2;
        }, (i, e) -> -1);
        assertThat(highWater.get()).isLessThanOrEqualTo(max);
        assertThat(out).containsExactlyElementsOf(items.stream().map(i -> i * 2).toList());
    }

    @Test
    void unboundedRunsEveryItemAtOnce() {
        // A CyclicBarrier of n only trips if all n workers are in flight simultaneously.
        int n = 12;
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Integer> items = IntStream.range(0, n).boxed().toList();
        List<Integer> out = BoundedFanout.map(items, BoundedFanout.UNBOUNDED, i -> {
            try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
            return i;
        }, (i, e) -> -999);
        assertThat(out).doesNotContain(-999).containsExactlyElementsOf(items);
    }

    @Test
    void aThrowingWorkItemIsRoutedToOnFailureWithoutSinkingTheBatch() {
        // The cause is UNWRAPPED (the ExecutionException wrapper never reaches onFailure).
        List<String> out = BoundedFanout.map(List.of("a", "b", "c"), 2,
                s -> {
                    if (s.equals("b")) throw new IllegalStateException("boom");
                    return s.toUpperCase();
                },
                (s, e) -> "FAIL:" + s + ":" + e.getClass().getSimpleName());
        assertThat(out).containsExactly("A", "FAIL:b:IllegalStateException", "C");
    }

    @Test
    void anErrorCauseIsRoutedNotThrownOut() {
        List<String> out = BoundedFanout.map(List.of("x", "y"), BoundedFanout.UNBOUNDED,
                s -> {
                    if (s.equals("y")) throw new StackOverflowError("deep");
                    return s;
                },
                (s, e) -> "ERR:" + (e instanceof Error));
        assertThat(out).containsExactly("x", "ERR:true");
    }

    @Test
    void nullWorkResultIsPreservedInSlot() {
        List<String> out = BoundedFanout.map(List.of("a", "b"), BoundedFanout.UNBOUNDED,
                s -> s.equals("a") ? null : s,
                (s, e) -> "F");
        assertThat(out).containsExactly(null, "b");
    }

    @Test
    void emptyAndNullInputReturnAMutableEmptyList() {
        assertThat(BoundedFanout.map(List.of(), 4, x -> x, (x, e) -> x)).isEmpty();
        List<Object> fromNull = BoundedFanout.map(null, 4, x -> x, (x, e) -> x);
        assertThat(fromNull).isEmpty();
        fromNull.add("mutable"); // must not throw — the returned list is mutable
        assertThat(fromNull).containsExactly("mutable");
    }

    @Test
    void everyItemIsFullyAwaitedBeforeMapReturns() {
        AtomicBoolean workFinished = new AtomicBoolean(false);
        List<Integer> out = BoundedFanout.map(List.of(1), BoundedFanout.UNBOUNDED,
                i -> { sleep(60); workFinished.set(true); return i; },
                (i, e) -> -1);
        assertThat(workFinished).isTrue();      // join-before-return: epilogues can trust completeness
        assertThat(out).containsExactly(1);
    }

    @Test
    void survivesAnInterruptedCallingThreadAndPreservesTheFlag() {
        Thread.currentThread().interrupt();
        try {
            List<Integer> out = BoundedFanout.map(List.of(1, 2, 3), BoundedFanout.UNBOUNDED,
                    i -> i, (i, e) -> -1);
            // Whether get() observed the interrupt or the fast task completed first, every slot is
            // filled and map never propagates the InterruptedException.
            assertThat(out).hasSize(3);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear so the flag doesn't leak into sibling tests
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
