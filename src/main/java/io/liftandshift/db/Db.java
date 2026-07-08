package io.liftandshift.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.liftandshift.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Thin JDBC helper around a HikariCP-pooled PostgreSQL database. One pooled connection per
 * operation; transactions via {@link #tx}. The public query/exec/Row surface is unchanged from
 * the previous SQLite helper so the services above are untouched by the storage swap.
 *
 * Money stays exact: integer cents columns map to Java {@code long}, per-share prices to
 * {@code numeric} / {@link java.math.BigDecimal}. Doubles appear only for non-money ratios
 * (IV, greeks, probabilities). Boolean flags are stored as integer 0/1 (see {@link #prep} and
 * {@link Row#bool}) so the existing call sites bind and read them unchanged.
 */
public final class Db implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Db.class);
    private final HikariDataSource ds;

    public Db(String jdbcUrl, String user, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("strikebench");
        cfg.setConnectionTimeout(10_000);
        this.ds = new HikariDataSource(cfg);
        log.info("Postgres pool ready ({})", jdbcUrl.replaceAll("password=[^&]*", "password=***"));
    }

    /** Builds a pool from app config (env > sysprops > properties > local-dev default). */
    public static Db forConfig(AppConfig cfg) {
        return new Db(cfg.dbUrl(), cfg.dbUser(), cfg.dbPassword());
    }

    /** The underlying pool — used by {@link Migrations} (Flyway) and nothing else. */
    public DataSource dataSource() { return ds; }

    @Override public void close() { ds.close(); }

    public interface SqlFn<T> { T apply(Connection c) throws SQLException; }
    public interface SqlConsumer { void accept(Connection c) throws SQLException; }

    public Connection open() throws SQLException {
        return ds.getConnection();
    }

    public <T> T with(SqlFn<T> fn) {
        try (Connection c = open()) {
            return fn.apply(c);
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    /** Runs fn inside a transaction; rolls back on any exception. Hikari resets the connection
     *  (autocommit, etc.) when it returns to the pool. */
    public <T> T tx(SqlFn<T> fn) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                T out = fn.apply(c);
                c.commit();
                return out;
            } catch (Exception e) {
                try { c.rollback(); } catch (SQLException re) { log.warn("rollback failed", re); }
                if (e instanceof SQLException se) throw new DbException(se);
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    public void exec(String sql, Object... params) {
        with(c -> {
            try (PreparedStatement ps = prep(c, sql, params)) {
                ps.executeUpdate();
                return null;
            }
        });
    }

    public <T> List<T> query(String sql, Function<Row, T> mapper, Object... params) {
        return with(c -> queryOn(c, sql, mapper, params));
    }

    public static <T> List<T> queryOn(Connection c, String sql, Function<Row, T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = prep(c, sql, params); ResultSet rs = ps.executeQuery()) {
            List<T> out = new ArrayList<>();
            Row row = new Row(rs);
            while (rs.next()) out.add(mapper.apply(row));
            return out;
        }
    }

    public static int execOn(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prep(c, sql, params)) {
            return ps.executeUpdate();
        }
    }

    public static long insertReturningId(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prep(c, sql, params)) {
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        return -1;
    }

    private static PreparedStatement prep(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            // Boolean flags live in integer 0/1 columns (kept from the SQLite schema for
            // zero call-site churn); bind them as ints.
            if (p instanceof Boolean b) ps.setInt(i + 1, b ? 1 : 0);
            else ps.setObject(i + 1, p);
        }
        return ps;
    }

    /** Wraps a ResultSet with null-safe accessors. */
    public record Row(ResultSet rs) {
        public String str(String col) {
            try { return rs.getString(col); } catch (SQLException e) { throw new DbException(e); }
        }
        public long lng(String col) {
            try { return rs.getLong(col); } catch (SQLException e) { throw new DbException(e); }
        }
        public Long lngOrNull(String col) {
            try { long v = rs.getLong(col); return rs.wasNull() ? null : v; } catch (SQLException e) { throw new DbException(e); }
        }
        public int intv(String col) {
            try { return rs.getInt(col); } catch (SQLException e) { throw new DbException(e); }
        }
        public double dbl(String col) {
            try { return rs.getDouble(col); } catch (SQLException e) { throw new DbException(e); }
        }
        public Double dblOrNull(String col) {
            try { double v = rs.getDouble(col); return rs.wasNull() ? null : v; } catch (SQLException e) { throw new DbException(e); }
        }
        public boolean bool(String col) {
            try { return rs.getInt(col) != 0; } catch (SQLException e) { throw new DbException(e); }
        }
    }

    public static final class DbException extends RuntimeException {
        public DbException(SQLException e) { super(e.getMessage(), e); }
    }
}
