package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.strategy.StrategyFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The §7.2 receipt as the candidate rail publishes it: the option-only net, the stock cash flow and
 * the whole-package net are three separate, reconciling amounts on ONE object, so a covered call's
 * "net credit" and its "net debit" are no longer two screens contradicting each other (§3.3).
 */
class CandidateTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"),
            ZoneId.of("America/New_York"));
    private static final long BP = 10_000_000L;

    private RecommendationEngine engine;

    @BeforeEach
    void setUp() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService market = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        engine = new RecommendationEngine(market, CLOCK);
    }

    private static RecommendationEngine.Request incomeReq() {
        return new RecommendationEngine.Request("AAPL", null, "month", "balanced", null, null, null, null,
                true, false, "income", null, null);
    }

    private static RecommendationEngine.Request directionalReq() {
        return new RecommendationEngine.Request("AAPL", "bullish", "month", "balanced", null, null, null, null,
                true, false, null, null, null);
    }

    @Test
    void optionOnlyNetEqualsPackageNetAndStockCashFlowIsZeroWithoutAStockLeg() {
        RecommendationEngine.Result result = engine.recommend(directionalReq(), BP);
        assertThat(result.candidates()).isNotEmpty();
        result.candidates().stream()
                .filter(c -> c.legs().stream().noneMatch(leg -> leg.type().equals("STOCK")))
                .forEach(c -> {
                    assertThat(c.price().optionNetPremiumCents())
                            .as("no stock leg => option net IS the package net (strategy=%s)", c.strategy())
                            .isEqualTo(c.price().grossPackageNetCents());
                    assertThat(c.price().stockCashFlowCents())
                            .as("no stock leg => no stock cash flow (strategy=%s)", c.strategy())
                            .isZero();
                });
    }

    @Test
    void buyWriteExposesPositiveOptionCreditWhilePackageNetStaysStockInclusiveNegative() {
        Candidate buyWrite = buyWrite();

        // The full stock-plus-options package costs cash (buying 100 shares dwarfs the call credit).
        assertThat(buyWrite.price().grossPackageNetCents()).isNegative();
        // But the option legs alone COLLECT premium — that is what optionNetPremiumCents reports.
        assertThat(buyWrite.price().optionNetPremiumCents()).isPositive();
        assertThat(buyWrite.price().optionNetPremiumCents())
                .isGreaterThan(buyWrite.price().grossPackageNetCents());
        // The share purchase is now a NAMED amount instead of an unexplained gap between the two.
        assertThat(buyWrite.price().stockCashFlowCents()).isNegative();
        assertThat(StrategyFamily.valueOf(buyWrite.strategy()).requiresLongStock()).isTrue();
    }

    @Test
    void everyCandidateReceiptReconcilesAndDisclosesItsBasisQuantityAndFees() {
        RecommendationEngine.Result result = engine.recommend(incomeReq(), BP);
        assertThat(result.candidates()).isNotEmpty();
        for (Candidate c : result.candidates()) {
            var price = c.price();
            assertThat(price.grossPackageNetCents())
                    .as("gross = option + stock (strategy=%s)", c.strategy())
                    .isEqualTo(price.optionNetPremiumCents() + price.stockCashFlowCents());
            assertThat(price.afterFeeNetCents())
                    .as("after-fee = gross - fees (strategy=%s)", c.strategy())
                    .isEqualTo(price.grossPackageNetCents() - price.openingFeesCents());
            // The rail used to print a qty-scaled net with no quantity, no basis and no fee beside
            // it — the exact condition §3.3 calls financially untrustworthy.
            assertThat(price.quantity()).isEqualTo(c.qty());
            assertThat(price.openingFeesCents()).isNotNull();
            assertThat(price.valuationBasis())
                    .isEqualTo(PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK);
            assertThat(price.executability()).isEqualTo(OrderInstruction.Executability.IMMEDIATE);
            assertThat(price.executableNetCents()).isEqualTo(price.grossPackageNetCents());
            assertThat(price.fingerprint()).isNotBlank();
            assertThat(price.freshness()).isNotBlank();
        }
    }

    @Test
    void candidateQuantityMustBePositiveAndMatchItsPriceReceipt() {
        Candidate candidate = buyWrite();

        assertThatThrownBy(() -> copyWithQuantity(candidate, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
        assertThatThrownBy(() -> copyWithQuantity(candidate, candidate.qty() + 1, candidate.price()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
    }

    /**
     * §3.1/§3.2 — the rail may state only what its evidence supports. The scan's gate is
     * {@code usableIn(lane)} (analysis grade), NOT {@code executableIn(lane)}: an end-of-day chain
     * is a legitimate scan input in the OBSERVED lane (it even scores 0.70 freshness), so the
     * engine happily produces candidates from it. It used to stamp every one of those candidates
     * EXECUTABLE_BOOK / IMMEDIATE with {@code executableNetCents} set to the package net — a rail
     * row telling the reader the package was tradeable at that price right now, while TradeService
     * refused the identical package and published UNAVAILABLE. The claim was then persisted and
     * frozen into decisions.
     */
    @Test
    void anEndOfDayChainProducesIdeasThatDoNotClaimToBeExecutableRightNow() {
        RecommendationEngine endOfDay = new RecommendationEngine(
                new MarketDataService(List.of(new EndOfDayConnector(new FixtureProvider(CLOCK))),
                        List.of(), List.of()), CLOCK);

        RecommendationEngine.Result result = endOfDay.recommend(directionalReq(), BP);

        assertThat(result.candidates())
                .as("EOD evidence is analysis-usable, so the scan must still produce ideas: %s",
                        result.notes())
                .isNotEmpty();
        for (Candidate c : result.candidates()) {
            var price = c.price();
            assertThat(price.valuationBasis())
                    .as("basis on EOD marks (strategy=%s)", c.strategy())
                    .isNotEqualTo(PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK);
            assertThat(price.executability())
                    .as("executability on EOD marks (strategy=%s)", c.strategy())
                    .isEqualTo(OrderInstruction.Executability.UNAVAILABLE);
            // A field named "executable" never carries a price nobody can trade on.
            assertThat(price.executableNetCents()).isNull();
            // The package is still fully priced and still reconciles — only the CLAIM is withdrawn.
            assertThat(price.grossPackageNetCents())
                    .isEqualTo(price.optionNetPremiumCents() + price.stockCashFlowCents());
            assertThat(c.warnings()).anySatisfy(w ->
                    assertThat(w).contains("not an executable book"));
        }
    }

    /** An OBSERVED-lane connector whose book is yesterday's close: usable for analysis, not tradeable. */
    private record EndOfDayConnector(FixtureProvider delegate)
            implements io.liftandshift.strikebench.market.ports.MarketDataProvider {

        private static final String SOURCE = "eod-connector";

        @Override public String name() { return SOURCE; }
        @Override public java.util.Set<io.liftandshift.strikebench.market.Domain> domains() {
            return delegate.domains();
        }
        @Override public List<io.liftandshift.strikebench.model.SymbolMatch> lookup(String query) {
            return delegate.lookup(query);
        }
        @Override public List<java.time.LocalDate> expirations(String symbol) {
            return delegate.expirations(symbol);
        }
        @Override public List<io.liftandshift.strikebench.model.Candle> candles(
                String symbol, java.time.LocalDate from, java.time.LocalDate to) {
            return delegate.candles(symbol, from, to);
        }

        @Override public java.util.Optional<io.liftandshift.strikebench.model.Quote> quote(String symbol) {
            return delegate.quote(symbol).map(q -> new io.liftandshift.strikebench.model.Quote(
                    q.symbol(), q.description(), q.last(), q.bid(), q.ask(), q.prevClose(), q.dayHigh(),
                    q.dayLow(), q.volume(), q.optionable(), q.asOfEpochMs(), SOURCE,
                    io.liftandshift.strikebench.model.Freshness.EOD));
        }

        @Override public java.util.Optional<io.liftandshift.strikebench.model.OptionChain> chain(
                String symbol, java.time.LocalDate expiration) {
            return delegate.chain(symbol, expiration).map(chain ->
                    new io.liftandshift.strikebench.model.OptionChain(chain.underlying(),
                            chain.expiration(), chain.underlyingPrice(), eod(chain.calls()),
                            eod(chain.puts()), chain.asOfEpochMs(), SOURCE,
                            io.liftandshift.strikebench.model.Freshness.EOD));
        }

        private static List<io.liftandshift.strikebench.model.OptionQuote> eod(
                List<io.liftandshift.strikebench.model.OptionQuote> quotes) {
            return quotes.stream().map(q -> new io.liftandshift.strikebench.model.OptionQuote(
                    q.underlying(), q.occSymbol(), q.type(), q.strike(), q.expiration(), q.bid(), q.ask(),
                    q.last(), q.volume(), q.openInterest(), q.iv(), q.delta(), q.gamma(), q.theta(),
                    q.vega(), q.asOfEpochMs(), SOURCE,
                    io.liftandshift.strikebench.model.Freshness.EOD)).toList();
        }
    }

    private Candidate buyWrite() {
        return engine.recommend(incomeReq(), BP).candidates().stream()
                .filter(c -> c.strategy().equals("COVERED_CALL"))
                .filter(c -> c.legs().stream().anyMatch(leg -> leg.type().equals("STOCK")))
                .findFirst().orElseThrow();
    }

    private static Candidate copyWithQuantity(
            Candidate candidate,
            int quantity,
            PackagePriceReceipt price) {
        return new Candidate(
                candidate.strategy(),
                candidate.displayName(),
                candidate.structureGroup(),
                candidate.label(),
                candidate.legs(),
                quantity,
                price,
                candidate.maxProfitCents(),
                candidate.maxLossCents(),
                candidate.breakevens(),
                candidate.liquidityScore(),
                candidate.freshness(),
                candidate.warnings(),
                candidate.confidence(),
                candidate.whyConsidered(),
                candidate.bestUpside(),
                candidate.biggestRisk(),
                candidate.wouldInvalidate(),
                candidate.beginnerExplanation(),
                candidate.intent(),
                candidate.intents(),
                candidate.shortSideExpirationItmProb(),
                candidate.annualizedOpeningPremiumRatePct(),
                candidate.effectivePrice(),
                candidate.intentNote(),
                candidate.usesHeldShares(),
                candidate.sharesNeeded(),
                candidate.combinedMaxLossCents(),
                candidate.marketImpliedRisk());
    }
}
