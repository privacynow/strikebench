package io.liftandshift.strikebench.paper;

import java.util.ArrayList;
import java.util.List;

/**
 * The ONE mechanical protocol-trigger evaluator. {@code eval/ManagementPlanner} states the
 * protocol as text at Decide ("credit decays to ~50%", "loss reaches ~2x the credit", "~21 days
 * to expiry"); this class is the runtime twin that checks those SAME thresholds against live
 * marks, for open practice trades and tracked structures alike. There is deliberately no second
 * copy of this math anywhere — the alert center and any status chip both call here.
 *
 * All thresholds are the planner's stated heuristics, not predictions, and every consumer must
 * label them as such (§3.15).
 */
public final class ProtocolEvaluator {

    /** Fraction of the collected credit whose capture triggers the credit take-profit rule. */
    public static final double CREDIT_TAKE_PROFIT_FRACTION = 0.50;
    /** Loss as a multiple of the collected credit that triggers the credit stop rule. */
    public static final double CREDIT_STOP_MULTIPLE = 2.0;
    /** Fraction of the debit paid that triggers the debit take-profit rule. */
    public static final double DEBIT_TAKE_PROFIT_FRACTION = 0.50;
    /** Fraction of the debit paid whose loss triggers the debit stop rule. */
    public static final double DEBIT_STOP_FRACTION = 0.50;
    /** Days to expiry at which the roll / time-exit rule asks for a decision. */
    public static final int TIME_RULE_DAYS = 21;

    /**
     * @param entryNetPremiumCents signed package entry: {@code > 0} credit, {@code < 0} debit
     * @param unrealizedCents      live package P/L at executable closing sides (null = marks unavailable)
     * @param daysToNearestExpiry  calendar days to the nearest option expiration (null = no options)
     */
    public record Inputs(long entryNetPremiumCents, Long unrealizedCents, Integer daysToNearestExpiry) {}

    /** One triggered rule. {@code rule} matches the planner's kinds; severity is the alert tier. */
    public record Trigger(String rule, String severity, String summary) {}

    public static final String TAKE_PROFIT = "TAKE_PROFIT";
    public static final String STOP_LOSS = "STOP_LOSS";
    public static final String ROLL = "ROLL";
    public static final String TIME_EXIT = "TIME_EXIT";

    private ProtocolEvaluator() {}

    /**
     * Evaluates the mechanical rules; returns triggered rules ordered most-significant first
     * (stop before take-profit before time). An empty list means the protocol is quiet.
     */
    public static List<Trigger> evaluate(Inputs in) {
        List<Trigger> out = new ArrayList<>();
        boolean credit = in.entryNetPremiumCents() > 0;
        long basis = Math.abs(in.entryNetPremiumCents());
        Long pnl = in.unrealizedCents();
        if (pnl != null && basis > 0) {
            if (credit) {
                if (pnl <= -Math.round(CREDIT_STOP_MULTIPLE * basis)) {
                    out.add(new Trigger(STOP_LOSS, "URGENT",
                            "the loss has reached ~2x the credit collected"));
                } else if (pnl >= Math.round(CREDIT_TAKE_PROFIT_FRACTION * basis)) {
                    out.add(new Trigger(TAKE_PROFIT, "ATTENTION",
                            "the credit has decayed to ~50% — most of the edge is captured"));
                }
            } else {
                if (pnl <= -Math.round(DEBIT_STOP_FRACTION * basis)) {
                    out.add(new Trigger(STOP_LOSS, "URGENT",
                            "the loss has reached ~50% of the debit paid"));
                } else if (pnl >= Math.round(DEBIT_TAKE_PROFIT_FRACTION * basis)) {
                    out.add(new Trigger(TAKE_PROFIT, "ATTENTION",
                            "the gain has reached ~50% of the debit paid"));
                }
            }
        }
        if (in.daysToNearestExpiry() != null && in.daysToNearestExpiry() >= 0
                && in.daysToNearestExpiry() <= TIME_RULE_DAYS) {
            out.add(credit
                    ? new Trigger(ROLL, "INFO", "~" + in.daysToNearestExpiry()
                            + " days to expiry — the protocol says decide: roll out or close")
                    : new Trigger(TIME_EXIT, "INFO", "~" + in.daysToNearestExpiry()
                            + " days to expiry — exit before the steep time decay if the thesis has not moved"));
        }
        return out;
    }
}
