package io.liftandshift.strikebench.strategy;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Constructs concrete legs for a strategy family from an option chain using
 * delta-targeted strike selection. Shared by the recommendation engine and the backtester.
 * Entry prices are chain mids; callers adjust for slippage as needed.
 */
public final class StrategyBuilder {

    private StrategyBuilder() {}

    public record Built(List<Leg> legs, List<OptionQuote> quotes, String label) {}

    /**
     * Intent-flow construction hints. targetPrice steers the short strike of covered calls /
     * cash-secured puts (and the collar's call) to the user's desired sell/buy level instead of
     * the default delta target; sharesHeld omits the stock BUY leg from stock-hedged families
     * because the account already owns the shares (the trade layer locks them as coverage).
     */
    public record BuildHints(BigDecimal targetPrice, boolean sharesHeld) {
        public static final BuildHints NONE = new BuildHints(null, false);
    }

    /** Returns null when the family cannot be built from this chain. */
    public static Built build(StrategyFamily family, OptionChain chain, OptionChain farChain, BigDecimal spot) {
        return build(family, chain, farChain, spot, BuildHints.NONE);
    }

    /** Returns null when the family cannot be built from this chain. */
    public static Built build(StrategyFamily family, OptionChain chain, OptionChain farChain, BigDecimal spot,
                              BuildHints hints) {
        try {
            return switch (family) {
                case LONG_CALL -> single(chain, OptionType.CALL, LegAction.BUY, 0.50);
                case LONG_PUT -> single(chain, OptionType.PUT, LegAction.BUY, 0.50);
                case LONG_STRADDLE -> straddle(chain, spot);
                case LONG_STRANGLE -> strangle(chain);
                case NAKED_CALL -> single(chain, OptionType.CALL, LegAction.SELL, 0.30);
                case NAKED_PUT -> single(chain, OptionType.PUT, LegAction.SELL, 0.30);
                case DEBIT_CALL_SPREAD -> vertical(chain, OptionType.CALL, LegAction.BUY, 0.50, +2);
                case DEBIT_PUT_SPREAD -> vertical(chain, OptionType.PUT, LegAction.BUY, 0.50, -2);
                case CREDIT_CALL_SPREAD -> vertical(chain, OptionType.CALL, LegAction.SELL, 0.27, +1);
                case CREDIT_PUT_SPREAD -> vertical(chain, OptionType.PUT, LegAction.SELL, 0.27, -1);
                case IRON_CONDOR -> combine(vertical(chain, OptionType.PUT, LegAction.SELL, 0.25, -1),
                        vertical(chain, OptionType.CALL, LegAction.SELL, 0.25, +1));
                case IRON_BUTTERFLY -> ironButterfly(chain, spot);
                case LONG_CALL_BUTTERFLY -> butterfly(chain, OptionType.CALL, spot);
                case LONG_PUT_BUTTERFLY -> butterfly(chain, OptionType.PUT, spot);
                case CALENDAR_CALL -> calendar(chain, farChain, OptionType.CALL, spot);
                case CALENDAR_PUT -> calendar(chain, farChain, OptionType.PUT, spot);
                case DIAGONAL_CALL -> diagonal(chain, farChain, OptionType.CALL);
                case DIAGONAL_PUT -> diagonal(chain, farChain, OptionType.PUT);
                case COVERED_CALL -> coveredCall(chain, spot, hints);
                case COVERED_STRANGLE -> coveredStrangle(chain, spot, hints);
                case COVERED_CALL_PUT_SPREAD -> coveredCallPutSpread(chain, spot, hints);
                case COVERED_CALL_CALL_OVERLAY -> coveredCallCallOverlay(chain, spot, hints);
                case CASH_SECURED_PUT -> cashSecuredPut(chain, hints);
                case PROTECTIVE_COLLAR -> collar(chain, spot, hints);
                case PROTECTIVE_PUT -> protectivePut(chain, spot, hints);
                case SHORT_STRADDLE, SHORT_STRANGLE -> null; // never built, never recommended
            };
        } catch (RuntimeException e) {
            return null; // unbuildable from this chain
        }
    }

    private static Built single(OptionChain chain, OptionType type, LegAction action, double targetDelta) {
        OptionQuote q = byDelta(chain, type, targetDelta);
        if (q == null) return null;
        return new Built(List.of(leg(action, q)), List.of(q),
                action + " " + strikeLabel(q) + " " + chain.expiration());
    }

