package io.liftandshift.strikebench;

import io.liftandshift.strikebench.api.ApiServer;
import io.liftandshift.strikebench.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/** Entry point: java -jar strikebench.jar */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        preloadAllClasses();
        AppConfig cfg = new AppConfig();
        ApiServer server = ApiServer.create(cfg, Clock.systemDefaultZone());
        server.start(cfg.port());
        log.info("StrikeBench is up: http://localhost:{}  (db={}, fixturesOnly={})",
                cfg.port(), cfg.dbUrl(), cfg.fixturesOnly());
        log.info("Educational tool only — paper trading by default, never financial advice.");
    }

    /**
     * One-time rebrand migration: when running on the DEFAULT database path and no
     * strikebench.db exists yet, an existing legacy data/options-lab.db (plus SQLite
     * -wal/-shm siblings) is MOVED into place so the account, trades, and holdings
     * survive the rename. Explicit DB_PATH settings are never touched.
     */
    static void migrateLegacyDefaultDb(AppConfig cfg) {
        String defaultPath = "data/strikebench.db";
        if (!defaultPath.equals(cfg.dbPath())) return; // explicit DB_PATH settings are never touched
        moveLegacyDb(java.nio.file.Path.of("data/options-lab.db"), java.nio.file.Path.of(defaultPath));
    }

    /** Moves legacy -> target (with SQLite -wal/-shm siblings) iff target doesn't exist yet. */
    static void moveLegacyDb(java.nio.file.Path legacy, java.nio.file.Path target) {
        try {
            if (java.nio.file.Files.exists(target) || !java.nio.file.Files.exists(legacy)) return;
            if (target.getParent() != null) java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.move(legacy, target);
            for (String suffix : new String[]{"-wal", "-shm"}) {
                java.nio.file.Path extra = java.nio.file.Path.of(legacy + suffix);
                if (java.nio.file.Files.exists(extra)) {
                    java.nio.file.Files.move(extra, java.nio.file.Path.of(target + suffix));
                }
            }
            log.info("Migrated legacy database {} -> {} (rebrand rename; data unchanged)", legacy, target);
        } catch (Exception e) {
            // Never brick the boot over a rename: fall back to the legacy file untouched.
            log.warn("Could not migrate legacy database {} -> {}: {} — continuing without the move",
                    legacy, target, e.toString());
        }
    }

    /**
     * Loads (without initializing) every class in the application jar at boot. A JVM reads
     * class bytecode lazily from the jar file; if the jar is rebuilt while the server runs,
     * lazy loads start failing in ways that can wedge worker threads. With everything
     * resident up front, a rebuilt jar costs at most static-resource 500s — never a wedge.
     * No-op when running from a classes directory (tests, IDE).
     */
    private static void preloadAllClasses() {
        long start = System.currentTimeMillis();
        try {
            var source = Main.class.getProtectionDomain().getCodeSource();
            if (source == null) return;
            java.nio.file.Path jar = java.nio.file.Path.of(source.getLocation().toURI());
            if (!java.nio.file.Files.isRegularFile(jar)) return;
            int loaded = 0, unavailable = 0;
            try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar.toFile())) {
                var entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.contains("module-info")) continue;
                    try {
                        Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, Main.class.getClassLoader());
                        loaded++;
                    } catch (Throwable t) {
                        unavailable++; // optional dependencies of shaded libraries — fine
                    }
                }
            }
            log.info("Preloaded {} classes in {} ms ({} optional-dependency classes unavailable) — a jar rebuild under this running server can no longer wedge it",
                    loaded, System.currentTimeMillis() - start, unavailable);
        } catch (Exception e) {
            log.warn("class preload skipped (non-fatal): {}", e.toString());
        }
    }
}
