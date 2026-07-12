package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives a mechanical management plan (take-profit, stop, roll, assignment) from the structure and
 * intent — so every recommendation ships with what to do AFTER entry, not just the entry.
 */
public final class ManagementPlanner {

    public ManagementPlan plan(Candidate c, StrategySpec spec) {
        boolean credit = c.entryNetPremiumCents() > 0;
        boolean hasShort = c.assignmentProb() != null; // engine sets this only when there are short legs
        List<ManagementPlan.Rule> rules = new ArrayList<>();
        String summary;

        if (credit) {
            summary = "Income/credit trade: manage early — take profits, defend the tested side.";
            rules.add(new ManagementPlan.Rule("take-profit", "credit decays to ~50% of what you collected",
                    "buy it back and close — most of the edge is captured"));
            rules.add(new ManagementPlan.Rule("stop", "loss reaches ~2x the credit received",
                    "close to cap the loss well inside max loss"));
            rules.add(new ManagementPlan.Rule("roll", "~21 days to expiry with the trade untested",
                    "roll out (and the untested side in) to keep theta working"));
            if (hasShort) {
                rules.add(new ManagementPlan.Rule("assignment", "a short leg goes in-the-money near expiry / ex-div",
                        assignmentAction(spec)));
            }
        } else {
            summary = "Debit/directional trade: let the thesis play, but respect time decay.";
            rules.add(new ManagementPlan.Rule("take-profit", "gain reaches ~50–100% of the debit paid or hits your target",
                    "take profit — don't round-trip a winner"));
            rules.add(new ManagementPlan.Rule("stop", "loss reaches ~50% of the debit paid",
                    "cut it; the thesis is not working"));
            rules.add(new ManagementPlan.Rule("time", "~21 days to expiry without follow-through",
                    "exit to avoid the steep theta into expiry"));
        }
        rules.add(new ManagementPlan.Rule("invalidation", "the thesis breaks (see 'would invalidate')",
                "close regardless of P/L — the reason to hold is gone"));
        return new ManagementPlan(summary, rules);
    }

    private static String assignmentAction(StrategySpec spec) {
        String intent = spec == null ? "" : String.valueOf(spec.intent());
        if ("ACQUIRE".equals(intent)) return "that's the goal — take the shares at your strike, or roll down-and-out for more premium";
        if ("EXIT".equals(intent)) return "that's the goal — let the shares be called away at your strike, or roll up-and-out";
        return "decide early: take assignment if you want the shares, else roll to avoid it";
    }
}
