package io.liftandshift.strikebench.market.providers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpTest {
    @Test
    void providerExceptionsNeverExposeQueryOrJsonCredentials() {
        var e = new Http.ProviderHttpException(
                "https://example.test/data?symbol=AAPL&apiKey=super-secret&limit=5", 429,
                "{\"access_token\":\"also-secret\",\"message\":\"slow down\"}");
        assertThat(e.getMessage()).contains("apiKey=<redacted>", "access_token\":\"<redacted>")
                .doesNotContain("super-secret", "also-secret");
    }
}
