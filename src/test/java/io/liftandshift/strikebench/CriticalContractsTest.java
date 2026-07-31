package io.liftandshift.strikebench;

import io.liftandshift.strikebench.eval.DecisionEndorsement;
import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackageLimitTickPolicy;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.recommend.HoldingsEvidence;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.util.Fees;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        List<Leg> optionPackage = List.of(Leg.option(LegAction.SELL, OptionType.PUT,
                bd("100"), LocalDate.of(2026, 12, 18), 1, bd("1.25")));
        var tick = PackageLimitTickPolicy.receipt(optionPackage, 5);
        assertEquals(5, tick.tickCents());
        assertThrows(IllegalArgumentException.class,
                () -> PackageLimitTickPolicy.requireValid(
                        OrderInstruction.limit(1), optionPackage, 5));
        PackageLimitTickPolicy.requireValid(OrderInstruction.limit(5), optionPackage, 5);
        assertEquals(1, PackageLimitTickPolicy.receipt(List.of(
                Leg.stockShares(LegAction.BUY, 100, bd("100"))), 1).tickCents());
    }

    @Test
    void proposedPackagesRequireAnExplicitOrderInstruction() {
        List<Leg> legs = List.of(Leg.option(LegAction.SELL, OptionType.PUT,
                bd("100"), LocalDate.of(2026, 12, 18), 1, bd("1.25")));
        assertThrows(IllegalArgumentException.class, () -> new TradeService.OpenRequest(
                "acct", "AMD", "CASH_SECURED_PUT", 1, legs, null, null, null,
                "INCOME", false, null, "TICKET", "PROPOSED", null));
        new TradeService.OpenRequest(
                "acct", "AMD", "CASH_SECURED_PUT", 1, legs, null, null, null,
                "INCOME", false, null, "TICKET", "PROPOSED", OrderInstruction.market());
    }

    @Test
    void hypotheticalSharesRemainAnalysisOnlyEvidence() {
        var holdings = new RecommendationEngine.Holdings(
                100, 12_500L, 15_000L, null,
                HoldingsEvidence.Provenance.HYPOTHETICAL_HOLDINGS);
        HoldingsEvidence evidence = holdings.evidence();

        assertEquals(HoldingsEvidence.Provenance.HYPOTHETICAL_HOLDINGS,
                evidence.provenance());
        assertFalse(evidence.endorsementEligible());
        assertFalse(evidence.placementEligible());
        assertTrue(evidence.basis().contains("only for analysis"));
        assertFalse(HoldingsEvidence.legacyUnverified(100, 12_500L).endorsementEligible());
        assertFalse(HoldingsEvidence.legacyUnverified(100, 12_500L).placementEligible());
        assertFalse(HoldingsEvidence.acquisitionTarget(100, null).placementEligible());
        assertEquals(HoldingsEvidence.Provenance.HYPOTHETICAL_HOLDINGS,
                new RecommendationEngine.Holdings(100, 12_500L, null).provenance());

        HoldingsEvidence unbound = HoldingsEvidence.accountBacked(100, 12_500L);
        assertFalse(unbound.placementEligible());
        HoldingsEvidence bound = HoldingsEvidence.accountBacked(
                100, 12_500L, "acct-ira", "TRACKED", 123L);
        assertTrue(bound.placementEligible());
        assertTrue(bound.matchesDestination("acct-ira"));
        assertFalse(bound.matchesDestination("acct-taxable"));
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

    @Test
    void observedHistoryIsValidatedBeforeReturnAndCloseOnlyFieldsStayAbsent() {
        Candle valid = new Candle(LocalDate.of(2026, 7, 28), bd("100"), bd("102"),
                bd("99"), bd("101"), 1_000, true);
        Candle invalid = new Candle(LocalDate.of(2026, 7, 29), null, null,
                null, bd("102"), 1_000, true);
        MarketDataProvider provider = new MarketDataProvider() {
            @Override public String name() { return "observed-test"; }
            @Override public Set<Domain> domains() { return Set.of(Domain.CANDLES); }
            @Override public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
                return List.of(valid, invalid);
            }
            @Override public List<SymbolMatch> lookup(String query) { return List.of(); }
            @Override public Optional<Quote> quote(String symbol) { return Optional.empty(); }
            @Override public List<LocalDate> expirations(String symbol) { return List.of(); }
            @Override public Optional<OptionChain> chain(String symbol, LocalDate expiration) {
                return Optional.empty();
            }
        };
        var market = new MarketDataService(List.of(provider), List.of(), List.of());

        CandleSeries observed = market.candleSeriesFromProviders(
                "AAPL", LocalDate.of(2026, 7, 28), LocalDate.of(2026, 7, 29));
        assertEquals(List.of(valid), observed.candles());
        assertEquals("missing OHLC field",
                io.liftandshift.strikebench.db.UnderlyingBackfill.invalidReason(invalid));

        CandleSeries closeOnly = new CandleSeries(
                List.of(invalid), "stored:broker", Freshness.EOD, "CLOSE_ONLY");
        assertFalse(closeOnly.hasFullOhlc());
        assertNull(closeOnly.candles().getFirst().open());
        assertNull(closeOnly.candles().getFirst().high());
        assertNull(closeOnly.candles().getFirst().low());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
