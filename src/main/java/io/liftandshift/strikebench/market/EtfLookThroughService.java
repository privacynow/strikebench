package io.liftandshift.strikebench.market;
import static io.liftandshift.strikebench.util.Numbers.round4;

import io.liftandshift.strikebench.model.DataAge;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.DataProvenance;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one ETF-composition evidence owner. Snapshots are partial issuer/index disclosures with an
 * explicit residual; the residual is never redistributed or guessed. This is a data receipt, not
 * another allocation or risk engine. BookRiskService may compose its output but remains the sole
 * owner of concentration policy.
 */
public final class EtfLookThroughService {
    public static final String SNAPSHOT_VERSION = "reviewed-etf-composition-2026-07-22-1";
    public static final int CURRENT_THROUGH_DAYS = 120;
    public static final int UNAVAILABLE_AFTER_DAYS = 365;

    public enum Status { AVAILABLE, STALE, UNAVAILABLE }
    public enum CompositionKind { FUND_HOLDINGS, INDEX_PROXY }

    public record Holding(String symbol, String name, double weightPct, String theme) {
        public Holding {
            symbol = norm(symbol);
            name = name == null || name.isBlank() ? symbol : name.trim();
            if (!(weightPct > 0 && weightPct <= 100) || !Double.isFinite(weightPct)) {
                throw new IllegalArgumentException("ETF holding weight must be >0% and <=100%");
            }
            theme = theme == null || theme.isBlank()
                    ? Universes.allocationSectorLabel(symbol) : theme.trim();
        }
    }

    public record Snapshot(String fund, CompositionKind kind, LocalDate asOf,
                           LocalDate reviewedAt, String sourceName, String sourceUrl,
                           List<Holding> disclosedHoldings, String disclosure) {
        public Snapshot {
            fund = norm(fund);
            if (kind == null || asOf == null || reviewedAt == null) {
                throw new IllegalArgumentException("ETF snapshot kind and dates are required");
            }
            if (sourceName == null || sourceName.isBlank() || sourceUrl == null || sourceUrl.isBlank()) {
                throw new IllegalArgumentException("ETF snapshot source and URL are required");
            }
            disclosedHoldings = disclosedHoldings == null ? List.of() : List.copyOf(disclosedHoldings);
            double covered = disclosedHoldings.stream().mapToDouble(Holding::weightPct).sum();
            if (covered > 100.000001) throw new IllegalArgumentException(fund + " ETF weights exceed 100%");
            disclosure = disclosure == null ? "" : disclosure.trim();
        }

        public double coveredWeightPct() {
            return round4(disclosedHoldings.stream().mapToDouble(Holding::weightPct).sum());
        }
        public double residualWeightPct() { return round4(Math.max(0, 100 - coveredWeightPct())); }
    }

    public record FundReceipt(String fund, Status status, CompositionKind kind,
                              LocalDate asOf, long ageDays, LocalDate reviewedAt,
                              String sourceName, String sourceUrl,
                              double coveredWeightPct, double residualWeightPct,
                              DataEvidence evidence, String note) {}
    public record ComponentExposure(String fund, String symbol, String name, String theme,
                                    double fundWeightPct, long notionalCents) {}
    public record ThemeExposure(String theme, long notionalCents,
                                List<String> funds, List<String> components) {
        public ThemeExposure {
            funds = funds == null ? List.of() : List.copyOf(funds);
            components = components == null ? List.of() : List.copyOf(components);
        }
    }
    public record BookReceipt(List<FundReceipt> funds,
                              List<ComponentExposure> components,
                              List<ThemeExposure> themes,
                              long etfNotionalCents, long disclosedNotionalCents,
                              long residualNotionalCents, boolean complete,
                              String version, String basis) {
        public BookReceipt {
            funds = funds == null ? List.of() : List.copyOf(funds);
            components = components == null ? List.of() : List.copyOf(components);
            themes = themes == null ? List.of() : List.copyOf(themes);
        }
    }

    private record ThemeAccumulator(long[] notional, Set<String> funds, Set<String> components) {}

    private final Clock clock;
    private final Map<String, Snapshot> snapshots;
    private final Set<String> recognizedEtfs;

    public EtfLookThroughService(Clock clock) {
        this(clock, reviewedSnapshots(), recognizedEtfs());
    }

