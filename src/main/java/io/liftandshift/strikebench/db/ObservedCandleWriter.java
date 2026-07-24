package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.DataProvenance;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** One validated write path for observed daily bars, shared by backfill and read-through caching. */
final class ObservedCandleWriter {

    record Result(int written, int rejected) {}

    private ObservedCandleWriter() {}

    static Result write(Db db, String symbol, CandleSeries series) {
        if (series == null || series.evidence().provenance() != DataProvenance.OBSERVED) {
            throw new IllegalArgumentException("Only observed daily history may enter observed storage");
        }
        return write(db, symbol, series.source(), series.candles());
    }

    static Result write(Db db, String symbol, String source, List<Candle> candles) {
        String sym = normalizeSymbol(symbol);
        String src = normalizeSource(source);
        if (sym.isEmpty() || src.isEmpty()) throw new IllegalArgumentException("symbol and source are required");

        LinkedHashMap<LocalDate, Candle> accepted = new LinkedHashMap<>();
        java.util.Set<LocalDate> conflicts = new java.util.HashSet<>();
        int rejected = 0;
        for (Candle candle : candles == null ? List.<Candle>of() : candles) {
            if (UnderlyingBackfill.invalidReason(candle) != null) {
                rejected++;
                continue;
            }
            if (conflicts.contains(candle.date())) {
                rejected++;
                continue;
            }
            Candle prior = accepted.putIfAbsent(candle.date(), candle);
            if (prior != null && !prior.equals(candle)) {
                accepted.remove(candle.date());
                conflicts.add(candle.date());
                rejected++;
            }
        }
        if (accepted.isEmpty()) return new Result(0, rejected);

        db.tx(connection -> {
            for (Candle candle : accepted.values()) upsert(connection, sym, src, candle);
            return null;
        });
        return new Result(accepted.size(), rejected);
    }

    private static void upsert(Connection connection, String symbol, String source, Candle candle) {
        upsertObservedBar(connection, symbol, candle.date(), candle.open(), candle.high(), candle.low(),
                candle.close(), candle.volume(), source, candle.adjusted(), quality(source), "OHLCV");
    }

    /**
     * THE full-OHLC observed underlying_bar upsert — the column set and conflict clause live here
     * once, shared by the read-through/backfill writer and the CSV importer. Each caller supplies its
     * own quality rank and bar kind (so persisted metadata is unchanged). Only FULL OHLC bars belong
     * here; the partial close-snapshot writers (SnapshotService, HistoricalOptionsIngest) intentionally
     * write a different, narrower shape that the OHLC validator would reject.
     */
    static void upsertObservedBar(Connection connection, String symbol, LocalDate date,
            java.math.BigDecimal open, java.math.BigDecimal high, java.math.BigDecimal low,
            java.math.BigDecimal close, Long volume, String source, boolean adjusted,
            int qualityRank, String barKind) {
        try {
            Db.execOn(connection, "INSERT INTO underlying_bar "
                            + "(symbol,d,open,high,low,close,volume,source,observed,adjusted,quality_rank,bar_kind) "
                            + "VALUES (?,?,?,?,?,?,?,?,1,?,?,?) "
                            + "ON CONFLICT(symbol,d,source,dataset_id) DO UPDATE SET "
                            + "open=excluded.open,high=excluded.high,low=excluded.low,close=excluded.close,"
                            + "volume=excluded.volume,observed=1,adjusted=excluded.adjusted,"
                            + "quality_rank=excluded.quality_rank,bar_kind=excluded.bar_kind,created_at=now()",
                    symbol, date, open, high, low, close, volume, source, adjusted, qualityRank, barKind);
        } catch (java.sql.SQLException e) {
            throw new Db.DbException(e);
        }
    }

    private static int quality(String source) {
        return switch (source.toLowerCase(Locale.ROOT)) {
            case "polygon" -> 90;
            case "alphavantage" -> 85;
            case "yahoo" -> 60;
            case "stooq" -> 50;
            default -> 70;
        };
    }

    private static String normalizeSymbol(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeSource(String value) {
        if (value == null) return "";
        String source = value.trim().toLowerCase(Locale.ROOT);
        if (source.startsWith("stored:")) source = source.substring("stored:".length());
        return source;
    }
}
