package io.liftandshift.strikebench.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigYahooTest {

    @Test
    void dailyRequestBudgetIsAlwaysPositiveAndBounded() {
        assertThat(new AppConfig(Map.of()).yahooDailyRequestLimit()).isEqualTo(160);
        assertThat(new AppConfig(Map.of("YAHOO_DAILY_REQUEST_LIMIT", "0"))
                .yahooDailyRequestLimit()).isEqualTo(1);
        assertThat(new AppConfig(Map.of("YAHOO_DAILY_REQUEST_LIMIT", "-40"))
                .yahooDailyRequestLimit()).isEqualTo(1);
        assertThat(new AppConfig(Map.of("YAHOO_DAILY_REQUEST_LIMIT", "50000"))
                .yahooDailyRequestLimit()).isEqualTo(500);
    }

    @Test
    void configuredCooldownIsNotTruncated() {
        assertThat(new AppConfig(Map.of("YAHOO_COOLDOWN_MINUTES", "240"))
                .yahooCooldownMinutes()).isEqualTo(240);
    }

    @Test
    void observedScoutIncludesTradableMemoryAndStorageNamesByDefault() {
        assertThat(new AppConfig(Map.of("FIXTURES_ONLY", "false")).autoUniverse())
                .contains("SMH", "MU", "STX", "WDC", "SNDK")
                .doesNotContain("000660.KS");
    }
}
