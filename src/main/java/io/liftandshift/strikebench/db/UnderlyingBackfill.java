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
    private final MissingRangePlanner planner;
    private final DataSyncState syncState;

    public UnderlyingBackfill(MarketDataService market, Db db, Clock clock) {
        this.market = market;
        this.db = db;
        this.clock = clock;
        this.planner = new MissingRangePlanner(db);
        this.syncState = new DataSyncState(db, clock);
    }

    public record BackfillResult(String symbol, String source, boolean observed, int rows,
                                 LocalDate from, LocalDate to, String note,
                                 int missingBefore, int rangesRequested, boolean complete, int quarantined) {}

    public BackfillResult backfill(String symbol, LocalDate from, LocalDate to) {
        return backfill(symbol, from, to, "auto", null, null);
    }

    public BackfillResult backfill(String symbol, LocalDate from, LocalDate to,
                                   String requestedSource, String ownerId, String jobId) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (sym.isEmpty()) throw new IllegalArgumentException("symbol is required");
        if (from == null || to == null || from.isAfter(to)) throw new IllegalArgumentException("bad date range");

        String sourceRequest = requestedSource == null || requestedSource.isBlank()
                ? "auto" : requestedSource.trim().toLowerCase(Locale.ROOT);
        MissingRangePlanner.Plan plan = planner.plan(sym, from, to);
        if (plan.complete()) {
            String note = "Observed daily history already covers this range; no provider request was needed.";
            syncState.succeeded(ownerId, sourceRequest, sym, from, to, to, 0, true, note);
            return new BackfillResult(sym, sourceRequest, true, 0, from, to, note, 0, 0, true, 0);
        }

        syncState.attempted(ownerId, sourceRequest, sym, from, to);
        int rows = 0, quarantined = 0;
        String actualSource = sourceRequest;
        LocalDate last = null;
        try {
            List<MissingRangePlanner.Range> ranges = plan.ranges();
            // Alpha's daily endpoint returns a compact/full snapshot regardless of the requested
            // sub-range. One request per symbol is sufficient; fragmented gaps must not multiply it.
            if ("alphavantage".equals(sourceRequest) && ranges.size() > 1) {
                ranges = List.of(new MissingRangePlanner.Range(ranges.getFirst().from(),
                        ranges.getLast().to(), plan.missingSessions()));
            }
            for (MissingRangePlanner.Range range : ranges) {
                // Providers ONLY: a partial store must never answer its own backfill request.
                CandleSeries series = "auto".equals(sourceRequest)
                        ? market.candleSeriesFromProviders(sym, range.from(), range.to())
                        : market.candleSeriesFromProvider(sourceRequest, sym, range.from(), range.to());
                List<Candle> candles = series.candles();
                if (candles.isEmpty()) continue;
                boolean observed = series.evidence().provenance()
                        == io.liftandshift.strikebench.model.DataProvenance.OBSERVED;
                actualSource = series.source() == null ? sourceRequest : series.source();
                if (!observed) {
                    String note = "The source returned generated data. Observed storage refused it.";
                    syncState.failed(ownerId, sourceRequest, sym, from, to, note);
                    return new BackfillResult(sym, actualSource, false, rows, from, to, note,
                            plan.missingSessions(), plan.ranges().size(), false, quarantined);
                }
                List<Candle> accepted = new java.util.ArrayList<>();
                for (Candle cd : candles) {
                    if (cd.date().isBefore(range.from()) || cd.date().isAfter(range.to())) continue;
                    String invalid = invalidReason(cd);
                    if (invalid != null) {
                        quarantined++;
                        syncState.quarantine(jobId, actualSource, sym, cd.date().toString(), invalid, candleExcerpt(cd));
                        continue;
                    }
                    accepted.add(cd);
                    if (last == null || cd.date().isAfter(last)) last = cd.date();
                }
                if (!accepted.isEmpty()) {
                    String sourceForWrite = actualSource;
                    db.tx(c -> {
                        for (Candle cd : accepted) upsert(c, sym, sourceForWrite, cd);
                        return null;
                    });
                    rows += accepted.size();
                }
            }
            MissingRangePlanner.Plan after = planner.plan(sym, from, to);
            boolean complete = after.complete();
            String note = rows == 0
                    ? "The selected source returned no eligible daily bars for the missing range."
                    : "Saved " + rows + " observed daily bars from " + actualSource
                      + (quarantined > 0 ? "; quarantined " + quarantined + " invalid row(s)" : "")
                      + (complete ? ". Coverage is complete." : ". Some requested sessions remain missing.");
            syncState.succeeded(ownerId, sourceRequest, sym, from, to, last, rows, complete, note);
            return new BackfillResult(sym, actualSource, rows > 0, rows, from, to, note,
                    plan.missingSessions(), plan.ranges().size(), complete, quarantined);
        } catch (RuntimeException e) {
            syncState.failed(ownerId, sourceRequest, sym, from, to, publicNote(e));
            throw e;
        }
    }

    private void upsert(java.sql.Connection connection, String symbol, String source, Candle cd) {
        try {
            Db.execOn(connection, "INSERT INTO underlying_bar (symbol,d,open,high,low,close,volume,source,observed,adjusted,quality_rank) "
                        + "VALUES (?,?,?,?,?,?,?,?,1,?,?) ON CONFLICT(symbol,d,source,dataset_id) DO UPDATE SET "
                        + "open=excluded.open,high=excluded.high,low=excluded.low,close=excluded.close,"
                        + "volume=excluded.volume,observed=1,adjusted=excluded.adjusted,quality_rank=excluded.quality_rank,created_at=now()",
                symbol, cd.date(), cd.open(), cd.high(), cd.low(), cd.close(), cd.volume(), source,
                cd.adjusted(), quality(source));
        } catch (java.sql.SQLException e) {
            throw new Db.DbException(e);
        }
    }

    private static int quality(String source) {
        return switch (source == null ? "" : source.toLowerCase(Locale.ROOT)) {
            case "polygon" -> 90;
            case "alphavantage" -> 85;
            case "yahoo" -> 60;
            case "stooq" -> 50;
            default -> 70;
        };
    }

    static String invalidReason(Candle c) {
        if (c == null || c.date() == null || c.close() == null || c.close().signum() <= 0) return "missing or non-positive close";
        if (c.open() == null || c.high() == null || c.low() == null) return "missing OHLC field";
        if (c.open().signum() <= 0 || c.high().signum() <= 0 || c.low().signum() <= 0) return "non-positive OHLC field";
        if (c.low().compareTo(c.high()) > 0) return "low is above high";
        if (c.high().compareTo(c.open().max(c.close())) < 0) return "high is below open or close";
        if (c.low().compareTo(c.open().min(c.close())) > 0) return "low is above open or close";
        if (c.volume() < 0) return "negative volume";
        return null;
    }

    private static String candleExcerpt(Candle c) {
        return c.date() + "," + c.open() + "," + c.high() + "," + c.low() + "," + c.close() + "," + c.volume();
    }

    private static String publicNote(RuntimeException e) {
        if (e instanceof ProviderRequestBudget.Exhausted) return e.getMessage();
        return "The source request failed; the cursor was preserved for a later retry.";
    }

}
