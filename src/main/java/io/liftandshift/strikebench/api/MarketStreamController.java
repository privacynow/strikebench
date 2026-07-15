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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

/** Market-state and typed-hint SSE transports. */
final class MarketStreamController implements AutoCloseable {
    private final Clock clock;
    private final MarketDataService market;
    private final MarketDataEngine engine;
    private final UniverseService universe;
    private final SimulationSessions sessions;
    private final EventBus events;
    private final AuthService auth;
    private final Function<Context, String> ownerId;
    private final Function<String, String> activeWorldFor;
    private final MarketFrameBroadcaster broadcaster;
    private final Runnable unsubscribeWorldEvents;

    MarketStreamController(AppConfig cfg, Clock clock, MarketDataService market,
                           MarketDataEngine engine, UniverseService universe,
                           SimulationSessions sessions, EventBus events, AuthService auth,
                           Function<Context, String> ownerId,
                           Function<String, String> activeWorldFor) {
        this.clock = clock;
        this.market = market;
        this.engine = engine;
        this.universe = universe;
        this.sessions = sessions;
        this.events = events;
        this.auth = auth;
        this.ownerId = ownerId;
        this.activeWorldFor = activeWorldFor;
        this.broadcaster = new MarketFrameBroadcaster(
                Math.max(1, cfg.engineStreamIntervalSeconds()), this::loadFrame);
        this.unsubscribeWorldEvents = events.subscribe(event -> {
            if (!"world.selected".equals(event.type())) return;
            Object owner = event.data().get("user");
            broadcaster.invalidateOwner(owner == null ? null : String.valueOf(owner));
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
        var request = new MarketFrameBroadcaster.Request(streamOwner, symbols,
                raw != null && !raw.isBlank());
        Runnable unsubscribe = broadcaster.subscribe(request, frame -> {
            if (client.terminated()) return;
            synchronized (client) { client.sendEvent("quotes", frame.payload()); }
        });
        client.onClose(unsubscribe);
    }

    private MarketFrameBroadcaster.Draft loadFrame(MarketFrameBroadcaster.Request request) {
        String world = activeWorldFor.apply(request.owner());
        List<Map<String, Object>> rows = quoteRows(world, request.owner(),
                request.customSymbols(), request.symbols());
        String simTime = null;
        if (!"observed".equals(world)) {
            simTime = sessions.getOrRestore(world, request.owner())
                    .map(session -> session.simTime().toString()).orElse(null);
        }
        return new MarketFrameBroadcaster.Draft(world, rows, simTime, clock.millis());
    }

    private List<Map<String, Object>> quoteRows(String world, String owner, boolean customSymbols,
                                                List<String> requestedSymbols) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if ("observed".equals(world)) {
            List<String> symbols = customSymbols ? requestedSymbols : universe.active().symbols();
            for (var snapshot : engine.quotes(symbols)) {
                rows.add(MarketDataEngine.toRow(snapshot));
            }
            return rows;
        }
        List<String> symbols;
        if ("demo".equals(world)) {
            symbols = !customSymbols
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
        unsubscribeWorldEvents.run();
        broadcaster.close();
    }
}
