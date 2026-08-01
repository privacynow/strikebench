package io.liftandshift.strikebench.pricing;

import io.liftandshift.strikebench.market.OptionTime;
import io.liftandshift.strikebench.util.Numbers;

/**
 * The single quantitative owner of an IV-implied move.
 *
 * <p>There are two legitimate clocks in the product and they are deliberately different types:
 * a listed contract uses its normalized {@link OptionTime.Measure calendar-time result}, while a
 * scenario cone projects a quoted IV over an explicitly declared number of trading sessions.
 * Callers cannot pass a bare {@code double years} or silently exchange one clock for the other.</p>
 */
public final class ExpectedMove {
    private ExpectedMove() {}

    /** The options-market annualization convention attached to the requested quantity. */
    public enum ClockBasis {
        LISTED_EXPIRY_CALENDAR,
        SCENARIO_TRADING_SESSIONS
    }

    /**
     * A declared scenario horizon. This is not an option expiration and therefore cannot be used
     * by the listed-expiry overloads.
     */
    public record ScenarioHorizon(int tradingSessions) {
        public ScenarioHorizon {
            if (tradingSessions < 1) {
                throw new IllegalArgumentException("scenario horizon needs at least one trading session");
            }
        }

        public double years() {
            return tradingSessions / LogReturnStatistics.TRADING_SESSIONS_PER_YEAR;
        }
    }

    /**
     * One risk-neutral lognormal range and its exact clock result. The range is a market-pricing
     * lens, not a forecast.
     */
    public record Range(ClockBasis clockBasis, double modelYears, double expectedMoveFraction,
                        double p16, double p50, double p84, String timeBasis) {
        public Range {
            if (clockBasis == null) throw new IllegalArgumentException("expected-move clock is required");
            if (!(modelYears > 0) || !Double.isFinite(modelYears)) {
                throw new IllegalArgumentException("expected-move model years must be positive");
            }
            if (!(expectedMoveFraction > 0) || !Double.isFinite(expectedMoveFraction)) {
                throw new IllegalArgumentException("expected move must be positive");
            }
            if (timeBasis == null || timeBasis.isBlank()) {
                throw new IllegalArgumentException("expected-move time basis is required");
            }
        }
    }

    /** IV-implied one-sigma move to one listed expiry. Null means the result is insufficient. */
    public static Double fraction(Double atmIv, OptionTime.Measure expiryTime) {
        if (!validIv(atmIv) || expiryTime == null || !expiryTime.hasModelTime()) return null;
        return atmIv * Math.sqrt(expiryTime.years());
    }

    /** Percent form of {@link #fraction(Double, OptionTime.Measure)}. */
    public static Double percent(Double atmIv, OptionTime.Measure expiryTime) {
        Double fraction = fraction(atmIv, expiryTime);
        return fraction == null ? null : fraction * 100.0;
    }

    /**
     * Risk-neutral range to the listed contract's expiration. Calendar years come only from the
     * normalized option-time result.
     */
    public static Range listedExpiryRange(double spot, Double atmIv,
                                          OptionTime.Measure expiryTime, double riskFreeRate) {
        if (!validInputs(spot, atmIv, expiryTime == null ? null : expiryTime.years(), riskFreeRate)) {
            return null;
        }
        return range(spot, atmIv, expiryTime.years(), riskFreeRate,
                ClockBasis.LISTED_EXPIRY_CALENDAR, expiryTime.basis());
    }

    /**
     * Risk-neutral range over a scenario canvas. Trading-session years are explicit and cannot be
     * mistaken for a listed contract's calendar maturity.
     */
    public static Range scenarioHorizonRange(double spot, Double atmIv,
                                             ScenarioHorizon horizon, double riskFreeRate) {
        if (horizon == null || !validInputs(spot, atmIv, horizon.years(), riskFreeRate)) return null;
        return range(spot, atmIv, horizon.years(), riskFreeRate,
                ClockBasis.SCENARIO_TRADING_SESSIONS,
                horizon.tradingSessions() + " declared trading sessions / "
                        + (int) LogReturnStatistics.TRADING_SESSIONS_PER_YEAR
                        + " (scenario-horizon convention)");
    }

    private static Range range(double spot, double atmIv, double years, double riskFreeRate,
                               ClockBasis clockBasis, String timeBasis) {
        LognormalTerminal terminal = LognormalTerminal.of(spot, atmIv, years, riskFreeRate);
        // Exact z-score used by the pre-existing p15.865/p84.135 market range.
        double width = terminal.sd() * 0.994457883209753;
        return new Range(clockBasis, years, atmIv * Math.sqrt(years),
                Numbers.round2(Math.exp(terminal.mu() - width)),
                Numbers.round2(Math.exp(terminal.mu())),
                Numbers.round2(Math.exp(terminal.mu() + width)),
                timeBasis);
    }

    private static boolean validInputs(double spot, Double atmIv, Double years, double riskFreeRate) {
        return spot > 0 && Double.isFinite(spot) && validIv(atmIv)
                && years != null && years > 0 && Double.isFinite(years)
                && Double.isFinite(atmIv) && Double.isFinite(riskFreeRate);
    }

    private static boolean validIv(Double atmIv) {
        return atmIv != null && atmIv > 0 && Double.isFinite(atmIv);
    }
}
