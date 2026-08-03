package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.util.BoundedFanout;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * The single traversal owner for cross-symbol analysis.
 *
 * <p>This kernel owns only the ceremony every universe scan must share: normalized symbol identity,
 * bounded concurrency, input-ordered results, per-symbol failure isolation, and serialized
 * completion delivery. It deliberately does <em>not</em> rank candidates or construct a portfolio.
 * {@link AutoRecommender} retains its goal-aware signal policy, {@link OpportunityScanner} retains
 * its exact decision-score/allocation policy, and PortfolioOptimizer remains a separate portfolio
 * responsibility.</p>
 */
public final class OpportunityScanKernel {
    private static final int DEFAULT_CONCURRENCY = 8;

    /**
     * Names the distinct work policies while enforcing the same traversal behavior.
     *
     * <p>Both workloads are provider-backed and therefore share the same conservative local fan-out.
     * Provider-specific politeness governors remain the stricter process-wide authority.</p>
     */
    public enum Policy {
        EVIDENCE_FIELD(DEFAULT_CONCURRENCY),
        EXACT_PACKAGE_FIELD(DEFAULT_CONCURRENCY);

        private final int maxConcurrency;

        Policy(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        int maxConcurrency() {
            return maxConcurrency;
        }
    }

    /** Normalized, de-duplicated universe in caller order. */
    public record Universe(List<String> symbols) {
        public Universe {
            symbols = List.copyOf(symbols == null ? List.of() : symbols);
        }

        public int size() {
            return symbols.size();
        }

        public boolean isEmpty() {
            return symbols.isEmpty();
        }
    }

    /** One input-ordered symbol outcome. A null value can still be a successful observation. */
    public record Item<T>(String symbol, T value, Throwable failure) {
        public boolean succeeded() {
            return failure == null;
        }
    }

    /** Completion-order observation used by progressive transports. */
    public record Completion<T>(Policy policy, String symbol, int completed, int total,
                                T value, Throwable failure) {}

    @FunctionalInterface
    public interface CompletionListener<T> {
        void onComplete(Completion<T> completion);
    }

    /** Full traversal result: normalized universe plus one input-ordered item per symbol. */
    public record Traversal<T>(Universe universe, List<Item<T>> items) {
        public Traversal {
            universe = Objects.requireNonNull(universe, "universe");
            items = List.copyOf(items == null ? List.of() : items);
            if (items.size() != universe.size()) {
                throw new IllegalArgumentException("scan traversal must retain one result per symbol");
            }
        }
    }

    public Universe prepare(List<String> symbols) {
        return new Universe(Symbol.list(symbols));
    }

    public <T> Traversal<T> traverse(Universe universe, Policy policy,
                                     Function<String, T> work,
                                     CompletionListener<T> listener) {
        return traverse(universe, policy, work, listener, () -> false);
    }

    /**
     * Traverses until the response-local caller is cancelled. Already-running symbol work is
     * allowed to finish; queued symbols are skipped before they can acquire more provider data.
     * The input-shaped result is preserved so existing scan consumers keep one slot per symbol.
     */
    public <T> Traversal<T> traverse(Universe universe, Policy policy,
                                     Function<String, T> work,
                                     CompletionListener<T> listener,
                                     BooleanSupplier cancelled) {
        Universe field = Objects.requireNonNull(universe, "universe");
        Policy traversalPolicy = Objects.requireNonNull(policy, "policy");
        Function<String, T> symbolWork = Objects.requireNonNull(work, "work");
        CompletionListener<T> observer = Objects.requireNonNull(listener, "listener");
        BooleanSupplier cancellation = Objects.requireNonNull(cancelled, "cancelled");
        AtomicInteger completed = new AtomicInteger();
        Object deliveryLock = new Object();

        List<Item<T>> items = BoundedFanout.map(field.symbols(), traversalPolicy.maxConcurrency(),
                symbol -> {
                    if (cancellation.getAsBoolean()) {
                        return new Item<>(symbol, null,
                                new CancellationException("scan response closed"));
                    }
                    Item<T> item;
                    try {
                        item = new Item<>(symbol, symbolWork.apply(symbol), null);
                    } catch (VirtualMachineError fatal) {
                        throw fatal;
                    } catch (Throwable failure) {
                        item = new Item<>(symbol, null, failure);
                    }
                    if (!cancellation.getAsBoolean()) {
                        synchronized (deliveryLock) {
                            int count = completed.incrementAndGet();
                            try {
                                observer.onComplete(new Completion<>(traversalPolicy, symbol, count,
                                        field.size(), item.value(), item.failure()));
                            } catch (RuntimeException ignored) {
                                // Progress delivery is observational. A closed stream cannot alter
                                // the normalized scan or strand the remaining symbols.
                            }
                        }
                    }
                    return item;
                },
                (symbol, failure) -> new Item<>(symbol, null, failure));
        return new Traversal<>(field, items);
    }
}
