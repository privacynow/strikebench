package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.support.TestPrices;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanOutcomeCapturedFeeTest {

    @Test
    void comparisonConsumesTheWholeCapturedReceiptAndNeverInventsMissingPriceOrFees() {
        ObjectNode captured = Json.MAPPER.createObjectNode();
        captured.put("qty", 2);
        var expected = TestPrices.withFeeSchedule(2, 42_000L, 42_000L, 260L, 777L);
        captured.set("price", Json.MAPPER.valueToTree(expected));
        assertThat(PlanOutcomeController.capturedOutcomePrice(captured)).isEqualTo(expected);

        ObjectNode absent = Json.MAPPER.createObjectNode();
        absent.put("qty", 2);
        assertThatThrownBy(() -> PlanOutcomeController.capturedOutcomePrice(absent))
                .hasMessageContaining("no captured package-price receipt");

        ObjectNode unknown = Json.MAPPER.createObjectNode();
        unknown.put("qty", 1);
        unknown.set("price", Json.MAPPER.valueToTree(TestPrices.optionOnly(1, 42_000L)));
        assertThatThrownBy(() -> PlanOutcomeController.capturedOutcomePrice(unknown))
                .hasMessageContaining("no estimated round-trip commission");

        ObjectNode malformed = Json.MAPPER.createObjectNode();
        malformed.put("qty", 1);
        malformed.putObject("price").put("grossPackageNetCents", 42_000L);
        assertThatThrownBy(() -> PlanOutcomeController.capturedOutcomePrice(malformed))
                .hasMessageContaining("captured package-price receipt is malformed");
    }
}
