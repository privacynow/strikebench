package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.paper.ProtocolEvaluator;

import java.util.List;

/**
 * The mechanical management plan — co-equal with entry, not an afterthought. Every recommendation
 * ships with how to take profit, when to cut losses, when to roll, and what to do on assignment,
 * so "what could fail + the plan" travels with "why one wins". Produced by {@code ManagementPlanner}.
 *
 * <p>This record RENDERS {@link ProtocolEvaluator}'s named policy; it owns no threshold. Every rule
 * carries the policy's own rule constant and its trigger VALUE with its unit, so a consumer can
 * compare a rule to a live mark instead of parsing prose, and the two vocabularies cannot drift
 * apart (§7.5).</p>
 */
public record ManagementPlan(String summary, String policyId, int policyVersion,
                             String policyFingerprint, ProtocolEvaluator.Regime regime,
                             ProtocolEvaluator.Side side, List<Rule> rules, List<String> regimeNotes) {
    public ManagementPlan {
        rules = rules == null ? List.of() : List.copyOf(rules);
        regimeNotes = regimeNotes == null ? List.of() : List.copyOf(regimeNotes);
    }

    /**
     * One mechanical rule: the policy's typed rule constant, its trigger VALUE with its unit, the
     * trigger wording the policy supplied, and the display action. {@code rule} is always one of
     * {@link ProtocolEvaluator}'s constants — there is no free-text kind.
     */
    public record Rule(String rule, Long triggerPnlCents, Integer triggerSessionsToExpiry,
                       String trigger, String action) {}
}
