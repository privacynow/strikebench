package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.config.AppConfig;
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

/**
 * Assignment preference is a TYPED context-revision attribute (folded Phase 9): declared with
 * the view, revised prospectively like every other assumption, validated at the boundary.
 */
class PlanAssignmentPreferenceApiTest {
    private static ApiServer server;
    private static HttpClient http;
    private static String base;

    @BeforeAll static void start() {
        var config = new HashMap<>(TestDb.freshConfig());
        config.put("FIXTURES_ONLY", "true");
        server = ApiServer.create(new AppConfig(config),
                Clock.fixed(Instant.parse("2026-07-12T16:00:00Z"), ZoneOffset.UTC));
        Javalin app = server.start(0);
        base = "http://localhost:" + app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterAll static void stop() {
        if (server != null) server.stop();
    }

    @Test void assignmentPreferenceIsDeclaredWithTheViewAndRevisedProspectively() throws Exception {
        HttpResponse<String> created = post("/api/plans", """
                {"clientRequestId":"assign-pref-1","symbol":"AAPL","intent":"INCOME",
                 "thesis":"neutral","horizonDays":21,"riskMode":"balanced",
                 "assignmentPreference":"prefer_below_basis"}
                """);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode plan = Json.parse(created.body());
        String id = plan.get("id").asText();
        long version = plan.get("version").asLong();
        assertThat(plan.at("/context/assignmentPreference").asText()).isEqualTo("PREFER_BELOW_BASIS");
        assertThat(plan.at("/context/rev").asInt()).isEqualTo(1);

        HttpResponse<String> revised = put("/api/plans/" + id + "/context", """
                {"expectedVersion":%d,"assignmentPreference":"AVOID"}
                """.formatted(version));
        assertThat(revised.statusCode()).as(revised.body()).isEqualTo(200);
        JsonNode after = Json.parse(revised.body());
        assertThat(after.at("/context/assignmentPreference").asText()).isEqualTo("AVOID");
        assertThat(after.at("/context/rev").asInt())
                .as("a changed preference is a NEW revision — history is never rewritten")
                .isEqualTo(2);
        assertThat(after.at("/context/thesis").asText()).isEqualTo("neutral");

        long v2 = after.get("version").asLong();
        HttpResponse<String> cleared = put("/api/plans/" + id + "/context", """
                {"expectedVersion":%d,"clear":["assignmentPreference"]}
                """.formatted(v2));
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        JsonNode clearedPlan = Json.parse(cleared.body());
        assertThat(clearedPlan.at("/context/assignmentPreference").isMissingNode()
                || clearedPlan.at("/context/assignmentPreference").isNull()).isTrue();
        assertThat(clearedPlan.at("/context/rev").asInt()).isEqualTo(3);

        HttpResponse<String> junk = post("/api/plans", """
                {"clientRequestId":"assign-pref-junk","symbol":"AAPL","intent":"INCOME",
                 "thesis":"neutral","horizonDays":22,"riskMode":"balanced",
                 "assignmentPreference":"YOLO"}
                """);
        assertThat(junk.statusCode()).isEqualTo(400);
        assertThat(junk.body()).contains("assignmentPreference must be one of");
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
