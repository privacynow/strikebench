package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.market.OptionTime;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import io.liftandshift.strikebench.util.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * THE mechanical management policy owner (program §7.5). One named, versioned, fingerprinted
 * {@link Policy} states the take-profit line, the stop line, the roll/exit clock, the harvest
 * thresholds and the assignment-decision clock. Every other surface RENDERS this policy — the
 * ticket analytics, Decide's management plan, the alert center, campaign adherence, the
 * held-position lifecycle verdict, and the backtester's exits. There is deliberately no second
 * copy of these numbers: five independent copies with five different answers was a P0.
 *
 * <p>Two invariants make the consolidation checkable:</p>
 * <ul>
 *   <li><b>ONE basis</b> — credit vs debit is always classified on the OPTION-ONLY signed net
 *       ({@link #side(long)}), never on a stock-inclusive package net. Otherwise every covered
 *       call is labelled "debit" and told to stop at half the debit paid — half the share
 *       purchase. {@link #optionEntryBasisCents} is the one way to obtain that basis.</li>
 *   <li><b>ONE clock</b> — every time threshold is expressed in TRADING SESSIONS and every caller
 *       passes the same measured {@link OptionTime.Measure}, so {@code MarketHours} owns the
 *       calendar (§7.6). Calendar days remain on the measure as a disclosed second unit; they are
 *       never a threshold.</li>
 * </ul>
 *
 * <p>Thresholds are a NAMED policy's stated heuristics — not predictions and not universal
 * financial truth. Every {@link Rule} and {@link Trigger} carries {@code policyId} and the policy
 * fingerprint so a surface can always say whose rule fired (§3.15, §7.5).</p>
 */
public final class ProtocolEvaluator {

    public static final String TAKE_PROFIT = "TAKE_PROFIT";
    public static final String STOP_LOSS = "STOP_LOSS";
    public static final String ROLL = "ROLL";
    public static final String TIME_EXIT = "TIME_EXIT";
    public static final String ASSIGNMENT = "ASSIGNMENT";
    public static final String INVALIDATION = "INVALIDATION";

    /** Alert tiers, strongest first — the same ladder {@code AlertCenterService} publishes. */
    public static final String URGENT = "URGENT";
    public static final String ATTENTION = "ATTENTION";
    public static final String INFO = "INFO";

    /**
     * Which side of the premium the package opened on, decided on the OPTION-ONLY net.
     *
     * <p>{@code FLAT} and {@code UNPRICED} are deliberately distinct. FLAT is a MEASUREMENT — the
     * package was priced and its option net is exactly zero — and it legitimately suppresses the
     * price rules. UNPRICED is an ABSENCE: the §7.2 receipt carries no price, so whether this
     * package collects or pays is unknown. Collapsing the second into the first would let a
     * missing mark be reported as a measured zero, which §3.2 forbids.</p>
     */
    public enum Side { CREDIT, DEBIT, FLAT, UNPRICED }

    /**
     * The management regime, derived from the ONE session clock and the policy's own boundaries.
     * Consumers switch on this value; nothing sniffs prose for "near-expiry" any more.
     */
    public enum Regime { NEAR_EXPIRY, SHORT_DATED, STANDARD }

    /**
     * A named, versioned, fingerprinted set of mechanical thresholds — the account's declared
     * management policy, persisted on the immutable account objective revision. This single record
     * carries BOTH the entry-protocol lines (take profit / stop / time) and the held-position
     * lifecycle thresholds (harvest, cheap risk removal, assignment decision, tail defense) so
     * there is exactly one place a threshold can be declared, overridden, or audited.
     *
     * @param policyId                            account-visible policy name (e.g. STANDARD_V1)
     * @param version                             threshold revision under that name
     * @param creditTakeProfitFraction            fraction of the collected credit whose capture takes profit
     * @param creditStopMultiple                  loss as a multiple of the collected credit that stops out
     * @param debitTakeProfitFraction             fraction of the debit paid whose gain takes profit
     * @param debitStopFraction                   fraction of the debit paid whose loss stops out
     * @param timeRuleSessions                    trading sessions to expiry at which roll/exit must be decided
     * @param nearExpirySessions                  trading sessions to expiry at which rolling stops being a plan
     * @param assignmentDecisionSessions          trading sessions to expiry at which assignment becomes an active decision
     * @param harvestCapturedPremiumPct           captured-premium percentage that opens a harvest
     * @param harvestRemainingPremiumMaxCents     residual premium ceiling a harvest must be under
     * @param cheapRiskRemovalMaxPctOfAssignment  close cost as a percentage of strike dollars that makes removal cheap
     * @param defendConfirmedEvents               whether a confirmed issuer event alone forces DEFEND
     * @param expectedShortfallDefendCents        expected-shortfall ceiling that forces DEFEND
     */
    public record Policy(
            String policyId,
            int version,
            double creditTakeProfitFraction,
            double creditStopMultiple,
            double debitTakeProfitFraction,
            double debitStopFraction,
            int timeRuleSessions,
            int nearExpirySessions,
            int assignmentDecisionSessions,
            double harvestCapturedPremiumPct,
            Long harvestRemainingPremiumMaxCents,
            double cheapRiskRemovalMaxPctOfAssignment,
            boolean defendConfirmedEvents,
            Long expectedShortfallDefendCents
    ) {
        public Policy {
            policyId = policyId == null ? "" : policyId.trim().toUpperCase(Locale.ROOT);
            if (policyId.isEmpty()) throw new IllegalArgumentException("policy id is required");
            if (version < 1) throw new IllegalArgumentException("policy version must be >= 1");
            fraction(creditTakeProfitFraction, "credit take-profit fraction");
            fraction(debitTakeProfitFraction, "debit take-profit fraction");
            fraction(debitStopFraction, "debit stop fraction");
            if (!Double.isFinite(creditStopMultiple) || creditStopMultiple <= 0 || creditStopMultiple > 100) {
                throw new IllegalArgumentException("credit stop multiple must be between 0 and 100");
            }
            sessions(timeRuleSessions, "time-rule sessions");
            sessions(nearExpirySessions, "near-expiry sessions");
            sessions(assignmentDecisionSessions, "assignment-decision sessions");
            if (nearExpirySessions > timeRuleSessions) {
                throw new IllegalArgumentException(
                        "the near-expiry window cannot be wider than the time rule that precedes it");
            }
            if (!Double.isFinite(harvestCapturedPremiumPct)
                    || harvestCapturedPremiumPct < 0 || harvestCapturedPremiumPct > 100) {
                throw new IllegalArgumentException("harvest captured-premium threshold must be 0-100%");
            }
            nonNegative(harvestRemainingPremiumMaxCents, "harvest remaining-premium threshold");
            if (!Double.isFinite(cheapRiskRemovalMaxPctOfAssignment)
                    || cheapRiskRemovalMaxPctOfAssignment < 0
                    || cheapRiskRemovalMaxPctOfAssignment > 100) {
                throw new IllegalArgumentException("cheap-risk-removal threshold must be 0-100%");
            }
            nonNegative(expectedShortfallDefendCents, "expected-shortfall defense threshold");
        }

        /**
         * The shipped default. Sessions, not calendar days: 15 sessions is the same three-week
         * distance the protocol has always meant by "~21 days", measured on the market's own clock.
         */
        public static Policy standard() {
            return new Policy("STANDARD_V1", 1, 0.50, 2.0, 0.50, 0.50,
                    15, 5, 5, 75.0, 10_000L, 0.35, false, null);
        }

        /**
         * A declared deviation from this policy under its own name — the ONLY way another lane
         * (today: a backtest request) may run different numbers, so the deviation is visible in
         * the receipt instead of hiding in a second evaluator.
         */
        public Policy overriddenAs(String adHocPolicyId, Double takeProfitFraction,
                                   Double stopMultiple, Integer timeRuleSessionsOverride) {
            if (takeProfitFraction == null && stopMultiple == null && timeRuleSessionsOverride == null) {
                return this;
            }
            double takeProfit = takeProfitFraction == null ? creditTakeProfitFraction : takeProfitFraction;
            double creditStop = stopMultiple == null ? creditStopMultiple : stopMultiple;
            double debitStop = stopMultiple == null ? debitStopFraction : Math.min(1.0, stopMultiple);
            int timeRule = timeRuleSessionsOverride == null ? timeRuleSessions : timeRuleSessionsOverride;
            return new Policy(adHocPolicyId, version, takeProfit, creditStop,
                    takeProfitFraction == null ? debitTakeProfitFraction : takeProfitFraction, debitStop,
                    timeRule, Math.min(nearExpirySessions, timeRule), assignmentDecisionSessions,
                    harvestCapturedPremiumPct, harvestRemainingPremiumMaxCents,
                    cheapRiskRemovalMaxPctOfAssignment, defendConfirmedEvents, expectedShortfallDefendCents);
        }

        /**
         * Identity of the exact numbers, so a stored receipt can prove which thresholds produced it.
         * Ignored on the wire: receipts carry the fingerprint as their own explicit field.
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String fingerprint() {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Json.canonical(this).getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException("Unable to fingerprint the management policy", e);
            }
        }

        private static void fraction(double value, String what) {
            if (!Double.isFinite(value) || value <= 0 || value > 1) {
                throw new IllegalArgumentException(what + " must be between 0 and 1");
            }
        }

        private static void sessions(int value, String what) {
            if (value < 0 || value > 2520) {
                throw new IllegalArgumentException(what + " must be between 0 and 2520 trading sessions");
            }
        }

        private static void nonNegative(Long value, String what) {
            if (value != null && value < 0) throw new IllegalArgumentException(what + " cannot be negative");
        }
    }

    /**
     * The frozen mechanical line quoted by a decision, review, or plan. Review code may describe
     * the target, stop, and time line; it may never recreate their arithmetic.
     *
     * @param triggerPnlCents          package P/L at which a price rule fires; null when the rule
     *                                 is not a price rule, or when the package opened FLAT and has
     *                                 no premium basis for a line
     * @param triggerSessionsToExpiry  trading sessions at which a time rule fires; null otherwise
     */
    public record Rule(String policyId, String policyFingerprint, String rule, Long triggerPnlCents,
                       Integer triggerSessionsToExpiry, String summary) {}

    /** One rule that actually fired against live evidence. {@code severity} is the alert tier. */
    public record Trigger(String policyId, String policyFingerprint, String rule, String severity,
                          Long triggerPnlCents, Integer triggerSessionsToExpiry, String summary) {}

    /**
     * The whole rendered protocol for one package at one moment: which named policy, which regime,
     * the typed rules, and the regime's qualitative notes. Both the ticket analytics and Decide's
     * management plan render THIS — neither restates a threshold in prose.
     */
    public record Plan(String policyId, int policyVersion, String policyFingerprint,
                       Regime regime, Side side, Integer sessionsToExpiry, Long calendarDaysToExpiry,
                       String timeBasis, List<Rule> rules, List<String> regimeNotes, String basis) {
        public Plan {
            rules = rules == null ? List.of() : List.copyOf(rules);
            regimeNotes = regimeNotes == null ? List.of() : List.copyOf(regimeNotes);
        }
    }

    /**
     * @param optionNetPremiumCents option legs ONLY, signed: {@code > 0} credit, {@code < 0} debit
     * @param unrealizedCents       live package P/L at executable closing sides (null = no marks)
     * @param timeToExpiry          the ONE measured time receipt (null = no live option expiry)
     */
    public record Inputs(long optionNetPremiumCents, Long unrealizedCents,
                         OptionTime.Measure timeToExpiry) {}

    private ProtocolEvaluator() {}

    // ---- ONE basis ------------------------------------------------------------------------

    /**
     * Credit / debit / flat on the option-only net. Zero is FLAT — never a credit. A NULL net is
     * UNPRICED — never a flat zero: the §7.2 receipt publishes null when a leg could not be marked,
     * and unboxing that to 0 would report an unknown package as a measured break-even (§3.2).
     */
    public static Side side(Long optionNetPremiumCents) {
        if (optionNetPremiumCents == null) return Side.UNPRICED;
        return optionNetPremiumCents > 0 ? Side.CREDIT
                : optionNetPremiumCents < 0 ? Side.DEBIT : Side.FLAT;
    }

    /**
     * The canonical option-only signed entry basis for a package. With no stock leg the recorded
     * package net IS the option net, so the authoritative recorded amount is kept (it may include
     * a proposed-net override the legs alone cannot reproduce). With a stock leg present the
     * option portion is repriced from the option legs through the same canonical
     * {@link PayoffCurve} the ticket used — a buy-write must never be judged on the share purchase.
     */
    public static long optionEntryBasisCents(List<Leg> legs, int qty, long packageEntryNetCents) {
        if (legs == null || legs.stream().noneMatch(Leg::isStock)) return packageEntryNetCents;
        List<Leg> optionLegs = legs.stream().filter(leg -> !leg.isStock()).toList();
        if (optionLegs.isEmpty()) return 0;
        return PayoffCurve.of(optionLegs, qty).entryNetPremiumCents();
    }

    /**
     * The canonical stock-only signed cash flow of a package: the shares bought or sold INSIDE the
     * package, priced from the stock legs themselves through the same {@link PayoffCurve} the
     * ticket used. A package with no stock leg has none, and the answer is exactly zero.
     *
     * <p>This is the independent counterpart to {@link #optionEntryBasisCents}, and it exists so
     * the §7.2 receipt's additive identity — package net == option net + stock cash — compares two
     * separately measured facts against the recorded package net instead of restating it. Deriving
     * this side as {@code packageNet - optionNet} made the identity true by construction for every
     * producer, so a package net struck on one basis beside an option net struck on another (the
     * exact defect §3.3 exists to catch) passed silently.</p>
     */
    public static long stockEntryBasisCents(List<Leg> legs, int qty) {
        if (legs == null) return 0;
        List<Leg> stockLegs = legs.stream().filter(Leg::isStock).toList();
        if (stockLegs.isEmpty()) return 0;
        return PayoffCurve.of(stockLegs, qty).entryNetPremiumCents();
    }

    /**
     * The option-only basis contributed by ONE tracked lot, prorated to the quantity this structure
     * actually holds. The lot's economic remaining open amount covers its whole remaining position;
     * comparing it against a close priced on the allocated quantity would fire the price rules at
     * the wrong P/L for any partially allocated lot.
     */
    public static long trackedLotBasisCents(String side, long economicRemainingOpenAmountCents,
                                            long allocatedQuantity, long remainingQuantity) {
        long amount = remainingQuantity > 0 && allocatedQuantity != remainingQuantity
                ? Math.round((double) economicRemainingOpenAmountCents
                        * allocatedQuantity / remainingQuantity)
                : economicRemainingOpenAmountCents;
        return "SHORT".equals(side) ? amount : Math.negateExact(amount);
    }

    // ---- ONE clock ------------------------------------------------------------------------

    /**
     * The ONE measured time receipt for the protocol, or null when there is no live decision left:
     * no option legs, or the nearest expiry has already passed (settlement mechanics own an expired
     * leg — the roll/exit rule has nothing left to ask). Callers must never build their own.
     */
    public static OptionTime.Measure timeTo(LocalDate today, LocalDate nearestExpiry) {
        if (today == null || nearestExpiry == null || nearestExpiry.isBefore(today)) return null;
        return OptionTime.toExpiry(today, nearestExpiry);
    }

    /** Same, from a leg list: the nearest option expiry decides. */
    public static OptionTime.Measure timeTo(List<Leg> legs, LocalDate today) {
        LocalDate nearest = legs == null ? null : legs.stream().filter(leg -> !leg.isStock())
                .map(Leg::expiration).filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo).orElse(null);
        return timeTo(today, nearest);
    }

    /** The regime this package is in, on the policy's own session boundaries. */
    public static Regime regime(Policy policy, OptionTime.Measure time) {
        if (time == null) return Regime.STANDARD;
        if (time.sessions() <= policy.nearExpirySessions()) return Regime.NEAR_EXPIRY;
        if (time.sessions() <= policy.timeRuleSessions()) return Regime.SHORT_DATED;
        return Regime.STANDARD;
    }

    // ---- The rules ------------------------------------------------------------------------

    /**
     * The exact target / stop / time lines for one signed OPTION-ONLY entry, always three rules in
     * this order: take profit, stop loss, time. A FLAT package has no premium basis, so its price
     * lines carry a null trigger rather than a fabricated $0 line.
     */
    public static List<Rule> rules(Policy policy, long optionNetPremiumCents) {
        return rules(policy, Long.valueOf(optionNetPremiumCents), null, policy.fingerprint());
    }

    /**
     * Same lines for a package whose §7.2 receipt states no price. Every price rule loses its
     * trigger and says WHY — a "stop at 2x the credit" is not merely unknown, it is meaningless
     * until a credit exists. The time and invalidation rules are unaffected: they are measured on
     * the calendar, not on the premium, so withholding them too would hide a plan we can prove.
     */
    public static List<Rule> unpricedRules(Policy policy, String unpricedReason) {
        return rules(policy, null, unpricedReason, policy.fingerprint());
    }

    /**
     * Fingerprinting hashes the whole policy, so each public entry point computes it exactly once.
     * A null {@code optionNetPremiumCents} is the UNPRICED case, never a zero.
     */
    private static List<Rule> rules(Policy policy, Long optionNetPremiumCents,
                                    String unpricedReason, String print) {
        Side side = side(optionNetPremiumCents);
        boolean credit = side == Side.CREDIT;
        boolean noBasis = side == Side.UNPRICED || side == Side.FLAT;
        long basis = optionNetPremiumCents == null ? 0 : Math.abs(optionNetPremiumCents);
        String id = policy.policyId();
        Long target = noBasis ? null : Math.round((credit ? policy.creditTakeProfitFraction()
                : policy.debitTakeProfitFraction()) * basis);
        Long stop = noBasis ? null : -Math.round((credit ? policy.creditStopMultiple()
                : policy.debitStopFraction()) * basis);
        int pct = (int) Math.round(100 * (credit ? policy.creditTakeProfitFraction()
                : policy.debitTakeProfitFraction()));
        String absence = unpricedNote(unpricedReason);
        String targetSummary = side == Side.UNPRICED
                ? "This package has no price, so there is no premium basis for a take-profit line. " + absence
                : basis == 0
                ? "This package opened flat, so there is no premium basis for a take-profit line."
                : credit ? "the credit has decayed to ~" + pct + "% — most of the edge is captured"
                : "the gain has reached ~" + pct + "% of the debit paid";
        String stopSummary = side == Side.UNPRICED
                ? "This package has no price, so there is no premium basis for a stop line. " + absence
                : basis == 0
                ? "This package opened flat, so there is no premium basis for a stop line."
                : credit ? "the loss has reached ~" + trim(policy.creditStopMultiple())
                        + "x the credit collected"
                : "the loss has reached ~" + (int) Math.round(100 * policy.debitStopFraction())
                        + "% of the debit paid";
        return List.of(
                new Rule(id, print, TAKE_PROFIT, target, null, targetSummary),
                new Rule(id, print, STOP_LOSS, stop, null, stopSummary),
                timeRule(policy, side, null, print));
    }

    /** The absence sentence the price rules carry, phrased once. */
    private static String unpricedNote(String unpricedReason) {
        return unpricedReason == null || unpricedReason.isBlank()
                ? "No package price is available." : unpricedReason;
    }

    /**
     * The time rule for one package. Inside the near-expiry window the rule KIND changes from ROLL
     * to TIME_EXIT, so no consumer has to read prose to discover that rolling is no longer a plan.
     * A null measure asks for the frozen line at the policy threshold rather than an observation.
     */
    private static Rule timeRule(Policy policy, Side side, OptionTime.Measure time, String print) {
        boolean nearExpiry = time != null && time.sessions() <= policy.nearExpirySessions();
        boolean credit = side == Side.CREDIT;
        int threshold = nearExpiry ? policy.nearExpirySessions() : policy.timeRuleSessions();
        // An unpriced package still has a calendar, so the time rule stands; only its credit-vs-debit
        // FRAMING is withheld, because naming a side would assert the very fact that is missing.
        String what = nearExpiry
                ? "close rather than roll — at this distance a roll is a new trade, not management"
                : side == Side.UNPRICED
                ? "revisit the position — with no package price, whether rolling pays for itself "
                        + "cannot be judged, so the conservative line is to close"
                : credit ? "decide: roll out or close"
                : "exit before steep time decay if the thesis has not moved";
        // ROLL is the CREDIT-only recommendation: it assumes premium is being collected. Without a
        // price we cannot claim that, so an unpriced package takes the conservative TIME_EXIT line
        // exactly as a measured-flat one does.
        return new Rule(policy.policyId(), print, nearExpiry || !credit ? TIME_EXIT : ROLL,
                null, threshold,
                sessionsPhrase(time == null ? threshold : time.sessions()) + " to expiry — " + what);
    }

    /**
     * The whole protocol rendered for one package: the three frozen lines, the assignment and
     * invalidation rules when they apply, and the regime's qualitative notes. This is the ONE
     * producer behind both the ticket's {@code managementPlan} and Decide's management plan — a
     * three-session package is never told to "roll at 21 DTE" because the rule itself changes.
     */
    public static Plan plan(Policy policy, long optionNetPremiumCents, OptionTime.Measure time,
                            boolean hasShortLegs) {
        return plan(policy, Long.valueOf(optionNetPremiumCents), null, time, hasShortLegs);
    }

    /**
     * The same protocol for a package whose §7.2 receipt states no price. The time, assignment and
     * invalidation rules are unchanged — they are calendar and structure facts, and withholding
     * them would hide guidance we can actually prove — while the price rules carry no trigger and
     * state the absence. The plan's {@code side} is {@link Side#UNPRICED}, never FLAT (§3.2).
     */
    public static Plan unpricedPlan(Policy policy, String unpricedReason, OptionTime.Measure time,
                                    boolean hasShortLegs) {
        return plan(policy, null, unpricedReason, time, hasShortLegs);
    }

    private static Plan plan(Policy policy, Long optionNetPremiumCents, String unpricedReason,
                             OptionTime.Measure time, boolean hasShortLegs) {
        Regime regime = regime(policy, time);
        Side side = side(optionNetPremiumCents);
        String id = policy.policyId();
        String print = policy.fingerprint();
        List<Rule> rules = new ArrayList<>(rules(policy, optionNetPremiumCents, unpricedReason, print));
        rules.set(2, timeRule(policy, side, time, print));
        if (hasShortLegs) {
            rules.add(new Rule(id, print, ASSIGNMENT, null, policy.assignmentDecisionSessions(),
                    "a short leg is in the money inside "
                            + sessionsPhrase(policy.assignmentDecisionSessions()) + " of expiry"));
        }
        rules.add(new Rule(id, print, INVALIDATION, null, null,
                "the thesis breaks (see 'would invalidate') — the reason to hold is gone"));

        List<String> notes = new ArrayList<>();
        switch (regime) {
            case NEAR_EXPIRY -> {
                notes.add("If price TOUCHES a short strike, close that side immediately; do not hold and hope.");
                notes.add("Do not carry a near-the-money short strike into the final hour — pin and "
                        + "assignment mechanics take over.");
                if (time != null && time.calendarDays() - time.sessions() >= 2) {
                    notes.add("A weekend/holiday gap sits inside this trade — the market can reopen "
                            + "through your strikes with no chance to react.");
                }
            }
            case SHORT_DATED -> notes.add("In the final week, close or roll — do not let a winner "
                    + "decay into a coin flip at expiry.");
            case STANDARD -> notes.add("Gamma grows fast once the time rule is reached; the plan "
                    + "matters more than the entry from there on.");
        }
        String basis = "Mechanical rules from the named policy " + id + " v" + policy.version()
                + ", measured on the option-only entry basis and the market's trading-session "
                + "clock. Heuristics, not predictions.";
        if (side == Side.UNPRICED) {
            basis = "Mechanical rules from the named policy " + id + " v" + policy.version()
                    + ", on the market's trading-session clock. The PRICE rules are unavailable "
                    + "because this package has no entry basis: " + unpricedNote(unpricedReason)
                    + " Heuristics, not predictions.";
        }
        return new Plan(id, policy.version(), print, regime, side,
                time == null ? null : time.sessions(), time == null ? null : time.calendarDays(),
                time == null ? "no live option expiry" : time.basis(), rules, notes, basis);
    }

    /**
     * Evaluates the named policy's rules against live evidence; returns fired rules ordered
     * most-significant first (stop before take-profit before time). Empty means the protocol is
     * quiet. The price rules stay silent without marks or without a premium basis, and the time
     * rule stays silent once the expiry has passed ({@link #timeTo} returns null there).
     */
    public static List<Trigger> evaluate(Policy policy, Inputs in) {
        List<Trigger> out = new ArrayList<>();
        String print = policy.fingerprint();
        List<Rule> rules = rules(policy, in.optionNetPremiumCents(), null, print);
        Rule target = rules.get(0);
        Rule stop = rules.get(1);
        Long pnl = in.unrealizedCents();
        if (pnl != null && target.triggerPnlCents() != null && stop.triggerPnlCents() != null) {
            if (pnl <= stop.triggerPnlCents()) out.add(fired(stop, URGENT));
            else if (pnl >= target.triggerPnlCents()) out.add(fired(target, ATTENTION));
        }
        OptionTime.Measure time = in.timeToExpiry();
        if (time != null && time.sessions() <= policy.timeRuleSessions()) {
            Rule rule = timeRule(policy, side(in.optionNetPremiumCents()), time, print);
            out.add(new Trigger(rule.policyId(), rule.policyFingerprint(), rule.rule(), INFO, null,
                    time.sessions(), rule.summary()));
        }
        return out;
    }

    private static Trigger fired(Rule rule, String severity) {
        return new Trigger(rule.policyId(), rule.policyFingerprint(), rule.rule(), severity,
                rule.triggerPnlCents(), rule.triggerSessionsToExpiry(), rule.summary());
    }

    private static String sessionsPhrase(int sessions) {
        return "~" + sessions + " trading session" + (sessions == 1 ? "" : "s");
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
