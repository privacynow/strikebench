package io.liftandshift.market;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
