package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.liftandshift.strikebench.config.AppConfig;
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
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** With AUTH_ENABLED, /api/* is gated but health/config/status/auth stay open (SPA can bootstrap). */
class AuthGateIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));

    private static ApiServer server;
    private static Javalin app;
    private static HttpClient http;
    private static String base;

    @BeforeAll
    static void start() {
        Map<String, String> conf = new HashMap<>(TestDb.freshConfig());
        conf.put("FIXTURES_ONLY", "true");
        conf.put("AUTH_ENABLED", "true");
        conf.put("OIDC_CLIENT_ID", "test-client");   // present so buildAuth succeeds; never contacted here
        conf.put("OIDC_CLIENT_SECRET", "test-secret");
        server = ApiServer.create(new AppConfig(conf), CLOCK);
        app = server.start(0);
        base = "http://localhost:" + app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() { if (server != null) server.stop(); }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void openRoutesStayReachableWithoutSignIn() throws Exception {
        assertThat(get("/api/health").statusCode()).isEqualTo(200);
        assertThat(get("/api/config").statusCode()).isEqualTo(200);
        assertThat(get("/api/status").statusCode()).isEqualTo(200);
    }

    @Test void meReportsUnauthenticatedWithALoginUrl() throws Exception {
        HttpResponse<String> r = get("/api/auth/me");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode me = Json.MAPPER.readTree(r.body());
        assertThat(me.get("authEnabled").asBoolean()).isTrue();
        assertThat(me.get("authenticated").asBoolean()).isFalse();
        assertThat(me.get("loginUrl").asText()).isEqualTo("/auth/login");
    }

    @Test void protectedApiRoutesReturn401WithoutSignIn() throws Exception {
        HttpResponse<String> r = get("/api/account");
        assertThat(r.statusCode()).isEqualTo(401);
        JsonNode err = Json.MAPPER.readTree(r.body());
        assertThat(err.get("error").asText()).isEqualTo("auth_required");
        assertThat(err.get("loginUrl").asText()).isEqualTo("/auth/login");

        assertThat(get("/api/trades").statusCode()).isEqualTo(401);
        assertThat(get("/api/portfolio/summary").statusCode()).isEqualTo(401);
    }

    @Test void tradeByIdAndAuditRoutesAreGated() throws Exception {
        // The IDOR-sensitive by-id routes are unreachable without a session (defense in depth
        // above the per-trade ownership check).
        assertThat(get("/api/trades/tr_anything").statusCode()).isEqualTo(401);
        assertThat(get("/api/audit").statusCode()).isEqualTo(401);
        HttpResponse<String> unwind = http.send(HttpRequest.newBuilder(URI.create(base + "/api/position-transformations/preview"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unwind.statusCode()).isEqualTo(401);
    }
}
