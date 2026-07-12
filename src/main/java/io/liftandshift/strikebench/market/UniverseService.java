package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The globally selected trading universe: one setting that feeds the ticker tape, the scout's
 * default scan list, research quick-chips, and symbol suggestions everywhere. Persisted in the
 * settings table so it survives restarts and browsers. Resolution: explicit user selection
 * (sector or custom list) > AUTO_UNIVERSE env > DEMO in fixtures-only mode > CORE.
 */
public final class UniverseService {

    private static final String KEY_SECTOR = "universe.sector";
    private static final String KEY_CUSTOM = "universe.custom";
    public static final int MAX_CUSTOM = 30;

    private final Db db;
    private final AppConfig cfg;
    private final Clock clock;

    public UniverseService(Db db, AppConfig cfg, Clock clock) {
        this.db = db;
        this.cfg = cfg;
        this.clock = clock;
    }

    public record Active(String source, String sectorKey, String sectorLabel, List<String> symbols) {}

    public Active active() {
        String custom = setting(KEY_CUSTOM);
        if (custom != null && !custom.isBlank()) {
            return new Active("custom", null, "Custom", parseList(custom));
        }
        String sector = setting(KEY_SECTOR);
        if (sector != null && Universes.SECTORS.containsKey(sector)) {
            Universes.Sector s = Universes.SECTORS.get(sector);
            return new Active("sector", s.key(), s.label(), s.symbols());
        }
        if (envUniverseSet()) {
            return new Active("env", null, "AUTO_UNIVERSE", cfg.autoUniverse());
        }
        Universes.Sector def = Universes.SECTORS.get(cfg.fixturesOnly() ? "DEMO" : "CORE");
        return new Active("default", def.key(), def.label(), def.symbols());
    }

    private boolean envUniverseSet() {
        String env = System.getenv("AUTO_UNIVERSE");
        return env != null && !env.isBlank();
    }

    /**
     * Every symbol worth keeping warm — the active universe FIRST (priority), then the union of all
     * curated sector lists (deduped). The market engine warms these on boot so switching sectors or
     * looking up any covered ticker is instant, not a cold fetch. The DEMO pseudo-sector is skipped in
     * live mode (its symbols are the fixture set, already covered by real sectors where applicable).
     */
    public List<String> warmSymbols() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(active().symbols());
        boolean demo = cfg.fixturesOnly();
        for (Universes.Sector s : Universes.SECTORS.values()) {
            boolean isDemo = "DEMO".equals(s.key());
            if (demo != isDemo) continue; // fixture mode → only the demo set (has data); live → all real sectors
            out.addAll(s.symbols());
        }
        return List.copyOf(out);
    }

    /** Selects a curated sector (clears any custom list). Throws on unknown keys -> 400. */
    public Active selectSector(String key) {
        String norm = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
        if (!Universes.SECTORS.containsKey(norm)) {
            throw new IllegalArgumentException("Unknown sector '" + key + "' — one of " + Universes.SECTORS.keySet());
        }
        putSetting(KEY_CUSTOM, "");
        putSetting(KEY_SECTOR, norm);
        return active();
    }

    /** Sets a custom list (1..MAX_CUSTOM sane symbols). */
    public Active selectCustom(List<String> symbols) {
        List<String> clean = new ArrayList<>();
        for (String raw : symbols == null ? List.<String>of() : symbols) {
            String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            if (!s.matches("[A-Z0-9.\\-]{1,10}")) {
                throw new IllegalArgumentException("'" + raw + "' is not a valid ticker symbol");
            }
            if (!clean.contains(s)) clean.add(s);
        }
        if (clean.isEmpty()) throw new IllegalArgumentException("A custom universe needs at least one symbol");
        if (clean.size() > MAX_CUSTOM) throw new IllegalArgumentException("A custom universe is capped at " + MAX_CUSTOM + " symbols");
        putSetting(KEY_CUSTOM, String.join(",", clean));
        return active();
    }

    /** Everything the UI needs to render the picker. */
    public Map<String, Object> describe() {
        Active a = active();
        List<Map<String, Object>> sectors = new ArrayList<>();
        for (Universes.Sector s : Universes.SECTORS.values()) {
            sectors.add(Map.of("key", s.key(), "label", s.label(), "symbols", s.symbols()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", Map.of(
                "source", a.source(),
                "sectorKey", a.sectorKey() == null ? "" : a.sectorKey(),
                "label", a.sectorLabel(),
                "symbols", a.symbols()));
        out.put("sectors", sectors);
        out.put("maxCustom", MAX_CUSTOM);
        if (cfg.fixturesOnly()) {
            out.put("note", "Demo mode: only the built-in demo symbols carry data — other sectors will scan but skip symbols without quotes.");
        }
        return out;
    }

    private String setting(String k) {
        var rows = db.query("SELECT v FROM settings WHERE k=?", r -> r.str("v"), k);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void putSetting(String k, String v) {
        db.exec("INSERT INTO settings(k,v,updated_at) VALUES (?,?,?) "
                + "ON CONFLICT(k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at",
                k, v, Instant.now(clock).toString());
    }

    private static List<String> parseList(String csv) {
        return java.util.Arrays.stream(csv.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }
}
