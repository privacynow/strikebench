package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.model.Leg;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * One typed, disclosed option-time receipt shared by ticket, outcome, lifecycle, and model
 * analytics.
 *
 * <p>A zero calendar-day count is not enough to describe an option. Before the final bell it is
 * live 0DTE and receives the one explicitly disclosed half-day model fraction; at or after the
 * final bell it is expired and receives no model time at all. Only an {@link Instant} from the
 * selected market lane can make that distinction, so date-only and persisted-unit factories are
 * deliberately {@link State#PARTIAL}.</p>
 */
public final class OptionTime {
    private OptionTime() {}

    public static final double LIVE_0DTE_MODEL_YEARS = 0.5 / 365.0;

    public enum State {
        LIVE,
        LIVE_0DTE,
        EXPIRED,
        NO_OPTION,
        PARTIAL
    }

    /**
     * The one server-owned choice of a listed expiration for a trading-session horizon.
     *
     * <p>{@code requestedHorizonSessions == null} means the caller made no horizon declaration,
     * so the nearest active listed contract is selected. A declared horizon is matched against
     * the exchange-calendar session distance carried by {@link Measure}; calendar-day or browser
     * weekday approximations never participate.</p>
     */
    public record ListedExpirationSelection(
            LocalDate expiration,
            Integer requestedHorizonSessions,
            Integer tradingSessions,
            Integer calendarDays,
            String basis
    ) {
        public ListedExpirationSelection {
            if (basis == null || basis.isBlank()) {
                throw new IllegalArgumentException("listed-expiration selection basis is required");
            }
            if (expiration == null
                    && (tradingSessions != null || calendarDays != null)) {
                throw new IllegalArgumentException(
                        "an unavailable listed expiration cannot carry distances");
            }
        }
    }

    /**
     * Selects one active listed expiration using the same market-lane clock and holiday-aware
     * session distance used by every other option-time consumer.
     */
    public static ListedExpirationSelection selectListedExpiration(
            List<LocalDate> expirations,
            Instant laneNow,
            Integer requestedHorizonSessions
    ) {
        if (laneNow == null) throw new IllegalArgumentException("market-lane instant is required");
        if (requestedHorizonSessions != null
                && (requestedHorizonSessions < 1 || requestedHorizonSessions > 756)) {
            throw new IllegalArgumentException(
                    "horizonSessions must be between 1 and 756");
        }

        List<Measure> active = expirations == null ? List.of() : expirations.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(expiration -> toExpiry(laneNow, expiration))
                .filter(Measure::live)
                .sorted(Comparator.comparing(Measure::expiration))
                .toList();
        if (active.isEmpty()) {
            return new ListedExpirationSelection(null, requestedHorizonSessions,
                    null, null, "no active listed expiration is available");
        }

        Measure selected = requestedHorizonSessions == null
                ? active.getFirst()
                : active.stream()
                        .min(Comparator
                                .comparingInt((Measure measure) ->
                                        Math.abs(measure.sessions()
                                                - requestedHorizonSessions))
                                .thenComparing(Measure::expiration))
                        .orElseThrow();
        String basis = requestedHorizonSessions == null
                ? "nearest active listed expiration because no horizon was declared"
                : "closest active listed expiration to the declared "
                        + requestedHorizonSessions
                        + " trading sessions; distance uses the exchange trading calendar";
        return new ListedExpirationSelection(
                selected.expiration(),
                requestedHorizonSessions,
                selected.sessions(),
                Math.toIntExact(selected.calendarDays()),
                basis);
    }

    /**
     * {@code sessions == -1} and {@code calendarDays == -1} mean that unit was not supplied.
     * {@code years == null} means no model time exists; expired/no-option and sessions-only
     * persisted receipts therefore cannot accidentally enter a pricer through a substituted zero.
     */
    public record Measure(State state, int sessions, long calendarDays, Double years,
                          Instant asOf, LocalDate expiration, String basis) {
        public Measure {
            state = state == null ? State.PARTIAL : state;
            if (sessions < -1 || calendarDays < -1) {
                throw new IllegalArgumentException("option-time units cannot be below -1");
            }
            if (years != null && (!Double.isFinite(years) || years <= 0)) {
                throw new IllegalArgumentException("model years must be positive and finite");
            }
            if ((state == State.EXPIRED || state == State.NO_OPTION) && years != null) {
                throw new IllegalArgumentException(state + " option time cannot carry model years");
            }
            if ((state == State.LIVE || state == State.LIVE_0DTE) && (asOf == null || expiration == null)) {
                throw new IllegalArgumentException("live option time requires lane instant and expiration");
            }
            if (basis == null || basis.isBlank()) {
                throw new IllegalArgumentException("option-time basis is required");
            }
        }

        public boolean hasModelTime() {
            return years != null;
        }

        public boolean hasManagementClock() {
            return sessions >= 0 && state != State.EXPIRED && state != State.NO_OPTION;
        }

        public boolean live() {
            return state == State.LIVE || state == State.LIVE_0DTE;
        }

        /**
         * Simple annualized percentage for an option-period cash fact over a positive denominator.
         *
         * <p>This is the one carry/return clock: listed-option IV, candidate premium carry,
         * lifecycle remaining carry, and theoretical return-on-capital all use this receipt's
         * disclosed calendar-time fraction. Trading sessions remain a management-urgency fact and
         * never silently replace the market's annualization convention.</p>
         */
        public Double annualizedSimplePercent(long periodCents, long denominatorCents) {
            if (!hasModelTime() || denominatorCents <= 0) return null;
            return 100.0 * periodCents / denominatorCents / years;
        }

        /** Annualizes an already-computed period percentage through the same option-time clock. */
        public Double annualizedPeriodPercent(Double periodPercent) {
            if (!hasModelTime() || periodPercent == null || !Double.isFinite(periodPercent)) return null;
            return periodPercent / years;
        }
    }

    public static Measure nearest(List<Leg> legs, LocalDate today) {
        return toExpiry(today, nearestExpiry(legs));
    }

    /** Market-lane-aware package clock. Production financial consumers use this overload. */
    public static Measure nearest(List<Leg> legs, Instant laneNow) {
        return toExpiry(laneNow, nearestExpiry(legs));
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

    public static Measure toExpiry(Instant laneNow, LocalDate expiry) {
        if (laneNow == null) throw new IllegalArgumentException("market-lane instant is required");
        LocalDate laneDate = LocalDate.ofInstant(laneNow, MarketHours.EASTERN);
        if (expiry == null) {
            return new Measure(State.NO_OPTION, 0, 0, null, laneNow, null,
                    "no option legs · no option model clock");
        }
        long rawDays = ChronoUnit.DAYS.between(laneDate, expiry);
        if (rawDays < 0 || MarketHours.contractDead(expiry, laneNow)) {
            return new Measure(State.EXPIRED, 0, Math.max(0, rawDays), null, laneNow, expiry,
                    "expired at the option market's final bell · no model time remains");
        }
        int sessions = MarketHours.tradingDaysBetween(laneDate, expiry);
        if (rawDays == 0) {
            return new Measure(State.LIVE_0DTE, sessions, 0, LIVE_0DTE_MODEL_YEARS,
                    laneNow, expiry,
                    "live 0DTE before the final bell · 0.5 calendar day / 365 model convention"
                            + " · " + sessions + " trading sessions remain");
        }
        return liveMeasure(sessions, rawDays, laneNow, expiry);
    }

    /**
     * Date-only compatibility boundary. The receipt is always partial because a date cannot prove
     * the selected market lane's instant or whether the final bell has passed.
     */
    public static Measure toExpiry(LocalDate today, LocalDate expiry) {
        if (today == null) throw new IllegalArgumentException("option-time date is required");
        if (expiry == null) {
            return new Measure(State.NO_OPTION, 0, 0, null, null, null,
                    "no option legs · no option model clock");
        }
        long rawDays = ChronoUnit.DAYS.between(today, expiry);
        if (rawDays < 0) {
            return new Measure(State.EXPIRED, 0, 0, null, null, expiry,
                    "expiration precedes the supplied date · no model time remains");
        }
        int sessions = MarketHours.tradingDaysBetween(today, expiry);
        double years = rawDays == 0 ? LIVE_0DTE_MODEL_YEARS : rawDays / 365.0;
        State state = State.PARTIAL;
        String prefix = rawDays == 0
                ? "same-day final-bell state unavailable from a date-only receipt"
                : "future expiry from a date-only receipt";
        return new Measure(state, sessions, rawDays, years, null, expiry,
                prefix + " · " + rawDays + " calendar days / 365 (chain-IV convention) · "
                        + sessions + " trading session" + (sessions == 1 ? "" : "s") + " remain");
    }

    /**
     * End-of-session valuation clock for historical replay and dated counterfactuals.
     *
     * <p>This is intentionally different from {@link #toExpiry(LocalDate, LocalDate)}: a daily
     * close on expiration day is already at the contract's terminal value, so it receives no
     * half-day 0DTE model fraction. Future expirations retain the listed-option calendar clock.
     * Naming this boundary prevents replay kernels from each re-spelling {@code days / 365} and
     * disagreeing about expiration day.</p>
     */
    public static Measure atSessionClose(LocalDate asOf, LocalDate expiry) {
        if (asOf == null) throw new IllegalArgumentException("session-close date is required");
        if (expiry == null) {
            return new Measure(State.NO_OPTION, 0, 0, null, null, null,
                    "no option legs · no option model clock");
        }
        long calendarDays = ChronoUnit.DAYS.between(asOf, expiry);
        if (calendarDays <= 0) {
            return new Measure(State.EXPIRED, 0, Math.max(0, calendarDays), null, null, expiry,
                    "valued at or after the expiration-session close · terminal value"
                            + " · no model time remains");
        }
        int sessions = MarketHours.tradingDaysBetween(asOf, expiry);
        double years = calendarDays / 365.0;
        return new Measure(State.PARTIAL, sessions, calendarDays, years, null, expiry,
                calendarDays + " calendar days / 365 from the valuation-session close"
                        + " · " + sessions + " trading session" + (sessions == 1 ? "" : "s")
                        + " remain");
    }

    /**
     * Rebuilds the measure from a receipt that already recorded both units, for consumers that hold
     * a persisted receipt rather than the expiry date. It never infers a session count from
     * calendar days — {@link MarketHours} remains the only place sessions are counted.
     */
    public static Measure ofRecordedUnits(int sessions, Integer calendarDays) {
        if (sessions < 0) throw new IllegalArgumentException("recorded sessions cannot be negative");
        if (calendarDays == null) {
            return new Measure(State.PARTIAL, sessions, -1, null, null, null,
                    sessions + " recorded trading session" + (sessions == 1 ? "" : "s")
                            + " remain · calendar days and model years unavailable");
        }
        long days = Math.max(0, calendarDays);
        double years = days == 0 ? LIVE_0DTE_MODEL_YEARS : days / 365.0;
        return new Measure(State.PARTIAL, sessions, days, years, null, null,
                days + " recorded calendar days / 365 (chain-IV convention) · "
                        + sessions + " recorded trading session" + (sessions == 1 ? "" : "s")
                        + " remain · final-bell state unavailable");
    }

    /**
     * Compatibility boundary for callers that recorded only the calendar-day unit. This does not
     * invent a trading-session count: {@code sessions == -1} means that unit was not supplied.
     * New market-aware code must use {@link #toExpiry(LocalDate, LocalDate)}.
     */
    public static Measure ofCalendarDays(int calendarDays) {
        long days = Math.max(0, calendarDays);
        double years = days == 0 ? LIVE_0DTE_MODEL_YEARS : days / 365.0;
        return new Measure(State.PARTIAL, -1, days, years, null, null,
                days + " recorded calendar days / 365 (chain-IV convention)"
                        + " · trading sessions unavailable · final-bell state unavailable");
    }

    private static Measure liveMeasure(int sessions, long calendarDays,
                                       Instant asOf, LocalDate expiry) {
        // Vendors annualize listed-option IV on calendar time. Trading sessions describe the
        // near-expiry regime and management urgency, but do not silently change the IV clock.
        double years = calendarDays / 365.0;
        String basis = calendarDays + " calendar days / 365 (chain-IV convention) · "
                + sessions + " trading session" + (sessions == 1 ? "" : "s") + " remain";
        return new Measure(State.LIVE, sessions, calendarDays, years, asOf, expiry, basis);
    }
}
