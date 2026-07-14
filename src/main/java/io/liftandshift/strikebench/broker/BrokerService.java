package io.liftandshift.strikebench.broker;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.ports.BrokerageProvider;
import io.liftandshift.strikebench.paper.AuditLog;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Live-trading gates around the brokerage port. A live order goes out ONLY when:
 * connected + a successful preview id + the exact typed confirmation + an idempotent
 * clientOrderId (replays return the recorded order instead of re-sending).
 * The recommendation engine has no path to this class.
 */
public final class BrokerService {

    public static final String CONFIRM_TEXT = "I understand max loss and this is real money";

    /** A live-order preview is confirmable for this long; after that, re-preview. */
    public static final long PREVIEW_TTL_SECONDS = 120;

    private final BrokerageProvider broker; // may be null when unconfigured
    private final Db db;
    private final AuditLog audit;
    private final Clock clock;

    public BrokerService(BrokerageProvider broker, Db db, AuditLog audit, Clock clock) {
        this.broker = broker;
        this.db = db;
        this.audit = audit;
        this.clock = clock;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean configured = broker != null && broker.configured();
        out.put("configured", configured);
        out.put("connected", configured && broker.connected());
        out.put("provider", broker == null ? null : broker.name());
        return out;
    }

    public String startConnect() {
        return required().startConnect();
    }

    public void verifyConnect(String code) {
        required().verifyConnect(code);
        audit.log(null, null, "BROKER_CONNECTED", "INFO", Map.of("provider", required().name()));
    }

    public List<BrokerageProvider.BrokerAccount> accounts() { return required().accounts(); }

    public BrokerageProvider.BrokerBalance balance(String accountIdKey) { return required().balance(accountIdKey); }

    public List<BrokerageProvider.BrokerPosition> positions(String accountIdKey) { return required().positions(accountIdKey); }

    public List<Map<String, Object>> orders(String accountIdKey) { return required().orders(accountIdKey); }

    public record PreviewOutcome(String localId, BrokerageProvider.OrderPreview preview) {}

    public PreviewOutcome preview(String accountIdKey, Map<String, Object> orderPayload) {
        requireArgs(accountIdKey, orderPayload);
        BrokerageProvider.OrderPreview preview = required().previewOrder(accountIdKey, orderPayload);
        String localId = Ids.order();
        String now = now();
        db.exec("""
                INSERT INTO live_orders(id, client_order_id, broker_account_key, symbol, preview_id, broker_order_id, status, payload_json, created_at, updated_at)
                VALUES (?,?,?,?,?,NULL,'PREVIEWED',?,?,?)""",
                localId, "preview-" + localId, accountIdKey, symbolOf(orderPayload), preview.previewId(),
                Json.canonical(orderPayload), now, now);
        audit.log(null, null, "LIVE_ORDER_PREVIEWED", "INFO",
                Map.of("accountIdKey", accountIdKey, "previewId", preview.previewId(),
                        "estimatedTotalCents", preview.estimatedTotalCents()));
        return new PreviewOutcome(localId, preview);
    }

    public record PlaceOutcome(String localId, String brokerOrderId, String status, List<String> messages, boolean replay) {}

    public PlaceOutcome place(String accountIdKey, Map<String, Object> orderPayload, String previewId,
                              String clientOrderId, String confirmText) {
        required();
        requireArgs(accountIdKey, orderPayload);
        if (previewId == null || previewId.isBlank()) {
            throw new IllegalArgumentException("A successful preview is required before placing a live order");
        }
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new IllegalArgumentException("clientOrderId is required (idempotency key)");
        }
        if (!CONFIRM_TEXT.equals(confirmText)) {
            throw new IllegalArgumentException("Type the exact confirmation to place a live order: \"" + CONFIRM_TEXT + "\"");
        }
        String canonicalPayload = Json.canonical(orderPayload);

        // The preview gate is real: the previewId must belong to a locally recorded preview
        // for the SAME account and the BYTE-IDENTICAL order, and it must be fresh — otherwise
        // the max-loss confirmation the user typed was against different economics.
        Optional<Map<String, String>> previewRow = db.query(
                "SELECT broker_account_key, payload_json, created_at FROM live_orders WHERE preview_id=? AND status='PREVIEWED'",
                r -> Map.of("account", r.str("broker_account_key"),
                        "payload", r.str("payload_json"),
                        "createdAt", r.str("created_at")), previewId).stream().findFirst();
        if (previewRow.isEmpty()) {
            throw new IllegalArgumentException("previewId does not match any recorded preview — preview the order first");
        }
        if (!previewRow.get().get("account").equals(accountIdKey)) {
            throw new IllegalArgumentException("previewId belongs to a different account");
        }
        if (!samePayload(previewRow.get().get("payload"), canonicalPayload)) {
            throw new IllegalArgumentException("The order differs from the one you previewed — preview again to confirm against current numbers");
        }
        java.time.Instant previewedAt = java.time.Instant.parse(previewRow.get().get("createdAt"));
        if (java.time.Duration.between(previewedAt, clock.instant()).getSeconds() > PREVIEW_TTL_SECONDS) {
            throw new IllegalArgumentException("Preview has expired (" + PREVIEW_TTL_SECONDS + "s) — preview again to see current cost before placing");
        }

