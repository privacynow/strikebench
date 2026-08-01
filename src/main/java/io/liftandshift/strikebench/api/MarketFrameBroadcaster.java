package io.liftandshift.strikebench.api;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One frame computation per owner/symbol scope, regardless of how many tabs subscribe. The
 * scheduler only dispatches work; provider or database reads run on virtual workers and cannot
 * stall another stream group.
 */
final class MarketFrameBroadcaster implements AutoCloseable {
    private static final int CLIENT_QUEUE_CAPACITY = 2;

    record Request(String owner, List<QuoteBatchComposer.RequestedSymbol> symbols,
                   boolean customSymbols) {
        Request {
            owner = owner == null ? "" : owner;
            // A default stream follows the owner's active universe dynamically. Keeping the
            // symbols captured at connection time in the group key both forked equivalent
            // subscribers and left a long-lived leader relaying an obsolete sector.
            symbols = customSymbols && symbols != null ? List.copyOf(symbols) : List.of();
        }
    }

    /** {@code quotes} is a list of {@link ApiResponses.QuoteView} rows; the transport only diffs
     *  and serializes them, so it deliberately does not re-state the row fields here. */
    record StreamError(String code, String detail, boolean retryable) {}

    record Draft(String world, List<?> quotes, String simTime, long asOf, StreamError error) {
        Draft(String world, List<?> quotes, String simTime, long asOf) {
            this(world, quotes, simTime, asOf, null);
        }

        Draft {
            world = world == null ? "observed" : world;
            quotes = quotes == null ? List.of() : List.copyOf(quotes);
        }
    }

    record Frame(long sequence, Draft draft) {
        Map<String, Object> payload() {
            Map<String, Object> frame = new java.util.LinkedHashMap<>();
            frame.put("seq", sequence);
            frame.put("quotes", draft.quotes());
            frame.put("asOf", draft.asOf());
            frame.put("world", draft.world());
            if (draft.simTime() != null) frame.put("simTime", draft.simTime());
            if (draft.error() != null) frame.put("error", draft.error());
            return frame;
        }
    }

    @FunctionalInterface interface FrameSource { Draft load(Request request); }

    private static final class Subscriber {
        private final Consumer<Frame> sink;
        private final ExecutorService workers;
        private final AtomicLong accepted = new AtomicLong();
        private final ArrayDeque<Frame> pending = new ArrayDeque<>(CLIENT_QUEUE_CAPACITY);
        private boolean draining;
        private boolean closed;

        private Subscriber(Consumer<Frame> sink, ExecutorService workers) {
            this.sink = sink;
            this.workers = workers;
        }

        private void send(Frame frame) {
            long seq = frame.sequence();
            long prior;
            do {
                prior = accepted.get();
                if (seq <= prior) return;
            } while (!accepted.compareAndSet(prior, seq));
            synchronized (this) {
                if (closed) return;
                while (pending.size() >= CLIENT_QUEUE_CAPACITY) pending.removeFirst();
                pending.addLast(frame);
                if (draining) return;
                draining = true;
            }
            try {
                workers.submit(this::drain);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                close();
            }
        }

        private void drain() {
            while (true) {
                Frame frame;
                synchronized (this) {
                    if (closed || pending.isEmpty()) {
                        draining = false;
                        return;
                    }
                    frame = pending.removeFirst();
                }
                try {
                    sink.accept(frame);
                } catch (RuntimeException e) {
                    close();
                    return;
                }
            }
        }

        private synchronized void close() {
            closed = true;
            pending.clear();
        }
    }

    private static final class Group {
        private final Request request;
        private final Set<Subscriber> subscribers = new CopyOnWriteArraySet<>();
        private final AtomicBoolean computing = new AtomicBoolean();
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicLong generation = new AtomicLong();
        private volatile Frame lastFrame;
        private Group(Request request) { this.request = request; }
    }

    private final FrameSource source;
    private final Map<Request, Group> groups = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workers;

    MarketFrameBroadcaster(int intervalSeconds, FrameSource source) {
        this.source = Objects.requireNonNull(source);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemon("market-stream-clock"));
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        int interval = Math.max(1, intervalSeconds);
        scheduler.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.SECONDS);
    }

    Runnable subscribe(Request request, Consumer<Frame> sink) {
        Group group = groups.computeIfAbsent(request, Group::new);
        Subscriber subscriber = new Subscriber(sink, workers);
        group.subscribers.add(subscriber);
        Frame cached = group.lastFrame;
        if (cached != null) deliver(subscriber, cached);
        else refresh(group);
        return () -> {
            group.subscribers.remove(subscriber);
            subscriber.close();
            if (group.subscribers.isEmpty()) groups.remove(request, group);
        };
    }

    private void tick() {
        for (Group group : groups.values()) refresh(group);
    }

    private void refresh(Group group) {
        if (group.subscribers.isEmpty() || !group.computing.compareAndSet(false, true)) return;
        long generation = group.generation.get();
        try {
            workers.submit(() -> {
                try {
                    Draft draft = source.load(group.request);
                    if (draft == null || group.generation.get() != generation) return;
                    Frame prior = group.lastFrame;
                    if (prior != null && sameContent(prior.draft(), draft)) return;
                    Frame frame = new Frame(group.sequence.incrementAndGet(), draft);
                    group.lastFrame = frame;
                    for (Subscriber subscriber : group.subscribers) deliver(subscriber, frame);
                } catch (RuntimeException failure) {
                    if (group.generation.get() != generation) return;
                    Frame priorFrame = group.lastFrame;
                    Draft prior = priorFrame == null ? null : priorFrame.draft();
                    Draft draft = new Draft(prior == null ? "observed" : prior.world(),
                            List.of(), prior == null ? null : prior.simTime(),
                            System.currentTimeMillis(),
                            new StreamError("MARKET_STREAM_REFRESH_FAILED",
                                    "Market quotes are temporarily unavailable; retry scheduled.", true));
                    if (prior != null && sameContent(prior, draft)) return;
                    Frame frame = new Frame(group.sequence.incrementAndGet(), draft);
                    group.lastFrame = frame;
                    for (Subscriber subscriber : group.subscribers) deliver(subscriber, frame);
                } finally {
                    group.computing.set(false);
                    if (group.generation.get() != generation) refresh(group);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            group.computing.set(false);
        }
    }

    private static void deliver(Subscriber subscriber, Frame frame) {
        try { subscriber.send(frame); }
        catch (RuntimeException ignored) { /* the close callback removes the subscriber */ }
    }

    private static boolean sameContent(Draft left, Draft right) {
        return Objects.equals(left.world(), right.world())
                && Objects.equals(left.quotes(), right.quotes())
                && Objects.equals(left.simTime(), right.simTime())
                && Objects.equals(left.error(), right.error());
    }

    int groupCount() { return groups.size(); }
    void refreshNow() { tick(); }

    void invalidateOwner(String owner) {
        for (Group group : groups.values()) {
            if (owner != null && !owner.equals(group.request.owner())) continue;
            group.generation.incrementAndGet();
            group.lastFrame = null;
            refresh(group);
        }
    }

    @Override public void close() {
        groups.values().forEach(group -> {
            group.subscribers.forEach(Subscriber::close);
            group.subscribers.clear();
        });
        groups.clear();
        scheduler.shutdownNow();
        workers.shutdownNow();
        await(scheduler);
        await(workers);
    }

    private static void await(ExecutorService executor) {
        try { executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
