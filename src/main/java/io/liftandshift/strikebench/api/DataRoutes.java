package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;

/** Canonical HTTP surface for Data Center operations and analysis datasets. */
public final class DataRoutes {
    public record Handlers(
            Handler overview,
            Handler coverage,
            Handler sources,
            Handler syncStatus,
            Handler planSync,
            Handler updateSyncSchedule,
            Handler importUnderlying,
            Handler importEvent,
            Handler listJobs,
            Handler getJob,
            Handler startJob,
            Handler cancelJob,
            Handler retryJob,
            Handler reset,
            Handler listDatasets,
            Handler activateDataset,
            Handler deleteDataset,
            Handler generateDataset
    ) {}

    private DataRoutes() {}

    public static void register(JavalinConfig config, Handlers h) {
        config.routes.get("/api/data/overview", h.overview());
        config.routes.get("/api/data/coverage", h.coverage());
        config.routes.get("/api/data/sources", h.sources());
        config.routes.get("/api/data/sync", h.syncStatus());
        config.routes.post("/api/data/sync/plan", h.planSync());
        config.routes.put("/api/data/sync/schedule", h.updateSyncSchedule());
        config.routes.post("/api/data/import/underlying", h.importUnderlying());
        config.routes.post("/api/data/import/event", h.importEvent());
        config.routes.get("/api/data/jobs", h.listJobs());
        config.routes.get("/api/data/jobs/{id}", h.getJob());
        config.routes.post("/api/data/jobs", h.startJob());
        config.routes.post("/api/data/jobs/{id}/cancel", h.cancelJob());
        config.routes.post("/api/data/jobs/{id}/retry", h.retryJob());
        config.routes.post("/api/data/reset", h.reset());
        config.routes.get("/api/datasets", h.listDatasets());
        config.routes.put("/api/datasets/active", h.activateDataset());
        config.routes.delete("/api/datasets/{id}", h.deleteDataset());
        config.routes.post("/api/datasets/generate", h.generateDataset());
    }
}
