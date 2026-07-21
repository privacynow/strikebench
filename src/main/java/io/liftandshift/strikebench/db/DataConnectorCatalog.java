package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Product contract for data sources: capability, setup, rights, and request budget are explicit.
 * It never claims a provider plan grants rights StrikeBench cannot inspect.
 */
public final class DataConnectorCatalog {

    public record Connector(String key, String name, String covers, boolean configured,
                            boolean eligible, boolean automated, String access, String rights, String cadence,
                            String history, String adjustment, int dailyLimit, int usedToday,
                            int remainingToday, boolean recommended, String setup, String note) {}

    private final AppConfig cfg;
    private final ProviderRequestBudget budgets;

    public DataConnectorCatalog(AppConfig cfg, ProviderRequestBudget budgets) {
        this.cfg = cfg;
        this.budgets = budgets;
    }

    public List<Connector> all() {
        List<Connector> out = new ArrayList<>();
        boolean polygon = !cfg.polygonApiKey().isBlank();
        boolean alpha = !cfg.alphaVantageApiKey().isBlank();
        boolean yahooOn = cfg.yahooEnabled();
        boolean yahooPermitted = yahooOn && cfg.yahooAutomationPermissionConfirmed();
        boolean stooq = cfg.stooqEnabled();
        out.add(connector("polygon", "Massive / Polygon", "Daily prices + plan-dependent option history",
                polygon, polygon, true, "Official keyed API", "Your subscribed plan governs personal use, storage, and redistribution.",
                "End-of-day", "Range requests; depth depends on your plan", "Adjusted OHLCV",
                cfg.polygonDailyRequestLimit(), true,
                "Set POLYGON_API_KEY and confirm your plan permits the intended local or hosted use.",
                "StrikeBench does not infer entitlements from possession of a key."));
        out.add(connector("alphavantage", "Alpha Vantage", "Daily equity and ETF prices",
                alpha, alpha, true, "Official keyed API", "Provider terms and your plan govern storage and use.",
                "End-of-day", cfg.alphaVantageFullHistoryEnabled() ? "Full daily history enabled" : "Latest ~100 daily rows on compact access",
                "Adjusted OHLCV", cfg.alphaVantageDailyRequestLimit(), !polygon,
                "Set ALPHAVANTAGE_API_KEY. Enable ALPHAVANTAGE_FULL_HISTORY_ENABLED only with an entitled plan.",
                "The default local allowance is 25 requests/day and survives restarts."));
        out.add(connector("yahoo", "Yahoo Finance automation", "Daily equity, ETF, and index prices",
                yahooOn, yahooPermitted, true, "Unofficial automated endpoint",
                "Enabled under the product owner's standing authorization; source terms still govern storage and use.",
                "End-of-day", "Requested date range; saved locally and incrementally enriched", "Raw OHLCV",
                cfg.yahooDailyRequestLimit(), !polygon && !alpha,
                yahooOn && !cfg.yahooAutomationPermissionConfirmed()
                        ? "Automation is revoked by YAHOO_AUTOMATION_PERMISSION_CONFIRMED=false."
                        : yahooOn
                            ? "Enabled. Set YAHOO_ENABLED=false to stop requests; stored rows retain their Yahoo provenance."
                            : "Disabled by YAHOO_ENABLED=false.",
                "Requests are serialized, spaced, daily-budgeted, and cooled down after rate limiting."));
        out.add(connector("user_csv", "Your price-history CSV", "Daily OHLCV exported from a source you may use",
                true, true, false, "User-owned file import",
                "You control the file and remain responsible for its source terms; StrikeBench does not redistribute it.",
                "Manual, additive", "Any range in the file", "Raw or adjusted, selected at import", 0, !polygon && !alpha,
                "Export CSV from your broker or data source, then upload it here.",
                "Rows are validated and invalid records are quarantined instead of entering observed analysis."));
        out.add(connector("stooq", "Stooq", "Daily equity prices",
                stooq, stooq, true, "Keyless public endpoint", "Source terms apply.", "End-of-day",
                "Requested range when the endpoint responds", "Raw OHLCV", 0, false,
                "Opt in with STOOQ_ENABLED only if the endpoint works from your network.",
                "Disabled by default because automated clients commonly receive an anti-bot page."));
        return List.copyOf(out);
    }

    public Connector requireEligible(String source) {
        String key = normalize(source);
        if ("auto".equals(key)) key = recommendedSource();
        String wanted = key;
        return all().stream().filter(c -> c.key().equals(wanted)).findFirst()
                .filter(Connector::eligible)
                .orElseThrow(() -> new IllegalStateException("Data source '" + wanted
                        + "' is not eligible. Open Data → Sources & jobs to review setup and usage terms."));
    }

    public Connector requireAutomated(String source) {
        Connector connector = requireEligible(source);
        if (!connector.automated()) {
            throw new IllegalStateException(connector.name() + " is a manual import, not an automated sync source.");
        }
        return connector;
    }

    public String recommendedSource() {
        return all().stream().filter(Connector::automated).filter(Connector::recommended).filter(Connector::eligible)
                .map(Connector::key).findFirst()
                .orElseGet(() -> all().stream().filter(Connector::automated).filter(Connector::eligible)
                        .map(Connector::key).findFirst().orElse("none"));
    }

    private Connector connector(String key, String name, String covers, boolean configured,
                                boolean eligible, boolean automated, String access, String rights, String cadence,
                                String history, String adjustment, int limit, boolean recommended,
                                String setup, String note) {
        ProviderRequestBudget.Usage usage = budgets.usage(key, limit);
        return new Connector(key, name, covers, configured, eligible, automated, access, rights, cadence,
                history, adjustment, limit, usage.used(), usage.remaining(), recommended, setup, note);
    }

    private static String normalize(String source) {
        return source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
    }
}
