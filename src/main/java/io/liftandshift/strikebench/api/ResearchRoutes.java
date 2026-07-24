package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;

/** Canonical HTTP surface for research, strategy discovery, and evaluation. */
public final class ResearchRoutes {
    public record Handlers(
            Handler questions,
            Handler runEventStudy,
            Handler createNote,
            Handler listNotes,
            Handler getNote,
            Handler updateNote,
            Handler deleteNote,
            Handler symbolResearch,
            Handler expirations,
            Handler chain,
            Handler expectedMove,
            Handler history,
            Handler news,
            Handler lookup,
            Handler strategies,
            Handler identifyStrategy,
            Handler exposure,
            Handler evaluations,
            Handler calibration,
            Handler resolveCalibration
    ) {}

    private ResearchRoutes() {}

    public static void register(JavalinConfig config, Handlers h) {
        config.routes.get("/api/research/questions", h.questions());
        config.routes.post("/api/research/event-studies", h.runEventStudy());
        config.routes.post("/api/research/notes", h.createNote());
        config.routes.get("/api/research/notes", h.listNotes());
        config.routes.get("/api/research/notes/{id}", h.getNote());
        config.routes.put("/api/research/notes/{id}", h.updateNote());
        config.routes.delete("/api/research/notes/{id}", h.deleteNote());
        config.routes.get("/api/research/{symbol}", h.symbolResearch());
        config.routes.get("/api/research/{symbol}/expirations", h.expirations());
        config.routes.get("/api/research/{symbol}/chain", h.chain());
        config.routes.get("/api/research/{symbol}/expected-move", h.expectedMove());
        config.routes.get("/api/research/{symbol}/history", h.history());
        config.routes.get("/api/research/{symbol}/news", h.news());
        config.routes.get("/api/lookup", h.lookup());
        config.routes.get("/api/strategies", h.strategies());
        config.routes.post("/api/strategies/identify", h.identifyStrategy());
        config.routes.post("/api/builder/exposure", h.exposure());
        config.routes.get("/api/evaluations", h.evaluations());
        config.routes.get("/api/calibration", h.calibration());
        config.routes.post("/api/calibration/resolve", h.resolveCalibration());
    }
}
