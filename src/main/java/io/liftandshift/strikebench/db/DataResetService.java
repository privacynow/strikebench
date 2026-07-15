package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.paper.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
                "secrets", "settings WHERE k <> 'cboe_cooldown_until'", "accounts", "sim_session"), List.of(
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

    public DataResetService(Db db, AccountService accounts) {
        this.db = db;
        this.accounts = accounts;
    }

    public record ResetResult(String tier, List<String> areasCleared, boolean reseededAccount) {}

    public static Tier parseTier(String raw) {
        String t = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        try { return Tier.valueOf(t); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("unknown reset tier: " + raw); }
    }

    /** Product areas affected by a tier, suitable for UI and audit output. */
    public List<String> areasFor(Tier tier) { return tier.areas; }

    public ResetResult reset(Tier tier) {
        db.tx(c -> {
            for (String table : tier.tables) {
                Db.execOn(c, "DELETE FROM " + table); // entries may carry their own WHERE predicate
            }
            return null;
        });
        boolean reseeded = false;
        if (tier.reseedAccount) {
            accounts.getOrCreateDefault(); // a funded default account so the app is usable post-reset
            reseeded = true;
        }
        log.warn("Local data reset completed: tier={} areas={} freshPracticeAccount={}", tier, tier.areas, reseeded);
        return new ResetResult(tier.name(), tier.areas, reseeded);
    }
}
