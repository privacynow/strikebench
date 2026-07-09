package io.liftandshift.strikebench.research;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The research-lab notebook: per-user saved analyses, full CRUD + isolation. */
class NotebookServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("UTC"));

    private Db db;

    @AfterEach void close() { if (db != null) db.close(); }

    @Test void createsUpdatesListsAndDeletes() {
        db = TestDb.fresh();
        NotebookService nb = new NotebookService(db, CLOCK);

        var note = nb.create(null, "IV rank study", "SPY put spreads at high IV rank", "vol,income");
        assertThat(note.id()).startsWith("note_");
        assertThat(note.title()).isEqualTo("IV rank study");

        assertThat(nb.list(null)).hasSize(1);
        assertThat(nb.get(null, note.id()).body()).contains("SPY put spreads");

        var updated = nb.update(null, note.id(), null, "Revised: only when IV rank > 50", "vol");
        assertThat(updated.body()).contains("Revised");
        assertThat(updated.title()).isEqualTo("IV rank study"); // COALESCE keeps the old title

        nb.delete(null, note.id());
        assertThat(nb.list(null)).isEmpty();
        assertThatThrownBy(() -> nb.get(null, note.id())).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test void notesAreIsolatedPerUser() {
        db = TestDb.fresh();
        db.exec("INSERT INTO users(id,email,provider,subject,name,created_at,updated_at) VALUES "
                + "('google:a','a@x.com','google','a','A','t','t')");
        NotebookService nb = new NotebookService(db, CLOCK);

        var mine = nb.create("google:a", "Private idea", "body", null);
        assertThat(nb.list(null)).isEmpty();                    // the local user sees nothing
        assertThat(nb.list("google:a")).hasSize(1);
        // Another owner cannot fetch it.
        assertThatThrownBy(() -> nb.get(null, mine.id())).isInstanceOf(java.util.NoSuchElementException.class);
    }
}
