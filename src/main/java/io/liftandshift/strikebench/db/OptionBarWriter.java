package io.liftandshift.strikebench.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * THE one {@code option_bar} upsert. The column set and conflict clause live here once; the two
 * observed writers — the forward snapshot ({@code SnapshotService}) and the historical vendor ingest
 * ({@code HistoricalOptionsIngest}) — build a {@link Row} with their own mark/source policy (snapshot
 * uses the quote midpoint; ingest uses last-as-mark) and bind it through the one statement. No second
 * copy of the option_bar SQL exists.
 */
public final class OptionBarWriter {

    private OptionBarWriter() {}

    public static final String UPSERT_SQL =
            "INSERT INTO option_bar (symbol, asof, expiration, strike, opt_type, bid, ask, last, mark, "
          + "iv, delta, gamma, theta, vega, open_interest, volume, underlying, source, "
          + "bid_ask_observed, iv_source, greeks_source) "
          + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
          + "ON CONFLICT (symbol, asof, expiration, strike, opt_type, source, dataset_id) DO UPDATE SET "
          + "bid=excluded.bid, ask=excluded.ask, last=excluded.last, mark=excluded.mark, iv=excluded.iv, "
          + "delta=excluded.delta, gamma=excluded.gamma, theta=excluded.theta, vega=excluded.vega, "
          + "open_interest=excluded.open_interest, volume=excluded.volume, underlying=excluded.underlying, "
          + "bid_ask_observed=excluded.bid_ask_observed, iv_source=excluded.iv_source, greeks_source=excluded.greeks_source";

    /** One observed option bar. {@code bidAskObserved} maps to the integer column (1/0). */
    public record Row(String symbol, LocalDate asof, LocalDate expiration, BigDecimal strike, String optType,
                      BigDecimal bid, BigDecimal ask, BigDecimal last, BigDecimal mark, Double iv,
                      Double delta, Double gamma, Double theta, Double vega, Long openInterest, Long volume,
                      BigDecimal underlying, String source, boolean bidAskObserved, String ivSource,
                      String greeksSource) {}

    /** Bind one Row onto a statement prepared with {@link #UPSERT_SQL}, in the canonical column order. */
    public static void bind(PreparedStatement ps, Row r) throws SQLException {
        int i = 0;
        ps.setObject(++i, r.symbol()); ps.setObject(++i, r.asof()); ps.setObject(++i, r.expiration());
        ps.setObject(++i, r.strike()); ps.setObject(++i, r.optType());
        ps.setObject(++i, r.bid()); ps.setObject(++i, r.ask()); ps.setObject(++i, r.last());
        ps.setObject(++i, r.mark()); ps.setObject(++i, r.iv());
        ps.setObject(++i, r.delta()); ps.setObject(++i, r.gamma()); ps.setObject(++i, r.theta());
        ps.setObject(++i, r.vega()); ps.setObject(++i, r.openInterest()); ps.setObject(++i, r.volume());
        ps.setObject(++i, r.underlying()); ps.setObject(++i, r.source());
        ps.setInt(++i, r.bidAskObserved() ? 1 : 0);
        ps.setObject(++i, r.ivSource()); ps.setObject(++i, r.greeksSource());
    }

    /** Single-row upsert on an existing connection (the snapshot writer's per-leg path). */
    public static void upsertOn(Connection connection, Row r) {
        try (PreparedStatement ps = connection.prepareStatement(UPSERT_SQL)) {
            bind(ps, r);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new Db.DbException(e);
        }
    }
}
