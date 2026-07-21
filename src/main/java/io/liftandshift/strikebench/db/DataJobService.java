package io.liftandshift.strikebench.db;

import com.fasterxml.jackson.databind.JsonNode;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataEngine;
import io.liftandshift.strikebench.market.SnapshotService;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.OwnerScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Data Center's background-job engine: cancellable, resumable, idempotent jobs with per-item
 * progress. Each kind's per-item work is idempotent (engine warm, snapshot upsert, underlying
 * history-sync upsert, CSV ingest upsert), so retrying a failed job re-does only what's missing.
 * Jobs run on a small daemon pool; orphaned RUNNING jobs (server restarted mid-run) are marked
 * FAILED on boot so they can be retried.
 */
public final class DataJobService {

    private static final Logger log = LoggerFactory.getLogger(DataJobService.class);

    public static final List<String> KINDS = List.of(
            "warm_universe", "refresh_now", "snapshot_now", "sync_underlying",
            "import_options_csv");

    private final Db db;
    private final Clock clock;
    private final MarketDataEngine engine;
    private final SnapshotService snapshots;
    private final UnderlyingBackfill backfill;
    private final UniverseService universe;
    private final AppConfig cfg;
    private final DataConnectorCatalog connectors;
    private final MarketDataMaintenanceGate maintenance;

