package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.paper.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * Tiered "reset data" for the Data Center — from clearing just market history up to a full
 * fresh-deploy wipe. Each tier deletes an explicit, FK-safe list of tables inside ONE transaction
 * (all-or-nothing), and paper/everything re-seed a funded default account so the app is usable
 * immediately after. Table names are hardcoded constants (never user input). The caller enforces
 * typed confirmation + admin auth before invoking this.
 */
public final class DataResetService {

    private static final Logger log = LoggerFactory.getLogger(DataResetService.class);

    // Every table the tier touches. Entries may carry a predicate ("dataset WHERE id <> 'observed'")
    // — the display name shown to the user is the part before WHERE. The 'observed' dataset registry
    // row is schema seed data (bar tables default/FK onto it), never user data, so it survives.
    public enum Tier {
        MARKET_DATA(List.of("option_bar", "underlying_bar", "market_snapshot",
                "dataset WHERE id <> 'observed'", "settings WHERE k IN ('active_dataset','cboe_cooldown_until')",
                "data_job_item", "data_job"), false),
        RESEARCH(List.of("recommendation", "strategy_evaluation", "backtests", "research_note"), false),
        PAPER(List.of("trade_marks", "ledger", "positions", "live_orders", "audit", "trades", "accounts"), true),
        EVERYTHING(List.of("option_bar", "underlying_bar", "market_snapshot", "dataset WHERE id <> 'observed'",
                "data_job_item", "data_job",
                "recommendation", "strategy_evaluation", "backtests", "research_note", "workspace",
                "trade_marks", "ledger", "positions", "live_orders", "audit", "trades",
                "secrets", "settings", "accounts"), true);

        final List<String> tables;
        final boolean reseedAccount;
        Tier(List<String> tables, boolean reseedAccount) { this.tables = tables; this.reseedAccount = reseedAccount; }
    }

    private final Db db;
    private final AccountService accounts;

    public DataResetService(Db db, AccountService accounts) {
        this.db = db;
        this.accounts = accounts;
    }

    public record ResetResult(String tier, List<String> tablesCleared, boolean reseededAccount) {}

    public static Tier parseTier(String raw) {
        String t = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        try { return Tier.valueOf(t); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("unknown reset tier: " + raw); }
    }

    /** The exact table list a tier will clear — surfaced to the UI so the user sees what's affected. */
    public List<String> tablesFor(Tier tier) { return displayNames(tier); }

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
        log.warn("DATA RESET tier={} cleared={} reseeded={}", tier, tier.tables, reseeded);
        return new ResetResult(tier.name(), displayNames(tier), reseeded);
    }

    /** The user-facing table list: predicate entries show just the table name. */
    private static List<String> displayNames(Tier tier) {
        return tier.tables.stream().map(t -> t.contains(" WHERE ") ? t.substring(0, t.indexOf(" WHERE ")) : t).toList();
    }
}
