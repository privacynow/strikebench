package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.market.sim.SimulationSessions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One stable universe response shape for observed, demo, and simulated markets. */
final class MarketUniverseView {
    private final AppConfig cfg;
    private final MarketDataService market;
    private final UniverseService universe;
    private final SimulationSessions sessions;

    MarketUniverseView(AppConfig cfg, MarketDataService market, UniverseService universe,
                       SimulationSessions sessions) {
        this.cfg = cfg;
        this.market = market;
        this.universe = universe;
        this.sessions = sessions;
    }

    /** THE resolver for "the symbols in play for this world": the world's own symbols when a
     *  simulated/demo world is active (worldParam-normalized to non-null), else the active observed
     *  universe. Previously re-spelled as {@code world != null ? worldSymbols.orElse(empty) : active}. */
    static List<String> symbolsForWorld(MarketDataService market, UniverseService universe, String world) {
        return world != null
                ? market.worldSymbols(world).map(List::copyOf).orElse(List.of())
                : universe.active().symbols();
    }

    Object describe(String world, String owner) {
        if (world != null) {
            List<String> symbols = market.worldSymbols(world)
                    .map(List::copyOf).orElse(List.of());
            boolean demo = "demo".equals(world);
            String name = demo ? "Built-in demo market" : sessions.getOrRestore(world, owner)
                    .map(session -> session.config().name() == null
                            ? "Simulated session" : session.config().name())
                    .orElse("Simulated session");
            String qualifier = demo ? "fabricated demo data" : "simulated";
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("active", Map.of("source", "world", "sectorKey", "world",
                    "label", name + " (" + qualifier + ")", "symbols", symbols));
            result.put("scout", Map.of("source", "SIMULATED_WORLD",
                    "label", "Current generated market", "symbols", symbols));
            result.put("sectors", List.of(Map.of("key", "world",
                    "label", name + " (" + qualifier + ")", "symbols", symbols)));
            result.put("world", world);
            result.put("lane", demo ? "DEMO" : "SIMULATED");
            return result;
        }
        Map<String, Object> observed = new LinkedHashMap<>(universe.describe());
        observed.put("world", "observed");
        observed.put("lane", cfg.fixturesOnly() ? "DEMO" : "OBSERVED");
        return observed;
    }
}
