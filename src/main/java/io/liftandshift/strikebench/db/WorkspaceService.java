package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.util.EventBus;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-user workspace persistence. The blob is CLIENT-AUTHORITATIVE: the browser owns the
 * shape (draft forms, working idea, route); the server stores it verbatim, versions it
 * (rev increments on every write, last-write-wins), and announces new revisions on the
 * event bus so other tabs of the same user can adopt them.
 */
public final class WorkspaceService {

    /** Hard cap on the stored blob — the workspace is forms and ids, never result payloads. */
    public static final int MAX_STATE_BYTES = 128 * 1024;

    private final Db db;
    private final Clock clock;
    private EventBus events; // optional

    public WorkspaceService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public void setEvents(EventBus events) { this.events = events; }

    public record Workspace(String stateJson, long rev, String updatedAt) {}

    /** Anonymous (auth off) sessions share the single local workspace. */
    private static String key(String userId) { return userId == null || userId.isBlank() ? "local" : userId; }

    public Optional<Workspace> get(String userId) {
        List<Workspace> rows = db.query(
                "SELECT state::text s, rev, updated_at::text ua FROM workspace WHERE user_id=?",
                r -> new Workspace(r.str("s"), r.lng("rev"), r.str("ua")), key(userId));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Upserts the blob; returns the new revision. Publishes workspace.updated {rev}. */
    public long put(String userId, String stateJson) {
        if (stateJson == null || stateJson.isBlank()) throw new IllegalArgumentException("state required");
        if (stateJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STATE_BYTES) {
            throw new IllegalArgumentException("workspace too large (max " + (MAX_STATE_BYTES / 1024) + "KB) — "
                    + "persist forms and ids, not result payloads");
        }
        String k = key(userId);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        // Db.prep uses RETURN_GENERATED_KEYS, which PgJDBC can't combine with INSERT..RETURNING
        // through executeQuery — so upsert and read the new rev atomically in one transaction.
        long rev = db.tx(c -> {
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
