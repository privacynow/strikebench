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
import io.liftandshift.strikebench.support.TestPrices;

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
    void unavailableNewsCatalystEvidenceIsNeutralAndDisclosedInTheReceipt() {
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

    @Test
    void collateralYieldAndDefinedRiskPeriodPremiumRemainDifferentTypedFacts() {
        EvaluationService evaluations = service(Map.of());
        CompensationView.CompensationEntry collateral = CompensationView.compute(
                List.of(evaluation("collateral", "2026-08-21")), evaluations, null).getFirst();
        CompensationView.CompensationEntry definedRisk = CompensationView.compute(
                List.of(definedRiskEvaluation("spread", "2026-08-21")), evaluations, null).getFirst();

        assertThat(collateral.premium().kind())
                .isEqualTo(CompensationView.PremiumMetricKind.COLLATERAL_OPENING_PREMIUM_RATE);
        assertThat(collateral.premium().denominatorCents()).isEqualTo(2_400_000L);
        assertThat(collateral.premium().annualizedPct()).isEqualTo(12.0);
        assertThat(component(collateral, "Collateral premium rate").note())
                .contains("$350 premium").contains("$24,000 collateral");

        assertThat(definedRisk.premium().kind())
                .isEqualTo(CompensationView.PremiumMetricKind.DEFINED_RISK_PERIOD_PREMIUM);
        assertThat(definedRisk.premium().annualizedPct()).isNull();
        assertThat(component(definedRisk, "Defined-risk period premium").note())
                .contains("not an annualized rate or expected return");
        assertThat(definedRisk.components())
                .noneMatch(component -> component.name().equals("Collateral premium rate"));
    }

    /**
     * An Income package without a price cannot publish a compensation metric, but it must remain
     * visible as an explicitly unavailable receipt rather than disappearing or being scored as
     * zero premium.
     */
    @Test
    void anUnpricedIncomePackagePublishesAnUnavailableReceiptWithoutInventingAMetric() {
        EvaluationService evaluations = service(Map.of());
        StrategyEvaluation priced = evaluation("priced", "2026-08-21");
        StrategyEvaluation unpriced = unpricedEvaluation("unpriced", "2026-08-21");

        List<CompensationView.CompensationEntry> result = CompensationView.compute(
                List.of(unpriced, priced), evaluations, null);

        assertThat(result).extracting(CompensationView.CompensationEntry::evaluationId)
                .containsExactly("priced", "unpriced");
        CompensationView.CompensationEntry unavailable = result.getLast();
        assertThat(unavailable.status()).isEqualTo(CompensationView.CompensationStatus.UNAVAILABLE);
        assertThat(unavailable.score()).isNull();
        assertThat(unavailable.premium()).isNull();
        assertThat(unavailable.components()).isEmpty();
        assertThat(unavailable.basis()).contains("lacks the price or opening-fee inputs");
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
        return evaluation(id, expiration, TestPrices.withFees(1, 35_000L, 35_000L, 0L));
    }

    /** The same package, refused before it could be priced: the §7.2 receipt states no amounts. */
    private static StrategyEvaluation unpricedEvaluation(String id, String expiration) {
        return evaluation(id, expiration,
                io.liftandshift.strikebench.paper.PackagePriceReceipt.unavailable(1,
                        io.liftandshift.strikebench.paper.PackagePriceReceipt.FeeSide.OPENING,
                        "No market or model mark for the 240 put."));
    }

    private static StrategyEvaluation definedRiskEvaluation(String id, String expiration) {
        var price = TestPrices.withFees(1, 10_000L, 10_000L, 0L);
        Candidate candidate = new Candidate("CREDIT_PUT_SPREAD", "Bull put (credit) spread",
                "vertical_credit", "SELL 240P / BUY 235P", List.of(
                        new LegView("SELL", "PUT", "240", expiration, 1, "2.00", 100, "OPEN"),
                        new LegView("BUY", "PUT", "235", expiration, 1, "1.00", 100, "OPEN")),
                1, price, 10_000L, 40_000L, List.of(),
                0.70, "DELAYED", List.of(), 0.6, "Premium with a capped floor",
                "Keep premium", "Lose the width", "Break the short strike", "Defined risk",
                "INCOME", List.of("INCOME"), 0.30, null,
                null, null, false, null, null,
                io.liftandshift.strikebench.support.TestMarketRiskReceipts.receipt(
                        price, 0.60, -1_000L));
        CapitalProfile capital = new CapitalProfile(40_000L, 40_000L,
                25.0, 200.0, 45, "defined-risk test", "IF repeatable every 45 days");
        VolatilityProfile volatility = new VolatilityProfile(
                0.30, 60.0, 60.0, 0.25, 0.05, 0.10, 60, "test evidence");
        return new StrategyEvaluation(id,
                new StrategySpec("AAPL", "CREDIT_PUT_SPREAD", "INCOME", "month",
                        null, "balanced", "decision"),
                candidate, capital, volatility, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static StrategyEvaluation evaluation(String id, String expiration,
            io.liftandshift.strikebench.paper.PackagePriceReceipt price) {
        Candidate candidate = new Candidate("CASH_SECURED_PUT", "Cash-secured put",
                "acquisition_income", "SELL 240P", List.of(
                        new LegView("SELL", "PUT", "240", expiration, 1, "3.50", 100, "OPEN")),
                1, price, 35_000L, 2_365_000L, List.of(),
                0.70, "DELAYED", List.of(), 0.6, "Paid to bid", "Keep premium",
                "Assigned in a selloff", "Crash through strike", "You collect premium",
                "INCOME", List.of("INCOME", "ACQUIRE"), 0.35, 12.0,
                null, null, false, null, null,
                io.liftandshift.strikebench.support.TestMarketRiskReceipts.receipt(
                        price, 0.60, 1_800L));
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
