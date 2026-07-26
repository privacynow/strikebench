package io.liftandshift.strikebench.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.liftandshift.strikebench.support.CurrentEvaluationReceiptFixture.withComparisonEndorsement;

/**
 * A restored plan rail must be the SAME object as a live one — enforced by the receipt's own
 * compact constructor, not by a hand-assembled node that happens to carry the same field names.
 *
 * <p>The restore used to write the stored package net unconditionally while defaulting the
 * valuation basis to {@code UNAVAILABLE} and attaching "stored before its price receipt existed".
 * {@link PackagePriceReceipt} declares that pairing illegal — a stated basis and a stated price
 * stand or fall together (§3.2) — but nothing ever fed the restored node back through the record,
 * so every pre-receipt candidate was published to the browser as a price the product simultaneously
 * said it did not have. These tests close both ends: the database will not hold the shape, and the
 * restore will not emit it.</p>
 */
class PlanCandidatePriceRestoreTest {

    private Db db;
    private PlanService plans;
    private PlanStrategyService strategies;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-07-12T16:00:00Z"), ZoneOffset.UTC);
        plans = new PlanService(db, clock);
        strategies = new PlanStrategyService(db, clock);
    }

    @AfterEach void close() { if (db != null) db.close(); }

    @Test void aPreReceiptRowRestoresAsALegalUnpricedReceiptRatherThanAPriceWithNoBasis() {
        Plan.View plan = plan("restore-legacy-1");
        PlanStrategyService.SavedRun saved = strategies.saveCompetition(null, plan,
                Json.parse("{\"filters\":{}}"), competition(PRICED_RECEIPT));
        String candidateId = saved.result().at("/candidates/0/id").asText();

        // Reproduce the exact pre-receipt row: a package net in entry_net_cents and nothing else.
        // The constraints this fix added are what make the shape unreachable, so the test has to
        // stand them down to recreate the data the reviewers found in the wild.
        db.exec("ALTER TABLE plan_candidate DROP CONSTRAINT plan_candidate_price_basis_pairing_check");
        db.exec("ALTER TABLE plan_candidate DROP CONSTRAINT plan_candidate_price_additive_check");
        db.exec("ALTER TABLE plan_candidate DROP CONSTRAINT plan_candidate_price_after_fee_check");
        db.exec("ALTER TABLE plan_candidate ALTER COLUMN valuation_basis DROP NOT NULL");
        db.exec("ALTER TABLE plan_candidate ALTER COLUMN price_executability DROP NOT NULL");
        db.exec("ALTER TABLE plan_candidate ALTER COLUMN price_fee_side DROP NOT NULL");
        db.exec("UPDATE plan_candidate SET valuation_basis=NULL, price_executability=NULL,"
                + " price_fee_side=NULL, stock_cash_flow_cents=NULL, opening_fees_cents=NULL,"
                + " estimated_round_trip_fees_cents=NULL,"
                + " after_fee_net_cents=NULL, executable_net_cents=NULL, price_source=NULL,"
                + " price_observed_at_epoch_ms=NULL, price_fingerprint=NULL,"
                + " price_unavailable_reason=NULL WHERE id=?", candidateId);

        JsonNode price = strategies.latestCompetition(null, plan.id()).result()
                .at("/candidates/0/price");
        // THE assertion: whatever the restore publishes must be a receipt the record would build.
        // Before the fix this threw "valuationBasis UNAVAILABLE and a null grossPackageNetCents
        // must occur together" — the node was not a legal receipt at all.
        PackagePriceReceipt restored = receiptFromNode(price);
        assertThat(restored.priced()).isFalse();
        assertThat(restored.grossPackageNetCents()).isNull();
        assertThat(restored.optionNetPremiumCents()).isNull();
        assertThat(restored.afterFeeNetCents()).isNull();
        assertThat(restored.unavailableReason()).contains("before its price receipt existed");
        // …and the wire node says the same thing, so a browser bound to it cannot print a number.
        assertThat(price.get("grossPackageNetCents").isNull()).isTrue();
        assertThat(price.get("valuationBasis").asText())
                .isEqualTo(PackagePriceReceipt.ValuationBasis.UNAVAILABLE.name());
        // Quantity survives: the reader still needs to know what size went unpriced (§7.2).
        assertThat(price.get("quantity").asInt()).isEqualTo(2);
    }

    @Test void aPricedCandidateRestoresIntoAnIdenticalReceipt() {
        Plan.View plan = plan("restore-priced-1");
        strategies.saveCompetition(null, plan, Json.parse("{\"filters\":{}}"),
                competition(PRICED_RECEIPT));

        JsonNode price = strategies.latestCompetition(null, plan.id()).result()
                .at("/candidates/0/price");
        PackagePriceReceipt restored = receiptFromNode(price);

        assertThat(restored.priced()).isTrue();
        assertThat(restored.quantity()).isEqualTo(2);
        assertThat(restored.optionNetPremiumCents()).isEqualTo(48_000L);
        assertThat(restored.stockCashFlowCents()).isEqualTo(-2_001_000L);
        assertThat(restored.grossPackageNetCents()).isEqualTo(-1_953_000L);
        assertThat(restored.estimatedRoundTripFeesCents()).isEqualTo(260L);
        assertThat(restored.afterFeeNetCents()).isEqualTo(-1_953_130L);
        assertThat(restored.valuationBasis())
                .isEqualTo(PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK);
        assertThat(restored.observedAt()).isEqualTo(1_785_000_000_000L);
        assertThat(restored.fingerprint()).isEqualTo("fixture-price");
        assertThat(restored.unavailableReason()).isNull();
    }

    @Test void anHonestlyUnpricedCandidateStoresAndRestoresWithItsReason() {
        Plan.View plan = plan("restore-unpriced-1");
        PlanStrategyService.SavedRun saved = strategies.saveCompetition(null, plan, Json.parse("{\"filters\":{}}"),
                competition(UNPRICED_RECEIPT));
        String candidateId = saved.result().at("/candidates/0/id").asText();

        PackagePriceReceipt restored = receiptFromNode(strategies.latestCompetition(null, plan.id())
                .result().at("/candidates/0/price"));

        assertThat(restored.priced()).isFalse();
        assertThat(restored.unavailableReason()).contains("no executable market");
        assertThat(restored.quantity()).isEqualTo(2);
        assertThat(restored.estimatedRoundTripFeesCents()).isNull();
        assertThat(db.query("SELECT estimated_round_trip_fees_cents FROM plan_candidate WHERE id=?",
                r -> r.lngOrNull("estimated_round_trip_fees_cents"), candidateId))
                .containsExactly((Long) null);
    }

    @Test void aCandidateWithNoPriceReceiptIsRefusedInsteadOfStoredAsANamelessAmount() {
        Plan.View plan = plan("restore-nopricenode-1");
        ObjectNode missingPriceAndEndorsement = competition(null);
        ((ObjectNode) missingPriceAndEndorsement.at("/candidates/0/evaluation")).remove("endorsement");
        assertThatThrownBy(() -> strategies.saveCompetition(null, plan,
                Json.parse("{\"filters\":{}}"), missingPriceAndEndorsement))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("package-price receipt is required");

        assertThatThrownBy(() -> strategies.saveCompetition(null, plan,
                Json.parse("{\"filters\":{}}"), competition("{\"quantity\":2}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valuationBasis");
    }

    @Test void theDatabaseItselfRefusesAPriceWithNoBasisAndSidesThatDoNotAddUp() {
        Plan.View plan = plan("restore-constraints-1");
        PlanStrategyService.SavedRun saved = strategies.saveCompetition(null, plan,
                Json.parse("{\"filters\":{}}"), competition(PRICED_RECEIPT));
        String id = saved.result().at("/candidates/0/id").asText();

        // A price whose basis has been erased: the self-contradiction this fix is about.
        assertThatThrownBy(() -> db.exec(
                "UPDATE plan_candidate SET valuation_basis='UNAVAILABLE',"
                        + " price_unavailable_reason='erased' WHERE id=?", id))
                .hasMessageContaining("plan_candidate_price_basis_pairing_check");

        // A package net that is not its two sides: §3.3's "unexplained different amounts", at rest.
        assertThatThrownBy(() -> db.exec(
                "UPDATE plan_candidate SET option_net_cents=option_net_cents+1 WHERE id=?", id))
                .hasMessageContaining("plan_candidate_price_additive_check");

        // An after-fee net that does not follow from the gross and the fee…
        assertThatThrownBy(() -> db.exec(
                "UPDATE plan_candidate SET after_fee_net_cents=after_fee_net_cents+1 WHERE id=?", id))
                .hasMessageContaining("plan_candidate_price_after_fee_check");

        // …and a MISSING one where a gross and a fee are both stated. A CHECK expression that
        // evaluates to NULL passes in Postgres, so this case needs its own explicit IS NOT NULL.
        assertThatThrownBy(() -> db.exec(
                "UPDATE plan_candidate SET after_fee_net_cents=NULL WHERE id=?", id))
                .hasMessageContaining("plan_candidate_price_after_fee_check");

        // An unpriced row must still say why.
        assertThatThrownBy(() -> db.exec(
                "UPDATE plan_candidate SET entry_net_cents=NULL, option_net_cents=NULL,"
                        + " stock_cash_flow_cents=NULL, after_fee_net_cents=NULL,"
                        + " valuation_basis='UNAVAILABLE' WHERE id=?", id))
                .hasMessageContaining("plan_candidate_price_unavailable_reason_check");
    }

    /** Rebuilds the wire node through the canonical record: the node is legal, or this throws. */
    private static PackagePriceReceipt receiptFromNode(JsonNode price) {
        return new PackagePriceReceipt(price.path("quantity").asInt(),
                lng(price, "optionNetPremiumCents"), lng(price, "stockCashFlowCents"),
                lng(price, "grossPackageNetCents"), lng(price, "openingFeesCents"),
                lng(price, "estimatedRoundTripFeesCents"),
                lng(price, "afterFeeNetCents"), lng(price, "executableNetCents"),
                lng(price, "restingLimitNetCents"),
                PackagePriceReceipt.ValuationBasis.valueOf(price.path("valuationBasis").asText()),
                OrderInstruction.Executability.valueOf(price.path("executability").asText()),
                str(price, "source"), str(price, "freshness"), lng(price, "observedAt"),
                str(price, "fingerprint"),
                PackagePriceReceipt.FeeSide.valueOf(price.path("feeSide").asText()),
                str(price, "unavailableReason"));
    }

    private static Long lng(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asLong();
    }

    private static String str(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private Plan.View plan(String key) {
        return plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest(key, "AAPL", "INCOME", null, null, "neutral", 30, null,
                        "conservative", null, null, null, null));
    }

    /** A buy-write: the structure where the option-only net and the package net are furthest apart. */
    private static final String PRICED_RECEIPT = """
            {"quantity":2,"optionNetPremiumCents":48000,"stockCashFlowCents":-2001000,
             "grossPackageNetCents":-1953000,"openingFeesCents":130,
             "estimatedRoundTripFeesCents":260,"afterFeeNetCents":-1953130,
             "executableNetCents":-1953000,"valuationBasis":"EXECUTABLE_BOOK",
             "executability":"IMMEDIATE","source":"fixture","freshness":"FIXTURE",
             "observedAt":1785000000000,"fingerprint":"fixture-price","feeSide":"OPENING"}""";

    private static final String UNPRICED_RECEIPT = """
            {"quantity":2,"optionNetPremiumCents":null,"stockCashFlowCents":null,
             "grossPackageNetCents":null,"openingFeesCents":null,
             "estimatedRoundTripFeesCents":null,"afterFeeNetCents":null,
             "executableNetCents":null,"valuationBasis":"UNAVAILABLE",
             "executability":"UNAVAILABLE","source":null,"freshness":"FIXTURE","observedAt":null,
             "fingerprint":null,"feeSide":"OPENING",
             "unavailableReason":"the complete package has no executable market"}""";

    /** One covered-call candidate; {@code priceJson} null omits the price receipt entirely. */
    private static ObjectNode competition(String priceJson) {
        return withComparisonEndorsement((ObjectNode) Json.parse("""
                {"symbol":"AAPL","thesis":"neutral","horizon":"month","riskMode":"conservative",
                 "intent":"INCOME","riskBudgetCents":100000,"ranking":"decision",
                 "economicMessage":"Compare the field","favorableCount":1,"mixedCount":0,
                 "unfavorableCount":0,"unavailableCount":0,"notes":[],"rejected":[],
                 "disclaimer":"Education only","candidates":[{
                   "strategy":"COVERED_CALL","displayName":"Covered call","structureGroup":"INCOME",
                   "label":"BUY 100 shares / SELL 105C","qty":2,
                   %s"maxProfitCents":50000,"maxLossCents":1953000,
                   "breakevens":["195.30"],"pop":0.58,"expectedValueCents":900,
                   "liquidityScore":0.9,"freshness":"FIXTURE","warnings":[],
                   "confidence":0.7,"whyConsidered":"Income on shares","bestUpside":"Called away",
                   "biggestRisk":"Shares fall","wouldInvalidate":"Breakdown",
                   "beginnerExplanation":"Own shares, sell a call",
                   "intent":"INCOME","intents":["INCOME"],"assignmentProb":0.3,
                   "annualizedYieldPct":12.0,"effectivePrice":"195.30","intentNote":"Earn premium",
                   "usesHeldShares":false,"sharesNeeded":0,"combinedMaxLossCents":1953000,
                   "evaluation":{"available":true,"decisionScore":68.0,"viable":true,
                     "capital":{},"volatility":{},"risk":{"pop":0.58},
                     "assessment":{"mechanics":{"eligible":true,"reasons":[]},
                       "economics":{"verdict":"FAVORABLE","placement":"WORTH_INVESTIGATING",
                         "label":"Favorable","summary":"Positive after costs",
                         "marketEvAfterCostsCents":900,"realizedVolEvAfterCostsCents":1400,
                         "estimatedRoundTripFeesCents":260,"marketEvPctOfRisk":0.5,
                         "observedEvidence":false,"reasons":["Scenario positive"]}},
                     "evidence":{"rollup":"DEMO_FIXTURE","perDimension":{"pricing":"DEMO_FIXTURE"},
                       "note":"Teaching data"},
                     "score":{"gatePassed":true,"gateFailures":[],"normalizedScore":74,
                       "riskAdjustedScore":68,"components":[]},
                     "management":{"summary":"Take profits mechanically","rules":[]},
                     "stance":{},"participation":{},"impliedStance":{},"ivContext":{},"coverage":{},
                     "explanation":{"assumptions":[],"failureModes":[]}},
                   "legs":[
                     {"action":"BUY","type":null,"strike":null,"expiration":null,"ratio":100,
                      "multiplier":1,"entryPrice":"100.05","positionEffect":"OPEN","stock":true},
                     {"action":"SELL","type":"CALL","strike":"105","expiration":"2026-08-21",
                      "ratio":1,"multiplier":100,"entryPrice":"2.40","positionEffect":"OPEN"}
                   ]}]}
                """.formatted(priceJson == null ? "" : "\"price\":" + priceJson + ",")));
    }
}
