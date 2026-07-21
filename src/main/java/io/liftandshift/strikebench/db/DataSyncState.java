package io.liftandshift.strikebench.db;

import java.time.Clock;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import io.liftandshift.strikebench.util.OwnerScope;

/** Durable cursors, quarantine diagnostics, and opt-in end-of-day schedules. */
public final class DataSyncState {

    /**
     * Durable schedules need room for the complete curated market universe (more than 100 names)
     * plus the independently bounded custom universe (up to 30 names). Keep additional headroom
     * explicit without allowing an unbounded persisted job contract.
     */
    static final int MAX_SCHEDULE_SYMBOLS = 200;

    public record Cursor(String userId, String source, String symbol, String status,
                         LocalDate requestedFrom, LocalDate requestedTo, LocalDate lastSuccessDate,
                         String lastAttemptAt, String nextAllowedAt, int failures,
                         long rowsWritten, String note, String updatedAt) {}

    public record Schedule(String userId, boolean enabled, String source, List<String> symbols,
                           int years, LocalDate lastRunDate,
                           String lastStatus, String lastJobId, String coverageHash,
                           String completedCoverageHash, String updatedAt) {
        /** A date is complete only for the exact persisted coverage contract that produced it. */
        public boolean covers(LocalDate completedSession) {
            return completedSession != null && lastRunDate != null
                    && !lastRunDate.isBefore(completedSession)
                    && coverageHash != null && coverageHash.equals(completedCoverageHash);
        }
    }

    public record QuarantineSummary(long total, List<QuarantineReason> reasons) {}
    public record QuarantineReason(String reason, long rows) {}

    private final Db db;
    @SuppressWarnings("unused") private final Clock clock;

