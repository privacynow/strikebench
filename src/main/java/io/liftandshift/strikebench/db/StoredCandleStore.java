package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.CandleCoverage;
import io.liftandshift.strikebench.market.ports.CandleStore;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads persisted daily candles from {@code underlying_bar} for the ACTIVE analysis dataset. With
 * the default 'observed' dataset this serves only attributable observed backfills/snapshots (one row
 * per date; Demo rows are physically quarantined and also excluded here). When the user selects a synthetic dataset in
 * the Data Center, its bars serve instead — labeled MODELED with source 'synthetic' so scenario mode
 * can never masquerade as market data. Coverage accompanies the coherent source so the caller can
 * enrich an incomplete observed range first, then retain the real partial history if no provider is available.
 */
public final class StoredCandleStore implements CandleStore {

    private final Db db;

    public StoredCandleStore(Db db) { this.db = db; }

    @Override
    public int persistObserved(String symbol, CandleSeries series) {
        return ObservedCandleWriter.write(db, symbol, series).written();
    }

    @Override
    public Optional<Read> candles(String symbol, LocalDate from, LocalDate to, String datasetId) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (sym.isEmpty() || from == null || to == null || from.isAfter(to)) return Optional.empty();
        String dataset = datasetId == null || datasetId.isBlank() ? DatasetService.OBSERVED : datasetId;
        boolean synthetic = !DatasetService.OBSERVED.equals(dataset);
        // Observed means observed: legacy/demo rows in the canonical dataset are not eligible
        // even as a weakest-link fallback. Scenario datasets intentionally contain modeled rows.
        String provenanceClause = synthetic ? " " : " AND observed=1 ";
        List<Row> rows = db.query(
                "SELECT d::text d, open, high, low, close, volume, source, observed,adjusted,bar_kind,quality_rank "
              + "FROM underlying_bar WHERE symbol=? AND dataset_id=? AND d BETWEEN ? AND ? "
              + provenanceClause
              + "ORDER BY source,d",
                r -> new Row(LocalDate.parse(r.str("d")),
                        r.bd("open"), r.bd("high"), r.bd("low"), r.bd("close"),
                        r.lng("volume"), r.str("source"), r.lng("observed") == 1,
                        r.lng("adjusted") == 1, r.str("bar_kind"), r.intv("quality_rank")),
                sym, dataset, from, to);
        if (rows.size() < 2) return Optional.empty(); // not enough to be useful; fall through to providers
        java.util.Map<String, List<Row>> bySource = rows.stream().collect(java.util.stream.Collectors.groupingBy(
                r -> r.source == null ? "unknown" : r.source, java.util.LinkedHashMap::new,
                java.util.stream.Collectors.toList()));
        List<Candidate> candidates = new java.util.ArrayList<>();
        bySource.forEach((source, sourceRows) -> {
            List<Candle> candles = sourceRows.stream().filter(r -> r.close != null).map(r -> new Candle(r.d,
                    r.open == null ? r.close : r.open, r.high == null ? r.close : r.high,
                    r.low == null ? r.close : r.low, r.close, r.volume, r.adjusted)).toList();
            boolean coherentAdjustment = sourceRows.stream().map(r -> r.adjusted).distinct().count() <= 1;
            boolean allObserved = sourceRows.stream().allMatch(r -> r.observed);
            int quality = sourceRows.stream().mapToInt(r -> r.qualityRank).max().orElse(0);
            if (candles.size() >= 2 && coherentAdjustment && (synthetic || allObserved)) {
                candidates.add(new Candidate(source, sourceRows, candles, quality,
                        CandleCoverage.assess(candles, from, to)));
            }
        });
        if (candidates.isEmpty()) return Optional.empty();
        // Never stitch sources or adjustment bases into one curve. Complete sources win. Among
        // complete sources preserve the declared quality preference; among partial sources prefer
        // the most useful coverage before quality. MarketDataService still asks providers to fill
        // a partial observed range before it uses that partial series as a fallback.
        candidates.sort((a, b) -> {
            if (a.coverage.complete() != b.coverage.complete()) return a.coverage.complete() ? -1 : 1;
            if (!a.coverage.complete()) {
                int byCoverage = Integer.compare(b.coverage.availableSessions(), a.coverage.availableSessions());
                if (byCoverage != 0) return byCoverage;
                int byRecency = b.coverage.availableTo().compareTo(a.coverage.availableTo());
                if (byRecency != 0) return byRecency;
            }
            int byQuality = Integer.compare(b.quality, a.quality);
            if (byQuality != 0) return byQuality;
            int byDensity = Integer.compare(b.candles.size(), a.candles.size());
            return byDensity != 0 ? byDensity : a.source.compareTo(b.source);
        });
        Candidate chosen = candidates.getFirst();
        Freshness freshness = synthetic ? Freshness.MODELED : Freshness.EOD;
        String source = synthetic ? "synthetic" : "stored:" + chosen.source;
        return Optional.of(new Read(new CandleSeries(chosen.candles, source, freshness, basis(chosen.rows)),
                chosen.coverage));
    }

    private record Row(LocalDate d, java.math.BigDecimal open, java.math.BigDecimal high,
                       java.math.BigDecimal low, java.math.BigDecimal close, long volume,
                       String source, boolean observed, boolean adjusted, String barKind, int qualityRank) {}
    private record Candidate(String source, List<Row> rows, List<Candle> candles, int quality,
                             CandleCoverage coverage) {}

    private static String basis(List<Row> rows) {
        java.util.Set<String> kinds = rows.stream().map(r -> r.barKind == null ? "OHLCV" : r.barKind).collect(java.util.stream.Collectors.toSet());
        return kinds.size() == 1 ? kinds.iterator().next() : "MIXED";
    }
}
