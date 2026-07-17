package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regime (folded Phase 10.3) is a FRAMING lens: honest classification from the lane's own
 * candles, warnings that condition how a structure reads against the trend — never a re-rank,
 * never a fabricated regime when history is thin.
 */
class RegimeLensTest {

    private final StrategyEvaluator evaluator = new StrategyEvaluator();

    private static List<Candle> path(double start, double endMultiple, int sessions) {
        List<Candle> out = new ArrayList<>();
        LocalDate day = LocalDate.parse("2026-03-02");
        for (int i = 0; i < sessions; i++) {
            double close = start * Math.pow(endMultiple, (double) i / (sessions - 1));
            BigDecimal px = BigDecimal.valueOf(Math.round(close * 100) / 100.0);
            out.add(new Candle(day.plusDays(i * 7L / 5), px, px, px, px, 1_000_000L, true));
        }
        return out;
    }

    @Test void classifiesTrendsBluntlyAndHonestly() {
        assertThat(RegimeProfiler.profile(path(100, 1.20, 63), null, false, "observed sessions").trend())
                .isEqualTo(RegimeSnapshot.Trend.UP);
        assertThat(RegimeProfiler.profile(path(100, 0.80, 63), null, false, "observed sessions").trend())
                .isEqualTo(RegimeSnapshot.Trend.DOWN);
        assertThat(RegimeProfiler.profile(path(100, 1.03, 63), null, false, "observed sessions").trend())
                .isEqualTo(RegimeSnapshot.Trend.SIDEWAYS);
    }

    @Test void thinHistoryIsUnknownNeverGuessed() {
        RegimeSnapshot thin = RegimeProfiler.profile(path(100, 1.5, 10), null, false, "observed sessions");
        assertThat(thin.trend()).isNull();
        assertThat(thin.basis()).contains("fewer than");
        assertThat(thin.headline()).isEmpty();
    }

    @Test void theHeadlineSpeaksUnitsAndVolRichness() {
        VolatilityProfile vol = new VolatilityProfiler().profile(0.35, 0.22,
                List.of(0.20, 0.25, 0.30, 0.35, 0.40), 30);
        RegimeSnapshot up = RegimeProfiler.profile(path(100, 1.18, 63), vol, false, "observed sessions");
        assertThat(up.headline()).contains("up 18%").contains("63 sessions").contains("rich premium");
    }

    private Candidate cashSecuredPut() {
        List<LegView> legs = List.of(new LegView("SELL", "PUT", "240", "2026-08-21", 1, "3.50", 100, "OPEN"));
        return new Candidate("CASH_SECURED_PUT", "Cash-secured put", "acquisition_income", "SELL 240P",
                legs, 1, 35_000L, 35_000L, 2_365_000L, List.of(), 0.60, 1_800L, 0.70, "DELAYED", List.of(), 0.6,
                "Paid to bid below the market", "Keep the premium", "Assigned in a selloff",
                "A crash through the strike", "You collect premium",
                "ACQUIRE", List.of("INCOME", "ACQUIRE"), 0.35, 5.1, null, null, false, null, null);
    }

    private EvalContext ctx(RegimeSnapshot regime) {
        return new EvalContext("AAPL", 25_200L, LocalDate.parse("2026-07-22"), 30, 0.30, 0.25,
                List.of(0.22, 0.26, 0.30, 0.34, 0.38, 0.29), 10_000_000L, true, 65, 0, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of("treasury",
                        io.liftandshift.strikebench.model.Freshness.EOD), null, null, regime, null);
    }

    @Test void downTrendConditionsShortPutStructuresWithACompensationQuestion() {
        RegimeSnapshot down = RegimeProfiler.profile(path(100, 0.82, 63), null, false, "observed sessions");
        StrategyEvaluation e = evaluator.evaluate(cashSecuredPut(),
                new StrategySpec("AAPL", "CASH_SECURED_PUT", "ACQUIRE", "month", null, "balanced", "decision"),
                ctx(down));
        assertThat(e.explanation().failureModes()).anySatisfy(line -> assertThat(line)
                .contains("down 18% over the last 63 sessions")
                .contains("judge whether it is enough")
                .contains("Trend heuristic"));
    }

    @Test void regimeNeverChangesTheScore() {
        RegimeSnapshot down = RegimeProfiler.profile(path(100, 0.82, 63), null, false, "observed sessions");
        StrategyEvaluation with = evaluator.evaluate(cashSecuredPut(),
                new StrategySpec("AAPL", "CASH_SECURED_PUT", "ACQUIRE", "month", null, "balanced", "decision"),
                ctx(down));
        StrategyEvaluation without = evaluator.evaluate(cashSecuredPut(),
                new StrategySpec("AAPL", "CASH_SECURED_PUT", "ACQUIRE", "month", null, "balanced", "decision"),
                ctx(null));
        assertThat(with.rankScore()).isEqualTo(without.rankScore());
        assertThat(with.decisionScore()).isEqualTo(without.decisionScore());
    }
}
