package io.liftandshift.strikebench.outcomes;

import io.liftandshift.strikebench.research.ResearchQuestionEngine;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.sim.IvSpec;
import io.liftandshift.strikebench.sim.ScenarioSpec;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Internal contract shared by every forward-looking outcome surface. */
public final class OutcomeContract {
    private OutcomeContract() {}

    public enum Operation { DECISION, PATHS, POSITION, COMPARE }

    /** Interpretations stay explicit; sharing paths must never blend what the probabilities mean. */
    public enum Basis { DECISION_POLICY, PARAMETRIC, HISTORICAL_ANALOGS, CONDITIONAL_BOOTSTRAP, RISK_NEUTRAL }

    /**
     * Caller assertion about the active analysis world. The server resolves this from identity and
     * rejects a mismatch; clients cannot select a foreign world or dataset through evaluation.
     */
    public record MarketContext(String symbol, String marketLane, String worldId,
                                String datasetId, String asOf) {}

    /** One cross-engine leg: expiration is listed-contract identity; expiryDay is path-relative. */
    public record Leg(String action, String type, BigDecimal strike, String expiration,
                      Integer expiryDay, Integer ratio) {}

    /** Signed entryCostCents: debit paid is positive, credit received is negative. */
    public record Position(String key, List<Leg> legs, Integer qty, Long entryCostCents) {}

    /** A price threshold the path ensemble should answer directly (target, floor, strike, breakeven). */
    public record DecisionLevel(String key, BigDecimal price) {}

    public record Request(Operation operation, Basis basis,
                          MarketContext context, Position position, List<Position> positions,
                          ScenarioSpec over, IvSpec iv, ResearchQuestionEngine.RunRequest study,
                          RecommendationEngine.Request decision, List<DecisionLevel> levels) {}

    public record Response(Operation operation, Basis basis,
                           Map<String, Object> context, String interpretation, Object result) {}
}
