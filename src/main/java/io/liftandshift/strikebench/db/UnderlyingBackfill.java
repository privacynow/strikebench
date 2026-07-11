package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.Candle;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Backfills daily {@code underlying_bar} history for a symbol from whatever candle source the
 * observed provider chain currently offers (Yahoo/Stooq/Polygon/Alpha Vantage). Provider-agnostic
 * and evidence-honest: non-observed results are rejected before any canonical row is written.
 * Idempotent upsert on {@code (symbol, d, source)}.
 *
 * <p>This is the writer the Data Center's "backfill underlying" job calls per symbol; once loaded,
 * the portfolio backtester and the research-question workbench read OBSERVED history instead of
 * re-fetching or falling back to the modeled tier.
 */
public final class UnderlyingBackfill {

    private final MarketDataService market;
    private final Db db;
    private final Clock clock;

    public UnderlyingBackfill(MarketDataService market, Db db, Clock clock) {
        this.market = market;
        this.db = db;
        this.clock = clock;
    }

    public record BackfillResult(String symbol, String source, boolean observed, int rows,
                                 LocalDate from, LocalDate to, String note) {}

    public BackfillResult backfill(String symbol, LocalDate from, LocalDate to) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (sym.isEmpty()) throw new IllegalArgumentException("symbol is required");
        if (from == null || to == null || from.isAfter(to)) throw new IllegalArgumentException("bad date range");

        // Providers ONLY: the stored-first read path would let a partial store answer its own
        // backfill request (an incomplete history could never grow past itself).
        CandleSeries series = market.candleSeriesFromProviders(sym, from, to);
        List<Candle> candles = series.candles();
        if (candles.isEmpty()) {
            return new BackfillResult(sym, series.source(), false, 0, from, to,
                    "No candle source returned data — set YAHOO_ENABLED, or a Polygon/Alpha Vantage key, for observed history.");
        }
        boolean observed = series.evidence().provenance()
                == io.liftandshift.strikebench.model.DataProvenance.OBSERVED;
        String source = series.source() == null ? "unknown" : series.source();
        if (!observed) {
            return new BackfillResult(sym, source, false, 0, from, to,
                    "The source returned generated/demo data. Observed backfill refused it; enter Demo or create a Scenario dataset explicitly.");
        }
        int rows = db.tx(c -> {
            int n = 0;
            for (Candle cd : candles) {
                if (cd.date().isBefore(from) || cd.date().isAfter(to)) continue;
                Db.execOn(c,
                        "INSERT INTO underlying_bar (symbol, d, open, high, low, close, volume, source, observed) "
                      + "VALUES (?,?,?,?,?,?,?,?,?) "
                      + "ON CONFLICT (symbol, d, source, dataset_id) DO UPDATE SET "
                      + "open=excluded.open, high=excluded.high, low=excluded.low, close=excluded.close, "
                      + "volume=excluded.volume, observed=excluded.observed",
                        sym, cd.date(), cd.open(), cd.high(), cd.low(), cd.close(), cd.volume(), source, observed);
                n++;
            }
            return n;
        });
        String note = "Loaded " + rows + " observed daily bars from " + source + ".";
        return new BackfillResult(sym, source, observed, rows, from, to, note);
    }

}