    private final ExecutorService jobPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "data-job"); t.setDaemon(true); return t;
    });
    private final Map<String, Boolean> cancelRequested = new ConcurrentHashMap<>();
    private final java.util.Set<String> activeRunners = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private boolean acceptingJobs = true;
    private io.liftandshift.strikebench.util.EventBus events; // optional: live progress to the UI
    private Runnable dataChangedHook = () -> {}; // clears negative/history caches after durable writes
    private volatile long lastProgressPublishMs = 0;

    public void setEvents(io.liftandshift.strikebench.util.EventBus events) { this.events = events; }
    public void setDataChangedHook(Runnable hook) { this.dataChangedHook = hook == null ? () -> {} : hook; }

    /** job.progress is a hint (throttled — fast items must not flood the stream); terminal events always send. */
    private void publishJob(String type, String id, String kind, String status, int done, int total, String userId) {
        if (events == null) return;
        long now = System.currentTimeMillis();
        boolean terminal = !"RUNNING".equals(status);
        if (!terminal && now - lastProgressPublishMs < 250 && done < total) return;
        lastProgressPublishMs = now;
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", id); data.put("kind", kind); data.put("status", status);
        data.put("done", done); data.put("total", total);
        // Owner scope: when auth is on, /api/events delivers user-scoped events ONLY to their owner.
        if (userId != null) data.put("user", userId);
        events.publish(type, data);
    }

    public DataJobService(Db db, Clock clock, MarketDataEngine engine, SnapshotService snapshots,
                          UnderlyingBackfill backfill, UniverseService universe, AppConfig cfg) {
        this(db, clock, engine, snapshots, backfill, universe, cfg,
                new DataConnectorCatalog(cfg, new ProviderRequestBudget(db, clock)));
    }

    public DataJobService(Db db, Clock clock, MarketDataEngine engine, SnapshotService snapshots,
                          UnderlyingBackfill backfill, UniverseService universe, AppConfig cfg,
                          DataConnectorCatalog connectors) {
        this(db, clock, engine, snapshots, backfill, universe, cfg, connectors,
                new MarketDataMaintenanceGate());
    }

    public DataJobService(Db db, Clock clock, MarketDataEngine engine, SnapshotService snapshots,
                          UnderlyingBackfill backfill, UniverseService universe, AppConfig cfg,
                          DataConnectorCatalog connectors, MarketDataMaintenanceGate maintenance) {
        this.db = db;
        this.clock = clock;
        this.engine = engine;
        this.snapshots = snapshots;
        this.backfill = backfill;
        this.universe = universe;
        this.cfg = cfg;
        this.connectors = connectors;
        this.maintenance = java.util.Objects.requireNonNull(maintenance, "maintenance");
    }

    MarketDataMaintenanceGate maintenanceGate() { return maintenance; }

    public record DataJob(String id, String kind, String status, int total, int done, long rowsWritten,
                          String message, String error, String createdAt, String updatedAt) {}

    public record DataJobItem(int seq, String label, String status, long rowsWritten, String note) {}

    // ---- Lifecycle ----

    /** A server restart abandons in-flight job threads — mark them FAILED so they can be retried. */
    public void reconcileOnBoot() {
        try {
            db.exec("UPDATE data_job SET status='FAILED', error='interrupted by server restart', updated_at=now() "
                  + "WHERE status IN ('RUNNING','QUEUED')");
        } catch (Exception e) {
            log.warn("Background data jobs could not be reconciled after restart");
            log.debug("Background-job reconciliation detail", e);
        }
    }

    public void shutdown() {
        jobPool.shutdownNow();
        try {
            if (!jobPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("Background data jobs did not stop within five seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- Public API ----

    public DataJob start(String kind, Map<String, Object> params, String userId) {
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(k)) throw new IllegalArgumentException("unknown job kind: " + kind);
        Map<String, Object> p = params == null ? Map.of() : params;
        List<String> labels = itemsFor(k, p);
        String id = Ids.newId("job");
        String paramsJson = Json.write(p);
        String owner = OwnerScope.id(userId);
        synchronized (lifecycleLock) {
            if (!acceptingJobs) {
                throw new IllegalStateException(
                        "Data maintenance is paused for a safe reset; retry after the reset completes.");
            }
            maintenance.write(() -> db.tx(c -> {
                OwnerScope.ensure(c, owner);
                Db.execOn(c, "INSERT INTO data_job (id, kind, status, params, total, done, user_id) VALUES (?,?,?,?::jsonb,?,0,?)",
                        id, k, "QUEUED", paramsJson, labels.size(), owner);
                for (int i = 0; i < labels.size(); i++) {
                    Db.execOn(c, "INSERT INTO data_job_item (job_id, seq, label, status) VALUES (?,?,?,?)",
                            id, i, labels.get(i), "PENDING");
                }
                return null;
            }));
            activeRunners.add(id);
            try {
                jobPool.submit(() -> run(id, k, p, labels, owner));
            } catch (RuntimeException submitFailure) {
                activeRunners.remove(id);
                lifecycleLock.notifyAll();
                db.exec("UPDATE data_job SET status='FAILED', error='background worker unavailable', updated_at=now() WHERE id=?", id);
                throw submitFailure;
            }
        }
        return get(id).job();
    }

    /** Stop admission, cancel queued/running work, and wait until no provider writer remains. */
    public void quiesceForReset(Duration timeout) {
        Duration bounded = timeout == null || timeout.isNegative() ? Duration.ZERO : timeout;
        synchronized (lifecycleLock) {
            acceptingJobs = false;
        }
        List<String> activeIds = db.query(
                "SELECT id FROM data_job WHERE status IN ('QUEUED','RUNNING') ORDER BY created_at",
                r -> r.str("id"));
        activeIds.forEach(this::cancel);

        long deadline = System.nanoTime() + bounded.toNanos();
        synchronized (lifecycleLock) {
            while (!activeRunners.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    acceptingJobs = true;
                    throw new IllegalStateException(
                            "Active data maintenance did not stop safely; the reset was not started.");
                }
                try {
                    long millis = Math.max(1L, Math.min(1_000L,
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
                    lifecycleLock.wait(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    acceptingJobs = true;
                    throw new IllegalStateException(
                            "Data reset was interrupted before background writers stopped.", e);
                }
            }
        }
    }

    /** Re-open admission after a reset transaction completes or aborts. */
    public void resumeAfterReset() {
        synchronized (lifecycleLock) {
            acceptingJobs = true;
            lifecycleLock.notifyAll();
        }
    }

    public void cancel(String jobId) {
        // Only flag jobs that are actually active — a finished/unknown id must not leak into the map.
        int updated = db.with(c -> Db.execOn(c,
                "UPDATE data_job SET status='CANCELLED', message='cancelled by user', updated_at=now() "
              + "WHERE id=? AND status IN ('QUEUED','RUNNING')", jobId));
        if (updated > 0) cancelRequested.put(jobId, Boolean.TRUE);
    }

    /** Re-run a finished/failed job with the same kind + params (idempotent → effectively a resume). */
    public DataJob retry(String jobId, String userId) {
        JobView v = get(jobId);
        if (v.job() == null) throw new io.liftandshift.strikebench.util.ResourceNotFoundException("no such job: " + jobId);
        JsonNode params = v.paramsJson() == null ? Json.obj() : Json.parse(v.paramsJson());
        Map<String, Object> p = Json.read(params.toString(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        return start(v.job().kind(), p, userId);
    }

    public record JobView(DataJob job, List<DataJobItem> items, String paramsJson) {}

    public JobView get(String jobId) {
        List<Object[]> head = db.query(
                "SELECT id, kind, status, total, done, rows_written, message, error, "
              + "created_at::text ca, updated_at::text ua, params::text pj FROM data_job WHERE id=?",
                r -> new Object[]{
                        new DataJob(r.str("id"), r.str("kind"), r.str("status"), (int) r.lng("total"),
                                (int) r.lng("done"), r.lng("rows_written"), r.str("message"), r.str("error"),
                                r.str("ca"), r.str("ua")),
                        r.str("pj")}, jobId);
        if (head.isEmpty()) return new JobView(null, List.of(), null);
        List<DataJobItem> items = db.query(
                "SELECT seq, label, status, rows_written, note FROM data_job_item WHERE job_id=? ORDER BY seq",
                r -> new DataJobItem((int) r.lng("seq"), r.str("label"), r.str("status"), r.lng("rows_written"), r.str("note")),
                jobId);
        return new JobView((DataJob) head.getFirst()[0], items, (String) head.getFirst()[1]);
    }

    /** The owner user_id of a job, or null if the job doesn't exist. */
    public String ownerOf(String jobId) {
        return db.query("SELECT user_id FROM data_job WHERE id=?", r -> r.str("user_id"), jobId)
                .stream().findFirst().orElse(null);
    }

    /** The job kind, or null if it doesn't exist (used to re-gate a privileged retry). */
    public String kindOf(String jobId) {
        return db.query("SELECT kind FROM data_job WHERE id=?", r -> r.str("kind"), jobId)
                .stream().findFirst().orElse(null);
    }

    /** Recent jobs scoped to the caller unless {@code all} (admin). Null-safe on user_id. */
    public List<DataJob> recent(String userId, boolean all, int limit) {
        int lim = Math.max(1, Math.min(limit, 100));
        if (all) return recent(lim);
        return db.query(
                "SELECT id, kind, status, total, done, rows_written, message, error, "
              + "created_at::text ca, updated_at::text ua FROM data_job "
              + "WHERE user_id=? ORDER BY created_at DESC LIMIT ?",
                r -> new DataJob(r.str("id"), r.str("kind"), r.str("status"), (int) r.lng("total"),
                        (int) r.lng("done"), r.lng("rows_written"), r.str("message"), r.str("error"),
                        r.str("ca"), r.str("ua")),
                OwnerScope.id(userId), lim);
    }

    public List<DataJob> recent(int limit) {
        return db.query(
                "SELECT id, kind, status, total, done, rows_written, message, error, "
              + "created_at::text ca, updated_at::text ua FROM data_job ORDER BY created_at DESC LIMIT ?",
                r -> new DataJob(r.str("id"), r.str("kind"), r.str("status"), (int) r.lng("total"),
                        (int) r.lng("done"), r.lng("rows_written"), r.str("message"), r.str("error"),
                        r.str("ca"), r.str("ua")),
                Math.max(1, Math.min(limit, 100)));
    }

    public boolean hasActive(String kind) {
        return db.query("SELECT count(*) c FROM data_job WHERE kind=? AND status IN ('QUEUED','RUNNING')",
                r -> r.lng("c"), kind).getFirst() > 0;
    }

    /**
     * Durable retry gate based on the terminal job timestamp. The scheduler uses this instead of
     * an in-memory timer, so a restart cannot erase a provider-friendly cooldown.
     */
    boolean retryReady(String jobId, Duration cooldown) {
        if (jobId == null || cooldown == null) return true;
        OffsetDateTime threshold = OffsetDateTime.ofInstant(
                clock.instant().minus(cooldown.isNegative() ? Duration.ZERO : cooldown), ZoneOffset.UTC);
        return db.query("SELECT count(*) c FROM data_job WHERE id=? AND updated_at<=?",
                r -> r.lng("c"), jobId, threshold).getFirst() > 0;
    }

    // ---- Runner ----

    private void run(String id, String kind, Map<String, Object> params, List<String> labels, String userId) {
        db.exec("UPDATE data_job SET status='RUNNING', updated_at=now() WHERE id=? AND status='QUEUED'", id);
        long totalRows = 0;
        int done = 0;   // items actually processed (excludes cancel-skipped)
        int failed = 0; // of those, how many threw
        boolean dataChangedNotified = false;
        try {
            for (int seq = 0; seq < labels.size(); seq++) {
                if (Boolean.TRUE.equals(cancelRequested.get(id))) {
                    setItem(id, seq, "SKIPPED", 0, "cancelled");
                    continue;
                }
                String label = labels.get(seq);
                try {
                    ItemResult res = maintenance.write(() -> work(id, kind, label, params, userId));
                    // Rows written do not imply requested coverage is complete. Preserve PARTIAL
                    // distinctly so the durable schedule can retry only the still-missing ranges.
                    setItem(id, seq, res.ok ? "DONE" : res.rows > 0 ? "PARTIAL" : "SKIPPED",
                            res.rows, res.note);
                    totalRows += res.rows;
                } catch (Exception e) {
                    failed++;
                    String failure = publicFailure(e);
                    setItem(id, seq, "FAILED", 0, failure);
                    log.debug("Data-job item failure for " + kind + " " + label, e);
                }
                done++;
                db.exec("UPDATE data_job SET done=?, rows_written=?, updated_at=now() WHERE id=?", done, totalRows, id);
                publishJob("job.progress", id, kind, "RUNNING", done, labels.size(), userId);
            }
            if (totalRows > 0) {
                notifyDataChanged();
                dataChangedNotified = true;
            }
            if (Boolean.TRUE.equals(cancelRequested.get(id))) {
                // Atomic: keep the cancel status AND the 'cancelled by user' message (no summary overwrite).
                db.exec("UPDATE data_job SET status='CANCELLED', message='cancelled by user', updated_at=now() WHERE id=?", id);
                publishJob("job.complete", id, kind, "CANCELLED", done, labels.size(), userId);
            } else {
                // FAILED only when EVERY processed item failed; a partial failure stays DONE (message notes it).
                String status = (done > 0 && failed == done) ? "FAILED" : "DONE";
                db.exec("UPDATE data_job SET status=?, message=?, updated_at=now() WHERE id=? AND status <> 'CANCELLED'",
                        status, summary(kind, done, totalRows, failed), id);
                publishJob("job.complete", id, kind, status, done, labels.size(), userId);
            }
        } catch (Exception e) {
            if (totalRows > 0 && !dataChangedNotified) {
                notifyDataChanged();
                dataChangedNotified = true;
            }
            db.exec("UPDATE data_job SET status='FAILED', error=?, updated_at=now() WHERE id=? AND status <> 'CANCELLED'",
                    publicFailure(e), id);
            log.debug("Data-job failure for " + kind + " " + id, e);
            publishJob("job.complete", id, kind, "FAILED", done, labels.size(), userId);
        } finally {
            if (totalRows > 0 && !dataChangedNotified) notifyDataChanged();
            cancelRequested.remove(id);
            synchronized (lifecycleLock) {
                activeRunners.remove(id);
                lifecycleLock.notifyAll();
            }
        }
    }

    private void notifyDataChanged() {
        try { dataChangedHook.run(); }
        catch (RuntimeException e) {
            log.warn("New data was saved, but cached views could not be refreshed immediately");
            log.debug("Data-change cache refresh detail", e);
        }
    }

    private static String publicFailure(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (e instanceof java.nio.file.NoSuchFileException || e instanceof java.io.FileNotFoundException
                || message.contains("no such file")) {
            return "The selected file could not be found.";
        }
        if (e instanceof io.liftandshift.strikebench.market.providers.Http.ProviderHttpException http) {
            return http.statusCode() > 0
                    ? "The source returned HTTP " + http.statusCode() + ". Try again later or check source access."
                    : "The source could not be reached. Check the connection and try again.";
        }
        if (e instanceof ProviderRequestBudget.Exhausted) return e.getMessage();
        if (message.contains("timeout") || message.contains("timed out")) {
            return "The source timed out. Try again later.";
        }
        return "The data operation failed. Check the source setup and try again.";
    }

    private record ItemResult(long rows, boolean ok, String note) {}

    private ItemResult work(String jobId, String kind, String label, Map<String, Object> params, String userId) {
        switch (kind) {
            case "warm_universe", "refresh_now" -> {
                // BLOCKING: the stale-while-refresh accessor reported success before any refresh ran.
                boolean ok = engine.refreshBlocking(label, cfg.httpTimeoutMs() + 5000L);
                var snap = engine.quote(label);
                String src = snap.map(x -> x.source()).orElse("none");
                return new ItemResult(ok ? 1 : 0, ok, ok ? "refreshed from " + src : "refresh failed or no quote");
            }
            case "snapshot_now" -> {
                SnapshotService.SnapshotResult r = snapshots.snapshotActiveUniverse();
                long rows = (long) r.underlyingRows() + r.optionRows();
                return new ItemResult(rows, true, r.symbols() + " symbols, " + r.underlyingRows()
                        + " underlying + " + r.optionRows() + " option bars"
                        + (r.errors().isEmpty() ? "" : " (" + r.errors().size() + " errors)"));
            }
            case "sync_underlying" -> {
                LocalDate to = dateParam(params, "to", LocalDate.now(clock));
                LocalDate completed = DataSyncScheduler.latestCompletedSession(clock);
                if (to.isAfter(completed)) to = completed;
                LocalDate from = dateParam(params, "from", to.minusYears(yearsParam(params)));
                String source = strParam(params, "source", "auto").trim().toLowerCase(Locale.ROOT);
                source = connectors.requireAutomated(source).key();
                // Compact Alpha access intentionally fetches only the recent window. Do not
                // pretend a five-year request can be fulfilled without the entitled full endpoint.
                if ("alphavantage".equals(source) && !cfg.alphaVantageFullHistoryEnabled()) {
                    from = from.isBefore(to.minusDays(160)) ? to.minusDays(160) : from;
                }
                var r = backfill.backfill(label, from, to, source, userId, jobId);
                return new ItemResult(r.rows(), r.complete(), r.note());
            }
            case "import_options_csv" -> {
                try {
                    String source = strParam(params, "source", "csv");
                    var r = HistoricalOptionsIngest.runFromFile(label, source, db);
                    long rows = (long) r.optionRows() + r.underlyingRows();
                    return new ItemResult(rows, true, r.optionRows() + " option + " + r.underlyingRows()
                            + " underlying rows, " + r.skipped() + " skipped");
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }
            default -> throw new IllegalArgumentException("unknown kind: " + kind);
        }
    }

    // ---- Item lists ----

    private List<String> itemsFor(String kind, Map<String, Object> params) {
        return switch (kind) {
            case "warm_universe", "refresh_now", "sync_underlying" -> symbolsParam(params);
            case "snapshot_now" -> List.of("active universe");
            case "import_options_csv" -> List.of(strParam(params, "path", ""));
            default -> List.of();
        };
    }

    private List<String> symbolsParam(Map<String, Object> params) {
        Object raw = params.get("symbols");
        List<String> syms = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) if (o != null) syms.add(o.toString().trim().toUpperCase(Locale.ROOT));
        } else if (raw instanceof String s && !s.isBlank()) {
            for (String p : s.split(",")) if (!p.isBlank()) syms.add(p.trim().toUpperCase(Locale.ROOT));
        }
        if (syms.isEmpty()) syms.addAll(universe.active().symbols());
        return syms.stream().distinct().limit(200).toList();
    }

    // ---- helpers ----

    private void setItem(String id, int seq, String status, long rows, String note) {
        db.exec("UPDATE data_job_item SET status=?, rows_written=?, note=? WHERE job_id=? AND seq=?",
                status, rows, note, id, seq);
    }

    private static String summary(String kind, int done, long rows, int failed) {
        return kind + ": " + done + " items, " + rows + " rows written" + (failed > 0 ? " (" + failed + " failed)" : "");
    }

    private static String strParam(Map<String, Object> p, String key, String def) {
        Object v = p.get(key);
        return v == null ? def : v.toString();
    }

    private static int yearsParam(Map<String, Object> p) {
        Object v = p.get("years");
        try { return v == null ? 5 : Math.max(1, Math.min(20, (int) Double.parseDouble(v.toString()))); }
        catch (Exception e) { return 5; }
    }

    private static LocalDate dateParam(Map<String, Object> p, String key, LocalDate def) {
        Object v = p.get(key);
        if (v == null || v.toString().isBlank()) return def;
        try { return LocalDate.parse(v.toString().trim()); } catch (Exception e) { return def; }
    }
}
