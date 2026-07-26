package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.eval.EvalContext;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** Complete two-sided evidence for proving that mechanical refusal does not erase price facts. */
    private static final class TwoSidedMarks implements MarksSource {
        @Override public Optional<BigDecimal> underlyingMark(String symbol) {
            return Optional.of(new BigDecimal("100.00"));
        }
        @Override public Optional<Long> underlyingAsOfMs(String symbol) {
            return Optional.of(1_785_000_000_000L);
        }
        @Override public Optional<DataEvidence> underlyingEvidence(String symbol, String worldId) {
            return Optional.of(DataEvidence.of("test-demo", Freshness.FIXTURE));
        }
        @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
            return Optional.of(new LegMark(new BigDecimal("1.00"), new BigDecimal("1.10"),
                    new BigDecimal("1.05"), 0.30, Freshness.FIXTURE,
                    null, null, null, null,
                    DataEvidence.of("test-demo", Freshness.FIXTURE), 1_785_000_000_000L));
        }
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

    private TradeService pricedMarket() {
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        return new TradeService(db, cfg, new TwoSidedMarks(), new AuditLog(db, CLOCK), CLOCK);
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
                "month", "balanced", "INCOME", false, null, "PLAN", "PROPOSED",
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
                "month", "balanced", "INCOME", false, null, "PLAN", "PROPOSED",
                OrderInstruction.market());
    }

    private TradeService.OpenRequest nakedShortCall() {
        List<Leg> legs = List.of(Leg.option(LegAction.SELL, OptionType.CALL,
                new BigDecimal("105"), EXP, 1, BigDecimal.ZERO));
        return new TradeService.OpenRequest(accountId, "AAPL", "CUSTOM", 1, legs, "neutral",
                "month", "aggressive", "INCOME", false, null, "PLAN", "PROPOSED",
                OrderInstruction.market());
    }

    private static EvalContext ctx() {
        return new EvalContext("AAPL", 10_000L, LocalDate.parse("2026-07-08"), 44, 0.30, 0.25,
                List.of(0.20, 0.22, 0.24, 0.26, 0.28, 0.30, 0.32, 0.34, 0.36, 0.38, 0.40, 0.29),
                10_000_000L, true, 0.04,
                DataEvidence.of("treasury", Freshness.EOD), null);
    }

    @Test
    void anUnmarkedContractRefusesWithAReasonInsteadOfPricingThePackageAtZero() {
        TradePreview preview = trades.analyze(creditPutSpread());

        assertThat(preview.ok()).isFalse();
        assertThat(preview.blockReasons()).anySatisfy(reason ->
                assertThat(reason).contains("No current quote evidence"));

        PackagePriceReceipt price = preview.price();
        assertThat(price.priced()).isFalse();
        assertThat(price.grossPackageNetCents()).isNull();
        assertThat(price.optionNetPremiumCents()).isNull();
        assertThat(price.valuationBasis()).isEqualTo(PackagePriceReceipt.ValuationBasis.UNAVAILABLE);
        assertThat(price.unavailableReason()).contains("No current quote evidence");
    }

    /**
     * THE regression: a genuinely unpriced package remains a complete mechanical preview, but it
     * cannot cross the risk-screened Candidate boundary. The API publishes a typed unavailable
     * evaluation instead of asking the evaluator to invent maximum loss or reserve.
     */
    @Test
    void theWholeEvaluationPipelineStopsAtTheTypedUnavailableBoundary() {
        TradeService.OpenRequest request = creditPutSpread();
        TradePreview preview = trades.analyze(request);
        assertThat(preview.maxLossCents()).isNull();
        assertThat(preview.reserveCents()).isNull();
        assertThatThrownBy(() -> TradeController.exactPreviewCandidate(request, preview))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot become a risk-screened candidate");

        ApiResponses.EvaluationReceipt receipt =
                TradeController.unavailableRiskEvaluation(preview, "The exact package");
        assertThat(receipt.available()).isFalse();
        assertThat(receipt.decisionScore()).isNull();
        assertThat(receipt.risk()).isNull();
        assertThat(receipt.stance()).isNull();
        assertThat(receipt.assessment().mechanics().eligible()).isFalse();
        assertThat(receipt.assessment().economics().verdict())
                .isEqualTo(EconomicAssessment.Verdict.UNAVAILABLE);
        assertThat(receipt.assessment().economics().marketEvAfterCostsCents()).isNull();
        assertThat(receipt.unavailableReason()).contains("No current quote evidence");

        var node = TradeController.exactPreviewNode(request, preview);
        assertThat(node.path("maxLossCents").isNull()).isTrue();
        assertThat(node.at("/price/priced").asBoolean()).isFalse();
        assertThat(node.path("liquidityScore").isNull()).isTrue();
        assertThat(node.path("confidence").isNull()).isTrue();
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
        assertThat(wire.at("/price/unavailableReason").asText()).contains("No current quote evidence");
        assertThat(preview.price().estimatedRoundTripFeesCents()).isNull();
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
        TradePreview preview = trades.analyze(creditPutSpread());
        ApiResponses.EvaluationReceipt receipt =
                TradeController.unavailableRiskEvaluation(preview, "The exact package");
        EconomicAssessment economics = receipt.assessment().economics();

        assertThat(economics.verdict()).isEqualTo(EconomicAssessment.Verdict.UNAVAILABLE);
        assertThat(economics.estimatedRoundTripFeesCents()).isNull();
        assertThat(economics.marketEvAfterCostsCents()).isNull();
        assertThat(economics.realizedVolEvAfterCostsCents()).isNull();
        assertThat(economics.reasons()).contains(EconomicAssessment.UNKNOWN_FEES_REASON);
        // The refusal is still complete: the mechanical failures are carried, not replaced.
        assertThat(economics.reasons())
                .anySatisfy(reason -> assertThat(reason).contains("No current quote evidence"));

        // And it stays null all the way onto the wire, rather than serializing as a free round trip.
        var wire = io.liftandshift.strikebench.util.Json.MAPPER.valueToTree(receipt);
        assertThat(wire.at("/assessment/economics/estimatedRoundTripFeesCents").isNumber()).isFalse();
        assertThat(wire.path("available").asBoolean()).isFalse();
    }

    @Test
    void aPricedButMechanicallyBlockedPackageKeepsItsCapturedCommission() {
        TradeService.OpenRequest request = nakedShortCall();
        TradePreview preview = pricedMarket().analyze(request);

        assertThat(preview.ok()).isFalse();
        assertThat(preview.blockReasons()).anySatisfy(reason ->
                assertThat(reason).containsIgnoringCase("undefined"));
        assertThat(preview.price().priced()).isTrue();
        assertThat(preview.price().grossPackageNetCents()).isEqualTo(10_000L);
        assertThat(preview.price().openingFeesCents()).isEqualTo(65L);
        assertThat(preview.price().estimatedRoundTripFeesCents()).isEqualTo(130L);

        assertThat(preview.maxLossCents()).isNull();
        assertThat(preview.reserveCents()).isNull();
        ApiResponses.EvaluationReceipt receipt =
                TradeController.unavailableRiskEvaluation(preview, "The exact package");
        assertThat(receipt.assessment().economics().estimatedRoundTripFeesCents())
                .isEqualTo(130L);
        assertThat(receipt.assessment().economics().reasons())
                .doesNotContain(EconomicAssessment.UNKNOWN_FEES_REASON);
        assertThat(receipt.assessment().mechanics().eligible()).isFalse();
        assertThat(receipt.available()).isFalse();
    }

    /**
     * The API seam must preserve an unknown fee as null. This used to declare a primitive
     * {@code long}; the method reference auto-unboxed the receipt and the controller caught an NPE,
     * replacing a complete degraded assessment with the generic wholesale-unavailable fallback.
     */
    @Test
    void theExactAssessmentApiSeamDoesNotInvokeTheEvaluatorWithoutRisk() {
        TradePreview preview = trades.analyze(creditPutSpread());
        ApiResponses.EvaluationReceipt receipt =
                TradeController.unavailableRiskEvaluation(preview, "The exact package");

        assertThat(receipt.assessment().economics().verdict())
                .isEqualTo(EconomicAssessment.Verdict.UNAVAILABLE);
        assertThat(receipt.assessment().economics().estimatedRoundTripFeesCents()).isNull();
        assertThat(receipt.decisionScore()).isNull();
        assertThat(receipt.stance()).isNull();
    }

    /**
     * The reviewer's exact reproduction, pinned: feed the unpriced receipt straight to the
     * management planner. It used to throw NPE on {@code optionNetPremiumCents().longValue()}.
     */
    @Test
    void theManagementPlannerRendersAnUnpricedProtocolInsteadOfThrowing() {
        TradePreview preview = trades.analyze(creditPutSpread());
        var time = ProtocolEvaluator.timeTo(ctx().asOfDate(), EXP);
        ProtocolEvaluator.Plan plan = ProtocolEvaluator.unpricedPlan(
                ProtocolEvaluator.Policy.standard(), preview.price().unavailableReason(), time, true);

        assertThat(plan.side()).isEqualTo(ProtocolEvaluator.Side.UNPRICED);
        // The rules that DO NOT need a price still stand — withholding them would hide guidance
        // the calendar and the structure can prove.
        assertThat(plan.rules()).extracting(ProtocolEvaluator.Rule::rule)
                .contains(ProtocolEvaluator.TIME_EXIT, ProtocolEvaluator.INVALIDATION);
        assertThat(plan.rules()).filteredOn(r -> ProtocolEvaluator.TIME_EXIT.equals(r.rule()))
                .allSatisfy(rule -> assertThat(rule.triggerSessionsToExpiry()).isNotNull());
        assertThat(plan.rules()).filteredOn(r ->
                        ProtocolEvaluator.TAKE_PROFIT.equals(r.rule())
                                || ProtocolEvaluator.STOP_LOSS.equals(r.rule()))
                .allSatisfy(rule -> {
                    assertThat(rule.triggerPnlCents()).isNull();
                    assertThat(rule.summary()).contains("No current quote evidence");
                });
    }

    /** Every refusal exit publishes the same unpriced receipt, so every one must degrade alike. */
    @Test
    void anExpiredLegRefusalDegradesExactlyAsAnUnmarkedContractDoes() {
        TradeService.OpenRequest request = expiredLeg();
        TradePreview preview = trades.analyze(request);
        assertThat(preview.ok()).isFalse();
        assertThat(preview.price().priced()).isFalse();
        assertThat(preview.price().unavailableReason()).contains("already expired");

        assertThat(preview.maxLossCents()).isNull();
        assertThat(preview.reserveCents()).isNull();
        ApiResponses.EvaluationReceipt receipt =
                TradeController.unavailableRiskEvaluation(preview, "The exact package");
        assertThat(receipt.available()).isFalse();
        assertThat(receipt.unavailableReason()).contains("already expired");
        assertThat(receipt.assessment().mechanics().eligible()).isFalse();
        assertThat(receipt.risk()).isNull();
        assertThat(TradeController.exactPreviewNode(request, preview)
                .path("maxLossCents").isNull()).isTrue();
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
    void theReviewPayloadPublishesATypedUnavailableAssessmentRatherThanInventingRisk() {
        TradeService.OpenRequest request = creditPutSpread();
        TradePreview preview = trades.analyze(request);
        ApiResponses.EvaluationReceipt receipt =
                TradeController.unavailableRiskEvaluation(preview, "The exact package");

        assertThat(receipt.available()).isFalse();
        assertThat(receipt.unavailableReason()).contains("No current quote evidence");
        assertThat(receipt.decisionScore()).isNull();
        assertThat(receipt.viable()).isNull();
        assertThat(receipt.management()).isNull();
        assertThat(receipt.risk()).isNull();
        assertThat(receipt.explanation()).isNull();
        assertThat(receipt.ivContext()).isNull();
        assertThat(receipt.assessment().economics().verdict())
                .isEqualTo(EconomicAssessment.Verdict.UNAVAILABLE);
        assertThat(TradeController.exactPreviewNode(request, preview)
                .path("maxLossCents").isNull()).isTrue();
    }
}
