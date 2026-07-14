package io.liftandshift.strikebench.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BroadBasedIndexOptionsTest {

    @Test
    void recognizesEverySharedRootAndKnownExchangeSeriesAliases() {
        assertThat(BroadBasedIndexOptions.ROOTS)
                .containsExactly("SPX", "XSP", "NDX", "VIX", "RUT", "DJX", "OEX", "XEO");
        assertThat(BroadBasedIndexOptions.ROOTS)
                .allMatch(BroadBasedIndexOptions::isKnownRoot);
        assertThat(BroadBasedIndexOptions.AUTOMATIC_SYMBOLS)
                .allMatch(BroadBasedIndexOptions::isKnownRoot);

        assertThat(BroadBasedIndexOptions.canonicalRoot("spxw")).contains("SPX");
        assertThat(BroadBasedIndexOptions.canonicalRoot("SPXpm")).contains("SPX");
        assertThat(BroadBasedIndexOptions.canonicalRoot("NDXP")).contains("NDX");
        assertThat(BroadBasedIndexOptions.canonicalRoot("VIXW")).contains("VIX");
        assertThat(BroadBasedIndexOptions.canonicalRoot("RUTW")).contains("RUT");
        assertThat(BroadBasedIndexOptions.canonicalRoot("_XSP")).contains("XSP");
    }

    @Test
    void doesNotTurnLookAlikeEquitySymbolsIntoIndexContracts() {
        assertThat(BroadBasedIndexOptions.isKnownRoot("SPY")).isFalse();
        assertThat(BroadBasedIndexOptions.isKnownRoot("VIXY")).isFalse();
        assertThat(BroadBasedIndexOptions.isKnownRoot("XSPT")).isFalse();
        assertThat(BroadBasedIndexOptions.isKnownRoot("RUTM")).isFalse();
        assertThat(BroadBasedIndexOptions.isKnownRoot(null)).isFalse();
    }
}
