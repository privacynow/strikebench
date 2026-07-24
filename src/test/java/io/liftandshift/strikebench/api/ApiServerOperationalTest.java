package io.liftandshift.strikebench.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiServerOperationalTest {
    @Test
    void throttleEvictsIndividualBucketsWithoutResettingTheWholePopulation() {
        ApiTelemetry.IpThrottle throttle = new ApiTelemetry.IpThrottle(1, 0, 3);
        String activeClient = "10.0.0.1";
        assertThat(throttle.tryAcquire(activeClient)).isTrue();
        assertThat(throttle.tryAcquire(activeClient)).isFalse();

        for (int i = 2; i <= 20; i++) {
            throttle.tryAcquire("10.0.0." + i);
            assertThat(throttle.tryAcquire(activeClient)).isFalse();
        }

        assertThat(throttle.activeBuckets()).isBetween(1L, 3L);
        assertThat(throttle.tryAcquire(activeClient))
                .as("address churn must not reset an actively throttled client")
                .isFalse();
    }
}
