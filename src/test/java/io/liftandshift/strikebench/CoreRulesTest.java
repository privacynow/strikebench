package io.liftandshift.strikebench;

import io.liftandshift.strikebench.backtest.Backtester;
import io.liftandshift.strikebench.eval.DecisionEndorsement;
import io.liftandshift.strikebench.eval.AccountFitAssessment;
import io.liftandshift.strikebench.db.WorkspaceContext;
import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.DataAge;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackagePrice;
import io.liftandshift.strikebench.paper.PackageLimitTickPolicy;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.recommend.HoldingsEvidence;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.recommend.DecisionDeclarationPolicy;
import io.liftandshift.strikebench.strategy.CapitalRequirement;
import io.liftandshift.strikebench.strategy.StrategyCatalog;
import io.liftandshift.strikebench.util.Fees;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * one current financial or data rule and should remain fast enough to run on every Maven build.</p>
 */
final class CoreRulesTest {

    @Test
    void persistedLegJsonContainsOnlyRecordFieldsAndStrictlyRoundTrips() {
        List<Leg> legs = List.of(
                Leg.stockShares(LegAction.BUY, 100, bd("212.50")),
                Leg.option(LegAction.SELL, OptionType.CALL, bd("230"),
                        LocalDate.of(2026, 9, 18), 1, bd("4.25"), 100));

        String json = Json.write(legs);

        assertFalse(json.contains("\"stock\""));
        assertEquals(legs, TradeRecord.legsFromJson(json));
    }

    @Test
    void packagePriceResponseRoundTripsWithoutTreatingItsDisplayTickAsInput() {
        PackagePrice price = PackagePrice.of(5, 118_000L, 118_000L, 0L,
                1_300L, 2_600L, PackagePrice.FeeSide.OPENING, 118_000L,
                OrderInstruction.market(), OrderInstruction.Executability.IMMEDIATE,
                PackagePrice.ValuationBasis.EXECUTABLE_BOOK,
                "fixture", "FIXTURE", 1_785_655_630_958L, "fingerprint");

        String json = Json.write(price);

        assertTrue(json.contains("\"limitTickCents\""));
        assertEquals(price, Json.read(json, PackagePrice.class));
    }

