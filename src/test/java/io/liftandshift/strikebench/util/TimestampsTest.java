package io.liftandshift.strikebench.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TimestampsTest {
    @Test void oneParserOwnsWireEpochIsoAndPostgresTextForms() {
        Instant expected = Instant.parse("2026-07-24T17:26:00Z");
        assertThat(Timestamps.instant("1784913960000")).isEqualTo(expected);
        assertThat(Timestamps.instant("2026-07-24T17:26:00Z")).isEqualTo(expected);
        assertThat(Timestamps.instant("2026-07-24 17:26:00+00")).isEqualTo(expected);
        assertThat(Timestamps.isoInstant("2026-07-24 17:26:00+00"))
                .isEqualTo("2026-07-24T17:26:00Z");
        assertThat(Timestamps.instant(null)).isNull();
    }
}
