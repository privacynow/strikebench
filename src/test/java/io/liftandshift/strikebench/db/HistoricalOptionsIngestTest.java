package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

/** Bulk ingest of a licensed historical-options CSV -> observed option_bar rows. */
class HistoricalOptionsIngestTest {

    private Db db;

    @AfterEach void close() { if (db != null) db.close(); }

    private HistoricalOptionsIngest.IngestResult ingest(String csv, String source) throws Exception {
        db = TestDb.fresh();
        return HistoricalOptionsIngest.run(new BufferedReader(new StringReader(csv)), source, db);
    }

    private long count(String sql) { return db.query(sql, r -> r.lng("c")).getFirst(); }

    @Test void loadsVendorRowsAsObservedEvidence() throws Exception {
        // Vendor-style header with aliases (ticker, expiry, right, impliedVol, oi, underlyingPrice).
        String csv = String.join("\n",
                "date,ticker,expiry,strike,right,bid,ask,impliedVol,delta,oi,underlyingPrice",
                "2026-06-01,AAPL,2026-07-17,250,C,5.10,5.20,0.31,0.55,1200,251.30",
                "2026-06-01,AAPL,2026-07-17,250,P,3.90,4.00,0.29,-0.45,900,251.30",
                "2026-06-02,AAPL,2026-07-17,250,C,4.80,4.90,0.28,0.53,1300,249.10");

        var r = ingest(csv, "orats");

        assertThat(r.optionRows()).isEqualTo(3);
        assertThat(r.underlyingRows()).isEqualTo(2);   // two distinct (AAPL, date)
        assertThat(r.skipped()).isZero();

        // Rows carry the vendor source and are marked OBSERVED (bid/ask + vendor IV/greeks).
        assertThat(count("SELECT count(*) c FROM option_bar WHERE source='orats' AND bid_ask_observed=1")).isEqualTo(3);
        assertThat(count("SELECT count(*) c FROM option_bar WHERE iv_source='vendor'")).isEqualTo(3);
        assertThat(count("SELECT count(*) c FROM option_bar WHERE greeks_source='vendor'")).isEqualTo(3);

        // Underlying bars recorded with the vendor close.
        var closes = db.query("SELECT close FROM underlying_bar WHERE symbol='AAPL' ORDER BY d",
                row -> row.str("close"));
        assertThat(closes).hasSize(2);

        // The moat pays off: EvaluationService's observed-IV history query now finds vendor ATM IV.
        long ivDays = count("SELECT count(DISTINCT asof) c FROM option_bar "
                + "WHERE symbol='AAPL' AND iv IS NOT NULL AND iv_source='vendor' "
                + "AND underlying IS NOT NULL AND abs(strike - underlying) <= underlying * 0.05");
        assertThat(ivDays).isEqualTo(2);
    }

    @Test void isIdempotentAndSkipsUnparseableRows() throws Exception {
        String csv = String.join("\n",
                "date,symbol,expiration,strike,type,bid,ask",
                "2026-06-01,AAPL,2026-07-17,250,CALL,5.10,5.20",
                "garbage,row,that,cannot,parse,at,all",
                "2026-06-01,AAPL,2026-07-17,255,PUT,2.10,2.20");
        var r = ingest(csv, "vendor");
        assertThat(r.optionRows()).isEqualTo(2);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.problems()).containsExactly("row 3 skipped: invalid or missing field");
        assertThat(r.problems().toString()).doesNotContain("Exception", "DateTimeParse");

        // Re-ingesting the same rows upserts in place — no duplicates.
        HistoricalOptionsIngest.run(new BufferedReader(new StringReader(csv)), "vendor", db);
        assertThat(count("SELECT count(*) c FROM option_bar")).isEqualTo(2);
    }

    @Test void reportsMissingRequiredColumns() throws Exception {
        var r = ingest("date,symbol,bid,ask\n2026-06-01,AAPL,1,2", "vendor");
        assertThat(r.optionRows()).isZero();
        assertThat(r.problems()).anyMatch(p -> p.contains("missing required column"));
    }
}
