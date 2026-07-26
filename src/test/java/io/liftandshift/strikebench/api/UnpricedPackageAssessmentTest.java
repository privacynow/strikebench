package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.eval.EvalContext;
import io.liftandshift.strikebench.eval.IvContext;
import io.liftandshift.strikebench.eval.ManagementPlan;
import io.liftandshift.strikebench.eval.ManagementPlanner;
import io.liftandshift.strikebench.eval.StrategyEvaluation;
import io.liftandshift.strikebench.eval.StrategyEvaluator;
import io.liftandshift.strikebench.eval.StrategySpec;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.paper.AccountService;
import io.liftandshift.strikebench.paper.AuditLog;
import io.liftandshift.strikebench.paper.MarksSource;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.paper.ProtocolEvaluator;
import io.liftandshift.strikebench.paper.TradePreview;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE §3.2 regression test for the canonical package-price receipt (§7.2).
 *
 * <p>A blocked ticket — an ordinary contract with no mark — must still produce a COMPLETE, degraded
 * assessment: every lane either states a fact it can prove or states that it is unavailable and
 * why. It must never throw, and it must never substitute a fabricated zero for the absent price.
 *
 * <p>Before the receipt existed, the candidate's package net was a primitive {@code long} that was
 * silently 0 on every refusal exit: the pipeline completed, but it completed by inventing a
 * free package. When the field became a boxed {@code Long} the invention stopped and ~12 unboxing
 * sites began throwing NPE instead — the assessment collapsed to "unavailable" wholesale (and
 * crashed outright anywhere the caller did not catch). This test pins the third behaviour, the
 * only correct one: complete, degraded, and honest about the absence.
 */
class UnpricedPackageAssessmentTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"),
            ZoneId.of("America/New_York"));
    private static final LocalDate EXP = LocalDate.of(2026, 8, 21);

    private Db db;
    private TradeService trades;
    private String accountId;

    /** The underlying is quoted; the contract is not. Ordinary data absence, not a broken market. */
    private static final class NoOptionMarks implements MarksSource {
        @Override public Optional<BigDecimal> underlyingMark(String symbol) {
            return Optional.of(new BigDecimal("100.00"));
        }
        @Override public Optional<Long> underlyingAsOfMs(String symbol) {
            return Optional.of(1_785_000_000_000L);
        }
        @Override public Optional<DataEvidence> underlyingEvidence(String symbol, String worldId) {
            return Optional.of(DataEvidence.of("test-demo", Freshness.FIXTURE));
        }
        @Override public Optional<BigDecimal> closeOn(String symbol, LocalDate date) { return Optional.empty(); }
        @Override public Optional<LegMark> legMark(String symbol, Leg leg) { return Optional.empty(); }
    }

    /** Nothing is quoted — not even the stock. The spot itself is the absent financial fact. */
    private static final class NoMarksAtAll implements MarksSource {
        @Override public Optional<BigDecimal> underlyingMark(String symbol) { return Optional.empty(); }
        @Override public Optional<LegMark> legMark(String symbol, Leg leg) { return Optional.empty(); }
    }

    @BeforeEach
    void setUp() {
        db = TestDb.fresh();
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        AuditLog audit = new AuditLog(db, CLOCK);
        trades = new TradeService(db, cfg, new NoOptionMarks(), audit, CLOCK);
        accountId = new AccountService(db, cfg, audit, CLOCK).getOrCreateDefault().id();
    }

    private TradeService unquotedMarket() {
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        return new TradeService(db, cfg, new NoMarksAtAll(), new AuditLog(db, CLOCK), CLOCK);
    }

    @AfterEach
    void closeDb() {
        if (db != null) db.close();
    }

    /** A plain credit put spread: nothing exotic, just a contract the feed cannot mark. */
    private TradeService.OpenRequest creditPutSpread() {
        List<Leg> legs = List.of(
                Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("95"), EXP, 1, BigDecimal.ZERO),
                Leg.option(LegAction.BUY, OptionType.PUT, new BigDecimal("90"), EXP, 1, BigDecimal.ZERO));
        return new TradeService.OpenRequest(accountId, "AAPL", "CREDIT_PUT_SPREAD", 1, legs, "neutral",
                "month", "balanced", "INCOME", false, null, null, "PLAN", "PROPOSED",
                OrderInstruction.market());
    }

    /**
     * A DIFFERENT refusal exit — the leg is already expired, so the package is refused before any
     * mark is even consulted. Both exits publish the same unpriced receipt, so both must degrade
     * identically; this guards against fixing one refusal path and leaving the other four.
     */
    private TradeService.OpenRequest expiredLeg() {
        List<Leg> legs = List.of(Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("95"),
                LocalDate.of(2026, 6, 19), 1, BigDecimal.ZERO));
        return new TradeService.OpenRequest(accountId, "AAPL", "CASH_SECURED_PUT", 1, legs, "neutral",
                "month", "balanced", "INCOME", false, null, null, "PLAN", "PROPOSED",
                OrderInstruction.market());
    }

    private static EvalContext ctx() {
        return new EvalContext("AAPL", 10_000L, LocalDate.parse("2026-07-08"), 44, 0.30, 0.25,
                List.of(0.20, 0.22, 0.24, 0.26, 0.28, 0.30, 0.32, 0.34, 0.36, 0.38, 0.40, 0.29),
                10_000_000L, true, 65, 0, 0.04,
                DataEvidence.of("treasury", Freshness.EOD), null);
    }

    private static StrategySpec spec() {
        return new StrategySpec("AAPL", "CREDIT_PUT_SPREAD", "INCOME", "month", "NEUTRAL",
                "BALANCED", "decision");
    }

    @Test
    void anUnmarkedContractRefusesWithAReasonInsteadOfPricingThePackageAtZero() {
        TradePreview preview = trades.analyze(creditPutSpread());

        assertThat(preview.ok()).isFalse();
        assertThat(preview.blockReasons()).anySatisfy(reason ->
                assertThat(reason).contains("No market or model mark"));

        PackagePriceReceipt price = preview.price();
        assertThat(price.priced()).isFalse();
        assertThat(price.grossPackageNetCents()).isNull();
        assertThat(price.optionNetPremiumCents()).isNull();
        assertThat(price.valuationBasis()).isEqualTo(PackagePriceReceipt.ValuationBasis.UNAVAILABLE);
        assertThat(price.unavailableReason()).contains("No market or model mark");
    }

    /**
     * THE regression: the whole evaluation pipeline over a genuinely unpriced package. Every
     * producer runs; none of them throws; none of them prints a zero it cannot prove.
     */
    @Test
    void theWholeEvaluationPipelineDegradesCompletelyRatherThanThrowing() {
        TradeService.OpenRequest request = creditPutSpread();
        TradePreview preview = trades.analyze(request);
        Candidate candidate = TradeController.exactPreviewCandidate(request, preview);
        assertThat(candidate.price().priced()).isFalse();

        StrategyEvaluation evaluation = new StrategyEvaluator().assessExact(candidate, spec(), ctx(),
                preview.ok(), preview.blockReasons(), 130L);

        // COMPLETE: every lane is present and answers for itself.
        assertThat(evaluation.risk()).isNotNull();
        assertThat(evaluation.capital()).isNotNull();
        assertThat(evaluation.volatility()).isNotNull();
        assertThat(evaluation.evidence()).isNotNull();
        assertThat(evaluation.management()).isNotNull();
        assertThat(evaluation.score()).isNotNull();
        assertThat(evaluation.assessment()).isNotNull();
        assertThat(evaluation.explanation()).isNotNull();
        assertThat(evaluation.stance()).isNotNull();
        assertThat(evaluation.participation()).isNotNull();
        assertThat(evaluation.ivContext()).isNotNull();

        // DEGRADED, never endorsed: an unpriced package cannot pass the mechanical gate, and the
        // absence itself is one of the stated reasons rather than a silent omission.
        assertThat(evaluation.score().gatePassed()).isFalse();
        assertThat(evaluation.score().gateFailures())
                .anySatisfy(failure -> assertThat(failure).contains("No market or model mark"));
        assertThat(evaluation.assessment().mechanics().eligible()).isFalse();
        assertThat(evaluation.assessment().economics().verdict())
                .isEqualTo(EconomicAssessment.Verdict.UNAVAILABLE);

        // NO FABRICATED PRICE: the entry side is unavailable rather than a "flat" $0 package.
        assertThat(evaluation.ivContext().entrySide()).isEqualTo(IvContext.EntrySide.UNAVAILABLE);
        assertThat(evaluation.ivContext().message()).contains("No market or model mark");

        // The management protocol still renders its time and invalidation rules, but its PRICE
        // rules carry no trigger — a stop at "50% of the credit" is meaningless with no credit.
        assertThat(evaluation.management().side()).isEqualTo(ProtocolEvaluator.Side.UNPRICED);
        assertThat(evaluation.management().rules()).isNotEmpty();
        assertThat(evaluation.management().rules()).filteredOn(r ->
                        ProtocolEvaluator.TAKE_PROFIT.equals(r.rule())
                                || ProtocolEvaluator.STOP_LOSS.equals(r.rule()))
                .isNotEmpty()
                .allSatisfy(rule -> assertThat(rule.triggerPnlCents()).isNull());
        assertThat(evaluation.management().rules()).anySatisfy(rule ->
                assertThat(rule.rule()).isEqualTo(ProtocolEvaluator.INVALIDATION));

        // The payoff/tail lanes state their absence rather than drawing a curve off unmarked legs.
        assertThat(evaluation.risk().terminalPayoff().available()).isFalse();
        assertThat(evaluation.risk().terminalPayoff().unavailableReason())
                .contains("No market or model mark");
        assertThat(evaluation.risk().scenarios()).isEmpty();
        assertThat(evaluation.risk().evHistVolCents()).isNull();

        // Stance keeps the Greeks it can prove from geometry, and withholds the payoff-derived
        // participation it cannot.
        assertThat(evaluation.participation().terminalUpsideCaptureBps()).isNull();
        assertThat(evaluation.participation().terminalBasis()).contains("No market or model mark");

        // The explanation names the absence instead of asserting a debit or a credit.
        assertThat(evaluation.explanation().failureModes())
                .anySatisfy(mode -> assertThat(mode).contains("No market or model mark"));
    }

    /**
     * §3.1 (review P0 #7): the preview publishes ONE package price. It used to carry the canonical
     * receipt AND two primitive twins, {@code entryNetPremiumCents} and {@code feesOpenCents}. On a
     * priced package the three agreed; on THIS refused one the receipt said "no price, and here is
     * why" while the twins said 0 and 0 — a substituted zero (§3.2) sitting on the wire under the
     * names a surface was most likely to read. The published shape is what pins it.
     */
    @Test
    void aRefusedPreviewPublishesExactlyOnePackagePriceAndNoZeroTwins() {
        TradePreview preview = trades.analyze(creditPutSpread());
        assertThat(preview.ok()).isFalse();

        var wire = io.liftandshift.strikebench.util.Json.MAPPER.valueToTree(preview);
        assertThat(wire.has("entryNetPremiumCents")).isFalse();
        assertThat(wire.has("feesOpenCents")).isFalse();

        // The one authority states the absence, with its reason, in the one place.
        assertThat(wire.at("/price/grossPackageNetCents").isNull()).isTrue();
        assertThat(wire.at("/price/openingFeesCents").isNull()).isTrue();
        assertThat(wire.at("/price/unavailableReason").asText()).contains("No market or model mark");
        assertThat(preview.price().roundTripFeesCents()).isNull();
    }

    /**
     * §3.2: with no commission on the receipt there is no "after costs" to state. Every consumer of
     * the round trip — ticket review, the Plan builder, tracked and Practice analysis — derived it as
     * {@code preview.feesOpenCents() * 2}, which on this package was 0, so the assessment published
     * the package's GROSS expectation under the name {@code marketEvAfterCostsCents} and a $0.00
     * round-trip cost beside it.
     */
    @Test
    void anUnknownCommissionRefusesToStateAnyExpectedValueAfterCosts() {
        TradeService.OpenRequest request = creditPutSpread();
        TradePreview preview = trades.analyze(request);
        Candidate candidate = TradeController.exactPreviewCandidate(request, preview);

        StrategyEvaluation evaluation = new StrategyEvaluator().assessExact(candidate, spec(), ctx(),
                preview.ok(), preview.blockReasons(), preview.price().roundTripFeesCents());
        EconomicAssessment economics = evaluation.assessment().economics();

        assertThat(economics.verdict()).isEqualTo(EconomicAssessment.Verdict.UNAVAILABLE);
        assertThat(economics.estimatedRoundTripFeesCents()).isNull();
        assertThat(economics.marketEvAfterCostsCents()).isNull();
        assertThat(economics.realizedVolEvAfterCostsCents()).isNull();
        assertThat(economics.reasons()).contains(EconomicAssessment.UNKNOWN_FEES_REASON);
        // The refusal is still complete: the mechanical failures are carried, not replaced.
        assertThat(economics.reasons())
                .anySatisfy(reason -> assertThat(reason).contains("No market or model mark"));

        // And it stays null all the way onto the wire, rather than serializing as a free round trip.
        var wire = io.liftandshift.strikebench.util.Json.MAPPER
                .valueToTree(ApiResponses.EvaluationReceipt.of(evaluation));
        assertThat(wire.at("/assessment/economics/estimatedRoundTripFeesCents").isNumber()).isFalse();
    }

    /**
     * The reviewer's exact reproduction, pinned: feed the unpriced receipt straight to the
     * management planner. It used to throw NPE on {@code optionNetPremiumCents().longValue()}.
     */
    @Test
    void theManagementPlannerRendersAnUnpricedProtocolInsteadOfThrowing() {
        TradeService.OpenRequest request = creditPutSpread();
        Candidate candidate = TradeController.exactPreviewCandidate(request, trades.analyze(request));

        ManagementPlan plan = new ManagementPlanner().plan(candidate, spec(), ctx(),
                ProtocolEvaluator.Policy.standard());

        assertThat(plan.side()).isEqualTo(ProtocolEvaluator.Side.UNPRICED);
        assertThat(plan.summary()).contains("no price").contains("No market or model mark");
        // The rules that DO NOT need a price still stand — withholding them would hide guidance
        // the calendar and the structure can prove.
        assertThat(plan.rules()).extracting(ManagementPlan.Rule::rule)
                .contains(ProtocolEvaluator.TIME_EXIT, ProtocolEvaluator.INVALIDATION);
        assertThat(plan.rules()).filteredOn(r -> ProtocolEvaluator.TIME_EXIT.equals(r.rule()))
                .allSatisfy(rule -> assertThat(rule.triggerSessionsToExpiry()).isNotNull());
    }

    /** Every refusal exit publishes the same unpriced receipt, so every one must degrade alike. */
    @Test
    void anExpiredLegRefusalDegradesExactlyAsAnUnmarkedContractDoes() {
        TradeService.OpenRequest request = expiredLeg();
        TradePreview preview = trades.analyze(request);
        assertThat(preview.ok()).isFalse();
        assertThat(preview.price().priced()).isFalse();
        assertThat(preview.price().unavailableReason()).contains("already expired");

        Candidate candidate = TradeController.exactPreviewCandidate(request, preview);
        StrategyEvaluation evaluation = new StrategyEvaluator().assessExact(candidate,
                new StrategySpec("AAPL", "CASH_SECURED_PUT", "INCOME", "month", "NEUTRAL",
                        "BALANCED", "decision"),
                ctx(), preview.ok(), preview.blockReasons(), 130L);

        assertThat(evaluation.management().side()).isEqualTo(ProtocolEvaluator.Side.UNPRICED);
        assertThat(evaluation.score().gatePassed()).isFalse();
        assertThat(evaluation.score().gateFailures())
                .anySatisfy(failure -> assertThat(failure).contains("already expired"));
        assertThat(evaluation.ivContext().entrySide()).isEqualTo(IvContext.EntrySide.UNAVAILABLE);
        assertThat(evaluation.risk().terminalPayoff().available()).isFalse();
    }

    /**
     * §3.2 for the SPOT, the last primitive financial quantity on this record. When no lane owns an
     * underlying mark there is no spot, and the preview must say so: {@code underlyingCents} was a
     * primitive {@code long} that returned 0, which a surface cannot distinguish from a stock that
     * genuinely trades at $0.00 — and 0 is not merely wrong, it is the divisor and the anchor every
     * later percent-move, review benchmark and share-count derives from.
     */
    @Test
    void anUnquotedUnderlyingReportsNoSpotRatherThanAZeroDollarStock() {
        TradePreview preview = unquotedMarket().analyze(creditPutSpread());

        assertThat(preview.ok()).isFalse();
        assertThat(preview.underlyingCents()).isNull();
        // ABSENT WITH A REASON: blockReasons is this record's reason channel, and it names the
        // symbol whose price is missing rather than leaving the null unexplained.
        assertThat(preview.blockReasons()).anySatisfy(reason ->
                assertThat(reason).contains("No current price for AAPL"));

        // On the WIRE, not just in Java: the shared mapper is NON_NULL by default, so without the
        // per-property ALWAYS the key would vanish and the browser would read `undefined`.
        assertThat(io.liftandshift.strikebench.util.Json.write(preview))
                .contains("\"underlyingCents\":null");
    }

    /**
     * The other half of the contract, and the reason this is not a blanket "null on any refusal":
     * a refusal that happens WITH a quoted underlying must still publish the spot it can prove.
     * Nulling a known fact is the same §3.2 violation pointed the other way.
     */
    @Test
    void aRefusalThatStillKnowsTheSpotPublishesIt() {
        TradePreview expired = trades.analyze(expiredLeg());
        assertThat(expired.ok()).isFalse();
        assertThat(expired.underlyingCents()).isEqualTo(10_000L);

        TradePreview unmarkedLegs = trades.analyze(creditPutSpread());
        assertThat(unmarkedLegs.ok()).isFalse();
        assertThat(unmarkedLegs.underlyingCents()).isEqualTo(10_000L);
    }

    /** The API surface the desk actually calls must degrade the same way — no 500, no blank lane. */
    @Test
    void theReviewPayloadPublishesTheDegradedAssessmentRatherThanNoAssessment() {
        TradeService.OpenRequest request = creditPutSpread();
        TradePreview preview = trades.analyze(request);
        Candidate candidate = TradeController.exactPreviewCandidate(request, preview);

        StrategyEvaluation evaluation = new StrategyEvaluator().assessExact(candidate, spec(), ctx(),
                preview.ok(), preview.blockReasons(), 130L);
        ApiResponses.EvaluationReceipt receipt = ApiResponses.EvaluationReceipt.of(evaluation);

        // available=true is the point: the assessment EXISTS and is degraded lane by lane. The
        // wholesale "assessment unavailable" fallback in TradeController.reviewPayload is the
        // failure mode this test forbids — it is for unexpected faults, not for absent prices.
        assertThat(receipt.available()).isTrue();
        assertThat(receipt.unavailableReason()).isNull();
        assertThat(receipt.decisionScore()).isZero();
        assertThat(receipt.viable()).isFalse();
        assertThat(receipt.management()).isNotNull();
        assertThat(receipt.risk()).isNotNull();
        assertThat(receipt.explanation()).isNotNull();
        assertThat(receipt.ivContext()).isNotNull();
    }
}
