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

    @Test
    void ironCondorUsesProbabilityBoundariesInsteadOfCollapsingAroundSpotForMaximumCredit() {
        OptionChain chain = new OptionChain("AMD", EXPIRATION, bd("100"),
                List.of(
                        quote(OptionType.CALL, "102", "5.00", "5.10", 0.48),
                        quote(OptionType.CALL, "105", "2.00", "2.10", 0.20),
                        quote(OptionType.CALL, "110", "0.50", "0.60", 0.05)
                ),
                List.of(
                        quote(OptionType.PUT, "90", "0.50", "0.60", -0.05),
                        quote(OptionType.PUT, "95", "2.00", "2.10", -0.20),
                        quote(OptionType.PUT, "98", "5.00", "5.10", -0.48)
                ),
                1L, "test", Freshness.REALTIME);

        StrategyBuilder.Built built = StrategyBuilder.build(
                StrategyFamily.IRON_CONDOR, chain, null, bd("100"));

        assertThat(built).isNotNull();
        assertThat(leg(built, OptionType.PUT, LegAction.SELL).strike()).isEqualByComparingTo("95");
        assertThat(leg(built, OptionType.CALL, LegAction.SELL).strike()).isEqualByComparingTo("105");
        assertThat(leg(built, OptionType.PUT, LegAction.BUY).strike()).isEqualByComparingTo("90");
        assertThat(leg(built, OptionType.CALL, LegAction.BUY).strike()).isEqualByComparingTo("110");
    }

    @Test
    void ironCondorRetriesPreferredWingsWhenTheFirstExactPackageIsEconomicDust() {
        OptionChain chain = new OptionChain("AMD", EXPIRATION, bd("100"),
                List.of(
                        quote(OptionType.CALL, "102.5", "1.20", "1.25", 0.45),
                        quote(OptionType.CALL, "105", "1.00", "1.05", 0.20),
                        quote(OptionType.CALL, "107.5", "0.85", "0.90", 0.10),
                        quote(OptionType.CALL, "110", "0.80", "0.88", 0.05)
                ),
                List.of(
                        quote(OptionType.PUT, "90", "0.80", "0.88", -0.05),
                        quote(OptionType.PUT, "92.5", "0.85", "0.90", -0.10),
                        quote(OptionType.PUT, "95", "1.00", "1.05", -0.20),
                        quote(OptionType.PUT, "97.5", "1.20", "1.25", -0.45)
                ),
                1L, "test", Freshness.REALTIME);

        StrategyBuilder.Built built = StrategyBuilder.build(
                StrategyFamily.IRON_CONDOR, chain, null, bd("100"));

        // The preferred two-listed-strike wings collect $0.24 against $5.00 of width (4.8%),
        // which is positive but below the shared economic-comparison floor.
        assertThat(bd("0.24").doubleValue() / bd("5.00").doubleValue())
                .isLessThan(StrategyBuilder.MIN_IRON_CONDOR_CREDIT_TO_WIDTH);
        assertThat(built).isNotNull();
        assertThat(leg(built, OptionType.PUT, LegAction.SELL).strike()).isEqualByComparingTo("95");
        assertThat(leg(built, OptionType.CALL, LegAction.SELL).strike()).isEqualByComparingTo("105");
        assertThat(leg(built, OptionType.PUT, LegAction.BUY).strike()).isEqualByComparingTo("92.5");
        assertThat(leg(built, OptionType.CALL, LegAction.BUY).strike()).isEqualByComparingTo("107.5");

        BigDecimal credit = executableCredit(built);
        BigDecimal widestWing = widestWing(built);
        assertThat(credit).isEqualByComparingTo("0.20");
        assertThat(widestWing).isEqualByComparingTo("2.5");
        assertThat(credit.doubleValue() / widestWing.doubleValue())
                .isGreaterThanOrEqualTo(StrategyBuilder.MIN_IRON_CONDOR_CREDIT_TO_WIDTH);
    }

    private static BigDecimal executableCredit(StrategyBuilder.Built built) {
        BigDecimal credit = BigDecimal.ZERO;
        for (int i = 0; i < built.legs().size(); i++) {
            Leg leg = built.legs().get(i);
            OptionQuote quote = built.quotes().get(i);
            BigDecimal price = leg.action() == LegAction.SELL ? quote.bid() : quote.ask();
            credit = credit.add(leg.action() == LegAction.SELL ? price : price.negate());
        }
        return credit;
    }

    private static BigDecimal widestWing(StrategyBuilder.Built built) {
        BigDecimal putWidth = leg(built, OptionType.PUT, LegAction.SELL).strike()
                .subtract(leg(built, OptionType.PUT, LegAction.BUY).strike());
        BigDecimal callWidth = leg(built, OptionType.CALL, LegAction.BUY).strike()
                .subtract(leg(built, OptionType.CALL, LegAction.SELL).strike());
        return putWidth.max(callWidth);
    }

    private static Leg leg(StrategyBuilder.Built built, OptionType type, LegAction action) {
        return built.legs().stream()
                .filter(leg -> leg.type() == type && leg.action() == action)
                .findFirst()
                .orElseThrow();
    }

    private static OptionQuote quote(OptionType type, String strike, String bid, String ask) {
        return quote(type, strike, bid, ask, null);
    }

    private static OptionQuote quote(OptionType type, String strike, String bid, String ask, Double delta) {
        return new OptionQuote("AMD", "AMD-" + type + '-' + strike, type, bd(strike), EXPIRATION,
                bd(bid), bd(ask), null, 100L, 100L, 0.35, delta, null, null, null,
                1L, "test", Freshness.REALTIME);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
