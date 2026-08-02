package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.CandleCoverage;
import io.liftandshift.strikebench.market.ports.CandleStore;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.DataAge;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Symbol;

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
    private final MarketDataMaintenanceGate maintenance;

    public StoredCandleStore(Db db, MarketDataMaintenanceGate maintenance) {
        this.db = db;
        this.maintenance = java.util.Objects.requireNonNull(maintenance, "maintenance");
    }

    @Override
    public int persistObserved(String symbol, CandleSeries series) {
        return maintenance.write(() -> ObservedCandleWriter.write(db, symbol, series).written());
    }

    @Override
    public Optional<Read> candles(String symbol, LocalDate from, LocalDate to, String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("analysis dataset id is required");
        }
        String sym = Symbol.normalizeOptional(symbol);
        if (sym == null || from == null || to == null || from.isAfter(to)) return Optional.empty();
        String dataset = datasetId.trim();
        boolean synthetic = !DatasetService.OBSERVED.equals(dataset);
        // Observed means observed: Demo rows in the normalized dataset are not eligible
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
                    r.open, r.high, r.low, r.close, r.volume, r.adjusted)).toList();
            boolean coherentAdjustment = sourceRows.stream().map(r -> r.adjusted).distinct().count() <= 1;
            boolean allObserved = sourceRows.stream().allMatch(r -> r.observed);
            boolean fullOhlc = sourceRows.stream().filter(r -> r.close != null).allMatch(r ->
                    r.open != null && r.high != null && r.low != null
                            && !"CLOSE_ONLY".equalsIgnoreCase(r.barKind));
            int quality = sourceRows.stream().mapToInt(r -> r.qualityRank).max().orElse(0);
            if (candles.size() >= 2 && coherentAdjustment && (synthetic || allObserved)) {
                candidates.add(new Candidate(source, sourceRows, candles, quality, fullOhlc,
                        CandleCoverage.assess(candles, from, to)));
            }
        });
        if (candidates.isEmpty()) return Optional.empty();
        // Never stitch sources or adjustment bases into one curve. Complete sources win. Among
        // complete sources preserve the declared quality preference; among partial sources prefer
        // the most useful coverage before quality. MarketDataService still asks providers to fill
        // a partial observed range before it uses that partial series as a fallback.
        candidates.sort((a, b) -> {
            // A complete observed OHLC history is a stronger fact than a close-only capture. Never
            // let source rank promote the explicitly partial shape over genuine daily ranges.
            if (a.fullOhlc != b.fullOhlc) return a.fullOhlc ? -1 : 1;
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
        String source = synthetic ? "synthetic" : "stored:" + chosen.source;
        DataEvidence evidence = synthetic ? DataEvidence.modeled(source)
                : DataEvidence.observed(source, DataAge.EOD);
        return Optional.of(new Read(new CandleSeries(chosen.candles, evidence, basis(chosen.rows),
                        CandleSeries.priceBasisOf(chosen.candles)),
                chosen.coverage));
    }

    private record Row(LocalDate d, java.math.BigDecimal open, java.math.BigDecimal high,
                       java.math.BigDecimal low, java.math.BigDecimal close, long volume,
                       String source, boolean observed, boolean adjusted, String barKind, int qualityRank) {}
    private record Candidate(String source, List<Row> rows, List<Candle> candles, int quality,
                             boolean fullOhlc,
                             CandleCoverage coverage) {}

    private static String basis(List<Row> rows) {
        java.util.Set<String> kinds = rows.stream().map(r -> r.barKind == null ? "OHLCV" : r.barKind).collect(java.util.stream.Collectors.toSet());
        return kinds.size() == 1 ? kinds.iterator().next() : "MIXED";
    }
}
