package io.liftandshift.strikebench.strategy;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.DataProvenance;
import io.liftandshift.strikebench.support.ObservedFixtureProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExposureSizerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"),
            ZoneId.of("America/New_York"));

    private static MarketDataService demoMarket() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        return new MarketDataService(List.<MarketDataProvider>of(fixture),
                List.<NewsFilingsProvider>of(fixture), List.<RatesProvider>of(fixture));
    }

    @Test
    void sizesBothSyntheticDirectionsInsideTheBuilderContract() {
        ExposureSizer sizer = new ExposureSizer(demoMarket());
        var bullish = sizer.size(new ExposureSizer.Request("AAPL", 10_000_000L, true), null);
        var bearish = sizer.size(new ExposureSizer.Request("AAPL", 10_000_000L, false), null);

        assertThat(bullish.underlyingCents()).isPositive();
        assertThat(bullish.contracts()).isGreaterThanOrEqualTo(1);
        assertThat(bullish.structure()).contains("Synthetic long");
        assertThat(bullish.deltaExposureCents()).isPositive();
        assertThat(bullish.notes()).anyMatch(n -> n.contains("Delta-1"));
        assertThat(bullish.evidence().provenance()).isEqualTo(DataProvenance.DEMO);
        assertThat(bearish.structure()).contains("Synthetic short");
        assertThat(bearish.deltaExposureCents()).isNegative();
        assertThat(bearish.notes()).anyMatch(n -> n.contains("UNDEFINED RISK"));
    }

    @Test
    void explicitUnknownWorldNeverFallsThroughToObservedPrices() {
        var observed = new ObservedFixtureProvider(CLOCK);
        var market = new MarketDataService(List.<MarketDataProvider>of(observed), List.of(), List.of());
        market.setWorldResolver(id -> java.util.Optional.empty());

        var result = new ExposureSizer(market)
                .size(new ExposureSizer.Request("AAPL", 10_000_000L, true), "missing-world");
        assertThat(result.contracts()).isZero();
        assertThat(result.evidence().provenance()).isEqualTo(DataProvenance.MISSING);
    }

    @Test
    void rejectsAnEmptyExposureInsteadOfInventingAQuantity() {
        assertThatThrownBy(() -> new ExposureSizer(demoMarket())
                .size(new ExposureSizer.Request("AAPL", 0L, true), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
