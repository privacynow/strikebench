package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The dataset registry + the ACTIVE analysis dataset switch. 'observed' is the canonical real-data
 * dataset and can never be deleted or overwritten; each synthetic simulation run is auto-saved as
 * its own dataset (rows in the bar tables under its dataset_id) so runs coexist and are comparable.
 * The active dataset drives the candle read path — anything other than 'observed' is SCENARIO MODE,
 * surfaced loudly in the UI. Recommendations always default to observed.
 */
public final class DatasetService {

    public static final String OBSERVED = "observed";
    private static final String ACTIVE_KEY = "active_dataset";
    private static final int KEEP_SYNTHETIC = 25; // retention cap: oldest synthetic runs are pruned

    private final Db db;
    private final Clock clock;
    private volatile String activeCache = null;
    private io.liftandshift.strikebench.util.EventBus events; // optional: dataset switches to the UI

    public DatasetService(Db db, Clock clock) { this.db = db; this.clock = clock; }

    public void setEvents(io.liftandshift.strikebench.util.EventBus events) { this.events = events; }

    public record DatasetRow(String id, String name, String kind, String symbol, Long seed,
                             String spec, long bars, String createdAt) {}

    /**
     * OWNERSHIP MODEL: every synthetic dataset belongs to its creator ('local' when auth is off);
     * 'observed' is the shared system dataset. Listing, deletion, and retention pruning are scoped
     * to the owner — one user can never enumerate, delete, or prune another user's runs. The
     * ACTIVE selection stays a single app-level switch (it changes the global candle read path —
     * the tape, the engine, backtests), so with auth on the API additionally gates switching to
     * admins; here we enforce the ownership half: you may only activate observed or YOUR OWN run.
     */
    private static String owner(String userId) { return userId == null || userId.isBlank() ? "local" : userId; }

    public String activeId() {
        String a = activeCache;
        if (a != null) return a;
        var rows = db.query("SELECT v FROM settings WHERE k=?", r -> r.str("v"), ACTIVE_KEY);
        a = rows.isEmpty() || rows.getFirst() == null || rows.getFirst().isBlank() ? OBSERVED : rows.getFirst();
        activeCache = a;
        return a;
    }

    public void setActive(String id, String userId) {
        if (!OBSERVED.equals(id) && !ownedBy(id, userId)) {
            throw new java.util.NoSuchElementException("no such dataset: " + id); // absent OR someone else's — same answer
        }
        db.exec("INSERT INTO settings(k,v,updated_at) VALUES (?,?,?) ON CONFLICT (k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at",
                ACTIVE_KEY, id, clock.instant().toString());
        activeCache = id;
        if (events != null) events.publish("dataset.selected", java.util.Map.of("active", id));
    }

    /** True when the dataset exists AND belongs to this caller ('observed' belongs to everyone). */
    public boolean ownedBy(String id, String userId) {
        if (OBSERVED.equals(id)) return true;
        return db.query("SELECT 1 x FROM dataset WHERE id=? AND user_id IS NOT DISTINCT FROM ?",
                r -> 1, id, owner(userId)).size() > 0;
    }

    /** The caller's datasets + the shared observed one — never anyone else's. */
    public List<DatasetRow> list(String userId) {
        List<DatasetRow> out = new ArrayList<>();
        db.query("SELECT d.id, d.name, d.kind, d.symbol, d.seed, d.spec::text spec, d.created_at::text ca, "
              + "(SELECT count(*) FROM underlying_bar b WHERE b.dataset_id = d.id) bars "
              + "FROM dataset d WHERE d.id='observed' OR d.user_id IS NOT DISTINCT FROM ? "
              + "ORDER BY (d.id='observed') DESC, d.created_at DESC",
                r -> out.add(new DatasetRow(r.str("id"), r.str("name"), r.str("kind"), r.str("symbol"),
                        r.lngOrNull("seed"), r.str("spec"), r.lng("bars"), r.str("ca"))), owner(userId));
        return out;
    }

    /** Registers a synthetic dataset row; bars are written by the caller under the returned id. */
    public String create(String name, String kind, String symbol, long seed, Object spec, String userId) {
        String id = Ids.newId("ds");
        db.exec("INSERT INTO dataset (id, name, kind, symbol, seed, spec, user_id) VALUES (?,?,?,?,?,?::jsonb,?)",
                id, name, kind, symbol, seed, Json.write(spec), owner(userId));
        prune(userId);
        return id;
    }

    /** Deletes a synthetic dataset the caller OWNS (bars cascade). 'observed' is untouchable. */
    public void delete(String id, String userId) {
        if (OBSERVED.equals(id)) throw new IllegalArgumentException("The observed dataset cannot be deleted");
        if (!ownedBy(id, userId)) throw new java.util.NoSuchElementException("no such dataset: " + id);
        if (id.equals(activeId())) setActive(OBSERVED, userId); // never leave the app pointed at a ghost
        db.exec("DELETE FROM dataset WHERE id=?", id);
    }

    /** Retention is PER OWNER — creating your 26th run must never prune someone else's. */
    private void prune(String userId) {
        // The globally active dataset is always retained: pruning the run the app is analyzing
        // would silently flip everyone back to observed mid-thought.
        db.exec("DELETE FROM dataset WHERE id <> 'observed' AND user_id IS NOT DISTINCT FROM ? AND id <> ? AND id NOT IN "
              + "(SELECT id FROM dataset WHERE id <> 'observed' AND user_id IS NOT DISTINCT FROM ? "
              + " ORDER BY created_at DESC LIMIT ?)", owner(userId), activeId(), owner(userId), KEEP_SYNTHETIC);
    }

    public Map<String, Object> describe(String userId) {
        return Map.of("active", activeId(), "datasets", list(userId));
    }
}
