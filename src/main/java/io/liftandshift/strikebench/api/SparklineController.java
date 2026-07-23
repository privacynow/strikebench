package io.liftandshift.strikebench.api;
import static io.liftandshift.strikebench.market.MarketLane.worldParam;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.javalin.http.Context;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.DataEvidence;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Batch card-history reads plus their data-version-aware negative cache. */
final class SparklineController {
    private final Clock clock;
    private final MarketDataService market;
    private final UniverseService universe;
    private final EvaluationService evaluations;
    private final Function<Context, String> activeWorld;
    private final Function<Context, AnalysisContext> analysisContext;
    private final Cache<String, Boolean> emptyMemo = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(15)).maximumSize(500).build();
    private final AtomicLong historicalDataVersion = new AtomicLong();

    SparklineController(Clock clock, MarketDataService market, UniverseService universe,
                        EvaluationService evaluations, Function<Context, String> activeWorld,
                        Function<Context, AnalysisContext> analysisContext) {
        this.clock = clock;
        this.market = market;
        this.universe = universe;
        this.evaluations = evaluations;
        this.activeWorld = activeWorld;
        this.analysisContext = analysisContext;
    }

    void invalidate() {
        historicalDataVersion.incrementAndGet();
        emptyMemo.invalidateAll();
        market.invalidateHistoricalData();
        evaluations.invalidateHistoricalData();
    }

    void sparklines(Context ctx) {
        String raw = ctx.queryParam("symbols");
        String requested = ctx.queryParam("range") == null
                ? "3m" : ctx.queryParam("range").toLowerCase(Locale.ROOT);
        String range = switch (requested) {
            case "1m", "3m", "6m", "ytd", "1y" -> requested;
            default -> "3m";
        };
        String world = worldParam(activeWorld.apply(ctx));
        LocalDate today = market.simInstant(world)
                .map(instant -> LocalDate.ofInstant(instant, MarketHours.EASTERN))
                .orElseGet(() -> LocalDate.now(clock));
        int days = switch (range) {
            case "1m" -> 30;
            case "6m" -> 182;
            case "ytd" -> today.getDayOfYear();
            case "1y" -> 365;
            default -> 91;
        };
        LocalDate from = today.minusDays(days);
        List<String> symbols = raw == null || raw.isBlank()
                ? MarketUniverseView.symbolsForWorld(market, universe, world)
                : java.util.Arrays.stream(raw.split(","))
                    .map(symbol -> symbol.trim().toUpperCase(Locale.ROOT))
                    .filter(symbol -> !symbol.isBlank()).distinct().toList();
        int totalRequested = symbols.size();
        if (totalRequested > 16) symbols = symbols.subList(0, 16);

        AnalysisContext context = analysisContext.apply(ctx);
        String lane = world != null ? world : "observed";
        long dataVersion = historicalDataVersion.get();
        Map<String, Map<String, Object>> rows = new ConcurrentHashMap<>();
        Semaphore gate = new Semaphore(2);
        List<Thread> workers = new ArrayList<>();
        for (String symbol : symbols) {
            workers.add(Thread.startVirtualThread(() -> loadRow(rows, gate, symbol, from,
                    today, world, context, lane, range, dataVersion)));
        }
        for (Thread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        List<Map<String, Object>> output = new ArrayList<>();
        for (String symbol : symbols) {
            Map<String, Object> row = rows.get(symbol);
            if (row != null) output.add(row);
        }
        ctx.json(new ApiResponses.Sparklines<>(range, output, totalRequested, world));
    }

    private void loadRow(Map<String, Map<String, Object>> rows, Semaphore gate, String symbol,
                         LocalDate from, LocalDate today, String world, AnalysisContext context,
                         String lane, String range, long dataVersion) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        String memoKey = dataVersion + "|" + lane + "|" + context + "|" + symbol + "|" + range;
        if (world == null && emptyMemo.getIfPresent(memoKey) != null) {
            unavailable(row, "No daily-candle source for this symbol right now — quotes still work.",
                    DataEvidence.missing("daily history unavailable"));
            rows.put(symbol, row);
            return;
        }
        try {
            gate.acquire();
            try {
                var series = market.candleSeries(symbol, from, today, world, context);
                List<Candle> candles = series == null ? List.of() : series.candles();
                if (candles.size() < 2) {
                    if (world == null) emptyMemo.put(memoKey, Boolean.TRUE);
                    unavailable(row,
                            "No daily-candle source for this symbol right now — quotes still work.",
                            series == null ? DataEvidence.missing("daily history unavailable")
                                    : series.evidence());
                } else {
                    int stride = Math.max(1, (int) Math.ceil(candles.size() / 64.0));
                    List<String> dates = new ArrayList<>();
                    List<BigDecimal> closes = new ArrayList<>();
                    for (int i = 0; i < candles.size(); i += stride) {
                        Candle candle = candles.get(i);
                        dates.add(candle.date().toString());
                        closes.add(candle.close());
                    }
                    Candle last = candles.getLast();
                    if (!dates.getLast().equals(last.date().toString())) {
                        dates.add(last.date().toString());
                        closes.add(last.close());
                    }
                    row.put("available", true);
                    row.put("dates", dates);
                    row.put("closes", closes);
                    row.put("source", series.source());
                    row.put("freshness", series.freshness().name());
                    row.put("evidence", series.evidence());
                }
            } finally {
                gate.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            unavailable(row, "History lookup was interrupted — try again.",
                    DataEvidence.missing("history interrupted"));
        } catch (RuntimeException e) {
            unavailable(row, "History lookup failed — try again or check Data source health.",
                    DataEvidence.missing("history lookup failed"));
        }
        rows.put(symbol, row);
    }

    private static void unavailable(Map<String, Object> row, String note, DataEvidence evidence) {
        row.put("available", false);
        row.put("closes", List.of());
        row.put("note", note);
        row.put("evidence", evidence);
    }

}
