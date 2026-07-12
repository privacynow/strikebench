package io.liftandshift.strikebench.recommend;

import java.util.Locale;

/**
 * THE per-idea capital budget policy — one place, consumed by every surface (review IC-1):
 * /api/risk-budget, manual recommendations, the auto scout, ladder construction, and the
 * ticket guardrail advisory. Basis is the caller's CURRENT account buying power (paper and
 * simulation accounts are cash-only — no margin figure can silently inflate the denominator);
 * the user's declared risk capital, when set, caps every mode.
 *
 * The engine itself consumes the policy through Request.maxLossCents (its budget =
 * min(percent x buying power, maxLossCents)), so {@link #effectiveMaxLossCents} is the ONE
 * translation from declared capital to the engine's request contract.
 */
public final class RiskBudgetPolicy {

    private RiskBudgetPolicy() {}

    public record Budget(String mode, String label, double percent, long basisCents,
                         long policyBudgetCents, Long capCents, long effectiveBudgetCents, boolean capped) {}

    public static Budget compute(RecommendationEngine.RiskMode mode, long buyingPowerCents, Long riskCapitalCents) {
        long policy = Math.round(buyingPowerCents * mode.defaultRiskPct());
        Long cap = riskCapitalCents != null && riskCapitalCents > 0 ? riskCapitalCents : null;
        long effective = cap != null ? Math.min(policy, cap) : policy;
        return new Budget(mode.name().toLowerCase(Locale.ROOT), labelOf(mode), mode.defaultRiskPct(),
                buyingPowerCents, policy, cap, effective, cap != null && policy > cap);
    }

    public static String labelOf(RecommendationEngine.RiskMode mode) {
        return switch (mode) {
            case CONSERVATIVE -> "Cautious";
            case BALANCED -> "Standard";
            case AGGRESSIVE -> "High";
        };
    }

    /**
     * The request-level cap the engine consumes: the tighter of the caller's explicit
     * max-loss and the declared risk capital. Null when neither binds.
     */
    public static Long effectiveMaxLossCents(Long requestMaxLossCents, Long riskCapitalCents) {
        Long cap = riskCapitalCents != null && riskCapitalCents > 0 ? riskCapitalCents : null;
        Long req = requestMaxLossCents != null && requestMaxLossCents > 0 ? requestMaxLossCents : null;
        if (cap == null) return req;
        if (req == null) return cap;
        return Math.min(req, cap);
    }
}
