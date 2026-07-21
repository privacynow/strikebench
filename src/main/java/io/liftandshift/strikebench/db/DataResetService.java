package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.paper.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tiered "reset data" for the Data Center — from clearing just market history up to a full
 * fresh-deploy wipe. Each tier deletes an explicit, dependency-safe set of records inside ONE transaction
 * (all-or-nothing), and paper/everything re-seed a funded default account so the app is usable
 * immediately after. Table names are hardcoded constants (never user input). The caller enforces
 * typed confirmation + admin auth before invoking this.
 */
public final class DataResetService {

    private static final Logger log = LoggerFactory.getLogger(DataResetService.class);

    // Storage targets stay internal. Product-facing responses use areas, never schema names.
    public enum Tier {
        MARKET_DATA(List.of("option_bar", "underlying_bar", "market_snapshot",
                "dataset WHERE id NOT IN ('observed','demo-fixture')", "settings WHERE k LIKE 'active_dataset%'",
                "data_quarantine", "data_sync_cursor", "data_job_item", "data_job"), List.of(
                        "Market history and snapshots", "Generated datasets", "Background data jobs", "Active data selection"), false),
        RESEARCH(List.of("recommendation", "strategy_evaluation", "backtests", "research_note"), List.of(
                "Saved recommendations", "Evaluations", "Backtests", "Research notes"), false),
        PAPER(List.of("trade_marks", "ledger", "positions", "live_orders", "audit", "trades", "accounts", "sim_session",
                "settings WHERE k LIKE 'active_world:%'"), List.of(
                "Practice trades and marks", "Share positions", "Practice orders", "Practice ledger and account",
                "Simulation practice sessions"), true),
        EVERYTHING(List.of("option_bar", "underlying_bar", "market_snapshot", "dataset WHERE id NOT IN ('observed','demo-fixture')",
                "data_quarantine", "data_sync_cursor", "data_sync_schedule", "data_job_item", "data_job",
                "plans", "ensemble_artifact", "recommendation", "strategy_evaluation", "backtests", "research_note", "workspace",
                "trade_marks", "ledger", "positions", "live_orders", "audit", "trades",
                "secrets", "settings WHERE k !~ '^[a-z0-9_-]+_cooldown_until$'", "accounts", "sim_session"), List.of(
                        "Market data and datasets", "Research and backtests", "Paper portfolio and account",
                        "Simulation practice sessions", "Workspace and local settings"), true);

        final List<String> tables;
        final List<String> areas;
        final boolean reseedAccount;
        Tier(List<String> tables, List<String> areas, boolean reseedAccount) {
            this.tables = tables;
            this.areas = areas;
            this.reseedAccount = reseedAccount;
        }
    }

    private final Db db;
    private final AccountService accounts;
    private final DataJobService jobs;
    private final SchedulerControl scheduler;
    private final MarketDataMaintenanceGate maintenance;

    /** Small lifecycle seam so reset completion can report a degraded remount without lying. */
    public interface SchedulerControl {
        boolean pauseForReset();
        void resumeAfterReset(boolean wasRunning);
    }

    public DataResetService(Db db, AccountService accounts) {
        this(db, accounts, null, null, new MarketDataMaintenanceGate());
    }

    public DataResetService(Db db, AccountService accounts, DataJobService jobs,
                            DataSyncScheduler scheduler) {
        this(db, accounts, jobs, scheduler,
                jobs == null ? new MarketDataMaintenanceGate() : jobs.maintenanceGate());
    }

    public DataResetService(Db db, AccountService accounts, DataJobService jobs,
                            SchedulerControl scheduler, MarketDataMaintenanceGate maintenance) {
        this.db = db;
        this.accounts = accounts;
        this.jobs = jobs;
        this.scheduler = scheduler;
        this.maintenance = java.util.Objects.requireNonNull(maintenance, "maintenance");
    }

    public record ResetResult(String tier, List<String> areasCleared, boolean reseededAccount,
                              String maintenanceStatus, List<String> warnings) {
        public ResetResult {
            areasCleared = List.copyOf(areasCleared == null ? List.of() : areasCleared);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }
    }

    public static Tier parseTier(String raw) {
        String t = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        try { return Tier.valueOf(t); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("unknown reset tier: " + raw); }
    }

    /** Product areas affected by a tier, suitable for UI and audit output. */
    public List<String> areasFor(Tier tier) { return tier.areas; }

    public ResetResult reset(Tier tier) {
        boolean clearsJobs = tier == Tier.MARKET_DATA || tier == Tier.EVERYTHING;
        boolean jobsPaused = false;
        boolean schedulerHandled = false;
        boolean schedulerWasRunning = false;
        boolean committed = false;
        boolean reseeded = false;
        RuntimeException failure = null;
        List<String> warnings = new ArrayList<>();
        MarketDataMaintenanceGate.ResetLease resetLease = null;
        try {
            if (clearsJobs && jobs != null) {
                jobs.quiesceForReset(Duration.ofSeconds(15));
                jobsPaused = true;
            }
            if (clearsJobs && scheduler != null) {
                schedulerHandled = true;
                schedulerWasRunning = scheduler.pauseForReset();
            }
            if (clearsJobs) {
                resetLease = maintenance.pauseWrites(Duration.ofSeconds(15));
                clear(tier);
            } else {
                clear(tier);
            }
            if (tier.reseedAccount) {
                accounts.getOrCreateDefault(); // a funded default account so the app is usable post-reset
                reseeded = true;
            }
            committed = true;
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            if (schedulerHandled) {
                try {
                    scheduler.resumeAfterReset(schedulerWasRunning);
                } catch (RuntimeException remountFailure) {
                    if (committed) {
                        String warning = "Scheduled market-data maintenance could not be remounted automatically; "
                                + "restart the service or check Data health.";
                        warnings.add(warning);
                        log.warn("Market-data reset committed, but scheduled maintenance could not be remounted");
                        log.debug("Scheduled maintenance remount detail", remountFailure);
                    } else if (failure != null) {
                        failure.addSuppressed(remountFailure);
                    } else {
                        failure = remountFailure;
                    }
                }
            }
            if (jobsPaused) jobs.resumeAfterReset();
            if (resetLease != null) resetLease.close();
        }
        if (failure != null) throw failure;
        String health = warnings.isEmpty() ? "READY" : "DEGRADED";
        log.warn("Local data reset completed: tier={} areas={} freshPracticeAccount={} maintenanceStatus={}",
                tier, tier.areas, reseeded, health);
        return new ResetResult(tier.name(), tier.areas, reseeded, health, warnings);
    }

    private void clear(Tier tier) {
        db.tx(c -> {
            for (String table : tier.tables) {
                Db.execOn(c, "DELETE FROM " + table); // entries may carry their own WHERE predicate
            }
            if (tier == Tier.MARKET_DATA) {
                // Keep the user's schedule contract, but never keep a completion receipt for the
                // corpus that this transaction just removed.  Clearing these fields in the same
                // transaction as the bars/jobs means the next scheduler tick must rebuild coverage.
                Db.execOn(c, "UPDATE data_sync_schedule SET last_run_date=NULL,"
                        + "last_status='MARKET_DATA_RESET',last_job_id=NULL,"
                        + "completed_coverage_hash=NULL,updated_at=now()");
            }
            return null;
        });
    }
}
