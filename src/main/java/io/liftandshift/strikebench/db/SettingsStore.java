package io.liftandshift.strikebench.db;

import java.util.Optional;

/** Small typed boundary for process-wide operational settings. */
public final class SettingsStore {
    private final Db db;

    public SettingsStore(Db db) {
        this.db = db;
    }

    public Optional<String> get(String key) {
        requireKey(key);
        var values = db.query("SELECT v FROM settings WHERE k=?", row -> row.str("v"), key);
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
    }

    public void put(String key, String value) {
        requireKey(key);
        if (value == null) throw new IllegalArgumentException("setting value is required");
        db.exec("INSERT INTO settings(k,v,updated_at) VALUES (?,?,now()) "
                        + "ON CONFLICT (k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at",
                key, value);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("setting key is required");
    }
}
