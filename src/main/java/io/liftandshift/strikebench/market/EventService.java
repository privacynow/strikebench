package io.liftandshift.strikebench.market;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.NewsItem;
import io.liftandshift.strikebench.util.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single issuer-event owner. Confirmed issuer evidence, reviewed imports, SEC-cadence
 * estimates, and honest unavailability all use one persisted shape and one precedence rule.
 * No consumer parses headlines or invents a parallel event calendar.
 */
public final class EventService {

    public enum EventType { EARNINGS, EX_DIVIDEND }
    public enum EvidenceStatus { CONFIRMED, ESTIMATED, UNAVAILABLE }
    public enum EventSession { BEFORE_OPEN, AFTER_CLOSE, UNKNOWN }
    public enum SourceKind { ISSUER_CONFIRMED, REVIEWED_IMPORT, SEC_CADENCE, UNAVAILABLE }

    /**
     * Canonical event evidence. The confidence bounds are exact for confirmed events and a
     * disclosed window for estimates. Derived compatibility fields are serialized from status;
     * they are never separately stored or independently mutable.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventEvidence(
            String symbol,
            EventType eventType,
            EvidenceStatus status,
            LocalDate date,
            EventSession session,
            LocalDate confidenceStart,
            LocalDate confidenceEnd,
            SourceKind sourceKind,
            String source,
            String sourceUrl,
            OffsetDateTime observedAt,
            String payloadFingerprint,
            String basis,
            String note
    ) {
        public EventEvidence {
            symbol = normalizeSymbol(symbol);
            if (eventType == null || status == null || session == null || sourceKind == null
                    || observedAt == null || source == null || source.isBlank()
                    || payloadFingerprint == null || !payloadFingerprint.matches("[0-9a-f]{64}")
                    || basis == null || basis.isBlank() || note == null || note.isBlank()) {
                throw new IllegalArgumentException("event evidence needs type, authority, source, time, fingerprint, basis, and note");
            }
            boolean unavailable = status == EvidenceStatus.UNAVAILABLE;
            if (unavailable != (date == null)) {
                throw new IllegalArgumentException("only unavailable event evidence may omit its date");
            }
            if (unavailable) {
                if (confidenceStart != null || confidenceEnd != null || sourceKind != SourceKind.UNAVAILABLE) {
                    throw new IllegalArgumentException("unavailable evidence cannot claim a confidence window or source authority");
                }
            } else {
                if (confidenceStart == null || confidenceEnd == null
                        || confidenceStart.isAfter(date) || confidenceEnd.isBefore(date)
                        || confidenceStart.isAfter(confidenceEnd)) {
                    throw new IllegalArgumentException("available event evidence needs an ordered confidence window around its date");
                }
                if (sourceKind == SourceKind.UNAVAILABLE) {
                    throw new IllegalArgumentException("available evidence needs an available source authority");
                }
            }
            if (status == EvidenceStatus.CONFIRMED && session == EventSession.UNKNOWN) {
                throw new IllegalArgumentException("confirmed earnings evidence needs before-open or after-close session timing");
            }
            if (sourceKind == SourceKind.ISSUER_CONFIRMED && status != EvidenceStatus.CONFIRMED) {
                throw new IllegalArgumentException("issuer-confirmed evidence must be confirmed");
            }
        }

        @JsonProperty public boolean available() { return status != EvidenceStatus.UNAVAILABLE; }
        @JsonProperty public boolean confirmed() { return status == EvidenceStatus.CONFIRMED; }
        @JsonProperty public int windowDays() {
            if (!available()) return 0;
            return Math.max((int) java.time.temporal.ChronoUnit.DAYS.between(confidenceStart, date),
                    (int) java.time.temporal.ChronoUnit.DAYS.between(date, confidenceEnd));
        }
    }

    /** One canonical interpretation of the event evidence against a dated package horizon. */
    public record EarningsProximity(boolean available, boolean likelyBefore,
                                    EventEvidence evidence, String note) {}

    /** Optional external issuer adapter. Every call is wrapped by this service's politeness gate. */
    public interface IssuerEventProvider {
        Optional<IssuerEvent> nextEarnings(String symbol);
    }

