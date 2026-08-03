package io.liftandshift.strikebench.outcomes;

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

    }

    /** A price threshold the path ensemble should answer directly (target, floor, strike, breakeven). */
    public record DecisionLevel(String key, BigDecimal price) {}

    public record Request(Operation operation, Basis basis,
                          MarketContext context, Position position, List<Position> positions,
                          ScenarioSpec over, IvSpec iv, ResearchQuestionEngine.RunRequest study,
                          RecommendationEngine.Request decision, List<DecisionLevel> levels) {
        public Request {
            if (operation == null) throw new IllegalArgumentException("operation is required");
            if (basis == null) throw new IllegalArgumentException("basis is required");
            if (context == null) throw new IllegalArgumentException("market context is required");
            positions = positions == null ? List.of() : List.copyOf(positions);
            levels = levels == null ? List.of() : List.copyOf(levels);
            switch (operation) {
                case DECISION -> {
                    if (basis != Basis.DECISION_POLICY || decision == null) {
                        throw new IllegalArgumentException(
                                "DECISION requires DECISION_POLICY basis and decision inputs");
                    }
                    if (position != null || !positions.isEmpty() || over != null || iv != null
                            || study != null || !levels.isEmpty()) {
                        throw new IllegalArgumentException(
                                "DECISION accepts only market context and decision inputs");
                    }
                }
                case PATHS -> {
                    if (basis != Basis.PARAMETRIC || over == null) {
                        throw new IllegalArgumentException(
                                "PATHS requires PARAMETRIC basis and a scenario specification");
                    }
                    if (!positions.isEmpty() || study != null || decision != null) {
                        throw new IllegalArgumentException(
                                "PATHS does not accept comparison, study, or decision inputs");
                    }
                }
                case POSITION -> {
                    if (position == null) {
                        throw new IllegalArgumentException("POSITION requires one position");
                    }
                    if (!positions.isEmpty() || decision != null || !levels.isEmpty()) {
                        throw new IllegalArgumentException(
                                "POSITION does not accept comparison, decision, or decision-level inputs");
                    }
                    requireScenarioInputs(basis, over, study);
                }
                case COMPARE -> {
                    if (positions.isEmpty()) {
                        throw new IllegalArgumentException("COMPARE requires positions");
                    }
                    if (position != null || decision != null || !levels.isEmpty()) {
                        throw new IllegalArgumentException(
                                "COMPARE does not accept a single position, decision, or decision-level inputs");
                    }
                    requireScenarioInputs(basis, over, study);
                }
            }
        }

        private static void requireScenarioInputs(Basis basis, ScenarioSpec over,
                                                  ResearchQuestionEngine.RunRequest study) {
            if (basis == Basis.DECISION_POLICY) {
                throw new IllegalArgumentException(
                        "DECISION_POLICY basis is valid only for DECISION");
            }
            if (basis == Basis.RISK_NEUTRAL) {
                if (over != null || study != null) {
                    throw new IllegalArgumentException(
                            "RISK_NEUTRAL evaluation does not accept scenario or historical-study inputs");
                }
                return;
            }
            if (over == null) {
                throw new IllegalArgumentException(
                        "path-based evaluation requires a scenario specification");
            }
            boolean historical = basis == Basis.HISTORICAL_ANALOGS
                    || basis == Basis.CONDITIONAL_BOOTSTRAP;
            if (historical != (study != null)) {
                throw new IllegalArgumentException(historical
                        ? "historical evaluation requires study inputs"
                        : "PARAMETRIC evaluation does not accept historical-study inputs");
            }
        }
    }

    public record Response(Operation operation, Basis basis,
                           Map<String, Object> context, String interpretation, Object result) {}
}
