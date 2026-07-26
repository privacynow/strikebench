package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.util.OwnerScope;

import java.time.Clock;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
                         long rowsWritten, String note, String updatedAt, LocalDate earliestAvailable) {}

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
        String sym = Symbol.normalize(symbol);
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
                owner, source, sym, from, to);
    }

    public void succeeded(String ownerId, String source, String symbol, LocalDate from, LocalDate to,
                          LocalDate lastSuccess, long rows, boolean complete, String note) {
        String owner = ensureOwner(ownerId);
        String sym = Symbol.normalize(symbol);
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
                owner, source, sym, complete ? "COMPLETE" : "PARTIAL",
                from, to, lastSuccess, rows, cap(note, 500));
    }

    public void failed(String ownerId, String source, String symbol, LocalDate from, LocalDate to, String note) {
        String owner = ensureOwner(ownerId);
        String sym = Symbol.normalize(symbol);
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
                owner, source, sym, from, to, cap(note, 500));
    }

    public List<Cursor> cursors(String ownerId) {
        return db.query("SELECT user_id,source_key,symbol,status,requested_from::text rf,requested_to::text rt,"
                        + "last_success_date::text ls,last_attempt_at::text la,next_allowed_at::text na,"
                        + "failure_count,rows_written,note,updated_at::text ua,earliest_available::text ea FROM data_sync_cursor "
                        + "WHERE user_id=? ORDER BY updated_at DESC",
                r -> new Cursor(r.str("user_id"), r.str("source_key"), r.str("symbol"), r.str("status"),
                        date(r.str("rf")), date(r.str("rt")), date(r.str("ls")), r.str("la"), r.str("na"),
                        r.intv("failure_count"), r.lng("rows_written"), r.str("note"), r.str("ua"),
                        date(r.str("ea"))), OwnerScope.id(ownerId));
    }

    /**
     * Records a durable pre-history boundary for a (source, symbol) so the missing-range planner
     * clamps future requests to >= this date. Monotonic: an existing later boundary is kept. The
     * boundary is a market fact, so it is stored under the SYSTEM scope and read market-wide.
     */
    public void recordEarliestAvailable(String source, String symbol, LocalDate earliest) {
        if (earliest == null) return;
        String owner = ensureOwner(OwnerScope.SYSTEM);
        String src = source == null || source.isBlank() ? "auto" : source.trim().toLowerCase(Locale.ROOT);
        String sym = Symbol.normalize(symbol);
        db.exec("INSERT INTO data_sync_cursor(user_id,source_key,symbol,earliest_available) VALUES (?,?,?,?) "
                        + "ON CONFLICT(user_id,source_key,symbol,domain,interval_key) DO UPDATE SET "
                        + "earliest_available=CASE WHEN data_sync_cursor.earliest_available IS NULL THEN excluded.earliest_available "
                        + "ELSE greatest(data_sync_cursor.earliest_available,excluded.earliest_available) END,"
                        + "updated_at=now()",
                owner, src, sym, earliest);
    }

    /**
     * Records a local BUDGET_EXHAUSTED deferral: sets next_allowed_at so a scheduler resumes at the
     * allowance reset rather than retrying now. A DEFERRED status is not a failure — failure_count is
     * left untouched so it never counts toward a breaker.
     */
    public void deferredUntilBudgetReset(String ownerId, String source, String symbol,
                                         LocalDate from, LocalDate to,
                                         java.time.Instant nextAllowedAt, String note) {
        if (nextAllowedAt == null) return;
        String owner = ensureOwner(ownerId);
        db.exec("INSERT INTO data_sync_cursor(user_id,source_key,symbol,status,requested_from,requested_to,"
                        + "last_attempt_at,next_allowed_at,note) VALUES (?,?,?,'DEFERRED',?,?,now(),?,?) "
                        + "ON CONFLICT(user_id,source_key,symbol,domain,interval_key) DO UPDATE SET "
                        + "status='DEFERRED',last_attempt_at=now(),next_allowed_at=excluded.next_allowed_at,"
                        + "note=excluded.note,updated_at=now()",
                owner, source == null || source.isBlank() ? "auto" : source.trim().toLowerCase(Locale.ROOT),
                Symbol.normalize(symbol),
                from, to, java.sql.Timestamp.from(nextAllowedAt), cap(note, 500));
    }

    /** The durable earliest-available boundary for a (source, symbol), market-wide; null if unknown. */
    public LocalDate earliestAvailable(String source, String symbol) {
        String sym = Symbol.normalize(symbol);
        String src = source == null || source.isBlank() || "auto".equalsIgnoreCase(source)
                ? null : source.trim().toLowerCase(Locale.ROOT);
        List<LocalDate> rows = src == null
                // 'auto' (any provider): a range is pre-history only if it predates the EARLIEST
                // coverage ANY provider has — the MIN boundary. Using MAX (the most-restrictive
                // provider) wrongly skipped ranges an earlier-starting provider could still serve.
                ? db.query("SELECT min(earliest_available)::text m FROM data_sync_cursor "
                        + "WHERE symbol=? AND earliest_available IS NOT NULL", r -> date(r.str("m")), sym)
                // A specific provider clamps to its OWN learned boundary.
                : db.query("SELECT max(earliest_available)::text m FROM data_sync_cursor "
                        + "WHERE symbol=? AND lower(source_key)=? AND earliest_available IS NOT NULL",
                        r -> date(r.str("m")), sym, src);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * If a BUDGET_EXHAUSTED deferral for (owner, source, symbol) is still in effect — status DEFERRED
     * with next_allowed_at in the future — returns that reset instant so the caller SKIPS re-attempting
     * the exhausted allowance now instead of re-spending it every tick. Empty once the reset has passed.
     */
    public java.util.Optional<java.time.Instant> deferredUntil(String ownerId, String source, String symbol) {
        String owner = OwnerScope.id(ownerId);
        String sym = Symbol.normalize(symbol);
        String src = source == null || source.isBlank() ? "auto" : source.trim().toLowerCase(Locale.ROOT);
        // Return the raw reset instant; the caller compares it against the injected app clock (not the
        // DB clock), so behavior is deterministic under a fixed test clock and honest in production.
        List<Long> rows = db.query(
                "SELECT (extract(epoch from next_allowed_at)*1000)::bigint ms FROM data_sync_cursor "
                        + "WHERE user_id=? AND lower(source_key)=? AND symbol=? AND status='DEFERRED' "
                        + "AND next_allowed_at IS NOT NULL ORDER BY next_allowed_at DESC LIMIT 1",
                r -> r.lng("ms"), owner, src, sym);
        return rows.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(java.time.Instant.ofEpochMilli(rows.getFirst()));
    }

    public void quarantine(String ownerId, String jobId, String source, String symbol, String rowRef,
                           String reason, String payloadExcerpt) {
        String owner = ensureOwner(ownerId);
        db.exec("INSERT INTO data_quarantine(user_id,job_id,source_key,symbol,row_ref,reason,payload_excerpt) VALUES (?,?,?,?,?,?,?)",
                owner, jobId, source, quarantineSymbol(symbol), cap(rowRef, 80),
                cap(reason, 300), cap(payloadExcerpt, 500));
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
        RawSchedule raw = db.query("SELECT user_id,enabled,source_key,symbols,years,last_run_date::text lrd,"
                        + "last_status,last_job_id,coverage_hash,completed_coverage_hash,"
                        + "updated_at::text ua FROM data_sync_schedule WHERE user_id=?",
                DataSyncState::rawSchedule, OwnerScope.id(ownerId)).stream().findFirst().orElse(null);
        return raw == null
                ? new Schedule(OwnerScope.id(ownerId), false, "auto", List.of(), 5,
                        null, null, null, coverageHash("auto", List.of(), 5), null, null)
                : readSchedule(raw);
    }

    public Schedule saveSchedule(String ownerId, boolean enabled, String source, List<String> symbols,
                                 int years) {
        String owner = ensureOwner(ownerId);
        List<String> normalized = normalizeSymbols(symbols, true).symbols().stream()
                .limit(MAX_SCHEDULE_SYMBOLS).toList();
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
        List<RawSchedule> stored = db.query(
                "SELECT user_id,enabled,source_key,symbols,years,last_run_date::text lrd,"
                        + "last_status,last_job_id,coverage_hash,completed_coverage_hash,"
                        + "updated_at::text ua FROM data_sync_schedule WHERE enabled=1",
                DataSyncState::rawSchedule);
        List<Schedule> valid = new ArrayList<>(stored.size());
        for (RawSchedule raw : stored) {
            Schedule schedule = readSchedule(raw);
            if (schedule.enabled()) valid.add(schedule);
        }
        return List.copyOf(valid);
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

    private Schedule readSchedule(RawSchedule raw) {
        try {
            NormalizedSymbols normalized = normalizePersistedSymbols(raw.symbols());
            String canonicalSymbols = String.join(",", normalized.symbols());
            String canonicalHash = coverageHash(raw.source(), normalized.symbols(), raw.years());
            String status = raw.lastStatus();
            String completedHash = raw.completedCoverageHash();
            if (normalized.changed() || !canonicalHash.equals(raw.coverageHash())) {
                boolean contractChanged = !canonicalHash.equals(raw.coverageHash());
                if (contractChanged) {
                    db.exec("UPDATE data_sync_schedule SET symbols=?,coverage_hash=?,"
                                    + "completed_coverage_hash=NULL,last_status='CONFIG_CHANGED',"
                                    + "updated_at=now() WHERE user_id=?",
                            canonicalSymbols, canonicalHash, raw.userId());
                    status = "CONFIG_CHANGED";
                    completedHash = null;
                } else {
                    db.exec("UPDATE data_sync_schedule SET symbols=?,coverage_hash=?,updated_at=now() "
                                    + "WHERE user_id=?",
                            canonicalSymbols, canonicalHash, raw.userId());
                }
            }
            return new Schedule(raw.userId(), raw.enabled(), raw.source(), normalized.symbols(),
                    raw.years(), raw.lastRunDate(), status, raw.lastJobId(), canonicalHash,
                    completedHash, raw.updatedAt());
        } catch (IllegalArgumentException invalid) {
            String reason = cap("INVALID_SYMBOLS · " + invalid.getMessage(), 500);
            db.exec("UPDATE data_sync_schedule SET enabled=0,last_status=?,updated_at=now() WHERE user_id=?",
                    reason, raw.userId());
            return new Schedule(raw.userId(), false, raw.source(), List.of(), raw.years(),
                    raw.lastRunDate(), reason, raw.lastJobId(), raw.coverageHash(),
                    raw.completedCoverageHash(), raw.updatedAt());
        }
    }

    private static RawSchedule rawSchedule(Db.Row r) {
        return new RawSchedule(r.str("user_id"), r.bool("enabled"), r.str("source_key"),
                r.str("symbols"), r.intv("years"), date(r.str("lrd")), r.str("last_status"),
                r.str("last_job_id"), r.str("coverage_hash"), r.str("completed_coverage_hash"),
                r.str("ua"));
    }

    private static NormalizedSymbols normalizePersistedSymbols(String joined) {
        if (joined == null || joined.isBlank()) return new NormalizedSymbols(List.of(), false);
        String[] members = joined.split(",", -1);
        for (String member : members) {
            if (member == null || member.isBlank()) {
                throw new IllegalArgumentException("stored schedule contains a blank symbol member");
            }
        }
        NormalizedSymbols normalized = normalizeSymbols(List.of(members), false);
        return new NormalizedSymbols(normalized.symbols(),
                !joined.equals(String.join(",", normalized.symbols())));
    }

    /**
     * Collection boundaries may normalize spelling, but may not silently collapse two persisted
     * identities onto one ticker. That would change the schedule's coverage contract without the
     * owner knowing which member survived.
     */
    private static NormalizedSymbols normalizeSymbols(List<String> rawSymbols, boolean ignoreBlanks) {
        Map<String, String> originals = new LinkedHashMap<>();
        List<String> normalized = new ArrayList<>();
        boolean changed = false;
        for (String raw : rawSymbols == null ? List.<String>of() : rawSymbols) {
            if (raw == null || raw.isBlank()) {
                if (ignoreBlanks) continue;
                throw new IllegalArgumentException("symbol is required");
            }
            String canonical = Symbol.normalize(raw);
            String prior = originals.putIfAbsent(canonical, raw);
            if (prior != null) {
                if (prior.trim().equalsIgnoreCase(raw.trim())) {
                    changed = true;
                    continue;
                }
                throw new IllegalArgumentException("canonical symbol collision: "
                        + prior.trim() + " and " + raw.trim() + " both resolve to " + canonical);
            }
            normalized.add(canonical);
            changed |= !canonical.equals(raw.trim());
        }
        return new NormalizedSymbols(List.copyOf(normalized), changed);
    }

    private record RawSchedule(String userId, boolean enabled, String source, String symbols,
                               int years, LocalDate lastRunDate, String lastStatus, String lastJobId,
                               String coverageHash, String completedCoverageHash, String updatedAt) {}
    private record NormalizedSymbols(List<String> symbols, boolean changed) {}

    /**
     * Stable identity for the exact daily observed-history coverage contract. Symbol order is not
     * semantic; source, lookback, or membership changes are. Bump the version literal if the
     * domain/interval/provenance contract itself changes.
     */
    static String coverageHash(String source, List<String> symbols, int years) {
        String src = source == null || source.isBlank()
                ? "auto" : source.trim().toLowerCase(Locale.ROOT);
        List<String> normalized = Symbol.list(symbols).stream().sorted().toList();
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

    /**
     * Quarantine is evidence about rejected input, not a canonical identity boundary. Preserve an
     * invalid member so the owner can diagnose it; requiring it to pass Symbol validation would
     * make malformed CSV/provider rows impossible to quarantine.
     */
    private static String quarantineSymbol(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Symbol.normalize(raw);
        } catch (IllegalArgumentException invalid) {
            return cap(raw.trim(), 80);
        }
    }

    private static LocalDate date(String raw) { return raw == null || raw.isBlank() ? null : LocalDate.parse(raw); }
    private static String cap(String s, int n) { return s == null ? null : s.substring(0, Math.min(n, s.length())); }
}
