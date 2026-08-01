package io.liftandshift.strikebench.broker;

import com.fasterxml.jackson.databind.JsonNode;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.ports.BrokerageProvider;
import io.liftandshift.strikebench.paper.AuditLog;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.OwnerScope;
import io.liftandshift.strikebench.util.ResourceNotFoundException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Optional live-trading boundary.
 *
 * <p>This service does not price, size, evaluate, or compose an order. The API controller first
 * runs the exact Practice preview/guardrail/endorsement/readiness path and supplies the frozen
 * result plus a provider-neutral command copied from that approved package. This class persists
 * that identity, consumes the preview atomically, reserves idempotency before the external call,
 * and treats every ambiguous submission as UNKNOWN until the broker ledger reconciles it.</p>
 */
public final class BrokerService {

    public static final String CONFIRM_TEXT = "I understand max loss and this is real money";
    public static final long PREVIEW_TTL_SECONDS = 120;

    private static final String PREVIEWED = "PREVIEWED";
    private static final String SUBMITTING = "SUBMITTING";
    private static final String UNKNOWN = "UNKNOWN";

    private final BrokerageProvider broker;
    private final Db db;
    private final AuditLog audit;
    private final Clock clock;
    private final boolean liveEnabled;

    public BrokerService(BrokerageProvider broker, Db db, AuditLog audit, Clock clock,
                         boolean liveEnabled) {
        this.broker = broker;
        this.db = db;
        this.audit = audit;
        this.clock = clock;
        this.liveEnabled = liveEnabled;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean configured = broker != null && broker.configured();
        out.put("enabled", liveEnabled);
        out.put("configured", configured);
        out.put("connected", liveEnabled && configured && broker.connected());
        out.put("provider", broker == null ? null : broker.name());
        out.put("availability", liveEnabled ? "ENABLED" : "DISABLED");
        if (!liveEnabled) {
            out.put("reason",
                    "Live brokerage is disabled for this installation (BROKER_LIVE_ENABLED=false).");
        }
        return out;
    }

    public boolean liveEnabled() {
        return liveEnabled;
    }

    public String startConnect() {
        return required().startConnect();
    }

    public void verifyConnect(String code) {
        required().verifyConnect(code);
        audit.log(null, null, "BROKER_CONNECTED", "INFO", Map.of("provider", required().name()));
    }

    public List<BrokerageProvider.BrokerAccount> accounts() {
        return required().accounts();
    }

    public BrokerageProvider.BrokerBalance balance(String accountIdKey) {
        return required().balance(requireText(accountIdKey, "accountIdKey"));
    }

    public List<BrokerageProvider.BrokerPosition> positions(String accountIdKey) {
        return required().positions(requireText(accountIdKey, "accountIdKey"));
    }

    /**
     * The immutable policy facts copied from one normalized TradePreviewResponse.
     * approvedPreviewJson is the complete result, not a second summary built here.
     */
    public record ValidatedApproval(
            String approvedPreviewJson,
            String packagePriceFingerprint,
            Long executableNetCents,
            boolean evaluationAvailable,
            String guardrailLevel,
            String endorsementStatus,
            boolean endorsed,
            boolean reviewAllowed,
            boolean confirmAllowed,
            boolean proceedWithoutEndorsement,
            List<String> readinessReasons
    ) {
        public ValidatedApproval {
            if (approvedPreviewJson == null || approvedPreviewJson.isBlank()
                    || !Json.parse(approvedPreviewJson).isObject()) {
                throw new IllegalArgumentException("the approved live-order preview is required");
            }
            packagePriceFingerprint = requireText(packagePriceFingerprint,
                    "package-price fingerprint");
            guardrailLevel = requireText(guardrailLevel, "guardrail level");
            endorsementStatus = requireText(endorsementStatus,
                    "endorsement status");
            readinessReasons = readinessReasons == null
                    ? List.of() : List.copyOf(readinessReasons);
        }

        void requireReady(BrokerageProvider.OrderCommand command) {
            if (!evaluationAvailable) {
                throw new IllegalArgumentException(
                        "Live preview requires a complete package evaluation.");
            }
            if ("BLOCK".equalsIgnoreCase(guardrailLevel)) {
                throw new IllegalArgumentException(
                        "The guardrail result blocks this live package.");
            }
            if (!reviewAllowed || !confirmAllowed) {
                String detail = readinessReasons.isEmpty()
                        ? "The execution result is not confirmable."
                        : String.join(" ", readinessReasons);
                throw new IllegalArgumentException(detail);
            }
            if (!packagePriceFingerprint.equals(command.packagePriceFingerprint())) {
                throw new IllegalArgumentException(
                        "The live command does not match the approved package price.");
            }
            OrderInstruction instruction = command.orderInstruction();
            if (instruction.type() != OrderInstruction.Type.LIMIT
                    || instruction.limitNetCents() == null) {
                throw new IllegalArgumentException(
                        "Live option packages require an explicit signed LIMIT; MARKET is never the default.");
            }
            if (executableNetCents == null
                    || !executableNetCents.equals(instruction.limitNetCents())) {
                throw new IllegalArgumentException(
                        "The live LIMIT must equal the current executable package net; preview again.");
            }
            if (!endorsed && !proceedWithoutEndorsement) {
                throw new IllegalArgumentException(
                        "This package is a comparison, not an endorsement. Explicitly acknowledge "
                                + "proceedWithoutEndorsement to request a live preview.");
            }
        }
    }

