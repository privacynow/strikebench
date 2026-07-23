package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.model.OptionQuote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;

/** Resolves one lane-owned ATM volatility input at a requested trading-session horizon. */
public final class MarketVolatilityResolver {
    private final MarketDataService market;
    private final Clock clock;

    public MarketVolatilityResolver(MarketDataService market, Clock clock) {
        this.market = market;
        this.clock = clock;
    }

    public Double atmIv(String symbol, String worldId, int horizonSessions) {
        SimulationEngine.MarketVolInput input = resolve(symbol, worldId, horizonSessions);
        return input == null ? null : input.atmIv();
    }

    public SimulationEngine.MarketVolInput resolve(String symbol, String worldId, int horizonSessions) {
        try {
            var expirations = market.expirations(symbol, worldId);
            if (expirations.isEmpty()) return null;

            LocalDate laneToday = market.laneToday(worldId, clock);
            LocalDate target = MarketHours.tradingDateAfter(laneToday, Math.max(1, horizonSessions));
            LocalDate expiration = expirations.stream()
                    .min(Comparator.comparingLong(date -> Math.abs(ChronoUnit.DAYS.between(date, target))))
                    .orElse(null);
            var chain = expiration == null ? null : market.chain(symbol, expiration, worldId).orElse(null);
            if (chain == null || chain.isEmpty() || chain.underlyingPrice() == null) return null;

            BigDecimal spot = chain.underlyingPrice();
            Double iv = chain.calls().stream()
                    .filter(option -> option.iv() != null && option.iv() > 0.01)
                    .min(Comparator.comparingDouble(option -> Math.abs(option.strike().subtract(spot).doubleValue())))
                    .map(OptionQuote::iv)
                    .orElse(null);
            if (iv == null) return null;

            int calendarDays = Math.max(0, (int) ChronoUnit.DAYS.between(laneToday, expiration));
            return new SimulationEngine.MarketVolInput(iv, expiration, calendarDays);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