    EtfLookThroughService(Clock clock, Map<String, Snapshot> snapshots, Set<String> recognizedEtfs) {
        this.clock = clock;
        this.snapshots = Collections.unmodifiableMap(new LinkedHashMap<>(snapshots));
        this.recognizedEtfs = Set.copyOf(recognizedEtfs);
    }

    public FundReceipt receipt(String rawFund) {
        return receipt(rawFund, LocalDate.now(clock));
    }

    /** Whether the symbol is known to be an ETF, including funds whose composition is unavailable. */
    public boolean recognizes(String rawSymbol) {
        return recognizedEtfs.contains(norm(rawSymbol));
    }

    FundReceipt receipt(String rawFund, LocalDate onDate) {
        String fund = norm(rawFund);
        Snapshot snapshot = snapshots.get(fund);
        if (snapshot == null) {
            return new FundReceipt(fund, Status.UNAVAILABLE, null, null, 0, null,
                    null, null, 0, 100, DataEvidence.missing("ETF composition unavailable"),
                    "No reviewed composition snapshot is available for " + fund
                            + "; StrikeBench does not infer its holdings from the ticker or fund name.");
        }
        long age = Math.max(0, ChronoUnit.DAYS.between(snapshot.asOf(), onDate));
        Status status = age <= CURRENT_THROUGH_DAYS ? Status.AVAILABLE
                : age <= UNAVAILABLE_AFTER_DAYS ? Status.STALE : Status.UNAVAILABLE;
        DataAge dataAge = status == Status.AVAILABLE ? DataAge.EOD
                : status == Status.STALE ? DataAge.STALE : DataAge.MISSING;
        DataEvidence evidence = status == Status.UNAVAILABLE
                ? DataEvidence.missing(snapshot.sourceName() + " snapshot is too old")
                : new DataEvidence(DataProvenance.OBSERVED, dataAge, snapshot.sourceName());
        String note = snapshot.disclosure() + " Disclosed holdings cover "
                + formatPct(snapshot.coveredWeightPct()) + "% of fund weight; the remaining "
                + formatPct(snapshot.residualWeightPct())
                + "% stays an explicit residual and is never redistributed."
                + (snapshot.kind() == CompositionKind.INDEX_PROXY
                    ? " This is the named tracked index used as a labeled proxy, not a claim of exact fund holdings."
                    : "");
        return new FundReceipt(fund, status, snapshot.kind(), snapshot.asOf(), age,
                snapshot.reviewedAt(), snapshot.sourceName(), snapshot.sourceUrl(),
                snapshot.coveredWeightPct(), snapshot.residualWeightPct(), evidence, note);
    }

    /** Expand only recognized ETFs; ordinary stock symbols pass through to BookRisk unchanged. */
    public BookReceipt expand(Map<String, Long> symbolNotionalCents) {
        return expand(symbolNotionalCents, LocalDate.now(clock));
    }

    BookReceipt expand(Map<String, Long> rawNotional, LocalDate onDate) {
        Map<String, Long> notionals = rawNotional == null ? Map.of() : rawNotional;
        List<FundReceipt> fundRows = new ArrayList<>();
        List<ComponentExposure> componentRows = new ArrayList<>();
        Map<String, ThemeAccumulator> themes = new LinkedHashMap<>();
        long total = 0, disclosed = 0, residual = 0;
        boolean complete = true;
        for (var entry : notionals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            String fund = norm(entry.getKey());
            if (!recognizedEtfs.contains(fund)) continue;
            long notional = Math.max(0, entry.getValue() == null ? 0 : entry.getValue());
            total = Math.addExact(total, notional);
            FundReceipt receipt = receipt(fund, onDate);
            fundRows.add(receipt);
            Snapshot snapshot = snapshots.get(fund);
            if (receipt.status() == Status.UNAVAILABLE || snapshot == null) {
                complete = false;
                residual = Math.addExact(residual, notional);
                accumulate(themes, "ETF composition unavailable", notional, fund, fund);
                continue;
            }
            if (receipt.status() == Status.STALE) complete = false;
            long allocated = 0;
            for (Holding holding : snapshot.disclosedHoldings()) {
                long amount = Math.round(notional * holding.weightPct() / 100.0);
                allocated = Math.addExact(allocated, amount);
                componentRows.add(new ComponentExposure(fund, holding.symbol(), holding.name(),
                        holding.theme(), holding.weightPct(), amount));
                accumulate(themes, holding.theme(), amount, fund, holding.symbol());
            }
            long remainder = Math.subtractExact(notional, allocated);
            disclosed = Math.addExact(disclosed, allocated);
            residual = Math.addExact(residual, remainder);
            if (remainder > 0) accumulate(themes, "ETF residual / undisclosed holdings",
                    remainder, fund, fund + " residual");
        }
        componentRows.sort(Comparator.comparingLong(ComponentExposure::notionalCents).reversed()
                .thenComparing(ComponentExposure::fund).thenComparing(ComponentExposure::symbol));
        List<ThemeExposure> themeRows = themes.entrySet().stream()
                .map(entry -> new ThemeExposure(entry.getKey(), entry.getValue().notional()[0],
                        List.copyOf(entry.getValue().funds()),
                        List.copyOf(entry.getValue().components())))
                .sorted(Comparator.comparingLong(ThemeExposure::notionalCents).reversed()
                        .thenComparing(ThemeExposure::theme)).toList();
        return new BookReceipt(fundRows, componentRows, themeRows, total, disclosed, residual,
                complete, SNAPSHOT_VERSION,
                "Top holdings from reviewed issuer disclosures (or a labeled tracked-index proxy). "
                        + "Every source/as-of date travels with the fund; undisclosed weight remains residual.");
    }

