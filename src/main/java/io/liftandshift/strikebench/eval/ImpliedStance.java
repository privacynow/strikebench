package io.liftandshift.strikebench.eval;

/** Plain-language classification derived from the unit-pinned stance vector, never a catalog label. */
public record ImpliedStance(
        Direction direction,
        Shape convexity,
        Shape volatility,
        Carry carry,
        Tail primaryTail,
        String label,
        String summary
) {
    public enum Direction { BULLISH, BEARISH, NEUTRAL }
    public enum Shape { LONG, SHORT, FLAT }
    public enum Carry { POSITIVE, NEGATIVE, FLAT }
    public enum Tail { DOWNSIDE, UPSIDE, BOTH, LIMITED, UNKNOWN }
}
