package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.sim.PathPosition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutcomeFeeAuthorityTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 24);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 21);

    @Test
    void scenarioContractCountDelegatesExactRatiosAndQuantityToCanonicalFees() {
        PathPosition position = new PathPosition(AS_OF, List.of(
                option(LegAction.BUY, 2),
                option(LegAction.SELL, 3),
                Leg.stock(LegAction.BUY, 100, BigDecimal.ZERO)));

        assertThat(OutcomeController.scenarioOptionContracts(position, 4)).isEqualTo(20);
    }

    @Test
    void malformedScenarioQuantityIsRejectedInsteadOfSilentlyBecomingOne() {
        PathPosition position = new PathPosition(AS_OF, List.of(option(LegAction.BUY, 1)));

        assertThatThrownBy(() -> OutcomeController.scenarioOptionContracts(position, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity >= 1");
        assertThatThrownBy(() -> OutcomeController.scenarioOptionContracts(null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a position");
    }

    private static Leg option(LegAction action, int ratio) {
        return Leg.option(action, OptionType.CALL, new BigDecimal("100"), EXPIRY,
                ratio, BigDecimal.ZERO);
    }
}
