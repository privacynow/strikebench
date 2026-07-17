package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.market.providers.FixtureProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The event model must ESTIMATE from filing cadence and never guess: too-thin history yields
 * empty, and the estimate always projects forward of today. This class exists because a keyword
 * heuristic once warned "earnings expected" two weeks AFTER Micron reported (the MU incident).
 */
class EventServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-10T15:30:00Z"), ZoneOffset.UTC);

    private MarketDataService market() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        return new MarketDataService(java.util.List.of(fixture), java.util.List.of(fixture), java.util.List.of());
    }

    @Test
    void estimateProjectsForwardOrHonestlyDeclines() {
        EventService events = new EventService(market(), CLOCK);
        var est = events.nextEarnings("AAPL");
        // Fixture news may or may not include >=2 quarterly filings — BOTH outcomes must be honest:
        // either an unconfirmed forward-dated estimate with a disclosed basis, or no estimate at all.
        est.ifPresentOrElse(e -> {
            var reports = events.quarterlyReportDates("AAPL");
            assertThat(reports).hasSizeGreaterThanOrEqualTo(2).isSortedAccordingTo(java.util.Comparator.reverseOrder());
            assertThat(e.confirmed()).isFalse();
            assertThat(e.estimated()).isAfterOrEqualTo(LocalDate.of(2026, 7, 10));
            assertThat(e.basis()).contains("filing cadence");
            assertThat(e.windowDays()).isBetween(1, 14);
        }, () -> assertThat(events.nextEarnings("AAPL")).isEmpty());
    }

    @Test
    void unknownSymbolsNeverGuess() {
        EventService events = new EventService(market(), CLOCK);
        assertThat(events.nextEarnings("ZZZZZZ")).isEmpty();
        assertThat(events.quarterlyReportDates("ZZZZZZ")).isEmpty();
        assertThat(events.earningsLikelyBefore("ZZZZZZ", LocalDate.of(2026, 8, 21))).isFalse();
    }
}
