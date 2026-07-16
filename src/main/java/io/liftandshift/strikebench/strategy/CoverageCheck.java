package io.liftandshift.strikebench.strategy;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Structural risk-coverage check, independent of the payoff curve. A short option unit is
 * covered only by a long unit that survives at least as long AND caps the loss:
 *   short CALL @K needs a long CALL with strike <= K and expiration >= the short's
 *   short PUT  @K needs a long PUT  with strike >= K and expiration >= the short's
 *   long stock covers short calls at any strike, in exact deliverable shares.
 * Anything short and unmatched is undefined risk — this is what catches reverse
 * calendars, inverted diagonals, and net-short ratio structures that a single-expiration
 * payoff curve cannot see.
 */
public final class CoverageCheck {

    private CoverageCheck() {}

    private record Unit(BigDecimal strike, LocalDate expiration, int multiplier) {}

    /** Human-readable descriptions of uncovered short exposure; empty = structurally defined risk. */
    public static List<String> uncoveredShorts(List<Leg> legs) {
        return uncoveredShortsWithHeldShares(legs, 0);
    }

    /** Same structural check with exact external shares available to cover short calls. */
    public static List<String> uncoveredShortsWithHeldShares(List<Leg> legs, long heldCallCoverShares) {
        List<String> problems = new ArrayList<>();

        long stockShares = Math.max(0, heldCallCoverShares);
        for (Leg leg : legs) {
            if (leg.isStock()) {
                if (leg.action() == LegAction.SELL) {
                    problems.add("Short stock legs are not supported");
                } else {
                    stockShares = Math.addExact(stockShares,
                            Math.multiplyExact((long) leg.ratio(), leg.multiplier()));
                }
            }
        }

        for (OptionType type : OptionType.values()) {
            List<Unit> shorts = units(legs, type, LegAction.SELL);
            List<Unit> longs = units(legs, type, LegAction.BUY);
            long stockAvailable = type == OptionType.CALL ? stockShares : 0;
            if (shorts.isEmpty()) continue;
            if (!matchable(shorts, 0, longs, new boolean[longs.size()], stockAvailable, type)) {
                problems.add("Short " + type + " exposure is not fully covered by longer-dated, "
                        + (type == OptionType.CALL ? "lower" : "higher") + "-strike longs"
                        + (type == OptionType.CALL ? " or stock" : "") + " — undefined risk");
            }
        }
        return problems;
    }

    /**
     * Smallest number of exact external shares needed per package unit to cover short calls.
     * Returns -1 when shares cannot make the complete structure defined-risk.
     */
    public static long callCoverSharesNeeded(List<Leg> legs) {
        if (uncoveredShorts(legs).isEmpty()) return 0;
        long upper = units(legs, OptionType.CALL, LegAction.SELL).stream()
                .mapToLong(Unit::multiplier).sum();
        if (upper == 0 || !uncoveredShortsWithHeldShares(legs, upper).isEmpty()) return -1;
        long low = 0, high = upper;
        while (low < high) {
            long mid = low + (high - low) / 2;
            if (uncoveredShortsWithHeldShares(legs, mid).isEmpty()) high = mid;
            else low = mid + 1;
        }
        return low;
    }

    /**
     * Exact held-share context needed by one package unit. A partly covered short-call ratio
     * needs only the residual deliverable not already covered by long calls. With no short
     * calls, the largest option leg defines the stock context (for example, a protective put).
     */
    public static long shareContextUnitsNeeded(List<Leg> legs) {
        long callCover = callCoverSharesNeeded(legs);
        if (callCover > 0) return callCover;
        if (legs.stream().anyMatch(leg -> !leg.isStock()
                && leg.type() == OptionType.CALL && leg.action() == LegAction.SELL)) return 0;
        return legs.stream().filter(leg -> !leg.isStock())
                .mapToLong(leg -> Math.multiplyExact((long) leg.ratio(), leg.multiplier()))
                .max().orElse(0);
    }

    private static List<Unit> units(List<Leg> legs, OptionType type, LegAction action) {
        List<Unit> out = new ArrayList<>();
        for (Leg leg : legs) {
            if (leg.isStock() || leg.type() != type || leg.action() != action) continue;
            for (int i = 0; i < leg.ratio(); i++) {
                out.add(new Unit(leg.strike(), leg.expiration(), leg.multiplier()));
            }
        }
        return out;
    }

    /** Exhaustive matching (leg counts are tiny) — greedy can miss valid assignments. */
    private static boolean matchable(List<Unit> shorts, int idx, List<Unit> longs, boolean[] used,
                                     long stockAvailable, OptionType type) {
        if (idx == shorts.size()) return true;
        Unit s = shorts.get(idx);
        for (int i = 0; i < longs.size(); i++) {
            if (used[i]) continue;
            Unit l = longs.get(i);
            boolean strikeOk = type == OptionType.CALL
                    ? l.strike().compareTo(s.strike()) <= 0
                    : l.strike().compareTo(s.strike()) >= 0;
            if (l.multiplier() == s.multiplier()
                    && strikeOk && !l.expiration().isBefore(s.expiration())) {
                used[i] = true;
                if (matchable(shorts, idx + 1, longs, used, stockAvailable, type)) return true;
                used[i] = false;
            }
        }
        if (stockAvailable >= s.multiplier() && type == OptionType.CALL) {
            return matchable(shorts, idx + 1, longs, used,
                    stockAvailable - s.multiplier(), type);
        }
        return false;
    }
}
