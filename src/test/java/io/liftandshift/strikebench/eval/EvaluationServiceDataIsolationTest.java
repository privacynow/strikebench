package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationServiceDataIsolationTest {

    private Db db;

    @AfterEach void closeDb() { if (db != null) db.close(); }

    @Test
    void observedIvHistoryIgnoresVendorRowsInAnotherDataset() {
        db = TestDb.fresh();
        db.exec("INSERT INTO dataset(id,name,kind,symbol) VALUES (?,?,?,?)",
                "scenario-aapl", "AAPL scenario", "SYNTHETIC_PURE", "AAPL");
        insertIv("observed", "2026-07-01", 0.22, "observed-csv");
        insertIv("scenario-aapl", "2026-07-02", 0.91, "scenario-csv");

        EvaluationService service = new EvaluationService(null, null, db, Clock.systemUTC());
        assertThat(service.ivHistory("AAPL")).containsExactly(0.22);
        insertIv("observed", "2026-07-03", 0.31, "observed-csv-2");
        assertThat(service.ivHistory("AAPL")).containsExactly(0.22); // memoized until data changes
        service.invalidateHistoricalData();
        assertThat(service.ivHistory("AAPL")).containsExactly(0.31, 0.22);
    }

    private void insertIv(String dataset, String asof, double iv, String source) {
        db.exec("""
                INSERT INTO option_bar(symbol,asof,expiration,strike,opt_type,bid,ask,iv,underlying,
                  source,bid_ask_observed,iv_source,greeks_source,dataset_id)
                VALUES (?,?::date,'2026-08-21'::date,250,'CALL',5.00,5.20,?,251,?,1,'vendor','vendor',?)
                """, "AAPL", asof, iv, source, dataset);
    }
}
