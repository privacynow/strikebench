package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.sim.PathPosition;
import io.liftandshift.strikebench.util.DataUnavailableException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutcomeControllerContractTest {

    @Test void missingOrUnusableOutcomeQuoteIsADataGap() {
        assertThatThrownBy(() -> OutcomeController.requireOutcomeQuote(Optional.empty(), "NVDA"))
                .isInstanceOf(DataUnavailableException.class)
                .hasMessageContaining("No price for NVDA").hasMessageContaining("lane-owned quote");

        Quote unusable = new Quote("NVDA", "NVIDIA", null, null, null, null,
                null, null, null, true, 0L, "test", Freshness.MISSING);
        assertThatThrownBy(() -> OutcomeController.requireOutcomeQuote(Optional.of(unusable), "NVDA"))
                .isInstanceOf(DataUnavailableException.class);

        Quote usable = new Quote("NVDA", "NVIDIA", BigDecimal.valueOf(210.25), null, null, null,
                null, null, null, true, 0L, "test", Freshness.REALTIME);
        assertThat(OutcomeController.requireOutcomeQuote(Optional.of(usable), "NVDA")).isSameAs(usable);
    }

    @Test void compareExactExpirationsMustAlignAndParseBeforePricing() {
        LocalDate today = LocalDate.parse("2026-07-15");
        PathPosition spread = new PathPosition(today, List.of(
                Leg.option(LegAction.BUY, OptionType.CALL, BigDecimal.valueOf(210),
                        LocalDate.parse("2026-08-21"), 1, BigDecimal.ZERO),
                Leg.option(LegAction.SELL, OptionType.CALL, BigDecimal.valueOf(220),
                        LocalDate.parse("2026-08-21"), 1, BigDecimal.ZERO)));

        OutcomeController.validateContractExpirations(spread,
                List.of("2026-08-21", "2026-08-21"));
        OutcomeController.validateContractExpirations(spread, null);

        assertThatThrownBy(() -> OutcomeController.validateContractExpirations(
                spread, List.of("2026-08-21")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("align with the legs");
        assertThatThrownBy(() -> OutcomeController.validateContractExpirations(
                spread, List.of("2026-08-21", "not-a-date")))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }
}
