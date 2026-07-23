package io.liftandshift.strikebench.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Fan a per-item unit of work out over one virtual thread each, optionally bounding how many run
 * at once, and collect the results in INPUT ORDER with per-item failure isolation.
 *
 * <p>The single shared home for the "submit per symbol, join, collect in order, turn a per-item
 * failure into a value rather than sinking the batch" ceremony that was hand-rolled in
 * {@code OpportunityScanner}, {@code AutoRecommender}'s signal loop, and {@code SparklineController}.
 * It carries ZERO domain knowledge: the concurrency bound, the work, and the failure mapping are all
 * injected, so each caller keeps its own policy.
 *
 * <p>Guarantees callers depend on:
 * <ul>
 *   <li><b>Input order</b> — {@code result.get(i)} always corresponds to {@code items.get(i)},
 *       regardless of completion order.</li>
 *   <li><b>Bound</b> — when {@code maxConcurrency > 0} at most {@code min(maxConcurrency, n)} tasks
 *       hold a permit at once; {@code maxConcurrency <= 0} ({@link #UNBOUNDED}) creates no gate.</li>
 *   <li><b>Failure isolation</b> — a throw from {@code work} (surfaced as the unwrapped
 *       {@link ExecutionException} cause) or an {@link InterruptedException} while joining is routed
 *       to {@code onFailure.apply(item, cause)}; that value takes the slot and the batch continues.
 *       Any {@link Throwable} cause is routed (including an {@link Error}).</li>
 *   <li><b>Join before return</b> — the try-with-resources executor {@code close()} blocks until
 *       every task finishes, so callers may safely run epilogue steps on the full result.</li>
 *   <li><b>Null-preserving</b> — a {@code null} returned by {@code work} is kept in its slot.</li>
 * </ul>
 *
 * <p><b>{@code onFailure} MUST be total</b>: a throw from it propagates and aborts the batch. Both
 * current call sites use pure record/null constructors, so this holds; keep it that way.
 *
 * <p>This per-batch bound is orthogonal to the process-wide governors
 * ({@code ProviderPoliteness}, {@code SimBudget}); they compose and must remain separate.
 */
public final class BoundedFanout {

    /** Pass as {@code maxConcurrency} to submit every item at once with no semaphore gate. */
    public static final int UNBOUNDED = 0;

    private BoundedFanout() {}

    public static <T, R> List<R> map(List<T> items,
                                     int maxConcurrency,
                                     Function<? super T, ? extends R> work,
                                     BiFunction<? super T, ? super Throwable, ? extends R> onFailure) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(onFailure, "onFailure");
        List<T> in = items == null ? List.of() : items;
        int n = in.size();
        List<R> results = new ArrayList<>(Collections.nCopies(n, null));
        if (n == 0) return results;
        Semaphore gate = maxConcurrency > 0 ? new Semaphore(Math.min(maxConcurrency, n)) : null;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<R>> futures = new ArrayList<>(n);
            for (T item : in) {
                futures.add(executor.submit(() -> {
                    if (gate != null) gate.acquire();
                    try {
                        return work.apply(item);
                    } finally {
                        if (gate != null) gate.release();
                    }
                }));
            }
            for (int i = 0; i < n; i++) {
                T item = in.get(i);
                try {
                    results.set(i, futures.get(i).get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    results.set(i, onFailure.apply(item, cause));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.set(i, onFailure.apply(item, e));
                }
            }
        }
        return results;
    }
}
