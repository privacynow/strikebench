package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.eval.StrategyEvaluation;
import io.liftandshift.strikebench.eval.StrategySpec;
import io.liftandshift.strikebench.model.Horizon;
import io.liftandshift.strikebench.util.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * THE identity of one scan result row (program §6.2, audit §8.2):
 * {@code symbol + strategy family + exact package + expiration + declarations}.
 *
 * <p>Deduplicating a scan by SYMBOL alone silently discards real alternatives — an IWM bull put
 * spread and an IWM covered call are two different answers to two different questions, and
 * collapsing them means the row the user clicks may not be the row they were shown. Every place
 * that has to decide "is this the same result?" — the Scout's retained rows, the portfolio scan's
 * ranked field, the Book-aware frontier — asks this one primitive instead of re-spelling its own
 * rule.</p>
 *
 * <p>The key deliberately covers the package's financial and declarative content only. Price is
 * NOT part of it: re-pricing the same strikes at a new mark must stay the same row, otherwise a
 * streaming scan would show one result twice. {@link io.liftandshift.strikebench.paper.PackagePriceReceipt}
 * owns the separate price-level fingerprint that answers "is this the same quote?".</p>
 */
public record ResultIdentity(
        String key,
        String symbol,
        String family,
        String expiration,
        int quantity,
        String goal,
        String view,
        String horizon,
        Integer horizonSessions,
        String riskMode
) {
    public ResultIdentity {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("result identity key is required");
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("result identity symbol is required");
    }

    /** The identity of the evaluated package, from the evaluation's own candidate and spec. */
    public static ResultIdentity of(StrategyEvaluation evaluation) {
        if (evaluation == null || evaluation.candidate() == null) {
            throw new IllegalArgumentException("a result identity requires its exact evaluation");
        }
        Candidate candidate = evaluation.candidate();
        StrategySpec spec = evaluation.spec();
        String symbol = upper(evaluation.symbol());
        if (symbol == null) throw new IllegalArgumentException("a result identity requires its symbol");
        String family = upper(evaluation.family());
        String goal = upper(spec == null ? candidate.intent() : spec.intent());
        String view = upper(spec == null ? null : spec.thesis());
        String horizon = spec == null ? null : trimmed(spec.horizon());
        String riskMode = lower(spec == null ? null : spec.riskMode());
        Integer sessions = horizonSessions(horizon);
        List<String> legs = legIdentities(candidate);

        Map<String, Object> stable = new LinkedHashMap<>();
        stable.put("symbol", symbol);
        stable.put("family", family);
        stable.put("quantity", candidate.qty());
        stable.put("legs", legs);
        stable.put("goal", goal);
        stable.put("view", view);
        stable.put("horizonSessions", sessions);
        stable.put("riskMode", riskMode);
        return new ResultIdentity(sha256(stable), symbol, family, frontExpiration(candidate),
                candidate.qty(), goal, view, horizon, sessions, riskMode);
    }

    /** Canonical leg identity: structure only, in the wire form's already-canonical decimals. */
    private static List<String> legIdentities(Candidate candidate) {
        List<String> legs = new ArrayList<>();
        for (LegView leg : candidate.legs() == null ? List.<LegView>of() : candidate.legs()) {
            legs.add(String.join("|",
                    upperOrBlank(leg.action()), upperOrBlank(leg.type()),
                    blankIfNull(leg.strike()), blankIfNull(leg.expiration()),
                    Integer.toString(leg.ratio()), Integer.toString(leg.multiplier())));
        }
        // Leg ORDER is presentational; the same four contracts listed differently are one package.
        legs.sort(String::compareTo);
        return List.copyOf(legs);
    }

    /** The row's exact expiration: the front option expiration, or null for a share-only package. */
    private static String frontExpiration(Candidate candidate) {
        String front = null;
        for (LegView leg : candidate.legs() == null ? List.<LegView>of() : candidate.legs()) {
            if ("STOCK".equalsIgnoreCase(leg.type()) || leg.expiration() == null) continue;
            if (front == null || leg.expiration().compareTo(front) < 0) front = leg.expiration();
        }
        return front;
    }

    private static Integer horizonSessions(String horizon) {
        if (horizon == null || horizon.isBlank()) return null;
        try { return Horizon.tradingSessions(horizon); }
        catch (RuntimeException unparseable) { return null; }
    }

    private static String sha256(Map<String, Object> stable) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Json.canonical(stable).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot identify a scan result", e);
        }
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    private static String upper(String value) {
        String trimmed = trimmed(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
    private static String lower(String value) {
        String trimmed = trimmed(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }
    private static String upperOrBlank(String value) {
        String upper = upper(value);
        return upper == null ? "" : upper;
    }
    private static String blankIfNull(String value) {
        String trimmed = trimmed(value);
        return trimmed == null ? "" : trimmed;
    }
}
