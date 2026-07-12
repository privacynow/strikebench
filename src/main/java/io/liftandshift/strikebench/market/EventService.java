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

    private final MarketDataService market;
    private final Clock clock;
    private final Cache<String, Optional<EarningsEstimate>> cache =
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

    /** True when the estimated earnings window overlaps [today, expiration]. */
    public boolean earningsLikelyBefore(String symbol, LocalDate expiration) {
        if (expiration == null) return false;
        LocalDate today = LocalDate.now(clock);
        return nextEarnings(symbol)
                .map(e -> !e.estimated().minusDays(e.windowDays()).isAfter(expiration)
                        && !e.estimated().plusDays(e.windowDays()).isBefore(today))
                .orElse(false);
    }

    private Optional<EarningsEstimate> estimate(String sym) {
        List<LocalDate> reports = new ArrayList<>();
        try {
            for (NewsItem n : market.news(sym)) {
                if (!"SEC EDGAR".equals(n.source()) || n.headline() == null) continue;
                String h = n.headline();
                if (h.startsWith("10-Q") || h.startsWith("10-K")) {
                    reports.add(LocalDate.ofInstant(java.time.Instant.ofEpochMilli(n.publishedEpochMs()), ZoneOffset.UTC));
                }
            }
        } catch (RuntimeException e) {
            return Optional.empty(); // no filings reachable — no estimate, never a guess
        }
        if (reports.size() < 2) return Optional.empty();
        reports.sort(java.util.Comparator.reverseOrder());

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
}
