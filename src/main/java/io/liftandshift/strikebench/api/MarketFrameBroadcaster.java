package io.liftandshift.strikebench.api;

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
    record Request(String owner, List<String> symbols, boolean customSymbols) {
        Request {
            owner = owner == null ? "" : owner;
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
        }
    }

    record Draft(String world, List<Map<String, Object>> quotes, String simTime, long asOf) {
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
            return frame;
        }
    }

    @FunctionalInterface interface FrameSource { Draft load(Request request); }

    private static final class Subscriber {
        private final Consumer<Frame> sink;
        private final AtomicLong delivered = new AtomicLong();
        private Subscriber(Consumer<Frame> sink) { this.sink = sink; }
        private void send(Frame frame) {
            long seq = frame.sequence();
            long prior;
            do {
                prior = delivered.get();
                if (seq <= prior) return;
            } while (!delivered.compareAndSet(prior, seq));
            sink.accept(frame);
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
        Subscriber subscriber = new Subscriber(sink);
        group.subscribers.add(subscriber);
        Frame cached = group.lastFrame;
        if (cached != null) deliver(subscriber, cached);
        else refresh(group);
        return () -> {
            group.subscribers.remove(subscriber);
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
                } catch (RuntimeException ignored) {
                    // The next interval retries; one failed source cannot terminate the shared clock.
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
                && Objects.equals(left.simTime(), right.simTime());
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
        groups.values().forEach(group -> group.subscribers.clear());
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