    /** Raw confirmed issuer result before canonicalization and persistence. */
    public record IssuerEvent(LocalDate date, EventSession session, String source, String sourceUrl,
                              Instant observedAt, String rawPayload) {
        public IssuerEvent {
            if (date == null || session == null || session == EventSession.UNKNOWN
                    || source == null || source.isBlank() || sourceUrl == null || sourceUrl.isBlank()) {
                throw new IllegalArgumentException("issuer event needs date, session, source, and URL");
            }
        }
    }

    /** Admin-reviewed evidence entering through the existing Data owner. */
    public record ReviewedEvent(
            String symbol,
            EvidenceStatus status,
            LocalDate date,
            EventSession session,
            LocalDate confidenceStart,
            LocalDate confidenceEnd,
            SourceKind sourceKind,
            String source,
            String sourceUrl,
            OffsetDateTime observedAt,
            String basis,
            String reviewedBy,
            String rawPayload
    ) {
        public ReviewedEvent {
            symbol = normalizeSymbol(symbol);
            if (status == null || status == EvidenceStatus.UNAVAILABLE || date == null || session == null
                    || sourceKind == null || !EnumSet.of(SourceKind.ISSUER_CONFIRMED,
                            SourceKind.REVIEWED_IMPORT).contains(sourceKind)
                    || source == null || source.isBlank() || sourceUrl == null || sourceUrl.isBlank()
                    || basis == null || basis.isBlank() || reviewedBy == null || reviewedBy.isBlank()) {
                throw new IllegalArgumentException("reviewed event needs available evidence, source URL, basis, and reviewer");
            }
            if (status == EvidenceStatus.CONFIRMED && session == EventSession.UNKNOWN) {
                throw new IllegalArgumentException("a confirmed reviewed event needs before-open or after-close timing");
            }
            if (sourceKind == SourceKind.ISSUER_CONFIRMED && status != EvidenceStatus.CONFIRMED) {
                throw new IllegalArgumentException("issuer-confirmed imports must have CONFIRMED status");
            }
            if (confidenceStart == null) confidenceStart = status == EvidenceStatus.CONFIRMED ? date : date.minusDays(7);
            if (confidenceEnd == null) confidenceEnd = status == EvidenceStatus.CONFIRMED ? date : date.plusDays(7);
            if (confidenceStart.isAfter(date) || confidenceEnd.isBefore(date)) {
                throw new IllegalArgumentException("reviewed confidence bounds must contain the event date");
            }
        }
    }

    private record QuarterlyReport(LocalDate date, String url) {}

    private static final String SEC_MODEL_VERSION = "sec-filing-cadence-v2";
    private static final Set<SourceKind> ISSUER_ONLY = EnumSet.of(SourceKind.ISSUER_CONFIRMED);
    private static final Set<SourceKind> REVIEWED_ONLY = EnumSet.of(SourceKind.REVIEWED_IMPORT);
    private static final Set<SourceKind> SEC_ONLY = EnumSet.of(SourceKind.SEC_CADENCE);

