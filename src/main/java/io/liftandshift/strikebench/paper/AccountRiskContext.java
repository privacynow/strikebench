package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.SettingsStore;
import io.liftandshift.strikebench.util.Json;

/**
 * The user's REAL account, self-declared: paper buying power is not the denominator that matters
 * when judging size. Every field is optional and user-entered (or broker-fetched when connected) —
 * never inferred as exact from a single broker screen. Stored per user in settings as JSON.
 *
 * All values are cents. null = not provided.
 */
public record AccountRiskContext(
        Long nlvCents,           // net liquidation value
        Long cashBpCents,        // cash buying power
        Long marginBpCents,      // margin buying power
        Long maintenanceCents,   // maintenance requirement
        Long riskCapitalCents    // self-defined max risk per trade
) {
    public boolean isEmpty() {
        return nlvCents == null && cashBpCents == null && marginBpCents == null
                && maintenanceCents == null && riskCapitalCents == null;
    }

    private static String key(String owner) {
        return "risk_context:" + (owner == null || owner.isBlank() ? "local" : owner);
    }

    public static AccountRiskContext load(Db db, String owner) {
        var raw = SettingsStore.read(db, key(owner)).filter(s -> !s.isBlank());
        if (raw.isEmpty()) {
            return new AccountRiskContext(null, null, null, null, null);
        }
        try { return Json.read(raw.get(), AccountRiskContext.class); }
        catch (RuntimeException e) {
            // A malformed stored limit must never become an empty context: that can turn a
            // user-declared cap into the paper account's much larger buying-power allowance.
            throw new IllegalStateException("Stored account risk limits are invalid; review and save them again before sizing a trade.", e);
        }
    }

    public static void save(Db db, String owner, AccountRiskContext rc) {
        // JVM wall-clock preserved (deliberately NOT the injected clock — self-declared limits are
        // real-account facts, timestamped in real time).
        SettingsStore.upsert(db, key(owner), Json.write(rc), java.time.Instant.now());
    }
}
