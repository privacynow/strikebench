package io.liftandshift.market.providers;

import io.liftandshift.config.AppConfig;
import io.liftandshift.market.Domain;
import io.liftandshift.market.ports.MarketDataProvider;
import io.liftandshift.model.Candle;
import io.liftandshift.model.OptionChain;
import io.liftandshift.model.Quote;
import io.liftandshift.model.SymbolMatch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Keyless end-of-day daily candles from stooq.com, served as CSV:
 * {@code GET {base}/q/d/l/?s={symbol}.us&i=d} with header
 * {@code Date,Open,High,Low,Close,Volume}. Candles only; every other domain
 * returns empty. HTTP failures propagate as {@link Http.ProviderHttpException}
 * so the service falls through the provider chain.
 */
public final class StooqProvider implements MarketDataProvider {

    private final Http http;
    private final String baseUrl;

    public StooqProvider(AppConfig cfg) {
        this.http = new Http(cfg.httpTimeoutMs());
        this.baseUrl = Http.normalizeBase(cfg.stooqBaseUrl());
    }

    @Override
    public String name() {
        return "stooq";
    }

    @Override
    public Set<Domain> domains() {
        return Set.of(Domain.CANDLES);
    }

    @Override
    public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
        String url = baseUrl + "/q/d/l/?s=" + symbol.trim().toLowerCase(Locale.ROOT) + ".us&i=d";
        String body = http.get(url);
        if (body == null) return List.of();
        String trimmed = body.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("No data")) return List.of();

        List<Candle> out = new ArrayList<>();
        String[] lines = trimmed.split("\\r?\\n");
        for (int i = 1; i < lines.length; i++) { // skip header row
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] cols = line.split(",", -1);
            if (cols.length < 5) continue; // malformed row
            LocalDate date;
            try {
                date = LocalDate.parse(cols[0].trim());
            } catch (DateTimeParseException e) {
                continue; // malformed row
            }
            if (date.isBefore(from) || date.isAfter(to)) continue;
            out.add(new Candle(
                    date,
                    new BigDecimal(cols[1].trim()),
                    new BigDecimal(cols[2].trim()),
                    new BigDecimal(cols[3].trim()),
                    new BigDecimal(cols[4].trim()),
                    parseVolume(cols.length > 5 ? cols[5] : null),
                    false));
        }
        out.sort(Comparator.comparing(Candle::date));
        return out;
    }

    private static long parseVolume(String raw) {
        if (raw == null) return 0L;
        String s = raw.trim();
        if (s.isEmpty()) return 0L;
        try {
            return new BigDecimal(s).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ---- Domains stooq does not serve ----

    @Override
    public List<SymbolMatch> lookup(String query) {
        return List.of();
    }

    @Override
    public Optional<Quote> quote(String symbol) {
        return Optional.empty();
    }

    @Override
    public List<LocalDate> expirations(String symbol) {
        return List.of();
    }

    @Override
    public Optional<OptionChain> chain(String symbol, LocalDate expiration) {
        return Optional.empty();
    }
}
