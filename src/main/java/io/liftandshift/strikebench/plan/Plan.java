package io.liftandshift.strikebench.plan;

/** Durable journey state. Pricing and evaluation outputs remain owned by their existing engines. */
public final class Plan {
    private Plan() {}

    public enum MarketKind { OBSERVED, DEMO, SIMULATED }
    public enum Status { DRAFT, ACTIVE, DECIDED_CASH, POSITION_OPEN, CLOSED, ABANDONED, ARCHIVED }
    public enum Stage { UNDERSTAND, EVIDENCE, STRATEGY, OUTCOMES, DECIDE, MANAGE_REVIEW }

    public record ContextRevision(
            String id,
            int rev,
            String thesis,
            Integer horizonDays,
            Long targetCents,
            String riskMode,
            Long holdingsShares,
            Long costBasisCents,
            Long priceAssumptionCents,
            String inputHash,
            String engineVersion,
            String createdAt
    ) {}

    public record View(
            String id,
            String originPlanId,
            String symbol,
            String intent,
            MarketKind marketKind,
            String worldId,
            String accountId,
            String title,
            Status status,
            Stage activeStage,
            long version,
            boolean open,
            boolean assumptionsEditable,
            ContextRevision context,
            String createdAt,
            String updatedAt
    ) {}

    /** The browser supplies only working-plan facts. Market/account ownership is server-derived. */
    public record CreateRequest(
            String clientRequestId,
            String symbol,
            String intent,
            String originPlanId,
            String title,
            String thesis,
            Integer horizonDays,
            Long targetCents,
            String riskMode,
            Long holdingsShares,
            Long costBasisCents,
            Long priceAssumptionCents
    ) {}

    /** Mutable context creates a new immutable revision; omitted values retain the prior value. */
    public record ContextUpdateRequest(
            Long expectedVersion,
            String thesis,
            Integer horizonDays,
            Long targetCents,
            String riskMode,
            Long holdingsShares,
            Long costBasisCents,
            Long priceAssumptionCents,
            java.util.Set<String> clear
    ) {}

    /** Intent is editable until a decision freezes the Plan's historical meaning. */
    public record IntentRequest(Long expectedVersion, String intent) {}
    public record StageRequest(Long expectedVersion, String stage) {}
    public record OpenRequest(Long expectedVersion, Boolean open) {}
    public record ArchiveRequest(Long expectedVersion) {}
}
