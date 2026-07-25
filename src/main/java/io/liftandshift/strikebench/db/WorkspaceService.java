package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.util.EventBus;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.liftandshift.strikebench.util.OwnerScope;

/**
 * Per-user persistence for THE one {@link WorkspaceContext} (audit §6, backend gap 8). The blob is
 * no longer a free-form client scratchpad: it is one versioned, server-stamped record, and this
 * service is its only writer.
 *
 * <p>Three guarantees, all of them enforced inside a single row-locked transaction:
 *
 * <ul>
 *   <li><b>A partial write never clears an untouched declaration.</b> {@link #patch} reads,
 *       merges and writes under {@code SELECT … FOR UPDATE}; omitted fields keep their stored
 *       value. Import Trade wiping goal/view/horizon/risk is structurally impossible here.</li>
 *   <li><b>A world change commits atomically.</b> Market-owned focus is cleared, the new world,
 *       lane and account are stamped, and the generation advances in ONE value written by ONE
 *       statement. No caller can observe a context that says Observed beside Demo's focus,
 *       because that value never exists — reads reconcile before they answer, exactly as
 *       {@code WorldTransitionService.active} repairs a stale world selector on read.</li>
 *   <li><b>An unknown stored version is refused, never half-read.</b> The row is left intact and
 *       the reason is returned; a merge onto an unreadable base is rejected rather than guessed
 *       at. A full replace heals it.</li>
 * </ul>
 */
public final class WorkspaceService {

    /** Hard cap on the stored blob — the workspace is declarations and ids, never result payloads. */
    public static final int MAX_STATE_BYTES = 128 * 1024;

    private final Db db;
    private final Clock clock;
    private EventBus events; // optional

    public WorkspaceService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public void setEvents(EventBus events) { this.events = events; }

    record Workspace(String stateJson, long rev, String updatedAt) {}

    /**
     * One workspace answer: the committed context (or nothing stored), its revision, the world
     * transition that was committed while answering, and any refusal to read what was stored.
     * Mode and context travel together — a caller cannot render one without the other.
     */
    public record ContextState(long rev, String updatedAt, WorkspaceContext context,
                               WorkspaceContext.Transition transition,
                               WorkspaceContext.Unreadable unreadable) {
        /** Nothing is stored: an undeclared workspace, not an empty default (§3.5). */
        static ContextState nothingStored() {
            return new ContextState(0, null, null, null, null);
        }
    }

    /** Anonymous (auth off) sessions share the single local workspace. */
    private static String key(String userId) { return OwnerScope.id(userId); }

    /**
     * Reads the stored context, reconciled to the caller's active market before it is returned.
     * A world change is committed here (one write) so no half-applied context is ever observable.
     */
    public ContextState context(String userId, WorkspaceContext.ActiveMarket market) {
        return commit(userId, market, false, (stored, base, rev) -> base);
    }

    /**
     * Full replace: the request declares every client-owned field, so anything it omits becomes
     * undeclared. Server facts stay server-stamped. This is also the repair path — a stored blob
     * this build cannot read is replaced rather than merged onto.
     */
    public ContextState replace(String userId, WorkspaceContext requested,
                                WorkspaceContext.ActiveMarket market) {
        if (requested == null) throw new IllegalArgumentException("a workspace context body is required");
        guardWorld(requested.world(), market);
        return commit(userId, market, true,
                (stored, base, rev) -> base.replacedWith(requested).validated());
    }

    /**
     * Partial write: only the fields the patch names change; everything else is preserved exactly.
     * Refuses to merge onto a stored blob this build could not read, because a half-read base
     * would silently drop declarations the user still has.
     */
    public ContextState patch(String userId, WorkspaceContext.Patch patch,
                              WorkspaceContext.ActiveMarket market) {
        if (patch == null) throw new IllegalArgumentException("a workspace patch body is required");
        guardWorld(patch.world(), market);
        return commit(userId, market, true, (stored, base, rev) -> {
            guardRevision(patch.expectedRev(), rev);
            if (stored != null && stored.unreadable() != null) {
                throw new IllegalStateException(stored.unreadable().reason()
                        + ". Send a complete workspace context (PUT /api/workspace) to replace it;"
                        + " a partial write onto an unreadable context would drop declarations.");
            }
            return base.merge(patch).validated();
        });
    }

    /** Optional optimistic guard used by writes that carry {@code expectedRev}. */
    private static void guardRevision(Long expectedRev, long actualRev) {
        if (expectedRev != null && expectedRev != actualRev) {
            throw new IllegalStateException("the workspace moved on (revision " + actualRev
                    + ", this write expected " + expectedRev + "); re-read it before writing again");
        }
    }

    /** A write that names another world is a stale publication from a previous context generation. */
    private static void guardWorld(String declaredWorld, WorkspaceContext.ActiveMarket market) {
        if (declaredWorld == null || declaredWorld.isBlank()) return;
        if (market == null || !declaredWorld.trim().equals(market.world())) {
            throw new IllegalStateException("this write was made against market '" + declaredWorld.trim()
                    + "' but the active market is '" + (market == null ? "unknown" : market.world())
                    + "'; re-read the workspace context before writing");
        }
    }

    private interface Apply {
        WorkspaceContext apply(WorkspaceContext.Stored stored, WorkspaceContext base, long rev);
    }

    private record Committed(ContextState state, boolean wrote) {}

