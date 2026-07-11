package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DataSyncStateTest {
    private Db db;
    @AfterEach void close() { if (db != null) db.close(); }

    @Test
    void importingOlderHistoryNeverMovesCursorBackward() {
        db = TestDb.fresh();
        var state = new DataSyncState(db, Clock.systemUTC());
        state.succeeded(null, "user_csv", "AAPL", LocalDate.parse("2026-07-01"),
                LocalDate.parse("2026-07-10"), LocalDate.parse("2026-07-10"), 8, true, "newer");
        state.succeeded(null, "user_csv", "AAPL", LocalDate.parse("2020-01-01"),
                LocalDate.parse("2020-12-31"), LocalDate.parse("2020-12-31"), 250, true, "older");
        assertThat(state.cursors(null).getFirst().lastSuccessDate()).isEqualTo(LocalDate.parse("2026-07-10"));
        assertThat(state.cursors(null).getFirst().rowsWritten()).isEqualTo(258);
    }
}
