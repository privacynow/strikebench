package io.liftandshift.strikebench.market;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;

/**
 * Curated sector universes: liquid, optionable US names plus the sector ETF, sized so a scout
 * scan stays fast. These are DISCOVERY lists, not recommendations — every symbol still goes
 * through the same screens, and unknown/optionless symbols are skipped with a reason.
 * DEMO mirrors the built-in fixture symbols so fixtures-only mode has full data.
 */
public final class Universes {

    private Universes() {}

    public record Sector(String key, String label, List<String> symbols) {}

    public static final Map<String, Sector> SECTORS = build();

    private static final List<String> ALLOCATION_SECTOR_ORDER = List.of(
            "SEMICONDUCTORS", "TECH", "HEALTHCARE", "DEFENSE", "STAPLES", "DISCRETIONARY",
            "ENERGY", "FINANCIALS", "INDUSTRIALS", "COMMUNICATIONS", "UTILITIES", "ETFS");

    /** One non-overlapping sector label for portfolio allocation; discovery lists remain overlapping. */
    public static String allocationSectorLabel(String rawSymbol) {
        String symbol = normalize(rawSymbol);
        for (String key : ALLOCATION_SECTOR_ORDER) {
            Sector sector = SECTORS.get(key);
            if (sector != null && sector.symbols().contains(symbol)) return sector.label();
        }
        return "Other / unclassified";
    }

    /** Same-sector discovery set, preserving the curated order and excluding the anchor. */
    public static List<String> peersOf(String rawSymbol) {
        String symbol = normalize(rawSymbol);
        LinkedHashSet<String> peers = new LinkedHashSet<>();
        for (Sector sector : SECTORS.values()) {
            if ("CORE".equals(sector.key()) || "ETFS".equals(sector.key()) || "DEMO".equals(sector.key())) continue;
            if (sector.symbols().contains(symbol)) peers.addAll(sector.symbols());
        }
        if (peers.isEmpty()) {
            for (Sector sector : SECTORS.values()) if (sector.symbols().contains(symbol)) peers.addAll(sector.symbols());
        }
        peers.remove(symbol);
        return List.copyOf(peers);
    }

    /** Liquid sector/macro instruments suitable for a separately-evaluated complement Plan. */
    public static List<String> complementsFor(String rawSymbol) {
        String symbol = normalize(rawSymbol);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Sector sector : SECTORS.values()) {
            if (!sector.symbols().contains(symbol)) continue;
            sector.symbols().stream().filter(Universes::looksLikeSectorEtf).forEach(out::add);
        }
        out.addAll(List.of("SPY", "QQQ", "IWM", "TLT", "GLD"));
        out.remove(symbol);
        return List.copyOf(out);
    }

    private static boolean looksLikeSectorEtf(String symbol) {
        return symbol.startsWith("XL") || List.of("SMH", "ITA").contains(symbol);
    }

    private static String normalize(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        return symbol.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static Map<String, Sector> build() {
        Map<String, Sector> m = new LinkedHashMap<>();
        put(m, "CORE", "Core (indexes + megacaps)",
                "SPY,QQQ,IWM,DIA,AAPL,MSFT,NVDA,TSLA,AMZN,GOOGL");
        put(m, "TECH", "Technology",
                "AAPL,MSFT,NVDA,GOOGL,META,CRM,ORCL,ADBE,NOW,IBM,XLK");
        put(m, "SEMICONDUCTORS", "Semiconductors",
                "NVDA,AMD,AVGO,TSM,MU,QCOM,INTC,ARM,SMH");
        put(m, "HEALTHCARE", "Healthcare",
                "UNH,LLY,JNJ,PFE,MRK,ABBV,TMO,AMGN,CVS,XLV");
        put(m, "DEFENSE", "Defense & aerospace",
                "LMT,RTX,NOC,GD,BA,LHX,HII,AVAV,ITA");
        put(m, "STAPLES", "Consumer staples",
                "PG,KO,PEP,COST,WMT,CL,MDLZ,GIS,XLP");
        put(m, "DISCRETIONARY", "Consumer discretionary",
                "AMZN,TSLA,HD,MCD,NKE,SBUX,LOW,TGT,XLY");
        put(m, "ENERGY", "Energy",
                "XOM,CVX,COP,SLB,OXY,EOG,PSX,XLE");
        put(m, "FINANCIALS", "Financials",
                "JPM,BAC,GS,MS,WFC,C,V,MA,XLF");
        put(m, "INDUSTRIALS", "Industrials",
                "CAT,DE,HON,GE,UPS,UNP,ETN,XLI");
        put(m, "COMMUNICATIONS", "Communications & media",
                "GOOGL,META,NFLX,DIS,CMCSA,T,VZ,TMUS,XLC");
        put(m, "UTILITIES", "Utilities",
                "NEE,DUK,SO,D,AEP,XLU");
        put(m, "ETFS", "Index & macro ETFs",
                "SPY,QQQ,IWM,DIA,GLD,SLV,TLT,EEM,XLE,XLF");
        put(m, "DEMO", "Demo data (built-in)",
                "AAPL,SPY,QQQ,TSLA");
        return java.util.Collections.unmodifiableMap(m);
    }

    private static void put(Map<String, Sector> m, String key, String label, String csv) {
        m.put(key, new Sector(key, label, List.of(csv.split(","))));
    }
}