    /**
     * The one read-modify-write path. The row is locked for the whole operation, so a concurrent
     * writer cannot interleave between the read and the write, and the world reconcile plus the
     * caller's own change land in a single statement.
     *
     * <p>A read creates nothing: with no stored context the answer is "nothing stored" rather than
     * a manufactured default (§3.5). A read of an unreadable blob reports the refusal and leaves
     * the row exactly as it is.
     */
    private ContextState commit(String userId, WorkspaceContext.ActiveMarket market, boolean writing,
                                Apply apply) {
        String owner = key(userId);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Committed committed = db.tx(c -> {
            Optional<Workspace> row = lock(c, owner);
            WorkspaceContext.Stored stored = row.map(r -> WorkspaceContext.read(r.stateJson())).orElse(null);
            boolean readable = stored != null && stored.readable();
            long rev = row.map(Workspace::rev).orElse(0L);

            if (!writing) {
                if (stored == null) return new Committed(ContextState.nothingStored(), false);
                if (!readable) {
                    return new Committed(new ContextState(rev, row.orElseThrow().updatedAt(),
                            null, null, stored.unreadable()), false);
                }
                WorkspaceContext.WorldCommit moved = stored.context().inWorld(market);
                if (moved.transition() == null && moved.context().equals(stored.context())) {
                    return new Committed(new ContextState(rev, row.orElseThrow().updatedAt(),
                            moved.context(), null, null), false);
                }
                Workspace saved = write(c, owner, moved.context(), now);
                return new Committed(new ContextState(saved.rev(), saved.updatedAt(),
                        moved.context(), moved.transition(), null), true);
            }

            WorkspaceContext.WorldCommit moved = readable
                    ? stored.context().inWorld(market)
                    : new WorkspaceContext.WorldCommit(WorkspaceContext.empty(market), null);
            WorkspaceContext next = apply.apply(stored, moved.context(), rev);
            if (next == null) throw new IllegalStateException("a workspace write produced no context");
            if (readable && next.equals(stored.context())) {
                return new Committed(new ContextState(rev, row.orElseThrow().updatedAt(),
                        next, null, null), false);
            }
            Workspace saved = write(c, owner, next, now);
            return new Committed(new ContextState(saved.rev(), saved.updatedAt(), next,
                    moved.transition(), null), true);
        });
        if (committed.wrote()) announce(owner, committed.state());
        return committed.state();
    }

    private static Optional<Workspace> lock(Connection c, String owner) throws SQLException {
        List<Workspace> rows = Db.queryOn(c,
                "SELECT state::text s, rev, updated_at::text ua FROM workspace WHERE user_id=? FOR UPDATE",
                r -> new Workspace(r.str("s"), r.lng("rev"), r.str("ua")), owner);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private static Workspace write(Connection c, String owner, WorkspaceContext context,
                                   OffsetDateTime now) throws SQLException {
        OwnerScope.ensure(c, owner);
        String json = context.toJson();
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STATE_BYTES) {
            throw new IllegalArgumentException("workspace too large (max " + (MAX_STATE_BYTES / 1024)
                    + "KB) — persist declarations and ids, not result payloads");
        }
        // Db.prep uses RETURN_GENERATED_KEYS, which PgJDBC can't combine with INSERT..RETURNING
        // through executeQuery — so upsert and read the new rev in the same locked transaction.
        Db.execOn(c, "INSERT INTO workspace (user_id, state, rev, updated_at) VALUES (?, ?::jsonb, 1, ?) "
                + "ON CONFLICT (user_id) DO UPDATE SET state=excluded.state, rev=workspace.rev+1, "
                + "updated_at=excluded.updated_at", owner, json, now);
        return Db.queryOn(c, "SELECT rev, updated_at::text ua FROM workspace WHERE user_id=?",
                r -> new Workspace(json, r.lng("rev"), r.str("ua")), owner).getFirst();
    }

    /**
     * One announcement carrying the revision AND the market it belongs to, so another tab adopts
     * the mode and the context together instead of learning them from two races.
     */
    private void announce(String owner, ContextState state) {
        if (events == null || state.context() == null || state.rev() == 0) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rev", state.rev());
        data.put("user", owner);
        data.put("world", state.context().world());
        data.put("marketLane", state.context().marketLane());
        data.put("generation", state.context().generation());
        if (state.transition() != null) data.put("cleared", state.transition().cleared());
        events.publish("workspace.updated", data);
    }

    // ---------------------------------------------------------------------------------------
    // Raw blob primitives. Package-private on purpose: the context API above is the only
    // supported writer, so no caller outside this package can store an unversioned blob again.
    // ---------------------------------------------------------------------------------------

    Optional<Workspace> get(String userId) {
        List<Workspace> rows = db.query(
                "SELECT state::text s, rev, updated_at::text ua FROM workspace WHERE user_id=?",
                r -> new Workspace(r.str("s"), r.lng("rev"), r.str("ua")), key(userId));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    long put(String userId, String stateJson) {
        if (stateJson == null || stateJson.isBlank()) throw new IllegalArgumentException("state required");
        if (stateJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STATE_BYTES) {
            throw new IllegalArgumentException("workspace too large (max " + (MAX_STATE_BYTES / 1024) + "KB) — "
                    + "persist declarations and ids, not result payloads");
        }
        String k = key(userId);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        long rev = db.tx(c -> {
            OwnerScope.ensure(c, k);
            Db.execOn(c, "INSERT INTO workspace (user_id, state, rev, updated_at) VALUES (?, ?::jsonb, 1, ?) "
                  + "ON CONFLICT (user_id) DO UPDATE SET state=excluded.state, rev=workspace.rev+1, "
                  + "updated_at=excluded.updated_at", k, stateJson, now);
            return Db.queryOn(c, "SELECT rev FROM workspace WHERE user_id=?",
                    r -> r.lng("rev"), k).getFirst();
        });
        if (events != null) events.publish("workspace.updated", Map.of("rev", rev, "user", k));
        return rev;
    }
}