        // Idempotency: the SAME clientOrderId with the SAME order replays the recorded result;
        // the same id with a DIFFERENT order is a hard error, never a silent no-op.
        Optional<Map<String, String>> existing = db.query(
                "SELECT id, broker_order_id, status, payload_json, preview_id FROM live_orders WHERE client_order_id=?",
                r -> Map.of("id", r.str("id"),
                        "brokerOrderId", String.valueOf(r.str("broker_order_id")),
                        "status", r.str("status"),
                        "payload", String.valueOf(r.str("payload_json")),
                        "previewId", String.valueOf(r.str("preview_id"))), clientOrderId).stream().findFirst();
        if (existing.isPresent() && !"null".equals(existing.get().get("brokerOrderId"))) {
            Map<String, String> row = existing.get();
            if (!samePayload(row.get("payload"), canonicalPayload)) {
                throw new IllegalStateException("clientOrderId '" + clientOrderId
                        + "' was already used for a DIFFERENT order — use a fresh clientOrderId");
            }
            return new PlaceOutcome(row.get("id"), row.get("brokerOrderId"), row.get("status"), List.of(), true);
        }

        BrokerageProvider.OrderResult result = required().placeOrder(accountIdKey, orderPayload, previewId, clientOrderId);
        String localId = existing.map(m -> m.get("id")).orElse(Ids.order());
        String now = now();
        if (existing.isPresent()) {
            db.exec("UPDATE live_orders SET broker_order_id=?, status=?, updated_at=? WHERE id=?",
                    result.brokerOrderId(), result.status(), now, localId);
        } else {
            db.exec("""
                    INSERT INTO live_orders(id, client_order_id, broker_account_key, symbol, preview_id, broker_order_id, status, payload_json, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)""",
                    localId, clientOrderId, accountIdKey, symbolOf(orderPayload), previewId,
                    result.brokerOrderId(), result.status(), canonicalPayload, now, now);
        }
        audit.log(null, null, "LIVE_ORDER_PLACED", "WARN", Map.of(
                "accountIdKey", accountIdKey, "brokerOrderId", result.brokerOrderId(),
                "clientOrderId", clientOrderId));
        return new PlaceOutcome(localId, result.brokerOrderId(), result.status(), result.messages(), false);
    }

    public void cancel(String accountIdKey, String brokerOrderId) {
        required().cancelOrder(accountIdKey, brokerOrderId);
        // A broker cancel is an asynchronous REQUEST that can lose the race to a fill —
        // record intent, never a terminal fact. Confirm via the orders list.
        db.exec("UPDATE live_orders SET status='CANCEL_REQUESTED', updated_at=? WHERE broker_order_id=?", now(), brokerOrderId);
        audit.log(null, null, "LIVE_ORDER_CANCEL_REQUESTED", "WARN",
                Map.of("accountIdKey", accountIdKey, "brokerOrderId", brokerOrderId,
                        "note", "cancel requested; confirm terminal status via the broker orders list"));
    }

    private BrokerageProvider required() {
        if (broker == null || !broker.configured()) {
            throw new IllegalStateException("No brokerage is configured (set ETRADE_CONSUMER_KEY / ETRADE_CONSUMER_SECRET)");
        }
        return broker;
    }

    private static boolean samePayload(String left, String right) {
        return Json.parse(left).equals(Json.parse(right));
    }

    private static void requireArgs(String accountIdKey, Map<String, Object> payload) {
        if (accountIdKey == null || accountIdKey.isBlank()) throw new IllegalArgumentException("accountIdKey is required");
        if (payload == null || payload.isEmpty()) throw new IllegalArgumentException("order payload is required");
    }

    @SuppressWarnings("unchecked")
    private static String symbolOf(Map<String, Object> payload) {
        try {
            Object orders = payload.get("Order");
            if (orders instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> order) {
                Object instruments = ((Map<String, Object>) order).get("Instrument");
                if (instruments instanceof List<?> il && !il.isEmpty() && il.getFirst() instanceof Map<?, ?> inst) {
                    Object product = ((Map<String, Object>) inst).get("Product");
                    if (product instanceof Map<?, ?> p && p.get("symbol") != null) {
                        return String.valueOf(p.get("symbol"));
                    }
                }
            }
        } catch (RuntimeException ignored) { }
        return "UNKNOWN";
    }

    private String now() {
        return Instant.now(clock).toString();
    }
}