    private static Built vertical(OptionChain chain, OptionType type, LegAction anchorAction, double targetDelta, int hedgeSteps) {
        OptionQuote anchor = byDelta(chain, type, targetDelta);
        OptionQuote hedge = anchor == null ? null : stepAway(chain, type, anchor.strike(), hedgeSteps);
        if (anchor == null || hedge == null) return null;
        LegAction hedgeAction = anchorAction.opposite();
        return new Built(List.of(leg(anchorAction, anchor), leg(hedgeAction, hedge)), List.of(anchor, hedge),
                anchorAction + " " + strikeLabel(anchor) + " / " + hedgeAction + " " + strikeLabel(hedge) + " " + chain.expiration());
    }

    private static Built combine(Built a, Built b) {
        if (a == null || b == null) return null;
        List<Leg> legs = new ArrayList<>(a.legs()); legs.addAll(b.legs());
        List<OptionQuote> quotes = new ArrayList<>(a.quotes()); quotes.addAll(b.quotes());
        return new Built(legs, quotes, a.label() + " + " + b.label());
    }

    /** Buy the ATM call AND put: a defined-risk bet on a big move in EITHER direction. */
    private static Built straddle(OptionChain chain, BigDecimal spot) {
        BigDecimal atm = nearestStrike(chain, spot);
        OptionQuote call = at(chain, OptionType.CALL, atm);
        OptionQuote put = at(chain, OptionType.PUT, atm);
        if (call == null || put == null) return null;
        return new Built(
                List.of(leg(LegAction.BUY, call), leg(LegAction.BUY, put)),
                List.of(call, put),
                "BUY " + atm.stripTrailingZeros().toPlainString() + " straddle " + chain.expiration());
    }

    /** Buy an OTM call and an OTM put (~30 delta): cheaper than a straddle, needs a bigger move. */
    private static Built strangle(OptionChain chain) {
        OptionQuote call = byDelta(chain, OptionType.CALL, 0.30);
        OptionQuote put = byDelta(chain, OptionType.PUT, 0.30);
        if (call == null || put == null
                || call.strike().compareTo(put.strike()) <= 0) return null; // degenerate chain
        return new Built(
                List.of(leg(LegAction.BUY, call), leg(LegAction.BUY, put)),
                List.of(call, put),
                "BUY " + put.strike().stripTrailingZeros().toPlainString() + "/"
                        + call.strike().stripTrailingZeros().toPlainString() + " strangle " + chain.expiration());
    }

    private static Built ironButterfly(OptionChain chain, BigDecimal spot) {
        BigDecimal atm = nearestStrike(chain, spot);
        OptionQuote shortCall = at(chain, OptionType.CALL, atm);
        OptionQuote shortPut = at(chain, OptionType.PUT, atm);
        OptionQuote wingCall = stepAway(chain, OptionType.CALL, atm, +2);
        OptionQuote wingPut = stepAway(chain, OptionType.PUT, atm, -2);
        if (shortCall == null || shortPut == null || wingCall == null || wingPut == null) return null;
        return new Built(
                List.of(leg(LegAction.SELL, shortCall), leg(LegAction.SELL, shortPut),
                        leg(LegAction.BUY, wingCall), leg(LegAction.BUY, wingPut)),
                List.of(shortCall, shortPut, wingCall, wingPut),
                "SELL " + atm.stripTrailingZeros().toPlainString() + " straddle, BUY wings " + chain.expiration());
    }

    private static Built butterfly(OptionChain chain, OptionType type, BigDecimal spot) {
        BigDecimal atm = nearestStrike(chain, spot);
        OptionQuote lower = stepAway(chain, type, atm, -2);
        OptionQuote middle = at(chain, type, atm);
        OptionQuote upper = stepAway(chain, type, atm, +2);
        if (lower == null || middle == null || upper == null) return null;
        Leg mid = new Leg(LegAction.SELL, type, middle.strike(), chain.expiration(), 2, mid(middle),
                Leg.SHARES_PER_CONTRACT);
        return new Built(List.of(leg(LegAction.BUY, lower), mid, leg(LegAction.BUY, upper)),
                List.of(lower, middle, upper),
                "BUY " + strikeLabel(lower) + " / SELL 2x " + strikeLabel(middle) + " / BUY " + strikeLabel(upper) + " " + chain.expiration());
    }

