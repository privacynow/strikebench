package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.paper.BrokerStatementParser;
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

import static org.assertj.core.api.Assertions.assertThat;

class BrokerImportApiTest {
    private static ApiServer server;
    private static HttpClient http;
    private static String base;

    @BeforeAll static void start() {
        var config = new HashMap<>(TestDb.freshConfig());
        config.put("FIXTURES_ONLY", "true");
        server = ApiServer.create(new AppConfig(config),
                Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC));
        Javalin app = server.start(0);
        base = "http://localhost:" + app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterAll static void stop() { if (server != null) server.stop(); }

    @Test
    void pendingCommandsUseOneCanonicalRouteAndBoundedOwnerScopedQueue() throws Exception {
        JsonNode account = json(post("/api/portfolio/accounts", """
                {"name":"Broker API Book","accountType":"ROTH_IRA","broker":"Vanguard",
                 "lotMethod":"FIFO","openingCashCents":1000000}
                """));
        String accountId = account.get("id").asText();
        String rawAccount = "IRA ending 7788";
        String statement = "confirmation_number,account,date,symbol,type,action,position,quantity,multiplier,price,net_amount,fees,leg\n"
                + "api-pending," + rawAccount + ",2026-07-01,MU,stock,buy,open,1,1,,-100,0,0\n";

        ObjectNode previewRequest = Json.obj();
        previewRequest.put("parserVersion", BrokerStatementParser.VERSION);
        previewRequest.put("sourceSystem", "VANGUARD");
        previewRequest.put("sourceAccount", rawAccount);
        previewRequest.put("text", statement);
        JsonNode preview = json(post("/api/portfolio/broker-imports/preview", previewRequest.toString()));
        assertThat(preview.toString()).doesNotContain(rawAccount);

        JsonNode parsed = preview.get("parsed");
        JsonNode sourceGroup = parsed.get("groups").get(0);
        ObjectNode confirm = Json.obj();
        confirm.put("parserVersion", parsed.get("parserVersion").asText());
        confirm.put("previewFingerprint", parsed.get("previewFingerprint").asText());
        confirm.put("sourceSystem", "VANGUARD");
        confirm.put("sourceAccount", rawAccount);
        confirm.put("text", statement);
        confirm.putObject("destinationAccountByFingerprint")
                .put(sourceGroup.get("accountFingerprint").asText(), accountId);
        ObjectNode selected = confirm.putArray("groups").addObject();
        selected.put("groupKey", sourceGroup.get("groupKey").asText());
        for (JsonNode leg : sourceGroup.get("legs")) {
            ObjectNode verified = selected.withArray("legs").addObject();
            for (String field : new String[]{"legNo", "instrumentType", "action", "positionEffect", "symbol",
                    "optionType", "strike", "expiration", "quantity", "multiplier"}) {
                if (leg.hasNonNull(field)) verified.set(field, leg.get(field));
            }
            verified.put("acknowledgeInferred", true);
        }
        JsonNode confirmed = json(post("/api/portfolio/broker-imports/confirm", confirm.toString()));
        String pendingId = confirmed.at("/items/0/id").asText();

        JsonNode open = json(get("/api/portfolio/broker-imports?status=OPEN&limit=1&offset=0"
                + "&portfolioAccountId=" + accountId));
        assertThat(open.get("limit").asInt()).isEqualTo(1);
        assertThat(open.get("total").asInt()).isEqualTo(1);
        assertThat(open.get("imports")).hasSize(1);

        JsonNode rejected = json(post("/api/portfolio/broker-imports/" + pendingId + "/commands",
                "{\"action\":\"REJECT\"}"));
        assertThat(rejected.at("/pending/status").asText()).isEqualTo("REJECTED");
        assertThat(post("/api/portfolio/broker-imports/" + pendingId + "/reject", "{}").statusCode())
                .isEqualTo(404);

        JsonNode reopened = json(post("/api/portfolio/broker-imports/" + pendingId + "/commands",
                "{\"action\":\"REOPEN\"}"));
        assertThat(reopened.at("/pending/status").asText()).isEqualTo("PENDING");
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

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return Json.parse(response.body());
    }
}
