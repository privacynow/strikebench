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
        if (args.length > 0 && "ingest-options".equals(args[0])) { runIngestOptions(args); return; }
        preloadAllClasses();
        AppConfig cfg = new AppConfig();
        ApiServer server = ApiServer.create(cfg, Clock.systemDefaultZone());
        server.start(cfg.port());
        log.info("StrikeBench is ready at http://localhost:{} (market mode: {})",
                cfg.port(), cfg.fixturesOnly() ? "demo" : "observed");
        log.info("Educational tool only — paper trading by default, never financial advice.");
    }

    /**
     * Bulk-ingests a LICENSED historical-options CSV into option_bar (the "own the past" moat):
     * {@code java -jar strikebench.jar ingest-options <csv> [source]} (source defaults to "vendor").
     * Only derived rows are stored; never redistribute the vendor file.
     */
    static void runIngestOptions(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: java -jar strikebench.jar ingest-options <csv> [source]");
            System.exit(2);
            return;
        }
        String source = args.length >= 3 ? args[2] : "vendor";
        AppConfig cfg = new AppConfig();
        try (io.liftandshift.strikebench.db.Db db = io.liftandshift.strikebench.db.Db.forConfig(cfg)) {
            io.liftandshift.strikebench.db.Migrations.run(db);
            var result = io.liftandshift.strikebench.db.HistoricalOptionsIngest.runFromFile(args[1], source, db);
            result.problems().forEach(p -> log.warn("  {}", p));
            log.info("ingested {} option bars + {} underlying bars ({} skipped) from {} as source '{}'",
                    result.optionRows(), result.underlyingRows(), result.skipped(), args[1], source);
        } catch (Exception e) {
            log.error("Data import failed. Check the input file and import settings.");
            log.debug("Data-import failure detail", e);
            System.exit(1);
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
            log.debug("Preloaded {} classes in {} ms ({} optional-dependency classes unavailable)",
                    loaded, System.currentTimeMillis() - start, unavailable);
        } catch (Exception e) {
            log.warn("Startup preparation was incomplete; restart if requests fail");
            log.debug("Startup preparation detail", e);
        }
    }
}
