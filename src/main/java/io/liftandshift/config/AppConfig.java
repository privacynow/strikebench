package io.liftandshift.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Configuration resolved from (highest priority first):
 * 1. Environment variables (UPPER_SNAKE)
 * 2. JVM system properties (lower.dot)
 * 3. ./strikebench.properties (working directory)
 * 4. Built-in defaults
 *
 * Secrets (API keys, OAuth tokens) live only here and in the local database.
 * They are never exposed to the frontend.
 */
public final class AppConfig {

    private final Properties fileProps = new Properties();
    private final java.util.Map<String, String> overrides = new java.util.HashMap<>();

    public AppConfig() {
        this(Path.of("strikebench.properties"));
    }

    public AppConfig(Path propertiesFile) {
        if (propertiesFile != null && Files.isRegularFile(propertiesFile)) {
            // Properties.load(InputStream) decodes Latin-1 per spec — a UTF-8 file with an
            // em-dash or accented brand name would silently garble. Read as UTF-8 text.
            try (java.io.Reader in = Files.newBufferedReader(propertiesFile, java.nio.charset.StandardCharsets.UTF_8)) {
                fileProps.load(in);
            } catch (IOException e) {
                System.err.println("WARN: could not read " + propertiesFile + ": " + e.getMessage());
            }
        }
    }

    /** Test constructor: explicit overrides beat every other source. Keys in UPPER_SNAKE. */
    public AppConfig(java.util.Map<String, String> overrides) {
        this((Path) null);
        overrides.forEach((k, v) -> this.overrides.put(k.toUpperCase(Locale.ROOT).replace('.', '_'), v));
    }

    public String get(String key, String def) {
        String override = overrides.get(key.toUpperCase(Locale.ROOT).replace('.', '_'));
        if (override != null && !override.isBlank()) return override.trim();
        String env = System.getenv(key.toUpperCase(Locale.ROOT).replace('.', '_'));
        if (env != null && !env.isBlank()) return env.trim();
        String sys = System.getProperty(key.toLowerCase(Locale.ROOT).replace('_', '.'));
        if (sys != null && !sys.isBlank()) return sys.trim();
        String file = fileProps.getProperty(key.toLowerCase(Locale.ROOT).replace('_', '.'));
        if (file != null && !file.isBlank()) return file.trim();
        return def;
    }

    public int getInt(String key, int def) {
        try { return Integer.parseInt(get(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public long getLong(String key, long def) {
        try { return Long.parseLong(get(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public boolean getBool(String key, boolean def) {
        return switch (get(key, String.valueOf(def)).toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> def;
        };
    }

    // ---- Well-known settings ----

    public int port() { return getInt("PORT", 7070); }

    /** Product name shown in the UI header, page title, and docs links. */
    public String brandName() { return get("BRAND_NAME", "StrikeBench"); }

    /** One-line subtitle available to the UI (home hero, about text). */
    public String brandTagline() {
        return get("BRAND_TAGLINE", "Learn, paper-trade, and backtest options — with honest numbers.");
    }

    /** Default DB file. A legacy data/options-lab.db is moved here once at boot (see Main). */
    public String dbPath() { return get("DB_PATH", "data/strikebench.db"); }

    /** On-device AI assets (transformers.js runtime + ONNX models), served at /models when present. */
    public String modelsDir() { return get("MODELS_DIR", "data/models"); }

    /** When true, only deterministic fixture data is served; no network calls are made. */
    public boolean fixturesOnly() { return getBool("FIXTURES_ONLY", false); }

    public long httpTimeoutMs() { return getLong("HTTP_TIMEOUT_MS", 10_000L); }

    /** Per-contract, per-leg commission in cents (default $0.65). */
    public long feePerContractCents() { return getLong("FEE_PER_CONTRACT_CENTS", 65); }

    /** Per-order base fee in cents (default $0). */
    public long feePerOrderCents() { return getLong("FEE_PER_ORDER_CENTS", 0); }

    /** Default starting cash for a new paper account, in cents (default $100,000). */
    public long defaultStartingCashCents() { return getLong("DEFAULT_STARTING_CASH_CENTS", 100_000_00L); }

    // Provider keys (all optional; providers degrade gracefully when absent)
    public String etradeConsumerKey() { return get("ETRADE_CONSUMER_KEY", ""); }
    public String etradeConsumerSecret() { return get("ETRADE_CONSUMER_SECRET", ""); }
    public boolean etradeSandbox() { return getBool("ETRADE_SANDBOX", true); }
    /** Override base URL, used by tests to point at a mock server. Empty = derive from sandbox flag. */
    public String etradeBaseUrlOverride() { return get("ETRADE_BASE_URL", ""); }

    public String polygonApiKey() { return get("POLYGON_API_KEY", ""); }
    public String alphaVantageApiKey() { return get("ALPHAVANTAGE_API_KEY", ""); }
    public String fredApiKey() { return get("FRED_API_KEY", ""); }

    /** Override base URLs so tests can point free providers at a mock server. */
    public String cboeBaseUrl() { return get("CBOE_BASE_URL", "https://cdn.cboe.com"); }
    public String stooqBaseUrl() { return get("STOOQ_BASE_URL", "https://stooq.com"); }
    public String edgarBaseUrl() { return get("EDGAR_BASE_URL", "https://www.sec.gov"); }
    /** EDGAR submissions live on a different host than the ticker file. */
    public String edgarDataBaseUrl() { return get("EDGAR_DATA_BASE_URL", "https://data.sec.gov"); }
    /** Contact info EDGAR requires in the User-Agent header. */
    public String edgarUserAgent() { return get("EDGAR_USER_AGENT", "StrikeBench/1.0 (babarahmedfaraz@gmail.com)"); }
    public String treasuryBaseUrl() { return get("TREASURY_BASE_URL", "https://home.treasury.gov"); }
    public String fredBaseUrl() { return get("FRED_BASE_URL", "https://api.stlouisfed.org"); }
    public String polygonBaseUrl() { return get("POLYGON_BASE_URL", "https://api.polygon.io"); }
    public String alphaVantageBaseUrl() { return get("ALPHAVANTAGE_BASE_URL", "https://www.alphavantage.co"); }

    /** Symbols the auto-scout scans when the caller does not supply a universe. */
    public java.util.List<String> autoUniverse() {
        String raw = fixturesOnly()
                ? get("AUTO_UNIVERSE", "AAPL,SPY,QQQ,TSLA")
                : get("AUTO_UNIVERSE", "SPY,QQQ,IWM,AAPL,MSFT,NVDA,TSLA,AMZN,META,GOOGL");
        return java.util.Arrays.stream(raw.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }
}