    private static Built calendar(OptionChain near, OptionChain far, OptionType type, BigDecimal spot) {
        if (far == null) return null;
        BigDecimal atm = nearestStrike(near, spot);
        OptionQuote nearQ = at(near, type, atm);
        OptionQuote farQ = at(far, type, atm);
        if (nearQ == null || farQ == null) return null;
        return new Built(List.of(leg(LegAction.SELL, nearQ), leg(LegAction.BUY, farQ)), List.of(nearQ, farQ),
                "SELL " + strikeLabel(nearQ) + " " + near.expiration() + " / BUY " + strikeLabel(farQ) + " " + far.expiration());
    }

    private static Built diagonal(OptionChain near, OptionChain far, OptionType type) {
        if (far == null) return null;
        OptionQuote longQ = byDelta(far, type, 0.60);
        OptionQuote shortQ = byDelta(near, type, 0.30);
        if (longQ == null || shortQ == null || longQ.strike().compareTo(shortQ.strike()) == 0) return null;
        return new Built(List.of(leg(LegAction.BUY, longQ), leg(LegAction.SELL, shortQ)), List.of(longQ, shortQ),
                "BUY " + strikeLabel(longQ) + " " + far.expiration() + " / SELL " + strikeLabel(shortQ) + " " + near.expiration());
    }

    private static Built coveredCall(OptionChain chain, BigDecimal spot, BuildHints hints) {
        OptionQuote call = hints.targetPrice() != null
                ? strikeAtOrAbove(chain, OptionType.CALL, hints.targetPrice())
                : byDelta(chain, OptionType.CALL, 0.30);
        if (call == null) call = byDelta(chain, OptionType.CALL, 0.30);
        if (call == null) return null;
        if (hints.sharesHeld()) {
            return new Built(List.of(leg(LegAction.SELL, call)), listOf(call),
                    "SELL " + strikeLabel(call) + " " + chain.expiration() + " against held shares");
        }
        List<Leg> legs = List.of(Leg.stock(LegAction.BUY, 1, spot), leg(LegAction.SELL, call));
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(null); // stock leg has no option quote
        quotes.add(call);
        return new Built(legs, quotes, "BUY 100 shares / SELL " + strikeLabel(call) + " " + chain.expiration());
    }

    /** Covered call plus a cash-secured put: double premium and a standing repurchase bid below. */
    private static Built coveredStrangle(OptionChain chain, BigDecimal spot, BuildHints hints) {
        OptionQuote call = hints.targetPrice() != null
                ? strikeAtOrAbove(chain, OptionType.CALL, hints.targetPrice())
                : byDelta(chain, OptionType.CALL, 0.30);
        if (call == null) call = byDelta(chain, OptionType.CALL, 0.30);
        OptionQuote put = byDelta(chain, OptionType.PUT, 0.25);
        if (call == null || put == null) return null;
        if (put.strike().compareTo(call.strike()) >= 0) return null; // degenerate — the strikes must bracket the price
        String label = "SELL " + strikeLabel(call) + " / SELL " + strikeLabel(put) + " " + chain.expiration();
        if (hints.sharesHeld()) {
            List<OptionQuote> quotes = new ArrayList<>();
            quotes.add(call);
            quotes.add(put);
            return new Built(List.of(leg(LegAction.SELL, call), leg(LegAction.SELL, put)), quotes,
                    label + " around held shares");
        }
        List<Leg> legs = List.of(Leg.stock(LegAction.BUY, 1, spot), leg(LegAction.SELL, call), leg(LegAction.SELL, put));
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(null); // stock leg has no option quote
        quotes.add(call);
        quotes.add(put);
        return new Built(legs, quotes, "BUY 100 shares / " + label);
    }

