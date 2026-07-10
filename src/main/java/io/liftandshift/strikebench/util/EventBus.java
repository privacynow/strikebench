package io.liftandshift.strikebench.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Tiny in-process pub/sub for workspace events (job progress, dataset switches, provider
 * cooldowns, workspace revisions). The /api/events SSE route subscribes browser clients;
 * services publish. Design rules:
 * <ul>
 *   <li>Events are HINTS, not payloads — small maps with ids/versions; the client refetches
 *       the full resource when it cares. GETs stay the single source of truth.</li>
 *   <li>Publishing never throws and never blocks the publisher on a slow subscriber
 *       (subscriber exceptions are swallowed; SSE writes are already buffered by Jetty).</li>
 *   <li>A small replay ring supports SSE reconnect via Last-Event-ID so a briefly-offline
 *       tab misses nothing recent; anything older than the ring is covered by refetching.</li>
 * </ul>
 */
public final class EventBus {

    /** One published event. {@code seq} is the SSE id — monotonically increasing per process. */
    public record Event(long seq, String type, Map<String, Object> data) {}

    private static final int REPLAY_MAX = 256;

    private final AtomicLong seq = new AtomicLong();
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Event> replay = new ArrayDeque<>(); // guarded by `this`
    // PER-SUBSCRIBER bounded queues drained on virtual threads: one stalled SSE client blocks
    // only ITS OWN drain, never the publisher and never other subscribers (a single shared pump
    // let one slow client stall all delivery and grow an unbounded queue). Events are hints —
    // when a queue overflows the OLDEST hint is dropped; the client's normal refetching covers it.
    private static final int SUBSCRIBER_QUEUE_MAX = 256;
    private final java.util.concurrent.ExecutorService drains =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final class Subscriber {
        final Consumer<Event> consumer;
        final ArrayDeque<Event> queue = new ArrayDeque<>(); // guarded by `this` (the Subscriber)
        boolean draining;

        Subscriber(Consumer<Event> consumer) { this.consumer = consumer; }

        void offer(Event e) {
            boolean startDrain = false;
            synchronized (this) {
                if (queue.size() >= SUBSCRIBER_QUEUE_MAX) queue.removeFirst(); // drop-oldest: hints, not payloads
                queue.addLast(e);
                if (!draining) { draining = true; startDrain = true; }
            }
            if (startDrain) drains.execute(this::drain);
        }

        private void drain() {
            while (true) {
                Event e;
                synchronized (this) {
                    e = queue.pollFirst();
                    if (e == null) { draining = false; return; }
                }
                try { consumer.accept(e); } catch (Exception ignore) { /* one bad subscriber never breaks the bus */ }
            }
        }
    }

    /** Publishes an event to all subscribers (async, per-subscriber ordered). Never throws or blocks. */
    public Event publish(String type, Map<String, Object> data) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (data != null) copy.putAll(data);
        Event e = new Event(seq.incrementAndGet(), type, copy);
        synchronized (this) {
            replay.addLast(e);
            while (replay.size() > REPLAY_MAX) replay.removeFirst();
        }
        for (Subscriber s : subscribers) s.offer(e);
        return e;
    }

    /** The newest sequence number — a fresh SSE client with no Last-Event-ID starts here (no history dump). */
    public long currentSeq() { return seq.get(); }

    /** Subscribes; returns the unsubscribe handle (call it on SSE close). */
    public Runnable subscribe(Consumer<Event> consumer) {
        Subscriber s = new Subscriber(consumer);
        subscribers.add(s);
        return () -> subscribers.remove(s);
    }

    /** Events newer than {@code lastSeq}, oldest first — the Last-Event-ID replay. */
    public synchronized List<Event> since(long lastSeq) {
        List<Event> out = new ArrayList<>();
        for (Event e : replay) if (e.seq() > lastSeq) out.add(e);
        return out;
    }

    public int subscriberCount() { return subscribers.size(); }
}
