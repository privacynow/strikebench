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
 * the default 'observed' dataset this serves only attributable observed backfills/snapshots (one row
 * per date; Demo rows are physically quarantined and also excluded here). When the user selects a synthetic dataset in
 * the Data Center, its bars serve instead — labeled MODELED with source 'synthetic' so scenario mode
 * can never masquerade as market data. Empty result ⇒ the provider chain runs.
 */
public final class StoredCandleStore implements CandleStore {

    private final Db db;

    public StoredCandleStore(Db db) { this.db = db; }

    /** Back-compat ctor: the dataset now arrives per call, so the service handle is unused. */
    public StoredCandleStore(Db db, DatasetService datasets) { this(db); }

    @Override
    public Optional<CandleSeries> candles(String symbol, LocalDate from, LocalDate to, String datasetId) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (sym.isEmpty() || from == null || to == null || from.isAfter(to)) return Optional.empty();
        String dataset = datasetId == null || datasetId.isBlank() ? DatasetService.OBSERVED : datasetId;
        boolean synthetic = !DatasetService.OBSERVED.equals(dataset);
        // Observed means observed: legacy/demo rows in the canonical dataset are not eligible
        // even as a weakest-link fallback. Scenario datasets intentionally contain modeled rows.
        String provenanceClause = synthetic ? " " : " AND observed=1 ";
        List<Row> rows = db.query(
                "SELECT DISTINCT ON (d) d::text d, open, high, low, close, volume, source, observed "
              + "FROM underlying_bar WHERE symbol=? AND dataset_id=? AND d BETWEEN ? AND ? "
              + provenanceClause
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

        if (synthetic) {
            // Scenario mode: the dataset IS the analysis world — serve whatever it holds, MODELED.
            return Optional.of(new CandleSeries(candles, "synthetic", Freshness.MODELED));
        }
        // Observed dataset: the store satisfies the request ONLY when it actually COVERS the
        // requested range — two stray rows must never silence the provider chain on a five-year
        // ask (and, via the backfill path, freeze an incomplete store forever).
        if (!coversRange(candles, from, to)) return Optional.empty();
        return Optional.of(new CandleSeries(candles, "stored-observed", Freshness.EOD));
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