    /** Copy one already-normalized Practice package into the provider-neutral wire command. */
    public static BrokerageProvider.OrderCommand command(
            TradeService.OpenRequest request, String packagePriceFingerprint) {
        if (request == null) throw new IllegalArgumentException("approved package is required");
        return new BrokerageProvider.OrderCommand(
                request.symbol(), request.qty(),
                request.legs().stream().map(leg -> new BrokerageProvider.OrderLeg(
                        leg.action(), leg.type(), leg.strike(), leg.expiration(),
                        leg.ratio(), leg.multiplier())).toList(),
                request.orderInstruction(), packagePriceFingerprint);
    }

    public record PreviewOutcome(
            String localId,
            BrokerageProvider.OrderPreview providerPreview,
            String commandFingerprint,
            String status
    ) {}

    public PreviewOutcome preview(String ownerId, String practiceAccountId,
                                  String brokerAccountKey,
                                  BrokerageProvider.OrderCommand command,
                                  ValidatedApproval approval) {
        required();
        String owner = OwnerScope.id(ownerId);
        String practice = requireText(practiceAccountId, "Practice account id");
        String brokerAccount = requireText(brokerAccountKey, "brokerAccountIdKey");
        if (command == null) throw new IllegalArgumentException("live order command is required");
        if (approval == null) throw new IllegalArgumentException("live-order approval is required");
        approval.requireReady(command);

        BrokerageProvider.OrderPreview providerPreview =
                required().previewOrder(brokerAccount, command);
        if (providerPreview == null || providerPreview.previewId() == null
                || providerPreview.previewId().isBlank()) {
            throw new IllegalStateException(
                    "The broker did not return a usable preview id; no live order was reserved.");
        }

        String localId = Ids.order();
        String commandJson = Json.stable(command);
        String commandFingerprint = sha256(commandJson);
        String now = now();
        db.tx(connection -> {
            String scopedOwner = OwnerScope.lock(connection, owner);
            Db.execOn(connection, """
                    INSERT INTO live_orders(
                        id, owner_id, practice_account_id, client_order_id,
                        broker_account_key, symbol, preview_id, broker_order_id, status,
                        payload_json, command_fingerprint, approved_preview_json,
                        provider_preview_json, broker_result_json,
                        proceed_without_endorsement, last_error,
                        consumed_at, submitted_at, reconciled_at, created_at, updated_at)
                    VALUES(?,?,?,'preview-' || ?,?,?,?,NULL,?,
                           ?::jsonb,?,?::jsonb,?::jsonb,NULL,?,NULL,
                           NULL,NULL,NULL,?,?)""",
                    localId, scopedOwner, practice, localId, brokerAccount, command.symbol(),
                    providerPreview.previewId(), PREVIEWED, commandJson, commandFingerprint,
                    approval.approvedPreviewJson(), Json.write(providerPreview),
                    approval.proceedWithoutEndorsement(), now, now);
            return null;
        });
        auditSafe(practice, "LIVE_ORDER_PREVIEWED", "INFO", Map.of(
                "localOrderId", localId,
                "brokerAccountIdKey", brokerAccount,
                "providerPreviewId", providerPreview.previewId(),
                "commandFingerprint", commandFingerprint,
                "packagePriceFingerprint", command.packagePriceFingerprint()));
        return new PreviewOutcome(localId, providerPreview, commandFingerprint, PREVIEWED);
    }

