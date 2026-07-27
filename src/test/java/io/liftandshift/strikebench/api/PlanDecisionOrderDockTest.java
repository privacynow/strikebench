package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The decision response has one exact price authority: {@code preview.price}. The order dock
 * carries only the user's instruction and cannot republish that receipt under scalar aliases.
 */
class PlanDecisionOrderDockTest {

    @Test void marketDockSerializesOnlyItsInstruction() {
        var json = Json.MAPPER.valueToTree(
                PlanDecisionController.orderDock(order(OrderInstruction.market())));

        assertThat(json.path("orderInstruction").path("type").asText()).isEqualTo("MARKET");
        assertThat(json.has("price")).isFalse();
        assertThat(json.has("displayCashNetCents")).isFalse();
        assertThat(json.has("suggestedLimitNetCents")).isFalse();
        assertThat(json.size()).isEqualTo(1);
    }

    @Test void limitDockPreservesTheExactInstructionWithoutASecondPrice() {
        var json = Json.MAPPER.valueToTree(
                PlanDecisionController.orderDock(order(OrderInstruction.limit(69_000))));

        assertThat(json.path("orderInstruction").path("type").asText()).isEqualTo("LIMIT");
        assertThat(json.path("orderInstruction").path("limitNetCents").asLong())
                .isEqualTo(69_000L);
        assertThat(json.size()).isEqualTo(1);
    }

    @Test void contradictoryExecutionAuthorizationFailsClosedAtConstruction() {
        assertThatThrownBy(() -> new ApiResponses.ExecutionDecision(false, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fails closed");
    }

    private static TradeOpenRequest order(OrderInstruction instruction) {
        return new TradeOpenRequest("AMD", "CASH_SECURED_PUT", 1, List.of(), "neutral", "1d",
                "conservative", "INCOME", false, null, null,
                "PLAN", List.of(), null, "PROPOSED", instruction);
    }
}
