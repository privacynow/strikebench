package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.market.ports.HistoricalOptionsProvider;
import io.liftandshift.strikebench.model.DataAge;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.Symbol;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
        Optional<SourceSelection> selected = selectedSource(sym, asOf, expiration, DatasetService.OBSERVED);
        if (selected.isEmpty()) return Optional.empty();
        SourceSelection source = selected.get();
        List<OptionQuote> calls = new ArrayList<>(), puts = new ArrayList<>();
        final BigDecimal[] underlying = {null};
        final boolean[] allObserved = {true}; // weakest link: ONE modeled row degrades the chain
        db.query("SELECT opt_type, strike, bid, ask, last, mark, iv, delta, gamma, theta, vega, "
              + "open_interest, volume, underlying, bid_ask_observed, source_observed_at "
              + "FROM option_bar WHERE symbol=? AND asof=? AND expiration=? AND dataset_id='observed' AND source=? "
              + "ORDER BY strike",
                r -> {
                    boolean observed = r.lng("bid_ask_observed") == 1;
                    allObserved[0] &= observed;
                    if (underlying[0] == null) underlying[0] = r.bd("underlying");
                    OptionType type = "CALL".equals(r.str("opt_type")) ? OptionType.CALL : OptionType.PUT;
                    java.time.OffsetDateTime rowObservedAt = r.odt("source_observed_at");
                    long rowEpochMs = rowObservedAt == null ? source.observedAt().toEpochMilli()
                            : rowObservedAt.toInstant().toEpochMilli();
                    OptionQuote q = new OptionQuote(sym, null, type, r.bd("strike"), expiration,
                            r.bd("bid"), r.bd("ask"), r.bd("last") == null ? r.bd("mark") : r.bd("last"),
                            r.lngOrNull("volume"), r.lngOrNull("open_interest"),
                            r.dblOrNull("iv"), r.dblOrNull("delta"), r.dblOrNull("gamma"),
                            r.dblOrNull("theta"), r.dblOrNull("vega"),
                            rowEpochMs, observed
                                    ? DataEvidence.observed("stored:" + source.source(), DataAge.EOD)
                                    : DataEvidence.modeled("stored:" + source.source()));
                    if (type == OptionType.CALL) calls.add(q); else puts.add(q);
                    return null;
                }, sym, asOf, expiration, source.source());
        if (calls.isEmpty() && puts.isEmpty()) return Optional.empty();
        return Optional.of(new OptionChain(sym, expiration, underlying[0], calls, puts,
                source.observedAt().toEpochMilli(), allObserved[0]
                        ? DataEvidence.observed("stored:" + source.source(), DataAge.EOD)
                        : DataEvidence.modeled("stored:" + source.source()))); // worst-of, never best-of
    }

    /**
     * The sole deterministic source decision for a historical option slice. Rows from different
     * vendors/captures are never merged into one chain: a fully observed, balanced, two-sided
     * source wins, then density, then stable source identity.
     */
    public Optional<SourceSelection> selectedSource(String symbol, LocalDate asOf,
                                                    LocalDate expiration, String datasetId) {
        String sym = norm(symbol);
        String dataset = datasetId == null || datasetId.isBlank()
                ? DatasetService.OBSERVED : datasetId;
        List<SourceSelection> candidates = db.query(
                "SELECT source, count(*) rows, "
              + "sum(CASE WHEN bid_ask_observed=1 THEN 1 ELSE 0 END) observed_rows, "
              + "sum(CASE WHEN bid_ask_observed=1 AND bid IS NOT NULL AND ask IS NOT NULL "
              + "AND bid<=ask THEN 1 ELSE 0 END) two_sided_rows, "
              + "count(DISTINCT strike) strikes, "
              + "sum(CASE WHEN opt_type='CALL' THEN 1 ELSE 0 END) calls, "
              + "sum(CASE WHEN opt_type='PUT' THEN 1 ELSE 0 END) puts, "
              + "count(DISTINCT underlying) underlying_variants, max(source_observed_at) observed_at "
              + "FROM option_bar WHERE symbol=? AND asof=? AND expiration=? AND dataset_id=? "
              + "GROUP BY source",
                r -> {
                    long rows = r.lng("rows");
                    long observed = r.lng("observed_rows");
                    long twoSided = r.lng("two_sided_rows");
                    long calls = r.lng("calls");
                    long puts = r.lng("puts");
                    java.time.OffsetDateTime observedAt = r.odt("observed_at");
                    Instant instant = observedAt == null
                            ? io.liftandshift.strikebench.market.MarketHours.sessionClose(asOf)
                            : observedAt.toInstant();
                    return new SourceSelection(r.str("source"), rows, observed, twoSided,
                            r.lng("strikes"), Math.min(calls, puts),
                            r.lng("underlying_variants"), instant);
                }, sym, asOf, expiration, dataset);
        return candidates.stream()
                .filter(x -> x.source() != null && !x.source().isBlank())
                .filter(x -> x.underlyingVariants() <= 1)
                .sorted(SourceSelection.PREFERRED)
                .findFirst();
    }

    public record SourceSelection(String source, long rows, long observedRows, long twoSidedRows,
                                  long distinctStrikes, long balancedSideRows,
                                  long underlyingVariants, Instant observedAt) {
        private static final Comparator<SourceSelection> PREFERRED =
                Comparator.<SourceSelection>comparingInt(x -> x.observedRows == x.rows ? 1 : 0).reversed()
                        .thenComparing(Comparator.comparingLong(SourceSelection::balancedSideRows).reversed())
                        .thenComparing(Comparator.comparingLong(SourceSelection::twoSidedRows).reversed())
                        .thenComparing(Comparator.comparingLong(SourceSelection::distinctStrikes).reversed())
                        .thenComparing(Comparator.comparingLong(SourceSelection::rows).reversed())
                        .thenComparing(SourceSelection::source);
    }

    @Override
    public List<LocalDate> historicalExpirations(String symbol, LocalDate asOf) {
        return db.query("SELECT DISTINCT expiration::text e FROM option_bar "
              + "WHERE symbol=? AND asof=? AND dataset_id='observed' ORDER BY expiration::text",
                r -> LocalDate.parse(r.str("e")), norm(symbol), asOf);
    }

    private static String norm(String s) { return Symbol.normalize(s); }
}
