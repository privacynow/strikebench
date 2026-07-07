package io.liftandshift.strategy;

import io.liftandshift.model.Freshness;
import io.liftandshift.model.Leg;
import io.liftandshift.model.LegAction;
import io.liftandshift.model.OptionQuote;
import io.liftandshift.model.OptionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 8);
    private static final LocalDate EXP = LocalDate.of(2026, 8, 21);
    private static final BigDecimal SPOT = new BigDecimal("100.00");

    private static OptionQuote quote(OptionType type, String strike, String bid, String ask, long oi, long vol, Freshness fresh) {
        return new OptionQuote("TEST", "TEST  260821" + (type == OptionType.CALL ? "C" : "P") + "0", type,
                new BigDecimal(strike), EXP, new BigDecimal(bid), new BigDecimal(ask), null,
                vol, oi, 0.25, 0.4, 0.02, -0.01, 0.1, System.currentTimeMillis(), "test", fresh);
    }

    private static Leg leg(LegAction a, OptionType t, String strike) {
        return Leg.option(a, t, new BigDecimal(strike), EXP, 1, new BigDecimal("2.00"));
    }

    private static Guardrails.Proposal proposal(StrategyFamily family, List<Leg> legs, List<OptionQuote> quotes) {
        return new Guardrails.Proposal(family, legs, 1, quotes, SPOT, Freshness.FIXTURE, TODAY,
                10_000_000L, false, false, false);
    }

    @Test
    void nakedCallIsBlocked() {
        Verdict v = Guardrails.check(proposal(StrategyFamily.NAKED_CALL,
                List.of(leg(LegAction.SELL, OptionType.CALL, "105")),
                List.of(quote(OptionType.CALL, "105", "1.90", "2.10", 5000, 300, Freshness.FIXTURE))));
        assertThat(v.blocked()).isTrue();
        assertThat(v.blockReasons()).anySatisfy(r -> assertThat(r).containsIgnoringCase("undefined risk"));
    }

    @Test
    void undefinedRiskBlockedEvenWithoutFamilyLabel() {
        // Same naked short call passed without its family tag: payoff analysis still blocks it
        Verdict v = Guardrails.check(proposal(null,
                List.of(leg(LegAction.SELL, OptionType.CALL, "105")),
                List.of(quote(OptionType.CALL, "105", "1.90", "2.10", 5000, 300, Freshness.FIXTURE))));
        assertThat(v.blocked()).isTrue();
    }

    @Test
    void definedRiskSpreadAllowed() {
        Verdict v = Guardrails.check(proposal(StrategyFamily.CREDIT_PUT_SPREAD,
                List.of(leg(LegAction.SELL, OptionType.PUT, "100"), leg(LegAction.BUY, OptionType.PUT, "95")),
                List.of(quote(OptionType.PUT, "100", "2.90", "3.10", 5000, 300, Freshness.FIXTURE),
                        quote(OptionType.PUT, "95", "1.15", "1.25", 5000, 300, Freshness.FIXTURE))));
        assertThat(v.level()).isEqualTo(Verdict.Level.ALLOW);
    }

    @Test
    void staleQuoteBlocks() {
        Verdict v = Guardrails.check(proposal(StrategyFamily.LONG_CALL,
                List.of(leg(LegAction.BUY, OptionType.CALL, "100")),
                List.of(quote(OptionType.CALL, "100", "2.40", "2.60", 5000, 300, Freshness.STALE))));
        assertThat(v.blocked()).isTrue();
        assertThat(v.blockReasons()).anySatisfy(r -> assertThat(r).contains("STALE"));
    }

    @Test
    void missingQuoteBlocksAsChainless() {
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(null);
        Verdict v = Guardrails.check(proposal(StrategyFamily.LONG_CALL,
                List.of(leg(LegAction.BUY, OptionType.CALL, "100")), quotes));
        assertThat(v.blocked()).isTrue();
        assertThat(v.blockReasons()).anySatisfy(r -> assertThat(r).containsIgnoringCase("no tradable market"));
    }

    @Test
    void insufficientBuyingPowerBlocks() {
        Verdict v = Guardrails.check(new Guardrails.Proposal(StrategyFamily.LONG_CALL,
                List.of(leg(LegAction.BUY, OptionType.CALL, "100")),
                1,
                List.of(quote(OptionType.CALL, "100", "2.40", "2.60", 5000, 300, Freshness.FIXTURE)),
                SPOT, Freshness.FIXTURE, TODAY,
                100L, // $1 of buying power
                false, false, false));
        assertThat(v.blocked()).isTrue();
        assertThat(v.blockReasons()).anySatisfy(r -> assertThat(r).containsIgnoringCase("buying power"));
    }

    @Test
    void warningsForZeroDteWideSpreadLowOiAndDelayed() {
        Leg zeroDte = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"), TODAY, 1, new BigDecimal("1.00"));
        OptionQuote wide = new OptionQuote("TEST", "OCC", OptionType.CALL, new BigDecimal("100"), TODAY,
                new BigDecimal("0.80"), new BigDecimal("1.20"), null, 3L, 40L, 0.25,
                0.5, 0.05, -0.05, 0.02, System.currentTimeMillis(), "test", Freshness.DELAYED);
        Verdict v = Guardrails.check(new Guardrails.Proposal(StrategyFamily.LONG_CALL,
                List.of(zeroDte), 1, List.of(wide), SPOT, Freshness.DELAYED, TODAY, 10_000_000L, false, true, false));
        assertThat(v.level()).isEqualTo(Verdict.Level.WARN);
        assertThat(v.warnings()).anySatisfy(w -> assertThat(w).contains("0DTE"));
        assertThat(v.warnings()).anySatisfy(w -> assertThat(w).containsIgnoringCase("wide bid/ask"));
        assertThat(v.warnings()).anySatisfy(w -> assertThat(w).containsIgnoringCase("open interest"));
        assertThat(v.warnings()).anySatisfy(w -> assertThat(w).containsIgnoringCase("earnings"));
        assertThat(v.warnings()).anySatisfy(w -> assertThat(w).contains("DELAYED"));
    }

    @Test
    void shortItmCallNearExDivWarnsOfAssignment() {
        Verdict v = Guardrails.check(new Guardrails.Proposal(StrategyFamily.COVERED_CALL,
                List.of(Leg.stock(LegAction.BUY, 1, SPOT), leg(LegAction.SELL, OptionType.CALL, "95")),
                1,
                java.util.Arrays.asList(null, quote(OptionType.CALL, "95", "5.90", "6.10", 5000, 300, Freshness.FIXTURE)),
                SPOT, Freshness.FIXTURE, TODAY, 10_000_000L, false, false, true));
        assertThat(v.blocked()).isFalse();
        assertThat(v.warnings()).anySatisfy(w -> assertThat(w).containsIgnoringCase("early assignment"));
    }

    @Test
    void expiredContractBlocked() {
        Leg past = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"), TODAY.minusDays(7), 1, new BigDecimal("2.00"));
        Verdict v = Guardrails.check(proposal(StrategyFamily.LONG_CALL, List.of(past),
                List.of(quote(OptionType.CALL, "100", "1.90", "2.10", 5000, 300, Freshness.FIXTURE))));
        assertThat(v.blocked()).isTrue();
        assertThat(v.blockReasons()).anySatisfy(r -> assertThat(r).containsIgnoringCase("expired"));
    }

    @Test
    void multiExpirationNetDebitWithUncoveredShortBlocked() {
        // Net debit overall, but the short call outlives its long cover -> undefined risk
        Leg longNear = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"), LocalDate.of(2026, 7, 17), 1, new BigDecimal("3.00"));
        Leg shortFar = Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("105"), EXP, 1, new BigDecimal("1.10"));
        Verdict v = Guardrails.check(proposal(null, List.of(longNear, shortFar),
                List.of(quote(OptionType.CALL, "100", "2.90", "3.10", 5000, 300, Freshness.FIXTURE),
                        quote(OptionType.CALL, "105", "1.00", "1.20", 5000, 300, Freshness.FIXTURE))));
        assertThat(v.blocked()).isTrue();
        assertThat(v.blockReasons()).anySatisfy(r -> assertThat(r).containsIgnoringCase("not fully covered"));
    }

    @Test
    void multiExpirationCreditBlocked() {
        Leg nearSell = Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("100"), LocalDate.of(2026, 7, 17), 1, new BigDecimal("3.00"));
        Leg farBuy = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"), EXP, 1, new BigDecimal("2.00"));
        // Net credit across expirations (near sells for more than far costs)
        Verdict v = Guardrails.check(proposal(null, List.of(nearSell, farBuy),
                List.of(quote(OptionType.CALL, "100", "2.90", "3.10", 5000, 300, Freshness.FIXTURE),
                        quote(OptionType.CALL, "100", "1.90", "2.10", 5000, 300, Freshness.FIXTURE))));
        assertThat(v.blocked()).isTrue();
        assertThat(v.blockReasons()).anySatisfy(r -> assertThat(r).containsIgnoringCase("multi-expiration credit"));
    }
}
