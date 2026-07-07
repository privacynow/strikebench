package io.liftandshift.market.providers;

import io.liftandshift.config.AppConfig;
import io.liftandshift.market.ports.RatesProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Year;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyless risk-free rates from the U.S. Treasury daily yield-curve XML feed:
 * {@code GET {base}/resource-center/data-chart-center/interest-rates/pages/xml
 *      ?data=daily_treasury_yield_curve&field_tdr_date_value={currentYear}}
 *
 * <p>The feed is an Atom document with one {@code <entry>} per business day whose OData
 * properties carry the curve as percents, e.g. {@code <d:BC_1MONTH>5.40</d:BC_1MONTH>}.
 * We take the LAST entry in the document (most recent day) and map a requested horizon
 * to the nearest tenor: &le;45d &rarr; 1M, &le;135d &rarr; 3M, &le;270d &rarr; 6M, else 1Y.
 *
 * <p>Rates are ratios, not money, so doubles are fine here. Parsing is a couple of regexes —
 * deliberately no XML library. The parsed curve is cached for one hour. HTTP failures
 * propagate (MarketDataService catches and falls through the provider chain); a response
 * that parses but lacks the tenor yields {@link OptionalDouble#empty()}.
 */
public final class TreasuryRatesProvider implements RatesProvider {

    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Pattern ENTRY = Pattern.compile("<entry\\b[^>]*>(.*?)</entry>", Pattern.DOTALL);

    private final Http http;
    private final String base;
    private final Clock clock;

    private Curve cachedCurve;
    private long cachedAtMillis = Long.MIN_VALUE;

    public TreasuryRatesProvider(AppConfig cfg) {
        this(cfg, Clock.systemDefaultZone());
    }

    public TreasuryRatesProvider(AppConfig cfg, Clock clock) {
        this.http = new Http(cfg.httpTimeoutMs());
        this.base = Http.normalizeBase(cfg.treasuryBaseUrl());
        this.clock = clock;
    }

    @Override
    public String name() {
        return "treasury";
    }

    @Override
    public OptionalDouble riskFreeRate(int days) {
        Curve curve = curve();
        Double percent;
        if (days <= 45) percent = curve.oneMonth();
        else if (days <= 135) percent = curve.threeMonth();
        else if (days <= 270) percent = curve.sixMonth();
        else percent = curve.oneYear();
        return percent == null ? OptionalDouble.empty() : OptionalDouble.of(percent / 100.0);
    }

    private synchronized Curve curve() {
        long now = clock.millis();
        if (cachedCurve != null && now - cachedAtMillis < CACHE_TTL.toMillis()) {
            return cachedCurve;
        }
        String url = base + "/resource-center/data-chart-center/interest-rates/pages/xml"
                + "?data=daily_treasury_yield_curve"
                + "&field_tdr_date_value=" + Year.now(clock).getValue();
        String xml = http.get(url);
        cachedCurve = parseLastEntry(xml);
        cachedAtMillis = now;
        return cachedCurve;
    }

    /** Last {@code <entry>} in document order is the most recent trading day. */
    private static Curve parseLastEntry(String xml) {
        String last = null;
        Matcher m = ENTRY.matcher(xml);
        while (m.find()) last = m.group(1);
        if (last == null) return new Curve(null, null, null, null);
        return new Curve(
                tenor(last, "BC_1MONTH"),
                tenor(last, "BC_3MONTH"),
                tenor(last, "BC_6MONTH"),
                tenor(last, "BC_1YEAR"));
    }

    /** Extracts one {@code <d:NAME ...>value</d:NAME>} percent; null when absent or unparsable. */
    private static Double tenor(String entry, String name) {
        Matcher m = Pattern.compile("<d:" + name + "[^>]*>([^<]*)</d:" + name + ">").matcher(entry);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Yields in percent (e.g. 5.40 == 5.40%); null when the feed omitted the tenor. */
    private record Curve(Double oneMonth, Double threeMonth, Double sixMonth, Double oneYear) {}
}