    /** Covered call whose premium funds a debit put spread: a protected shelf below the shares. */
    private static Built coveredCallPutSpread(OptionChain chain, BigDecimal spot, BuildHints hints) {
        OptionQuote call = hints.targetPrice() != null
                ? strikeAtOrAbove(chain, OptionType.CALL, hints.targetPrice())
                : byDelta(chain, OptionType.CALL, 0.30);
        if (call == null) call = byDelta(chain, OptionType.CALL, 0.30);
        OptionQuote floorPut = byDelta(chain, OptionType.PUT, 0.30);
        OptionQuote fundingPut = floorPut == null ? null : stepAway(chain, OptionType.PUT, floorPut.strike(), -2);
        if (call == null || floorPut == null || fundingPut == null) return null;
        if (fundingPut.strike().compareTo(floorPut.strike()) >= 0
                || floorPut.strike().compareTo(call.strike()) >= 0) return null;
        String label = "SELL " + strikeLabel(call) + " / BUY " + strikeLabel(floorPut)
                + " / SELL " + strikeLabel(fundingPut) + " " + chain.expiration();
        if (hints.sharesHeld()) {
            List<OptionQuote> quotes = new ArrayList<>();
            quotes.add(call);
            quotes.add(floorPut);
            quotes.add(fundingPut);
            return new Built(List.of(leg(LegAction.SELL, call), leg(LegAction.BUY, floorPut),
                    leg(LegAction.SELL, fundingPut)), quotes, label + " around held shares");
        }
        List<Leg> legs = List.of(Leg.stock(LegAction.BUY, 1, spot), leg(LegAction.SELL, call),
                leg(LegAction.BUY, floorPut), leg(LegAction.SELL, fundingPut));
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(null);
        quotes.add(call);
        quotes.add(floorPut);
        quotes.add(fundingPut);
        return new Built(legs, quotes, "BUY 100 shares / " + label);
    }

    /** Covered call plus a farther long call: upside participation resumes above the overlay strike. */
    private static Built coveredCallCallOverlay(OptionChain chain, BigDecimal spot, BuildHints hints) {
        OptionQuote shortCall = hints.targetPrice() != null
                ? strikeAtOrAbove(chain, OptionType.CALL, hints.targetPrice())
                : byDelta(chain, OptionType.CALL, 0.35);
        if (shortCall == null) shortCall = byDelta(chain, OptionType.CALL, 0.35);
        OptionQuote overlay = shortCall == null ? null : stepAway(chain, OptionType.CALL, shortCall.strike(), +2);
        if (shortCall == null || overlay == null) return null;
        if (overlay.strike().compareTo(shortCall.strike()) <= 0) return null;
        String label = "SELL " + strikeLabel(shortCall) + " / BUY " + strikeLabel(overlay) + " " + chain.expiration();
        if (hints.sharesHeld()) {
            List<OptionQuote> quotes = new ArrayList<>();
            quotes.add(shortCall);
            quotes.add(overlay);
            return new Built(List.of(leg(LegAction.SELL, shortCall), leg(LegAction.BUY, overlay)), quotes,
                    label + " over held shares");
        }
        List<Leg> legs = List.of(Leg.stock(LegAction.BUY, 1, spot), leg(LegAction.SELL, shortCall),
                leg(LegAction.BUY, overlay));
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(null);
        quotes.add(shortCall);
        quotes.add(overlay);
        return new Built(legs, quotes, "BUY 100 shares / " + label);
    }

    private static Built cashSecuredPut(OptionChain chain, BuildHints hints) {
        OptionQuote put = hints.targetPrice() != null
                ? strikeAtOrBelow(chain, OptionType.PUT, hints.targetPrice())
                : byDelta(chain, OptionType.PUT, 0.30);
        if (put == null) put = byDelta(chain, OptionType.PUT, 0.30);
        if (put == null) return null;
        return new Built(List.of(leg(LegAction.SELL, put)), listOf(put),
                "SELL " + strikeLabel(put) + " " + chain.expiration());
    }

    private static Built protectivePut(OptionChain chain, BigDecimal spot, BuildHints hints) {
        OptionQuote put = hints.targetPrice() != null
                ? strikeAtOrBelow(chain, OptionType.PUT, hints.targetPrice())
                : byDelta(chain, OptionType.PUT, 0.30);
        if (put == null) put = byDelta(chain, OptionType.PUT, 0.30);
        if (put == null) return null;
        if (hints.sharesHeld()) {
            return new Built(List.of(leg(LegAction.BUY, put)), listOf(put),
                    "BUY " + strikeLabel(put) + " " + chain.expiration() + " protecting held shares");
        }
        List<Leg> legs = List.of(Leg.stock(LegAction.BUY, 1, spot), leg(LegAction.BUY, put));
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(null);
        quotes.add(put);
        return new Built(legs, quotes, "BUY 100 shares / BUY " + strikeLabel(put) + " " + chain.expiration());
    }

