package io.liftandshift.strikebench.db;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Durable cursors, quarantine diagnostics, and opt-in end-of-day schedules. */
public final class DataSyncState {

    public record Cursor(String ownerKey, String source, String symbol, String status,
                         LocalDate requestedFrom, LocalDate requestedTo, LocalDate lastSuccessDate,
                         String lastAttemptAt, String nextAllowedAt, int failures,
                         long rowsWritten, String note, String updatedAt) {}

    public record Schedule(String ownerKey, boolean enabled, String source, List<String> symbols,
                           int years, String adjustment, LocalDate lastRunDate,
                           String lastStatus, String lastJobId, String updatedAt) {}

    public record QuarantineSummary(long total, List<QuarantineReason> reasons) {}
    public record QuarantineReason(String reason, long rows) {}

    private final Db db;
    @SuppressWarnings("unused") private final Clock clock;

    public DataSyncState(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public static String ownerKey(String ownerId) {
        return ownerId == null || ownerId.isBlank() ? "local" : "user:" + ownerId;
    }

    public static String ownerId(String ownerKey) {
        return ownerKey == null || "local".equals(ownerKey) ? null
                : ownerKey.startsWith("user:") ? ownerKey.substring(5) : ownerKey;
    }

    public void attempted(String ownerId, String source, String symbol, LocalDate from, LocalDate to) {
        db.exec("INSERT INTO data_sync_cursor(owner_key,source_key,symbol,status,requested_from,requested_to,last_attempt_at) "
                        + "VALUES (?,?,?,'RUNNING',?,?,now()) ON CONFLICT(owner_key,source_key,symbol,domain,interval_key) "
                        + "DO UPDATE SET status='RUNNING',requested_from=CASE "
                        + "WHEN data_sync_cursor.requested_from IS NULL THEN excluded.requested_from "
                        + "WHEN excluded.requested_from IS NULL THEN data_sync_cursor.requested_from "
                        + "ELSE least(data_sync_cursor.requested_from,excluded.requested_from) END,"
                        + "requested_to=CASE WHEN data_sync_cursor.requested_to IS NULL THEN excluded.requested_to "
                        + "WHEN excluded.requested_to IS NULL THEN data_sync_cursor.requested_to "
                        + "ELSE greatest(data_sync_cursor.requested_to,excluded.requested_to) END,"
                        + "last_attempt_at=now(),updated_at=now()",
                ownerKey(ownerId), source, symbol, from, to);
    }

    public void succeeded(String ownerId, String source, String symbol, LocalDate from, LocalDate to,
                          LocalDate lastSuccess, long rows, boolean complete, String note) {
        db.exec("INSERT INTO data_sync_cursor(owner_key,source_key,symbol,status,requested_from,requested_to,"
                        + "last_success_date,last_attempt_at,failure_count,rows_written,note) "
                        + "VALUES (?,?,?,?,?,?,?,now(),0,?,?) ON CONFLICT(owner_key,source_key,symbol,domain,interval_key) "
                        + "DO UPDATE SET status=excluded.status,requested_from=CASE "
                        + "WHEN data_sync_cursor.requested_from IS NULL THEN excluded.requested_from "
                        + "WHEN excluded.requested_from IS NULL THEN data_sync_cursor.requested_from "
                        + "ELSE least(data_sync_cursor.requested_from,excluded.requested_from) END,"
                        + "requested_to=CASE WHEN data_sync_cursor.requested_to IS NULL THEN excluded.requested_to "
                        + "WHEN excluded.requested_to IS NULL THEN data_sync_cursor.requested_to "
                        + "ELSE greatest(data_sync_cursor.requested_to,excluded.requested_to) END,last_success_date=CASE "
                        + "WHEN data_sync_cursor.last_success_date IS NULL THEN excluded.last_success_date "
                        + "WHEN excluded.last_success_date IS NULL THEN data_sync_cursor.last_success_date "
                        + "ELSE greatest(data_sync_cursor.last_success_date,excluded.last_success_date) END,"
                        + "last_attempt_at=now(),failure_count=0,rows_written=excluded.rows_written,"
                        + "note=excluded.note,updated_at=now()",
                ownerKey(ownerId), source, symbol, complete ? "COMPLETE" : "PARTIAL",
                from, to, lastSuccess, rows, cap(note, 500));
    }

    public void failed(String ownerId, String source, String symbol, LocalDate from, LocalDate to, String note) {
        db.exec("INSERT INTO data_sync_cursor(owner_key,source_key,symbol,status,requested_from,requested_to,"
                        + "last_attempt_at,failure_count,note) VALUES (?,?,?,'FAILED',?,?,now(),1,?) "
                        + "ON CONFLICT(owner_key,source_key,symbol,domain,interval_key) DO UPDATE SET "
                        + "status='FAILED',requested_from=CASE WHEN data_sync_cursor.requested_from IS NULL THEN excluded.requested_from "
                        + "WHEN excluded.requested_from IS NULL THEN data_sync_cursor.requested_from "
                        + "ELSE least(data_sync_cursor.requested_from,excluded.requested_from) END,"
                        + "requested_to=CASE WHEN data_sync_cursor.requested_to IS NULL THEN excluded.requested_to "
                        + "WHEN excluded.requested_to IS NULL THEN data_sync_cursor.requested_to "
                        + "ELSE greatest(data_sync_cursor.requested_to,excluded.requested_to) END,"
                        + "last_attempt_at=now(),failure_count=data_sync_cursor.failure_count+1,note=excluded.note,updated_at=now()",
                ownerKey(ownerId), source, symbol, from, to, cap(note, 500));
    }

    public List<Cursor> cursors(String ownerId) {
        return db.query("SELECT owner_key,source_key,symbol,status,requested_from::text rf,requested_to::text rt,"
                        + "last_success_date::text ls,last_attempt_at::text la,next_allowed_at::text na,"
                        + "failure_count,rows_written,note,updated_at::text ua FROM data_sync_cursor "
                        + "WHERE owner_key=? ORDER BY updated_at DESC",
                r -> new Cursor(r.str("owner_key"), r.str("source_key"), r.str("symbol"), r.str("status"),
                        date(r.str("rf")), date(r.str("rt")), date(r.str("ls")), r.str("la"), r.str("na"),
                        r.intv("failure_count"), r.lng("rows_written"), r.str("note"), r.str("ua")), ownerKey(ownerId));
    }

    public void quarantine(String ownerId, String jobId, String source, String symbol, String rowRef,
                           String reason, String payloadExcerpt) {
        db.exec("INSERT INTO data_quarantine(owner_key,job_id,source_key,symbol,row_ref,reason,payload_excerpt) VALUES (?,?,?,?,?,?,?)",
                ownerKey(ownerId), jobId, source, symbol, cap(rowRef, 80), cap(reason, 300), cap(payloadExcerpt, 500));
    }

    public QuarantineSummary quarantineSummary(String ownerId) {
        String owner = ownerKey(ownerId);
        long total = db.query("SELECT count(*) c FROM data_quarantine WHERE owner_key=?", r -> r.lng("c"), owner).getFirst();
        List<QuarantineReason> reasons = db.query(
                "SELECT reason,count(*) c FROM data_quarantine WHERE owner_key=? GROUP BY reason ORDER BY c DESC LIMIT 12",
                r -> new QuarantineReason(r.str("reason"), r.lng("c")), owner);
        return new QuarantineSummary(total, reasons);
    }

    public Schedule schedule(String ownerId) {
        return db.query("SELECT owner_key,enabled,source_key,symbols,years,adjustment,last_run_date::text lrd,"
                        + "last_status,last_job_id,updated_at::text ua FROM data_sync_schedule WHERE owner_key=?",
                r -> mapSchedule(r), ownerKey(ownerId)).stream().findFirst()
                .orElse(new Schedule(ownerKey(ownerId), false, "auto", List.of(), 5, "AUTO",
                        null, null, null, null));
    }

    public Schedule saveSchedule(String ownerId, boolean enabled, String source, List<String> symbols,
                                 int years, String adjustment) {
        String joined = String.join(",", symbols == null ? List.of() : symbols.stream()
                .map(s -> s.trim().toUpperCase()).filter(s -> !s.isBlank()).distinct().limit(120).toList());
        String src = source == null || source.isBlank() ? "auto" : source.trim().toLowerCase();
        String adj = adjustment == null ? "AUTO" : adjustment.trim().toUpperCase();
        if (!List.of("AUTO", "RAW", "ADJUSTED").contains(adj)) throw new IllegalArgumentException("bad adjustment basis");
        int y = Math.max(1, Math.min(20, years));
        db.exec("INSERT INTO data_sync_schedule(owner_key,enabled,source_key,symbols,years,adjustment) VALUES (?,?,?,?,?,?) "
                        + "ON CONFLICT(owner_key) DO UPDATE SET enabled=excluded.enabled,source_key=excluded.source_key,"
                        + "symbols=excluded.symbols,years=excluded.years,adjustment=excluded.adjustment,updated_at=now()",
                ownerKey(ownerId), enabled, src, joined, y, adj);
        return schedule(ownerId);
    }

    public List<Schedule> enabledSchedules() {
        return db.query("SELECT owner_key,enabled,source_key,symbols,years,adjustment,last_run_date::text lrd,"
                        + "last_status,last_job_id,updated_at::text ua FROM data_sync_schedule WHERE enabled=1",
                DataSyncState::mapSchedule);
    }

    public void markScheduleRun(String ownerKey, LocalDate day, String status, String jobId) {
        db.exec("UPDATE data_sync_schedule SET last_run_date=?,last_status=?,last_job_id=?,updated_at=now() WHERE owner_key=?",
                day, status, jobId, ownerKey);
    }

    private static Schedule mapSchedule(Db.Row r) {
        String raw = r.str("symbols");
        List<String> symbols = raw == null || raw.isBlank() ? List.of() : List.of(raw.split(","));
        return new Schedule(r.str("owner_key"), r.bool("enabled"), r.str("source_key"), symbols,
                r.intv("years"), r.str("adjustment"), date(r.str("lrd")), r.str("last_status"),
                r.str("last_job_id"), r.str("ua"));
    }

    private static LocalDate date(String raw) { return raw == null || raw.isBlank() ? null : LocalDate.parse(raw); }
    private static String cap(String s, int n) { return s == null ? null : s.substring(0, Math.min(n, s.length())); }
}
