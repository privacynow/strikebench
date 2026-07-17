package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.strategy.StrategyIntent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One boundary for user-owned decision declarations. Product routes may derive market scope,
 * account capital, or display limits, but they may not invent a goal, horizon, risk posture,
 * directional view, or construction objective.
 */
public final class DecisionDeclarationPolicy {
    private static final List<String> RISK_MODES = List.of("conservative", "balanced", "aggressive");
    private static final List<String> THESES = List.of("bullish", "bearish", "neutral", "volatile");
    private static final List<String> HORIZONS = List.of("0dte", "week", "month", "quarter");
    private static final List<String> OBJECTIVES = List.of("decision", "market_ev", "history_ev");

    private DecisionDeclarationPolicy() {}

    public static StrategyIntent requireRecommendation(String operation,
                                                        RecommendationEngine.Request request,
                                                        boolean requireSymbol) {
        String intentRaw = request == null ? null : request.intent();
        Map<String, Object> required = new LinkedHashMap<>();
        if (requireSymbol) required.put("symbol", request == null ? null : request.symbol());
        required.put("goal (intent)", intentRaw);
        required.put("market view (thesis)", request == null ? null : request.thesis());
        required.put("horizon", request == null ? null : request.horizon());
        required.put("risk posture", request == null ? null : request.riskMode());
        require(operation, required);
        StrategyIntent intent = StrategyIntent.parse(intentRaw);
        requireChoice(operation, "market view", request.thesis(), THESES);
        requireChoice(operation, "horizon", request.horizon(), HORIZONS);
        requireRiskMode(operation, request.riskMode());
        return intent;
    }

    public static StrategyIntent requireLadder(String operation, RecommendationEngine.Request request) {
        String intentRaw = request == null ? null : request.intent();
        Map<String, Object> required = new LinkedHashMap<>();
        required.put("goal (intent)", intentRaw);
        required.put("market view (thesis)", request == null ? null : request.thesis());
        required.put("horizon", request == null ? null : request.horizon());
        required.put("risk posture", request == null ? null : request.riskMode());
        require(operation, required);
        requireChoice(operation, "market view", request.thesis(), THESES);
        requireChoice(operation, "horizon", request.horizon(), HORIZONS);
        requireRiskMode(operation, request.riskMode());
        return StrategyIntent.parse(intentRaw);
    }

    public static void requireScout(String operation, AutoRecommender.AutoRequest request) {
        Map<String, Object> required = new LinkedHashMap<>();
        required.put("goal (intents)", request == null ? null : request.intents());
        required.put("horizon", request == null ? null : request.horizons());
        required.put("risk posture", request == null ? null : request.riskMode());
        require(operation, required);
        for (String horizon : request.horizons()) requireChoice(operation, "horizon", horizon, HORIZONS);
        for (String intent : request.intents()) {
            if (blank(intent)) throw new IllegalArgumentException(operation + " goal cannot be blank");
            StrategyIntent.parse(intent);
        }
        requireRiskMode(operation, request.riskMode());
    }

    public static StrategyIntent requireConstruction(String operation, String intent, String thesis,
                                                     String horizon, String riskMode, String objective) {
        Map<String, Object> required = new LinkedHashMap<>();
        required.put("goal (intent)", intent);
        required.put("market view (thesis)", thesis);
        required.put("horizon", horizon);
        required.put("risk posture", riskMode);
        required.put("ranking objective", objective);
        require(operation, required);
        requireChoice(operation, "market view", thesis, THESES);
        requireChoice(operation, "horizon", horizon, HORIZONS);
        requireRiskMode(operation, riskMode);
        requireChoice(operation, "ranking objective", objective, OBJECTIVES);
        return StrategyIntent.parse(intent);
    }

    private static void require(String operation, Map<String, Object> required) {
        List<String> missing = required.entrySet().stream()
                .filter(entry -> absent(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(operation + " requires an explicit "
                    + String.join(", ", missing) + "; no decision default was substituted");
        }
    }

    private static void requireRiskMode(String operation, String raw) {
        requireChoice(operation, "risk posture", raw, RISK_MODES);
    }

    private static void requireChoice(String operation, String label, String raw, List<String> allowed) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(operation + " " + label + " must be one of "
                    + String.join(", ", allowed));
        }
    }

    private static boolean absent(Object value) {
        if (value == null) return true;
        if (value instanceof String text) return blank(text);
        if (value instanceof Collection<?> values) {
            return values.isEmpty() || values.stream().allMatch(DecisionDeclarationPolicy::absent);
        }
        return false;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
