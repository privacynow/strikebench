package io.liftandshift.strikebench.market.providers;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.model.NewsItem;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyless per-symbol news HEADLINES via the Google News RSS search feed — the real headline source
 * the app lacked (EDGAR only emits filings). Aggregated ALONGSIDE EDGAR by MarketDataService, so a
 * symbol shows both. Transport/parse failures return empty (the service records the status and the
 * aggregation simply omits this source), never fabricating anything.
 */
public final class NewsRssProvider implements NewsFilingsProvider {

    private static final int MAX_ITEMS = 12;
    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link>(.*?)</link>", Pattern.DOTALL);
    private static final Pattern PUBDATE = Pattern.compile("<pubDate>(.*?)</pubDate>", Pattern.DOTALL);
    private static final Pattern SOURCE = Pattern.compile("<source[^>]*>(.*?)</source>", Pattern.DOTALL);

    private final Http http;
    private final String baseUrl;

    public NewsRssProvider(AppConfig cfg) {
        this.http = new Http(cfg.httpTimeoutMs());
        this.baseUrl = Http.normalizeBase(cfg.newsRssBaseUrl());
    }

    @Override public String name() { return "news-rss"; }

    @Override
    public List<NewsItem> news(String symbol) {
        String ticker = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (ticker.isEmpty() || baseUrl.isBlank()) return List.of();

        String q = URLEncoder.encode("\"" + ticker + "\" stock", StandardCharsets.UTF_8);
        String url = baseUrl + "?q=" + q + "&hl=en-US&gl=US&ceid=US:en";
        String xml = http.get(url);   // throws on transport/non-2xx; MarketDataService records + omits

        List<NewsItem> items = new ArrayList<>();
        Matcher im = ITEM.matcher(xml);
        while (im.find() && items.size() < MAX_ITEMS) {
            String item = im.group(1);
            String rawTitle = first(TITLE, item);
            if (rawTitle == null || rawTitle.isBlank()) continue;
            String source = unescape(orEmpty(first(SOURCE, item)));
            String title = stripSourceSuffix(unescape(rawTitle), source);
            String link = unescape(orEmpty(first(LINK, item))).trim();
            long ts = parseDate(first(PUBDATE, item));
            items.add(new NewsItem(ticker, title, source.isBlank() ? "News" : source, link, ts));
        }
        return List.copyOf(items);
    }

    private static String first(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String orEmpty(String s) { return s == null ? "" : s; }

    /** Google News titles read "Headline - Source"; drop the trailing source so it isn't doubled. */
    private static String stripSourceSuffix(String title, String source) {
        if (!source.isBlank() && title.endsWith(" - " + source)) {
            return title.substring(0, title.length() - source.length() - 3).trim();
        }
        return title.trim();
    }

    private static long parseDate(String rfc1123) {
        if (rfc1123 == null || rfc1123.isBlank()) return 0L;
        try {
            return ZonedDateTime.parse(rfc1123.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("<![CDATA[", "").replace("]]>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").trim();
    }
}
