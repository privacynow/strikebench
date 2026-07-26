package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.support.TestPrices;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutcomeCapturedFeePolicyTest {

    @Test
    void frozenEntryUsesItsCapturedFeeWithoutConsultingCurrentConfiguration() {
        AtomicBoolean currentScheduleConsulted = new AtomicBoolean();
        PackagePriceReceipt captured =
                TestPrices.withFeeSchedule(1, 42_000L, 42_000L, 65L, 777L);

        long fees = OutcomeController.resolveOutcomeRoundTripFees(captured, () -> {
            currentScheduleConsulted.set(true);
            return 260L;
        });

        assertThat(fees).isEqualTo(777L);
        assertThat(currentScheduleConsulted).isFalse();
    }

    @Test
    void frozenEntryWithoutCapturedFeeIsRefusedByName() {
        AtomicBoolean currentScheduleConsulted = new AtomicBoolean();
        PackagePriceReceipt withoutFees = TestPrices.optionOnly(42_000L);

        assertThatThrownBy(() -> OutcomeController.resolveOutcomeRoundTripFees(withoutFees, () -> {
            currentScheduleConsulted.set(true);
            return 260L;
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("captured entry")
                .hasMessageContaining("no estimated round-trip commission")
                .hasMessageContaining("cannot be reported");
        assertThat(currentScheduleConsulted).isFalse();
    }

    @Test
    void freshEntryUsesTheCurrentFeeSchedule() {
        AtomicBoolean currentScheduleConsulted = new AtomicBoolean();

        long fees = OutcomeController.resolveOutcomeRoundTripFees(null, () -> {
            currentScheduleConsulted.set(true);
            return 260L;
        });

        assertThat(fees).isEqualTo(260L);
        assertThat(currentScheduleConsulted).isTrue();
    }

    @Test
    void unavailableCapturedEntryIsNeverSilentlyRepricedFromTheCurrentBook() {
        PackagePriceReceipt unavailable = PackagePriceReceipt.unavailable(
                1, PackagePriceReceipt.FeeSide.OPENING, "one leg has no executable quote");
        assertThatThrownBy(() -> OutcomeController.resolveOutcomeRoundTripFees(unavailable, () -> 260L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("captured entry is unavailable")
                .hasMessageContaining("one leg has no executable quote");
    }
}
