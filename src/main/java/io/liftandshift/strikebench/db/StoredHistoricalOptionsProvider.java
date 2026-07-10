package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.market.ports.HistoricalOptionsProvider;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Serves historical option chains from {@code option_bar} — the rows written by forward snapshots
 * and licensed CSV ingest. First in the single backtester's historical chain, so owned option
 * history (the "own the past" moat) upgrades EVERY backtest, not just the portfolio engine.
 * Evidence-honest: chains whose rows carry observed bid/ask are EOD; model-sourced rows are MODELED.
 */
public final class StoredHistoricalOptionsProvider implements HistoricalOptionsProvider {

    private final Db db;

    public StoredHistoricalOptionsProvider(Db db) { this.db = db; }

    @Override public String name() { return "stored"; }

    @Override
    public Optional<OptionChain> historicalChain(String symbol, LocalDate asOf, LocalDate expiration) {
        String sym = norm(symbol);
        List<OptionQuote> calls = new ArrayList<>(), puts = new ArrayList<>();
        final BigDecimal[] underlying = {null};
        final boolean[] anyObserved = {false};
        db.query("SELECT opt_type, strike, bid, ask, last, mark, iv, delta, gamma, theta, vega, "
              + "open_interest, volume, underlying, bid_ask_observed "
              + "FROM option_bar WHERE symbol=? AND asof=? AND expiration=? AND dataset_id='observed' "
              + "ORDER BY strike",
                r -> {
                    boolean observed = r.lng("bid_ask_observed") == 1;
                    anyObserved[0] |= observed;
                    if (underlying[0] == null) underlying[0] = r.bd("underlying");
                    OptionType type = "CALL".equals(r.str("opt_type")) ? OptionType.CALL : OptionType.PUT;
                    OptionQuote q = new OptionQuote(sym, null, type, r.bd("strike"), expiration,
                            r.bd("bid"), r.bd("ask"), r.bd("last") == null ? r.bd("mark") : r.bd("last"),
                            r.lngOrNull("volume"), r.lngOrNull("open_interest"),
                            r.dblOrNull("iv"), r.dblOrNull("delta"), r.dblOrNull("gamma"),
                            r.dblOrNull("theta"), r.dblOrNull("vega"),
                            asOf.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
                            "stored", observed ? Freshness.EOD : Freshness.MODELED);
                    if (type == OptionType.CALL) calls.add(q); else puts.add(q);
                    return null;
                }, sym, asOf, expiration);
        if (calls.isEmpty() && puts.isEmpty()) return Optional.empty();
        return Optional.of(new OptionChain(sym, expiration, underlying[0], calls, puts,
                asOf.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), "stored",
                anyObserved[0] ? Freshness.EOD : Freshness.MODELED));
    }

    @Override
    public List<LocalDate> historicalExpirations(String symbol, LocalDate asOf) {
        return db.query("SELECT DISTINCT expiration::text e FROM option_bar "
              + "WHERE symbol=? AND asof=? AND dataset_id='observed' ORDER BY expiration::text",
                r -> LocalDate.parse(r.str("e")), norm(symbol), asOf);
    }

    private static String norm(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }
}
