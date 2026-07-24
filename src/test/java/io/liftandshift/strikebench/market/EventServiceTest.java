package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.model.NewsItem;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The event owner persists one precedence-ordered authority receipt and never guesses. */
class EventServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-10T15:30:00Z"), ZoneOffset.UTC);
    private Db db;

    @AfterEach void closeDb() { if (db != null) db.close(); }

    @Test
    void secCadenceEstimateIsCanonicalPersistedAndReusableWithoutAnotherAcquisition() {
        db = TestDb.fresh();
        AtomicInteger calls = new AtomicInteger();
        EventService events = new EventService(marketWithSec(calls), db, CLOCK);

        EventService.EventEvidence event = events.earnings("aapl");
        assertThat(event.status()).isEqualTo(EventService.EvidenceStatus.ESTIMATED);
        assertThat(event.sourceKind()).isEqualTo(EventService.SourceKind.SEC_CADENCE);
        assertThat(event.source()).isEqualTo("SEC EDGAR");
        assertThat(event.date()).isAfterOrEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(event.windowDays()).isEqualTo(7);
        assertThat(event.payloadFingerprint()).matches("[0-9a-f]{64}");
        assertThat(event.observedAt()).isEqualTo(OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC));
        assertThat(events.history("AAPL")).singleElement().isEqualTo(event);
        assertThat(calls).hasValue(1);

        AtomicInteger shouldStayZero = new AtomicInteger();
        EventService restarted = new EventService(marketWithSec(shouldStayZero), db, CLOCK);
        assertThat(restarted.earnings("AAPL").payloadFingerprint()).isEqualTo(event.payloadFingerprint());
        assertThat(shouldStayZero).hasValue(0);
    }

    @Test
    void confirmedIssuerEvidenceOutranksReviewedImportAndSecEstimate() {
        db = TestDb.fresh();
        EventService events = new EventService(marketWithSec(new AtomicInteger()), db, CLOCK);
        events.earnings("AAPL"); // persist the lower-authority SEC estimate first
        events.importReviewed(new EventService.ReviewedEvent("AAPL",
                EventService.EvidenceStatus.ESTIMATED, LocalDate.of(2026, 8, 3),
                EventService.EventSession.UNKNOWN, LocalDate.of(2026, 7, 31),
                LocalDate.of(2026, 8, 6), EventService.SourceKind.REVIEWED_IMPORT,
                "Reviewed calendar", "https://calendar.example/aapl", null,
                "analyst-reviewed calendar import", "reviewer@example.test", "reviewed-row"));
        EventService.EventEvidence reviewed = events.earnings("AAPL");
        assertThat(reviewed.sourceKind()).isEqualTo(EventService.SourceKind.REVIEWED_IMPORT);

        EventService.EventEvidence chosen = events.importReviewed(new EventService.ReviewedEvent("AAPL",
                EventService.EvidenceStatus.CONFIRMED, LocalDate.of(2026, 7, 22),
                EventService.EventSession.AFTER_CLOSE, null, null,
                EventService.SourceKind.ISSUER_CONFIRMED, "Apple Investor Relations",
                "https://investor.apple.com/events", null, "issuer-published earnings call",
                "reviewer@example.test", "issuer-page-receipt"));
        assertThat(chosen.status()).isEqualTo(EventService.EvidenceStatus.CONFIRMED);
        assertThat(chosen.sourceKind()).isEqualTo(EventService.SourceKind.ISSUER_CONFIRMED);
        assertThat(chosen.session()).isEqualTo(EventService.EventSession.AFTER_CLOSE);
        assertThat(chosen.confidenceStart()).isEqualTo(chosen.date());
        assertThat(chosen.confidenceEnd()).isEqualTo(chosen.date());
        assertThat(events.history("AAPL")).hasSize(3);

        EventService.EarningsProximity proximity = events.earningsProximity(
                "AAPL", LocalDate.of(2026, 8, 7));
        assertThat(proximity.available()).isTrue();
        assertThat(proximity.likelyBefore()).isTrue();
        assertThat(proximity.note()).contains("CONFIRMED").contains("after close");
    }

    @Test
    void missingEvidencePersistsUnavailableRatherThanClaimingNoEvent() {
        db = TestDb.fresh();
        MarketDataService empty = new MarketDataService(List.of(), List.of(), List.of());
        EventService events = new EventService(empty, db, CLOCK);
        EventService.EventEvidence unavailable = events.earnings("ZZZZZZ");

        assertThat(unavailable.status()).isEqualTo(EventService.EvidenceStatus.UNAVAILABLE);
        assertThat(unavailable.available()).isFalse();
        assertThat(unavailable.date()).isNull();
        assertThat(unavailable.note()).contains("not a no-event claim");
        assertThat(events.history("ZZZZZZ")).singleElement()
                .extracting(EventService.EventEvidence::status)
                .isEqualTo(EventService.EvidenceStatus.UNAVAILABLE);
        assertThat(events.earningsLikelyBefore("ZZZZZZ", LocalDate.of(2026, 8, 21))).isFalse();
    }

    @Test
    void issuerAcquisitionCannotBypassProviderPoliteness() {
        db = TestDb.fresh();
        EventService seed = new EventService(new MarketDataService(List.of(), List.of(), List.of()), db, CLOCK);
        seed.importReviewed(new EventService.ReviewedEvent("AAPL", EventService.EvidenceStatus.ESTIMATED,
                LocalDate.of(2026, 8, 3), EventService.EventSession.UNKNOWN, null, null,
                EventService.SourceKind.REVIEWED_IMPORT, "Reviewed calendar",
                "https://calendar.example/aapl", null, "reviewed calendar", "reviewer", "row"));

        AtomicInteger issuerCalls = new AtomicInteger();
        EventService.IssuerEventProvider issuer = symbol -> {
            issuerCalls.incrementAndGet();
            return Optional.of(new EventService.IssuerEvent(LocalDate.of(2026, 7, 22),
                    EventService.EventSession.AFTER_CLOSE, "Issuer IR", "https://issuer.example/ir",
                    CLOCK.instant(), "issuer"));
        };
        ProviderPoliteness issuerGate = new ProviderPoliteness("issuer-event-test", 1, 0, 60_000);
        issuerGate.trip();
        EventService events = new EventService(new MarketDataService(List.of(), List.of(), List.of()), db,
                CLOCK, List.of(issuer), issuerGate,
                new ProviderPoliteness("sec-event-test", 1, 0, 60_000));

        assertThat(events.earnings("AAPL").sourceKind()).isEqualTo(EventService.SourceKind.REVIEWED_IMPORT);
        assertThat(issuerCalls).hasValue(0);
    }

    private static MarketDataService marketWithSec(AtomicInteger calls) {
        NewsFilingsProvider filings = new NewsFilingsProvider() {
            @Override public String name() { return "SEC-test"; }
            @Override public List<NewsItem> news(String symbol) {
                calls.incrementAndGet();
                return List.of(
                        filing(symbol, "10-Q filed", "2026-05-01T18:00:00Z", "q2"),
                        filing(symbol, "10-K filed", "2026-01-30T18:00:00Z", "k"),
                        filing(symbol, "10-Q filed", "2025-10-30T18:00:00Z", "q3"));
            }
        };
        return new MarketDataService(List.of(), List.of(filings), List.of());
    }

    private static NewsItem filing(String symbol, String headline, String instant, String tail) {
        return new NewsItem(symbol, headline, "SEC EDGAR", "https://sec.example/" + tail,
                Instant.parse(instant).toEpochMilli());
    }
}
