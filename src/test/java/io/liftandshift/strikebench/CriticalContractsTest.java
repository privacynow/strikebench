package io.liftandshift.strikebench;

import io.liftandshift.strikebench.eval.DecisionEndorsement;
import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.util.Fees;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small, high-value release smoke for policies that have caused real correctness incidents.
 *
 * <p>This intentionally does not recreate the retired broad legacy suite. Each assertion protects
 * one current canonical owner and should remain fast enough to run on every Maven build.</p>
 */
final class CriticalContractsTest {

    @Test
    void optionFeesHaveOneExactRoundTripScheduleAndStocksRemainCommissionFree() {
        assertEquals(0, Fees.roundTripCents(0, 65, 25));
        assertEquals(new Fees.Schedule(220, 220, 440), Fees.schedule(3, 65, 25));
        assertThrows(IllegalArgumentException.class, () -> Fees.schedule(1, -1, 0));
    }

    @Test
    void signedPackageLimitsTreatMoreCreditAndLessDebitAsMoreFavorable() {
        assertEquals(OrderInstruction.Executability.IMMEDIATE,
                OrderInstruction.limit(400).executability(500L, true));
        assertEquals(OrderInstruction.Executability.RESTING,
                OrderInstruction.limit(600).executability(500L, true));
        assertEquals(OrderInstruction.Executability.IMMEDIATE,
                OrderInstruction.limit(-600).executability(-500L, true));
        assertEquals(OrderInstruction.Executability.RESTING,
                OrderInstruction.limit(-400).executability(-500L, true));
        assertEquals(OrderInstruction.Executability.UNAVAILABLE,
                OrderInstruction.limit(500).executability(null, false));
    }

    @Test
    void anUnavailableLiveBookDoesNotEraseAnEconomicEndorsement() {
        DecisionEndorsement ranked = new DecisionEndorsement(
                true, DecisionEndorsement.ENDORSED, "candidate-1", List.of(), null);

        DecisionEndorsement exact = DecisionEndorsement.exact(
                ranked,
                OrderInstruction.limit(125),
                OrderInstruction.Executability.UNAVAILABLE,
                false,
                List.of());

        assertTrue(exact.endorsed());
        assertEquals(DecisionEndorsement.ENDORSED, exact.status());
        assertTrue(exact.basis().contains("Execution readiness is separate"));
    }

    @Test
    void expirationUsesTheActualEarlyCloseBoundary() {
        LocalDate fridayAfterThanksgiving = LocalDate.of(2026, 11, 27);
        Instant justBefore = Instant.parse("2026-11-27T17:59:59Z");
        Instant atClose = Instant.parse("2026-11-27T18:00:00Z");

        assertFalse(MarketHours.contractDead(fridayAfterThanksgiving, justBefore));
        assertTrue(MarketHours.contractDead(fridayAfterThanksgiving, atClose));
    }

    @Test
    void emptyAndMixedHistoryKeepTheirProviderAndPriceBasisTruth() {
        CandleSeries absent = CandleSeries.emptyFrom("yahoo");
        assertTrue(absent.isEmpty());
        assertEquals("yahoo", absent.source());
        assertEquals(Freshness.MISSING, absent.freshness());

        Candle raw = new Candle(LocalDate.of(2026, 7, 28), bd("100"), bd("102"),
                bd("99"), bd("101"), 1_000, false);
        Candle adjusted = new Candle(LocalDate.of(2026, 7, 29), bd("50"), bd("51"),
                bd("49"), bd("50"), 2_000, true);
        CandleSeries mixed = new CandleSeries(
                List.of(raw, adjusted), "owned-csv", Freshness.EOD, "OHLCV");

        assertEquals("MIXED", mixed.priceBasis());
        assertTrue(mixed.hasFullOhlc());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
