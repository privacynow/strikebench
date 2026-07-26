package io.liftandshift.strikebench.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutcomeCapturedFeePolicyTest {

    @Test
    void frozenEntryUsesItsCapturedFeeWithoutConsultingCurrentConfiguration() {
        AtomicBoolean currentScheduleConsulted = new AtomicBoolean();

        long fees = OutcomeController.resolveOutcomeRoundTripFees(42_000L, 777L, () -> {
            currentScheduleConsulted.set(true);
            return 260L;
        });

        assertThat(fees).isEqualTo(777L);
        assertThat(currentScheduleConsulted).isFalse();
    }

    @Test
    void frozenEntryWithoutCapturedFeeIsRefusedByName() {
        AtomicBoolean currentScheduleConsulted = new AtomicBoolean();

        assertThatThrownBy(() -> OutcomeController.resolveOutcomeRoundTripFees(42_000L, null, () -> {
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

        long fees = OutcomeController.resolveOutcomeRoundTripFees(null, null, () -> {
            currentScheduleConsulted.set(true);
            return 260L;
        });

        assertThat(fees).isEqualTo(260L);
        assertThat(currentScheduleConsulted).isTrue();
    }

    @Test
    void capturedFeeCannotTravelWithoutItsCapturedEntry() {
        assertThatThrownBy(() -> OutcomeController.resolveOutcomeRoundTripFees(null, 777L, () -> 260L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires its captured entry price");
    }
}