    private final MarketDataService market;
    private final Db db;
    private final Clock clock;
    private final List<IssuerEventProvider> issuerProviders;
    private final ProviderPoliteness issuerPoliteness;
    private final ProviderPoliteness secPoliteness;
    private final Cache<String, EventEvidence> cache =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(6)).maximumSize(500).build();
    private final Cache<String, List<QuarterlyReport>> quarterlyReports =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(6)).maximumSize(500).build();

    /** Unit-level constructor; production uses the persistent overload. */
    public EventService(MarketDataService market, Clock clock) {
        this(market, null, clock, List.of(),
                new ProviderPoliteness("issuer-events", 1, 250, 30 * 60_000L),
                new ProviderPoliteness("sec-event-evidence", 1, 250, 30 * 60_000L));
    }

    public EventService(MarketDataService market, Db db, Clock clock) {
        this(market, db, clock, List.of(),
                new ProviderPoliteness("issuer-events", 1, 250, 30 * 60_000L),
                new ProviderPoliteness("sec-event-evidence", 1, 250, 30 * 60_000L));
    }

    /** Injectable acquisition seams for deterministic provider-order and politeness tests. */
    public EventService(MarketDataService market, Db db, Clock clock,
                        List<IssuerEventProvider> issuerProviders,
                        ProviderPoliteness issuerPoliteness,
                        ProviderPoliteness secPoliteness) {
        this.market = market; // data-only EvaluationService tests may not own a market reader
        this.db = db;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.issuerProviders = List.copyOf(issuerProviders == null ? List.of() : issuerProviders);
        this.issuerPoliteness = java.util.Objects.requireNonNull(issuerPoliteness, "issuer politeness");
        this.secPoliteness = java.util.Objects.requireNonNull(secPoliteness, "SEC politeness");
    }

    /** Always returns a receipt: confirmed, estimated, or explicitly unavailable. */
    public EventEvidence earnings(String symbol) {
        String sym = normalizeSymbol(symbol);
        return cache.get(sym, this::resolve);
    }

    /** Canonical unavailable shape for Demo/simulated lanes that must not borrow Observed events. */
    public EventEvidence unavailableForContext(String symbol, String note) {
        return unavailableForContext(symbol, EventType.EARNINGS, note);
    }

    public EventEvidence unavailableForContext(String symbol, EventType eventType, String note) {
        String sym = normalizeSymbol(symbol);
        String reason = note == null || note.isBlank() ? "event evidence unavailable in this context" : note;
        Map<String, Object> material = Map.of(
                "symbol", sym, "type", eventType.name(),
                "status", EvidenceStatus.UNAVAILABLE.name(), "context", reason);
        return new EventEvidence(sym, eventType, EvidenceStatus.UNAVAILABLE,
                null, EventSession.UNKNOWN, null, null, SourceKind.UNAVAILABLE,
                "StrikeBench event evidence", null,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), fingerprint(material),
                "market-lane event isolation", reason);
    }

    /** Dated issuer 10-Q/10-K filings behind the SEC-cadence estimate, newest first. */
    public List<LocalDate> quarterlyReportDates(String symbol) {
        String sym = normalizeSymbol(symbol);
        return quarterlyReports.get(sym, this::loadQuarterlyReports).stream()
                .map(QuarterlyReport::date).toList();
    }

    public boolean earningsLikelyBefore(String symbol, LocalDate expiration) {
        return earningsProximity(symbol, expiration).likelyBefore();
    }

    public EarningsProximity earningsProximity(String symbol, LocalDate throughDate) {
        if (throughDate == null) {
            return new EarningsProximity(false, false, null,
                    "earnings proximity unavailable — the package has no dated expiration");
        }
        EventEvidence event = earnings(symbol);
        if (!event.available()) {
            return new EarningsProximity(false, false, event, event.note());
        }
        LocalDate today = LocalDate.now(clock);
        boolean likelyBefore = !event.confidenceStart().isAfter(throughDate)
                && !event.confidenceEnd().isBefore(today);
        String status = event.status() == EvidenceStatus.CONFIRMED
                ? "earnings CONFIRMED " + event.date() + " " + sessionText(event.session())
                : "earnings ESTIMATED near " + event.date() + " (±" + event.windowDays() + " days)";
        return new EarningsProximity(true, likelyBefore, event,
                status + (likelyBefore ? " within this package's life" : ", outside this package's life")
                        + "; " + event.basis());
    }

    /** Persists one reviewed record and invalidates the affected canonical read. */
    public EventEvidence importReviewed(ReviewedEvent input) {
        if (db == null) throw new IllegalStateException("reviewed event import requires persistent storage");
        OffsetDateTime observed = input.observedAt() == null
                ? OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC) : input.observedAt();
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("symbol", input.symbol());
        material.put("type", EventType.EARNINGS.name());
        material.put("status", input.status().name());
        material.put("date", input.date());
        material.put("session", input.session().name());
        material.put("confidenceStart", input.confidenceStart());
        material.put("confidenceEnd", input.confidenceEnd());
        material.put("sourceKind", input.sourceKind().name());
        material.put("source", input.source());
        material.put("sourceUrl", input.sourceUrl());
        material.put("rawPayload", input.rawPayload());
        EventEvidence evidence = new EventEvidence(input.symbol(), EventType.EARNINGS,
                input.status(), input.date(), input.session(), input.confidenceStart(),
                input.confidenceEnd(), input.sourceKind(), input.source(), input.sourceUrl(),
                observed, fingerprint(material), input.basis(),
                input.status() == EvidenceStatus.CONFIRMED
                        ? "Confirmed earnings evidence reviewed from " + input.source() + "."
                        : "Reviewed earnings estimate from " + input.source() + ".");
        persist(evidence, input.reviewedBy());
        cache.invalidate(input.symbol());
        return earnings(input.symbol());
    }

    /** Immutable stored history for audits; canonical reads still apply the single precedence rule. */
    public List<EventEvidence> history(String symbol) {
        if (db == null) return List.of();
        return db.query("SELECT * FROM market_event_evidence WHERE symbol=? AND event_type='EARNINGS' "
                        + "ORDER BY observed_at DESC, payload_fingerprint",
                EventService::fromRow, normalizeSymbol(symbol));
    }

    public void invalidate(String symbol) {
        String sym = normalizeSymbol(symbol);
        cache.invalidate(sym);
        quarterlyReports.invalidate(sym);
    }

    private EventEvidence resolve(String sym) {
        EventEvidence acquiredIssuer = acquireIssuerEvidence(sym);
        EventEvidence existing = bestPersisted(sym, ISSUER_ONLY);
        if (existing != null) return existing;
        if (db == null && acquiredIssuer != null) return acquiredIssuer;
        existing = bestPersisted(sym, REVIEWED_ONLY);
        if (existing != null) return existing;
        existing = bestPersisted(sym, SEC_ONLY);
        if (existing != null) return existing;

        EventEvidence estimate = estimateFromSecCadence(sym);
        if (estimate != null) {
            persist(estimate, null);
            return estimate;
        }
        EventEvidence unavailable = unavailable(sym);
        persist(unavailable, null);
        return unavailable;
    }

    private EventEvidence acquireIssuerEvidence(String sym) {
        EventEvidence earliest = null;
        for (IssuerEventProvider provider : issuerProviders) {
            Optional<IssuerEvent> result;
            try {
                result = issuerPoliteness.call(() -> provider.nextEarnings(sym), Optional.empty());
            } catch (RuntimeException unavailable) {
                continue;
            }
            if (result.isEmpty()) continue;
            IssuerEvent raw = result.get();
            OffsetDateTime observed = OffsetDateTime.ofInstant(
                    raw.observedAt() == null ? clock.instant() : raw.observedAt(), ZoneOffset.UTC);
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("symbol", sym);
            material.put("date", raw.date());
            material.put("session", raw.session().name());
            material.put("source", raw.source());
            material.put("sourceUrl", raw.sourceUrl());
            material.put("rawPayload", raw.rawPayload());
            EventEvidence evidence = new EventEvidence(sym, EventType.EARNINGS,
                    EvidenceStatus.CONFIRMED, raw.date(), raw.session(), raw.date(), raw.date(),
                    SourceKind.ISSUER_CONFIRMED, raw.source(), raw.sourceUrl(), observed,
                    fingerprint(material), "issuer-published earnings calendar evidence",
                    "Confirmed earnings date and session from " + raw.source() + ".");
            persist(evidence, null);
            if (earliest == null || evidence.date().isBefore(earliest.date())) earliest = evidence;
        }
        return earliest;
    }

    private EventEvidence estimateFromSecCadence(String sym) {
        List<QuarterlyReport> reports = new ArrayList<>(
                quarterlyReports.get(sym, this::loadQuarterlyReports));
        if (reports.size() < 2) return null;
        List<Long> gaps = new ArrayList<>();
        for (int i = 0; i + 1 < reports.size(); i++) {
            long gap = java.time.temporal.ChronoUnit.DAYS.between(
                    reports.get(i + 1).date(), reports.get(i).date());
            if (gap >= 30) gaps.add(gap);
        }
        if (gaps.isEmpty()) return null;
        gaps.sort(Long::compareTo);
        long cadence = Math.clamp(gaps.get(gaps.size() / 2), 60, 120);
        LocalDate today = LocalDate.now(clock);
        LocalDate projected = reports.getFirst().date().plusDays(cadence);
        int guard = 0;
        while (projected.isBefore(today) && guard++ < 8) projected = projected.plusDays(cadence);
        int window = 7;
        String basis = "SEC filing cadence (" + reports.size() + " quarterly reports, ~"
                + cadence + "-day rhythm; " + SEC_MODEL_VERSION + ")";
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("model", SEC_MODEL_VERSION);
        material.put("symbol", sym);
        material.put("reports", reports);
        material.put("cadenceDays", cadence);
        material.put("projectedDate", projected);
        return new EventEvidence(sym, EventType.EARNINGS, EvidenceStatus.ESTIMATED,
                projected, EventSession.UNKNOWN, projected.minusDays(window), projected.plusDays(window),
                SourceKind.SEC_CADENCE, "SEC EDGAR", reports.getFirst().url(),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), fingerprint(material), basis,
                "Unconfirmed earnings window estimated from issuer filing cadence; this is not a no-event claim.");
    }

    private List<QuarterlyReport> loadQuarterlyReports(String sym) {
        if (market == null) return List.of();
        try {
            return secPoliteness.call(() -> {
                LinkedHashSet<QuarterlyReport> reports = new LinkedHashSet<>();
                for (NewsItem item : market.news(sym)) {
                    if (!"SEC EDGAR".equals(item.source()) || item.headline() == null) continue;
                    if (item.headline().startsWith("10-Q") || item.headline().startsWith("10-K")) {
                        reports.add(new QuarterlyReport(LocalDate.ofInstant(
                                Instant.ofEpochMilli(item.publishedEpochMs()), ZoneOffset.UTC), item.url()));
                    }
                }
                return reports.stream().sorted(Comparator.comparing(QuarterlyReport::date).reversed()).toList();
            }, List.of());
        } catch (RuntimeException unavailable) {
            return List.of();
        }
    }

    private EventEvidence bestPersisted(String sym, Set<SourceKind> allowed) {
        if (db == null) return null;
        LocalDate today = LocalDate.now(clock);
        return db.query("SELECT * FROM market_event_evidence WHERE symbol=? AND event_type='EARNINGS' "
                        + "AND confidence_end>=? ORDER BY event_date, observed_at DESC",
                EventService::fromRow, sym, today).stream()
                .filter(event -> allowed.contains(event.sourceKind()))
                .min(Comparator.comparing(EventEvidence::date)
                        .thenComparing(EventEvidence::observedAt, Comparator.reverseOrder()))
                .orElse(null);
    }

    private void persist(EventEvidence evidence, String reviewedBy) {
        if (db == null) return;
        db.exec("INSERT INTO market_event_evidence(payload_fingerprint,symbol,event_type,evidence_status,"
                        + "event_date,event_session,confidence_start,confidence_end,source_kind,source_label,"
                        + "source_url,observed_at,basis,note,reviewed_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(payload_fingerprint) DO NOTHING",
                evidence.payloadFingerprint(), evidence.symbol(), evidence.eventType().name(),
                evidence.status().name(), evidence.date(), evidence.session().name(),
                evidence.confidenceStart(), evidence.confidenceEnd(), evidence.sourceKind().name(),
                evidence.source(), evidence.sourceUrl(), evidence.observedAt(), evidence.basis(),
                evidence.note(), reviewedBy);
    }

    private EventEvidence unavailable(String sym) {
        Map<String, Object> material = Map.of(
                "symbol", sym, "type", EventType.EARNINGS.name(),
                "status", EvidenceStatus.UNAVAILABLE.name(), "asOf", LocalDate.now(clock));
        return new EventEvidence(sym, EventType.EARNINGS, EvidenceStatus.UNAVAILABLE,
                null, EventSession.UNKNOWN, null, null, SourceKind.UNAVAILABLE,
                "StrikeBench event evidence", null,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), fingerprint(material),
                "issuer evidence → reviewed import → SEC filing cadence",
                "earnings unavailable — no confirmed or reviewed date and not enough reachable SEC quarterly filings "
                        + "to estimate a cadence; this is not a no-event claim");
    }

    private static EventEvidence fromRow(Db.Row row) {
        return new EventEvidence(row.str("symbol"), EventType.valueOf(row.str("event_type")),
                EvidenceStatus.valueOf(row.str("evidence_status")), row.date("event_date"),
                EventSession.valueOf(row.str("event_session")), row.date("confidence_start"),
                row.date("confidence_end"), SourceKind.valueOf(row.str("source_kind")),
                row.str("source_label"), row.str("source_url"), row.odt("observed_at"),
                row.str("payload_fingerprint"), row.str("basis"), row.str("note"));
    }

    private static String sessionText(EventSession session) {
        return switch (session) {
            case BEFORE_OPEN -> "before open";
            case AFTER_CLOSE -> "after close";
            case UNKNOWN -> "session unknown";
        };
    }

    private static String normalizeSymbol(String raw) {
        String symbol = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9.^_-]{1,20}")) {
            throw new IllegalArgumentException("a valid symbol is required");
        }
        return symbol;
    }

    private static String fingerprint(Object material) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(Json.write(material).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