    private static void accumulate(Map<String, ThemeAccumulator> themes, String theme,
                                   long amount, String fund, String component) {
        ThemeAccumulator row = themes.computeIfAbsent(theme, ignored ->
                new ThemeAccumulator(new long[]{0}, new LinkedHashSet<>(), new LinkedHashSet<>()));
        row.notional()[0] = Math.addExact(row.notional()[0], amount);
        row.funds().add(fund);
        row.components().add(component);
    }

    private static Map<String, Snapshot> reviewedSnapshots() {
        LocalDate reviewed = LocalDate.parse("2026-07-22");
        Map<String, Snapshot> rows = new LinkedHashMap<>();
        rows.put("VOO", snapshot("VOO", CompositionKind.FUND_HOLDINGS, "2026-05-31", reviewed,
                "Vanguard VOO portfolio composition",
                "https://investor.vanguard.com/investment-products/etfs/profile/voo",
                "Issuer holding details as of 2026-05-31.", List.of(
                        h("NVDA", "NVIDIA", 7.89), h("AAPL", "Apple", 7.05),
                        h("MSFT", "Microsoft", 5.14), h("AMZN", "Amazon", 4.07),
                        h("GOOGL", "Alphabet Class A", 3.41), h("AVGO", "Broadcom", 3.26),
                        h("GOOG", "Alphabet Class C", 2.71), h("META", "Meta Platforms", 2.13),
                        h("TSLA", "Tesla", 1.89), h("MU", "Micron", 1.68))));
        rows.put("VTI", snapshot("VTI", CompositionKind.FUND_HOLDINGS, "2026-05-31", reviewed,
                "Vanguard VTI portfolio composition",
                "https://investor.vanguard.com/investment-products/etfs/profile/vti",
                "Issuer holding details as of 2026-05-31; only directly verified rows are included.", List.of(
                        h("NVDA", "NVIDIA", 6.70), h("AAPL", "Apple", 6.30),
                        h("MSFT", "Microsoft", 4.60), h("AMZN", "Amazon", 3.60),
                        h("GOOGL", "Alphabet Class A", 3.05), h("AVGO", "Broadcom", 2.91))));
        rows.put("VGT", snapshot("VGT", CompositionKind.FUND_HOLDINGS, "2026-05-31", reviewed,
                "Vanguard VGT portfolio composition",
                "https://investor.vanguard.com/investment-products/etfs/profile/vgt",
                "Issuer holding details as of 2026-05-31.", List.of(
                        h("NVDA", "NVIDIA", 16.78), h("AAPL", "Apple", 15.26),
                        h("MSFT", "Microsoft", 9.87), h("AVGO", "Broadcom", 4.49),
                        h("MU", "Micron", 4.19), h("AMD", "Advanced Micro Devices", 3.20),
                        h("INTC", "Intel", 1.95), h("CSCO", "Cisco", 1.85))));
        rows.put("VXUS", snapshot("VXUS", CompositionKind.FUND_HOLDINGS, "2026-05-31", reviewed,
                "Vanguard VXUS portfolio composition",
                "https://investor.vanguard.com/investment-products/etfs/profile/vxus",
                "Issuer holding details as of 2026-05-31; foreign listings retain explicit curated themes.", List.of(
                        h("2330.TW", "Taiwan Semiconductor Manufacturing", 3.95, "Semiconductors, memory & storage"),
                        h("005930.KS", "Samsung Electronics", 2.17, "Semiconductors, memory & storage"),
                        h("000660.KS", "SK hynix", 1.85, "Semiconductors, memory & storage"),
                        h("ASML", "ASML", 1.38, "Semiconductors, memory & storage"),
                        h("0700.HK", "Tencent", .74, "Communications & media"),
                        h("HSBA.L", "HSBC", .71, "Financials"),
                        h("ROG.SW", "Roche", .65, "Healthcare"),
                        h("NOVN.SW", "Novartis", .63, "Healthcare"))));
        rows.put("JEPQ", snapshot("JEPQ", CompositionKind.FUND_HOLDINGS, "2026-03-31", reviewed,
                "J.P. Morgan JEPQ fact sheet",
                "https://am.jpmorgan.com/content/dam/jpm-am-aem/americas/us/en/literature/fact-sheet/etfs/FS-JEPQ.PDF",
                "Issuer fact sheet top-ten holdings as of 2026-03-31; the fund's option overlay is not represented as stock weight.", List.of(
                        h("NVDA", "NVIDIA", 7.4), h("AAPL", "Apple", 6.4),
                        h("GOOG", "Alphabet Class C", 5.1), h("MSFT", "Microsoft", 4.9),
                        h("AMZN", "Amazon", 4.1), h("META", "Meta Platforms", 3.1),
                        h("TSLA", "Tesla", 2.6), h("WMT", "Walmart", 2.5),
                        h("AVGO", "Broadcom", 2.3), h("NFLX", "Netflix", 2.1))));
        rows.put("QQQ", snapshot("QQQ", CompositionKind.INDEX_PROXY, "2026-05-01", reviewed,
                "Nasdaq-100 constituent weights",
                "https://www.nasdaq.com/docs/2026/05/04/NDX.pdf",
                "Nasdaq's published NDX weights as of 2026-05-01; QQQ states that it tracks NDX.", List.of(
                        h("NVDA", "NVIDIA", 8.42), h("AAPL", "Apple", 7.18),
                        h("MSFT", "Microsoft", 5.37), h("AMZN", "Amazon", 5.03),
                        h("GOOGL", "Alphabet Class A", 3.92), h("GOOG", "Alphabet Class C", 3.64),
                        h("AVGO", "Broadcom", 3.49), h("TSLA", "Tesla", 3.41),
                        h("META", "Meta Platforms", 3.13), h("WMT", "Walmart", 3.10),
                        h("MU", "Micron", 2.94), h("AMD", "Advanced Micro Devices", 2.83),
                        h("INTC", "Intel", 2.40), h("COST", "Costco", 2.16),
                        h("NFLX", "Netflix", 1.87))));
        return Collections.unmodifiableMap(rows);
    }

    private static Snapshot snapshot(String fund, CompositionKind kind, String asOf,
                                     LocalDate reviewed, String source, String url,
                                     String disclosure, List<Holding> holdings) {
        return new Snapshot(fund, kind, LocalDate.parse(asOf), reviewed,
                source, url, holdings, disclosure);
    }
    private static Holding h(String symbol, String name, double weight) {
        return new Holding(symbol, name, weight, null);
    }
    private static Holding h(String symbol, String name, double weight, String theme) {
        return new Holding(symbol, name, weight, theme);
    }

    private static Set<String> recognizedEtfs() {
        return Set.of("VOO", "VTI", "VGT", "VXUS", "QQQ", "JEPQ", "SPY", "IWM", "DIA",
                "GLD", "SLV", "TLT", "EEM", "SMH", "ITA", "XLK", "XLV", "XLP", "XLY",
                "XLE", "XLF", "XLI", "XLC", "XLU", "JEPI", "QYLD", "XYLD", "RYLD");
    }
    private static String norm(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("ETF symbol is required");
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
    private static String formatPct(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
