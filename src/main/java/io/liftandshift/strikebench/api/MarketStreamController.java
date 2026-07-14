package io.liftandshift.strikebench.api;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.liftandshift.strikebench.auth.AuthService;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataEngine;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.market.sim.SimulationSessions;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.util.EventBus;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

/** Market-state and typed-hint SSE transports. */
final class MarketStreamController implements AutoCloseable {
    private final AppConfig cfg;
    private final Clock clock;
    private final MarketDataService market;
    private final MarketDataEngine engine;
    private final UniverseService universe;
    private final SimulationSessions sessions;
    private final EventBus events;
    private final AuthService auth;
    private final Function<Context, String> ownerId;
    private final Function<String, String> activeWorldFor;
    private final ScheduledExecutorService scheduler;

    MarketStreamController(AppConfig cfg, Clock clock, MarketDataService market,
                           MarketDataEngine engine, UniverseService universe,
                           SimulationSessions sessions, EventBus events, AuthService auth,
                           Function<Context, String> ownerId,
                           Function<String, String> activeWorldFor) {
        this.cfg = cfg;
        this.clock = clock;
        this.market = market;
        this.engine = engine;
        this.universe = universe;
        this.sessions = sessions;
        this.events = events;
        this.auth = auth;
        this.ownerId = ownerId;
        this.activeWorldFor = activeWorldFor;
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "market-stream");
            thread.setDaemon(true);
            return thread;
        });
    }

    void marketStream(SseClient client) {
        String raw = client.ctx().queryParam("symbols");
        List<String> symbols = raw == null || raw.isBlank()
                ? universe.active().symbols()
                : java.util.Arrays.stream(raw.split(","))
                    .map(symbol -> symbol.trim().toUpperCase(Locale.ROOT))
                    .filter(symbol -> !symbol.isBlank()).distinct().limit(60).toList();
        String streamOwner = ownerId.apply(client.ctx());
        client.keepAlive();
        AtomicReference<Future<?>> taskRef = new AtomicReference<>();
        AtomicLong sequence = new AtomicLong();
        AtomicInteger lastFrameHash = new AtomicInteger();
        Runnable push = () -> {
            if (client.terminated()) {
                Future<?> task = taskRef.get();
                if (task != null) task.cancel(false);
                return;
            }
            try {
                String world = activeWorldFor.apply(streamOwner);
                List<Map<String, Object>> rows = quoteRows(world, streamOwner, raw, symbols);
                int hash = rows.hashCode();
                if (hash == lastFrameHash.get() && sequence.get() > 0) return;
                lastFrameHash.set(hash);
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("seq", sequence.incrementAndGet());
                frame.put("quotes", rows);
                frame.put("asOf", clock.millis());
                frame.put("world", world);
                if (!"observed".equals(world)) {
                    sessions.getOrRestore(world, streamOwner)
                            .ifPresent(session -> frame.put("simTime", session.simTime().toString()));
                }
                client.sendEvent("quotes", frame);
            } catch (Exception ignored) {
                // The scheduled task keeps trying; close detection removes abandoned clients.
            }
        };
        push.run();
        int interval = Math.max(1, cfg.engineStreamIntervalSeconds());
        Future<?> task = scheduler.scheduleWithFixedDelay(push, interval, interval, TimeUnit.SECONDS);
        taskRef.set(task);
        client.onClose(() -> task.cancel(true));
    }

    private List<Map<String, Object>> quoteRows(String world, String owner, String raw,
                                                List<String> requestedSymbols) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if ("observed".equals(world)) {
            for (var snapshot : engine.quotes(requestedSymbols)) {
                rows.add(MarketDataEngine.toRow(snapshot));
            }
            return rows;
        }
        List<String> symbols;
        if ("demo".equals(world)) {
            symbols = raw == null || raw.isBlank()
                    ? market.worldSymbols("demo").map(List::copyOf).orElse(List.of())
                    : requestedSymbols;
        } else {
            symbols = sessions.getOrRestore(world, owner)
                    .map(session -> List.copyOf(session.config().symbolBetas().keySet()))
                    .orElse(List.of());
        }
        for (String symbol : symbols) {
            market.quote(symbol, world).ifPresent(quote -> rows.add(quoteRow(quote)));
        }
        return rows;
    }

    private static Map<String, Object> quoteRow(Quote quote) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", quote.symbol());
        row.put("description", quote.description());
        row.put("last", quote.last() == null ? null : quote.last().toPlainString());
        row.put("bid", quote.bid() == null ? null : quote.bid().toPlainString());
        row.put("ask", quote.ask() == null ? null : quote.ask().toPlainString());
        row.put("prevClose", quote.prevClose() == null ? null : quote.prevClose().toPlainString());
        row.put("optionable", quote.optionable());
        row.put("freshness", quote.markFreshness().name());
        row.put("evidence", quote.evidence());
        row.put("asOf", quote.asOfEpochMs());
        row.put("refreshing", false);
        return row;
    }

    void eventStream(SseClient client) {
        client.keepAlive();
        long last = events.currentSeq();
        String lastId = client.ctx().header("Last-Event-ID");
        if (lastId != null) {
            try {
                last = Long.parseLong(lastId.trim());
            } catch (NumberFormatException ignored) {
                // A malformed replay cursor starts from now.
            }
        }
        String caller = auth.enabled() ? ownerId.apply(client.ctx()) : null;
        boolean scoped = auth.enabled();
        Predicate<EventBus.Event> visible = event -> {
            if (!scoped) return true;
            Object owner = event.data().get("user");
            return owner == null || owner.equals(caller);
        };
        AtomicReference<Runnable> unsubscribeRef = new AtomicReference<>();
        Runnable unsubscribe = events.subscribe(event -> {
            if (client.terminated()) {
                Runnable close = unsubscribeRef.get();
                if (close != null) close.run();
                return;
            }
            if (visible.test(event)) sendEvent(client, event);
        });
        unsubscribeRef.set(unsubscribe);
        client.onClose(unsubscribe);
        for (EventBus.Event event : events.since(last)) {
            if (visible.test(event)) sendEvent(client, event);
        }
    }

    private static void sendEvent(SseClient client, EventBus.Event event) {
        try {
            synchronized (client) {
                client.sendEvent(event.type(), event.data(), String.valueOf(event.seq()));
            }
        } catch (Exception ignored) {
            // The client close callback or the next publish removes the subscription.
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
