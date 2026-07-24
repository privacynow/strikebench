package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.NewsItem;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The canonical filing-cadence event evidence must reach the human evaluation receipt. */
class EvaluationEventProximityTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));
    private Db db;

    @AfterEach void closeDb() { if (db != null) db.close(); }

    @Test
    void estimatedEventInsidePackageLifeReachesEvaluationFraming() {
        MarketDataService market = marketWithReports(Map.of("AAPL", List.of(
                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-01-31"),
                LocalDate.parse("2025-11-02"))));
        StrategyEvaluation evaluation = evaluate(market);

        assertThat(evaluation.explanation().failureModes()).anySatisfy(line -> assertThat(line)
                .contains("Event proximity")
                .contains("earnings ESTIMATED near 2026-07-30")
                .contains("SEC filing cadence")
                .contains("framing, not a forecast"));
    }

    @Test
    void missingFilingCadenceIsNamedUnavailableNotMisstatedAsNoEvent() {
        MarketDataService market = marketWithReports(Map.of());
        StrategyEvaluation evaluation = evaluate(market);

        assertThat(evaluation.explanation().assumptions()).anySatisfy(line -> assertThat(line)
                .contains("Event proximity")
                .contains("unavailable")
                .contains("not a no-event claim"));
        assertThat(evaluation.explanation().failureModes())
                .noneSatisfy(line -> assertThat(line).contains("Event proximity"));
    }

    @Test
    void everyExpiryCarriesItsOwnDteAndVolatilityContext() {
        MarketDataService market = marketWithReports(Map.of());
        db = TestDb.fresh();
        EvaluationService service = new EvaluationService(market, db, CLOCK,
                new EventService(market, CLOCK));
        List<LocalDate> expirations = market.expirations("AAPL", null);
        LocalDate near = expirations.getFirst();
        LocalDate far = expirations.get(Math.min(4, expirations.size() - 1));

        List<StrategyEvaluation> evaluated = service.evaluate("AAPL", "INCOME", null, "month",
                "balanced", List.of(premiumCandidate(near.toString()), premiumCandidate(far.toString())),
                10_000_000L, null, false, AnalysisContext.OBSERVED, null, null);

        java.util.Map<String, StrategyEvaluation> byExpiration = evaluated.stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> row.candidate().legs().getFirst().expiration(), row -> row));
        StrategyEvaluation nearEvaluation = byExpiration.get(near.toString());
        StrategyEvaluation farEvaluation = byExpiration.get(far.toString());
        int nearDte = (int) java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.ofInstant(CLOCK.instant(), io.liftandshift.strikebench.market.MarketHours.EASTERN), near);
        int farDte = (int) java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.ofInstant(CLOCK.instant(), io.liftandshift.strikebench.market.MarketHours.EASTERN), far);

        assertThat(nearEvaluation.capital().daysToExpiry()).isEqualTo(nearDte);
        assertThat(farEvaluation.capital().daysToExpiry()).isEqualTo(farDte);
        assertThat(farEvaluation.volatility().expectedMovePct())
                .isGreaterThan(nearEvaluation.volatility().expectedMovePct());
        assertThat(evaluated).allSatisfy(row ->
                assertThat(row.spec().family()).isEqualTo(row.candidate().strategy()));
    }

    private StrategyEvaluation evaluate(MarketDataService market) {
        db = TestDb.fresh();
        EventService events = new EventService(market, CLOCK);
        EvaluationService service = new EvaluationService(market, db, CLOCK, events);
        return service.evaluate("AAPL", "INCOME", null, "month", "balanced",
                List.of(premiumCandidate("2026-08-21")), 10_000_000L, null, false,
                AnalysisContext.OBSERVED, null, null).getFirst();
    }

    private static MarketDataService marketWithReports(Map<String, List<LocalDate>> reports) {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        NewsFilingsProvider filings = new NewsFilingsProvider() {
            // Match the fixture lane so its quotes/history remain explicitly DEMO rather than
            // being rejected as an Observed/fixture blend. The individual items still carry the
            // SEC EDGAR source consumed by EventService.
            @Override public String name() { return "fixture"; }

            @Override public List<NewsItem> news(String symbol) {
                return reports.getOrDefault(symbol, List.of()).stream().map(date ->
                        new NewsItem(symbol, "10-Q quarterly report", "SEC EDGAR",
                                "https://sec.test/filing/" + date,
                                date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())).toList();
            }
        };
        return new MarketDataService(List.of(fixture), List.of(filings), List.of(fixture));
    }

    private static Candidate premiumCandidate(String expiration) {
        List<LegView> legs = List.of(
                new LegView("SELL", "PUT", "240", expiration, 1, "3.50", 100, "OPEN"));
        return new Candidate("CASH_SECURED_PUT", "Cash-secured put", "acquisition_income",
                "SELL 240P", legs, 1, 35_000L, 35_000L, 2_365_000L, List.of(),
                0.60, 1_800L, 0.70, "DELAYED", List.of(), 0.6,
                "Paid to bid below the market", "Keep the premium", "Assigned in a selloff",
                "A crash through the strike", "You collect premium", "INCOME",
                List.of("INCOME", "ACQUIRE"), 0.35, 5.1, null, null,
                false, null, null);
    }
}
