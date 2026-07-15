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
    public enum ReceiptAuthority { SYSTEM_OBSERVED, BROKER_REPORTED, USER_ALLOCATED }
    public enum Objective { INCOME, ACCUMULATE, HEDGE, DIRECTIONAL, CAPITAL_PRESERVATION }
    public enum Direction { BULLISH, BEARISH, NEUTRAL, NON_DIRECTIONAL }
    public enum AssignmentPreference { AVOID, ACCEPT, PREFER_BELOW_BASIS, SEEK }
    public enum PlanActionRole {
        ENTRY, ADJUST, ROLL, PARTIAL_CLOSE, CLOSE, ASSIGNMENT, EXERCISE, EXPIRATION
    }
    public enum PackageSource { HYPOTHETICAL_DRAFT, PRACTICE_TRADE, TRACKED_STRUCTURE }
    public enum PriceAuthority { OBSERVED, BROKER_REPORTED, USER_REPORTED, MODELED }
}
