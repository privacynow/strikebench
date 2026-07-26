package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        var cursor = state.cursors(null).getFirst();
        assertThat(cursor.lastSuccessDate()).isEqualTo(LocalDate.parse("2026-07-10"));
        assertThat(cursor.requestedFrom()).isEqualTo(LocalDate.parse("2020-01-01"));
        assertThat(cursor.requestedTo()).isEqualTo(LocalDate.parse("2026-07-10"));
        assertThat(cursor.rowsWritten()).isEqualTo(250);
    }

    @Test
    void quarantineDiagnosticsAreOwnerScoped() {
        db = TestDb.fresh();
        var state = new DataSyncState(db, Clock.systemUTC());
        state.quarantine("alice@example.com", "job-a", "csv", "AAPL", "row 2", "bad close", "...");
        state.quarantine("bob@example.com", "job-b", "csv", "QQQ", "row 3", "bad date", "...");

        assertThat(state.quarantineSummary("alice@example.com").total()).isEqualTo(1);
        assertThat(state.quarantineSummary("alice@example.com").reasons().getFirst().reason()).isEqualTo("bad close");
        assertThat(state.quarantineSummary("bob@example.com").total()).isEqualTo(1);
        assertThat(state.quarantineSummary(null).total()).isZero();
    }

    @Test
    void coverageHashIsOrderStableAndChangesWithCoverageConfiguration() {
        String base = DataSyncState.coverageHash("yahoo", List.of("AAPL", "QQQ"), 2);
        assertThat(DataSyncState.coverageHash("YAHOO", List.of("qqq", "aapl", "AAPL"), 2))
                .isEqualTo(base);
        assertThat(DataSyncState.coverageHash("yahoo", List.of("AAPL", "QQQ", "AMD"), 2))
                .isNotEqualTo(base);
        assertThat(DataSyncState.coverageHash("yahoo", List.of("AAPL", "QQQ"), 3))
                .isNotEqualTo(base);
        assertThat(DataSyncState.coverageHash("stooq", List.of("AAPL", "QQQ"), 2))
                .isNotEqualTo(base);
    }

    @Test
    void persistedLegacyAliasesAreRewrittenToOneCanonicalScheduleIdentity() {
        db = TestDb.fresh();
        var state = new DataSyncState(db, Clock.systemUTC());
        state.saveSchedule("legacy-owner", true, "yahoo", List.of("MSFT"), 5);
        db.exec("UPDATE data_sync_schedule SET symbols='brk-b,msft' WHERE user_id='legacy-owner'");

        DataSyncState.Schedule schedule = state.schedule("legacy-owner");

        assertThat(schedule.enabled()).isTrue();
        assertThat(schedule.symbols()).containsExactly("BRK.B", "MSFT");
        assertThat(db.query("SELECT symbols FROM data_sync_schedule WHERE user_id='legacy-owner'",
                r -> r.str("symbols"))).containsExactly("BRK.B,MSFT");
        assertThat(schedule.lastStatus()).isEqualTo("CONFIG_CHANGED");
        assertThat(schedule.completedCoverageHash()).isNull();
    }

    @Test
    void oneMalformedPersistedScheduleIsDisabledWithoutHidingHealthySchedules() {
        db = TestDb.fresh();
        var state = new DataSyncState(db, Clock.systemUTC());
        state.saveSchedule("healthy-owner", true, "yahoo", List.of("AAPL"), 5);
        state.saveSchedule("broken-owner", true, "yahoo", List.of("QQQ"), 5);
        db.exec("UPDATE data_sync_schedule SET symbols='QQQ,../AAPL' WHERE user_id='broken-owner'");

        assertThat(state.enabledSchedules())
                .extracting(DataSyncState.Schedule::userId)
                .contains("healthy-owner")
                .doesNotContain("broken-owner");
        DataSyncState.Schedule broken = state.schedule("broken-owner");
        assertThat(broken.enabled()).isFalse();
        assertThat(broken.lastStatus()).contains("INVALID_SYMBOLS").contains("invalid symbol");
        assertThat(db.query("SELECT enabled FROM data_sync_schedule WHERE user_id='broken-owner'",
                r -> r.intv("enabled"))).containsExactly(0);
    }

    @Test
    void scheduleWritesRejectTwoAliasesForTheSameCanonicalSymbol() {
        db = TestDb.fresh();
        var state = new DataSyncState(db, Clock.systemUTC());

        assertThat(state.saveSchedule("dedupe-owner", true, "yahoo",
                List.of("AAPL", " aapl "), 5).symbols()).containsExactly("AAPL");
        assertThatThrownBy(() -> state.saveSchedule("collision-owner", true, "yahoo",
                List.of("BRK.B", "BRK-B"), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical symbol collision")
                .hasMessageContaining("BRK.B");
    }

    @Test
    void quarantinePreservesMalformedSymbolEvidenceInsteadOfRejectingTheEvidence() {
        db = TestDb.fresh();
        var state = new DataSyncState(db, Clock.systemUTC());

        state.quarantine("quarantine-owner", null, "user_csv", "../AAPL",
                "row 7", "invalid symbol", "../AAPL,2026-07-01,200");

        assertThat(db.query("SELECT symbol,reason FROM data_quarantine "
                        + "WHERE user_id='quarantine-owner'",
                r -> List.of(r.str("symbol"), r.str("reason"))))
                .containsExactly(List.of("../AAPL", "invalid symbol"));
    }
}
