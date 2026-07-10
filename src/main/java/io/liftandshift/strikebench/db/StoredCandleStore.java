package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.ports.CandleStore;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads persisted daily candles from {@code underlying_bar} for the ACTIVE analysis dataset. With
 * the default 'observed' dataset this serves real backfills/snapshots (one row per date, observed
 * preferred over demo, labeled EOD/FIXTURE honestly). When the user selects a synthetic dataset in
 * the Data Center, its bars serve instead — labeled MODELED with source 'synthetic' so scenario mode
 * can never masquerade as market data. Empty result ⇒ the provider chain runs.
 */
public final class StoredCandleStore implements CandleStore {

    private final Db db;
    private final DatasetService datasets; // nullable: observed-only mode (unit tests)

    public StoredCandleStore(Db db) { this(db, null); }

    public StoredCandleStore(Db db, DatasetService datasets) {
        this.db = db;
        this.datasets = datasets;
    }

    private String activeId() { return datasets == null ? DatasetService.OBSERVED : datasets.activeId(); }

    @Override public String cacheKey() { return activeId(); }

    @Override
    public Optional<CandleSeries> candles(String symbol, LocalDate from, LocalDate to) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (sym.isEmpty() || from == null || to == null || from.isAfter(to)) return Optional.empty();
        String dataset = activeId();
        // One row per day, preferring observed real data over demo, then a stable source order.
        List<Row> rows = db.query(
                "SELECT DISTINCT ON (d) d::text d, open, high, low, close, volume, source, observed "
              + "FROM underlying_bar WHERE symbol=? AND dataset_id=? AND d BETWEEN ? AND ? "
              + "ORDER BY d, observed DESC, source",
                r -> new Row(LocalDate.parse(r.str("d")),
                        r.bd("open"), r.bd("high"), r.bd("low"), r.bd("close"),
                        r.lng("volume"), r.lng("observed") == 1),
                sym, dataset, from, to);
        if (rows.size() < 2) return Optional.empty(); // not enough to be useful; fall through to providers

        List<Candle> candles = new java.util.ArrayList<>(rows.size());
        boolean allObserved = true;
        for (Row r : rows) {
            if (r.close == null) continue;
            allObserved &= r.observed;
            candles.add(new Candle(r.d,
                    r.open == null ? r.close : r.open,
                    r.high == null ? r.close : r.high,
                    r.low == null ? r.close : r.low,
                    r.close, r.volume, false));
        }
        if (candles.size() < 2) return Optional.empty();

        boolean synthetic = !DatasetService.OBSERVED.equals(dataset);
        if (synthetic) {
            // Scenario mode: the dataset IS the analysis world — serve whatever it holds, MODELED.
            return Optional.of(new CandleSeries(candles, "synthetic", Freshness.MODELED));
        }
        // Observed dataset: the store satisfies the request ONLY when it actually COVERS the
        // requested range — two stray rows must never silence the provider chain on a five-year
        // ask (and, via the backfill path, freeze an incomplete store forever).
        if (!coversRange(candles, from, to)) return Optional.empty();
        // Weakest-link evidence: a series containing ANY demo bar is labeled FIXTURE, never EOD —
        // one real row must not launder fabricated neighbors.
        return Optional.of(new CandleSeries(candles, "stored", allObserved ? Freshness.EOD : Freshness.FIXTURE));
    }

    /** Head within a week of `from`, tail within a week of `to`, and ≥60% of expected trading days. */
    private static boolean coversRange(List<Candle> candles, LocalDate from, LocalDate to) {
        LocalDate first = candles.getFirst().date(), last = candles.getLast().date();
        if (first.isAfter(from.plusDays(7)) || last.isBefore(to.minusDays(7))) return false;
        long weekdays = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            java.time.DayOfWeek w = d.getDayOfWeek();
            if (w != java.time.DayOfWeek.SATURDAY && w != java.time.DayOfWeek.SUNDAY) weekdays++;
        }
        return candles.size() >= Math.max(2, Math.round(weekdays * 0.6));
    }

    private record Row(LocalDate d, java.math.BigDecimal open, java.math.BigDecimal high,
                       java.math.BigDecimal low, java.math.BigDecimal close, long volume, boolean observed) {}
}
