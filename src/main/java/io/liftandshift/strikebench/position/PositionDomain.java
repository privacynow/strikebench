package io.liftandshift.strikebench.position;

/** Shared position vocabulary. These axes are independent by design. */
public final class PositionDomain {
    private PositionDomain() {}

    public enum AnalysisArtifactState { DRAFT, FROZEN, RETIRED }
    public enum ExecutionLane { NONE, PRACTICE, REAL }
    public enum PositionState {
        PENDING, OPEN, PARTIALLY_CLOSED, ASSIGNED, EXERCISED, EXPIRED, CLOSED
    }
    public enum ReceiptKind { DECISION, ADOPTION, TRANSFORMATION, RESOLUTION }
    public enum ReceiptAuthority { SYSTEM_ANALYSIS, BROKER_REPORTED, USER_ALLOCATED }
    /** Authority for one derived or reported fact. This is deliberately separate from the
     * receipt's authorship and a market price's authority: a broker may author a receipt while
     * StrikeBench still derives one particular collateral number. */
    public enum FactAuthority {
        SYSTEM_CALCULATED, OBSERVED_MARKET, BROKER_REPORTED, USER_REPORTED,
        MODEL_DERIVED, UNAVAILABLE
    }
    public enum Objective { INCOME, ACCUMULATE, HEDGE, DIRECTIONAL, CAPITAL_PRESERVATION }
    public enum Direction { BULLISH, BEARISH, NEUTRAL, NON_DIRECTIONAL }
    public enum AssignmentPreference { AVOID, ACCEPT, PREFER_BELOW_BASIS, SEEK }
    public enum PlanActionRole {
        ENTRY, ADJUST, ROLL, PARTIAL_CLOSE, CLOSE, ASSIGNMENT, EXERCISE, EXPIRATION
    }
    public enum PackageSource {
        HYPOTHETICAL_DRAFT, PRACTICE_TRADE, PRACTICE_HOLDING,
        TRACKED_STRUCTURE, TRACKED_HOLDING
    }
    public enum PriceAuthority { OBSERVED, BROKER_REPORTED, USER_REPORTED, MODELED }
}
