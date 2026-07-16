package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

class TradePreviewAssessmentFallbackIntegrationTest {

    @Test
    void controllerReturnsMechanicalPreviewWhenExactAssessmentThrows() throws Exception {
        var settings = new HashMap<>(TestDb.freshConfig());
        settings.put("FIXTURES_ONLY", "true");
        Clock clock = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));
        ApiServer server = ApiServer.create(new AppConfig(settings), clock)
                .exactAssessmentForTest((symbol, candidate, buyingPower, context, world,
                                         eligible, failures, fees, exposure) -> {
                    throw new IllegalStateException("forced assessment outage");
                });
        Javalin app = server.start(0);
        try {
            String base = "http://localhost:" + app.port();
            HttpClient http = HttpClient.newHttpClient();
            JsonNode expirations = Json.parse(http.send(HttpRequest.newBuilder(
                            URI.create(base + "/api/research/AAPL/expirations")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body()).get("expirations");
            String expiration = expirations.get(2).asText();
            String request = """
                    {"symbol":"AAPL","strategy":"LONG_CALL","qty":1,
                     "source":"BOUNDARY_TEST","fillNature":"PROPOSED",
                     "legs":[{"action":"BUY","type":"CALL","strike":"255","expiration":"%s",
                              "ratio":1,"multiplier":100,"positionEffect":"OPEN"}]}
                    """.formatted(expiration);
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create(base + "/api/trades/preview"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            JsonNode body = Json.parse(response.body());
            assertThat(body.at("/preview/ok").asBoolean()).isTrue();
            assertThat(body.at("/preview/maxLossCents").asLong()).isPositive();
            assertThat(body.at("/evaluation/available").asBoolean()).isFalse();
            assertThat(body.at("/evaluation/assessment/mechanics/eligible").asBoolean()).isTrue();
            assertThat(body.at("/evaluation/decisionScore").isMissingNode()
                    || body.at("/evaluation/decisionScore").isNull()).isTrue();
            assertThat(body.at("/evaluation/unavailableReason").asText())
                    .contains("mechanics were checked", "decision assessment is unavailable");
        } finally {
            server.stop();
        }
    }
}
