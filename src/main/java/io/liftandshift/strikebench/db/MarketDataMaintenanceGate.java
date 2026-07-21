package io.liftandshift.strikebench.db;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * One process-wide admission gate for writes to canonical market-data storage.
 *
 * <p>Normal writers share the read side, so quote persistence, observed-history write-through,
 * snapshots, and Data Center jobs can continue concurrently. A destructive market-data reset
 * takes the write side only after scheduled jobs have been quiesced. The fair lock prevents a
 * stream of foreground reads from starving a queued reset, and writers that arrive during the
 * reset wait until the reset transaction has completed.</p>
 */
public final class MarketDataMaintenanceGate {

    @FunctionalInterface
    public interface CheckedSupplier<T, E extends Exception> {
        T get() throws E;
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    /** Run one canonical market-data write under shared admission. */
    public <T> T write(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        lock.readLock().lock();
        try {
            return operation.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Run one canonical market-data write under shared admission. */
    public void write(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        write(() -> {
            operation.run();
            return null;
        });
    }

    /** Checked-exception variant for streaming user-owned imports. */
    public <T, E extends Exception> T writeChecked(CheckedSupplier<T, E> operation) throws E {
        Objects.requireNonNull(operation, "operation");
        lock.readLock().lock();
        try {
            return operation.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Stop all admitted writers and reject starvation for the duration of a destructive reset.
     * The returned lease must remain open through the reset transaction.
     */
    public ResetLease pauseWrites(Duration timeout) {
        Duration bounded = timeout == null || timeout.isNegative() ? Duration.ZERO : timeout;
        boolean acquired;
        try {
            acquired = lock.writeLock().tryLock(bounded.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Market-data reset was interrupted before active writers stopped.", e);
        }
        if (!acquired) {
            throw new IllegalStateException(
                    "Active market-data writers did not stop safely; the reset was not started.");
        }
        return new ResetLease(lock);
    }

    /** Exclusive reset admission. Close exactly once after the reset transaction completes. */
    public static final class ResetLease implements AutoCloseable {
        private ReentrantReadWriteLock owner;

        private ResetLease(ReentrantReadWriteLock owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            ReentrantReadWriteLock held = owner;
            if (held == null) return;
            owner = null;
            held.writeLock().unlock();
        }
    }
}
