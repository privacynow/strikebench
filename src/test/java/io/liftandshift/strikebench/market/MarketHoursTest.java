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

    @org.junit.jupiter.api.Test
    void nyseHolidaysAreNotTradingDays() {
        // Rule-computed calendar: fixed, floating, observed-shift and Easter-derived cases.
        org.assertj.core.api.Assertions.assertThat(MarketHours.isTradingDay(java.time.LocalDate.of(2026, 1, 1))).isFalse();   // New Year's
        org.assertj.core.api.Assertions.assertThat(MarketHours.isTradingDay(java.time.LocalDate.of(2026, 1, 19))).isFalse();  // MLK (3rd Mon)
        org.assertj.core.api.Assertions.assertThat(MarketHours.isTradingDay(java.time.LocalDate.of(2026, 4, 3))).isFalse();   // Good Friday
        org.assertj.core.api.Assertions.assertThat(MarketHours.isTradingDay(java.time.LocalDate.of(2026, 7, 3))).isFalse();   // Jul 4 observed (Sat -> Fri)
        org.assertj.core.api.Assertions.assertThat(MarketHours.isTradingDay(java.time.LocalDate.of(2026, 7, 4))).isFalse();   // Saturday anyway
        org.assertj.core.api.Assertions.assertThat(MarketHours.isTradingDay(java.time.LocalDate.of(2026, 11, 26))).isFalse(); // Thanksgiving
        org.assertj.core.api.Assertions.assertThat(MarketHours.isTradingDay(java.time.LocalDate.of(2026, 12, 25))).isFalse(); // Christmas
        org.assertj.core.api.Assertions.assertThat(MarketHours.isTradingDay(java.time.LocalDate.of(2026, 7, 10))).isTrue();   // ordinary Friday
        // A holiday weekday is NOT a regular session even at noon ET.
        java.time.Instant noonOnMlk = java.time.LocalDate.of(2026, 1, 19).atTime(12, 0)
                .atZone(MarketHours.EASTERN).toInstant();
        org.assertj.core.api.Assertions.assertThat(MarketHours.isRegularSession(noonOnMlk)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void tradingDaysBetweenCountsSessionsNotCalendarDays() {
        // The MU condor case: sold Friday 2026-07-10, expiring Monday 2026-07-13 — ONE session away.
        org.assertj.core.api.Assertions.assertThat(MarketHours.tradingDaysBetween(
                java.time.LocalDate.of(2026, 7, 10), java.time.LocalDate.of(2026, 7, 13))).isEqualTo(1);
        // A week spanning July-4th-observed loses a session: Mon Jun 29 -> Mon Jul 6 = 4 sessions.
        org.assertj.core.api.Assertions.assertThat(MarketHours.tradingDaysBetween(
                java.time.LocalDate.of(2026, 6, 29), java.time.LocalDate.of(2026, 7, 6))).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(MarketHours.tradingDaysBetween(
                java.time.LocalDate.of(2026, 7, 10), java.time.LocalDate.of(2026, 7, 10))).isZero();
    }

    @Test
    void tradingDateAfterIsTheExactInverseAcrossWeekendsAndHolidays() {
        LocalDate friday = LocalDate.of(2026, 7, 10);
        assertThat(MarketHours.tradingDateAfter(friday, 1)).isEqualTo(LocalDate.of(2026, 7, 13));
        LocalDate beforeJulyFourth = LocalDate.of(2026, 6, 29);
        LocalDate fourSessionsLater = MarketHours.tradingDateAfter(beforeJulyFourth, 4);
        assertThat(fourSessionsLater).isEqualTo(LocalDate.of(2026, 7, 6));
        assertThat(MarketHours.tradingDaysBetween(beforeJulyFourth, fourSessionsLater)).isEqualTo(4);
    }
}
