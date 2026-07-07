package io.liftandshift.model;

public enum LegAction {
    BUY, SELL;

    public int sign() { return this == BUY ? 1 : -1; }
    public LegAction opposite() { return this == BUY ? SELL : BUY; }
}
