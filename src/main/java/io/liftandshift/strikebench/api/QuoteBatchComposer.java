package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.market.MarketDataEngine;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketLane;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.model.Symbol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The one composer for batch and streaming quote rows. It owns no pricing: it turns the canonical
 * market-engine result into one {@link ApiResponses.QuoteView} for every requested symbol,
 * including malformed and currently unavailable symbols.
 */
final class QuoteBatchComposer {
    record RequestedSymbol(String display, String canonical, String invalidReason) {}

    record Result(List<ApiResponses.QuoteView> rows, int requested, int considered,
                  boolean truncated, MarketLane lane) {
        Result {
            rows = List.copyOf(rows);
        }
    }

    private final MarketDataService market;
    private final MarketDataEngine engine;
    private final UniverseService universe;

    QuoteBatchComposer(MarketDataService market, MarketDataEngine engine, UniverseService universe) {
        this.market = market;
        this.engine = engine;
        this.universe = universe;
    }

    Result compose(String raw, String world, int limit) {
        List<RequestedSymbol> requested = raw == null || raw.isBlank()
                ? null : parse(raw);
        return compose(requested, world, limit);
    }

    /**
     * A null request follows the active market's universe at composition time. An explicit list is
     * already tolerant-parsed and therefore preserves invalid rows without sending them to a
     * provider.
     */
    Result compose(List<RequestedSymbol> explicit, String world, int limit) {
        List<RequestedSymbol> requestedRows = explicit == null
                ? valid(MarketUniverseView.symbolsForWorld(market, universe, world))
                : List.copyOf(explicit);
        int requested = requestedRows.size();
        int boundedLimit = Math.max(1, limit);
        List<RequestedSymbol> bounded = requested > boundedLimit
                ? requestedRows.subList(0, boundedLimit) : requestedRows;
        MarketLane lane = market.lane(world);
        List<ApiResponses.QuoteView> rows = world == null
                ? observedRows(bounded) : worldRows(bounded, world, lane);
        return new Result(rows, requested, bounded.size(), requested > boundedLimit, lane);
    }

    static List<RequestedSymbol> parse(String raw) {
        List<RequestedSymbol> rows = new ArrayList<>();
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        for (String member : raw.split(",")) {
            String display = member == null ? "" : member.trim();
            if (display.isBlank()) continue;
            try {
                String canonical = Symbol.normalize(display);
                if (identities.add("valid:" + canonical)) {
                    rows.add(new RequestedSymbol(canonical, canonical, null));
                }
            } catch (IllegalArgumentException invalid) {
                String safe = display.replace('\n', ' ').replace('\r', ' ');
                String bounded = safe.substring(0, Math.min(safe.length(), 80));
                if (identities.add("invalid:" + bounded)) {
                    rows.add(new RequestedSymbol(bounded, null,
                            "Invalid symbol " + bounded + "; no market-data request was sent."));
                }
            }
        }
        return List.copyOf(rows);
    }

    private static List<RequestedSymbol> valid(List<String> symbols) {
        return Symbol.list(symbols).stream()
                .map(symbol -> new RequestedSymbol(symbol, symbol, null)).toList();
    }

    private List<ApiResponses.QuoteView> observedRows(List<RequestedSymbol> requests) {
        List<String> valid = requests.stream().filter(row -> row.invalidReason() == null)
                .map(RequestedSymbol::canonical).toList();
        Map<String, MarketDataEngine.MarketSnapshot> priced = new LinkedHashMap<>();
        String batchFailure = null;
        try {
            for (var snapshot : engine.quotes(valid)) priced.put(snapshot.symbol(), snapshot);
        } catch (RuntimeException failure) {
            batchFailure = "the market quote refresh failed; the stream will retry";
        }

        List<ApiResponses.QuoteView> rows = new ArrayList<>();
        for (RequestedSymbol request : requests) {
            if (request.invalidReason() != null) {
                rows.add(ApiResponses.QuoteView.unavailable(request.display(), request.invalidReason()));
                continue;
            }
            var snapshot = priced.get(request.canonical());
            String failure = batchFailure;
            rows.add(snapshot == null
                    ? ApiResponses.QuoteView.unavailable(request.canonical(), failure != null
                            ? failure + " for " + request.canonical()
                            : unavailableReason(request.canonical()))
                    : ApiResponses.QuoteView.of(snapshot.toQuote(), snapshot.refreshing()));
        }
        return List.copyOf(rows);
    }

    private List<ApiResponses.QuoteView> worldRows(List<RequestedSymbol> requests, String world,
                                                   MarketLane lane) {
        List<ApiResponses.QuoteView> rows = new ArrayList<>();
        for (RequestedSymbol request : requests) {
            if (request.invalidReason() != null) {
                rows.add(ApiResponses.QuoteView.unavailable(request.display(), request.invalidReason()));
                continue;
            }
            try {
                rows.add(engine.currentQuote(request.canonical(), world)
                        .map(quote -> ApiResponses.QuoteView.of(quote, false))
                        .orElseGet(() -> ApiResponses.QuoteView.unavailable(request.canonical(),
                                worldUnavailableReason(request.canonical(), world, lane))));
            } catch (RuntimeException failure) {
                rows.add(ApiResponses.QuoteView.unavailable(request.canonical(),
                        "the market quote refresh failed for " + request.canonical()
                                + "; the stream will retry"));
            }
        }
        return List.copyOf(rows);
    }

    private String unavailableReason(String symbol) {
        try {
            return engine.unavailableReason(symbol);
        } catch (RuntimeException failure) {
            return "the market has no current quote for " + symbol;
        }
    }

    private static String worldUnavailableReason(String symbol, String world, MarketLane lane) {
        if (lane == MarketLane.DEMO) {
            return "the demo market has no teaching quote for " + symbol;
        }
        return "simulated world " + world + " does not price " + symbol
                + " — its symbol set was fixed when the world was created";
    }
}
