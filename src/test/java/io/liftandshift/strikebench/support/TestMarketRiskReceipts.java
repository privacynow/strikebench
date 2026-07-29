package io.liftandshift.strikebench.support;

import io.liftandshift.strikebench.eval.EvalContext;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.ScenarioStory;
import io.liftandshift.strikebench.market.OptionTime;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.pricing.ProbabilityMap;
import io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Attaches the canonical production risk-neutral receipt to option-only evaluator fixtures. */
public final class TestMarketRiskReceipts {
    private TestMarketRiskReceipts() {}

    public static Candidate attach(Candidate candidate, EvalContext context) {
        if (candidate == null || candidate.price() == null || !candidate.price().priced()) {
            throw new IllegalArgumentException("test candidate requires an exact package price");
        }
        if (context == null || context.atmIv() == null || !(context.atmIv() > 0)
                || !context.timeToExpiry().hasModelTime()) {
            throw new IllegalArgumentException("test context requires IV and a model clock");
        }
        if (Boolean.TRUE.equals(candidate.usesHeldShares())) {
            throw new IllegalArgumentException("this fixture helper supports option-only packages");
        }
        List<Leg> legs = candidate.legs().stream().map(LegView::toLeg).toList();
        PayoffCurve marked = PayoffCurve.of(legs, candidate.qty());
        long adjustment = candidate.price().grossPackageNetCents()
                - marked.entryNetPremiumCents();
        PayoffCurve curve = PayoffCurve.of(legs, candidate.qty(), adjustment);
        List<BigDecimal> shortStrikes = legs.stream()
                .filter(leg -> !leg.isStock() && leg.action() == LegAction.SELL)
                .map(Leg::strike).distinct().toList();
        RiskNeutralAnalyzer.Receipt receipt = RiskNeutralAnalyzer.analyze(curve,
                candidate.price(), context.underlyingCents(), context.atmIv(),
                context.timeToExpiry(), context.riskFreeRate(), shortStrikes);
        return new Candidate(candidate.strategy(), candidate.displayName(),
                candidate.structureGroup(), candidate.label(), candidate.legs(), candidate.qty(),
                candidate.price(), candidate.maxProfitCents(), candidate.maxLossCents(),
                candidate.breakevens(), candidate.liquidityScore(), candidate.freshness(), candidate.warnings(),
                candidate.confidence(), candidate.whyConsidered(), candidate.bestUpside(),
                candidate.biggestRisk(), candidate.wouldInvalidate(),
                candidate.beginnerExplanation(), candidate.intent(), candidate.intents(),
                candidate.shortSideExpirationItmProb(), candidate.annualizedOpeningPremiumRatePct(),
                candidate.effectivePrice(), candidate.intentNote(), candidate.usesHeldShares(),
                candidate.sharesNeeded(), candidate.combinedMaxLossCents(), receipt);
    }

    /**
     * A typed market-implied receipt for tests whose subject is policy composition rather than the
     * risk-neutral calculator. It starts with the production analyzer so the price/input
     * fingerprint is genuine, then substitutes the explicitly named policy fixture outputs.
     */
    public static RiskNeutralAnalyzer.Receipt receipt(PackagePriceReceipt price,
                                                       Double pop, Long expectedValueCents) {
        if (price == null || !price.priced() || pop == null || expectedValueCents == null) {
            return RiskNeutralAnalyzer.Receipt.unavailable(
                    "This fixture deliberately has no market-implied probability or EV.");
        }
        LocalDate expiry = LocalDate.of(2026, 8, 21);
        Leg leg = Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("100"), expiry,
                1, new BigDecimal("2.00"));
        PayoffCurve curve = PayoffCurve.of(List.of(leg), 1);
        OptionTime.Measure time = OptionTime.toExpiry(
                Instant.parse("2026-07-22T15:30:00Z"), expiry);
        RiskNeutralAnalyzer.Receipt seed = RiskNeutralAnalyzer.analyze(curve, price,
                10_000L, 0.30, time, 0.04, List.of(new BigDecimal("100")));
        ProbabilityMap.Result probability = new ProbabilityMap.Result(pop, 0, 0,
                Math.max(0, 1 - pop), 0, 0, List.of(), "typed policy fixture");
        List<RiskNeutralAnalyzer.ScenarioMass> masses = java.util.Arrays.stream(
                        ScenarioStory.values())
                .map(story -> new RiskNeutralAnalyzer.ScenarioMass(
                        story, story.underlyingMoveFraction(),
                        1.0 / ScenarioStory.values().length))
                .toList();
        return new RiskNeutralAnalyzer.Receipt(seed.schemaVersion(), seed.modelVersion(), true,
                null, seed.fingerprint(), seed.priceFingerprint(), seed.underlyingCents(),
                seed.marketIv(), seed.riskFreeRate(), seed.time(), probability,
                expectedValueCents, List.of(), masses);
    }

    public static RiskNeutralAnalyzer.Receipt receipt(Double pop, Long expectedValueCents) {
        return receipt(TestPrices.optionOnly(0L), pop, expectedValueCents);
    }
}
