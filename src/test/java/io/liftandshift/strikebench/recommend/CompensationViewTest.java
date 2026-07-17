package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.CapitalProfile;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.eval.StrategyEvaluation;
import io.liftandshift.strikebench.eval.StrategySpec;
import io.liftandshift.strikebench.eval.VolatilityProfile;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.NewsItem;
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

/** Earnings proximity is a named, evidence-bearing part of the beside-not-instead ranking. */
class CompensationViewTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));
    private Db db;

    @AfterEach void closeDb() { if (db != null) db.close(); }

    @Test
    void eventWindowChangesOnlyItsNamedComponentAndTheCompensationOrder() {
        EvaluationService evaluations = service(Map.of("AAPL", List.of(
                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-01-31"),
                LocalDate.parse("2025-11-02"))));
        StrategyEvaluation outsideWindow = evaluation("outside", "2026-07-17");
        StrategyEvaluation insideWindow = evaluation("inside", "2026-08-21");

        List<CompensationView.CompensationEntry> result = CompensationView.compute(
                List.of(insideWindow, outsideWindow), evaluations, null);

        assertThat(result).extracting(CompensationView.CompensationEntry::evaluationId)
                .containsExactly("outside", "inside");
        CompensationView.CompensationComponent outside = component(result.getFirst(), "Earnings proximity");
        CompensationView.CompensationComponent inside = component(result.getLast(), "Earnings proximity");
        assertThat(outside.weight()).isEqualTo(0.10);
        assertThat(outside.value()).isEqualTo(1.0);
        assertThat(outside.note()).contains("outside this package's life").contains("no event-window penalty");
        assertThat(inside.value()).isZero();
        assertThat(inside.note()).contains("earnings ESTIMATED near 2026-07-30")
                .contains("score reduced for event-gap exposure");
        assertThat(result.getFirst().score() - result.getLast().score()).isEqualTo(10.0);
    }

    @Test
    void unavailableEventEvidenceIsNeutralAndDisclosedInTheReceipt() {
        EvaluationService evaluations = service(Map.of());

        CompensationView.CompensationEntry entry = CompensationView.compute(
                List.of(evaluation("unknown", "2026-08-21")), evaluations, null).getFirst();
        CompensationView.CompensationComponent event = component(entry, "Earnings proximity");

        assertThat(event.weight()).isEqualTo(0.10);
        assertThat(event.value()).isEqualTo(0.5);
        assertThat(event.note()).contains("unavailable")
                .contains("treated as neutral")
                .contains("not as no event");
        assertThat(CompensationView.BASIS).contains("earnings proximity 10%")
                .contains("Missing evidence is neutral");
    }

    private EvaluationService service(Map<String, List<LocalDate>> reports) {
        db = TestDb.fresh();
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        NewsFilingsProvider filings = new NewsFilingsProvider() {
            @Override public String name() { return "fixture"; }

            @Override public List<NewsItem> news(String symbol) {
                return reports.getOrDefault(symbol, List.of()).stream().map(date ->
                        new NewsItem(symbol, "10-Q quarterly report", "SEC EDGAR",
                                "https://sec.test/filing/" + symbol + "/" + date,
                                date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())).toList();
            }
        };
        MarketDataService market = new MarketDataService(
                List.of(fixture), List.of(filings), List.of(fixture));
        return new EvaluationService(market, db, CLOCK, new EventService(market, CLOCK));
    }

    private static CompensationView.CompensationComponent component(
            CompensationView.CompensationEntry entry, String name) {
        return entry.components().stream().filter(value -> name.equals(value.name())).findFirst().orElseThrow();
    }

    private static StrategyEvaluation evaluation(String id, String expiration) {
        Candidate candidate = new Candidate("CASH_SECURED_PUT", "Cash-secured put",
                "acquisition_income", "SELL 240P", List.of(
                        new LegView("SELL", "PUT", "240", expiration, 1, "3.50", 100, "OPEN")),
                1, 35_000L, 35_000L, 2_365_000L, List.of(), 0.60, 1_800L,
                0.70, "DELAYED", List.of(), 0.6, "Paid to bid", "Keep premium",
                "Assigned in a selloff", "Crash through strike", "You collect premium",
                "INCOME", List.of("INCOME", "ACQUIRE"), 0.35, 12.0,
                null, null, false, null, null);
        CapitalProfile capital = new CapitalProfile(2_365_000L, 2_365_000L,
                1.48, 12.0, 45, "cash collateral", "IF repeatable every 45 days");
        VolatilityProfile volatility = new VolatilityProfile(
                0.30, 60.0, 60.0, 0.25, 0.05, 0.10, 60, "test evidence");
        return new StrategyEvaluation(id,
                new StrategySpec("AAPL", "CASH_SECURED_PUT", "INCOME", "month",
                        null, "balanced", "decision"),
                candidate, capital, volatility, null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
