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
import java.util.ArrayList;
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
                        quote(OptionType.CALL, "107.5", "0.85", "0.87", 0.10),
                        quote(OptionType.CALL, "110", "0.80", "0.88", 0.05)
                ),
                List.of(
                        quote(OptionType.PUT, "90", "0.80", "0.88", -0.05),
                        quote(OptionType.PUT, "92.5", "0.85", "0.87", -0.10),
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
        assertThat(credit).isEqualByComparingTo("0.26");
        assertThat(widestWing).isEqualByComparingTo("2.5");
        assertThat(credit.doubleValue() / widestWing.doubleValue())
                .isGreaterThanOrEqualTo(StrategyBuilder.MIN_IRON_CONDOR_CREDIT_TO_WIDTH);
    }

    @Test
    void ironCondorSearchDropsBrokenWingCrumbButRetainsBalancedProtection() {
        OptionChain chain = new OptionChain("SPY", EXPIRATION, bd("748"),
                List.of(
                        quote(OptionType.CALL, "788", "0.80", "0.85", 0.20),
                        quote(OptionType.CALL, "792", "0.35", "0.40", 0.10)
                ),
                List.of(
                        quote(OptionType.PUT, "575", "0.10", "0.20", -0.05),
                        quote(OptionType.PUT, "590", "0.35", "0.50", -0.10),
                        quote(OptionType.PUT, "594", "1.00", "1.05", -0.20)
                ), 1L, "test", Freshness.REALTIME);

        List<StrategyBuilder.Built> alternatives = StrategyBuilder.buildAlternatives(
                StrategyFamily.IRON_CONDOR, chain, null, bd("748"), StrategyBuilder.BuildHints.NONE);

        assertThat(alternatives).isNotEmpty();
        assertThat(alternatives).allSatisfy(built -> {
            BigDecimal putWidth = leg(built, OptionType.PUT, LegAction.SELL).strike()
                    .subtract(leg(built, OptionType.PUT, LegAction.BUY).strike());
            BigDecimal callWidth = leg(built, OptionType.CALL, LegAction.BUY).strike()
                    .subtract(leg(built, OptionType.CALL, LegAction.SELL).strike());
            IronCondorQuality.Assessment quality = IronCondorQuality.assess(
                    putWidth, callWidth, executableCredit(built));
            assertThat(quality.viable()).isTrue();
            assertThat(quality.narrowToWideWing())
                    .isGreaterThanOrEqualTo(IronCondorQuality.MIN_NARROW_TO_WIDE_WING);
        });
        assertThat(alternatives).allSatisfy(built ->
                assertThat(leg(built, OptionType.PUT, LegAction.BUY).strike())
                        .as("the 19-point put wing paired with a 4-point call wing is not retained")
                        .isEqualByComparingTo("590"));
    }

    @Test
    void creditVerticalAlternativesPreserveAnotherShortProbabilityBoundary() {
        OptionChain chain = new OptionChain("AMD", EXPIRATION, bd("100"),
                List.of(
                        quote(OptionType.CALL, "100", "9.80", "10.00", 0.50),
                        quote(OptionType.CALL, "102", "8.20", "8.40", 0.50),
                        quote(OptionType.CALL, "104", "6.50", "6.70", 0.50),
                        quote(OptionType.CALL, "106", "4.70", "4.90", 0.20),
                        quote(OptionType.CALL, "108", "3.00", "3.20", 0.20),
                        quote(OptionType.CALL, "110", "1.70", "1.90", 0.10),
                        quote(OptionType.CALL, "112", "0.80", "0.95", 0.05)
                ), List.of(), 1L, "test", Freshness.REALTIME);

        List<StrategyBuilder.Built> alternatives = StrategyBuilder.buildAlternatives(
                StrategyFamily.CREDIT_CALL_SPREAD, chain, null, bd("100"),
                StrategyBuilder.BuildHints.NONE);

        assertThat(alternatives).hasSize(StrategyBuilder.MAX_SEARCH_ALTERNATIVES);
        assertThat(alternatives.stream()
                .map(built -> leg(built, OptionType.CALL, LegAction.SELL).strike())
                .distinct())
                .as("several high-RoR widths off one near-money short must not erase a 20-delta boundary")
                .contains(bd("106"));
    }

    @Test
    void ironCondorAlternativesPreserveAnotherPairOfShortBoundaries() {
        OptionChain chain = new OptionChain("AMD", EXPIRATION, bd("100"),
                List.of(
                        quote(OptionType.CALL, "102.5", "2.50", "2.55", 0.45),
                        quote(OptionType.CALL, "105", "1.50", "1.55", 0.20),
                        quote(OptionType.CALL, "110", "0.60", "0.65", 0.10),
                        quote(OptionType.CALL, "115", "0.20", "0.25", 0.05)
                ),
                List.of(
                        quote(OptionType.PUT, "85", "0.20", "0.25", -0.05),
                        quote(OptionType.PUT, "90", "0.60", "0.65", -0.10),
                        quote(OptionType.PUT, "95", "1.50", "1.55", -0.20),
                        quote(OptionType.PUT, "97.5", "2.50", "2.55", -0.45)
                ), 1L, "test", Freshness.REALTIME);

        List<StrategyBuilder.Built> alternatives = StrategyBuilder.buildAlternatives(
                StrategyFamily.IRON_CONDOR, chain, null, bd("100"), StrategyBuilder.BuildHints.NONE);

        assertThat(alternatives).hasSize(StrategyBuilder.MAX_SEARCH_ALTERNATIVES);
        List<String> shortBoundaryPairs = alternatives.stream().map(built ->
                leg(built, OptionType.PUT, LegAction.SELL).strike().toPlainString() + '/'
                        + leg(built, OptionType.CALL, LegAction.SELL).strike().toPlainString()).distinct().toList();
        assertThat(shortBoundaryPairs)
                .as("four preferred-wing combinations must not consume every bounded search slot")
                .contains("95/105").hasSizeGreaterThan(1);
    }

    @Test
    void largeChainsKeepAlternativeSearchLinearAndRetainedStateBounded() {
        int quoteCountPerSide = 2_001;
        List<OptionQuote> calls = new ArrayList<>(quoteCountPerSide);
        List<OptionQuote> puts = new ArrayList<>(quoteCountPerSide);
        for (int i = 0; i < quoteCountPerSide; i++) {
            BigDecimal strike = bd("50").add(bd("0.05").multiply(BigDecimal.valueOf(i)));
            double k = strike.doubleValue();
            double callBid = Math.max(0.05, (160.0 - k) * 0.10);
            double putBid = Math.max(0.05, (k - 40.0) * 0.10);
            calls.add(denseQuote(OptionType.CALL, strike, callBid, callBid + 0.005,
                    Math.max(0.01, 0.50 - Math.max(0, k - 100.0) * 0.01)));
            puts.add(denseQuote(OptionType.PUT, strike, putBid, putBid + 0.005,
                    -Math.max(0.01, 0.50 - Math.max(0, 100.0 - k) * 0.01)));
        }
        OptionChain chain = new OptionChain("AMD", EXPIRATION, bd("100"), calls, puts,
                1L, "dense-test", Freshness.REALTIME);

        StrategyBuilder.AlternativeSearchResult vertical = StrategyBuilder.buildAlternativesWithStats(
                StrategyFamily.CREDIT_CALL_SPREAD, chain, null, bd("100"),
                StrategyBuilder.BuildHints.NONE);
        StrategyBuilder.AlternativeSearchResult condor = StrategyBuilder.buildAlternativesWithStats(
                StrategyFamily.IRON_CONDOR, chain, null, bd("100"),
                StrategyBuilder.BuildHints.NONE);
        StrategyBuilder.AlternativeSearchResult repeatedCondor = StrategyBuilder.buildAlternativesWithStats(
                StrategyFamily.IRON_CONDOR, chain, null, bd("100"),
                StrategyBuilder.BuildHints.NONE);

        assertThat(vertical.alternatives()).hasSizeLessThanOrEqualTo(StrategyBuilder.MAX_SEARCH_ALTERNATIVES);
        assertThat(vertical.quotePairEvaluations())
                .isLessThanOrEqualTo((long) quoteCountPerSide
                        * StrategyBuilder.MAX_VERTICAL_WING_PROBES_PER_SHORT);
        assertThat(condor.alternatives()).hasSizeLessThanOrEqualTo(StrategyBuilder.MAX_SEARCH_ALTERNATIVES);
        assertThat(condor.quotePairEvaluations())
                .as("four wing probes per quote plus a bounded 16x16 side combination")
                .isLessThanOrEqualTo(2L * quoteCountPerSide * 4
                        + (long) StrategyBuilder.MAX_CONDOR_SIDES_PER_TYPE
                        * StrategyBuilder.MAX_CONDOR_SIDES_PER_TYPE);
        assertThat(vertical.peakRetainedCandidates())
                .isLessThanOrEqualTo(StrategyBuilder.MAX_SEARCH_RETAINED_CANDIDATES);
        assertThat(condor.peakRetainedCandidates())
                .isLessThanOrEqualTo(StrategyBuilder.MAX_SEARCH_RETAINED_CANDIDATES);
        assertThat(repeatedCondor.alternatives().stream().map(StrategyBuilder.Built::label))
                .containsExactlyElementsOf(condor.alternatives().stream()
                        .map(StrategyBuilder.Built::label).toList());
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

    private static OptionQuote denseQuote(OptionType type, BigDecimal strike, double bid,
                                           double ask, Double delta) {
        return new OptionQuote("AMD", "AMD-" + type + '-' + strike.toPlainString(), type,
                strike, EXPIRATION, BigDecimal.valueOf(bid), BigDecimal.valueOf(ask), null,
                100L, 100L, 0.35, delta, null, null, null,
                1L, "dense-test", Freshness.REALTIME);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
