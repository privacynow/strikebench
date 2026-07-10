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
    private final CopyOnWriteArrayList<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Event> replay = new ArrayDeque<>(); // guarded by `this`

    /** Publishes an event to all subscribers. Null-tolerant data values; never throws. */
    public Event publish(String type, Map<String, Object> data) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (data != null) copy.putAll(data);
        Event e = new Event(seq.incrementAndGet(), type, copy);
        synchronized (this) {
            replay.addLast(e);
            while (replay.size() > REPLAY_MAX) replay.removeFirst();
        }
        for (Consumer<Event> s : subscribers) {
            try { s.accept(e); } catch (Exception ignore) { /* one bad subscriber never breaks the bus */ }
        }
        return e;
    }

    /** Subscribes; returns the unsubscribe handle (call it on SSE close). */
    public Runnable subscribe(Consumer<Event> consumer) {
        subscribers.add(consumer);
        return () -> subscribers.remove(consumer);
    }

    /** Events newer than {@code lastSeq}, oldest first — the Last-Event-ID replay. */
    public synchronized List<Event> since(long lastSeq) {
        List<Event> out = new ArrayList<>();
        for (Event e : replay) if (e.seq() > lastSeq) out.add(e);
        return out;
    }

    public int subscriberCount() { return subscribers.size(); }
}