    @Test
    void replayIdentityIsStableAcrossMapAndJsonRepresentations() {
        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put("symbol", "NVDA");
        effective.put("engineKind", "SINGLE");
        effective.put("modelInputs", Map.of("fallbackVolatility", 0.30, "annualRate", 0.04));

        assertEquals(Backtester.inputFingerprint(effective),
                Backtester.inputFingerprint(Json.MAPPER.valueToTree(effective)));
    }

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
                bd("100"), LocalDate.of(2026, 12, 18), 1, bd("1.25"), 100));
        var tick = PackageLimitTickPolicy.ruleFor(optionPackage, 5);
        assertEquals(5, tick.tickCents());
        assertThrows(IllegalArgumentException.class,
                () -> PackageLimitTickPolicy.requirePackageLimit(
                        OrderInstruction.limit(1), optionPackage, 5));
        PackageLimitTickPolicy.requirePackageLimit(OrderInstruction.limit(5), optionPackage, 5);
        assertEquals(1, PackageLimitTickPolicy.ruleFor(List.of(
                Leg.stockShares(LegAction.BUY, 100, bd("100"))), 1).tickCents());
    }

    @Test
    void analysisPackagesCanOmitAnOrderInstruction() {
        List<Leg> legs = List.of(Leg.option(LegAction.SELL, OptionType.PUT,
                bd("100"), LocalDate.of(2026, 12, 18), 1, bd("1.25"), 100));
        new TradeService.OpenRequest(
                "acct", "AMD", "CASH_SECURED_PUT", 1, legs, null, null, null,
                "INCOME", false, null, "ANALYZE", "PROPOSED", null, null);
        new TradeService.OpenRequest(
                "acct", "AMD", "CASH_SECURED_PUT", 1, legs, null, null, null,
                "INCOME", false, null, "TICKET", "PROPOSED", OrderInstruction.market(), null);
    }

    @Test
    void hypotheticalSharesRemainAnalysisOnlyEvidence() {
        var holdings = new RecommendationEngine.Holdings(
                100, 12_500L, 15_000L, null,
                HoldingsEvidence.Provenance.HYPOTHETICAL_HOLDINGS,
                null, null, null);
        HoldingsEvidence evidence = holdings.evidence();

        assertEquals(HoldingsEvidence.Provenance.HYPOTHETICAL_HOLDINGS,
                evidence.provenance());
        assertFalse(evidence.isAccountBacked());
        assertFalse(HoldingsEvidence.forProvenance(
                HoldingsEvidence.Provenance.ACQUISITION_TARGET, 100, null,
                null, null, null).isAccountBacked());
        assertEquals(HoldingsEvidence.Provenance.HYPOTHETICAL_HOLDINGS,
                new RecommendationEngine.Holdings(100, 12_500L, null, null,
                        HoldingsEvidence.Provenance.HYPOTHETICAL_HOLDINGS,
                        null, null, null).provenance());

        HoldingsEvidence unbound = HoldingsEvidence.forProvenance(
                HoldingsEvidence.Provenance.ACCOUNT_BACKED, 100, 12_500L,
                null, null, null);
        assertFalse(unbound.isAccountBacked());
        HoldingsEvidence bound = HoldingsEvidence.forProvenance(
                HoldingsEvidence.Provenance.ACCOUNT_BACKED, 100, 12_500L,
                "acct-ira", "TRACKED", 123L);
        assertTrue(bound.isAccountBacked());
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
        assertEquals("MISSING", absent.freshness());

        Candle raw = new Candle(LocalDate.of(2026, 7, 28), bd("100"), bd("102"),
                bd("99"), bd("101"), 1_000, false);
        Candle adjusted = new Candle(LocalDate.of(2026, 7, 29), bd("50"), bd("51"),
                bd("49"), bd("50"), 2_000, true);
        CandleSeries mixed = new CandleSeries(
                List.of(raw, adjusted), DataEvidence.observed("owned-csv", DataAge.EOD),
                "OHLCV", "MIXED");

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
        var market = new MarketDataService(List.of(provider), List.of(), List.of(), null,
                java.time.Clock.fixed(Instant.parse("2026-07-29T20:00:00Z"), java.time.ZoneOffset.UTC));

        CandleSeries observed = market.candleSeriesFromProviders(
                "AAPL", LocalDate.of(2026, 7, 28), LocalDate.of(2026, 7, 29));
        assertEquals(List.of(valid), observed.candles());
        assertEquals("missing OHLC field",
                io.liftandshift.strikebench.db.UnderlyingBackfill.invalidReason(invalid));

        CandleSeries closeOnly = new CandleSeries(
                List.of(invalid), DataEvidence.observed("stored:broker", DataAge.EOD),
                "CLOSE_ONLY", "ADJUSTED");
        assertFalse(closeOnly.hasFullOhlc());
        assertNull(closeOnly.candles().getFirst().open());
        assertNull(closeOnly.candles().getFirst().high());
        assertNull(closeOnly.candles().getFirst().low());
    }

    @Test
    void accountFitKeepsLossAppetiteSeparateFromCollateralAndBuyingPower() {
        CapitalRequirement cashSecured = new CapitalRequirement(
                StrategyCatalog.FundingClass.CASH_COLLATERAL,
                StrategyCatalog.CapitalBasis.STRIKE_CASH_COLLATERAL,
                18_000L, 20_000L, 18_100L, 20_000L,
                "Cash-secured assessment.", null);

        AccountFitAssessment collateralFits = AccountFitAssessment.assess(
                cashSecured, 100_000L, 5_000L);
        assertEquals("COLLATERAL_OUTSIDE_LOSS_APPETITE", collateralFits.status());
        assertFalse(collateralFits.withinLossAppetite());
        assertTrue(collateralFits.withinBuyingPower());

        AccountFitAssessment capitalDoesNotFit = AccountFitAssessment.assess(
                cashSecured, 10_000L, 25_000L);
        assertEquals("EXCEEDS_BUYING_POWER", capitalDoesNotFit.status());
        assertTrue(capitalDoesNotFit.withinLossAppetite());
        assertFalse(capitalDoesNotFit.withinBuyingPower());
    }

    @Test
    void strategyIdentityUsesOnlyTheContextExactLegsCannotCarry() {
        LocalDate expiry = LocalDate.of(2026, 12, 18);
        List<Leg> shortPut = List.of(Leg.option(
                LegAction.SELL, OptionType.PUT, bd("100"), expiry, 1, bd("1.25"), 100));
        assertEquals(StrategyCatalog.FundingClass.CASH_COLLATERAL,
                StrategyCatalog.identify(StrategyCatalog.ClassificationRequest.draft(
                        "CASH_SECURED_PUT", "AMD", 1, shortPut, false))
                        .fundingClass());
        assertEquals(StrategyCatalog.FundingClass.UNDEFINED_RISK,
                StrategyCatalog.identify(StrategyCatalog.ClassificationRequest.draft(
                        "NAKED_PUT", "AMD", 1, shortPut, false))
                        .fundingClass());

        List<Leg> shortCall = List.of(Leg.option(
                LegAction.SELL, OptionType.CALL, bd("120"), expiry, 1, bd("1.10"), 100));
        assertEquals("COVERED_CALL",
                StrategyCatalog.identify(StrategyCatalog.ClassificationRequest.draft(
                        "COVERED_CALL", "AMD", 1, shortCall, true))
                        .family());
        assertEquals(StrategyCatalog.FundingClass.SHARE_BACKED,
                StrategyCatalog.identify(StrategyCatalog.ClassificationRequest.draft(
                        "COVERED_CALL", "AMD", 1, shortCall, true))
                        .fundingClass());

        List<Leg> longCall = List.of(Leg.option(
                LegAction.BUY, OptionType.CALL, bd("120"), expiry, 1, bd("1.10"), 100));
        assertEquals("LONG_CALL",
                StrategyCatalog.identify(StrategyCatalog.ClassificationRequest.draft(
                        "COVERED_CALL", "AMD", 1, longCall, true))
                        .family());
    }

    @Test
    void recommendationRequiresAnExplicitEarningsPolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> new RecommendationEngine.Request(
                        "AMD", "neutral", "month", "balanced", null, null, null,
                        List.of(), null, false, "INCOME", null, null));

        RecommendationEngine.Request declared = new RecommendationEngine.Request(
                "AMD", "neutral", "month", "balanced", null, null, null,
                List.of(), true, false, "INCOME", null, null);
        assertEquals("INCOME", DecisionDeclarationPolicy.requireRecommendation(
                "recommendation", declared, true).name());
    }

    @Test
    void workspaceKeepsTheEarningsDeclarationAcrossMarketTransitions() {
        WorkspaceContext.Stored stored = WorkspaceContext.read("""
                {"version":1,"generation":1,"world":"observed","datasetId":"observed",
                 "marketMode":"OBSERVED","accountId":"acct-observed","avoidEarnings":false}
                """);
        assertTrue(stored.readable());
        WorkspaceContext context = stored.context().validated();
        assertFalse(context.avoidEarnings());

        WorkspaceContext moved = context.inWorld(new WorkspaceContext.ActiveMarket(
                "sim-review", "sim-data", "SIMULATED", "acct-sim")).context();
        assertFalse(moved.avoidEarnings());
        assertTrue(WorkspaceContext.DECLARATIONS.contains("avoidEarnings"));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
