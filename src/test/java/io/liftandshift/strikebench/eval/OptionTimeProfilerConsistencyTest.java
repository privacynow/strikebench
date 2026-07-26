package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.market.OptionTime;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.pricing.BlackScholes;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.support.TestPrices;
import io.liftandshift.strikebench.util.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class OptionTimeProfilerConsistencyTest {

    @Test
    void liveZeroDteFractionIsSharedAcrossVolRiskEconomicsAndStance() {
        LocalDate expiry = LocalDate.of(2026, 7, 24);
        OptionTime.Measure time = OptionTime.toExpiry(
                Instant.parse("2026-07-24T19:00:00Z"), expiry);
        Candidate candidate = candidate(expiry);
        EvalContext context = context(time);

        StrategyEvaluation evaluation = new StrategyEvaluator().evaluate(candidate,
                new StrategySpec("AAPL", "LONG_CALL", "DIRECTIONAL", "0DTE",
                        "bullish", "balanced", "test"), context);

        double years = OptionTime.LIVE_0DTE_MODEL_YEARS;
        assertThat(evaluation.volatility().expectedMovePct())
                .isCloseTo(0.30 * Math.sqrt(years), offset(1e-12));
        assertThat(evaluation.risk().scenarios())
                .allMatch(scenario -> scenario.prob() != null);
        assertThat(evaluation.risk().evHistVolCents()).isNotNull();
        assertThat(evaluation.assessment().economics().realizedVolEvAfterCostsCents()).isNotNull();
        assertThat(evaluation.assessment().economics().realisticEvLowAfterCostsCents()).isNotNull();

        double expectedDeltaShares = 100.0 * BlackScholes.delta(true, 100.0, 100.0,
                years, 0.04, 0, 0.30);
        assertThat(evaluation.stance().dollarDeltaCents())
                .isEqualTo(Money.toCents(expectedDeltaShares * 100.0));
        assertThat(evaluation.stance().durationCalendarDays()).isZero();
    }

    private static Candidate candidate(LocalDate expiration) {
        return new Candidate("LONG_CALL", "Long call", "single_long", "BUY 100C",
                List.of(new LegView("BUY", "CALL", "100", expiration.toString(),
                        1, "2.00", 100, "OPEN")),
                1, TestPrices.withFees(1, -20_000L, -20_000L, 65L),
                null, 20_000L, List.of("102.00"), 0.48, -1_000L, 0.9,
                "DELAYED", List.of(), 0.8, "Upside", "Uncapped upside",
                "Premium can expire worthless", "Thesis breaks", "Pay the debit",
                "DIRECTIONAL", List.of("DIRECTIONAL"), null, null, null, null,
                false, null, null);
    }

    private static EvalContext context(OptionTime.Measure time) {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 100; i++) closes.add(95.0 + i * 0.05);
        return new EvalContext("AAPL", 10_000L, LocalDate.of(2026, 7, 24), time,
                0.30, 0.25,
                List.of(0.20, 0.21, 0.22, 0.23, 0.24, 0.25,
                        0.26, 0.27, 0.28, 0.29, 0.30),
                1_000_000L, true, 0.04,
                DataEvidence.of("treasury", Freshness.EOD), null, null, null, closes,
                DataEvidence.of("stored history", Freshness.EOD));
    }
}
