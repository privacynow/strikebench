package io.liftandshift.strikebench.market;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.liftandshift.strikebench.model.NewsItem;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The EVENT MODEL: estimated earnings dates from the issuer's own SEC filing cadence — never from
 * headline keywords. Quarterly reports (10-Q/10-K) land on a stable rhythm a few days after each
 * earnings release; the median gap between recent report filings projects the next window. Every
 * estimate is labeled ESTIMATED and {@code confirmed=false} — a licensed calendar source can later
 * supply confirmed dates through the same shape. Ex-dividend has NO keyless source and is reported
 * as honestly unavailable rather than guessed.
 *
 * The MU incident is the reason this class exists: a keyword heuristic warned "earnings expected
 * before expiration" when Micron had reported two weeks earlier.
 */
public final class EventService {

    /** confirmed=false means filing-cadence ESTIMATE; a real calendar source may set true. */
    public record EarningsEstimate(LocalDate estimated, int windowDays, String basis, boolean confirmed) {}

    /**
     * One canonical interpretation of an earnings estimate against a dated trade horizon.
     * {@code available=false} is deliberately distinct from {@code likelyBefore=false}: thin or
     * unreachable filing evidence is not evidence that no event is near.
     */
    public record EarningsProximity(boolean available, boolean likelyBefore,
                                    EarningsEstimate estimate, String note) {}

    private final MarketDataService market;
    private final Clock clock;
    private final Cache<String, Optional<EarningsEstimate>> cache =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(6)).maximumSize(500).build();
    private final Cache<String, List<LocalDate>> quarterlyReports =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(6)).maximumSize(500).build();

    public EventService(MarketDataService market, Clock clock) {
        this.market = market;
        this.clock = clock;
    }

    /** Next estimated earnings window, or empty when the filing history is too thin to project. */
    public Optional<EarningsEstimate> nextEarnings(String symbol) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (sym.isEmpty()) return Optional.empty();
        return cache.get(sym, this::estimate);
    }

    /**
     * Dated issuer 10-Q/10-K filings behind the canonical cadence estimate, newest first.
     * Scenario templates consume this instead of re-parsing NewsItem headlines in a second
     * subsystem. An empty list means the SEC evidence is unreachable or absent.
     */
    public List<LocalDate> quarterlyReportDates(String symbol) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (sym.isEmpty()) return List.of();
        return quarterlyReports.get(sym, this::loadQuarterlyReportDates);
    }

    /** True when the estimated earnings window overlaps [today, expiration]. */
    public boolean earningsLikelyBefore(String symbol, LocalDate expiration) {
        return earningsProximity(symbol, expiration).likelyBefore();
    }

    /**
     * Returns the evidence-bearing proximity used by evaluation, guardrails, Research, and Scout.
     * No headline scanner is consulted: this is the same SEC filing-cadence estimate returned by
     * {@link #nextEarnings(String)}.
     */
    public EarningsProximity earningsProximity(String symbol, LocalDate throughDate) {
        if (throughDate == null) {
            return new EarningsProximity(false, false, null,
                    "earnings proximity unavailable — the package has no dated expiration");
        }
        Optional<EarningsEstimate> estimate = nextEarnings(symbol);
        if (estimate.isEmpty()) {
            return new EarningsProximity(false, false, null,
                    "earnings proximity unavailable — not enough reachable SEC quarterly filings "
                            + "to project a cadence; this is not a no-event claim");
        }
        EarningsEstimate event = estimate.get();
        LocalDate today = LocalDate.now(clock);
        boolean likelyBefore = !event.estimated().minusDays(event.windowDays()).isAfter(throughDate)
                && !event.estimated().plusDays(event.windowDays()).isBefore(today);
        String status = likelyBefore
                ? "earnings ESTIMATED near " + event.estimated() + " (±" + event.windowDays()
                    + " days) within this package's life"
                : "next earnings ESTIMATED near " + event.estimated() + " (±" + event.windowDays()
                    + " days), outside this package's life";
        return new EarningsProximity(true, likelyBefore, event,
                status + "; unconfirmed, based on " + event.basis());
    }

    private Optional<EarningsEstimate> estimate(String sym) {
        List<LocalDate> reports = new ArrayList<>(quarterlyReportDates(sym));
        if (reports.size() < 2) return Optional.empty();

        // Median gap between consecutive quarterly reports, sanity-clamped to a quarter-ish rhythm.
        List<Long> gaps = new ArrayList<>();
        for (int i = 0; i + 1 < reports.size(); i++) {
            long g = java.time.temporal.ChronoUnit.DAYS.between(reports.get(i + 1), reports.get(i));
            if (g >= 30) gaps.add(g); // amendments/duplicates filed days apart are not a cadence
        }
        if (gaps.isEmpty()) return Optional.empty();
        gaps.sort(Long::compareTo);
        long gap = Math.clamp(gaps.get(gaps.size() / 2), 60, 120);

        LocalDate today = LocalDate.now(clock);
        LocalDate next = reports.getFirst().plusDays(gap);
        int guard = 0;
        while (next.isBefore(today) && guard++ < 8) next = next.plusDays(gap);
        return Optional.of(new EarningsEstimate(next, 7,
                "SEC filing cadence (" + reports.size() + " quarterly reports, ~" + gap + "-day rhythm)", false));
    }

    private List<LocalDate> loadQuarterlyReportDates(String sym) {
        java.util.LinkedHashSet<LocalDate> reports = new java.util.LinkedHashSet<>();
        try {
            for (NewsItem n : market.news(sym)) {
                if (!"SEC EDGAR".equals(n.source()) || n.headline() == null) continue;
                String h = n.headline();
                if (h.startsWith("10-Q") || h.startsWith("10-K")) {
                    reports.add(LocalDate.ofInstant(java.time.Instant.ofEpochMilli(n.publishedEpochMs()), ZoneOffset.UTC));
                }
            }
        } catch (RuntimeException e) {
            return List.of(); // no filings reachable — no estimate, never a guess
        }
        return reports.stream().sorted(java.util.Comparator.reverseOrder()).toList();
    }
}
