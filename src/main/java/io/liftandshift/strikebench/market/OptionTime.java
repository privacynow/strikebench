package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.model.Leg;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** One disclosed time convention shared by ticket, outcome and risk-neutral analytics. */
public final class OptionTime {
    private OptionTime() {}

    public record Measure(int sessions, long calendarDays, double years, String basis) {}

    public static Measure nearest(List<Leg> legs, LocalDate today) {
        return toExpiry(today, nearestExpiry(legs));
    }

    /**
     * THE nearest option expiry in a package, or null when it holds no dated option leg. Exposed
     * because callers that must NAME the expiry (the market-implied range's own basis line) were
     * otherwise re-deriving this same min-of-expirations beside {@link #nearest}.
     */
    public static LocalDate nearestExpiry(List<Leg> legs) {
        if (legs == null) return null;
        return legs.stream().filter(l -> !l.isStock()).map(Leg::expiration)
                .filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
    }

    public static Measure toExpiry(LocalDate today, LocalDate expiry) {
        if (expiry == null) return new Measure(0, 0, 0.5 / 365.0, "no option legs");
        long calendarDays = Math.max(0, ChronoUnit.DAYS.between(today, expiry));
        int sessions = MarketHours.tradingDaysBetween(today, expiry);
        return measure(sessions, calendarDays);
    }

    /**
     * Rebuilds the measure from a receipt that already recorded both units, for consumers that hold
     * a persisted receipt rather than the expiry date. It never infers a session count from
     * calendar days — {@link MarketHours} remains the only place sessions are counted.
     */
    public static Measure ofRecordedUnits(int sessions, Integer calendarDays) {
        return measure(sessions, calendarDays == null ? sessions : calendarDays);
    }

    private static Measure measure(int sessions, long calendarDays) {
        // Vendors annualize listed-option IV on calendar time. Trading sessions describe the
        // near-expiry regime and management urgency, but do not silently change the IV clock.
        double years = Math.max(calendarDays, 0.5) / 365.0;
        String basis = calendarDays + " calendar days / 365 (chain-IV convention) · "
                + sessions + " trading session" + (sessions == 1 ? "" : "s") + " remain";
        return new Measure(sessions, calendarDays, years, basis);
    }
}
