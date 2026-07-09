package io.liftandshift.strikebench.eval;

import java.util.List;

/**
 * The mechanical management plan — co-equal with entry, not an afterthought. Every recommendation
 * ships with how to take profit, when to cut losses, when to roll, and what to do on assignment,
 * so "what could fail + the plan" travels with "why one wins". Produced by {@code ManagementPlanner}.
 */
public record ManagementPlan(String summary, List<Rule> rules) {
    public ManagementPlan {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** One mechanical rule: a trigger and the action to take. kind groups them for the UI. */
    public record Rule(String kind, String trigger, String action) {}
}
