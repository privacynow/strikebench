package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.util.EventBus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The shared provider gate: breaker on rate limits, short-circuit while cooling, prefetch budget. */
class ProviderPolitenessTest {

    @Test
    void rateLimitTripsTheBreakerAndShortCircuitsUntilItClears() {
        ProviderPoliteness p = new ProviderPoliteness("yahoo", 2, 0, 60_000);
        EventBus bus = new EventBus();
        p.setEvents(bus);
        AtomicInteger calls = new AtomicInteger();

        assertThat(p.prefetchBudget()).isTrue();
        // A 429 propagates AND trips the provider-wide breaker.
        assertThatThrownBy(() -> p.call(() -> { calls.incrementAndGet(); throw new RuntimeException("HTTP 429 rate limited"); }, null))
                .isInstanceOf(RuntimeException.class);
        assertThat(p.coolingDown()).isTrue();
        assertThat(p.prefetchBudget()).isFalse();
        // While cooling, the request is NEVER made — the fallback answers.
        String out = p.call(() -> { calls.incrementAndGet(); return "network"; }, "fallback");
        assertThat(out).isEqualTo("fallback");
        assertThat(calls.get()).isEqualTo(1); // only the tripping call reached the wire
        // The trip was announced for the UI's calm cooldown chip.
        assertThat(bus.since(0)).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("provider.cooldown");
            assertThat(e.data()).containsEntry("provider", "yahoo");
        });
    }

    @Test
    void yahooLegacy999AlsoTripsAndOrdinaryFailuresDoNot() {
        ProviderPoliteness p = new ProviderPoliteness("yahoo", 1, 0, 60_000);
        assertThatThrownBy(() -> p.call(() -> { throw new RuntimeException("HTTP 500 boom"); }, null))
                .hasMessageContaining("500");
        assertThat(p.coolingDown()).isFalse(); // a server error is not a rate limit
        assertThatThrownBy(() -> p.call(() -> { throw new RuntimeException("HTTP 999 request denied"); }, null))
                .hasMessageContaining("999");
        assertThat(p.coolingDown()).isTrue();
    }

    @Test
    void healthyCallsPassThroughAndReturnTheValue() {
        ProviderPoliteness p = new ProviderPoliteness("x", 4, 0, 60_000);
        assertThat(p.call(() -> "ok", null)).isEqualTo("ok");
        assertThat(p.coolingDown()).isFalse();
    }

    @Test
    void repeatedOrdinaryFailuresBackOffInsteadOfSweepingTheWholeUniverse() {
        ProviderPoliteness p = new ProviderPoliteness("yahoo", 1, 0, 60_000);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> p.call(() -> { throw new RuntimeException("HTTP 500 upstream"); }, null))
                    .hasMessageContaining("500");
            assertThat(p.coolingDown()).isFalse();
        }
        assertThatThrownBy(() -> p.call(() -> { throw new RuntimeException("HTTP 500 upstream"); }, null))
                .hasMessageContaining("500");
        assertThat(p.coolingDown()).isTrue();
    }

    @Test
    void restoredCooldownShortCircuitsAndExpiredStateIsIgnored() {
        ProviderPoliteness restored = new ProviderPoliteness("yahoo", 1, 0, 60_000);
        restored.seedCooldown(System.currentTimeMillis() + 60_000);
        AtomicInteger calls = new AtomicInteger();
        assertThat(restored.call(() -> { calls.incrementAndGet(); return "wire"; }, "stored"))
                .isEqualTo("stored");
        assertThat(calls).hasValue(0);

        ProviderPoliteness expired = new ProviderPoliteness("yahoo", 1, 0, 60_000);
        expired.seedCooldown(System.currentTimeMillis() - 1);
        assertThat(expired.call(() -> "wire", "stored")).isEqualTo("wire");
    }
}
