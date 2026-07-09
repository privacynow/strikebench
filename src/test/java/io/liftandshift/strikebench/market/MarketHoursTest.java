package io.liftandshift.strikebench.market;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MarketHoursTest {

    // 2026-07-08 is a Wednesday. 13:30Z = 9:30 ET, 20:00Z = 16:00 ET (EDT, UTC-4).

    @Test
    void regularSessionBoundaries() {
        assertThat(MarketHours.isRegularSession(Instant.parse("2026-07-08T13:29:59Z"))).isFalse(); // 9:29:59 ET
        assertThat(MarketHours.isRegularSession(Instant.parse("2026-07-08T13:30:00Z"))).isTrue();  // open
        assertThat(MarketHours.isRegularSession(Instant.parse("2026-07-08T15:30:00Z"))).isTrue();  // midday
        assertThat(MarketHours.isRegularSession(Instant.parse("2026-07-08T19:59:59Z"))).isTrue();  // 15:59:59 ET
        assertThat(MarketHours.isRegularSession(Instant.parse("2026-07-08T20:00:00Z"))).isFalse(); // the bell
        assertThat(MarketHours.isRegularSession(Instant.parse("2026-07-11T15:00:00Z"))).isFalse(); // Saturday
        assertThat(MarketHours.isRegularSession(Instant.parse("2026-07-12T15:00:00Z"))).isFalse(); // Sunday
    }

    @Test
    void contractsDieAtTheClosingBellOfExpirationDay() {
        LocalDate expiry = LocalDate.of(2026, 7, 6); // Monday
        assertThat(MarketHours.contractDead(expiry, Instant.parse("2026-07-06T15:30:00Z"))).isFalse(); // morning of expiry
        assertThat(MarketHours.contractDead(expiry, Instant.parse("2026-07-06T19:59:59Z"))).isFalse(); // 15:59:59 ET
        assertThat(MarketHours.contractDead(expiry, Instant.parse("2026-07-06T20:00:00Z"))).isTrue();  // 16:00 ET sharp
        // The real incident: 9:24pm ET the same evening — same calendar day, dead contract
        assertThat(MarketHours.contractDead(expiry, Instant.parse("2026-07-07T01:24:46Z"))).isTrue();
        assertThat(MarketHours.contractDead(expiry, Instant.parse("2026-07-10T12:00:00Z"))).isTrue();  // days later
        assertThat(MarketHours.contractDead(LocalDate.of(2026, 8, 21), Instant.parse("2026-07-07T01:24:46Z"))).isFalse();
    }
}
