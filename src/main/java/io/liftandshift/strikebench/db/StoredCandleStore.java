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
 * Reads persisted daily candles from {@code underlying_bar}. One row per date is chosen (best source
 * first: observed over demo), so a Data Center backfill (Yahoo/Stooq/Polygon/AV/CSV) actually powers
 * Research and the backtesters. Evidence is honest: EOD when any chosen row is observed, FIXTURE when
 * all are demo — so stored demo bars never masquerade as real. Empty result ⇒ the provider chain runs.
 */
public final class StoredCandleStore implements CandleStore {

    private final Db db;

    public StoredCandleStore(Db db) { this.db = db; }

    @Override
    public Optional<CandleSeries> candles(String symbol, LocalDate from, LocalDate to) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (sym.isEmpty() || from == null || to == null || from.isAfter(to)) return Optional.empty();
        // One row per day, preferring observed real data over demo, then a stable source order.
        // Scoped to the OBSERVED dataset — synthetic runs live under their own dataset_id and are
        // read only when explicitly selected (recommendations must default to observed data).
        List<Row> rows = db.query(
                "SELECT DISTINCT ON (d) d::text d, open, high, low, close, volume, source, observed "
              + "FROM underlying_bar WHERE symbol=? AND dataset_id='observed' AND d BETWEEN ? AND ? "
              + "ORDER BY d, observed DESC, source",
                r -> new Row(LocalDate.parse(r.str("d")),
                        r.bd("open"), r.bd("high"), r.bd("low"), r.bd("close"),
                        r.lng("volume"), r.lng("observed") == 1),
                sym, from, to);
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
        return Optional.of(new CandleSeries(candles, "stored", anyObserved ? Freshness.EOD : Freshness.FIXTURE));
    }

    private record Row(LocalDate d, java.math.BigDecimal open, java.math.BigDecimal high,
                       java.math.BigDecimal low, java.math.BigDecimal close, long volume, boolean observed) {}
}
