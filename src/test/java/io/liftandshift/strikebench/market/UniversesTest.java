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
}
