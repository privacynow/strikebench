package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.market.MarketDataEngine.MarketSnapshot;
import io.liftandshift.strikebench.model.Freshness;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the engine's last-known quote per symbol so a restart boots stale-first: the tape and
 * quote tiles paint immediately from local data (loaded as STALE, honestly labeled), then refresh
 * slowly. Nothing here ever downloads — it's a local mirror written after each successful refresh.
 */
public final class MarketSnapshotStore implements io.liftandshift.strikebench.market.ports.SnapshotStore {

    private final Db db;

    public MarketSnapshotStore(Db db) { this.db = db; }

    /** Upsert one symbol's last-known quote (best-effort; called after a successful refresh). */
    @Override public void save(MarketSnapshot s) {
        if (s == null || s.symbol() == null || s.last() == null) return;
        db.exec("INSERT INTO market_snapshot (symbol, description, last, bid, ask, prev_close, optionable, "
              + "source, freshness, as_of, captured_at) VALUES (?,?,?,?,?,?,?,?,?,?, now()) "
              + "ON CONFLICT (symbol) DO UPDATE SET description=excluded.description, last=excluded.last, "
              + "bid=excluded.bid, ask=excluded.ask, prev_close=excluded.prev_close, optionable=excluded.optionable, "
              + "source=excluded.source, freshness=excluded.freshness, as_of=excluded.as_of, captured_at=now()",
                s.symbol(), s.description(), s.last(), s.bid(), s.ask(), s.prevClose(), s.optionable(),
                s.source(), s.freshness() == null ? null : s.freshness().name(), s.asOfEpochMs());
    }

    /** Load every persisted last-known quote as a STALE snapshot to seed the engine on boot. */
    @Override public List<MarketSnapshot> loadAll() {
        List<MarketSnapshot> out = new ArrayList<>();
        db.query("SELECT symbol, description, last, bid, ask, prev_close, optionable, source, freshness, as_of FROM market_snapshot",
                r -> {
                    // Provenance survives the round-trip: a persisted FIXTURE quote reloads as
                    // FIXTURE — switching a local DB from demo to live must never show fake
                    // prices dressed up as stale MARKET data. Everything real reloads as STALE.
                    boolean demo = "FIXTURE".equals(r.str("freshness")) || "fixture".equals(r.str("source"));
                    out.add(new MarketSnapshot(r.str("symbol"), r.str("description"), r.bd("last"), r.bd("bid"),
                            r.bd("ask"), r.bd("prev_close"), r.lng("optionable") == 1,
                            demo ? Freshness.FIXTURE : Freshness.STALE, r.str("source"),
                            r.lng("as_of"), r.lng("as_of"), false, null));
                    return null;
                });
        return out;
    }
}
