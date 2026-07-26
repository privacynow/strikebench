package io.liftandshift.strikebench.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.eval.DecisionEndorsement;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.util.Json;

import java.util.List;

/**
 * Builds the two authority-owned portions shared by persisted Plan-candidate test fixtures.
 *
 * <p>Tests that exercise a current evaluation should not hand-copy the endorsement wire contract:
 * serializing the production records here keeps fixture shape aligned with the authority that
 * publishes it. Tests that deliberately submit obsolete or partial evaluations opt out explicitly.</p>
 */
public final class CurrentEvaluationReceiptFixture {
    private CurrentEvaluationReceiptFixture() {}

    /**
     * Adds a comparison endorsement to a direct candidate or every candidate in a result field.
     * Existing endorsements are preserved so a test can supply a purpose-built receipt.
     */
    public static ObjectNode withComparisonEndorsement(ObjectNode root) {
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray()) {
            candidates.forEach(CurrentEvaluationReceiptFixture::addComparisonEndorsement);
        } else {
            addComparisonEndorsement(root);
        }
        return root;
    }

    /**
     * Gives a deliberately invalid evaluation fixture a valid, honestly unavailable package-price
     * receipt so evaluation validation—not an unrelated missing-price error—is what the test reaches.
     */
    public static ObjectNode withUnavailablePrice(ObjectNode candidate) {
        candidate.set("price", Json.MAPPER.valueToTree(PackagePriceReceipt.unavailable(
                1, PackagePriceReceipt.FeeSide.OPENING,
                "This fixture intentionally has no executable package price.")));
        return candidate;
    }

    private static void addComparisonEndorsement(JsonNode candidate) {
        JsonNode evaluation = candidate.path("evaluation");
        if (!(evaluation instanceof ObjectNode object)
                || !evaluation.path("available").asBoolean(false)
                || evaluation.has("endorsement")) {
            return;
        }
        var endorsement = new DecisionEndorsement(false, DecisionEndorsement.COMPARISON, null,
                List.of("This test fixture remains an inspectable comparison."),
                "Fixture receipt serialized from the backend-owned endorsement contract.");
        object.set("endorsement", Json.MAPPER.valueToTree(endorsement));
    }
}