    private static Built collar(OptionChain chain, BigDecimal spot, BuildHints hints) {
        OptionQuote put = byDelta(chain, OptionType.PUT, 0.25);
        OptionQuote call = hints.targetPrice() != null
                ? strikeAtOrAbove(chain, OptionType.CALL, hints.targetPrice())
                : byDelta(chain, OptionType.CALL, 0.25);
        if (call == null) call = byDelta(chain, OptionType.CALL, 0.25);
        if (put == null || call == null) return null;
        if (put.strike().compareTo(call.strike()) >= 0) return null; // degenerate collar
        if (hints.sharesHeld()) {
            List<Leg> legs = List.of(leg(LegAction.BUY, put), leg(LegAction.SELL, call));
            List<OptionQuote> quotes = new ArrayList<>();
            quotes.add(put);
            quotes.add(call);
            return new Built(legs, quotes,
                    "BUY " + strikeLabel(put) + " / SELL " + strikeLabel(call) + " " + chain.expiration()
                            + " around held shares");
        }
        List<Leg> legs = List.of(Leg.stock(LegAction.BUY, 1, spot), leg(LegAction.BUY, put), leg(LegAction.SELL, call));
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(null);
        quotes.add(put);
        quotes.add(call);
        return new Built(legs, quotes,
                "BUY 100 shares / BUY " + strikeLabel(put) + " / SELL " + strikeLabel(call) + " " + chain.expiration());
    }

    /** Lowest marked strike at or above the target price; null when the chain tops out below it. */
    public static OptionQuote strikeAtOrAbove(OptionChain chain, OptionType type, BigDecimal target) {
        return chain.strikes().stream()
                .filter(k -> k.compareTo(target) >= 0)
                .sorted(Comparator.naturalOrder())
                .map(k -> at(chain, type, k))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    /** Highest marked strike at or below the target price; null when the chain bottoms out above it. */
    public static OptionQuote strikeAtOrBelow(OptionChain chain, OptionType type, BigDecimal target) {
        return chain.strikes().stream()
                .filter(k -> k.compareTo(target) <= 0)
                .sorted(Comparator.reverseOrder())
                .map(k -> at(chain, type, k))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    private static List<OptionQuote> listOf(OptionQuote q) {
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(q);
        return quotes;
    }

    private static Leg leg(LegAction action, OptionQuote q) {
        return Leg.option(action, q.type(), q.strike(), q.expiration(), 1, mid(q));
    }

    private static BigDecimal mid(OptionQuote q) {
        return Objects.requireNonNull(q.mid(), "no mark");
    }

    public static OptionQuote byDelta(OptionChain chain, OptionType type, double targetAbsDelta) {
        List<OptionQuote> side = type == OptionType.CALL ? chain.calls() : chain.puts();
        return side.stream()
                .filter(q -> q.hasMark() && q.delta() != null)
                .min(Comparator.comparingDouble(q -> Math.abs(Math.abs(q.delta()) - targetAbsDelta)))
                .orElse(null);
    }

    public static OptionQuote at(OptionChain chain, OptionType type, BigDecimal strike) {
        return chain.find(type, strike).filter(OptionQuote::hasMark).orElse(null);
    }

    public static OptionQuote stepAway(OptionChain chain, OptionType type, BigDecimal fromStrike, int steps) {
        List<BigDecimal> strikes = chain.strikes();
        int idx = -1;
        for (int i = 0; i < strikes.size(); i++) if (strikes.get(i).compareTo(fromStrike) == 0) { idx = i; break; }
        if (idx < 0 || idx + steps < 0 || idx + steps >= strikes.size()) return null;
        return at(chain, type, strikes.get(idx + steps));
    }

    public static BigDecimal nearestStrike(OptionChain chain, BigDecimal spot) {
        return chain.strikes().stream()
                .min(Comparator.comparingDouble(k -> Math.abs(k.doubleValue() - spot.doubleValue())))
                .orElseThrow();
    }

    private static String strikeLabel(OptionQuote q) {
        return q.strike().stripTrailingZeros().toPlainString() + (q.type() == OptionType.CALL ? "C" : "P");
    }
}
