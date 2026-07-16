package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.OwnerScope;

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
    private static final String ACTIVE_KEY_PREFIX = "active_dataset:";
    private static final int KEEP_SYNTHETIC = 25; // retention cap: oldest synthetic runs are pruned

    private final Db db;
    private final Clock clock;
    private final java.util.concurrent.ConcurrentHashMap<String, String> activeCache = new java.util.concurrent.ConcurrentHashMap<>();
    private io.liftandshift.strikebench.util.EventBus events; // optional: dataset switches to the UI

    public DatasetService(Db db, Clock clock) { this.db = db; this.clock = clock; }

    public void setEvents(io.liftandshift.strikebench.util.EventBus events) { this.events = events; }

    public record DatasetRow(String id, String name, String kind, String symbol, Long seed,
                             String spec, long bars, String createdAt) {}

    /**
     * OWNERSHIP MODEL: every synthetic dataset belongs to its creator ('local' when auth is off);
     * 'observed' is the shared system dataset. Listing, deletion, retention pruning, AND the active
     * selection are all scoped to the owner — one user can never enumerate, delete, prune, or flip
     * the read path of another user's world. The per-request candle read path resolves the CALLER'S
     * selection (see AnalysisContext); background machinery always reads observed.
     */
    private static String owner(String userId) { return OwnerScope.id(userId); }

    /**
     * PER-USER active dataset: one user exploring a synthetic future must never flip anyone
     * else's read path. Stored per owner under {@code active_dataset:<owner>}.
     */
    public String activeId(String userId) {
        String k = ACTIVE_KEY_PREFIX + owner(userId);
        String cached = activeCache.get(k);
        if (cached != null) return cached;
        var rows = db.query("SELECT v FROM settings WHERE k=?", r -> r.str("v"), k);
        String a = rows.isEmpty() || rows.getFirst() == null || rows.getFirst().isBlank() ? null : rows.getFirst();
        if (a == null) a = OBSERVED;
        // A dangling id (dataset pruned/deleted) silently means observed, never a ghost world.
        if (!OBSERVED.equals(a) && !exists(a)) a = OBSERVED;
        activeCache.put(k, a);
        return a;
    }

    private boolean exists(String id) {
        return db.query("SELECT 1 x FROM dataset WHERE id=?", r -> 1, id).size() > 0;
    }

    public void setActive(String id, String userId) {
        if (!OBSERVED.equals(id) && !ownedBy(id, userId)) {
            throw new io.liftandshift.strikebench.util.ResourceNotFoundException("no such dataset: " + id); // absent OR someone else's — same answer
        }
        String k = ACTIVE_KEY_PREFIX + owner(userId);
        db.exec("INSERT INTO settings(k,v,updated_at) VALUES (?,?,?) ON CONFLICT (k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at",
                k, id, clock.instant().toString());
        activeCache.put(k, id);
        if (events != null) {
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("active", id);
            data.put("user", owner(userId)); // owner-scoped delivery on /api/events
            events.publish("dataset.selected", data);
        }
    }

    /** Drops the in-memory active-dataset cache (used after a Data reset wipes the settings rows). */
    public void invalidateActiveCache() { activeCache.clear(); }

    /** Human name for a dataset id — the scenario banner must never show a raw ds_… id. */
    public String nameOf(String id) {
        if (OBSERVED.equals(id)) return "Observed market data";
        var rows = db.query("SELECT name FROM dataset WHERE id=?", r -> r.str("name"), id);
        return rows.isEmpty() || rows.getFirst() == null ? id : rows.getFirst();
    }

    /** True when the dataset exists AND belongs to this caller ('observed' belongs to everyone). */
    public boolean ownedBy(String id, String userId) {
        if (OBSERVED.equals(id)) return true;
        return db.query("SELECT 1 x FROM dataset WHERE id=? AND user_id=?",
                r -> 1, id, owner(userId)).size() > 0;
    }

    /** The caller's datasets + the shared observed one — never anyone else's. */
    public List<DatasetRow> list(String userId) {
        List<DatasetRow> out = new ArrayList<>();
        db.query("SELECT d.id, d.name, d.kind, d.symbol, d.seed, d.spec::text spec, d.created_at::text ca, "
              + "(SELECT count(*) FROM underlying_bar b WHERE b.dataset_id = d.id) bars "
              + "FROM dataset d WHERE d.id='observed' OR d.user_id=? "
              + "ORDER BY (d.id='observed') DESC, d.created_at DESC",
                r -> out.add(new DatasetRow(r.str("id"), r.str("name"), r.str("kind"), r.str("symbol"),
                        r.lngOrNull("seed"), r.str("spec"), r.lng("bars"), r.str("ca"))), owner(userId));
        return out;
    }

    /** Registers a synthetic dataset row; bars are written by the caller under the returned id. */
    public String create(String name, String kind, String symbol, long seed, Object spec, String userId) {
        String id = Ids.newId("ds");
        db.tx(c -> {
            String owner = OwnerScope.ensure(c, userId);
            Db.execOn(c, "INSERT INTO dataset (id, name, kind, symbol, seed, spec, user_id) VALUES (?,?,?,?,?,?::jsonb,?)",
                    id, name, kind, symbol, seed, Json.write(spec), owner);
            return null;
        });
        prune(userId);
        return id;
    }

    /** Deletes a synthetic dataset the caller OWNS (bars cascade). 'observed' is untouchable. */
    public void delete(String id, String userId) {
        if (OBSERVED.equals(id)) throw new IllegalArgumentException("The observed dataset cannot be deleted");
        if (!ownedBy(id, userId)) throw new io.liftandshift.strikebench.util.ResourceNotFoundException("no such dataset: " + id);
        if (id.equals(activeId(userId))) setActive(OBSERVED, userId); // never leave this user pointed at a ghost
        db.exec("DELETE FROM dataset WHERE id=?", id);
    }

    /** Retention is PER OWNER — creating your 26th run must never prune someone else's. */
    private void prune(String userId) {
        // The globally active dataset is always retained: pruning the run the app is analyzing
        // would silently flip everyone back to observed mid-thought.
        db.exec("DELETE FROM dataset WHERE id <> 'observed' AND user_id=? AND id <> ? AND id NOT IN "
              + "(SELECT id FROM dataset WHERE id <> 'observed' AND user_id=? "
              + " ORDER BY created_at DESC LIMIT ?)", owner(userId), activeId(userId), owner(userId), KEEP_SYNTHETIC);
    }

    public Map<String, Object> describe(String userId) {
        return Map.of("active", activeId(userId), "datasets", list(userId));
    }
}
