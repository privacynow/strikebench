package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.OwnerScope;

import java.time.Clock;
import java.time.Instant;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The dataset registry + the ACTIVE analysis dataset switch. 'observed' is the normalized real-data
 * dataset and can never be deleted or overwritten; each synthetic simulation run is auto-saved as
 * its own dataset (rows in the bar tables under its dataset_id) so runs coexist and are comparable.
 * The active dataset drives the candle read path — anything other than 'observed' is SCENARIO MODE,
 * surfaced loudly in the UI. Recommendations always default to observed.
 */
public final class DatasetService {

    public static final String OBSERVED = "observed";
    private static final int KEEP_SYNTHETIC = 25; // retention cap: oldest synthetic runs are pruned

    private final Db db;
    private final Clock clock;
    private final java.util.concurrent.ConcurrentHashMap<String, String> activeCache = new java.util.concurrent.ConcurrentHashMap<>();
    private io.liftandshift.strikebench.util.EventBus events; // optional: dataset switches to the UI

    public DatasetService(Db db, Clock clock) { this.db = db; this.clock = clock; }

    public void setEvents(io.liftandshift.strikebench.util.EventBus events) { this.events = events; }

    public record DatasetRow(String id, String name, String kind, String symbol, Long seed,
                             String spec, long bars, String createdAt) {}
    public record SelectionMutation(String activeId, boolean changed) {}
    public record DeleteMutation(boolean deleted, boolean selectionChanged, String activeId) {}
    /**
     * Normalized durable interpretation of one owner's selector. A dangling or foreign id is
     * repaired to Observed in the caller's transaction; it is never merely hidden in a cache while
     * another subsystem continues reading the invalid setting.
     */
    public record ActiveSelection(String activeId, boolean repaired,
                                  String previousId, String repairReason) {}

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
        String scopedOwner = owner(userId);
        String k = SettingsStore.activeDatasetKey(scopedOwner);
        String cached = activeCache.get(k);
        if (cached != null) return cached;
        ActiveSelection resolved = db.tx(connection ->
                resolveActiveOn(connection, scopedOwner, clock.instant()));
        activeCache.put(k, resolved.activeId());
        return resolved.activeId();
    }

    public void setActive(String id, String userId) {
        String scopedOwner = owner(userId);
        invalidateActiveCache(scopedOwner);
        SelectionMutation mutation = db.tx(c -> selectOn(c, id, scopedOwner, clock.instant()));
        invalidateActiveCache(scopedOwner);
        if (events != null) {
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("active", mutation.activeId());
            data.put("user", scopedOwner); // owner-scoped delivery on /api/events
            events.publish("dataset.selected", data);
        }
    }

    /** Drops the in-memory active-dataset cache (used after a Data reset wipes the settings rows). */
    public void invalidateActiveCache() { activeCache.clear(); }

    /** Drops only one owner's selector cache; safe after an owner-serialized commit. */
    public void invalidateActiveCache(String userId) {
        activeCache.remove(SettingsStore.activeDatasetKey(owner(userId)));
    }

    /**
     * Connection-scoped selector mutation. DatasetService remains the persistence/ownership
     * authority; WorldTransitionService composes this with workspace reconciliation and publishes
     * only after the caller's transaction commits.
     */
    public SelectionMutation selectOn(Connection connection, String id, String userId, Instant now)
            throws SQLException {
        String scopedOwner = owner(userId);
        OwnerScope.lock(connection, scopedOwner);
        requireOwnedOn(connection, id, scopedOwner);
        String key = SettingsStore.activeDatasetKey(scopedOwner);
        String current = SettingsStore.readOn(connection, key)
                .filter(value -> !value.isBlank()).orElse(OBSERVED);
        if (current.equals(id)) return new SelectionMutation(id, false);
        SettingsStore.upsertOn(connection, key, id, now);
        return new SelectionMutation(id, true);
    }

    /**
     * Resolves and, when necessary, repairs the selector while the owner's transition lock is held.
     * Existence is owner-scoped: another user's valid dataset id is invalid for this caller.
     */
    public ActiveSelection resolveActiveOn(Connection connection, String userId, Instant now)
            throws SQLException {
        String scopedOwner = owner(userId);
        OwnerScope.lock(connection, scopedOwner);
        String key = SettingsStore.activeDatasetKey(scopedOwner);
        String selected = SettingsStore.readOn(connection, key)
                .filter(value -> !value.isBlank()).orElse(OBSERVED);
        if (OBSERVED.equals(selected)) {
            return new ActiveSelection(OBSERVED, false, null, null);
        }
        boolean owned = !Db.queryOn(connection,
                "SELECT 1 x FROM dataset WHERE id=? AND user_id=? FOR SHARE",
                row -> 1, selected, scopedOwner).isEmpty();
        if (owned) return new ActiveSelection(selected, false, null, null);
        SettingsStore.upsertOn(connection, key, OBSERVED, now);
        return new ActiveSelection(OBSERVED, true, selected,
                "ACTIVE_DATASET_UNAVAILABLE_OR_NOT_OWNED");
    }

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
        String scopedOwner = owner(userId);
        invalidateActiveCache(scopedOwner);
        db.tx(connection -> deleteOn(connection, id, scopedOwner, clock.instant()));
        invalidateActiveCache(scopedOwner);
    }

    /** Deletes and, when necessary, repoints this owner in the caller's transaction. */
    public DeleteMutation deleteOn(Connection connection, String id, String userId, Instant now)
            throws SQLException {
        if (OBSERVED.equals(id)) {
            throw new IllegalArgumentException("The observed dataset cannot be deleted");
        }
        String scopedOwner = owner(userId);
        OwnerScope.lock(connection, scopedOwner);
        requireOwnedOn(connection, id, scopedOwner);
        String key = SettingsStore.activeDatasetKey(scopedOwner);
        String active = SettingsStore.readOn(connection, key)
                .filter(value -> !value.isBlank()).orElse(OBSERVED);
        boolean selectionChanged = id.equals(active);
        if (selectionChanged) {
            SettingsStore.upsertOn(connection, key, OBSERVED, now);
            active = OBSERVED;
        }
        int deleted = Db.execOn(connection,
                "DELETE FROM dataset WHERE id=? AND user_id=?", id, scopedOwner);
        if (deleted != 1) {
            throw new io.liftandshift.strikebench.util.ResourceNotFoundException(
                    "no such dataset: " + id);
        }
        return new DeleteMutation(true, selectionChanged, active);
    }

    /** Retention is PER OWNER — creating your 26th run must never prune someone else's. */
    private void prune(String userId) {
        String scopedOwner = owner(userId);
        invalidateActiveCache(scopedOwner);
        db.tx(connection -> {
            OwnerScope.lock(connection, scopedOwner);
            String active = SettingsStore
                    .readOn(connection, SettingsStore.activeDatasetKey(scopedOwner))
                    .filter(value -> !value.isBlank()).orElse(OBSERVED);
            Db.execOn(connection,
                    "DELETE FROM dataset WHERE id <> 'observed' AND user_id=? AND id <> ? AND id NOT IN "
                            + "(SELECT id FROM dataset WHERE id <> 'observed' AND user_id=? "
                            + " ORDER BY created_at DESC LIMIT ?)",
                    scopedOwner, active, scopedOwner, KEEP_SYNTHETIC);
            return null;
        });
        invalidateActiveCache(scopedOwner);
    }

    private static void requireOwnedOn(Connection connection, String id, String owner)
            throws SQLException {
        if (OBSERVED.equals(id)) return;
        boolean owned = !Db.queryOn(connection,
                "SELECT 1 x FROM dataset WHERE id=? AND user_id=? FOR UPDATE",
                row -> 1, id, owner).isEmpty();
        if (!owned) {
            throw new io.liftandshift.strikebench.util.ResourceNotFoundException(
                    "no such dataset: " + id);
        }
    }

    public Map<String, Object> describe(String userId) {
        return Map.of("active", activeId(userId), "datasets", list(userId));
    }

    /** Description with an already-validated active id supplied by WorldTransitionService. */
    public Map<String, Object> describe(String userId, String activeId) {
        return Map.of("active", activeId, "datasets", list(userId));
    }
}
