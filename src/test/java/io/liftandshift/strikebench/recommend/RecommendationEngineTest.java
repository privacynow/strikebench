package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.strategy.StrategyBuilder;
import io.liftandshift.strikebench.strategy.StrategyFamily;
import io.liftandshift.strikebench.util.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationEngineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 8);
    private static final long BP = 10_000_000L; // $100k

    private RecommendationEngine engine;

    @BeforeEach
    void setUp() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService market = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        engine = new RecommendationEngine(market, CLOCK);
    }

    @Test
    void observedRecommendationsRefuseFabricatedProviderPayloads() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataProvider badObservedConnector = new MarketDataProvider() {
            @Override public String name() { return "observed-connector"; }
            @Override public Set<io.liftandshift.strikebench.market.Domain> domains() { return fixture.domains(); }
            @Override public List<io.liftandshift.strikebench.model.SymbolMatch> lookup(String q) { return fixture.lookup(q); }
            @Override public java.util.Optional<io.liftandshift.strikebench.model.Quote> quote(String s) { return fixture.quote(s); }
            @Override public List<LocalDate> expirations(String s) { return fixture.expirations(s); }
            @Override public java.util.Optional<io.liftandshift.strikebench.model.OptionChain> chain(String s, LocalDate e) {
                return fixture.chain(s, e);
            }
            @Override public List<io.liftandshift.strikebench.model.Candle> candles(String s, LocalDate f, LocalDate t) {
                return fixture.candles(s, f, t);
            }
        };
        RecommendationEngine observed = new RecommendationEngine(
                new MarketDataService(List.of(badObservedConnector), List.of(), List.of()), CLOCK);

        RecommendationEngine.Result result = observed.recommend(req("AAPL", "bullish", "month", "balanced"), BP);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.notes()).anySatisfy(note ->
                assertThat(note).contains("OBSERVED-lane").contains("DEMO"));
    }

    private static RecommendationEngine.Request req(String symbol, String thesis, String horizon, String mode) {
        return new RecommendationEngine.Request(symbol, thesis, horizon, mode, null, null, null, null,
                true, false, null, null, null);
    }

    private static MarketDataProvider staleObservedProvider(Clock clock) {
        FixtureProvider fixture = new FixtureProvider(clock);
        return new MarketDataProvider() {
            @Override public String name() { return "cboe-test"; }
            @Override public Set<io.liftandshift.strikebench.market.Domain> domains() { return fixture.domains(); }
            @Override public List<io.liftandshift.strikebench.model.SymbolMatch> lookup(String q) {
                return fixture.lookup(q);
            }
            @Override public java.util.Optional<io.liftandshift.strikebench.model.Quote> quote(String symbol) {
                return fixture.quote(symbol).map(q -> new io.liftandshift.strikebench.model.Quote(
                        q.symbol(), q.description(), q.last(), q.bid(), q.ask(), q.prevClose(),
                        q.dayHigh(), q.dayLow(), q.volume(), q.optionable(), q.asOfEpochMs(),
                        "cboe-test", io.liftandshift.strikebench.model.Freshness.STALE));
            }
            @Override public List<LocalDate> expirations(String symbol) { return fixture.expirations(symbol); }
            @Override public java.util.Optional<io.liftandshift.strikebench.model.OptionChain> chain(
                    String symbol, LocalDate expiration) {
                return fixture.chain(symbol, expiration).map(chain -> new io.liftandshift.strikebench.model.OptionChain(
                        chain.underlying(), chain.expiration(), chain.underlyingPrice(),
                        chain.calls().stream().map(this::stale).toList(),
                        chain.puts().stream().map(this::stale).toList(), chain.asOfEpochMs(),
                        "cboe-test", io.liftandshift.strikebench.model.Freshness.STALE));
            }
            private io.liftandshift.strikebench.model.OptionQuote stale(
                    io.liftandshift.strikebench.model.OptionQuote quote) {
                return new io.liftandshift.strikebench.model.OptionQuote(
                        quote.underlying(), quote.occSymbol(), quote.type(), quote.strike(), quote.expiration(),
                        quote.bid(), quote.ask(), quote.last(), quote.volume(), quote.openInterest(), quote.iv(),
                        quote.delta(), quote.gamma(), quote.theta(), quote.vega(), quote.asOfEpochMs(),
                        "cboe-test", io.liftandshift.strikebench.model.Freshness.STALE);
            }
            @Override public List<io.liftandshift.strikebench.model.Candle> candles(
                    String symbol, LocalDate from, LocalDate to) {
                return List.of();
            }
        };
    }

    @Test
    void staleObservedCloseSupportsLabeledStrategyAnalysis() {
        Clock closed = Clock.fixed(Instant.parse("2026-07-08T22:00:00Z"), ZoneId.of("America/New_York"));
        MarketDataProvider provider = staleObservedProvider(closed);
        RecommendationEngine observed = new RecommendationEngine(
                new MarketDataService(List.of(provider), List.of(), List.of()), closed);

        RecommendationEngine.Result result = observed.recommend(
                req("AAPL", "neutral", "week", "conservative"), BP);

        assertThat(result.candidates())
                .as("notes=%s rejected=%s", result.notes(), result.rejected()).isNotEmpty();
        assertThat(result.candidates()).allSatisfy(candidate ->
                assertThat(candidate.freshness()).isEqualTo("STALE"));
        assertThat(result.notes()).anySatisfy(note ->
                assertThat(note).contains("market is closed").contains("PRIOR CLOSE"));
    }

    @Test
    void everyViableExpiryReachesDecisionPolicyBeforePerFamilySelection() {
        MarketDataProvider provider = staleObservedProvider(CLOCK);
        RecommendationEngine observed = new RecommendationEngine(
                new MarketDataService(List.of(provider), List.of(), List.of()), CLOCK);
        RecommendationEngine.Request request = new RecommendationEngine.Request(
                "AAPL", "bullish", "month", "aggressive", null, null, null,
                List.of("LONG_CALL"), true, false, "DIRECTIONAL", null, null);

        RecommendationEngine.Result result = observed.recommend(request, BP);
        Set<String> expirations = result.candidates().stream()
                .filter(candidate -> candidate.strategy().equals("LONG_CALL"))
                .flatMap(candidate -> candidate.legs().stream())
                .map(LegView::expiration)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(expirations)
                .as("raw EV/max-loss must not collapse expiry alternatives before DecisionPolicy")
                .hasSizeGreaterThan(1);
    }

    @Test
    void boundedStrikeAndWidthAlternativesReachDecisionPolicy() {
        MarketDataProvider provider = staleObservedProvider(CLOCK);
        RecommendationEngine observed = new RecommendationEngine(
                new MarketDataService(List.of(provider), List.of(), List.of()), CLOCK);
        RecommendationEngine.Request request = new RecommendationEngine.Request(
                "AAPL", "neutral", "30d", "aggressive", null, null, null,
                List.of("CREDIT_PUT_SPREAD"), true, false, "INCOME", null, null);

        RecommendationEngine.Result result = observed.recommend(request, BP);
        var byExpiration = result.candidates().stream()
                .filter(candidate -> candidate.strategy().equals("CREDIT_PUT_SPREAD"))
                .collect(java.util.stream.Collectors.groupingBy(candidate ->
                        candidate.legs().getFirst().expiration()));

        assertThat(byExpiration).isNotEmpty();
        assertThat(byExpiration).hasSizeLessThanOrEqualTo(4);
        assertThat(byExpiration.values()).allSatisfy(packages ->
                assertThat(packages).hasSizeLessThanOrEqualTo(StrategyBuilder.MAX_SEARCH_ALTERNATIVES));
        assertThat(result.candidates().stream()
                .filter(candidate -> candidate.strategy().equals("CREDIT_PUT_SPREAD")))
                .hasSizeLessThanOrEqualTo(4 * StrategyBuilder.MAX_SEARCH_ALTERNATIVES);
        assertThat(byExpiration.values()).anySatisfy(packages -> {
            assertThat(packages).hasSizeBetween(2, StrategyBuilder.MAX_SEARCH_ALTERNATIVES);
            assertThat(packages.stream().map(Candidate::label).distinct().count())
                    .isEqualTo(packages.size());
        });
    }

    @Test
    void internalIncomeFallbackIsThirtySessionsAndNeverRewritesAnExplicitHorizon() {
        assertThat(RecommendationEngine.effectiveHorizon(
                null, io.liftandshift.strikebench.strategy.StrategyIntent.INCOME)).isEqualTo("30d");
        assertThat(RecommendationEngine.effectiveHorizon(
                "1d", io.liftandshift.strikebench.strategy.StrategyIntent.INCOME)).isEqualTo("1d");
    }

    @Test
    void ironCondorRejectsPennyCreditAgainstTenDollarWings() {
        Candidate pennyCondor = condorCandidate(100L, 99_900L); // $1 total credit / $1,000 gross width
        Candidate viableCondor = condorCandidate(10_000L, 90_000L); // $100 / $1,000 = 10%

        assertThat(RecommendationEngine.packageViability(StrategyFamily.IRON_CONDOR, pennyCondor))
                .contains("is 0.1%")
                .contains("minimum 10%")
                .contains("not an automatic recommendation");
        assertThat(RecommendationEngine.packageViability(StrategyFamily.IRON_CONDOR, viableCondor))
                .isNull();
    }

    @Test
    void ironCondorRejectsWildlyAsymmetricProtectionEvenWhenCreditClearsOldFloor() {
        Candidate brokenWing = condorCandidate(10_500L, 179_500L,
                "575", "594", "788", "792");

        assertThat(RecommendationEngine.packageViability(StrategyFamily.IRON_CONDOR, brokenWing))
                .contains("5.5% of the widest wing")
                .contains("21.1% of the wider wing")
                .contains("broken-wing package")
                .contains("not an automatic recommendation");
    }

    private static Candidate condorCandidate(long creditCents, long maxLossCents) {
        return condorCandidate(creditCents, maxLossCents, "90", "100", "110", "120");
    }

    private static Candidate condorCandidate(long creditCents, long maxLossCents,
                                              String longPut, String shortPut,
                                              String shortCall, String longCall) {
        return new Candidate("IRON_CONDOR", "Iron condor", "range_credit", "four-leg package",
                List.of(
                        new LegView("BUY", "PUT", longPut, "2026-08-21", 1, "0.10", 100, "OPEN"),
                        new LegView("SELL", "PUT", shortPut, "2026-08-21", 1, "0.40", 100, "OPEN"),
                        new LegView("SELL", "CALL", shortCall, "2026-08-21", 1, "0.40", 100, "OPEN"),
                        new LegView("BUY", "CALL", longCall, "2026-08-21", 1, "0.10", 100, "OPEN")),
                1, creditCents, creditCents, maxLossCents, List.of(), 0.50, 0L,
                0.50, "DELAYED", List.of(), 0.50, "range income", "credit", "wing risk",
                "breakout", "four defined-risk legs", "INCOME", List.of("INCOME"),
                0.20, null, null, null, false, null, null);
    }

    private static RecommendationEngine.Request intentReq(String intent, RecommendationEngine.Holdings holdings,
                                                          RecommendationEngine.Filters filters) {
        return new RecommendationEngine.Request("AAPL", null, "month", "balanced", null, null, null, null,
                true, false, intent, holdings, filters);
    }

    @Test
    void fabricatedDemoHeadlinesNeverBecomeCandidateEventWarnings() {
        RecommendationEngine.Result result = engine.recommend(
                req("QQQ", "bullish", "month", "balanced"), BP);

        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates()).allSatisfy(candidate ->
                assertThat(candidate.warnings()).noneSatisfy(warning -> assertThat(warning)
                        .containsIgnoringCase("event-like news")));
    }

    @Test
    void incomeIntentSelectsIncomeFamiliesWithYieldAndAssignmentMetrics() {
        RecommendationEngine.Holdings h = new RecommendationEngine.Holdings(100, 20_000L, null);
        RecommendationEngine.Result result = engine.recommend(intentReq("income", h, null), BP);
        assertThat(result.intent()).isEqualTo("INCOME");
        assertThat(result.candidates()).isNotEmpty();
        for (Candidate c : result.candidates()) {
            StrategyFamily family = StrategyFamily.valueOf(c.strategy());
            assertThat(family.servesIntent(io.liftandshift.strikebench.strategy.StrategyIntent.INCOME)).isTrue();
            assertThat(c.intent()).isEqualTo("INCOME");
            if (c.entryNetPremiumCents() > 0 && !family.multiExpiration()) {
                assertThat(c.assignmentProb()).isNotNull().isBetween(0.0, 1.0);
                assertThat(c.intentNote()).contains("Collect");
            }
        }
        // Covered call against the held shares: share-backed yield, sane magnitude (not a
        // four-digit annualized best case — that bug is pinned here)
        Candidate cc = result.candidates().stream()
                .filter(c -> c.strategy().equals("COVERED_CALL")).findFirst().orElseThrow();
        assertThat(cc.usesHeldShares()).isTrue();
        assertThat(cc.annualizedYieldPct()).isNotNull().isBetween(1.0, 100.0);
        // Defined-risk spreads never quote an annualized "yield" — R:R covers that honestly
        result.candidates().stream()
                .filter(c -> !StrategyFamily.valueOf(c.strategy()).needsStock()
                        && !c.strategy().equals("CASH_SECURED_PUT"))
                .forEach(c -> assertThat(c.annualizedYieldPct()).isNull());
    }

    @Test
    void beginnerCopySeparatesBuyWritePackageDebitFromOptionCredit() {
        RecommendationEngine.Result result = engine.recommend(intentReq("income", null, null), BP);
        Candidate coveredCall = result.candidates().stream()
                .filter(candidate -> candidate.strategy().equals("COVERED_CALL"))
                .filter(candidate -> candidate.legs().stream().anyMatch(leg -> leg.type().equals("STOCK")))
                .findFirst().orElseThrow();
        long optionCredit = coveredCall.legs().stream()
                .filter(leg -> !leg.type().equals("STOCK"))
                .mapToLong(leg -> {
                    long cents = Money.centsFromPrice(new BigDecimal(leg.entryPrice()),
                            Math.multiplyExact((long) leg.ratio() * coveredCall.qty(), leg.multiplier()));
                    return leg.action().equals("SELL") ? cents : -cents;
                }).sum();

        assertThat(coveredCall.entryNetPremiumCents()).isNegative();
        assertThat(optionCredit).isPositive();
        assertThat(coveredCall.beginnerExplanation())
                .contains("complete stock-plus-options package costs "
                        + Money.fmt(-coveredCall.entryNetPremiumCents()))
                .contains("option legs collect " + Money.fmt(optionCredit) + " net")
                .doesNotContain("most you can lose on the options portion");
    }

    @Test
    void exitIntentWritesCoveredCallsAgainstHeldSharesAtTheTargetPrice() {
        // User story: holds 300 AAPL from $200, wants out at $260+
        RecommendationEngine.Holdings h = new RecommendationEngine.Holdings(300, 20_000L, 26_000L);
        RecommendationEngine.Result result = engine.recommend(intentReq("exit", h, null), BP);
        Candidate cc = result.candidates().stream()
                .filter(c -> c.strategy().equals("COVERED_CALL")).findFirst().orElseThrow();
        assertThat(cc.usesHeldShares()).isTrue();
        assertThat(cc.legs()).allSatisfy(l -> assertThat(l.type()).isNotEqualTo("STOCK")); // no stock buy — uses held shares
        assertThat(cc.qty()).isEqualTo(3); // covers all 300 shares
        assertThat(cc.sharesNeeded()).isEqualTo(300);
        assertThat(cc.maxLossCents()).isZero(); // no NEW cash at risk
        assertThat(cc.combinedMaxLossCents()).isPositive(); // but the shares' own downside is disclosed
        assertThat(cc.entryNetPremiumCents()).isPositive();
        // Short strike honors the target sell price
        double strike = Double.parseDouble(cc.legs().getFirst().strike());
        assertThat(strike).isGreaterThanOrEqualTo(260.0);
        assertThat(cc.effectivePrice()).isNotNull();
        assertThat(Double.parseDouble(cc.effectivePrice())).isGreaterThan(260.0);
        assertThat(cc.intentNote()).contains("sell 300 shares").contains("goal");
        assertThat(cc.assignmentProb()).isNotNull();
    }

    @Test
    void assignmentProbabilityDoesNotDoubleCountNestedShortStrikes() {
        LocalDate expiration = TODAY.plusDays(30);
        var call100 = io.liftandshift.strikebench.model.Leg.option(
                io.liftandshift.strikebench.model.LegAction.SELL,
                io.liftandshift.strikebench.model.OptionType.CALL, new BigDecimal("100"), expiration, 1,
                BigDecimal.ONE);
        var call110 = io.liftandshift.strikebench.model.Leg.option(
                io.liftandshift.strikebench.model.LegAction.SELL,
                io.liftandshift.strikebench.model.OptionType.CALL, new BigDecimal("110"), expiration, 1,
                BigDecimal.ONE);

        Double one = RecommendationEngine.assignmentProbabilityFromIvs(
                List.of(call100), List.of(0.30), new BigDecimal("100"), TODAY, 0.30, 0.04);
        Double nested = RecommendationEngine.assignmentProbabilityFromIvs(
                List.of(call100, call110), List.of(0.30, 0.30), new BigDecimal("100"), TODAY, 0.30, 0.04);

        assertThat(nested).isEqualTo(one);
    }

    @Test
    void acquireIntentSellsPutsAtOrBelowTheDesiredBuyPrice() {
        RecommendationEngine.Holdings h = new RecommendationEngine.Holdings(null, null, 24_000L); // want AAPL at $240
        RecommendationEngine.Result result = engine.recommend(intentReq("acquire", h, null), BP);
        Candidate csp = result.candidates().stream()
                .filter(c -> c.strategy().equals("CASH_SECURED_PUT")).findFirst().orElseThrow();
        double strike = Double.parseDouble(csp.legs().getFirst().strike());
        assertThat(strike).isLessThanOrEqualTo(240.0);
        assertThat(csp.qty()).isEqualTo(1); // never silently scale into the whole account
        assertThat(Double.parseDouble(csp.effectivePrice())).isLessThan(strike); // strike minus premium
        assertThat(csp.intentNote()).contains("buy 100 shares").contains("below today's");
        // The blocked naked-put educational rejection should mention undefined risk
        assertThat(result.rejected()).anySatisfy(r -> assertThat(r.strategy()).isEqualTo("NAKED_PUT"));
    }

    @Test
    void cashSecuredPutYieldAndEffectivePriceUseNetPremiumOverFullStrikeCash() {
        engine.withFees(100, 500); // $1/contract + $5/order at entry
        RecommendationEngine.Result result = engine.recommend(intentReq("acquire",
                new RecommendationEngine.Holdings(null, null, 24_000L), null), BP);
        Candidate csp = result.candidates().stream()
                .filter(c -> c.strategy().equals("CASH_SECURED_PUT")).findFirst().orElseThrow();
        assertThat(csp.qty()).isEqualTo(1);
        double strike = Double.parseDouble(csp.legs().getFirst().strike());
        long openingFees = 600;
        long netPremium = csp.entryNetPremiumCents() - openingFees;
        int dte = (int) java.time.temporal.ChronoUnit.DAYS.between(TODAY,
                LocalDate.parse(csp.legs().getFirst().expiration()));
        double expectedYield = Math.round(100.0 * (netPremium / (strike * 100.0 * 100.0))
                * (365.0 / Math.max(1, dte)) * 100.0) / 100.0;
        assertThat(csp.annualizedYieldPct()).isEqualTo(expectedYield);
        assertThat(Double.parseDouble(csp.effectivePrice()))
                .isCloseTo(strike - netPremium / 10_000.0, org.assertj.core.data.Offset.offset(0.011));
        assertThat(csp.intentNote()).contains("after opening fees");
    }

    @Test
    void hedgeIntentProtectsHeldSharesWithPutsAndCollars() {
        RecommendationEngine.Holdings h = new RecommendationEngine.Holdings(200, 21_000L, null);
        RecommendationEngine.Result result = engine.recommend(intentReq("hedge", h, null), BP);
        assertThat(result.candidates()).isNotEmpty();
        Candidate pp = result.candidates().stream()
                .filter(c -> c.strategy().equals("PROTECTIVE_PUT")).findFirst().orElseThrow();
        assertThat(pp.usesHeldShares()).isTrue();
        assertThat(pp.maxLossCents()).isPositive(); // the hedge costs its debit
        assertThat(pp.intentNote()).contains("Guarantees a sale price");
        assertThat(pp.qty()).isLessThanOrEqualTo(2); // never hedge more lots than held
        for (Candidate c : result.candidates()) {
            assertThat(StrategyFamily.valueOf(c.strategy()).servesIntent(io.liftandshift.strikebench.strategy.StrategyIntent.HEDGE)).isTrue();
        }
    }

    @Test
    void filtersRejectCandidatesWithHumanReadableReasons() {
        RecommendationEngine.Filters strictPop = new RecommendationEngine.Filters(0.99, null, null, null);
        RecommendationEngine.Result r1 = engine.recommend(intentReq("income", null, strictPop), BP);
        assertThat(r1.candidates()).isEmpty();
        assertThat(r1.rejected()).anySatisfy(rej ->
                assertThat(String.join(" ", rej.reasons())).contains("below your minimum"));

        RecommendationEngine.Filters noAssignment = new RecommendationEngine.Filters(null, 0.0001, null, null);
        RecommendationEngine.Result r2 = engine.recommend(intentReq("income", null, noAssignment), BP);
        assertThat(r2.rejected()).anySatisfy(rej ->
                assertThat(String.join(" ", rej.reasons())).contains("Assignment probability"));

        RecommendationEngine.Filters richYield = new RecommendationEngine.Filters(null, null, 500.0, null);
        RecommendationEngine.Holdings h = new RecommendationEngine.Holdings(100, 20_000L, null);
        RecommendationEngine.Result r3 = engine.recommend(intentReq("income", h, richYield), BP);
        assertThat(r3.candidates()).isEmpty();
        assertThat(r3.rejected()).anySatisfy(rej ->
                assertThat(String.join(" ", rej.reasons())).containsAnyOf("yield", "minimum"));
    }

    @Test
    void unknownIntentIsRejectedLoudly() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                engine.recommend(intentReq("yolo", null, null), BP))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("intent");
    }

    @Test
    void bullishConservativeReturnsDefinedRiskCandidatesWithFullNarratives() {
        RecommendationEngine.Result result = engine.recommend(req("AAPL", "bullish", "month", "conservative"), BP);
        assertThat(result.riskMode()).isEqualTo("CONSERVATIVE");
        assertThat(result.candidates()).isNotEmpty();
        for (Candidate c : result.candidates()) {
            StrategyFamily family = StrategyFamily.valueOf(c.strategy());
            assertThat(family.definedRisk()).isTrue();
            assertThat(c.maxLossCents()).isPositive().isLessThanOrEqualTo(result.riskBudgetCents());
            assertThat(c.legs()).isNotEmpty();
            assertThat(c.whyConsidered()).isNotBlank();
            assertThat(c.bestUpside()).isNotBlank();
            assertThat(c.biggestRisk()).isNotBlank();
            assertThat(c.wouldInvalidate()).isNotBlank();
            assertThat(c.beginnerExplanation()).isNotBlank();
            assertThat(c.confidence()).isBetween(0.0, 1.0);
            // conservative risk budget = 1% of $100k = $1,000
            assertThat(result.riskBudgetCents()).isEqualTo(100_000L);
        }
        assertThat(result.disclaimer()).containsIgnoringCase("not financial advice");
    }

    @Test
    void retiredRiskModesAreRejectedInsteadOfSilentlyChangingTheBudget() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                engine.recommend(req("AAPL", "bullish", "month", "learning"), BP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riskMode");
    }

    @Test
    void overBudgetTeachingReasonNamesOneLotRiskAndPlanBudget() {
        RecommendationEngine.Request request = new RecommendationEngine.Request(
                "AAPL", "bullish", "month", "conservative", 1_000L, null, null,
                List.of("LONG_CALL"), true, false, "DIRECTIONAL", null, null);
        RecommendationEngine.Result result = engine.recommend(request, BP);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.rejected()).filteredOn(rejection -> rejection.strategy().equals("LONG_CALL"))
                .singleElement().satisfies(rejection -> {
            assertThat(rejection.strategy()).isEqualTo("LONG_CALL");
            assertThat(String.join(" ", rejection.reasons()))
                    .contains("One lot risks")
                    .contains("above this Plan's $10.00 budget");
        });
    }

    @Test
    void neutralConservativeIncludesCreditStructuresAndRejectsNakedCall() {
        RecommendationEngine.Result result = engine.recommend(req("AAPL", "neutral", "month", "conservative"), BP);
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates()).extracting(Candidate::strategy)
                .anyMatch(s -> s.equals("IRON_CONDOR") || s.equals("CREDIT_PUT_SPREAD") || s.equals("CREDIT_CALL_SPREAD"));
        assertThat(result.rejected()).anySatisfy(r -> {
            assertThat(r.strategy()).isEqualTo("NAKED_CALL");
            assertThat(String.join(" ", r.reasons())).containsIgnoringCase("undefined risk");
        });
    }

    @Test
    void everyRiskModeSeesTheSameDefinedRiskCatalog() {
        // Risk modes differ ONLY by budget: the family sets offered for the same view must be
        // identical across all three (sizing/qty may differ; naked structures stay blocked).
        RecommendationEngine.Result cons = engine.recommend(req("AAPL", "neutral", "month", "conservative"), BP);
        RecommendationEngine.Result aggr = engine.recommend(req("AAPL", "neutral", "month", "aggressive"), BP);
        Set<String> consFams = new java.util.HashSet<>();
        cons.candidates().forEach(c -> consFams.add(c.strategy()));
        cons.rejected().forEach(r -> consFams.add(r.strategy()));
        Set<String> aggrFams = new java.util.HashSet<>();
        aggr.candidates().forEach(c -> aggrFams.add(c.strategy()));
        aggr.rejected().forEach(r -> aggrFams.add(r.strategy()));
        assertThat(consFams).isEqualTo(aggrFams);
        assertThat(cons.candidates()).extracting(Candidate::strategy).noneMatch(f -> StrategyFamily.valueOf(f).blockedByDefault());
    }

    @Test
    void zeroDteExcludedByDefault() {
        RecommendationEngine.Result result = engine.recommend(req("SPY", "neutral", "0DTE", "aggressive"), BP);
        for (Candidate c : result.candidates()) {
            for (LegView leg : c.legs()) {
                if (leg.expiration() != null) {
                    assertThat(LocalDate.parse(leg.expiration())).isAfter(TODAY);
                }
            }
        }
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).containsIgnoringCase("0DTE"));
    }

    @Test
    void zeroDteAllowedWhenOptedIn() {
        RecommendationEngine.Request request = new RecommendationEngine.Request(
                "SPY", "neutral", "0DTE", "aggressive", null, null, null, null,
                true, true, null, null, null);
        RecommendationEngine.Result result = engine.recommend(request, BP);
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates()).anySatisfy(c -> {
            assertThat(c.legs()).anySatisfy(l -> assertThat(l.expiration()).isEqualTo(TODAY.toString()));
            assertThat(c.warnings()).anySatisfy(w -> assertThat(w).contains("0DTE"));
        });
    }

    @Test
    void nonOptionableSymbolYieldsNoCandidates() {
        RecommendationEngine.Result result = engine.recommend(req("VTSAX", "bullish", "month", "balanced"), BP);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).containsIgnoringCase("no listed options"));
    }

    @Test
    void unknownSymbolYieldsNote() {
        RecommendationEngine.Result result = engine.recommend(req("ZZZZ", "bullish", "month", "balanced"), BP);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).containsIgnoringCase("no market data"));
    }

    @Test
    void minConfidenceFiltersEverything() {
        RecommendationEngine.Request request = new RecommendationEngine.Request(
                "AAPL", "bullish", "month", "balanced", null, null, 0.999, null,
                true, false, null, null, null);
        RecommendationEngine.Result result = engine.recommend(request, BP);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.rejected()).anySatisfy(r ->
                assertThat(String.join(" ", r.reasons())).containsIgnoringCase("confidence"));
    }

    @Test
    void allowedStrategiesWhitelistRespected() {
        RecommendationEngine.Request request = new RecommendationEngine.Request(
                "AAPL", "bullish", "month", "aggressive", null, null, null, List.of("LONG_CALL"),
                true, false, null, null, null);
        RecommendationEngine.Result result = engine.recommend(request, BP);
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates()).allSatisfy(c -> assertThat(c.strategy()).isEqualTo("LONG_CALL"));
    }

    @Test
    void riskBudgetScalesWithPctAndCapsQty() {
        RecommendationEngine.Request request = new RecommendationEngine.Request(
                "AAPL", "bullish", "month", "balanced", null, 0.10, null, null,
                true, false, null, null, null);
        RecommendationEngine.Result result = engine.recommend(request, BP);
        assertThat(result.riskBudgetCents()).isEqualTo(1_000_000L); // 10% of $100k
        for (Candidate c : result.candidates()) {
            assertThat(c.maxLossCents()).isLessThanOrEqualTo(result.riskBudgetCents());
            assertThat(c.qty()).isBetween(1, 5);
        }
    }

    @Test
    void candidateConstructionReturnsTheCompleteFieldForDecisionPolicy() {
        // The engine constructs the COMPLETE field. DecisionPolicy is the only component allowed
        // to rank it; presentation may summarize with diverse representatives but cannot hide one.
        RecommendationEngine.Result result = engine.recommend(req("AAPL", "neutral", "month", "aggressive"), BP);
        List<Candidate> c = result.candidates();
        assertThat(c.size()).isGreaterThanOrEqualTo(2);
        // Every defined-risk family that fits the thesis and passed screens is present: no top-N
        // cap and no structural-group trim (a neutral month view offers many structures).
        assertThat(c.size()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void volatileThesisOffersStraddlesAndStrangles() {
        // "Big move, direction unknown" must offer the canonical structures for it —
        // long straddle/strangle are first-class families, defined risk (the debit).
        RecommendationEngine.Result result = engine.recommend(req("AAPL", "volatile", "month", "balanced"), BP);
        Set<String> offered = new java.util.HashSet<>();
        for (Candidate c : result.candidates()) offered.add(c.strategy());
        assertThat(offered).containsAnyOf("LONG_STRADDLE", "LONG_STRANGLE");
        for (Candidate c : result.candidates()) {
            if (!c.strategy().equals("LONG_STRADDLE") && !c.strategy().equals("LONG_STRANGLE")) continue;
            assertThat(c.legs()).hasSize(2);
            assertThat(c.entryNetPremiumCents()).isNegative();              // both legs bought
            assertThat(c.maxLossCents()).isEqualTo(-c.entryNetPremiumCents()); // risk = the debit
            assertThat(c.maxProfitCents()).isNull();                        // uncapped either way
            assertThat(c.breakevens()).hasSize(2);                          // one below, one above
        }
    }

    @Test
    void heldSharesSurfaceTheCompositeIncomeStructures() {
        RecommendationEngine.Holdings h = new RecommendationEngine.Holdings(100, 20_000L, null);
        RecommendationEngine.Result result = engine.recommend(intentReq("INCOME", h, null), BP);
        var families = result.candidates().stream().map(Candidate::strategy).toList();
        assertThat(families).contains("COVERED_CALL");
        assertThat(families)
                .as("the folded-Phase-9 composites compete beside the covered call, never replacing it")
                .contains("COVERED_STRANGLE", "COVERED_CALL_PUT_SPREAD", "COVERED_CALL_CALL_OVERLAY");
        result.candidates().stream()
                .filter(c -> c.strategy().startsWith("COVERED_"))
                .forEach(c -> assertThat(c.usesHeldShares())
                        .as(c.strategy() + " rides the held shares").isTrue());
    }

    private java.util.List<String> incomeFan(String view) {
        return engine.recommend(new RecommendationEngine.Request("AAPL", view, "month", "balanced",
                        null, null, null, null, true, false, "INCOME", null, null), BP)
                .candidates().stream().map(Candidate::strategy).toList();
    }

    @Test
    void anExplicitViewTiltsIncomeRankingButNeverGatesTheCatalog() {
        // REGRESSION (owner report): "earn income · bearish" surfaced a SINGLE bear call spread while
        // "neutral" showed a full fan. The market view is a ranking tilt for objective flows, never a
        // catalog gate, so the income catalog must be identical across declared views — and never one trade.
        var bearish = incomeFan("bearish");
        var neutral = incomeFan("neutral");
        var bullish = incomeFan("bullish");
        assertThat(bearish).as("a bearish income view must still return a diverse fan, not one trade")
                .hasSizeGreaterThan(1);
        assertThat(bearish).as("view must not change WHICH income structures are offered")
                .containsExactlyInAnyOrderElementsOf(neutral);
        assertThat(bullish).containsExactlyInAnyOrderElementsOf(neutral);
    }

    @Test
    void noIncomeCandidateContradictsEarningIncome() {
        // Objective-coherence gate: nothing offered under INCOME may pay to be held (a pure-option,
        // single-expiration income structure must COLLECT a credit), no diagonal may sit on the income
        // menu, and no bounded structure that cannot profit may be offered.
        for (String view : List.of("bearish", "bullish", "neutral")) {
            var candidates = engine.recommend(new RecommendationEngine.Request("AAPL", view, "month", "balanced",
                    null, null, null, null, true, false, "INCOME", null, null), BP).candidates();
            assertThat(candidates).as(view + " income fan is non-empty").isNotEmpty();
            for (Candidate c : candidates) {
                StrategyFamily fam = StrategyFamily.valueOf(c.strategy());
                assertThat(c.strategy()).as("no diagonal on the income menu").doesNotContain("DIAGONAL");
                if (!fam.multiExpiration() && !fam.needsStock()) {
                    assertThat(c.entryNetPremiumCents())
                            .as(view + " income " + c.strategy() + " must collect a credit, not pay a debit")
                            .isPositive();
                }
                if (c.maxProfitCents() != null) {
                    assertThat(c.maxProfitCents())
                            .as(c.strategy() + " must be able to profit at executable prices").isPositive();
                }
            }
        }
    }

    @Test
    void incomeCatalogNamesUndefinedRiskFamiliesAsExcludedInsteadOfSilentlyDroppingThem() {
        RecommendationEngine.Result result = engine.recommend(new RecommendationEngine.Request(
                "AAPL", "neutral", "month", "balanced", null, null, null, null,
                true, false, "INCOME", null, null), BP);

        assertThat(result.candidates()).extracting(Candidate::strategy)
                .doesNotContain("NAKED_CALL", "NAKED_PUT", "SHORT_STRADDLE", "SHORT_STRANGLE");
        assertThat(result.rejected()).extracting(Rejection::strategy)
                .contains("NAKED_CALL", "NAKED_PUT", "SHORT_STRADDLE", "SHORT_STRANGLE");
        assertThat(result.rejected())
                .filteredOn(rejection -> java.util.Set.of(
                        "NAKED_CALL", "NAKED_PUT", "SHORT_STRADDLE", "SHORT_STRANGLE")
                        .contains(rejection.strategy()))
                .allSatisfy(rejection -> assertThat(rejection.reasons())
                        .anySatisfy(reason -> assertThat(reason)
                                .contains("undefined risk").contains("blocked by default")));

        var accounted = new java.util.HashSet<String>();
        result.candidates().forEach(candidate -> accounted.add(candidate.strategy()));
        result.rejected().forEach(rejection -> accounted.add(rejection.strategy()));
        var applicableCatalog = java.util.Arrays.stream(StrategyFamily.values())
                .filter(family -> family.servesIntent(
                        io.liftandshift.strikebench.strategy.StrategyIntent.INCOME))
                .map(Enum::name)
                .toList();
        assertThat(accounted)
                .as("every income family is either offered or returned with an exact exclusion reason")
                .containsAll(applicableCatalog);
    }
}
