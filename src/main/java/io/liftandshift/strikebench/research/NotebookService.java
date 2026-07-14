package io.liftandshift.strikebench.research;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.util.Ids;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import io.liftandshift.strikebench.util.ResourceNotFoundException;
import io.liftandshift.strikebench.util.OwnerScope;

/**
 * Research notebook: per-user saved analyses (title + freeform/markdown body + tags),
 * so a user can keep their hypotheses, scan results, and conclusions. Every row is scoped to one
 * canonical user; null callers resolve to the explicit local owner at the boundary.
 */
public final class NotebookService {

    private final Db db;
    private final Clock clock;

    public NotebookService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public record Note(String id, String title, String body, String tags, String createdAt, String updatedAt) {}

    public Note create(String userId, String title, String body, String tags) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        String id = Ids.newId("note");
        String now = Instant.now(clock).toString();
        db.tx(c -> {
            String owner = OwnerScope.ensure(c, userId);
            Db.execOn(c, "INSERT INTO research_note(id,user_id,title,body,tags,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                    id, owner, title.trim(), body == null ? "" : body, tags, now, now);
            return null;
        });
        return get(userId, id);
    }

    public List<Note> list(String userId) {
        return db.query("""
                SELECT id,title,body,tags,created_at,updated_at FROM research_note
                WHERE user_id=?::text
                ORDER BY updated_at DESC LIMIT 200
                """, NotebookService::map, OwnerScope.id(userId));
    }

    public Note get(String userId, String id) {
        var rows = db.query("""
                SELECT id,title,body,tags,created_at,updated_at FROM research_note
                WHERE id=? AND user_id=?::text
                """, NotebookService::map, id, OwnerScope.id(userId));
        if (rows.isEmpty()) throw new ResourceNotFoundException("no such note " + id);
        return rows.getFirst();
    }

    public Note update(String userId, String id, String title, String body, String tags) {
        get(userId, id); // ownership check (404 otherwise)
        String now = Instant.now(clock).toString();
        db.exec("UPDATE research_note SET title=COALESCE(?,title), body=COALESCE(?,body), tags=?, updated_at=? "
                + "WHERE id=? AND user_id=?::text",
                title, body, tags, now, id, OwnerScope.id(userId));
        return get(userId, id);
    }

    public void delete(String userId, String id) {
        get(userId, id); // ownership check
        db.exec("DELETE FROM research_note WHERE id=? AND user_id=?::text", id, OwnerScope.id(userId));
    }

    private static Note map(Db.Row r) {
        return new Note(r.str("id"), r.str("title"), r.str("body"), r.str("tags"),
                r.str("created_at"), r.str("updated_at"));
    }
}
