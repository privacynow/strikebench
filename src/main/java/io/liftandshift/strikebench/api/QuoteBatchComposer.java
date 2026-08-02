package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.market.MarketDataEngine;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketMode;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.model.Symbol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The one composer for batch and streaming quote rows. It owns no pricing: it turns the normalized
 * market-engine result into one {@link ApiResponses.QuoteView} for every requested symbol,
 * including malformed and currently unavailable symbols.
 */
final class QuoteBatchComposer {
    record RequestedSymbol(String display, String normalized, String invalidReason) {}

    record Result(List<ApiResponses.QuoteView> rows, int requested, int considered,
                  boolean truncated, MarketMode mode) {
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
        MarketMode mode = market.mode(world, io.liftandshift.strikebench.db.AnalysisContext.OBSERVED);
        List<ApiResponses.QuoteView> rows = world == null
                ? observedRows(bounded) : worldRows(bounded, world, mode);
        return new Result(rows, requested, bounded.size(), requested > boundedLimit, mode);
    }

    static List<RequestedSymbol> parse(String raw) {
        List<RequestedSymbol> rows = new ArrayList<>();
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        for (String member : raw.split(",")) {
            String display = member == null ? "" : member.trim();
            if (display.isBlank()) continue;
            try {
                String normalized = Symbol.normalize(display);
                if (identities.add("valid:" + normalized)) {
                    rows.add(new RequestedSymbol(normalized, normalized, null));
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
                .map(RequestedSymbol::normalized).toList();
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
            var snapshot = priced.get(request.normalized());
            String failure = batchFailure;
            rows.add(snapshot == null
                    ? ApiResponses.QuoteView.unavailable(request.normalized(), failure != null
                            ? failure + " for " + request.normalized()
                            : unavailableReason(request.normalized()))
                    : ApiResponses.QuoteView.of(snapshot.toQuote(), snapshot.refreshing()));
        }
        return List.copyOf(rows);
    }

    private List<ApiResponses.QuoteView> worldRows(List<RequestedSymbol> requests, String world,
                                                   MarketMode mode) {
        List<ApiResponses.QuoteView> rows = new ArrayList<>();
        for (RequestedSymbol request : requests) {
            if (request.invalidReason() != null) {
                rows.add(ApiResponses.QuoteView.unavailable(request.display(), request.invalidReason()));
                continue;
            }
            try {
                rows.add(engine.currentQuote(request.normalized(), world)
                        .map(quote -> ApiResponses.QuoteView.of(quote, false))
                        .orElseGet(() -> ApiResponses.QuoteView.unavailable(request.normalized(),
                                worldUnavailableReason(request.normalized(), world, mode))));
            } catch (RuntimeException failure) {
                rows.add(ApiResponses.QuoteView.unavailable(request.normalized(),
                        "the market quote refresh failed for " + request.normalized()
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

    private static String worldUnavailableReason(String symbol, String world, MarketMode mode) {
        if (mode == MarketMode.DEMO) {
            return "the demo market has no teaching quote for " + symbol;
        }
        return "simulated world " + world + " does not price " + symbol
                + " — its symbol set was fixed when the world was created";
    }
}
