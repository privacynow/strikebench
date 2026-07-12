package io.liftandshift.strikebench.paper;

import java.util.List;

/** An unsafe or unfundable trade. Maps to HTTP 422; the account is never mutated. */
public final class TradeRejectedException extends RuntimeException {

    private final List<String> reasons;

    public TradeRejectedException(List<String> reasons) {
        super(String.join("; ", reasons));
        this.reasons = List.copyOf(reasons);
    }

    public List<String> reasons() { return reasons; }
}
