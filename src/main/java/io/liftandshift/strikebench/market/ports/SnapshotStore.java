package io.liftandshift.strikebench.market.ports;

import io.liftandshift.strikebench.market.MarketDataEngine.MarketSnapshot;

import java.util.List;

/**
 * Persistence port for the engine's last-known quotes — implemented by a DB-backed store, injected so
 * the engine boots stale-first. Kept in {@code market.ports} so the engine depends on an interface,
 * not on the db layer (which depends on market).
 */
public interface SnapshotStore {

    void save(MarketSnapshot snapshot);

    List<MarketSnapshot> loadAll();
}
