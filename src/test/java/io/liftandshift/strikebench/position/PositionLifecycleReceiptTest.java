package io.liftandshift.strikebench.position;

import com.fasterxml.jackson.databind.JsonNode;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionLifecycleReceiptTest {

    @Test void syntheticAcceptanceBookReconcilesExactlyWithoutContainingAnOwnerRecord() throws IOException {
        JsonNode fixture;
        try (var stream = PositionLifecycleReceiptTest.class.getResourceAsStream(
                "/fixtures/position-lifecycle-book-v1.json")) {
            assertThat(stream).isNotNull();
            fixture = Json.MAPPER.readTree(stream);
        }

        assertThat(fixture.path("synthetic").asBoolean()).isTrue();
        assertThat(fixture.path("ownerAccountRecord").asBoolean()).isFalse();
        JsonNode account = fixture.path("account");
        long settlement = account.path("settlementBalanceCents").asLong();
        long pending = account.path("pendingDebitCents").asLong();
        long obligation = account.path("shortPutObligationCents").asLong();
        assertThat(settlement - obligation - pending)
                .isEqualTo(account.path("genuinelyFreeCents").asLong())
                .isEqualTo(29L);

        long contracts = 0;
        long reconstructedObligation = 0;
        var bySymbol = new java.util.LinkedHashMap<String, Long>();
        for (JsonNode group : fixture.withArray("shortPutGroups")) {
            long quantity = group.path("contracts").asLong();
            contracts += quantity;
            reconstructedObligation = Math.addExact(reconstructedObligation,
                    Math.multiplyExact(Math.multiplyExact(quantity, 100L), group.path("strikeCents").asLong()));
            bySymbol.merge(group.path("symbol").asText(), quantity, Long::sum);
            assertThat(group.path("expiration").asText()).isEqualTo("2031-08-07");
        }
        assertThat(contracts).isEqualTo(38L);
        assertThat(reconstructedObligation).isEqualTo(obligation);
        assertThat(bySymbol).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "AMD", 9L, "AVGO", 2L, "INTC", 21L, "MU", 2L, "NVDA", 1L, "QQQ", 3L));
        assertThat(fixture.withArray("confirmedEventCrossings").size()).isEqualTo(4);
        assertThat(fixture.withArray("coveredCallCases").toString())
                .contains("BELOW_TAX_LOT_BASIS", "ATM_ON_COVERED_CALL_FUND");
        assertThat(fixture.at("/workspaceStress/bookRows").asInt()).isGreaterThan(12);
        assertThat(fixture.at("/workspaceStress/candidateRows").asInt()).isGreaterThan(9);
    }

    @Test void receiptDefensivelyCopiesEveryCollectionAndKeepsEconomicOwnersAsReferences() {
        List<String> limitations = new ArrayList<>(List.of("Event evidence not loaded yet."));
        List<PositionLifecycleReceipt.AssignmentLeg> assignmentLegs = new ArrayList<>(List.of(
                new PositionLifecycleReceipt.AssignmentLeg(OptionType.PUT, LocalDate.parse("2031-08-07"),
                        18_000, 100, 1_800_000, 17_952L, "BUY_SHARES",
                        "Strike less the current fresh-eyes executable credit.")));
        var close = new PositionLifecycleReceipt.CloseQuote(true, -4_400L, -4_700L, -4_700L,
                65L, -4_765L, PositionDomain.PriceAuthority.OBSERVED,
                "Long legs sell at bid; short legs buy at ask.", null);
        var receipt = new PositionLifecycleReceipt(
                PositionLifecycleReceipt.SCHEMA_VERSION, "NVDA", "position-fingerprint",
                PositionLifecycleReceipt.History.unavailable("No opening receipt is linked.",
                        "History is never inferred from today's marks."),
                new PositionLifecycleReceipt.CurrentChoice(close,
                        "Would you open the exact position you still own today, ignoring sunk campaign cash?",
                        PositionLifecycleReceipt.FRESH_EYES_ECONOMICS_REF,
                        new PositionLifecycleReceipt.ForwardEconomics(true, 0L, 120L,
                                -80L, 300L, 50L, true,
                                "Fresh-eyes EV with only the immediate executable cash leg replaced.", null),
                        180_000L, "Existing probability-map CVaR95 receipt.",
                        PositionLifecycleReceipt.STANCE_REF,
                        "One current choice; history does not vote.", limitations),
                new PositionLifecycleReceipt.CarryCollateral(4_700L, 5.96, 16,
                        new PositionLifecycleReceipt.MoneyFact(1_800_000L,
                                PositionDomain.FactAuthority.MODEL_DERIVED, "Cash-secured strike obligation."),
                        PositionLifecycleReceipt.RateFact.unavailable(
                                "No broker-reported settlement-fund rate is linked."),
                        new PositionLifecycleReceipt.MoneyFact(1_800_000L,
                                PositionDomain.FactAuthority.MODEL_DERIVED, "Theoretical encumbrance."),
                        new PositionLifecycleReceipt.MoneyFact(1_800_000L,
                                PositionDomain.FactAuthority.MODEL_DERIVED, "Theoretical release, not broker buying power."),
                        0L, "Premium carry and collateral income are separate.", limitations),
                new PositionLifecycleReceipt.AssignmentExit(assignmentLegs,
                        PositionLifecycleReceipt.MoneyFact.unavailable("Tax-lot basis is not linked."),
                        PositionLifecycleReceipt.MoneyFact.unavailable("Campaign basis is not linked."),
                        List.of(), "UNAVAILABLE", "bookActionProjections",
                        "Assignment is exact geometry, not a probability verdict.", limitations),
                new PositionLifecycleReceipt.Evidence(OffsetDateTime.parse("2031-07-22T12:00:00Z"),
                        "PARTIAL", "market-fingerprint", "model-fingerprint", "FACTS_ONLY",
                        List.of("preview", "evaluation"), limitations));

        limitations.add("mutated");
        assignmentLegs.clear();

        assertThat(receipt.currentChoice().limitations()).containsExactly("Event evidence not loaded yet.");
        assertThat(receipt.assignmentExit().legs()).hasSize(1);
        assertThat(receipt.currentChoice().freshEyesEconomicsRef())
                .isEqualTo("evaluation.assessment.economics");
        assertThat(receipt.currentChoice().stanceRef()).isEqualTo("evaluation.stance");
        assertThat(Json.MAPPER.valueToTree(receipt).at("/currentChoice/freshEyesEconomicsRef").asText())
                .isEqualTo("evaluation.assessment.economics");
    }

    @Test void authorityAndCashReconciliationCannotBeFaked() {
        assertThatThrownBy(() -> new PositionLifecycleReceipt.MoneyFact(100L,
                PositionDomain.FactAuthority.UNAVAILABLE, "Unknown."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable money fact");
        assertThatThrownBy(() -> new PositionLifecycleReceipt.CloseQuote(true, null,
                -4_700L, -4_700L, 65L, -4_700L, PositionDomain.PriceAuthority.OBSERVED,
                "Executable book.", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reconcile");
    }
}
