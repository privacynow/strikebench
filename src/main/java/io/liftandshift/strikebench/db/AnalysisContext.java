package io.liftandshift.strikebench.db;

/**
 * The EXPLICIT, immutable per-request analysis context: who is asking and which dataset their
 * analysis world uses. Constructed once at the API boundary from the caller's identity and their
 * active-dataset selection, then passed as a parameter into every engine that reads price history
 * (research, evaluation, backtests, simulations) — including work fanned out onto virtual threads,
 * where a ThreadLocal would silently fail to propagate.
 *
 * Every read must carry a context. Paper-money operations (settlement, marks, ledgers) and
 * background machinery (engine warm, snapshots, backfills) pass {@link #OBSERVED} deliberately;
 * an omitted context is never interpreted as permission to read observed data.
 */
public record AnalysisContext(String userId, String datasetId) {

    /** The shared real-data world — background work and paper-money paths always use this. */
    public static final AnalysisContext OBSERVED = new AnalysisContext(null, DatasetService.OBSERVED);

    public AnalysisContext {
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("analysis dataset id is required");
        }
        datasetId = datasetId.trim();
        userId = userId == null || userId.isBlank() ? null : userId.trim();
    }

    public boolean synthetic() { return !DatasetService.OBSERVED.equals(datasetId); }
}
