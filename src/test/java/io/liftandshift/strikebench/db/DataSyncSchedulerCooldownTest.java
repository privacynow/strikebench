package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataSyncSchedulerCooldownTest {

    @Test
    void yahooRetryUsesTheWholeConfiguredQuietPeriod() {
        AppConfig cfg = new AppConfig(Map.of("YAHOO_COOLDOWN_MINUTES", "240"));
        DataSyncScheduler scheduler = new DataSyncScheduler(cfg, Clock.systemUTC(), null, null);
        var schedule = new DataSyncState.Schedule("system", true, "yahoo", List.of("AMD"),
                2, null, null, null, "coverage", null, null);

        assertThat(scheduler.retryCooldown(schedule)).isEqualTo(Duration.ofMinutes(240));
    }
}
