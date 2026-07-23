package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.StoredCandleStore;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.support.ObservedFixtureProvider;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

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

        EvaluationService service = new EvaluationService(null, db, Clock.systemUTC());
        assertThat(service.ivHistory("AAPL")).containsExactly(0.22);
        insertIv("observed", "2026-07-03", 0.31, "observed-csv-2");
        assertThat(service.ivHistory("AAPL")).containsExactly(0.22); // memoized until data changes
        service.invalidateHistoricalData();
        assertThat(service.ivHistory("AAPL")).containsExactly(0.31, 0.22);
    }

    @Test
    void intradaySnapshotsCountAsOneIvHistoryDay() {
        db = TestDb.fresh();
        insertIvAt("2026-07-01T14:00:00Z", 0.20, "morning");
        insertIvAt("2026-07-01T19:00:00Z", 0.30, "afternoon");
        insertIvAt("2026-07-02T19:00:00Z", 0.40, "next-day");

        EvaluationService service = new EvaluationService(null, db, Clock.systemUTC());
        assertThat(service.ivHistory("AAPL")).containsExactly(0.40, 0.25);
    }

    @Test
    void evaluationKeepsSyntheticHistoryModeledBesideObservedOptionPricing() {
        db = TestDb.fresh();
        String dataset = "scenario-aapl";
        db.exec("INSERT INTO dataset(id,name,kind,symbol) VALUES (?,?,?,?)",
                dataset, "AAPL modeled scenario", "SYNTHETIC_PURE", "AAPL");
        LocalDate today = LocalDate.parse("2026-07-08");
        for (int day = 0; day < 12; day++) {
            insertIv("observed", today.minusDays(day).toString(), 0.25 + day * 0.001,
                    "observed-test-feed");
        }
        for (int day = 0; day < 126; day++) {
            LocalDate date = today.minusDays(125).plusDays(day);
            double close = 250.0 + Math.sin(day / 7.0) * 0.75;
            db.exec("""
                    INSERT INTO underlying_bar(symbol,d,open,high,low,close,source,observed,dataset_id)
                    VALUES ('AAPL',?,?,?,?,?,'scenario-generator',0,?)
                    """, date, close, close + 0.25, close - 0.25, close, dataset);
        }

        Clock laneClock = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneOffset.UTC);
        var observed = new ObservedFixtureProvider(laneClock);
        MarketDataService market = new MarketDataService(
                List.<MarketDataProvider>of(observed), List.<NewsFilingsProvider>of(),
                List.<RatesProvider>of(), new StoredCandleStore(db));
        LocalDate expiration = market.expirations("AAPL", null).stream()
                .filter(date -> date.isAfter(today.plusDays(20))).findFirst().orElseThrow();
        Candidate candidate = highPremiumPut(expiration);

        StrategyEvaluation evaluation = new EvaluationService(market, db, laneClock).evaluate(
                "AAPL", "INCOME", "NEUTRAL", "month", "balanced", List.of(candidate),
                10_000_000L, null, false, new AnalysisContext("local", dataset), null, null)
                .getFirst();

        assertThat(evaluation.evidence().perDimension().get("pricing"))
                .isEqualTo(EvidenceLevel.OBSERVED_DELAYED);
        assertThat(evaluation.evidence().perDimension().get("history"))
                .isEqualTo(EvidenceLevel.MODELED);
        assertThat(evaluation.evidence().perDimension().get("volatility"))
                .as("a synthetic dataset must not borrow the observed IV-rank history")
                .isEqualTo(EvidenceLevel.MODELED);
        assertThat(evaluation.evidence().note())
                .contains("daily history is MODELED/NOT_APPLICABLE from synthetic");
        assertThat(evaluation.evidence().claims().get("endorsement").observed()).isFalse();
        assertThat(evaluation.assessment().economics().realizedVolEvAfterCostsCents())
                .isGreaterThan(evaluation.assessment().economics().realisticEvMaterialityCents());
        // Two-axis: synthetic history yields FAVORABLE economics with a modeled evidence badge,
        // which stays non-actionable (never an observed endorsement).
        assertThat(evaluation.assessment().economics().verdict())
                .isEqualTo(EconomicAssessment.Verdict.FAVORABLE);
        assertThat(evaluation.assessment().economics().observedEvidence()).isFalse();
        assertThat(evaluation.assessment().economics().actionableFavorable()).isFalse();
    }

    private static Candidate highPremiumPut(LocalDate expiration) {
        List<LegView> legs = List.of(new LegView(
                "SELL", "PUT", "240", expiration.toString(), 1, "10.00", 100, "OPEN"));
        return new Candidate("CASH_SECURED_PUT", "Cash-secured put", "acquisition_income",
                "SELL 240P", legs, 1, 100_000L, 100_000L, 2_300_000L,
                List.of("230.00"), 0.75, 90_000L, 0.90, "DELAYED", List.of(), 0.8,
                "Income below spot", "Keep the premium", "Assignment below breakeven",
                "A sharp selloff", "Collect premium or acquire shares", "INCOME",
                List.of("INCOME", "ACQUIRE"), 0.20, 30.0, "230.00",
                "Collect premium or acquire at $230", false, null, null);
    }

    private void insertIv(String dataset, String asof, double iv, String source) {
        db.exec("""
                INSERT INTO option_bar(symbol,asof,expiration,strike,opt_type,bid,ask,iv,underlying,
                  source,bid_ask_observed,iv_source,greeks_source,dataset_id)
                VALUES (?,?::date,'2026-08-21'::date,250,'CALL',5.00,5.20,?,251,?,1,'vendor','vendor',?)
                """, "AAPL", asof, iv, source, dataset);
    }

    private void insertIvAt(String asof, double iv, String source) {
        db.exec("""
                INSERT INTO option_bar(symbol,asof,expiration,strike,opt_type,bid,ask,iv,underlying,
                  source,bid_ask_observed,iv_source,greeks_source,dataset_id)
                VALUES (?,?::timestamptz,'2026-08-21'::date,250,'CALL',5.00,5.20,?,251,?,1,'vendor','vendor','observed')
                """, "AAPL", asof, iv, source);
    }
}
