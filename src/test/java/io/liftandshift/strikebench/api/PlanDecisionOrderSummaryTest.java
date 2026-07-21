package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.TradePreview;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanDecisionOrderSummaryTest {

    @Test void unavailableExecutionDoesNotPromoteThePreviewZeroSentinelToAValuation() {
        var order = order(OrderInstruction.market());
        var preview = preview(false, 0, OrderInstruction.Executability.UNAVAILABLE, null);

        var summary = PlanDecisionController.orderSummary(order, preview);

        assertThat(summary.executability()).isEqualTo(OrderInstruction.Executability.UNAVAILABLE);
        assertThat(summary.executableNetCents()).isNull();
        assertThat(summary.valuedNetCents()).isNull();
        assertThat(summary.valuationBasis()).isEqualTo(ApiResponses.OrderValuationBasis.UNAVAILABLE);
    }

    @Test void immediateAndRestingInstructionsExposeDistinctValuationBases() {
        var immediate = PlanDecisionController.orderSummary(order(OrderInstruction.market()),
                preview(true, 67_000, OrderInstruction.Executability.IMMEDIATE, 67_000L));
        assertThat(immediate.valuedNetCents()).isEqualTo(67_000L);
        assertThat(immediate.valuationBasis()).isEqualTo(ApiResponses.OrderValuationBasis.EXECUTABLE_BOOK);

        var resting = PlanDecisionController.orderSummary(order(OrderInstruction.limit(69_000)),
                preview(false, 69_000, OrderInstruction.Executability.RESTING, 67_000L));
        assertThat(resting.valuedNetCents()).isEqualTo(69_000L);
        assertThat(resting.executableNetCents()).isEqualTo(67_000L);
        assertThat(resting.valuationBasis()).isEqualTo(ApiResponses.OrderValuationBasis.RESTING_LIMIT);
    }

    private static TradeOpenRequest order(OrderInstruction instruction) {
        return new TradeOpenRequest("AMD", "CASH_SECURED_PUT", 1, List.of(), "neutral", "1d",
                "conservative", "INCOME", false, null, instruction.limitNetCents(), null,
                "PLAN", List.of(), null, "PROPOSED", instruction);
    }

    private static TradePreview preview(boolean ok, long entryNet,
                                        OrderInstruction.Executability executability,
                                        Long executableNet) {
        var quality = new java.util.LinkedHashMap<String, Object>();
        quality.put("executability", executability.name());
        if (executableNet != null) quality.put("executableNetCents", executableNet);
        return new TradePreview(ok, ok ? List.of() : List.of("Execution is unavailable."), List.of(),
                entryNet, 0, 0, null, List.of(), null, null, 0,
                100_000, 100_000, 0, 0, 100_000, 100_000,
                "MISSING", null, 0, null, List.of(), List.of(),
                Map.of("executionQuality", quality));
    }
}
