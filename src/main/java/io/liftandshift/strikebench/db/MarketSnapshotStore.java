package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.market.MarketDataEngine.MarketSnapshot;
import io.liftandshift.strikebench.model.Freshness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        var evidence = io.liftandshift.strikebench.model.DataEvidence.of(s.source(), s.freshness());
        if (evidence.provenance() != io.liftandshift.strikebench.model.DataProvenance.OBSERVED
                && evidence.provenance() != io.liftandshift.strikebench.model.DataProvenance.BROKER) return;
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
        db.query("SELECT symbol, description, last, bid, ask, prev_close, optionable, source, freshness, as_of "
                        + "FROM market_snapshot WHERE coalesce(lower(source),'') NOT LIKE '%fixture%' "
                        + "AND coalesce(freshness,'') NOT IN ('FIXTURE','SIMULATED','MODELED')",
                r -> {
                    out.add(new MarketSnapshot(r.str("symbol"), r.str("description"), r.bd("last"), r.bd("bid"),
                            r.bd("ask"), r.bd("prev_close"), r.lng("optionable") == 1,
                            Freshness.STALE, r.str("source"),
                            r.lng("as_of"), r.lng("as_of"), false, null));
                    return null;
                });
        return out;
    }

    /** Read one last-known observed quote for a user-facing request whose provider refresh failed. */
    @Override public Optional<MarketSnapshot> load(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        return db.query("SELECT symbol, description, last, bid, ask, prev_close, optionable, source, freshness, as_of "
                        + "FROM market_snapshot WHERE symbol=? "
                        + "AND coalesce(lower(source),'') NOT LIKE '%fixture%' "
                        + "AND coalesce(freshness,'') NOT IN ('FIXTURE','SIMULATED','MODELED')",
                r -> new MarketSnapshot(r.str("symbol"), r.str("description"), r.bd("last"), r.bd("bid"),
                        r.bd("ask"), r.bd("prev_close"), r.lng("optionable") == 1,
                        Freshness.STALE, r.str("source"), r.lng("as_of"), r.lng("as_of"), false, null),
                symbol.trim().toUpperCase(java.util.Locale.ROOT)).stream().findFirst();
    }
}
