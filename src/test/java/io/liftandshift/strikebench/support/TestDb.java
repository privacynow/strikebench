package io.liftandshift.strikebench.support;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.Migrations;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-test isolated Postgres databases for the JUnit suite. Each fork builds one template from
 * then PostgreSQL clones that template for normal tests. Tests still own separate databases,
 * without rebuilding the schema hundreds of times. Schema tests explicitly ask for an empty
 * database. Callers close the returned Db in teardown; databases are dropped
 * at JVM exit.
 *
 * Requires a running Postgres — the docker-compose 'db' service by default:
 *   docker compose up -d db
 * Override the target with TEST_DB_URL / TEST_DB_USER / TEST_DB_PASSWORD (e.g. on CI).
 */
public final class TestDb {

    private static final String ADMIN_URL = env("TEST_DB_URL", "jdbc:postgresql://localhost:5432/strikebench_dev");
    private static final String USER = env("TEST_DB_USER", "strikebench");
    private static final String PASS = env("TEST_DB_PASSWORD", "strikebench");
    private static final String BASE = ADMIN_URL.substring(0, ADMIN_URL.lastIndexOf('/') + 1);
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Set<String> CREATED = Collections.synchronizedSet(new HashSet<>());
	private static volatile String templateName;

    static { Runtime.getRuntime().addShutdownHook(new Thread(TestDb::dropAll)); }

    private TestDb() {}

    /** A fresh, current-schema, isolated database. Caller closes the returned Db. */
    public static Db fresh() {
	    String name = newDatabase(true);
	    Db db = new Db(BASE + name, USER, PASS, () -> drop(name));
	    return db;
    }

    /** Overrides for a current-schema clone. App startup re-runs Flyway, which no-ops (already migrated). */
    public static Map<String, String> freshConfig() {
	    return config(newDatabase(true));
    }

	/** A genuinely empty database reserved for tests of Flyway migration and legacy-shape rejection. */
	public static Map<String, String> emptyConfig() {
	    return config(newDatabase(false));
	}

	private static Map<String, String> config(String name) {
	    return Map.of("DB_URL", BASE + name, "DB_USER", USER, "DB_PASSWORD", PASS);
	}

	private static String newDatabase(boolean currentSchema) {
	    String name = "sbtest_" + SEQ.incrementAndGet() + "_" + Long.toHexString(System.nanoTime());
	    if (currentSchema) {
	        admin("CREATE DATABASE " + name + " TEMPLATE " + template());
	    } else {
	        admin("CREATE DATABASE " + name);
	    }
	    CREATED.add(name);
	    return name;
    }

	private static String template() {
	    String ready = templateName;
	    if (ready != null) return ready;
	    synchronized (TestDb.class) {
	        if (templateName != null) return templateName;
	        String name = "sbtest_template_" + ProcessHandle.current().pid() + "_"
	                + Long.toHexString(System.nanoTime());
	        admin("CREATE DATABASE " + name);
	        try (Db db = new Db(BASE + name, USER, PASS)) {
	            Migrations.run(db);
	        } catch (RuntimeException e) {
	            try { admin("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)"); }
	            catch (RuntimeException ignored) { }
	            throw e;
	        }
	        templateName = name;
	        return name;
	    }
	}

    private static void dropAll() {
	    for (String name : new HashSet<>(CREATED)) {
	        try { drop(name); }
	        catch (RuntimeException ignored) { /* best-effort cleanup */ }
	    }
	    String template = templateName;
	    if (template != null) {
	        try { admin("DROP DATABASE IF EXISTS " + template + " WITH (FORCE)"); }
	        catch (RuntimeException ignored) { /* best-effort cleanup */ }
	    }
    }

    private static void drop(String name) {
        if (!CREATED.remove(name)) return;
        try { admin("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)"); }
        catch (RuntimeException e) {
            CREATED.add(name);
            throw e;
        }
    }

    static int retainedCount() { return CREATED.size(); }

    private static void admin(String sql) {
        try (Connection c = DriverManager.getConnection(ADMIN_URL, USER, PASS);
             Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("TestDb admin failed (" + sql + "). Is Postgres running? "
                    + "Try `docker compose up -d db`. Cause: " + e.getMessage(), e);
        }
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? def : v;
    }
}
