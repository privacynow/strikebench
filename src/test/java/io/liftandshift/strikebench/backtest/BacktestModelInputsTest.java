package io.liftandshift.strikebench.backtest;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestModelInputsTest {

    @Test void resolvesAndDisclosesTheLaneOwnedRate() {
        RatesProvider rates = new RatesProvider() {
            @Override public String name() { return "test-curve"; }
            @Override public OptionalDouble riskFreeRate(int days) { return OptionalDouble.of(0.071); }
        };
        MarketDataService market = new MarketDataService(List.of(), List.of(), List.of(rates));

        BacktestModelInputs inputs = BacktestModelInputs.resolve(market, 45, null);

        assertThat(inputs.annualRate()).isEqualTo(0.071);
        assertThat(inputs.rateEvidence().source()).isEqualTo("test-curve");
        assertThat(inputs.disclosure())
                .containsEntry("annualRate", 0.071)
                .containsEntry("rateSource", "test-curve")
                .containsEntry("rateConvention",
                        "current lane-owned rate held constant across replay dates; not a historical yield curve");
    }

    @Test void modeledReplayActuallyConsumesTheSuppliedRate() {
        MarketDataService market = new MarketDataService(List.of(), List.of(), List.of());
        HistoricalReplayKernel replay = new HistoricalReplayKernel(market, null);
        LocalDate asOf = LocalDate.parse("2026-01-02");
        Leg call = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"),
                asOf.plusDays(90), 1, BigDecimal.ZERO);

        long zeroRate = replay.valueCents("TEST", List.of(call), 1, 100, 0.25, 0.0, asOf,
                new AnalysisContext(null, "scenario"), HistoricalReplayKernel.PriceIntent.MARK,
                false, new HistoricalReplayKernel.Evidence());
        long highRate = replay.valueCents("TEST", List.of(call), 1, 100, 0.25, 0.08, asOf,
                new AnalysisContext(null, "scenario"), HistoricalReplayKernel.PriceIntent.MARK,
                false, new HistoricalReplayKernel.Evidence());

        assertThat(highRate).isGreaterThan(zeroRate);
    }

    @Test void modeledReplayUsesTheContractDeliverableMultiplier() {
        MarketDataService market = new MarketDataService(List.of(), List.of(), List.of());
        HistoricalReplayKernel replay = new HistoricalReplayKernel(market, null);
        LocalDate asOf = LocalDate.parse("2026-01-02");
        Leg standard = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"),
                asOf, 1, BigDecimal.ZERO, 100);
        Leg adjusted = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"),
                asOf, 1, BigDecimal.ZERO, 10);

        long standardValue = replay.intrinsicValueCents(List.of(standard), 1, new BigDecimal("110"));
        long adjustedValue = replay.intrinsicValueCents(List.of(adjusted), 1, new BigDecimal("110"));

        assertThat(standardValue).isEqualTo(100_000);
        assertThat(adjustedValue).isEqualTo(10_000);
    }
}
