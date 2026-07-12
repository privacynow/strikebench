package io.liftandshift.strikebench.broker;

import io.liftandshift.strikebench.db.Db;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Key/value secrets in the local database (OAuth tokens). Never exposed over HTTP. */
public final class SecretsStore {

    private final Db db;
    private final Clock clock;

    public SecretsStore(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public void put(String key, String value) {
        db.exec("INSERT INTO secrets(k, v, updated_at) VALUES (?,?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at",
                key, value, Instant.now(clock).toString());
    }

    public Optional<String> get(String key) {
        List<String> rows = db.query("SELECT v FROM secrets WHERE k=?", r -> r.str("v"), key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void delete(String key) {
        db.exec("DELETE FROM secrets WHERE k=?", key);
    }
}
