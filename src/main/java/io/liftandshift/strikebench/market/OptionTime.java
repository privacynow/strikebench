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
        LocalDate expiry = legs.stream().filter(l -> !l.isStock()).map(Leg::expiration)
                .filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        return toExpiry(today, expiry);
    }

    public static Measure toExpiry(LocalDate today, LocalDate expiry) {
        if (expiry == null) return new Measure(0, 0, 0.5 / 365.0, "no option legs");
        long calendarDays = Math.max(0, ChronoUnit.DAYS.between(today, expiry));
        int sessions = MarketHours.tradingDaysBetween(today, expiry);
        // Vendors annualize listed-option IV on calendar time. Trading sessions describe the
        // near-expiry regime and management urgency, but do not silently change the IV clock.
        double years = Math.max(calendarDays, 0.5) / 365.0;
        String basis = calendarDays + " calendar days / 365 (chain-IV convention) · "
                + sessions + " trading session" + (sessions == 1 ? "" : "s") + " remain";
        return new Measure(sessions, calendarDays, years, basis);
    }
}
