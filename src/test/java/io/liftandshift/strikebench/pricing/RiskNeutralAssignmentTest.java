package io.liftandshift.strikebench.pricing;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskNeutralAssignmentTest {
    private static final Instant LANE_NOW = Instant.parse("2026-07-08T15:30:00Z");
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 7);

    @Test
    void exactCapturedIvAndLaneClockOwnAssignmentProbability() {
        Leg shortCall = Leg.option(LegAction.SELL, OptionType.CALL,
                new BigDecimal("105"), EXPIRY, 1, BigDecimal.ONE);

        Double lowIv = RiskNeutralAnalyzer.shortSideExpirationItmProbability(
                List.of(shortCall), List.of(0.15), 10_000, LANE_NOW, 0.04);
        Double highIv = RiskNeutralAnalyzer.shortSideExpirationItmProbability(
                List.of(shortCall), List.of(0.60), 10_000, LANE_NOW, 0.04);

        assertThat(lowIv).isNotNull();
        assertThat(highIv).isNotNull().isGreaterThan(lowIv);
    }

    @Test
    void missingIvAndExpiredClockStayUnavailableInsteadOfUsingThirtyPercent() {
        Leg live = Leg.option(LegAction.SELL, OptionType.PUT,
                new BigDecimal("95"), EXPIRY, 1, BigDecimal.ONE);
        Leg expired = Leg.option(LegAction.SELL, OptionType.PUT,
                new BigDecimal("95"), LocalDate.of(2026, 7, 7), 1, BigDecimal.ONE);

        assertThat(RiskNeutralAnalyzer.shortSideExpirationItmProbability(
                List.of(live), java.util.Arrays.asList((Double) null),
                10_000, LANE_NOW, 0.04)).isNull();
        assertThat(RiskNeutralAnalyzer.shortSideExpirationItmProbability(
                List.of(expired), List.of(0.30), 10_000, LANE_NOW, 0.04)).isNull();
    }

    @Test
    void mixedExpirationsStayUnavailableInsteadOfSummingDependentMarginals() {
        Leg nearPut = Leg.option(LegAction.SELL, OptionType.PUT,
                new BigDecimal("95"), EXPIRY, 1, BigDecimal.ONE);
        Leg farCall = Leg.option(LegAction.SELL, OptionType.CALL,
                new BigDecimal("110"), EXPIRY.plusMonths(1), 1, BigDecimal.ONE);

        assertThat(RiskNeutralAnalyzer.shortSideExpirationItmProbability(
                List.of(nearPut, farCall), List.of(0.30, 0.30),
                10_000, LANE_NOW, 0.04))
                .as("one underlying observed at two dates needs a joint path law")
                .isNull();
    }
}
