package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PositionTransformationApiTest {
    private static ApiServer server;
    private static Javalin app;
    private static HttpClient http;
    private static String base;
    private static Db db;

    @BeforeAll
    static void start() {
        Map<String, String> config = new HashMap<>(TestDb.freshConfig());
        config.put("FIXTURES_ONLY", "true");
        AppConfig cfg = new AppConfig(config);
        db = Db.forConfig(cfg);
        server = ApiServer.create(cfg, Clock.fixed(Instant.parse("2026-07-15T15:30:00Z"), ZoneOffset.UTC));
        app = server.start(0);
        base = "http://localhost:" + app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop();
        if (db != null) db.close();
    }

    @Test
    void canonicalClosePreservesLedgerBehaviorAndFreezesTheBeforeAfterReceipt() throws Exception {
        String expiration = Json.parse(get("/api/research/AAPL/expirations").body())
                .at("/expirations/2").asText();
        String order = """
                {"symbol":"AAPL","strategy":"CREDIT_PUT_SPREAD","qty":1,
                 "thesis":"bullish","horizon":"month","riskMode":"conservative",
                 "source":"POSITION_TRANSFORMATION_TEST","fillNature":"PROPOSED",
                 "legs":[
                   {"action":"SELL","type":"PUT","strike":"250","expiration":"%s","ratio":1,
                    "multiplier":100,"positionEffect":"OPEN"},
                   {"action":"BUY","type":"PUT","strike":"245","expiration":"%s","ratio":1,
                    "multiplier":100,"positionEffect":"OPEN"}]}
                """.formatted(expiration, expiration);
        JsonNode created = Json.parse(createAcknowledged(order).body());
        String tradeId = created.at("/trade/id").asText();
        JsonNode accountBefore = Json.parse(get("/api/account").body()).get("account");
        long tradeReserve = outstandingReserve(tradeId);

        ObjectNode request = Json.MAPPER.createObjectNode();
        request.put("source", "PRACTICE_TRADE");
        request.put("sourceId", tradeId);
        request.put("action", "CLOSE");
        HttpResponse<String> previewResponse = post("/api/position-transformations/preview", request.toString());
        assertThat(previewResponse.statusCode()).isEqualTo(200);
        JsonNode preview = Json.parse(previewResponse.body());
        assertThat(preview.at("/transformation/action").asText()).isEqualTo("CLOSE");
        assertThat(preview.at("/transformation/beforeIdentity/family").asText())
                .isEqualTo("CREDIT_PUT_SPREAD");
        assertThat(preview.at("/transformation/afterIdentity/label").asText())
                .isEqualTo("Cash / no position");
        assertThat(preview.at("/transformation/beforeRisk/maxLossCents").asLong())
                .isEqualTo(created.at("/trade/maxLossCents").asLong());
        assertThat(preview.at("/transformation/beforeRisk/reserveCents").asLong())
                .isEqualTo(tradeReserve);
        assertThat(preview.at("/previewToken").asText()).contains(".");
        long closingCash = preview.get("closingCashCents").asLong();
        long closingFees = preview.get("closingFeesCents").asLong();

        request.put("previewToken", preview.get("previewToken").asText());
        HttpResponse<String> appliedResponse = post("/api/position-transformations/apply", request.toString());
        assertThat(appliedResponse.statusCode()).isEqualTo(200);
        JsonNode applied = Json.parse(appliedResponse.body());
        String receiptId = applied.get("receiptId").asText();
        assertThat(applied.at("/trade/status").asText()).isEqualTo("CLOSED");
        assertThat(applied.at("/transformation/fingerprint").asText())
                .isEqualTo(preview.at("/transformation/fingerprint").asText());

        JsonNode accountAfter = Json.parse(get("/api/account").body()).get("account");
        assertThat(accountAfter.get("cashCents").asLong()).isEqualTo(
                accountBefore.get("cashCents").asLong() + closingCash - closingFees);
        assertThat(accountAfter.get("reservedCents").asLong())
                .isEqualTo(accountBefore.get("reservedCents").asLong() - tradeReserve);
        assertThat(accountAfter.get("buyingPowerCents").asLong())
                .isEqualTo(accountAfter.get("cashCents").asLong() - accountAfter.get("reservedCents").asLong());

        assertThat(db.query("SELECT kind,transformation_action,preview_fingerprint FROM position_receipt WHERE id=?",
                row -> row.str("kind") + "|" + row.str("transformation_action") + "|" + row.str("preview_fingerprint"),
                receiptId)).containsExactly("TRANSFORMATION|CLOSE|" + preview.at("/transformation/fingerprint").asText());
        assertThat(db.query("SELECT position_phase,COUNT(*) n FROM position_receipt_leg WHERE receipt_id=? "
                        + "GROUP BY position_phase ORDER BY position_phase",
                row -> row.str("position_phase") + "|" + row.lng("n"), receiptId))
                .containsExactly("BEFORE|2");
        assertThat(db.query("SELECT COUNT(*) n FROM ledger WHERE trade_id=? AND type='PREMIUM_CLOSE'",
                row -> row.lng("n"), tradeId)).containsExactly(1L);
    }

    @Test
    void applyRejectsATamperedPreviewWithoutChangingTheTrade() throws Exception {
        String expiration = Json.parse(get("/api/research/AAPL/expirations").body())
                .at("/expirations/2").asText();
        String order = """
                {"symbol":"AAPL","strategy":"DEBIT_CALL_SPREAD","qty":1,
                 "thesis":"bullish","horizon":"month","riskMode":"conservative",
                 "source":"POSITION_TRANSFORMATION_TEST","fillNature":"PROPOSED",
                 "legs":[
                   {"action":"BUY","type":"CALL","strike":"255","expiration":"%s","ratio":1,
                    "multiplier":100,"positionEffect":"OPEN"},
                   {"action":"SELL","type":"CALL","strike":"260","expiration":"%s","ratio":1,
                    "multiplier":100,"positionEffect":"OPEN"}]}
                """.formatted(expiration, expiration);
        String tradeId = Json.parse(createAcknowledged(order).body()).at("/trade/id").asText();
        ObjectNode request = Json.MAPPER.createObjectNode();
        request.put("source", "PRACTICE_TRADE");
        request.put("sourceId", tradeId);
        request.put("action", "CLOSE");
        JsonNode preview = Json.parse(post("/api/position-transformations/preview", request.toString()).body());
        request.put("previewToken", preview.get("previewToken").asText() + "00");

        HttpResponse<String> response = post("/api/position-transformations/apply", request.toString());
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(Json.parse(response.body()).get("detail").asText()).contains("stale");
        assertThat(Json.parse(get("/api/trades/" + tradeId).body()).at("/trade/status").asText())
                .isEqualTo("ACTIVE");
    }

    @Test
    void canonicalRollClosesAndReopensAtomicallyWithOneFrozenBeforeAfterReceipt() throws Exception {
        JsonNode expirations = Json.parse(get("/api/research/AAPL/expirations").body()).get("expirations");
        String near = expirations.get(2).asText();
        String farther = expirations.get(5).asText();
        ObjectNode opening = creditPutSpread(near, 1, "POSITION_TRANSFORMATION_ROLL_TEST");
        JsonNode created = Json.parse(createAcknowledged(opening.toString()).body());
        String oldTradeId = created.at("/trade/id").asText();
        JsonNode accountBefore = Json.parse(get("/api/account").body()).get("account");
        long oldReserve = outstandingReserve(oldTradeId);

        ObjectNode request = Json.MAPPER.createObjectNode();
        request.put("source", "PRACTICE_TRADE");
        request.put("sourceId", oldTradeId);
        request.put("action", "ROLL");
        request.set("after", creditPutSpread(farther, 1, "POSITION_TRANSFORMATION_ROLL_TEST"));
        HttpResponse<String> previewResponse = post("/api/position-transformations/preview", request.toString());
        assertThat(previewResponse.statusCode()).isEqualTo(200);
        JsonNode preview = Json.parse(previewResponse.body());
        assertThat(preview.at("/transformation/action").asText()).isEqualTo("ROLL");
        assertThat(preview.at("/transformation/warnings/0").asText()).contains("realizes");
        assertThat(preview.at("/after/preview/ok").asBoolean()).isTrue();
        assertThat(preview.at("/transformation/afterObligations/expirations/0").asText()).isEqualTo(farther);
        long projectedCash = accountBefore.get("cashCents").asLong()
                + preview.get("closingCashCents").asLong() - preview.get("closingFeesCents").asLong();
        long projectedReserve = accountBefore.get("reservedCents").asLong() - oldReserve;
        assertThat(preview.at("/after/preview/cashBeforeCents").asLong()).isEqualTo(projectedCash);
        assertThat(preview.at("/after/preview/reservedBeforeCents").asLong()).isEqualTo(projectedReserve);
        assertThat(preview.at("/after/preview/buyingPowerBeforeCents").asLong())
                .isEqualTo(projectedCash - projectedReserve);

        acknowledgeAfter(request, preview);
        request.put("previewToken", preview.get("previewToken").asText());
        HttpResponse<String> appliedResponse = post("/api/position-transformations/apply", request.toString());
        assertThat(appliedResponse.statusCode()).isEqualTo(200);
        JsonNode applied = Json.parse(appliedResponse.body());
        String receiptId = applied.get("receiptId").asText();
        String replacementTradeId = applied.at("/trade/id").asText();
        assertThat(replacementTradeId).isNotEqualTo(oldTradeId);
        assertThat(applied.at("/trade/status").asText()).isEqualTo("ACTIVE");
        assertThat(applied.at("/trade/legs/0/expiration").asText()).isEqualTo(farther);
        assertThat(Json.parse(get("/api/trades/" + oldTradeId).body()).at("/trade/status").asText())
                .isEqualTo("CLOSED");

        JsonNode accountAfter = Json.parse(get("/api/account").body()).get("account");
        long expectedCash = accountBefore.get("cashCents").asLong()
                + preview.get("closingCashCents").asLong() - preview.get("closingFeesCents").asLong()
                + preview.at("/after/preview/entryNetPremiumCents").asLong()
                - preview.at("/after/preview/feesOpenCents").asLong();
        assertThat(accountAfter.get("cashCents").asLong()).isEqualTo(expectedCash);
        assertThat(accountAfter.get("reservedCents").asLong())
                .isEqualTo(accountBefore.get("reservedCents").asLong() - oldReserve
                        + preview.at("/after/preview/reserveCents").asLong());
        assertThat(db.query("SELECT status FROM trades WHERE id=?", row -> row.str("status"), replacementTradeId))
                .containsExactly("ACTIVE");
        assertThat(db.query("SELECT position_phase,COUNT(*) n FROM position_receipt_leg WHERE receipt_id=? "
                        + "GROUP BY position_phase ORDER BY position_phase",
                row -> row.str("position_phase") + "|" + row.lng("n"), receiptId))
                .containsExactly("AFTER|2", "BEFORE|2");
        assertThat(db.query("SELECT transformation_action,position_state,practice_trade_id FROM position_receipt WHERE id=?",
                row -> row.str("transformation_action") + "|" + row.str("position_state") + "|" + row.str("practice_trade_id"),
                receiptId)).containsExactly("ROLL|OPEN|" + replacementTradeId);
        assertThat(db.query("SELECT type FROM ledger WHERE trade_id=? ORDER BY id",
                row -> row.str("type"), oldTradeId)).contains("PREMIUM_CLOSE", "RESERVE_RELEASE");
        assertThat(db.query("SELECT type FROM ledger WHERE trade_id=? ORDER BY id",
                row -> row.str("type"), replacementTradeId)).contains("PREMIUM_OPEN", "RESERVE_HOLD");
    }

    @Test
    void partialCloseKeepsTheSameTradeAndExactResidualBasisThroughTheFinalClose() throws Exception {
        String expiration = Json.parse(get("/api/research/AAPL/expirations").body())
                .at("/expirations/2").asText();
        JsonNode created = Json.parse(createAcknowledged(
                creditPutSpread(expiration, 5, "POSITION_TRANSFORMATION_PARTIAL_TEST").toString()).body());
        String tradeId = created.at("/trade/id").asText();
        long openingEntry = created.at("/trade/entryNetPremiumCents").asLong();
        long openingFees = created.at("/trade/feesOpenCents").asLong();
        long openingMaxLoss = created.at("/trade/maxLossCents").asLong();
        long reserveBefore = outstandingReserve(tradeId);
        JsonNode accountBefore = Json.parse(get("/api/account").body()).get("account");

        ObjectNode request = Json.MAPPER.createObjectNode();
        request.put("source", "PRACTICE_TRADE");
        request.put("sourceId", tradeId);
        request.put("action", "PARTIAL_CLOSE");
        request.put("closeQuantity", 2);
        JsonNode preview = Json.parse(post("/api/position-transformations/preview", request.toString()).body());
        assertThat(preview.at("/transformation/action").asText()).isEqualTo("PARTIAL_CLOSE");
        assertThat(preview.at("/transformation/warnings/0").asText())
                .contains("2 of 5 packages close").contains("3 survive");
        assertThat(preview.at("/transformation/afterRisk/reserveCents").asLong())
                .isEqualTo(remainingAllocation(reserveBefore, 5, 2));
        assertThat(preview.at("/transformation/afterRisk/maxLossCents").asLong())
                .isEqualTo(remainingAllocation(openingMaxLoss, 5, 2));
        long closingCash = preview.get("closingCashCents").asLong();
        long closingFees = preview.get("closingFeesCents").asLong();
        long actionRealized = preview.at("/transformation/realizedClosingCents").asLong();

        request.put("previewToken", preview.get("previewToken").asText());
        HttpResponse<String> appliedResponse = post("/api/position-transformations/apply", request.toString());
        assertThat(appliedResponse.statusCode()).isEqualTo(200);
        JsonNode applied = Json.parse(appliedResponse.body());
        String receiptId = applied.get("receiptId").asText();
        assertThat(applied.at("/trade/id").asText()).isEqualTo(tradeId);
        assertThat(applied.at("/trade/status").asText()).isEqualTo("ACTIVE");
        assertThat(applied.at("/trade/qty").asInt()).isEqualTo(3);
        assertThat(applied.get("actionRealizedPnlCents").asLong()).isEqualTo(actionRealized);
        assertThat(applied.get("realizedPnlToDateCents").asLong()).isEqualTo(actionRealized);
        assertThat(applied.at("/trade/entryNetPremiumCents").asLong())
                .isEqualTo(remainingAllocation(openingEntry, 5, 2));
        assertThat(applied.at("/trade/feesOpenCents").asLong())
                .isEqualTo(remainingAllocation(openingFees, 5, 2));
        assertThat(applied.at("/trade/maxLossCents").asLong())
                .isEqualTo(remainingAllocation(openingMaxLoss, 5, 2));

        JsonNode accountAfterPartial = Json.parse(get("/api/account").body()).get("account");
        assertThat(accountAfterPartial.get("cashCents").asLong())
                .isEqualTo(accountBefore.get("cashCents").asLong() + closingCash - closingFees);
        assertThat(outstandingReserve(tradeId)).isEqualTo(remainingAllocation(reserveBefore, 5, 2));
        assertThat(db.query("SELECT position_state,transformation_action,practice_trade_id FROM position_receipt WHERE id=?",
                row -> row.str("position_state") + "|" + row.str("transformation_action") + "|"
                        + row.str("practice_trade_id"), receiptId))
                .containsExactly("PARTIALLY_CLOSED|PARTIAL_CLOSE|" + tradeId);
        assertThat(db.query("SELECT position_phase || ':' || SUM(quantity) v FROM position_receipt_leg "
                        + "WHERE receipt_id=? GROUP BY position_phase ORDER BY position_phase",
                row -> row.str("v"), receiptId)).containsExactly("AFTER:6", "BEFORE:10");

        ObjectNode close = Json.MAPPER.createObjectNode();
        close.put("source", "PRACTICE_TRADE");
        close.put("sourceId", tradeId);
        close.put("action", "CLOSE");
        JsonNode closePreview = Json.parse(post("/api/position-transformations/preview", close.toString()).body());
        long remainingAction = closePreview.get("actionRealizedPnlCents").asLong();
        assertThat(closePreview.get("realizedPnlToDateCents").asLong())
                .isEqualTo(actionRealized + remainingAction);
        assertThat(closePreview.at("/transformation/realizedClosingCents").asLong())
                .isEqualTo(actionRealized + remainingAction);
        close.put("previewToken", closePreview.get("previewToken").asText());
        JsonNode closed = Json.parse(post("/api/position-transformations/apply", close.toString()).body());
        assertThat(closed.at("/trade/status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("actionRealizedPnlCents").asLong()).isEqualTo(remainingAction);
        assertThat(closed.get("realizedPnlToDateCents").asLong())
                .isEqualTo(actionRealized + remainingAction);
        assertThat(closed.at("/trade/realizedPnlCents").asLong())
                .isEqualTo(actionRealized + remainingAction);
        assertThat(db.query("SELECT COUNT(*) n FROM ledger WHERE trade_id=? AND type='PREMIUM_CLOSE'",
                row -> row.lng("n"), tradeId)).containsExactly(2L);
        assertThat(outstandingReserve(tradeId)).isZero();
    }

    @Test
    void partialCloseTokenBindsTheServerDerivedSurvivingQuantity() throws Exception {
        String expiration = Json.parse(get("/api/research/AAPL/expirations").body())
                .at("/expirations/2").asText();
        String tradeId = Json.parse(createAcknowledged(
                creditPutSpread(expiration, 4, "POSITION_TRANSFORMATION_PARTIAL_TAMPER_TEST").toString()).body())
                .at("/trade/id").asText();
        ObjectNode request = Json.MAPPER.createObjectNode();
        request.put("source", "PRACTICE_TRADE");
        request.put("sourceId", tradeId);
        request.put("action", "PARTIAL_CLOSE");
        request.put("closeQuantity", 1);
        JsonNode preview = Json.parse(post("/api/position-transformations/preview", request.toString()).body());
        request.put("previewToken", preview.get("previewToken").asText());
        request.put("closeQuantity", 2);

        HttpResponse<String> response = post("/api/position-transformations/apply", request.toString());
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(Json.parse(response.body()).get("detail").asText()).contains("stale");
        assertThat(Json.parse(get("/api/trades/" + tradeId).body()).at("/trade/qty").asInt()).isEqualTo(4);
        assertThat(db.query("SELECT COUNT(*) n FROM ledger WHERE trade_id=? AND type='PREMIUM_CLOSE'",
                row -> row.lng("n"), tradeId)).containsExactly(0L);
    }

    @Test
    void callerCannotRelabelABrokerRecordAsAPracticeTransformation() throws Exception {
        String expiration = Json.parse(get("/api/research/AAPL/expirations").body())
                .at("/expirations/2").asText();
        String tradeId = Json.parse(createAcknowledged(
                creditPutSpread(expiration, 3, "POSITION_TRANSFORMATION_ORIGIN_TEST").toString()).body())
                .at("/trade/id").asText();
        db.exec("UPDATE trades SET origin='EXTERNAL',data_provenance='BROKER' WHERE id=?", tradeId);
        long ledgerRows = db.query("SELECT COUNT(*) n FROM ledger WHERE trade_id=?",
                row -> row.lng("n"), tradeId).getFirst();

        ObjectNode request = Json.MAPPER.createObjectNode();
        request.put("source", "PRACTICE_TRADE");
        request.put("sourceId", tradeId);
        request.put("action", "PARTIAL_CLOSE");
        request.put("closeQuantity", 1);
        HttpResponse<String> response = post("/api/position-transformations/preview", request.toString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.parse(response.body()).get("detail").asText())
                .contains("tracked-account").contains("Practice");
        assertThat(Json.parse(get("/api/trades/" + tradeId).body()).at("/trade/qty").asInt()).isEqualTo(3);
        assertThat(db.query("SELECT COUNT(*) n FROM ledger WHERE trade_id=?",
                row -> row.lng("n"), tradeId)).containsExactly(ledgerRows);
    }

    @Test
    void blockedRollReplacementLeavesTheOpenPositionAndLedgerUntouched() throws Exception {
        JsonNode expirations = Json.parse(get("/api/research/AAPL/expirations").body()).get("expirations");
        String near = expirations.get(2).asText();
        String farther = expirations.get(5).asText();
        String tradeId = Json.parse(createAcknowledged(
                creditPutSpread(near, 1, "POSITION_TRANSFORMATION_ROLL_ROLLBACK_TEST").toString()).body())
                .at("/trade/id").asText();
        long closeRowsBefore = db.query("SELECT COUNT(*) n FROM ledger WHERE trade_id=? AND type='PREMIUM_CLOSE'",
                row -> row.lng("n"), tradeId).getFirst();
        long receiptsBefore = db.query("SELECT COUNT(*) n FROM position_receipt",
                row -> row.lng("n")).getFirst();

        ObjectNode request = Json.MAPPER.createObjectNode();
        request.put("source", "PRACTICE_TRADE");
        request.put("sourceId", tradeId);
        request.put("action", "ROLL");
        ObjectNode unaffordable = creditPutSpread(farther, 100, "POSITION_TRANSFORMATION_ROLL_ROLLBACK_TEST");
        ((ObjectNode) unaffordable.withArray("legs").get(0)).put("strike", "270");
        ((ObjectNode) unaffordable.withArray("legs").get(1)).put("strike", "245");
        request.set("after", unaffordable);
        JsonNode preview = Json.parse(post("/api/position-transformations/preview", request.toString()).body());
        assertThat(preview.at("/transformation/applicable").asBoolean()).isFalse();
        assertThat(preview.at("/after/guardrails/blockReasons").toString().toLowerCase())
                .contains("buying power");
        acknowledgeAfter(request, preview);
        request.put("previewToken", preview.get("previewToken").asText());

        HttpResponse<String> response = post("/api/position-transformations/apply", request.toString());
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(Json.parse(response.body()).toString().toLowerCase()).contains("buying power");
        assertThat(Json.parse(get("/api/trades/" + tradeId).body()).at("/trade/status").asText())
                .isEqualTo("ACTIVE");
        assertThat(db.query("SELECT COUNT(*) n FROM ledger WHERE trade_id=? AND type='PREMIUM_CLOSE'",
                row -> row.lng("n"), tradeId)).containsExactly(closeRowsBefore);
        assertThat(db.query("SELECT COUNT(*) n FROM position_receipt", row -> row.lng("n")))
                .containsExactly(receiptsBefore);
    }

    private static ObjectNode creditPutSpread(String expiration, int qty, String source) {
        return (ObjectNode) Json.parse("""
                {"symbol":"AAPL","strategy":"CREDIT_PUT_SPREAD","qty":%d,
                 "thesis":"bullish","horizon":"month","riskMode":"conservative",
                 "source":"%s","fillNature":"PROPOSED",
                 "legs":[
                   {"action":"SELL","type":"PUT","strike":"250","expiration":"%s","ratio":1,
                    "multiplier":100,"positionEffect":"OPEN"},
                   {"action":"BUY","type":"PUT","strike":"245","expiration":"%s","ratio":1,
                    "multiplier":100,"positionEffect":"OPEN"}]}
                """.formatted(qty, source, expiration, expiration));
    }

    private static long outstandingReserve(String tradeId) {
        return db.query("SELECT COALESCE(SUM(amount_cents),0) n FROM ledger WHERE trade_id=? "
                        + "AND type IN ('RESERVE_HOLD','RESERVE_RELEASE')",
                row -> row.lng("n"), tradeId).getFirst();
    }

    private static long remainingAllocation(long total, long packageQuantity, long removedQuantity) {
        long base = total / packageQuantity;
        long remainder = total % packageQuantity;
        long extraUnits = Math.min(removedQuantity, Math.abs(remainder));
        long removed = base * removedQuantity + (remainder < 0 ? -extraUnits : extraUnits);
        return total - removed;
    }

    private static void acknowledgeAfter(ObjectNode request, JsonNode preview) {
        JsonNode required = preview.at("/after/requiredAcks");
        if (!required.isArray() || required.isEmpty()) return;
        ObjectNode after = (ObjectNode) request.get("after");
        var acknowledgments = after.putArray("acknowledgedRisks");
        required.forEach(value -> acknowledgments.add(value.get("id").asText()));
        after.put("ackToken", preview.at("/after/ackToken").asText());
    }

    private static HttpResponse<String> createAcknowledged(String body) throws Exception {
        JsonNode preview = Json.parse(post("/api/trades/preview", body).body());
        if (!preview.has("requiredAcks") || preview.get("requiredAcks").isEmpty()) return post("/api/trades", body);
        ObjectNode request = (ObjectNode) Json.parse(body);
        var acknowledgments = request.putArray("acknowledgedRisks");
        preview.get("requiredAcks").forEach(value -> acknowledgments.add(value.get("id").asText()));
        request.put("ackToken", preview.get("ackToken").asText());
        return post("/api/trades", request.toString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
