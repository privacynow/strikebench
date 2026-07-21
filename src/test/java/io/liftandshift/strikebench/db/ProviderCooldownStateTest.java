package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.market.ProviderPoliteness;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.EventBus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderCooldownStateTest {

    @Test
    void mountedProviderBreakersSurviveAnOrdinaryProcessRestart() throws Exception {
        try (Db db = TestDb.fresh()) {
            SettingsStore settings = new SettingsStore(db);
            EventBus firstProcessEvents = new EventBus();
            ProviderPoliteness firstYahoo = new ProviderPoliteness("yahoo", 1, 0, 60_000);
            ProviderPoliteness firstCboe = new ProviderPoliteness("cboe", 1, 0, 60_000);
            firstYahoo.setEvents(firstProcessEvents);
            firstCboe.setEvents(firstProcessEvents);
            ProviderCooldownState.wire(settings, firstProcessEvents,
                    Map.of("yahoo", firstYahoo::seedCooldown, "cboe", firstCboe::seedCooldown));

            firstYahoo.trip();
            firstCboe.trip();
            long deadline = System.currentTimeMillis() + 2_000;
            while ((settings.get("yahoo_cooldown_until").isEmpty()
                    || settings.get("cboe_cooldown_until").isEmpty())
                    && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertThat(settings.get("yahoo_cooldown_until")).isPresent();
            assertThat(settings.get("cboe_cooldown_until")).isPresent();

            // A new provider and event bus model an ordinary server restart over the same DB.
            EventBus secondProcessEvents = new EventBus();
            ProviderPoliteness secondYahoo = new ProviderPoliteness("yahoo", 1, 0, 60_000);
            ProviderPoliteness secondCboe = new ProviderPoliteness("cboe", 1, 0, 60_000);
            ProviderCooldownState.wire(settings, secondProcessEvents,
                    Map.of("yahoo", secondYahoo::seedCooldown, "cboe", secondCboe::seedCooldown));

            AtomicInteger wireCalls = new AtomicInteger();
            assertThat(secondYahoo.call(() -> {
                wireCalls.incrementAndGet();
                return "network";
            }, "cooling")).isEqualTo("cooling");
            assertThat(secondCboe.call(() -> {
                wireCalls.incrementAndGet();
                return "network";
            }, "cooling")).isEqualTo("cooling");
            assertThat(wireCalls).hasValue(0);
        }
    }
}
