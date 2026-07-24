package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.strategy.StrategyFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #12-backend: {@link Candidate#optionNetPremiumCents()} exposes the option-legs-only opening net,
 * reused from the engine's already-computed value, distinct from the stock-inclusive package net.
 */
class CandidateTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"),
            ZoneId.of("America/New_York"));
    private static final long BP = 10_000_000L;

    private RecommendationEngine engine;

    @BeforeEach
    void setUp() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService market = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        engine = new RecommendationEngine(market, CLOCK);
    }

    private static RecommendationEngine.Request incomeReq() {
        return new RecommendationEngine.Request("AAPL", null, "month", "balanced", null, null, null, null,
                true, false, "income", null, null);
    }

    private static RecommendationEngine.Request directionalReq() {
        return new RecommendationEngine.Request("AAPL", "bullish", "month", "balanced", null, null, null, null,
                true, false, null, null, null);
    }

    @Test
    void optionOnlyNetEqualsPackageNetForStructuresWithoutAStockLeg() {
        RecommendationEngine.Result result = engine.recommend(directionalReq(), BP);
        assertThat(result.candidates()).isNotEmpty();
        result.candidates().stream()
                .filter(c -> c.legs().stream().noneMatch(leg -> leg.type().equals("STOCK")))
                .forEach(c -> assertThat(c.optionNetPremiumCents())
                        .as("no stock leg => option net IS the package net (strategy=%s)", c.strategy())
                        .isEqualTo(c.entryNetPremiumCents()));
    }

    @Test
    void buyWriteExposesPositiveOptionCreditWhilePackageNetStaysStockInclusiveNegative() {
        RecommendationEngine.Result result = engine.recommend(incomeReq(), BP);
        Candidate buyWrite = result.candidates().stream()
                .filter(c -> c.strategy().equals("COVERED_CALL"))
                .filter(c -> c.legs().stream().anyMatch(leg -> leg.type().equals("STOCK")))
                .findFirst().orElseThrow();

        // The full stock-plus-options package costs cash (buying 100 shares dwarfs the call credit).
        assertThat(buyWrite.entryNetPremiumCents()).isNegative();
        // But the option legs alone COLLECT premium — that is what optionNetPremiumCents reports.
        assertThat(buyWrite.optionNetPremiumCents()).isPositive();
        assertThat(buyWrite.optionNetPremiumCents()).isGreaterThan(buyWrite.entryNetPremiumCents());
        assertThat(StrategyFamily.valueOf(buyWrite.strategy()).needsStock()).isTrue();
    }
}
