package io.liftandshift.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Thin JDBC helper around a SQLite database.
 * One connection per operation; WAL mode; foreign keys on; busy timeout for concurrent handlers.
 */
public final class Db {

    private static final Logger log = LoggerFactory.getLogger(Db.class);
    private final String url;

    public Db(String path) {
        if (!":memory:".equals(path)) {
            Path p = Path.of(path).toAbsolutePath();
            try {
                if (p.getParent() != null) Files.createDirectories(p.getParent());
            } catch (Exception e) {
                throw new IllegalStateException("Cannot create database directory for " + p, e);
            }
            this.url = "jdbc:sqlite:" + p;
        } else {
            this.url = "jdbc:sqlite::memory:";
        }
    }

    public interface SqlFn<T> { T apply(Connection c) throws SQLException; }
    public interface SqlConsumer { void accept(Connection c) throws SQLException; }

    public Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA journal_mode = WAL");
        }
        return c;
    }

    public <T> T with(SqlFn<T> fn) {
        try (Connection c = open()) {
            return fn.apply(c);
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    /** Runs fn inside a transaction; rolls back on any exception. */
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
