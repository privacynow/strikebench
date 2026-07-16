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
        assertThat(accountAfter.get("reservedCents").asLong()).isZero();
        assertThat(accountAfter.get("buyingPowerCents").asLong())
                .isEqualTo(accountAfter.get("cashCents").asLong());

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
