package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.model.Symbol;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

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
          + "bid_ask_observed, iv_source, greeks_source, source_observed_at) "
          + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
          + "ON CONFLICT (symbol, asof, expiration, strike, opt_type, source, dataset_id) DO UPDATE SET "
          + "bid=excluded.bid, ask=excluded.ask, last=excluded.last, mark=excluded.mark, iv=excluded.iv, "
          + "delta=excluded.delta, gamma=excluded.gamma, theta=excluded.theta, vega=excluded.vega, "
          + "open_interest=excluded.open_interest, volume=excluded.volume, underlying=excluded.underlying, "
          + "bid_ask_observed=excluded.bid_ask_observed, iv_source=excluded.iv_source, "
          + "greeks_source=excluded.greeks_source,source_observed_at=excluded.source_observed_at";

    /** One observed option bar. {@code bidAskObserved} maps to the integer column (1/0). */
    public record Row(String symbol, LocalDate asof, LocalDate expiration, BigDecimal strike, String optType,
                      BigDecimal bid, BigDecimal ask, BigDecimal last, BigDecimal mark, Double iv,
                      Double delta, Double gamma, Double theta, Double vega, Long openInterest, Long volume,
                      BigDecimal underlying, String source, boolean bidAskObserved, String ivSource,
                      String greeksSource, Instant sourceObservedAt) {
        public Row {
            symbol = Symbol.normalize(symbol);
            if (asof == null || expiration == null || expiration.isBefore(asof)) {
                throw new IllegalArgumentException("option bar requires an as-of date and non-expired contract");
            }
            if (strike == null || strike.signum() <= 0) {
                throw new IllegalArgumentException("option bar strike must be positive");
            }
            optType = optType == null ? "" : optType.trim().toUpperCase(Locale.ROOT);
            if (!"CALL".equals(optType) && !"PUT".equals(optType)) {
                throw new IllegalArgumentException("option bar type must be CALL or PUT");
            }
            source = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
            if (source.isEmpty()) throw new IllegalArgumentException("option bar source is required");
            requireNonNegative("bid", bid);
            requireNonNegative("ask", ask);
            requireNonNegative("last", last);
            requireNonNegative("mark", mark);
            if (bid != null && ask != null && bid.compareTo(ask) > 0) {
                throw new IllegalArgumentException("option bar bid cannot exceed ask");
            }
            requireFiniteNonNegative("iv", iv);
            requireFinite("delta", delta);
            requireFinite("gamma", gamma);
            requireFinite("theta", theta);
            requireFinite("vega", vega);
            if (openInterest != null && openInterest < 0) {
                throw new IllegalArgumentException("open interest cannot be negative");
            }
            if (volume != null && volume < 0) {
                throw new IllegalArgumentException("volume cannot be negative");
            }
            if (underlying != null && underlying.signum() <= 0) {
                throw new IllegalArgumentException("underlying price must be positive");
            }
            if (bidAskObserved && (bid == null || ask == null)) {
                throw new IllegalArgumentException("observed bid/ask requires both sides");
            }
        }

        private static void requireNonNegative(String field, BigDecimal value) {
            if (value != null && value.signum() < 0) {
                throw new IllegalArgumentException(field + " cannot be negative");
            }
        }

        private static void requireFinite(String field, Double value) {
            if (value != null && !Double.isFinite(value)) {
                throw new IllegalArgumentException(field + " must be finite");
            }
        }

        private static void requireFiniteNonNegative(String field, Double value) {
            requireFinite(field, value);
            if (value != null && value < 0) {
                throw new IllegalArgumentException(field + " cannot be negative");
            }
        }
    }

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
        ps.setObject(++i, r.sourceObservedAt() == null ? null
                : java.time.OffsetDateTime.ofInstant(r.sourceObservedAt(), java.time.ZoneOffset.UTC));
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
