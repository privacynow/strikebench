package io.liftandshift.strikebench.api;

import io.javalin.Javalin;
import io.liftandshift.strikebench.auth.AuthService;
import io.liftandshift.strikebench.auth.IdentityProvider;
import io.liftandshift.strikebench.auth.VerifiedIdentity;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.EventBus;
import io.liftandshift.strikebench.util.OwnerScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real SSE live/replay coverage for authenticated owner isolation. */
class MarketStreamControllerAuthTest {
    private static final String ALICE = "google:alice";
    private static final String BOB = "google:bob";

    private Db db;
    private EventBus events;
    private MarketStreamController streams;
    private Javalin app;
    private String base;
    private HttpClient alice;
    private HttpClient bob;

    @BeforeEach
    void start() throws Exception {
        db = TestDb.fresh();
        Map<String, String> values = new HashMap<>();
        values.put("AUTH_ENABLED", "true");
        values.put("OIDC_CLIENT_ID", "test-client");
        values.put("OIDC_CLIENT_SECRET", "test-secret");
        values.put("AUTH_COOKIE_SECURE", "false");
        AppConfig cfg = new AppConfig(values);
        Clock clock = Clock.fixed(Instant.parse("2026-07-16T18:00:00Z"), ZoneOffset.UTC);
        IdentityProvider provider = new IdentityProvider() {
            @Override public URI authUrl(String state, String nonce) { return URI.create("https://issuer.test"); }
            @Override public VerifiedIdentity exchange(String code, String nonce) {
                throw new UnsupportedOperationException("login flow is not used by this transport test");
            }
        };
        AuthService auth = new AuthService(cfg, db, clock, provider);
        events = new EventBus();
        streams = new MarketStreamController(cfg, clock, null, null, null, null, events, auth,
                ctx -> OwnerScope.id(auth.currentUserId(ctx)), ignored -> "observed");
        app = Javalin.create(config -> {
            config.jetty.modifyServletContextHandler(handler -> {
                var sessions = new org.eclipse.jetty.ee10.servlet.SessionHandler();
                sessions.setHttpOnly(true);
                handler.setSessionHandler(sessions);
            });
            // A test-only login seam binds two independent cookie jars to two real server sessions.
            config.routes.get("/test-login/{uid}", ctx -> {
                ctx.sessionAttribute("uid", ctx.pathParam("uid"));
                ctx.status(204);
            });
            config.routes.sse("/events", streams::eventStream);
        }).start(0);
        base = "http://localhost:" + app.port();
        alice = clientWithCookies();
        bob = clientWithCookies();
        login(alice, ALICE);
        login(bob, BOB);
    }

    @AfterEach
    void stop() {
        if (app != null) app.stop();
        if (streams != null) streams.close();
        if (db != null) db.close();
    }

    @Test
    void liveDeliverySendsEachOwnerOnlyTheirOwnAlertHint() throws Exception {
        long cursor = events.currentSeq();
        HttpResponse<InputStream> aliceStream = open(alice, cursor);
        HttpResponse<InputStream> bobStream = open(bob, cursor);
        try {
            events.publish("alerts.updated", Map.of("user", ALICE, "marker", "alice-live"));
            events.publish("alerts.updated", Map.of("user", BOB, "marker", "bob-live"));

            String aliceFrame = readFrame(aliceStream.body());
            String bobFrame = readFrame(bobStream.body());
            assertThat(aliceFrame).contains("event: alerts.updated", "alice-live")
                    .doesNotContain("bob-live");
            assertThat(bobFrame).contains("event: alerts.updated", "bob-live")
                    .doesNotContain("alice-live");
        } finally {
            aliceStream.body().close();
            bobStream.body().close();
        }
    }

    @Test
    void replaySkipsOtherOwnersAndMalformedOwnerScopedAlertHints() throws Exception {
        long cursor = events.currentSeq();
        events.publish("alerts.updated", Map.of("user", BOB, "marker", "bob-replay"));
        events.publish("alerts.updated", Map.of("marker", "missing-user-replay"));
        events.publish("alerts.updated", Map.of("user", ALICE, "marker", "alice-replay"));

        HttpResponse<InputStream> replay = open(alice, cursor);
        try {
            String frame = readFrame(replay.body());
            assertThat(frame).contains("event: alerts.updated", "alice-replay")
                    .doesNotContain("bob-replay", "missing-user-replay");
        } finally {
            replay.body().close();
        }
    }

    private HttpResponse<InputStream> open(HttpClient client, long cursor) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/events"))
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", String.valueOf(cursor))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        return response;
    }

    private void login(HttpClient client, String user) throws Exception {
        HttpResponse<Void> response = client.send(HttpRequest.newBuilder(
                        URI.create(base + "/test-login/" + user)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(204);
    }

    private static HttpClient clientWithCookies() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager())
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    private static String readFrame(InputStream stream) throws Exception {
        byte[] bytes = new byte[4096];
        int count = stream.read(bytes);
        assertThat(count).isPositive();
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }
}
