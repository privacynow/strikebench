package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.support.TestPrices;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanOutcomeCapturedFeeTest {

    @Test
    void comparisonUsesTheCapturedReceiptAndNeverInventsMissingFees() {
        ObjectNode captured = Json.MAPPER.createObjectNode();
        ObjectNode price = Json.MAPPER.valueToTree(
                TestPrices.withFees(2, 42_000L, 42_000L, 260L));
        // Deliberately asymmetric: this proves the comparison consumes the captured estimate
        // rather than reconstructing it as opening commission × 2.
        price.put("estimatedRoundTripFeesCents", 777L);
        captured.set("price", price);
        assertThat(PlanOutcomeController.capturedRoundTripFees(captured)).isEqualTo(777L);

        ObjectNode absent = Json.MAPPER.createObjectNode();
        assertThat(PlanOutcomeController.capturedRoundTripFees(absent)).isNull();

        ObjectNode unknown = Json.MAPPER.createObjectNode();
        unknown.set("price", Json.MAPPER.valueToTree(TestPrices.optionOnly(1, 42_000L)));
        assertThat(PlanOutcomeController.capturedRoundTripFees(unknown)).isNull();

        ObjectNode malformed = Json.MAPPER.createObjectNode();
        malformed.putObject("price").put("grossPackageNetCents", 42_000L);
        assertThat(PlanOutcomeController.capturedRoundTripFees(malformed)).isNull();
    }
}
