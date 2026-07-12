package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Full-stack tests: real Javalin on a random port, fixture data, temp SQLite. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));

    private static ApiServer server;
    private static Javalin app;
    private static HttpClient http;
    private static String base;
    private static Path tmpDir;

    @BeforeAll
    static void startServer() throws IOException {
        java.util.Map<String, String> conf = new java.util.HashMap<>(TestDb.freshConfig());
        conf.put("FIXTURES_ONLY", "true");
        AppConfig cfg = new AppConfig(conf);
        server = ApiServer.create(cfg, CLOCK);
        app = server.start(0);
        base = "http://localhost:" + app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Creates a trade the way a real client does: preview first, acknowledge the server's
     *  material-risk contract (R2), then create with the signed token attached. */
    private static HttpResponse<String> createAcknowledged(String body) throws Exception {
        var prev = post("/api/trades/preview", body);
        var pj = Json.parse(prev.body());
        if (!pj.has("requiredAcks") || pj.get("requiredAcks").isEmpty()) {
            return post("/api/trades", body);
        }
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) Json.parse(body);
        var acks = node.putArray("acknowledgedRisks");
        pj.get("requiredAcks").forEach(a -> acks.add(a.get("id").asText()));
        node.put("ackToken", pj.get("ackToken").asText());
        return post("/api/trades", node.toString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void statusIs200WithNoKeys() throws Exception {
        HttpResponse<String> res = get("/api/status");
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode json = Json.parse(res.body());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.get("domains").has("QUOTES")).isTrue();
        assertThat(json.get("fixturesOnly").asBoolean()).isTrue();
    }

    @Test
    @Order(2)
    void configExposesFeesAndDisclaimer() throws Exception {
        JsonNode json = Json.parse(get("/api/config").body());
        assertThat(json.get("feePerContractCents").asLong()).isEqualTo(65);
        assertThat(json.get("disclaimer").asText()).containsIgnoringCase("not financial advice");
    }

    @Test
    @Order(3)
    void accountStartsAt100k() throws Exception {
        JsonNode json = Json.parse(get("/api/account").body());
        assertThat(json.at("/account/cashCents").asLong()).isEqualTo(10_000_000L);
        assertThat(json.at("/account/buyingPowerCents").asLong()).isEqualTo(10_000_000L);
        assertThat(json.at("/ledger/0/type").asText()).isEqualTo("DEPOSIT");
    }

    @Test
    @Order(4)
    void researchFlowAcrossTickers() throws Exception {
        JsonNode aapl = Json.parse(get("/api/research/AAPL").body());
        assertThat(aapl.at("/quote/last").decimalValue()).isEqualByComparingTo("255.30");
        assertThat(aapl.get("optionable").asBoolean()).isTrue();
        assertThat(aapl.get("ivAtm").asDouble()).isBetween(0.05, 2.0);
        assertThat(aapl.get("hv30").asDouble()).isGreaterThan(0.01);
        assertThat(aapl.get("expirations").size()).isEqualTo(8);
        assertThat(aapl.get("benchmarks").size()).isEqualTo(2);

        // Ticker change: research another symbol immediately after
        JsonNode spy = Json.parse(get("/api/research/SPY").body());
        assertThat(spy.at("/quote/symbol").asText()).isEqualTo("SPY");

        String exp = aapl.get("expirations").get(0).asText();
        JsonNode chain = Json.parse(get("/api/research/AAPL/chain?expiration=" + exp).body());
        assertThat(chain.get("calls").size()).isEqualTo(21);

        JsonNode vtsax = Json.parse(get("/api/research/VTSAX").body());
        assertThat(vtsax.get("optionable").asBoolean()).isFalse();
        assertThat(vtsax.get("expirations").size()).isZero();

        assertThat(get("/api/research/NOPE").statusCode()).isEqualTo(404);
    }

    @Test
    @Order(5)
    void recommendReturnsCandidatesAndRejectsNaked() throws Exception {
        HttpResponse<String> res = post("/api/recommend",
                "{\"symbol\":\"AAPL\",\"thesis\":\"bullish\",\"horizon\":\"month\",\"riskMode\":\"conservative\"}");
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode json = Json.parse(res.body());
        assertThat(json.get("candidates").size()).isGreaterThan(0);
        assertThat(json.get("disclaimer").asText()).containsIgnoringCase("educational");
        JsonNode first = json.get("candidates").get(0);
        assertThat(first.get("maxLossCents").asLong()).isPositive();
        assertThat(first.get("structureGroup").asText()).isNotBlank();
        assertThat(first.get("beginnerExplanation").asText()).isNotBlank();
    }

    @Test
    @Order(5)
    void autoScoutReturnsPicksWithSignalsAndTargets() throws Exception {
        HttpResponse<String> res = post("/api/recommend/auto", """
                {"horizons":["week","month"],"targetProfitCents":25000,"riskMode":"balanced"}""");
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode json = Json.parse(res.body());
        assertThat(json.get("picks").size()).isGreaterThan(0);
        JsonNode pick = json.get("picks").get(0);
        assertThat(pick.at("/signals/thesis").asText()).isIn("BULLISH", "BEARISH", "NEUTRAL", "VOLATILE");
        assertThat(pick.at("/signals/rationale").size()).isGreaterThan(0);
        assertThat(pick.get("horizons").size()).isEqualTo(2);
        JsonNode candidates = pick.get("horizons").get(0).get("candidates");
        if (candidates.size() > 0) {
            assertThat(candidates.get(0).get("targetFit").asText()).isNotBlank();
            assertThat(candidates.get(0).at("/candidate/maxLossCents").asLong()).isPositive();
        }
        assertThat(json.get("disclaimer").asText()).containsIgnoringCase("not predictions");
        // Empty body works with defaults too
        assertThat(post("/api/recommend/auto", "").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(6)
    void unsafeNakedCallIs422AndAccountUnchanged() throws Exception {
        String exp = Json.parse(get("/api/research/AAPL/expirations").body()).get("expirations").get(2).asText();
        HttpResponse<String> res = post("/api/trades", """
                {"symbol":"AAPL","strategy":"NAKED_CALL","qty":1,
                 "legs":[{"action":"SELL","type":"CALL","strike":"260","expiration":"%s","ratio":1}]}""".formatted(exp));
        assertThat(res.statusCode()).isEqualTo(422);
        JsonNode json = Json.parse(res.body());
        assertThat(json.get("error").asText()).isEqualTo("trade_rejected");
        assertThat(json.get("reasons").toString()).containsIgnoringCase("undefined risk");

        JsonNode account = Json.parse(get("/api/account").body());
        assertThat(account.at("/account/cashCents").asLong()).isEqualTo(10_000_000L);
        assertThat(account.at("/account/hasTraded").asBoolean()).isFalse();
    }

    @Test
    @Order(7)
    void createUnwindLifecycle() throws Exception {
        // Preview first: no mutation
        String exp = Json.parse(get("/api/research/AAPL/expirations").body()).get("expirations").get(2).asText();
        String spreadBody = """
                {"symbol":"AAPL","strategy":"CREDIT_PUT_SPREAD","qty":1,"thesis":"bullish","horizon":"month","riskMode":"conservative",
                 "legs":[{"action":"SELL","type":"PUT","strike":"250","expiration":"%s","ratio":1},
                         {"action":"BUY","type":"PUT","strike":"245","expiration":"%s","ratio":1}]}""".formatted(exp, exp);
        HttpResponse<String> previewRes = post("/api/trades/preview", spreadBody);
        assertThat(previewRes.statusCode()).isEqualTo(200);
        JsonNode preview = Json.parse(previewRes.body());
        assertThat(preview.at("/preview/ok").asBoolean()).isTrue();
        assertThat(preview.at("/preview/entryNetPremiumCents").asLong()).isPositive(); // credit
        JsonNode before = Json.parse(get("/api/account").body());
        assertThat(before.at("/account/hasTraded").asBoolean()).isFalse();

        // Create
        HttpResponse<String> createRes = createAcknowledged(spreadBody);
        assertThat(createRes.statusCode()).isEqualTo(201);
        JsonNode created = Json.parse(createRes.body());
        String tradeId = created.at("/trade/id").asText();
        assertThat(created.at("/trade/status").asText()).isEqualTo("ACTIVE");
        long maxLoss = created.at("/trade/maxLossCents").asLong();
        long feesOpen = created.at("/trade/feesOpenCents").asLong();
        assertThat(maxLoss).isPositive();

        JsonNode after = Json.parse(get("/api/account").body());
        assertThat(after.at("/account/buyingPowerCents").asLong())
                .isEqualTo(10_000_000L - maxLoss - feesOpen); // BP reduced by exactly maxLoss+fees

        // List / detail
        JsonNode list = Json.parse(get("/api/trades?status=ACTIVE").body());
        assertThat(list.get("total").asLong()).isEqualTo(1);
        JsonNode detail = Json.parse(get("/api/trades/" + tradeId).body());
        assertThat(detail.at("/trade/id").asText()).isEqualTo(tradeId);
        assertThat(detail.get("payoff").size()).isGreaterThan(30);
        assertThat(detail.at("/current/popNow").asDouble()).isBetween(0.0, 1.0);

        // Refresh writes a mark
        assertThat(post("/api/trades/" + tradeId + "/refresh", "{}").statusCode()).isEqualTo(200);
        JsonNode detail2 = Json.parse(get("/api/trades/" + tradeId).body());
        assertThat(detail2.get("marksHistory").size()).isEqualTo(1);

        // Unwind without confirm -> 400; with confirm -> realized P/L and reserve released
        assertThat(post("/api/trades/" + tradeId + "/unwind", "{}").statusCode()).isEqualTo(400);
        HttpResponse<String> unwindRes = post("/api/trades/" + tradeId + "/unwind", "{\"confirm\":true}");
        assertThat(unwindRes.statusCode()).isEqualTo(200);
        JsonNode unwound = Json.parse(unwindRes.body());
        assertThat(unwound.at("/trade/status").asText()).isEqualTo("CLOSED");
        assertThat(unwound.get("realizedPnlCents").isNumber()).isTrue();

        JsonNode finalAccount = Json.parse(get("/api/account").body());
        assertThat(finalAccount.at("/account/reservedCents").asLong()).isZero();
    }

    @Test
    @Order(8)
    void deleteRequiresConfirmAndVoidsTrade() throws Exception {
        String exp = Json.parse(get("/api/research/AAPL/expirations").body()).get("expirations").get(2).asText();
        String body = """
                {"symbol":"AAPL","strategy":"LONG_CALL","qty":1,
                 "legs":[{"action":"BUY","type":"CALL","strike":"255","expiration":"%s","ratio":1}]}""".formatted(exp);
        String tradeId = Json.parse(createAcknowledged(body).body()).at("/trade/id").asText();

        assertThat(delete("/api/trades/" + tradeId).statusCode()).isEqualTo(400); // no confirm
        assertThat(delete("/api/trades/" + tradeId + "?confirm=true").statusCode()).isEqualTo(200);
        JsonNode detail = Json.parse(get("/api/trades/" + tradeId).body());
        assertThat(detail.at("/trade/status").asText()).isEqualTo("DELETED");
    }

    @Test
    @Order(9)
    void settleRequiresExplicitConfirmAndBadDatesAre400() throws Exception {
        String exp = Json.parse(get("/api/research/AAPL/expirations").body()).get("expirations").get(2).asText();
        String body = """
                {"symbol":"AAPL","strategy":"LONG_CALL","qty":1,
                 "legs":[{"action":"BUY","type":"CALL","strike":"255","expiration":"%s","ratio":1}]}""".formatted(exp);
        String tradeId = Json.parse(createAcknowledged(body).body()).at("/trade/id").asText();

        // No/empty body or missing confirm flag must NOT settle (was auto-confirming)
        assertThat(post("/api/trades/" + tradeId + "/settle", "").statusCode()).isEqualTo(400);
        assertThat(post("/api/trades/" + tradeId + "/settle", "{}").statusCode()).isEqualTo(400);
        // With confirm it advances to the domain check (legs not expired -> 422)
        assertThat(post("/api/trades/" + tradeId + "/settle", "{\"confirm\":true}").statusCode()).isEqualTo(422);
        assertThat(delete("/api/trades/" + tradeId + "?confirm=true").statusCode()).isEqualTo(200);

        // Malformed dates are client errors, not 500s
        assertThat(get("/api/research/AAPL/chain?expiration=garbage").statusCode()).isEqualTo(400);
        HttpResponse<String> badBt = post("/api/backtest", "{\"symbol\":\"AAPL\",\"strategy\":\"LONG_CALL\",\"from\":\"nope\",\"to\":\"2026-06-30\"}");
        assertThat(badBt.statusCode()).isEqualTo(400);
    }

    @Test
    @Order(8)
    void portfolioGreeksAggregateActivePositions() throws Exception {
        String exp = Json.parse(get("/api/research/AAPL/expirations").body()).get("expirations").get(2).asText();
        String body = """
                {"symbol":"AAPL","strategy":"LONG_CALL","qty":1,
                 "legs":[{"action":"BUY","type":"CALL","strike":"255","expiration":"%s","ratio":1}]}""".formatted(exp);
        String tradeId = Json.parse(createAcknowledged(body).body()).at("/trade/id").asText();

        JsonNode greeks = Json.parse(get("/api/portfolio/greeks").body());
        assertThat(greeks.get("deltaShares").asDouble()).isGreaterThan(0); // long call = positive delta
        assertThat(greeks.get("thetaPerDay").asDouble()).isLessThan(0);    // long premium decays
        assertThat(greeks.get("positions").size()).isGreaterThanOrEqualTo(1);
        assertThat(greeks.get("note").asText()).containsIgnoringCase("model");

        // detail carries the same greeks + per-leg rows
        JsonNode detail = Json.parse(get("/api/trades/" + tradeId).body());
        assertThat(detail.at("/current/greeks/deltaShares").asDouble()).isGreaterThan(0);
        assertThat(detail.at("/current/legGreeks").size()).isEqualTo(1);

        assertThat(delete("/api/trades/" + tradeId + "?confirm=true").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(9)
    void auditTrailIsQueryable() throws Exception {
        JsonNode json = Json.parse(get("/api/audit").body());
        assertThat(json.get("entries").size()).isGreaterThan(0);
    }

    @Test
    @Order(10)
    void resetBlockedThenForced() throws Exception {
        HttpResponse<String> blocked = post("/api/account/reset", "{\"startingCashCents\":5000000,\"confirm\":true}");
        assertThat(blocked.statusCode()).isEqualTo(409); // has traded
        HttpResponse<String> forced = post("/api/account/reset", "{\"startingCashCents\":5000000,\"confirm\":true,\"force\":true}");
        assertThat(forced.statusCode()).isEqualTo(200);
        assertThat(Json.parse(forced.body()).at("/account/cashCents").asLong()).isEqualTo(5_000_000L);
    }

    @Test
    @Order(13)
    void backtestRunsAndPersists() throws Exception {
        HttpResponse<String> res = post("/api/backtest", """
                {"symbol":"AAPL","strategy":"DEBIT_CALL_SPREAD","from":"2026-03-02","to":"2026-06-30"}""");
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode report = Json.parse(res.body());
        assertThat(report.get("sampleSize").asInt()).isGreaterThan(0);
        assertThat(report.get("daysCovered").asInt()).isGreaterThan(0);
        assertThat(report.get("pricingMode").asText()).isEqualTo("MODELED_FROM_UNDERLYING");
        assertThat(report.get("assumptions").has("slippagePctPerLeg")).isTrue();
        assertThat(report.get("skipped").isArray()).isTrue();
        assertThat(report.get("confidence").asText()).isNotBlank();
        assertThat(report.get("disclaimer").asText()).containsIgnoringCase("educational");

        JsonNode list = Json.parse(get("/api/backtests").body());
        assertThat(list.get("backtests").size()).isGreaterThanOrEqualTo(1);
        String id = report.get("id").asText();
        JsonNode loaded = Json.parse(get("/api/backtests/" + id).body());
        assertThat(loaded.get("id").asText()).isEqualTo(id);

        // Undefined-risk strategy rejected with 400
        HttpResponse<String> naked = post("/api/backtest", """
                {"symbol":"AAPL","strategy":"NAKED_CALL","from":"2026-03-02","to":"2026-06-30"}""");
        assertThat(naked.statusCode()).isEqualTo(400);
    }

    @Test
    @Order(14)
    void brokerStatusReportsUnconfigured() throws Exception {
        JsonNode json = Json.parse(get("/api/broker/status").body());
        assertThat(json.get("configured").asBoolean()).isFalse();
        assertThat(json.get("connected").asBoolean()).isFalse();
        // And live-order endpoints refuse politely
        assertThat(post("/api/broker/connect/start", "{}").statusCode()).isEqualTo(409);
    }

    @Test
    @Order(15)
    void malformedBodiesAreClientErrorsNever500() throws Exception {
        for (String path : java.util.List.of("/api/trades", "/api/trades/preview", "/api/recommend", "/api/backtest", "/api/recommend/auto")) {
            assertThat(post(path, "{").statusCode()).as(path + " invalid json").isEqualTo(400);
            assertThat(post(path, "").statusCode()).as(path + " empty body").isIn(200, 400); // auto allows empty
        }
        assertThat(post("/api/trades", "{\"symbol\":\"AAPL\",\"qty\":\"abc\"}").statusCode()).isEqualTo(400);
    }

    @Test
    @Order(15)
    void missingResourcesAre404NotButchered() throws Exception {
        assertThat(get("/api/trades/tr_doesnotexist").statusCode()).isEqualTo(404);
        assertThat(get("/api/backtests/bt_doesnotexist").statusCode()).isEqualTo(404);
        assertThat(post("/api/trades/tr_doesnotexist/refresh", "{}").statusCode()).isEqualTo(404);
        // handler-written 404 bodies survive (not clobbered by the generic mapper)
        JsonNode research = Json.parse(get("/api/research/NOPE").body());
        assertThat(research.get("error").asText()).isEqualTo("unknown_symbol");
        // chains only exist for listed expirations — nothing fabricated
        assertThat(get("/api/research/AAPL/chain?expiration=2026-07-09").statusCode()).isEqualTo(404);
        // expired contracts cannot be opened
        HttpResponse<String> past = createAcknowledged("""
                {"symbol":"AAPL","strategy":"LONG_CALL","qty":1,
                 "legs":[{"action":"BUY","type":"CALL","strike":"255","expiration":"2020-01-17","ratio":1}]}""");
        assertThat(past.statusCode()).isEqualTo(422);
        assertThat(past.body()).contains("expired");
    }

    @Test
    @Order(16)
    void debitDiagonalWithoutEntryPricesCreates() throws Exception {
        JsonNode exps = Json.parse(get("/api/research/AAPL/expirations").body()).get("expirations");
        String near = exps.get(2).asText();
        String far = exps.get(6).asText();
        String body = """
                {"symbol":"AAPL","strategy":"DIAGONAL_CALL","qty":1,
                 "legs":[{"action":"BUY","type":"CALL","strike":"255","expiration":"%s","ratio":1},
                         {"action":"SELL","type":"CALL","strike":"260","expiration":"%s","ratio":1}]}""".formatted(far, near);
        JsonNode preview = Json.parse(post("/api/trades/preview", body).body());
        assertThat(preview.at("/preview/ok").asBoolean()).isTrue();
        HttpResponse<String> created = createAcknowledged(body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode trade = Json.parse(created.body()).get("trade");
        assertThat(trade.get("entryNetPremiumCents").asLong()).isNegative(); // debit
        assertThat(trade.path("maxProfitCents").isMissingNode()).isTrue();   // null = omitted (model-dependent)
        assertThat(delete("/api/trades/" + trade.get("id").asText() + "?confirm=true").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(11)
    void staticIndexServed() throws Exception {
        HttpResponse<String> res = get("/");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).containsIgnoringCase("strikebench");
    }

    @Test
    @Order(12)
    void unknownApiPathIs404Json() throws Exception {
        HttpResponse<String> res = get("/api/nonexistent");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(Json.parse(res.body()).get("error").asText()).isEqualTo("not_found");
    }
    @Test
    @Order(17)
    void positionsAndCoveredCallLifecycleViaApi() throws Exception {
        // Buy 100 AAPL at the fixture ask
        long cashBefore = Json.parse(get("/api/account").body()).at("/account/cashCents").asLong();
        HttpResponse<String> buy = post("/api/positions/buy", "{\"symbol\":\"AAPL\",\"shares\":100}");
        assertThat(buy.statusCode()).as(buy.body()).isEqualTo(201);
        JsonNode bought = Json.parse(buy.body());
        assertThat(bought.at("/position/shares").asLong()).isEqualTo(100);
        assertThat(bought.at("/position/freeShares").asLong()).isEqualTo(100);
        long cost = bought.get("totalCents").asLong();
        assertThat(Json.parse(get("/api/account").body()).at("/account/cashCents").asLong())
                .isEqualTo(cashBefore - cost);

        // EXIT-intent ideas read the real position and propose a covered call on the held shares
        JsonNode rec = Json.parse(post("/api/recommend",
                "{\"symbol\":\"AAPL\",\"intent\":\"exit\",\"riskMode\":\"balanced\"}").body());
        assertThat(rec.get("intent").asText()).isEqualTo("EXIT");
        JsonNode cc = null;
        for (JsonNode c : rec.get("candidates")) if (c.get("strategy").asText().equals("COVERED_CALL")) cc = c;
        assertThat(cc).as("covered call candidate present: " + rec.get("candidates")).isNotNull();
        assertThat(cc.get("usesHeldShares").asBoolean()).isTrue();
        assertThat(cc.get("maxLossCents").asLong()).isZero();
        assertThat(cc.get("combinedMaxLossCents").asLong()).isPositive();
        assertThat(cc.get("intentNote").asText()).contains("sell");

        // Place it through the normal ticket pipeline with useHeldShares
        StringBuilder legs = new StringBuilder("[");
        for (JsonNode l : cc.get("legs")) {
            if (legs.length() > 1) legs.append(',');
            legs.append("{\"action\":\"").append(l.get("action").asText())
                .append("\",\"type\":\"").append(l.get("type").asText())
                .append("\",\"strike\":\"").append(l.get("strike").asText())
                .append("\",\"expiration\":\"").append(l.get("expiration").asText())
                .append("\",\"ratio\":1}");
        }
        legs.append(']');
        String body = "{\"symbol\":\"AAPL\",\"strategy\":\"COVERED_CALL\",\"qty\":1,\"intent\":\"exit\","
                + "\"useHeldShares\":true,\"legs\":" + legs + "}";
        JsonNode preview = Json.parse(post("/api/trades/preview", body).body());
        assertThat(preview.at("/preview/ok").asBoolean()).as(preview.toString()).isTrue();
        assertThat(preview.at("/preview/reserveCents").asLong()).isZero();
        assertThat(preview.at("/guardrails/level").asText()).isNotEqualTo("BLOCK");
        HttpResponse<String> created = createAcknowledged(body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode trade = Json.parse(created.body()).get("trade");
        assertThat(trade.get("sharesLocked").asLong()).isEqualTo(100);
        assertThat(trade.get("intent").asText()).isEqualTo("EXIT");
        String tradeId = trade.get("id").asText();

        // Locked shares cannot be sold; the filterable list finds the trade by intent
        HttpResponse<String> sellLocked = post("/api/positions/sell", "{\"symbol\":\"AAPL\",\"shares\":100}");
        assertThat(sellLocked.statusCode()).isEqualTo(422);
        assertThat(sellLocked.body()).contains("locked");
        JsonNode byIntent = Json.parse(get("/api/trades?symbol=AAPL&intent=EXIT&status=ACTIVE").body());
        assertThat(byIntent.get("total").asLong()).isEqualTo(1);
        assertThat(Json.parse(get("/api/trades?symbol=AAPL&intent=INCOME&status=ACTIVE").body()).get("total").asLong()).isZero();

        // Void the call, sell the shares back — clean slate for later tests
        assertThat(delete("/api/trades/" + tradeId + "?confirm=true").statusCode()).isEqualTo(200);
        HttpResponse<String> sold = post("/api/positions/sell", "{\"symbol\":\"AAPL\",\"shares\":100}");
        assertThat(sold.statusCode()).as(sold.body()).isEqualTo(200);
        assertThat(Json.parse(sold.body()).get("realizedPnlCents").isNumber()).isTrue();
        assertThat(Json.parse(get("/api/positions").body()).get("positions")).isEmpty();
    }

    @Test
    @Order(18)
    void stockOrderValidationAndUnknownSymbols() throws Exception {
        assertThat(post("/api/positions/buy", "{\"symbol\":\"AAPL\"}").statusCode()).isEqualTo(400);
        assertThat(post("/api/positions/buy", "{\"symbol\":\"AAPL\",\"shares\":-5}").statusCode()).isEqualTo(400);
        HttpResponse<String> unknown = post("/api/positions/buy", "{\"symbol\":\"ZZZZ\",\"shares\":10}");
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(post("/api/positions/sell", "{\"symbol\":\"AAPL\",\"shares\":10}").statusCode()).isEqualTo(422);
        // Filters flow through recommend: an impossible POP floor rejects everything, loudly
        JsonNode rec = Json.parse(post("/api/recommend",
                "{\"symbol\":\"AAPL\",\"intent\":\"income\",\"filters\":{\"minPop\":0.99}}").body());
        assertThat(rec.get("candidates")).isEmpty();
        assertThat(rec.get("rejected").toString()).contains("below your minimum");
        // Unknown intent is a 400, not a 500
        assertThat(post("/api/recommend", "{\"symbol\":\"AAPL\",\"intent\":\"yolo\"}").statusCode()).isEqualTo(400);
    }
    @Test
    @Order(19)
    void acquireIgnoresExistingSharesAndNullBodiesAre400s() throws Exception {
        // Owning shares must not scale NEW purchases: acquire defaults to one 100-share lot
        assertThat(post("/api/positions/buy", "{\"symbol\":\"AAPL\",\"shares\":100}").statusCode()).isEqualTo(201);
        JsonNode rec = Json.parse(post("/api/recommend",
                "{\"symbol\":\"AAPL\",\"intent\":\"acquire\",\"riskMode\":\"balanced\"}").body());
        for (JsonNode c : rec.get("candidates")) {
            if (c.get("strategy").asText().equals("CASH_SECURED_PUT")) {
                assertThat(c.get("qty").asInt()).isEqualTo(1);
            }
        }
        assertThat(post("/api/positions/sell", "{\"symbol\":\"AAPL\",\"shares\":100}").statusCode()).isEqualTo(200);

        // The literal JSON document "null" is client error territory, never a 500
        assertThat(post("/api/positions/buy", "null").statusCode()).isEqualTo(400);
        assertThat(post("/api/recommend", "null").statusCode()).isEqualTo(400);
        assertThat(post("/api/recommend/auto", "null").statusCode()).isEqualTo(200); // defaults, like empty
        assertThat(post("/api/trades/preview", "null").statusCode()).isEqualTo(400);

        // Covered call without the shares: the reason names the shortfall, not "add a wing"
        JsonNode exps = Json.parse(get("/api/research/AAPL/expirations").body()).get("expirations");
        String exp = exps.get(2).asText();
        HttpResponse<String> noShares = post("/api/trades",
                "{\"symbol\":\"AAPL\",\"strategy\":\"COVERED_CALL\",\"useHeldShares\":true,"
                + "\"legs\":[{\"action\":\"SELL\",\"type\":\"CALL\",\"strike\":\"265\",\"expiration\":\"" + exp + "\",\"ratio\":1}]}");
        assertThat(noShares.statusCode()).isEqualTo(422);
        assertThat(noShares.body()).contains("free shares");
    }

    @Test
    @Order(20)
    void healthBeaconAndBrandConfig() throws Exception {
        JsonNode health = Json.parse(get("/api/health").body());
        assertThat(health.get("ok").asBoolean()).isTrue();
        assertThat(health.get("jarChangedSinceBoot").asBoolean()).isFalse();
        assertThat(health.get("startedAt").asText()).isNotBlank();

        JsonNode cfg = Json.parse(get("/api/config").body());
        assertThat(cfg.at("/brand/name").asText()).isEqualTo("StrikeBench");
        assertThat(cfg.at("/brand/tagline").asText()).isNotBlank();

        // Brand is plain config — a rename never needs a rebuild
        io.liftandshift.strikebench.config.AppConfig custom = new io.liftandshift.strikebench.config.AppConfig(
                java.util.Map.of("BRAND_NAME", "Strike Bench", "BRAND_TAGLINE", "test tagline"));
        assertThat(custom.brandName()).isEqualTo("Strike Bench");
        assertThat(custom.brandTagline()).isEqualTo("test tagline");
    }

    @Test
    @Order(21)
    void universeSelectionQuotesBatchAndHistoryRanges() throws Exception {
        // Default in fixture mode is the DEMO sector
        JsonNode u = Json.parse(get("/api/universe").body());
        assertThat(u.at("/active/symbols").toString()).contains("AAPL").contains("SPY");
        assertThat(u.get("sectors")).hasSize(1);
        assertThat(u.get("lane").asText()).isEqualTo("DEMO");
        // Demo and Simulated markets own their symbol list. Sector changes cannot mutate
        // the hidden Observed universe while another lane is active.
        assertThat(put("/api/universe", "{\"sector\":\"DEFENSE\"}").statusCode()).isEqualTo(409);

        // Batch quotes: light rows for whatever has data, silently skipping the unknown
        JsonNode quotes = Json.parse(get("/api/quotes?symbols=AAPL,TSLA,ZZZZ").body()).get("quotes");
        assertThat(quotes.size()).isEqualTo(2);
        assertThat(quotes.get(0).get("last").asText()).isNotBlank();
        JsonNode aaplQuote = java.util.stream.StreamSupport.stream(quotes.spliterator(), false)
                .filter(q -> "AAPL".equals(q.get("symbol").asText())).findFirst().orElseThrow();
        assertThat(aaplQuote.get("optionable").asBoolean()).isTrue();
        assertThat(aaplQuote.get("bid").asText()).isNotBlank();
        assertThat(aaplQuote.get("ask").asText()).isNotBlank();
        assertThat(aaplQuote.get("asOf").asLong()).isPositive();
        assertThat(Json.parse(get("/api/quotes?symbols=VTSAX").body())
                .at("/quotes/0/optionable").asBoolean()).isFalse();
        // No symbols param -> the active universe
        assertThat(Json.parse(get("/api/quotes").body()).get("quotes").size())
                .isEqualTo(u.at("/active/symbols").size());

        // The scout's default scan list is the selected universe
        JsonNode scan = Json.parse(post("/api/recommend/auto", "{}").body());
        java.util.Set<String> activeSymbols = new java.util.LinkedHashSet<>();
        u.at("/active/symbols").forEach(s -> activeSymbols.add(s.asText()));
        for (JsonNode pick : scan.get("picks")) {
            assertThat(pick.get("symbol").asText()).isIn(activeSymbols);
        }

        // History range pills: ytd/5y/max normalize and answer with whatever data exists
        for (String range : new String[]{"ytd", "5y", "max"}) {
            JsonNode h = Json.parse(get("/api/research/AAPL/history?range=" + range).body());
            assertThat(h.get("range").asText()).isEqualTo(range);
            assertThat(h.get("candles").size()).isGreaterThan(50);
        }
        assertThat(Json.parse(get("/api/research/AAPL/history?range=bogus").body()).get("range").asText()).isEqualTo("1y");

    }

    @Test
    @Order(22)
    void strikeLaddersAreIntentNative() throws Exception {
        // ACQUIRE: rungs step DOWN from spot; each rung names your price with full honesty metrics
        JsonNode acq = Json.parse(post("/api/recommend/ladder",
                "{\"symbol\":\"AAPL\",\"intent\":\"acquire\",\"riskMode\":\"balanced\"}").body());
        assertThat(acq.get("intent").asText()).isEqualTo("ACQUIRE");
        assertThat(acq.get("rungs").size()).isGreaterThanOrEqualTo(4);
        double prev = Double.MAX_VALUE;
        for (JsonNode r : acq.get("rungs")) {
            double strike = Double.parseDouble(r.get("legs").get(0).get("strike").asText());
            assertThat(strike).isLessThanOrEqualTo(255.30 + 0.01); // at or below fixture spot
            assertThat(strike).isLessThan(prev);
            prev = strike;
            assertThat(r.get("entryNetPremiumCents").asLong()).isPositive(); // paid to wait
            assertThat(Double.parseDouble(r.get("effectivePrice").asText())).isLessThan(strike);
            assertThat(r.get("assignmentProb").isNumber()).isTrue();
            assertThat(r.get("annualizedYieldPct").isNumber()).isTrue();
        }

        // The ladder is another view of the same request, not an escape hatch around its hard
        // screens. An impossible income floor excludes every rung and explains why.
        JsonNode screened = Json.parse(post("/api/recommend/ladder",
                "{\"symbol\":\"AAPL\",\"intent\":\"acquire\",\"riskMode\":\"balanced\","
                        + "\"filters\":{\"minAnnualizedYieldPct\":10000}}").body());
        assertThat(screened.get("rungs")).isEmpty();
        assertThat(screened.get("notes").toString()).contains("excluded by your selected limits")
                .contains("No ladder rung passed every selected limit");

        // EXIT/HEDGE never inflate the selected per-idea budget merely to manufacture a rung.
        JsonNode exitWithoutShares = Json.parse(post("/api/recommend/ladder",
                "{\"symbol\":\"AAPL\",\"intent\":\"exit\",\"maxLossCents\":100000}").body());
        assertThat(exitWithoutShares.get("rungs")).isEmpty();
        assertThat(exitWithoutShares.get("notes").toString()).contains("starts from shares you own");

        // EXIT with held shares: rungs climb ABOVE spot, sized to the shares, no new cash
        assertThat(post("/api/positions/buy", "{\"symbol\":\"AAPL\",\"shares\":100}").statusCode()).isEqualTo(201);
        JsonNode exit = Json.parse(post("/api/recommend/ladder",
                "{\"symbol\":\"AAPL\",\"intent\":\"exit\"}").body());
        assertThat(exit.get("rungs").size()).isGreaterThanOrEqualTo(4);
        double prevUp = 0;
        for (JsonNode r : exit.get("rungs")) {
            double strike = Double.parseDouble(r.get("legs").get(0).get("strike").asText());
            assertThat(strike).isGreaterThanOrEqualTo(255.29);
            assertThat(strike).isGreaterThan(prevUp);
            prevUp = strike;
            assertThat(r.get("usesHeldShares").asBoolean()).isTrue();
            assertThat(r.get("qty").asInt()).isEqualTo(1); // the one held lot
            assertThat(r.get("maxLossCents").asLong()).isZero();
        }

        // HEDGE: floors below spot, each rung costs its premium
        JsonNode hedge = Json.parse(post("/api/recommend/ladder",
                "{\"symbol\":\"AAPL\",\"intent\":\"hedge\",\"riskMode\":\"aggressive\",\"maxLossCents\":100000}").body());
        assertThat(hedge.get("rungs").size()).isGreaterThanOrEqualTo(3);
        for (JsonNode r : hedge.get("rungs")) {
            assertThat(r.get("maxLossCents").asLong()).isPositive();
            assertThat(r.get("maxLossCents").asLong()).isLessThanOrEqualTo(100_000L);
            assertThat(r.get("intentNote").asText()).contains("Guarantees");
        }
        assertThat(post("/api/positions/sell", "{\"symbol\":\"AAPL\",\"shares\":100}").statusCode()).isEqualTo(200);

        // Directional has no ladder — clean 400, not a mystery
        assertThat(post("/api/recommend/ladder", "{\"symbol\":\"AAPL\",\"intent\":\"directional\"}").statusCode()).isEqualTo(400);
    }

    @Test
    @Order(23)
    void previewCarriesLegMarksAssignmentAndPayoff() throws Exception {
        // The builder's live panel runs entirely off the preview response: it needs per-leg
        // executable marks + greeks, the engine's assignment probability, and chartable payoff.
        JsonNode research = Json.parse(get("/api/research/AAPL").body());
        String exp = research.get("expirations").get(3).asText();

        String spread = "{\"symbol\":\"AAPL\",\"strategy\":\"CREDIT_PUT_SPREAD\",\"qty\":1,\"legs\":["
                + "{\"action\":\"SELL\",\"type\":\"PUT\",\"strike\":\"250\",\"expiration\":\"" + exp + "\",\"ratio\":1},"
                + "{\"action\":\"BUY\",\"type\":\"PUT\",\"strike\":\"245\",\"expiration\":\"" + exp + "\",\"ratio\":1}]}";
        JsonNode spreadResponse = Json.parse(post("/api/trades/preview", spread).body());
        JsonNode p = spreadResponse.get("preview");
        assertThat(p.get("ok").asBoolean()).isTrue();
        assertThat(p.get("underlyingCents").asLong()).isGreaterThan(0);
        assertThat(spreadResponse.get("economics").get("verdict").asText()).isNotBlank();
        assertThat(spreadResponse.get("economics").has("marketEvAfterCostsCents")).isTrue();

        // Per-leg marks aligned with the request: fills at executable sides, greeks present
        assertThat(p.get("legs").size()).isEqualTo(2);
        JsonNode shortLeg = p.get("legs").get(0);
        assertThat(shortLeg.get("action").asText()).isEqualTo("SELL");
        assertThat(shortLeg.get("type").asText()).isEqualTo("PUT");
        assertThat(shortLeg.get("strike").asText()).isEqualTo("250");
        assertThat(shortLeg.get("bid").asText()).isNotBlank();
        assertThat(shortLeg.get("ask").asText()).isNotBlank();
        assertThat(shortLeg.get("fill").asText()).isEqualTo(shortLeg.get("bid").asText()); // SELL fills at bid
        assertThat(shortLeg.get("delta").isNumber()).isTrue();
        assertThat(shortLeg.get("theta").isNumber()).isTrue();

        // Assignment probability: one short strike, engine math, in (0,1]
        double assign = p.get("assignmentProb").asDouble();
        assertThat(assign).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);

        // Payoff samples: sorted ascending, spanning below and above the strikes
        JsonNode payoff = p.get("payoff");
        assertThat(payoff.size()).isGreaterThan(30);
        double prevPrice = -1;
        for (JsonNode pt : payoff) {
            double px = Double.parseDouble(pt.get("price").asText());
            assertThat(px).isGreaterThan(prevPrice);
            prevPrice = px;
        }

        // A long call has no short strike: assignmentProb is null, upside uncapped
        String longCall = "{\"symbol\":\"AAPL\",\"strategy\":\"LONG_CALL\",\"qty\":1,\"legs\":["
                + "{\"action\":\"BUY\",\"type\":\"CALL\",\"strike\":\"255\",\"expiration\":\"" + exp + "\",\"ratio\":1}]}";
        JsonNode lc = Json.parse(post("/api/trades/preview", longCall).body()).get("preview");
        assertThat(lc.has("assignmentProb")).isFalse(); // NON_NULL mapper: no shorts -> field absent
        assertThat(lc.get("payoff").size()).isGreaterThan(30);

        // Undefined risk is BLOCKED but the payoff still charts the cliff (education, not a dead end)
        String naked = "{\"symbol\":\"AAPL\",\"strategy\":\"CUSTOM\",\"qty\":1,\"legs\":["
                + "{\"action\":\"SELL\",\"type\":\"CALL\",\"strike\":\"260\",\"expiration\":\"" + exp + "\",\"ratio\":1}]}";
        JsonNode nk = Json.parse(post("/api/trades/preview", naked).body()).get("preview");
        assertThat(nk.get("ok").asBoolean()).isFalse();
        assertThat(nk.get("blockReasons").toString()).contains("Undefined");
        assertThat(nk.get("payoff").size()).isGreaterThan(30);
        assertThat(nk.get("legs").size()).isEqualTo(1);

        // Calendars: no expiration payoff curve (model-dependent), honestly empty
        String exp2 = research.get("expirations").get(5).asText();
        String calendar = "{\"symbol\":\"AAPL\",\"strategy\":\"CALENDAR_CALL\",\"qty\":1,\"legs\":["
                + "{\"action\":\"SELL\",\"type\":\"CALL\",\"strike\":\"255\",\"expiration\":\"" + exp + "\",\"ratio\":1},"
                + "{\"action\":\"BUY\",\"type\":\"CALL\",\"strike\":\"255\",\"expiration\":\"" + exp2 + "\",\"ratio\":1}]}";
        JsonNode calendarResponse = Json.parse(post("/api/trades/preview", calendar).body());
        JsonNode cal = calendarResponse.get("preview");
        assertThat(cal.get("payoff").size()).isZero();
        assertThat(cal.get("legs").size()).isEqualTo(2);
        assertThat(calendarResponse.get("economics").get("verdict").asText()).isEqualTo("UNAVAILABLE");
        assertThat(calendarResponse.get("economics").get("summary").asText())
                .contains("cannot support an economic verdict");
    }

    @Test
    @Order(24)
    void portfolioSummaryIsAnHonestLiquidationView() throws Exception {
        JsonNode acct = Json.parse(get("/api/account").body()).get("account");
        JsonNode sum = Json.parse(get("/api/portfolio/summary").body());
        // identity: total = cash + shares + open-trade liquidation; P/L measured vs start
        assertThat(sum.get("cashCents").asLong()).isEqualTo(acct.get("cashCents").asLong());
        long total = sum.get("totalValueCents").asLong();
        assertThat(total).isEqualTo(sum.get("cashCents").asLong()
                + sum.get("sharesValueCents").asLong()
                + sum.get("openTradesValueCents").asLong());
        assertThat(sum.get("totalPnlCents").asLong())
                .isEqualTo(total - sum.get("startingCashCents").asLong());
        // reserve stays a lien inside cash — the note must say so, and fees must be disclosed
        assertThat(sum.get("note").asText()).contains("BEFORE close fees");
        assertThat(sum.get("freshness").asText()).isNotEmpty();
        assertThat(sum.has("complete")).isTrue();
    }

    @Test
    @Order(25)
    void dataCenterOverviewSourcesAndGuardedReset() throws Exception {
        // Overview never 500s and carries engine + coverage + job kinds.
        HttpResponse<String> ov = get("/api/data/overview");
        assertThat(ov.statusCode()).isEqualTo(200);
        JsonNode j = Json.parse(ov.body());
        assertThat(j.has("engine")).isTrue();
        assertThat(j.get("jobKinds").toString()).contains("backfill_underlying");
        // Source cards disclose license/use mode (Yahoo = personal-only).
        JsonNode sources = Json.parse(get("/api/data/sources").body()).get("sources");
        assertThat(sources.toString()).contains("Yahoo Finance").contains("PERSONAL");
        JsonNode sourceDoc = Json.parse(get("/api/data/sources").body());
        assertThat(sourceDoc.get("connectors").toString()).contains("Your price-history CSV");
        JsonNode sync = Json.parse(get("/api/data/sync").body());
        assertThat(sync.get("note").asText()).contains("once after a completed market session");
        assertThat(sync.has("cursors")).isTrue();
        // A local user-owned daily CSV enters the observed store through multipart upload; the
        // browser never fetches a provider endpoint or sends a server filesystem path.
        String boundary = "StrikeBenchBoundary";
        String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"symbol\"\r\n\r\nMSFT\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"basis\"\r\n\r\nRAW\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"sourceLabel\"\r\n\r\nBroker export\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"msft.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\nDate,Close\n2026-07-06,500\n2026-07-07,505\n\r\n"
                + "--" + boundary + "--\r\n";
        HttpResponse<String> imported = http.send(HttpRequest.newBuilder(URI.create(base + "/api/data/import/underlying"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofString(multipart)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(imported.statusCode()).isEqualTo(200);
        assertThat(Json.parse(imported.body()).get("barBasis").asText()).isEqualTo("CLOSE_ONLY");
        // Coverage responds with a matrix + summary.
        assertThat(get("/api/data/coverage").statusCode()).isEqualTo(200);
        // Reset without confirm is refused.
        assertThat(post("/api/data/reset", "{\"tier\":\"MARKET_DATA\"}").statusCode()).isEqualTo(400);
        // Admin gate: a PROXIED request (X-Forwarded-For present, auth off) can't wipe — 401, not 200.
        HttpResponse<String> proxied = http.send(HttpRequest.newBuilder(URI.create(base + "/api/data/reset"))
                        .header("Content-Type", "application/json").header("X-Forwarded-For", "203.0.113.7")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"tier\":\"EVERYTHING\",\"confirm\":true}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(proxied.statusCode()).isEqualTo(401);
        HttpResponse<String> proxiedSchedule = http.send(HttpRequest.newBuilder(URI.create(base + "/api/data/sync/schedule"))
                        .header("Content-Type", "application/json").header("X-Forwarded-For", "203.0.113.7")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\":true,\"source\":\"auto\",\"symbols\":[\"AAPL\"],\"years\":1}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(proxiedSchedule.statusCode()).isEqualTo(401);
        HttpResponse<String> proxiedSync = http.send(HttpRequest.newBuilder(URI.create(base + "/api/data/jobs"))
                        .header("Content-Type", "application/json").header("X-Forwarded-For", "203.0.113.7")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"sync_underlying\",\"params\":{\"symbols\":[\"AAPL\"]}}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(proxiedSync.statusCode()).isEqualTo(401);
        // A backfill job can be started and is listed.
        HttpResponse<String> start = post("/api/data/jobs",
                "{\"kind\":\"backfill_underlying\",\"params\":{\"symbols\":[\"AAPL\"],\"from\":\"2026-04-01\",\"to\":\"2026-06-30\"}}");
        assertThat(start.statusCode()).isEqualTo(200);
        String jobId = Json.parse(start.body()).get("id").asText();
        assertThat(jobId).isNotBlank();
        assertThat(get("/api/data/jobs").body()).contains(jobId);
    }

    @Test
    @Order(26)
    void workspacePersistsVersionsAndStreamsEvents() throws Exception {
        // Fresh workspace: rev 0, no state.
        JsonNode empty = Json.parse(get("/api/workspace").body());
        assertThat(empty.get("rev").asLong()).isZero();

        // PUT stores the client-owned blob verbatim; revisions increment per write.
        HttpResponse<String> put1 = put("/api/workspace",
                "{\"route\":\"#/research/AAPL\",\"symbol\":\"AAPL\",\"forms\":{\"discover\":{\"goal\":\"INCOME\"}}}");
        assertThat(put1.statusCode()).isEqualTo(200);
        long rev1 = Json.parse(put1.body()).get("rev").asLong();
        long rev2 = Json.parse(put("/api/workspace",
                "{\"route\":\"#/trade/context\",\"symbol\":\"QQQ\"}").body()).get("rev").asLong();
        assertThat(rev2).isEqualTo(rev1 + 1);
        JsonNode got = Json.parse(get("/api/workspace").body());
        assertThat(got.get("rev").asLong()).isEqualTo(rev2);
        assertThat(got.get("state").get("symbol").asText()).isEqualTo("QQQ");

        // Garbage and non-object bodies are rejected, arrays included.
        assertThat(put("/api/workspace", "not json").statusCode()).isEqualTo(400);
        assertThat(put("/api/workspace", "[1,2]").statusCode()).isEqualTo(400);
        // Oversized blobs are rejected — the workspace is forms and ids, not payload storage.
        assertThat(put("/api/workspace", "{\"big\":\"" + "x".repeat(140 * 1024) + "\"}").statusCode()).isEqualTo(400);

        // The event bus announced both writes; /api/events replays from Last-Event-ID.
        assertThat(server.events.since(0)).anyMatch(e -> e.type().equals("workspace.updated"));

        // Dataset switches publish dataset.selected (the scenario banner listens for this).
        long before = server.events.since(0).size();
        put("/api/datasets/active", "{\"id\":\"observed\"}");
        assertThat(server.events.since(0).stream().skip(before))
                .anyMatch(e -> e.type().equals("dataset.selected") && "observed".equals(e.data().get("active")));

        // SSE endpoint speaks event-stream and replays from the precise event boundary.
        long workspaceSeq = server.events.since(0).stream()
                .filter(e -> e.type().equals("workspace.updated"))
                .mapToLong(io.liftandshift.strikebench.util.EventBus.Event::seq).max().orElseThrow();
        HttpRequest sse = HttpRequest.newBuilder(URI.create(base + "/api/events"))
                .header("Accept", "text/event-stream").header("Last-Event-ID", String.valueOf(workspaceSeq - 1))
                .timeout(java.time.Duration.ofSeconds(5)).GET().build();
        HttpResponse<java.io.InputStream> stream = http.send(sse, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
        byte[] first = new byte[4096];
        int n = stream.body().read(first);
        String frames = new String(first, 0, Math.max(0, n), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(frames).contains("event: workspace.updated");
        stream.body().close();
    }

    @Test
    @Order(27)
    void prefetchHeaderIsHonoredAndHarmless() throws Exception {
        // Fixture mode always has budget: a prefetch GET serves normally (200 + real payload).
        HttpResponse<String> pf = http.send(HttpRequest.newBuilder(URI.create(base + "/api/research/AAPL/expirations"))
                        .header("X-Priority", "prefetch").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(pf.statusCode()).isEqualTo(200);
        assertThat(Json.parse(pf.body()).get("expirations").size()).isGreaterThan(0);
        // A normal request without the header is untouched by the governor.
        assertThat(get("/api/research/AAPL/expirations").statusCode()).isEqualTo(200);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(32)
    void riskContextDrivesAccountFitAndHeatReports() throws Exception {
        // Declare the REAL account: $19.3K cash BP, $1,000 risk capital (the MU sizing lesson).
        var put = put("/api/account/risk-context", """
                {"nlvCents": 2500000, "cashBpCents": 1932800, "riskCapitalCents": 100000}""");
        assertThat(put.statusCode()).isEqualTo(200);

        // A preview now judges size against MY denominators, not the paper account's.
        var prev = post("/api/trades/preview", """
                {"symbol":"AAPL","strategy":"CREDIT_PUT_SPREAD","qty":1,"legs":[
                  {"action":"SELL","type":"PUT","strike":"250","expiration":"2026-08-21","ratio":1},
                  {"action":"BUY","type":"PUT","strike":"245","expiration":"2026-08-21","ratio":1}]}""");
        assertThat(prev.statusCode()).isEqualTo(200);
        var body = io.liftandshift.strikebench.util.Json.parse(prev.body());
        assertThat(body.has("accountFit")).isTrue();
        var fit = body.get("accountFit");
        assertThat(fit.has("pctOfNlv")).isTrue();
        assertThat(fit.has("pctOfCashBp")).isTrue();
        assertThat(fit.has("pctOfRiskCapital")).isTrue();
        // The analytics contract rides along on every preview.
        var analytics = body.get("preview").get("analytics");
        assertThat(analytics.has("probabilityMap")).isTrue();
        assertThat(analytics.has("executionQuality")).isTrue();
        assertThat(analytics.get("managementPlan").get("rules").size()).isGreaterThan(0);

        // Portfolio heat never 500s and reports the book's aggregates.
        var heat = get("/api/portfolio/heat");
        assertThat(heat.statusCode()).isEqualTo(200);
        var h = io.liftandshift.strikebench.util.Json.parse(heat.body());
        assertThat(h.has("totalMaxLossCents")).isTrue();
        assertThat(h.has("assignmentCashCents")).isTrue();
    }

    @Test
    @Order(28)
    void researchEventStudyRoutesAndAnalogStrategySim() throws Exception {
        // Research has one canonical route family; the dissolved Lab aliases stay gone.
        var q1 = get("/api/research/questions");
        assertThat(q1.statusCode()).isEqualTo(200);
        assertThat(get("/api/lab/questions").statusCode()).isEqualTo(404);
        assertThat(post("/api/lab/question", "{}").statusCode()).isEqualTo(404);
        // Static Research tools must win over /api/research/{symbol}; this route-ordering
        // contract previously sent "notes" through the ticker handler.
        var note = post("/api/research/notes", """
                {"title":"AAPL study","body":"Watch the 10-day follow-through","tags":"momentum"}""");
        assertThat(note.statusCode()).isEqualTo(200);
        String noteId = Json.parse(note.body()).get("id").asText();
        assertThat(get("/api/research/notes").statusCode()).isEqualTo(200);
        assertThat(get("/api/lab/notes").statusCode()).isEqualTo(404);
        assertThat(delete("/api/research/notes/" + noteId).statusCode()).isEqualTo(200);
        String study = """
            {"key":"pullback_rebound","symbol":"AAPL","from":"","to":"",
              "params":{"dropPct":2,"lookback":20,"forward":10}}""";
        var s1 = post("/api/research/event-studies", study);
        assertThat(s1.statusCode()).isEqualTo(200);
        var sj = Json.parse(s1.body());
        assertThat(sj.has("analogPaths")).isTrue();
        assertThat(sj.has("studyKey")).isTrue();
        if (sj.get("analogPaths").size() >= 5) {
            // The strategy sim runs on the SAME analogs and SAYS SO (labeled interpretation).
            String simBody = """
                {"operation":"POSITION","basis":"HISTORICAL_ANALOGS",
                  "context":{"symbol":"AAPL","marketLane":"DEMO","worldId":"demo","datasetId":"observed"},
                  "position":{"key":"BUY_AND_HOLD","qty":1,"entryCostCents":12345,
                    "legs":[{"action":"BUY","type":"STOCK","strike":0,"expiryDay":0,"ratio":1}]},
                  "over":{"model":"GBM","shape":"CHOP","horizonDays":10,"stepsPerDay":1,
                          "driftAnnual":0,"volAnnual":0.3,"jumpsPerYear":0,"jumpMean":0,
                          "jumpVol":0,"tailNu":0,"seed":7,"paths":100},
                  "study":{"key":"pullback_rebound","symbol":"AAPL","from":"","to":"",
                           "params":{"dropPct":2,"lookback":20,"forward":10}}}""";
            var r = post("/api/evaluate", simBody);
            assertThat(r.statusCode()).isEqualTo(200);
            var envelope = Json.parse(r.body());
            assertThat(envelope.get("basis").asText()).isEqualTo("HISTORICAL_ANALOGS");
            var rj = envelope.get("result");
            assertThat(rj.get("pathSource").asText()).isEqualTo("HISTORICAL_ANALOGS");
            assertThat(rj.get("paths").asInt()).isEqualTo(sj.get("analogPaths").size());
            assertThat(rj.get("entryCostCents").asLong()).isEqualTo(12345L);
            assertThat(rj.get("notes").toString()).contains("exact package price already shown");
            // This server is FIXTURES_ONLY: the study ran on demo candles, and the note must say
            // so — 'REAL past occurrences' on fixture data was holistic-review blocker #9.
            assertThat(rj.get("sourceNote").asText()).contains("DEMO-data occurrences");
            assertThat(rj.get("sourceNote").asText()).doesNotContain("REAL past");
            assertThat(rj.get("observed").asBoolean()).isFalse();
            assertThat(sj.get("studyKey").asText()).contains("ev=DEMO_FIXTURE");
            assertThat(rj.get("studyKey").asText()).isEqualTo(sj.get("studyKey").asText());
        }

        // "Exact listed package" is a contract, not copy: an explicit expiration must be
        // used as-is, and a disappeared expiration is refused rather than snapped nearby.
        String exact = """
            {"operation":"POSITION","basis":"PARAMETRIC",
              "context":{"symbol":"AAPL","marketLane":"DEMO","worldId":"demo","datasetId":"observed"},
              "position":{"key":"LONG_CALL","qty":1,"entryCostCents":1000,
                "legs":[{"action":"BUY","type":"CALL","strike":255,"expiration":"2026-08-21","expiryDay":5,"ratio":1}]},
              "over":{"model":"GBM","shape":"CHOP","horizonDays":5,"stepsPerDay":1,
                      "driftAnnual":0,"volAnnual":0.3,"jumpsPerYear":0,"jumpMean":0,
                      "jumpVol":0,"tailNu":6,"seed":17,"paths":40}}""";
        assertThat(post("/api/evaluate", exact).statusCode()).isEqualTo(200);
        String missingExact = exact.replace("2026-08-21", "2099-01-16");
        var refused = post("/api/evaluate", missingExact);
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("exact listed contracts");
    }

    @Test
    @Order(29)
    void simWorldAnchorsDiscloseProvenanceAndCalibration() throws Exception {
        // Release blocker B3: this server runs FIXTURES_ONLY, so an AAPL anchor comes from a
        // DEMO quote — the creator must say so, never "the real market's last price". A made-up
        // ticker starts at $100 and says that. Per-symbol calibration is disclosed with basis.
        // F3: fictional status is NEVER inferred — without the explicit flag, the unknown
        // ticker is EXCLUDED with a reason instead of silently becoming a $100 instrument.
        String noFlag = """
            {"name":"Anchor gate strict","symbols":{"AAPL":1.0,"ZZZFAKE":1.0},"scenario":"CHOP","speed":26}""";
        var rStrict = post("/api/sim/market", noFlag);
        assertThat(rStrict.statusCode()).isEqualTo(201);
        var jStrict = Json.parse(rStrict.body());
        assertThat(jStrict.get("config").get("symbolBetas").has("ZZZFAKE")).isFalse();
        assertThat(jStrict.get("spotBasis").get("ZZZFAKE").asText()).containsIgnoringCase("excluded");
        String strictWorld = jStrict.get("worldId").asText();
        assertThat(put("/api/world", "{\"world\":\"" + strictWorld + "\"}").statusCode()).isEqualTo(200);
        delete("/api/sim/market/" + strictWorld);
        assertThat(Json.parse(get("/api/world").body()).get("world").asText()).isEqualTo("demo");

        String body = """
            {"name":"Anchor gate","symbols":{"AAPL":1.0,"ZZZFAKE":1.0},"scenario":"CHOP","speed":26,
             "allowFictional":true}""";
        var r = post("/api/sim/market", body);
        assertThat(r.statusCode()).isEqualTo(201);
        var j = Json.parse(r.body());
        String aapl = j.get("spotBasis").get("AAPL").asText();
        assertThat(aapl).containsIgnoringCase("demo");
        assertThat(aapl).doesNotContain("real market");
        assertThat(j.get("spotBasis").get("ZZZFAKE").asText()).contains("made-up");
        // Calibration ran for the known symbol and names its basis (HV30 / chain ATM IV).
        assertThat(j.has("calibration")).isTrue();
        assertThat(j.get("calibration").get("AAPL").asText()).containsIgnoringCase("IV");
        // The persisted config carries the per-symbol values so the WORLD actually uses them.
        assertThat(j.get("config").has("symbolIvs")).isTrue();
        // Cleanup: finish the world so later tests see no extra running sessions.
        String wid = j.get("worldId").asText();
        delete("/api/sim/market/" + wid);
    }

    @Test
    @Order(30)
    void worldUniverseBuilderExpandsSectorAndAddsBenchmarks() throws Exception {
        // Holistic review Phase 1: a world is a MARKET — the current sector rides along as the
        // background tier, benchmarks are always present, and anchors are durable records.
        String body = "{\"name\":\"Builder gate\",\"symbols\":{\"AAPL\":1.0},"
                + "\"sectorKey\":\"TECH\",\"scenario\":\"CHOP\",\"speed\":26}";
        var r = post("/api/sim/market", body);
        assertThat(r.statusCode()).isEqualTo(201);
        var j = Json.parse(r.body());
        var betas = j.get("config").get("symbolBetas");
        assertThat(betas.has("AAPL")).isTrue();
        assertThat(betas.has("SPY")).as("benchmarks ride along").isTrue();
        assertThat(betas.has("QQQ")).isTrue();
        // The fixture provider prices only AAPL/SPY/QQQ/TSLA/VTSAX: every other TECH-sector
        // symbol has NO price here, and the no-silent-invention contract requires it to be
        // EXCLUDED with a reason — a recognized real ticker must never start at a made-up $100.
        assertThat(betas.has("MSFT")).as("no price available => excluded, never invented").isFalse();
        boolean msftExcluded = false;
        for (var x : j.get("excluded")) {
            if ("MSFT".equals(x.get("symbol").asText())) {
                msftExcluded = true;
                assertThat(x.get("reason").asText()).containsIgnoringCase("excluded");
            }
        }
        assertThat(msftExcluded).as("the exclusion is a durable, reasoned record").isTrue();
        // Durable anchor records with tier + provenance for everything that DID resolve.
        assertThat(j.get("anchors").size()).isGreaterThanOrEqualTo(3);
        for (var a : j.get("anchors")) assertThat(a.has("basis")).isTrue();
        delete("/api/sim/market/" + j.get("worldId").asText());
    }

    @Test
    @Order(31)
    void riskBudgetIsServerOwnedAndCapsByDeclaredRiskCapital() throws Exception {
        // ONE SOURCE OF TRUTH (review P0): the header/ticket consume this contract; no client
        // percentage arithmetic. Basis is explicit; declared risk capital caps every mode.
        JsonNode rb = Json.parse(get("/api/risk-budget").body());
        assertThat(rb.get("basisType").asText()).isEqualTo("BUYING_POWER");
        long basis = rb.get("basisCents").asLong();
        assertThat(basis).isPositive();
        assertThat(rb.get("modes")).hasSize(3);
        java.util.Map<String, Double> pct = java.util.Map.of("conservative", 0.01, "balanced", 0.02, "aggressive", 0.05);
        for (JsonNode m : rb.get("modes")) {
            String mode = m.get("mode").asText();
            assertThat(pct).containsKey(mode);
            assertThat(m.get("policyBudgetCents").asLong()).isEqualTo(Math.round(basis * pct.get(mode)));
            assertThat(m.get("effectiveBudgetCents").asLong()).isEqualTo(m.get("policyBudgetCents").asLong());
            assertThat(m.get("label").asText()).isIn("Cautious", "Standard", "High");
        }
        // Declare a risk capital SMALLER than the standard budget: every mode must cap to it.
        long cap = Math.round(basis * 0.02) - 5000;
        assertThat(put("/api/account/risk-context",
                "{\"riskCapitalCents\":" + cap + "}").statusCode()).isEqualTo(200);
        JsonNode capped = Json.parse(get("/api/risk-budget").body());
        assertThat(capped.get("explicitCapCents").asLong()).isEqualTo(cap);
        assertThat(capped.get("capSource").asText()).isEqualTo("RISK_CAPITAL");
        for (JsonNode m : capped.get("modes")) {
            assertThat(m.get("effectiveBudgetCents").asLong())
                    .isLessThanOrEqualTo(cap);
        }
        // Internal wire aliases are not a product capability: an obsolete mode is rejected
        // instead of silently changing the caller's capital budget.
        assertThat(post("/api/recommend",
                "{\"symbol\":\"AAPL\",\"thesis\":\"bullish\",\"horizon\":\"month\",\"riskMode\":\"learning\"}")
                .statusCode()).isEqualTo(400);
        // Clean up so later-ordered tests keep the uncapped budget expectations.
        assertThat(put("/api/account/risk-context", "{}").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(32)
    void sparklinesBatchServesClosesAndHonestEmptiesInOneCall() throws Exception {
        // ONE call for the whole card grid; a symbol with no candle source answers
        // available:false with a note — never a fabricated line, never a 404.
        JsonNode r = Json.parse(get("/api/sparklines?symbols=AAPL,SPY,ZZZZ&range=1m").body());
        assertThat(r.get("range").asText()).isEqualTo("1m");
        assertThat(r.get("totalRequested").asInt()).isEqualTo(3);
        assertThat(r.get("sparklines")).hasSize(3);
        java.util.Map<String, JsonNode> bySym = new java.util.HashMap<>();
        for (JsonNode row : r.get("sparklines")) bySym.put(row.get("symbol").asText(), row);
        for (String sym : new String[]{"AAPL", "SPY"}) {
            JsonNode row = bySym.get(sym);
            assertThat(row.get("available").asBoolean()).as(sym + " available").isTrue();
            assertThat(row.get("closes").size()).isGreaterThanOrEqualTo(10);
            assertThat(row.get("dates").size()).isEqualTo(row.get("closes").size());
            assertThat(row.get("freshness").asText()).isEqualTo("FIXTURE");
            assertThat(row.get("evidence").get("provenance").asText()).isEqualTo("DEMO");
        }
        JsonNode dead = bySym.get("ZZZZ");
        assertThat(dead.get("available").asBoolean()).isFalse();
        assertThat(dead.get("note").asText()).isNotBlank();
        assertThat(dead.get("evidence").get("provenance").asText()).isEqualTo("MISSING");
        // Blank symbols default to the active universe and never exceed the cap.
        JsonNode def = Json.parse(get("/api/sparklines").body());
        assertThat(def.get("sparklines").size()).isBetween(1, 16);
        JsonNode capped = Json.parse(get("/api/sparklines?symbols="
                + java.util.stream.IntStream.range(0, 17).mapToObj(i -> "Z" + String.format("%03d", i))
                        .collect(java.util.stream.Collectors.joining(","))).body());
        assertThat(capped.get("sparklines")).hasSize(16);
        assertThat(capped.get("totalRequested").asInt()).isEqualTo(17);
        assertThat(capped.has("batchLimit")).isFalse();
        assertThat(capped.has("truncated")).isFalse();
    }

    @Test
    @Order(33)
    void autoAndManualRecommendationsApplyTheIdenticalDeclaredCapitalCap() throws Exception {
        // ONE RiskBudgetPolicy (review IC-1): the scout must size under the same declared
        // risk-capital cap as manual recommendations — it previously skipped the cap path.
        long cap = 50_000L; // $500 — well under 1% of the default $100k account
        assertThat(put("/api/account/risk-context",
                "{\"riskCapitalCents\":" + cap + "}").statusCode()).isEqualTo(200);
        JsonNode policy = Json.parse(get("/api/risk-budget").body());
        long expected = java.util.stream.StreamSupport.stream(policy.get("modes").spliterator(), false)
                .filter(m -> "balanced".equals(m.get("mode").asText()))
                .findFirst().orElseThrow().get("effectiveBudgetCents").asLong();
        assertThat(expected).isLessThanOrEqualTo(cap);
        JsonNode manual = Json.parse(post("/api/recommend",
                "{\"symbol\":\"AAPL\",\"thesis\":\"bullish\",\"horizon\":\"month\",\"riskMode\":\"balanced\"}").body());
        assertThat(manual.get("riskBudgetCents").asLong()).isEqualTo(expected);
        JsonNode auto = Json.parse(post("/api/recommend/auto",
                "{\"universe\":[\"AAPL\"],\"riskMode\":\"balanced\"}").body());
        assertThat(auto.get("riskBudgetCents").asLong()).isEqualTo(expected);
        for (JsonNode pick : auto.get("picks")) {
            for (JsonNode hz : pick.get("horizons")) {
                for (JsonNode c : hz.get("candidates")) {
                    assertThat(c.get("candidate").get("maxLossCents").asLong())
                            .as("every scouted candidate sized under the declared capital")
                            .isLessThanOrEqualTo(cap);
                }
            }
        }
        assertThat(put("/api/account/risk-context", "{}").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(34)
    void sparklineEvidenceIsExplicitAndWorldSwitchReturnsBootstrap() throws Exception {
        // Fixture-mode candles are DEMO evidence — never inferred OBSERVED (review IC-1).
        JsonNode r = Json.parse(get("/api/sparklines?symbols=AAPL&range=1m").body());
        JsonNode row = r.get("sparklines").get(0);
        assertThat(row.get("evidence").get("provenance").asText()).isEqualTo("DEMO");
        // PUT /api/world responds with the target lane's complete universe bootstrap.
        JsonNode w = Json.parse(put("/api/world", "{\"world\":\"demo\"}").body());
        assertThat(w.get("universe").get("active").get("symbols").size()).isGreaterThan(0);
        assertThat(w.get("universe").get("lane").asText()).isEqualTo("DEMO");
        assertThat(w.get("revision").asLong()).isPositive();
        assertThat(w.get("epoch").asText()).isNotBlank();
        assertThat(Json.parse(get("/api/world").body()).get("epoch").asText()).isEqualTo(w.get("epoch").asText());
    }

    @Test
    @Order(35)
    void paperResetCancelsResidentWorldsAndReturnsToTheBaselineLane() throws Exception {
        var created = Json.parse(post("/api/sim/market", """
                {"name":"Reset lifecycle","symbols":{"AAPL":1.0},"scenario":"CHOP","speed":26}
                """).body());
        String world = created.get("worldId").asText();
        assertThat(post("/api/sim/market/" + world + "/start", "{}").statusCode()).isEqualTo(200);
        assertThat(put("/api/world", "{\"world\":\"" + world + "\"}").statusCode()).isEqualTo(200);

        var reset = post("/api/data/reset", "{\"tier\":\"PAPER\",\"confirm\":true}");
        assertThat(reset.statusCode()).isEqualTo(200);
        assertThat(Json.parse(get("/api/world").body()).get("world").asText()).isEqualTo("demo");
        assertThat(Json.parse(get("/api/sim/market").body()).get("sessions")).isEmpty();
        assertThat(post("/api/sim/market/" + world + "/step", "{}").statusCode()).isEqualTo(404);
        assertThat(Json.parse(get("/api/account").body()).get("account").get("id").asText()).isNotBlank();
    }

    @Test
    @Order(36)
    void everyIdeaCarriesEconomicMeaningWithoutDeletingTeachingCases() throws Exception {
        JsonNode r = Json.parse(post("/api/recommend", """
                {"symbol":"AAPL","thesis":"neutral","horizon":"month","riskMode":"conservative"}
                """).body());
        assertThat(r.get("candidates")).isNotEmpty();
        assertThat(r.get("economicPolicy").asText()).isEqualTo("decision_score");
        assertThat(r.get("economicMessage").asText()).isNotBlank();
        boolean teachingCase = false;
        boolean favorableTeachingCase = false;
        double previousDecisionScore = Double.MAX_VALUE;
        for (JsonNode c : r.get("candidates")) {
            assertThat(c.has("structurallyEligible")).isTrue();
            assertThat(c.has("economicVerdict")).isTrue();
            assertThat(c.has("economics")).isTrue();
            double decisionScore = c.get("decisionScore").asDouble();
            assertThat(decisionScore).isLessThanOrEqualTo(previousDecisionScore);
            previousDecisionScore = decisionScore;
            if ("FAVORABLE".equals(c.get("economicVerdict").asText())) {
                assertThat(c.get("economics").get("observedEvidence").asBoolean()).isFalse();
                assertThat(c.get("economics").get("label").asText()).containsIgnoringCase("teaching market");
                favorableTeachingCase = true;
            }
            teachingCase |= "UNFAVORABLE".equals(c.get("economicVerdict").asText());
        }
        assertThat(favorableTeachingCase)
                .as("the deterministic teaching world demonstrates a favorable case without rigging every result positive")
                .isTrue();
        assertThat(teachingCase).as("unfavorable structures stay visible as counterexamples").isTrue();
        assertThat(r.get("economicMessage").asText()).containsIgnoringCase("generated teaching market")
                .containsIgnoringCase("not evidence of a live-market edge");

        JsonNode auto = Json.parse(post("/api/recommend/auto", """
                {"universe":["SPY"],"horizons":["month"],"riskMode":"conservative"}
                """).body());
        for (JsonNode pick : auto.get("picks")) {
            for (JsonNode horizon : pick.get("horizons")) {
                for (JsonNode c : horizon.get("candidates")) {
                    assertThat(c.has("economics")).isTrue();
                    assertThat(c.has("decisionScore")).isTrue();
                }
            }
        }
    }

    @Test
    @Order(37)
    void finishingTheActiveWorldPublishesAnOrderedBaselineBootstrap() throws Exception {
        JsonNode before = Json.parse(get("/api/world").body());
        JsonNode created = Json.parse(post("/api/sim/market", """
                {"name":"Finish ordering","symbols":{"AAPL":1.0},"scenario":"CHOP","speed":26}
                """).body());
        String world = created.get("worldId").asText();
        assertThat(post("/api/sim/market/" + world + "/start", "{}").statusCode()).isEqualTo(200);
        JsonNode entered = Json.parse(put("/api/world", "{\"world\":\"" + world + "\"}").body());

        JsonNode finished = Json.parse(delete("/api/sim/market/" + world).body());
        assertThat(finished.get("worldReset").asBoolean()).isTrue();
        assertThat(finished.get("world").asText()).isEqualTo("demo");
        assertThat(finished.get("revision").asLong()).isGreaterThan(entered.get("revision").asLong());
        assertThat(finished.get("revision").asLong()).isGreaterThan(before.get("revision").asLong());
        assertThat(finished.get("epoch").asText()).isEqualTo(entered.get("epoch").asText());
        assertThat(finished.at("/universe/active/symbols")).isNotEmpty();

        JsonNode current = Json.parse(get("/api/world").body());
        assertThat(current.get("world").asText()).isEqualTo("demo");
        assertThat(current.get("revision").asLong()).isEqualTo(finished.get("revision").asLong());
    }
}
