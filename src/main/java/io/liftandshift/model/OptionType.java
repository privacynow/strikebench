package io.liftandshift.model;

public enum OptionType {
    CALL, PUT;

    public OptionType other() { return this == CALL ? PUT : CALL; }
}
