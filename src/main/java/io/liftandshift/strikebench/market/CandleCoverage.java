package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.model.Candle;

import java.time.LocalDate;
import java.util.List;

/** Requested-versus-available daily-history coverage, shared by storage and API disclosure. */
public record CandleCoverage(LocalDate requestedFrom, LocalDate requestedTo,
                             LocalDate availableFrom, LocalDate availableTo,
                             int availableSessions, int requestedSessions,
                             int coveragePct, boolean complete) {

    public static CandleCoverage assess(List<Candle> candles, LocalDate from, LocalDate to) {
        int requested = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (MarketHours.isTradingDay(d)) requested++;
        }
        List<LocalDate> dates = candles == null ? List.of() : candles.stream()
                .map(Candle::date)
                .filter(d -> d != null && !d.isBefore(from) && !d.isAfter(to))
                .distinct().sorted().toList();
        int available = dates.size();
        LocalDate first = dates.isEmpty() ? null : dates.getFirst();
        LocalDate last = dates.isEmpty() ? null : dates.getLast();
        int pct = requested == 0 ? (available > 0 ? 100 : 0)
                : Math.min(100, (int) Math.round(available * 100.0 / requested));
        boolean boundaries = first != null && !first.isAfter(from.plusDays(7))
                && !last.isBefore(to.minusDays(7));
        boolean complete = boundaries && available >= Math.max(2, Math.round(requested * 0.9));
        return new CandleCoverage(from, to, first, last, available, requested, pct, complete);
    }
}
