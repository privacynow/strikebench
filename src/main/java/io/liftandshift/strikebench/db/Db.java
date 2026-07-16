package io.liftandshift.strikebench.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.liftandshift.strikebench.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Runnable afterClose;
    private final AtomicBoolean closed = new AtomicBoolean();

    public Db(String jdbcUrl, String user, String password) {
        this(jdbcUrl, user, password, () -> {});
    }

    /**
     * Builds a database handle with an idempotent resource cleanup callback. Production callers
     * use the ordinary constructor; isolated test databases use this overload to drop their
     * physical database as soon as the pool closes instead of retaining hundreds until JVM exit.
     */
    public Db(String jdbcUrl, String user, String password, Runnable afterClose) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("strikebench");
        cfg.setConnectionTimeout(10_000);
        this.ds = new HikariDataSource(cfg);
        this.afterClose = Objects.requireNonNull(afterClose);
        log.info("Local data store ready");
    }

    /** Builds a pool from app config (env > sysprops > properties > local-dev default). */
    public static Db forConfig(AppConfig cfg) {
        return new Db(cfg.dbUrl(), cfg.dbUser(), cfg.dbPassword());
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { ds.close(); }
        finally { afterClose.run(); }
    }

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
                try { c.rollback(); }
                catch (SQLException re) {
                    log.warn("A local-data operation could not be rolled back cleanly");
                    log.debug("Local-data rollback detail", re);
                }
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
            // Let PostgreSQL resolve application strings against the target column. This keeps
            // ISO-8601 values type-safe for TIMESTAMPTZ while preserving ordinary TEXT/JSON use.
            else if (p instanceof String s) ps.setObject(i + 1, s, java.sql.Types.OTHER);
            else ps.setObject(i + 1, p);
        }
        return ps;
    }

    /** Wraps a ResultSet with null-safe accessors. */
    public record Row(ResultSet rs) {
        public String str(String col) {
            try {
                Object value = rs.getObject(col);
                if (value instanceof java.time.OffsetDateTime time) return time.toInstant().toString();
                if (value instanceof java.sql.Timestamp time) return time.toInstant().toString();
                return rs.getString(col);
            } catch (SQLException e) { throw new DbException(e); }
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
        public java.math.BigDecimal bd(String col) {
            try { return rs.getBigDecimal(col); } catch (SQLException e) { throw new DbException(e); }
        }
        public java.time.OffsetDateTime odt(String col) {
            try { return rs.getObject(col, java.time.OffsetDateTime.class); }
            catch (SQLException e) { throw new DbException(e); }
        }
        public java.time.LocalDate date(String col) {
            try { return rs.getObject(col, java.time.LocalDate.class); }
            catch (SQLException e) { throw new DbException(e); }
        }
        public java.util.List<String> strings(String col) {
            try {
                java.sql.Array array = rs.getArray(col);
                if (array == null) return java.util.List.of();
                Object raw = array.getArray();
                if (!(raw instanceof Object[] values)) return java.util.List.of();
                java.util.List<String> out = new java.util.ArrayList<>(values.length);
                for (Object value : values) if (value != null) out.add(value.toString());
                return java.util.List.copyOf(out);
            } catch (SQLException e) { throw new DbException(e); }
        }
        public byte[] bytes(String col) {
            try { return rs.getBytes(col); } catch (SQLException e) { throw new DbException(e); }
        }
    }

    public static final class DbException extends RuntimeException {
        public DbException(SQLException e) { super(e.getMessage(), e); }
    }
}
