package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.market.OptionTime;
import io.liftandshift.strikebench.paper.ProtocolEvaluator;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * RENDERS the named mechanical policy for one candidate — so every recommendation ships with what
 * to do AFTER entry, not just the entry. It states no threshold of its own: {@link
 * ProtocolEvaluator} produces the typed rules (values, units, policy id) and this class supplies
 * only the display action beside each one plus the intent-aware assignment wording.
 */
public final class ManagementPlanner {

    public ManagementPlan plan(Candidate c, StrategySpec spec, EvalContext ctx,
                               ProtocolEvaluator.Policy policy) {
        // ONE basis: classify on the OPTION-ONLY net. Classifying a buy-write on its stock-inclusive
        // net labelled every covered call "debit" and told the user to stop at ~50% of "the debit
        // paid" — half the share purchase.
        //
        // §3.2: a refused package (an expired leg, an unmarked contract) publishes a §7.2 result
        // with NO price. That is NOT a flat $0 entry, so the plan is rendered UNPRICED: the time,
        // assignment and invalidation rules still stand — they are calendar and structure facts —
        // while the take-profit and stop lines carry no trigger and say why.
        Long optionNet = c.price().optionNetPremiumCents();
        // Assignment/exercise management is structural. A probability model may be unavailable
        // for a real short leg (mixed expirations, missing IV, or missing model time).
        boolean hasShort = c.legs().stream().anyMatch(leg ->
                "SELL".equalsIgnoreCase(leg.action()) && !"STOCK".equalsIgnoreCase(leg.type()));
        OptionTime.Measure time = ctx == null ? null
                : ctx.timeToExpiry().asOf() == null
                ? ProtocolEvaluator.timeTo(ctx.asOfDate(), nearestExpiry(c))
                : ProtocolEvaluator.timeTo(ctx.timeToExpiry().asOf(), nearestExpiry(c));
        ProtocolEvaluator.Plan plan = optionNet == null
                ? ProtocolEvaluator.unpricedPlan(policy, c.price().unavailableReason(), time, hasShort)
                : ProtocolEvaluator.plan(policy, optionNet, time, hasShort);

        List<ManagementPlan.Rule> rules = new ArrayList<>();
        for (ProtocolEvaluator.Rule rule : plan.rules()) {
            rules.add(new ManagementPlan.Rule(rule.rule(), rule.triggerPnlCents(),
                    rule.triggerSessionsToExpiry(), rule.summary(), action(rule.rule(), plan, spec)));
        }
        String summary = switch (plan.side()) {
            case CREDIT -> "Income/credit trade: manage early — take profits, defend the tested side.";
            case DEBIT -> "Debit/directional trade: let the thesis play, but respect time decay.";
            case FLAT -> "This package opened flat, so there is no premium basis for the price rules — "
                    + "the time and invalidation rules carry the plan.";
            case UNPRICED -> "This package has no price, so the take-profit and stop lines cannot be "
                    + "stated — the time and invalidation rules carry the plan. "
                    + (c.price().unavailableReason() == null ? "" : c.price().unavailableReason());
        };
        return new ManagementPlan(summary, plan.policyId(), plan.policyVersion(),
                plan.policyFingerprint(), plan.regime(), plan.side(), rules, plan.regimeNotes());
    }

    /** The display action beside a typed rule. Wording only — never a second threshold. */
    private static String action(String rule, ProtocolEvaluator.Plan plan, StrategySpec spec) {
        boolean credit = plan.side() == ProtocolEvaluator.Side.CREDIT;
        boolean unpriced = plan.side() == ProtocolEvaluator.Side.UNPRICED;
        // "Buy it back" and "cut it" each assert which side of the premium this package is on. With
        // no price neither can be said, so the price rules state the absence instead of picking one.
        if (unpriced && (ProtocolEvaluator.TAKE_PROFIT.equals(rule)
                || ProtocolEvaluator.STOP_LOSS.equals(rule))) {
            return "unavailable — with no entry price there is no profit or loss to measure against";
        }
        return switch (rule) {
            case ProtocolEvaluator.TAKE_PROFIT -> credit
                    ? "buy it back and close — most of the edge is captured"
                    : "take profit — don't round-trip a winner";
            case ProtocolEvaluator.STOP_LOSS -> credit
                    ? "close to cap the loss well inside max loss"
                    : "cut it; the thesis is not working";
            case ProtocolEvaluator.ROLL -> "roll out (and the untested side in) to keep theta working";
            case ProtocolEvaluator.TIME_EXIT -> plan.regime() == ProtocolEvaluator.Regime.NEAR_EXPIRY
                    ? "close it; a roll at this distance is a new trade, not management"
                    : unpriced ? "close before the steep decay into expiry; without a price the "
                            + "roll-versus-close comparison cannot be made"
                    : "exit to avoid the steep theta into expiry";
            case ProtocolEvaluator.ASSIGNMENT -> assignmentAction(spec);
            default -> "close regardless of P/L — the reason to hold is gone";
        };
    }

    private static LocalDate nearestExpiry(Candidate c) {
        if (c == null || c.legs() == null) return null;
        return c.legs().stream().map(LegView::expiration)
                .filter(value -> value != null && !value.isBlank())
                .map(LocalDate::parse).min(LocalDate::compareTo).orElse(null);
    }

    private static String assignmentAction(StrategySpec spec) {
        String intent = spec == null ? "" : String.valueOf(spec.intent());
        if ("ACQUIRE".equals(intent)) return "that's the goal — take the shares at your strike, or roll down-and-out for more premium";
        if ("EXIT".equals(intent)) return "that's the goal — let the shares be called away at your strike, or roll up-and-out";
        return "decide early: take assignment if you want the shares, else roll to avoid it";
    }
}
