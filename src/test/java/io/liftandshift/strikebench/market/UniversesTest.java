package io.liftandshift.strikebench.market;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UniversesTest {
    @Test void reverseLookupKeepsPeersAndComplementsDistinctFromTheAnchor() {
        assertThat(Universes.peersOf("AAPL"))
                .contains("MSFT", "NVDA", "GOOGL", "XLK")
                .doesNotContain("AAPL");
        assertThat(Universes.complementsFor("NVDA"))
                .contains("SMH", "SPY", "TLT", "GLD")
                .doesNotContain("NVDA");
    }

    @Test void memoryAndStorageThemeUsesTradableUsProxies() {
        assertThat(Universes.SECTORS.get("SEMICONDUCTORS").symbols())
                .contains("MU", "STX", "WDC", "SNDK", "SMH")
                .doesNotContain("000660.KS");
        assertThat(Universes.peersOf("SNDK"))
                .contains("MU", "STX", "WDC", "SMH")
                .doesNotContain("SNDK");
        assertThat(Universes.allocationSectorLabel("WDC"))
                .isEqualTo("Semiconductors, memory & storage");
    }
}