    public DataSyncState(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public void attempted(String ownerId, String source, String symbol, LocalDate from, LocalDate to) {
        String owner = ensureOwner(ownerId);
        db.exec("INSERT INTO data_sync_cursor(user_id,source_key,symbol,status,requested_from,requested_to,last_attempt_at) "
                        + "VALUES (?,?,?,'RUNNING',?,?,now()) ON CONFLICT(user_id,source_key,symbol,domain,interval_key) "
                        + "DO UPDATE SET status='RUNNING',requested_from=CASE "
                        + "WHEN data_sync_cursor.requested_from IS NULL THEN excluded.requested_from "
                        + "WHEN excluded.requested_from IS NULL THEN data_sync_cursor.requested_from "
                        + "ELSE least(data_sync_cursor.requested_from,excluded.requested_from) END,"
                        + "requested_to=CASE WHEN data_sync_cursor.requested_to IS NULL THEN excluded.requested_to "
                        + "WHEN excluded.requested_to IS NULL THEN data_sync_cursor.requested_to "
                        + "ELSE greatest(data_sync_cursor.requested_to,excluded.requested_to) END,"
                        + "last_attempt_at=now(),updated_at=now()",
                owner, source, symbol, from, to);
    }

    public void succeeded(String ownerId, String source, String symbol, LocalDate from, LocalDate to,
                          LocalDate lastSuccess, long rows, boolean complete, String note) {
        String owner = ensureOwner(ownerId);
        db.exec("INSERT INTO data_sync_cursor(user_id,source_key,symbol,status,requested_from,requested_to,"
                        + "last_success_date,last_attempt_at,failure_count,rows_written,note) "
                        + "VALUES (?,?,?,?,?,?,?,now(),0,?,?) ON CONFLICT(user_id,source_key,symbol,domain,interval_key) "
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
                owner, source, symbol, complete ? "COMPLETE" : "PARTIAL",
                from, to, lastSuccess, rows, cap(note, 500));
    }

    public void failed(String ownerId, String source, String symbol, LocalDate from, LocalDate to, String note) {
        String owner = ensureOwner(ownerId);
        db.exec("INSERT INTO data_sync_cursor(user_id,source_key,symbol,status,requested_from,requested_to,"
                        + "last_attempt_at,failure_count,note) VALUES (?,?,?,'FAILED',?,?,now(),1,?) "
                        + "ON CONFLICT(user_id,source_key,symbol,domain,interval_key) DO UPDATE SET "
                        + "status='FAILED',requested_from=CASE WHEN data_sync_cursor.requested_from IS NULL THEN excluded.requested_from "
                        + "WHEN excluded.requested_from IS NULL THEN data_sync_cursor.requested_from "
                        + "ELSE least(data_sync_cursor.requested_from,excluded.requested_from) END,"
                        + "requested_to=CASE WHEN data_sync_cursor.requested_to IS NULL THEN excluded.requested_to "
                        + "WHEN excluded.requested_to IS NULL THEN data_sync_cursor.requested_to "
                        + "ELSE greatest(data_sync_cursor.requested_to,excluded.requested_to) END,"
                        + "last_attempt_at=now(),failure_count=data_sync_cursor.failure_count+1,note=excluded.note,updated_at=now()",
                owner, source, symbol, from, to, cap(note, 500));
    }

    public List<Cursor> cursors(String ownerId) {
        return db.query("SELECT user_id,source_key,symbol,status,requested_from::text rf,requested_to::text rt,"
                        + "last_success_date::text ls,last_attempt_at::text la,next_allowed_at::text na,"
                        + "failure_count,rows_written,note,updated_at::text ua FROM data_sync_cursor "
                        + "WHERE user_id=? ORDER BY updated_at DESC",
                r -> new Cursor(r.str("user_id"), r.str("source_key"), r.str("symbol"), r.str("status"),
                        date(r.str("rf")), date(r.str("rt")), date(r.str("ls")), r.str("la"), r.str("na"),
                        r.intv("failure_count"), r.lng("rows_written"), r.str("note"), r.str("ua")), OwnerScope.id(ownerId));
    }

    public void quarantine(String ownerId, String jobId, String source, String symbol, String rowRef,
                           String reason, String payloadExcerpt) {
        String owner = ensureOwner(ownerId);
        db.exec("INSERT INTO data_quarantine(user_id,job_id,source_key,symbol,row_ref,reason,payload_excerpt) VALUES (?,?,?,?,?,?,?)",
                owner, jobId, source, symbol, cap(rowRef, 80), cap(reason, 300), cap(payloadExcerpt, 500));
    }

    public QuarantineSummary quarantineSummary(String ownerId) {
        String owner = OwnerScope.id(ownerId);
        long total = db.query("SELECT count(*) c FROM data_quarantine WHERE user_id=?", r -> r.lng("c"), owner).getFirst();
        List<QuarantineReason> reasons = db.query(
                "SELECT reason,count(*) c FROM data_quarantine WHERE user_id=? GROUP BY reason ORDER BY c DESC LIMIT 12",
                r -> new QuarantineReason(r.str("reason"), r.lng("c")), owner);
        return new QuarantineSummary(total, reasons);
    }

    public Schedule schedule(String ownerId) {
        return db.query("SELECT user_id,enabled,source_key,symbols,years,last_run_date::text lrd,"
                        + "last_status,last_job_id,coverage_hash,completed_coverage_hash,"
                        + "updated_at::text ua FROM data_sync_schedule WHERE user_id=?",
                r -> mapSchedule(r), OwnerScope.id(ownerId)).stream().findFirst()
                .orElse(new Schedule(OwnerScope.id(ownerId), false, "auto", List.of(), 5,
                        null, null, null, coverageHash("auto", List.of(), 5), null, null));
    }

    public Schedule saveSchedule(String ownerId, boolean enabled, String source, List<String> symbols,
                                 int years) {
        String owner = ensureOwner(ownerId);
        List<String> normalized = symbols == null ? List.of() : symbols.stream()
                .filter(java.util.Objects::nonNull).map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isBlank()).distinct().limit(MAX_SCHEDULE_SYMBOLS).toList();
        String joined = String.join(",", normalized);
        String src = source == null || source.isBlank() ? "auto" : source.trim().toLowerCase();
        int y = Math.max(1, Math.min(20, years));
        String hash = coverageHash(src, normalized, y);
        db.exec("INSERT INTO data_sync_schedule(user_id,enabled,source_key,symbols,years,coverage_hash) "
                        + "VALUES (?,?,?,?,?,?) "
                        + "ON CONFLICT(user_id) DO UPDATE SET enabled=excluded.enabled,source_key=excluded.source_key,"
                        + "symbols=excluded.symbols,years=excluded.years,coverage_hash=excluded.coverage_hash,"
                        + "last_status=CASE WHEN data_sync_schedule.coverage_hash IS DISTINCT FROM excluded.coverage_hash "
                        + "THEN 'CONFIG_CHANGED' ELSE data_sync_schedule.last_status END,updated_at=now()",
                owner, enabled, src, joined, y, hash);
        return schedule(ownerId);
    }

