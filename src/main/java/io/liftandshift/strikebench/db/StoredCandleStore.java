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

        boolean anyObserved = false;
        List<Candle> candles = new java.util.ArrayList<>(rows.size());
        for (Row r : rows) {
            if (r.close == null) continue;
            anyObserved |= r.observed;
            candles.add(new Candle(r.d,
                    r.open == null ? r.close : r.open,
                    r.high == null ? r.close : r.high,
                    r.low == null ? r.close : r.low,
                    r.close, r.volume, false));
        }
        if (candles.size() < 2) return Optional.empty();
        boolean synthetic = !DatasetService.OBSERVED.equals(dataset);
        return Optional.of(new CandleSeries(candles,
                synthetic ? "synthetic" : "stored",
                synthetic ? Freshness.MODELED : (anyObserved ? Freshness.EOD : Freshness.FIXTURE)));
    }

    private record Row(LocalDate d, java.math.BigDecimal open, java.math.BigDecimal high,
                       java.math.BigDecimal low, java.math.BigDecimal close, long volume, boolean observed) {}
}
