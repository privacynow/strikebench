package io.liftandshift.strikebench.db;

/**
 * REQUEST-SCOPED dataset selection. The active analysis dataset is a per-user preference; a
 * before-handler resolves the caller's choice into this ThreadLocal for the duration of the
 * request, and the candle read path ({@link StoredCandleStore}) consults it. Anything running
 * OUTSIDE a request (engine warm, snapshot jobs, the scout's background scans) sees no context
 * and therefore reads OBSERVED data — scenario mode can never leak into shared machinery.
 */
public final class DatasetContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private DatasetContext() {}

    /** The requesting user's active dataset id, or null (= observed) outside a request. */
    public static String current() { return CURRENT.get(); }

    public static void set(String datasetId) {
        if (datasetId == null || datasetId.isBlank() || DatasetService.OBSERVED.equals(datasetId)) CURRENT.remove();
        else CURRENT.set(datasetId);
    }

    public static void clear() { CURRENT.remove(); }
}
