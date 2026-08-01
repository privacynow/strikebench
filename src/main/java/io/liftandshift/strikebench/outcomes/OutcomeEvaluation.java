package io.liftandshift.strikebench.outcomes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.liftandshift.strikebench.research.ResearchQuestionEngine;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.paper.PackagePrice;
import io.liftandshift.strikebench.sim.IvSpec;
import io.liftandshift.strikebench.sim.ScenarioSpec;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Shared model for every forward-looking outcome surface. */
public final class OutcomeEvaluation {
    private OutcomeEvaluation() {}

    public enum Operation { DECISION, PATHS, POSITION, COMPARE }

    /** Interpretations stay explicit; sharing paths must never blend what the probabilities mean. */
    public enum Basis { DECISION_POLICY, PARAMETRIC, HISTORICAL_ANALOGS, CONDITIONAL_BOOTSTRAP, RISK_NEUTRAL }

    /**
     * Caller assertion about the active analysis world. The server resolves this from identity and
     * rejects a mismatch; clients cannot select a foreign world or dataset through evaluation.
     */
    public record MarketContext(String symbol, String marketMode, String worldId,
                                String datasetId, String asOf) {
        public MarketContext {
            symbol = Symbol.normalize(symbol);
        }
    }

    /** One cross-engine leg: expiration is listed-contract identity; expiryDay is path-relative. */
    public record Leg(String action, String type, BigDecimal strike, String expiration,
                      Integer expiryDay, int ratio, int multiplier) {}

    /**
     * One exact position and, when already captured, its one normalized opening-price result.
     *
     * <p>{@code price == null} is the explicit request to price the current book. A non-null
     * result is never reconstructed from loose cost/fee primitives: its signed package cash,
     * opening and round-trip fees, quantity, valuation basis, evidence, and fingerprint travel
     * together. {@link PackagePrice.ValuationBasis#RECORDED_FILL} distinguishes an actual
     * held fill from a captured proposal price without inventing a second entry-price model.</p>
     */
    public record Position(String key, List<Leg> legs, int qty, PackagePrice price) {
        public Position {
            if (qty < 1 || qty > 100) throw new IllegalArgumentException("position qty must be 1..100");
            legs = legs == null ? List.of() : List.copyOf(legs);
            if (price != null && price.quantity() != qty) {
                throw new IllegalArgumentException(
                        "position quantity must match its captured package-price result");
            }
            if (price != null && price.feeSide() != PackagePrice.FeeSide.OPENING) {
                throw new IllegalArgumentException(
                        "an outcome position requires an OPENING package-price result");
            }
        }

        /**
         * Keep the wire migration fail-closed even though the application's shared JSON mapper
         * intentionally ignores unknown properties. Otherwise an old client could send the retired
         * loose cost/fee fields, have them discarded, and silently request a new current-book price.
         */
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        static Position fromJson(
                @JsonProperty("key") String key,
                @JsonProperty("legs") List<Leg> legs,
                @JsonProperty("qty") int qty,
                @JsonProperty("price") PackagePrice price,
                @JsonProperty("entryCostCents") Long retiredEntryCostCents,
                @JsonProperty("estimatedRoundTripFeesCents") Long retiredRoundTripFeesCents) {
            if (retiredEntryCostCents != null || retiredRoundTripFeesCents != null) {
                throw new IllegalArgumentException(
                        "outcome positions require one captured package-price result; "
                                + "loose entry cost or fee fields are not accepted");
            }
            return new Position(key, legs, qty, price);
        }
    }

    /** A price threshold the path ensemble should answer directly (target, floor, strike, breakeven). */
    public record DecisionLevel(String key, BigDecimal price) {}

    public record Request(Operation operation, Basis basis,
                          MarketContext context, Position position, List<Position> positions,
                          ScenarioSpec over, IvSpec iv, ResearchQuestionEngine.RunRequest study,
                          RecommendationEngine.Request decision, List<DecisionLevel> levels) {}

    public record Response(Operation operation, Basis basis,
                           Map<String, Object> context, String interpretation, Object result) {}
}
