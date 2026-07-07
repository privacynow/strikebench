package io.liftandshift.strategy;

import io.liftandshift.model.Leg;
import io.liftandshift.model.LegAction;
import io.liftandshift.model.OptionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Structural risk-coverage check, independent of the payoff curve. A short option unit is
 * covered only by a long unit that survives at least as long AND caps the loss:
 *   short CALL @K needs a long CALL with strike <= K and expiration >= the short's
 *   short PUT  @K needs a long PUT  with strike >= K and expiration >= the short's
 *   long stock (100-share lots) covers short calls at any strike.
 * Anything short and unmatched is undefined risk — this is what catches reverse
 * calendars, inverted diagonals, and net-short ratio structures that a single-expiration
 * payoff curve cannot see.
 */
public final class CoverageCheck {

    private CoverageCheck() {}

    private record Unit(BigDecimal strike, LocalDate expiration) {}

    /** Human-readable descriptions of uncovered short exposure; empty = structurally defined risk. */
    public static List<String> uncoveredShorts(List<Leg> legs) {
        return uncoveredShorts(legs, 0);
    }

    /**
     * Same check, but with {@code heldCallCoverLots} additional 100-share lots the account
     * holds OUTSIDE these legs (locked to the trade) available to cover short calls.
     */
    public static List<String> uncoveredShorts(List<Leg> legs, int heldCallCoverLots) {
        List<String> problems = new ArrayList<>();

        int stockLots = Math.max(0, heldCallCoverLots);
        for (Leg leg : legs) {
            if (leg.isStock()) {
                if (leg.action() == LegAction.SELL) {
                    problems.add("Short stock legs are not supported");
                } else {
                    stockLots += leg.ratio();
                }
            }
        }

        for (OptionType type : OptionType.values()) {
            List<Unit> shorts = units(legs, type, LegAction.SELL);
            List<Unit> longs = units(legs, type, LegAction.BUY);
            int stockAvailable = type == OptionType.CALL ? stockLots : 0;
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
     * Smallest number of EXTRA held 100-share lots (per 1x of these legs) that would make the
     * short-CALL side fully covered. 0 = already covered. -1 = shares cannot fix it
     * (short stock present, or the PUT side is itself uncovered — shares never cover puts).
     */
    public static int callCoverLotsNeeded(List<Leg> legs) {
        if (uncoveredShorts(legs).isEmpty()) return 0;
        int shortCallUnits = units(legs, OptionType.CALL, LegAction.SELL).size();
        for (int n = 1; n <= shortCallUnits; n++) {
            if (uncoveredShorts(legs, n).isEmpty()) return n;
        }
        return -1;
    }

    private static List<Unit> units(List<Leg> legs, OptionType type, LegAction action) {
        List<Unit> out = new ArrayList<>();
        for (Leg leg : legs) {
            if (leg.isStock() || leg.type() != type || leg.action() != action) continue;
            for (int i = 0; i < leg.ratio(); i++) out.add(new Unit(leg.strike(), leg.expiration()));
        }
        return out;
    }

    /** Exhaustive matching (leg counts are tiny) — greedy can miss valid assignments. */
    private static boolean matchable(List<Unit> shorts, int idx, List<Unit> longs, boolean[] used,
                                     int stockAvailable, OptionType type) {
        if (idx == shorts.size()) return true;
        Unit s = shorts.get(idx);
        for (int i = 0; i < longs.size(); i++) {
            if (used[i]) continue;
            Unit l = longs.get(i);
            boolean strikeOk = type == OptionType.CALL
                    ? l.strike().compareTo(s.strike()) <= 0
                    : l.strike().compareTo(s.strike()) >= 0;
            if (strikeOk && !l.expiration().isBefore(s.expiration())) {
                used[i] = true;
                if (matchable(shorts, idx + 1, longs, used, stockAvailable, type)) return true;
                used[i] = false;
            }
        }
        if (stockAvailable > 0 && type == OptionType.CALL) {
            return matchable(shorts, idx + 1, longs, used, stockAvailable - 1, type);
        }
        return false;
    }
}
