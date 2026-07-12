package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class UnderlyingCsvIngestTest {
    private Db db;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC);

    @AfterEach void close() { if (db != null) db.close(); }

    @Test
    void importsAdjustedOhlcvCoherentlyAndRecordsCursor() throws Exception {
        db = TestDb.fresh();
        String csv = "Date,Open,High,Low,Close,Adj Close,Volume\n"
                + "2026-07-09,100,110,90,100,50,1000\n"
                + "2026-07-10,102,112,92,102,51,1200\n";
        var state = new DataSyncState(db, clock);
        var result = UnderlyingCsvIngest.run(stream(csv), "prices.csv", "AAPL", "Broker export",
                UnderlyingCsvIngest.Basis.AUTO, db, state, clock, null);
        assertThat(result.rowsWritten()).isEqualTo(2);
        assertThat(result.quarantined()).isZero();
        assertThat(result.barBasis()).isEqualTo("OHLCV");
        Object[] row = db.query("SELECT open,high,low,close,adjusted,bar_kind,quality_rank FROM underlying_bar "
                + "WHERE symbol='AAPL' AND d='2026-07-09'", r -> new Object[]{r.bd("open"), r.bd("high"),
                r.bd("low"), r.bd("close"), r.bool("adjusted"), r.str("bar_kind"), r.intv("quality_rank")}).getFirst();
        assertThat((java.math.BigDecimal) row[0]).isEqualByComparingTo("50");
        assertThat((java.math.BigDecimal) row[1]).isEqualByComparingTo("55");
        assertThat((java.math.BigDecimal) row[2]).isEqualByComparingTo("45");
        assertThat((java.math.BigDecimal) row[3]).isEqualByComparingTo("50");
        assertThat(row[4]).isEqualTo(true);
        assertThat(row[5]).isEqualTo("OHLCV");
        assertThat(row[6]).isEqualTo(80);
        assertThat(state.cursors(null)).singleElement().satisfies(c -> {
            assertThat(c.source()).isEqualTo("user_csv");
            assertThat(c.status()).isEqualTo("COMPLETE");
        });
    }

    @Test
    void closeOnlyHistoryStaysExplicitAndBadRowsAreQuarantined() throws Exception {
        db = TestDb.fresh();
        String csv = "Date,Close\n"
                + "2026-07-08,100\n"
                + "2026-07-09,-1\n"
                + "2026-07-09,101\n"
                + "2026-07-10,102\n";
        var state = new DataSyncState(db, clock);
        var result = UnderlyingCsvIngest.run(stream(csv), "closes.csv", "QQQ", "My closes",
                UnderlyingCsvIngest.Basis.RAW, db, state, clock, null);
        assertThat(result.rowsWritten()).isEqualTo(3);
        assertThat(result.quarantined()).isEqualTo(1);
        assertThat(result.barBasis()).isEqualTo("CLOSE_ONLY");
        assertThat(state.quarantineSummary(null).total()).isEqualTo(1);
        var series = new StoredCandleStore(db).candles("QQQ", java.time.LocalDate.parse("2026-07-08"),
                java.time.LocalDate.parse("2026-07-10"), DatasetService.OBSERVED).orElseThrow();
        assertThat(series.barBasis()).isEqualTo("CLOSE_ONLY");
        assertThat(series.candles()).hasSize(3);
    }

    @Test
    void conflictingDuplicateDateGetsNoArbitraryWinner() throws Exception {
        db = TestDb.fresh();
        String csv = "Date,Close\n2026-07-10,100\n2026-07-10,101\n2026-07-10,102\n";
        var result = UnderlyingCsvIngest.run(stream(csv), "dup.csv", "SPY", "dupes",
                UnderlyingCsvIngest.Basis.RAW, db, new DataSyncState(db, clock), clock, null);
        assertThat(result.rowsWritten()).isZero();
        assertThat(result.quarantined()).isEqualTo(2);
        assertThat(db.query("SELECT count(*) c FROM underlying_bar WHERE symbol='SPY'", r -> r.lng("c")).getFirst()).isZero();
    }

    @Test
    void explicitAdjustedBasisWithoutAdjustedColumnIsAuditable() throws Exception {
        db = TestDb.fresh();
        String csv = "Date,Close\n2026-07-10,100\n";
        var result = UnderlyingCsvIngest.run(stream(csv), "adjusted.csv", "AAPL", "Adjusted export",
                UnderlyingCsvIngest.Basis.ADJUSTED, db, new DataSyncState(db, clock), clock, null);

        assertThat(result.source()).endsWith(":adjusted-declared");
        assertThat(result.note()).contains("user-declared adjusted");
        assertThat(db.query("SELECT adjusted FROM underlying_bar WHERE symbol='AAPL'",
                r -> r.bool("adjusted")).getFirst()).isTrue();
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
