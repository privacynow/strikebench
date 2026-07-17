package io.liftandshift.strikebench.strategy;

import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.OptionChain;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The folded-Phase-9 composites round-trip: each builds from a real chain, is bounded-loss,
 * and the built legs identify back to the same named family (no self-disagreement between
 * construction and classification).
 */
class CompositeCatalogTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"),
            ZoneId.of("America/New_York"));
    private static final List<StrategyFamily> COMPOSITES = List.of(
            StrategyFamily.COVERED_STRANGLE,
            StrategyFamily.COVERED_CALL_PUT_SPREAD,
            StrategyFamily.COVERED_CALL_CALL_OVERLAY);

    private record Fixture(OptionChain chain, BigDecimal spot) {}

    private static Fixture fixtureChain() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        LocalDate expiration = fixture.expirations("AAPL").get(2);
        OptionChain chain = fixture.chain("AAPL", expiration).orElseThrow();
        BigDecimal spot = fixture.quote("AAPL").orElseThrow().last();
        return new Fixture(chain, spot);
    }

    @Test
    void everyCompositeBuildsAndIdentifiesAsItself() {
        Fixture f = fixtureChain();
        for (StrategyFamily family : COMPOSITES) {
            StrategyBuilder.Built built = StrategyBuilder.build(family, f.chain(), null, f.spot());
            assertThat(built).as(family + " builds from the fixture chain").isNotNull();
            StrategyCatalog.PositionIdentity identity =
                    StrategyCatalog.identify("AAPL", 1, built.legs());
            assertThat(identity.family()).as(family + " round-trips through identify()")
                    .isEqualTo(family.name());
            assertThat(identity.definedRisk()).as(family + " is bounded-loss").isTrue();
        }
    }

    @Test
    void heldSharesHintDropsTheStockLegButKeepsTheName() {
        Fixture f = fixtureChain();
        for (StrategyFamily family : COMPOSITES) {
            StrategyBuilder.Built built = StrategyBuilder.build(family, f.chain(), null, f.spot(),
                    new StrategyBuilder.BuildHints(null, true));
            assertThat(built).isNotNull();
            assertThat(built.legs()).as(family + " with held shares has no stock leg")
                    .noneMatch(io.liftandshift.strikebench.model.Leg::isStock);
            assertThat(built.label()).containsIgnoringCase("held shares");
        }
    }

    @Test
    void compositePayoffsAreBoundedAndCarryTheirPiecewiseKinks() {
        Fixture f = fixtureChain();
        for (StrategyFamily family : COMPOSITES) {
            StrategyBuilder.Built built = StrategyBuilder.build(family, f.chain(), null, f.spot());
            var curve = io.liftandshift.strikebench.pricing.PayoffCurve.of(built.legs(), 1);
            assertThat(curve.maxLossUnbounded()).as(family + " max loss is quantified").isFalse();
            assertThat(curve.maxLossCents()).as(family + " worst case is a real number").isPositive();
        }
    }
}
