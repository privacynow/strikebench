package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.model.Symbol;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.liftandshift.strikebench.auth.AuthService;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataEngine;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.market.sim.SimulationSessions;
import io.liftandshift.strikebench.util.EventBus;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
                : Symbol.list(java.util.Arrays.asList(raw.split(","))).stream().limit(60).toList();
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
        List<ApiResponses.QuoteView> rows = quoteRows(world, request.owner(),
                request.customSymbols(), request.symbols());
        String simTime = null;
        if (!"observed".equals(world)) {
            simTime = sessions.getOrRestore(world, request.owner())
                    .map(session -> session.simTime().toString()).orElse(null);
        }
        return new MarketFrameBroadcaster.Draft(world, rows, simTime, clock.millis());
    }

    /** The live tape serves the SAME typed row as the /api/quotes batch — one shape, one price. */
    private List<ApiResponses.QuoteView> quoteRows(String world, String owner, boolean customSymbols,
                                                   List<String> requestedSymbols) {
        List<ApiResponses.QuoteView> rows = new ArrayList<>();
        if ("observed".equals(world)) {
            List<String> symbols = customSymbols ? requestedSymbols : universe.active().symbols();
            for (var snapshot : engine.quotes(symbols)) {
                rows.add(ApiResponses.QuoteView.of(snapshot.toQuote(), snapshot.refreshing()));
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
            engine.currentQuote(symbol, world)
                    .ifPresent(quote -> rows.add(ApiResponses.QuoteView.of(quote, false)));
        }
        return rows;
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
        Predicate<EventBus.Event> visible = event -> visibleToCaller(event, scoped, caller);
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

    /**
     * Authenticated event visibility. Most missing-user events are intentionally global system
     * hints (for example provider cooldowns). alerts.updated is intrinsically owner-scoped, so a
     * missing user is malformed and fails closed instead of being broadcast to every account.
     */
    static boolean visibleToCaller(EventBus.Event event, boolean scoped, String caller) {
        if (!scoped) return true;
        Object owner = event.data().get("user");
        if (owner != null) return owner.equals(caller);
        return !"alerts.updated".equals(event.type());
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