    public List<Schedule> enabledSchedules() {
        return db.query("SELECT user_id,enabled,source_key,symbols,years,last_run_date::text lrd,"
                        + "last_status,last_job_id,coverage_hash,completed_coverage_hash,"
                        + "updated_at::text ua FROM data_sync_schedule WHERE enabled=1",
                DataSyncState::mapSchedule);
    }

    /** Records an attempt without advancing the completed-session cursor. */
    public void markScheduleAttempt(String userId, String status, String jobId) {
        db.exec("UPDATE data_sync_schedule SET last_status=?,last_job_id=?,updated_at=now() WHERE user_id=?",
                status, jobId, OwnerScope.id(userId));
    }

    /** Advances completion only after the job proved full coverage for the current contract hash. */
    public void markScheduleComplete(String userId, LocalDate day, String status, String jobId,
                                     String completedCoverageHash) {
        db.exec("UPDATE data_sync_schedule SET last_run_date=CASE WHEN last_run_date IS NULL THEN ? "
                        + "ELSE greatest(last_run_date,?) END,last_status=?,last_job_id=?,"
                        + "completed_coverage_hash=?,updated_at=now() WHERE user_id=?",
                day, day, status, jobId, completedCoverageHash, OwnerScope.id(userId));
    }

    private static Schedule mapSchedule(Db.Row r) {
        String raw = r.str("symbols");
        List<String> symbols = raw == null || raw.isBlank() ? List.of() : List.of(raw.split(","));
        return new Schedule(r.str("user_id"), r.bool("enabled"), r.str("source_key"), symbols,
                r.intv("years"), date(r.str("lrd")), r.str("last_status"),
                r.str("last_job_id"), r.str("coverage_hash"), r.str("completed_coverage_hash"),
                r.str("ua"));
    }

    /**
     * Stable identity for the exact daily observed-history coverage contract. Symbol order is not
     * semantic; source, lookback, or membership changes are. Bump the version literal if the
     * domain/interval/provenance contract itself changes.
     */
    static String coverageHash(String source, List<String> symbols, int years) {
        String src = source == null || source.isBlank()
                ? "auto" : source.trim().toLowerCase(Locale.ROOT);
        List<String> normalized = symbols == null ? List.of() : symbols.stream()
                .filter(java.util.Objects::nonNull).map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isBlank()).distinct().sorted().toList();
        String contract = "daily-observed-underlying-v1\nsource=" + src
                + "\nyears=" + Math.max(1, Math.min(20, years))
                + "\nsymbols=" + String.join(",", normalized);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(contract.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String ensureOwner(String ownerId) {
        return db.with(c -> OwnerScope.ensure(c, ownerId));
    }

    private static LocalDate date(String raw) { return raw == null || raw.isBlank() ? null : LocalDate.parse(raw); }
    private static String cap(String s, int n) { return s == null ? null : s.substring(0, Math.min(n, s.length())); }
}
