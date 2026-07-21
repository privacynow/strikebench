package io.liftandshift.strikebench.strategy;

import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyBuilderTest {

    private static final LocalDate EXPIRATION = LocalDate.of(2026, 7, 24);

    @Test
    void ironCondorAlwaysKeepsTheCanonicalFourStrikeOrdering() {
        OptionChain chain = new OptionChain("AMD", EXPIRATION, bd("100"),
                List.of(
                        quote(OptionType.CALL, "99", "5.00", "5.10"),
                        quote(OptionType.CALL, "105", "3.60", "3.70"),
                        quote(OptionType.CALL, "110", "2.40", "2.50")
                ),
                List.of(
                        quote(OptionType.PUT, "90", "2.40", "2.50"),
                        quote(OptionType.PUT, "95", "3.60", "3.70"),
                        quote(OptionType.PUT, "101", "5.00", "5.10")
                ),
                1L, "test", Freshness.REALTIME);

        StrategyBuilder.Built built = StrategyBuilder.build(
                StrategyFamily.IRON_CONDOR, chain, null, bd("100"));

        assertThat(built).isNotNull();
        Leg shortPut = leg(built, OptionType.PUT, LegAction.SELL);
        Leg longPut = leg(built, OptionType.PUT, LegAction.BUY);
        Leg shortCall = leg(built, OptionType.CALL, LegAction.SELL);
        Leg longCall = leg(built, OptionType.CALL, LegAction.BUY);
        assertThat(longPut.strike()).isLessThan(shortPut.strike());
        assertThat(shortPut.strike()).isLessThan(shortCall.strike());
        assertThat(shortCall.strike()).isLessThan(longCall.strike());
    }

    private static Leg leg(StrategyBuilder.Built built, OptionType type, LegAction action) {
        return built.legs().stream()
                .filter(leg -> leg.type() == type && leg.action() == action)
                .findFirst()
                .orElseThrow();
    }

    private static OptionQuote quote(OptionType type, String strike, String bid, String ask) {
        return new OptionQuote("AMD", "AMD-" + type + '-' + strike, type, bd(strike), EXPIRATION,
                bd(bid), bd(ask), null, 100L, 100L, 0.35, null, null, null, null,
                1L, "test", Freshness.REALTIME);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