    public record PlaceOutcome(
            String localId,
            String brokerOrderId,
            String status,
            List<String> messages,
            boolean replay,
            boolean reconciled
    ) {
        public PlaceOutcome {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    public PlaceOutcome place(String ownerId, String localPreviewId,
                              String clientOrderId, String confirmText) {
        required();
        String owner = OwnerScope.id(ownerId);
        String localId = requireText(localPreviewId, "local preview id");
        String clientId = requireText(clientOrderId,
                "clientOrderId (required to prevent duplicate submissions)");
        if (clientId.startsWith("preview-")) {
            throw new IllegalArgumentException(
                    "clientOrderId uses a reserved preview identity; generate a fresh submission id.");
        }
        if (!CONFIRM_TEXT.equals(confirmText)) {
            throw new IllegalArgumentException(
                    "Type the exact confirmation to place a live order: \"" + CONFIRM_TEXT + "\"");
        }

        Reservation reservation = reserve(owner, localId, clientId);
        if (!reservation.fresh()) {
            RowState existing = reservation.row();
            if (SUBMITTING.equals(existing.status()) || UNKNOWN.equals(existing.status())) {
                ReconcileOutcome reconciliation = reconcile(owner, existing.id());
                if (reconciliation.lookupStatus() == BrokerageProvider.LookupStatus.FOUND) {
                    LiveOrderView recovered = reconciliation.order();
                    return new PlaceOutcome(recovered.localId(), recovered.brokerOrderId(),
                            recovered.status(), reconciliation.messages(), true, true);
                }
                throw new IllegalStateException(
                        "The original live submission has an uncertain broker outcome. "
                                + "StrikeBench reconciled before retry and will not submit it again. "
                                + "Use this order's reconcile action after the broker ledger updates.");
            }
            return replay(existing);
        }

        RowState row = reservation.row();
        try {
            BrokerageProvider.OrderResult result = required().placeOrder(
                    row.brokerAccountKey(), row.command(), row.providerPreviewId(), clientId);
            if (result == null || result.brokerOrderId() == null
                    || result.brokerOrderId().isBlank()) {
                markUnknown(owner, localId,
                        "The broker response did not include an order id.", result);
                throw new IllegalStateException(
                        "The broker returned an uncertain submission result. The order is recorded "
                                + "as UNKNOWN and will not be submitted again before reconciliation.");
            }
            String status = normalizeStatus(result.status(), "OPEN");
            updatePlaced(owner, localId, result, status);
            auditSafe(row.practiceAccountId(), "LIVE_ORDER_PLACED", "WARN", Map.of(
                    "localOrderId", localId,
                    "brokerOrderId", result.brokerOrderId(),
                    "clientOrderId", clientId,
                    "commandFingerprint", row.commandFingerprint()));
            return new PlaceOutcome(localId, result.brokerOrderId(), status,
                    result.messages(), false, false);
        } catch (BrokerageProvider.OrderNotSubmittedException rejected) {
            String reason = safeMessage(rejected);
            markNotSubmitted(owner, localId, reason);
            auditSafe(row.practiceAccountId(), "LIVE_ORDER_NOT_SUBMITTED", "INFO", Map.of(
                    "localOrderId", localId,
                    "clientOrderId", clientId,
                    "reason", reason,
                    "resolution", "Broker rejection proved that no order was accepted; a fresh preview may be created."));
            return new PlaceOutcome(localId, null, "NOT_SUBMITTED", List.of(reason),
                    false, false);
        } catch (RuntimeException failure) {
            RowState latest = owned(owner, localId, false);
            if (SUBMITTING.equals(latest.status())) {
                markUnknown(owner, localId, safeMessage(failure), null);
            }
            auditSafe(row.practiceAccountId(), "LIVE_ORDER_SUBMISSION_UNKNOWN", "WARN", Map.of(
                    "localOrderId", localId,
                    "clientOrderId", clientId,
                    "reason", safeMessage(failure)));
            throw failure;
        }
    }

    public record LiveOrderView(
            String localId,
            String clientOrderId,
            String practiceAccountId,
            String brokerAccountIdKey,
            String symbol,
            String providerPreviewId,
            String brokerOrderId,
            String status,
            String commandFingerprint,
            JsonNode command,
            JsonNode approvedPreview,
            JsonNode providerPreview,
            JsonNode brokerResult,
            boolean proceedWithoutEndorsement,
            String lastError,
            String createdAt,
            String updatedAt,
            String consumedAt,
            String submittedAt,
            String reconciledAt
    ) {}

    /** Owner-scoped local order ledger; raw provider rows never become a second API authority. */
    public List<LiveOrderView> orders(String ownerId, String brokerAccountKey) {
        required();
        String owner = OwnerScope.id(ownerId);
        if (brokerAccountKey == null || brokerAccountKey.isBlank()) {
            return db.query(SELECT + " WHERE owner_id=? ORDER BY created_at DESC",
                    BrokerService::mapRow, owner).stream().map(RowState::view).toList();
        }
        return db.query(SELECT
                        + " WHERE owner_id=? AND broker_account_key=? ORDER BY created_at DESC",
                BrokerService::mapRow, owner, brokerAccountKey.trim()).stream()
                .map(RowState::view).toList();
    }

    public record ReconcileOutcome(
            LiveOrderView order,
            BrokerageProvider.LookupStatus lookupStatus,
            List<String> messages
    ) {
        public ReconcileOutcome {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    public ReconcileOutcome reconcile(String ownerId, String localOrderId) {
        required();
        String owner = OwnerScope.id(ownerId);
        String localId = requireText(localOrderId, "local order id");
        RowState row = owned(owner, localId, false);
        if (PREVIEWED.equals(row.status())) {
            throw new IllegalStateException(
                    "This live preview has not been submitted and has no broker order to reconcile.");
        }
        BrokerageProvider.OrderLookup lookup;
        try {
            lookup = required().findOrder(row.brokerAccountKey(), row.clientOrderId(),
                    row.brokerOrderId());
            if (lookup == null) {
                lookup = BrokerageProvider.OrderLookup.unavailable(
                        "The broker returned no reconciliation result.");
            }
        } catch (RuntimeException failure) {
            lookup = BrokerageProvider.OrderLookup.unavailable(safeMessage(failure));
        }

        String now = now();
        if (lookup.lookupStatus() == BrokerageProvider.LookupStatus.FOUND) {
            BrokerageProvider.OrderResult result = lookup.order();
            String status = normalizeStatus(result.status(), "UNKNOWN");
            db.exec("""
                    UPDATE live_orders
                    SET broker_order_id=?, status=?, broker_result_json=?::jsonb,
                        last_error=NULL, reconciled_at=?, updated_at=?
                    WHERE id=? AND owner_id=?""",
                    blankToNull(result.brokerOrderId()), status, Json.write(result),
                    now, now, localId, owner);
        } else {
            String reason = lookup.lookupStatus() == BrokerageProvider.LookupStatus.NOT_FOUND
                    ? "The broker ledger does not yet show this reserved submission; automatic "
                        + "resubmission remains disabled."
                    : lookup.messages().isEmpty()
                        ? "Broker reconciliation is unavailable; automatic resubmission remains disabled."
                        : String.join(" ", lookup.messages());
            db.exec("""
                    UPDATE live_orders
                    SET status='UNKNOWN', last_error=?, reconciled_at=?, updated_at=?
                    WHERE id=? AND owner_id=?""",
                    reason, now, now, localId, owner);
        }
        RowState updated = owned(owner, localId, false);
        auditSafe(updated.practiceAccountId(), "LIVE_ORDER_RECONCILED", "INFO", Map.of(
                "localOrderId", localId,
                "lookupStatus", lookup.lookupStatus().name(),
                "status", updated.status()));
        return new ReconcileOutcome(updated.view(), lookup.lookupStatus(), lookup.messages());
    }

    /**
     * Cancel takes StrikeBench's owner-scoped local id. Account and broker-order identity come
     * only from the persisted row, so another owner's broker order cannot be named through input.
     */
    public LiveOrderView cancel(String ownerId, String localOrderId) {
        required();
        String owner = OwnerScope.id(ownerId);
        String localId = requireText(localOrderId, "local order id");
        RowState row = db.tx(connection -> {
            OwnerScope.lock(connection, owner);
            RowState found = owned(connection, owner, localId, true);
            if (found.brokerOrderId() == null || found.brokerOrderId().isBlank()) {
                throw new IllegalStateException(
                        "This live order has no reconciled broker order id and cannot be cancelled.");
            }
            if (found.status().startsWith("CANCEL_")) {
                throw new IllegalStateException(
                        "A cancel is already pending or uncertain; reconcile the broker order "
                                + "instead of sending another cancel request.");
            }
            if (List.of("FILLED", "EXECUTED", "CANCELLED", "CANCELED", "REJECTED", "EXPIRED")
                    .contains(found.status())) {
                throw new IllegalStateException(
                        "This broker order is already terminal and cannot be cancelled.");
            }
            Db.execOn(connection, """
                    UPDATE live_orders SET status='CANCEL_SUBMITTING', updated_at=?
                    WHERE id=? AND owner_id=?""", now(), localId, owner);
            return found;
        });
        try {
            required().cancelOrder(row.brokerAccountKey(), row.brokerOrderId());
            db.exec("""
                    UPDATE live_orders SET status='CANCEL_REQUESTED', updated_at=?
                    WHERE id=? AND owner_id=?""", now(), localId, owner);
            auditSafe(row.practiceAccountId(), "LIVE_ORDER_CANCEL_REQUESTED", "WARN", Map.of(
                    "localOrderId", localId,
                    "brokerOrderId", row.brokerOrderId(),
                    "note", "Cancel requested; reconcile the terminal broker status."));
        } catch (RuntimeException failure) {
            db.exec("""
                    UPDATE live_orders SET status='CANCEL_UNKNOWN', last_error=?, updated_at=?
                    WHERE id=? AND owner_id=?""",
                    safeMessage(failure), now(), localId, owner);
            throw failure;
        }
        return owned(owner, localId, false).view();
    }

    private Reservation reserve(String owner, String localId, String clientId) {
        return db.tx(connection -> {
            OwnerScope.lock(connection, owner);
            List<RowState> clientRows = Db.queryOn(connection,
                    SELECT + " WHERE owner_id=? AND client_order_id=? FOR UPDATE",
                    BrokerService::mapRow, owner, clientId);
            if (!clientRows.isEmpty()) {
                RowState existing = clientRows.getFirst();
                if (!existing.id().equals(localId)) {
                    throw new IllegalStateException("clientOrderId '" + clientId
                            + "' is already reserved for a different live order.");
                }
                return new Reservation(existing, false);
            }

            RowState preview = owned(connection, owner, localId, true);
            if (!PREVIEWED.equals(preview.status())) {
                if (clientId.equals(preview.clientOrderId())) {
                    return new Reservation(preview, false);
                }
                throw new IllegalStateException(
                        "This live preview was already consumed; use its recorded clientOrderId.");
            }
            Instant created = Instant.parse(preview.createdAt());
            if (Duration.between(created, clock.instant()).getSeconds() > PREVIEW_TTL_SECONDS) {
                throw new IllegalArgumentException("Preview has expired (" + PREVIEW_TTL_SECONDS
                        + "s); preview again against the current package price.");
            }
            String now = now();
            int updated = Db.execOn(connection, """
                    UPDATE live_orders
                    SET client_order_id=?, status='SUBMITTING',
                        consumed_at=?, submitted_at=?, updated_at=?, last_error=NULL
                    WHERE id=? AND owner_id=? AND status='PREVIEWED'""",
                    clientId, now, now, now, localId, owner);
            if (updated != 1) {
                throw new IllegalStateException(
                        "The live preview was consumed by another request.");
            }
            return new Reservation(owned(connection, owner, localId, false), true);
        });
    }

    private PlaceOutcome replay(RowState row) {
        BrokerageProvider.OrderResult result = row.brokerResultJson() == null
                ? null : Json.read(row.brokerResultJson(), BrokerageProvider.OrderResult.class);
        List<String> messages = result == null
                ? row.lastError() == null || row.lastError().isBlank()
                    ? List.of() : List.of(row.lastError())
                : result.messages();
        return new PlaceOutcome(row.id(), row.brokerOrderId(), row.status(),
                messages, true, false);
    }

    private void updatePlaced(String owner, String localId,
                              BrokerageProvider.OrderResult result, String status) {
        db.exec("""
                UPDATE live_orders
                SET broker_order_id=?, status=?, broker_result_json=?::jsonb,
                    last_error=NULL, updated_at=?
                WHERE id=? AND owner_id=?""",
                result.brokerOrderId(), status, Json.write(result), now(), localId, owner);
    }

    private void markUnknown(String owner, String localId, String reason,
                             BrokerageProvider.OrderResult result) {
        db.exec("""
                UPDATE live_orders
                SET status='UNKNOWN', broker_order_id=COALESCE(?,broker_order_id),
                    broker_result_json=COALESCE(?::jsonb,broker_result_json),
                    last_error=?, updated_at=?
                WHERE id=? AND owner_id=?""",
                result == null ? null : blankToNull(result.brokerOrderId()),
                result == null ? null : Json.write(result),
                reason, now(), localId, owner);
    }

    private void markNotSubmitted(String owner, String localId, String reason) {
        db.exec("""
                UPDATE live_orders
                SET status='NOT_SUBMITTED', last_error=?, reconciled_at=?, updated_at=?
                WHERE id=? AND owner_id=? AND status='SUBMITTING'""",
                reason, now(), now(), localId, owner);
    }

    private RowState owned(String owner, String localId, boolean lock) {
        return db.with(connection -> owned(connection, owner, localId, lock));
    }

    private static RowState owned(Connection connection, String owner, String localId,
                                  boolean lock) throws SQLException {
        List<RowState> rows = Db.queryOn(connection,
                SELECT + " WHERE id=? AND owner_id=?" + (lock ? " FOR UPDATE" : ""),
                BrokerService::mapRow, localId, owner);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Live order not found: " + localId);
        return rows.getFirst();
    }

    private BrokerageProvider required() {
        if (!liveEnabled) {
            throw new IllegalStateException(
                    "Live brokerage is disabled for this installation. "
                            + "Set BROKER_LIVE_ENABLED=true and restart StrikeBench.");
        }
        if (broker == null || !broker.configured()) {
            throw new IllegalStateException(
                    "No brokerage is configured (set ETRADE_CONSUMER_KEY / ETRADE_CONSUMER_SECRET)");
        }
        return broker;
    }

    private void auditSafe(String accountId, String action, String level,
                           Map<String, Object> detail) {
        try {
            audit.log(accountId, null, action, level, detail);
        } catch (RuntimeException ignored) {
            // The live mutation or uncertainty result is already durable. An audit failure must
            // never tell a caller that an external order failed and invite a duplicate submission.
        }
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String normalizeStatus(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safeMessage(Throwable failure) {
        if (failure == null || failure.getMessage() == null
                || failure.getMessage().isBlank()) {
            return "The broker request failed before a definitive order result was received.";
        }
        return failure.getMessage();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static JsonNode node(String value) {
        return value == null ? null : Json.parse(value);
    }

    private record Reservation(RowState row, boolean fresh) {}

    private static final String SELECT = """
            SELECT id,owner_id,practice_account_id,client_order_id,broker_account_key,symbol,
                   preview_id,broker_order_id,status,payload_json::text payload_json,
                   command_fingerprint,approved_preview_json::text approved_preview_json,
                   provider_preview_json::text provider_preview_json,
                   broker_result_json::text broker_result_json,proceed_without_endorsement,
                   last_error,
                   created_at::text created_at,updated_at::text updated_at,
                   consumed_at::text consumed_at,submitted_at::text submitted_at,
                   reconciled_at::text reconciled_at
            FROM live_orders""";

    private record RowState(
            String id,
            String ownerId,
            String practiceAccountId,
            String clientOrderId,
            String brokerAccountKey,
            String symbol,
            String providerPreviewId,
            String brokerOrderId,
            String status,
            String payloadJson,
            String commandFingerprint,
            String approvedPreviewJson,
            String providerPreviewJson,
            String brokerResultJson,
            boolean proceedWithoutEndorsement,
            String lastError,
            String createdAt,
            String updatedAt,
            String consumedAt,
            String submittedAt,
            String reconciledAt
    ) {
        BrokerageProvider.OrderCommand command() {
            return Json.read(payloadJson, BrokerageProvider.OrderCommand.class);
        }

        LiveOrderView view() {
            return new LiveOrderView(id, clientOrderId, practiceAccountId, brokerAccountKey,
                    symbol, providerPreviewId, brokerOrderId, status, commandFingerprint,
                    node(payloadJson), node(approvedPreviewJson), node(providerPreviewJson),
                    node(brokerResultJson), proceedWithoutEndorsement, lastError,
                    createdAt, updatedAt, consumedAt,
                    submittedAt, reconciledAt);
        }
    }

    private static RowState mapRow(Db.Row row) {
        return new RowState(
                row.str("id"), row.str("owner_id"), row.str("practice_account_id"),
                row.str("client_order_id"), row.str("broker_account_key"), row.str("symbol"),
                row.str("preview_id"), row.str("broker_order_id"), row.str("status"),
                row.str("payload_json"), row.str("command_fingerprint"),
                row.str("approved_preview_json"), row.str("provider_preview_json"),
                row.str("broker_result_json"), row.bool("proceed_without_endorsement"),
                row.str("last_error"),
                row.str("created_at"), row.str("updated_at"), row.str("consumed_at"),
                row.str("submitted_at"), row.str("reconciled_at"));
    }
}
