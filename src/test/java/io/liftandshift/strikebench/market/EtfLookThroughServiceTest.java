package io.liftandshift.strikebench.market;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EtfLookThroughServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-22T16:00:00Z"), ZoneOffset.UTC);

    @Test
    void expandsReviewedWeightsAndKeepsTheResidualExact() {
        var service = new EtfLookThroughService(CLOCK);
        var book = service.expand(Map.of("VGT", 1_000_000L, "AMD", 500_000L));

        assertThat(book.funds()).singleElement().satisfies(fund -> {
            assertThat(fund.fund()).isEqualTo("VGT");
            assertThat(fund.status()).isEqualTo(EtfLookThroughService.Status.AVAILABLE);
            assertThat(fund.sourceUrl()).contains("vanguard.com");
            assertThat(fund.asOf()).hasToString("2026-05-31");
            assertThat(fund.coveredWeightPct()).isEqualTo(57.59);
            assertThat(fund.residualWeightPct()).isEqualTo(42.41);
        });
        assertThat(book.etfNotionalCents()).isEqualTo(1_000_000);
        assertThat(book.disclosedNotionalCents()).isEqualTo(575_900);
        assertThat(book.residualNotionalCents()).isEqualTo(424_100);
        assertThat(book.components()).filteredOn(row -> row.symbol().equals("AMD"))
                .singleElement().satisfies(row -> {
                    assertThat(row.notionalCents()).isEqualTo(32_000);
                    assertThat(row.theme()).isEqualTo("Semiconductors, memory & storage");
                });
        assertThat(book.themes().stream().mapToLong(
                EtfLookThroughService.ThemeExposure::notionalCents).sum()).isEqualTo(1_000_000);
        assertThat(book.complete()).isTrue();
    }

    @Test
    void marksUnknownEtfsUnavailableInsteadOfInferringHoldings() {
        var service = new EtfLookThroughService(CLOCK);
        var book = service.expand(Map.of("SPY", 2_500_000L));

        assertThat(book.funds()).singleElement().satisfies(fund -> {
            assertThat(fund.status()).isEqualTo(EtfLookThroughService.Status.UNAVAILABLE);
            assertThat(fund.evidence().provenance().name()).isEqualTo("MISSING");
            assertThat(fund.note()).contains("does not infer");
        });
        assertThat(book.components()).isEmpty();
        assertThat(book.residualNotionalCents()).isEqualTo(2_500_000);
        assertThat(book.complete()).isFalse();
    }

    @Test
    void labelsQqqAsAnIndexProxyAndJepqStockWeightsAsOverlayIncomplete() {
        var service = new EtfLookThroughService(CLOCK);
        var book = service.expand(Map.of("QQQ", 1_000_000L, "JEPQ", 1_000_000L));

        assertThat(book.funds()).filteredOn(row -> row.fund().equals("QQQ"))
                .singleElement().satisfies(fund -> {
                    assertThat(fund.kind()).isEqualTo(EtfLookThroughService.CompositionKind.INDEX_PROXY);
                    assertThat(fund.note()).contains("labeled proxy");
                });
        assertThat(book.funds()).filteredOn(row -> row.fund().equals("JEPQ"))
                .singleElement().satisfies(fund -> assertThat(fund.note()).contains("option overlay"));
        assertThat(book.components()).filteredOn(row -> row.symbol().equals("NVDA"))
                .hasSize(2);
    }
}
