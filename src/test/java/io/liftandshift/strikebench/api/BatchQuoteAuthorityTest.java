package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5.5 — one batch-quote authority on the wire. Home reads {@code /api/quotes}; Research reads
 * {@code /api/research/{symbol}}. Both must state the SAME price, change, basis, source, freshness
 * and observation time for one symbol, so the browser never has to choose between two price
 * sources or rebuild one from {@code last} and a previous close. A symbol the market cannot price
 * still gets an answer — an unpriced row that says why.
 */
class BatchQuoteAuthorityTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));

    private static ApiServer server;
    private static Javalin app;
    private static HttpClient http;
    private static String base;

    @BeforeAll
    static void startServer() throws IOException {
        var conf = new java.util.HashMap<>(TestDb.freshConfig());
        conf.put("FIXTURES_ONLY", "true");
        server = ApiServer.create(new AppConfig(conf), CLOCK);
        app = server.start(0);
        base = "http://localhost:" + app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    private static JsonNode get(String path) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("body=%s", response.body()).isEqualTo(200);
        return Json.parse(response.body());
    }

    private static JsonNode row(JsonNode batch, String symbol) {
        for (JsonNode candidate : batch.get("quotes")) {
            if (symbol.equals(candidate.get("symbol").asText())) return candidate;
        }
        throw new AssertionError(symbol + " has no row in " + batch.get("quotes"));
    }

    /** Missing and explicit-null are the same absence on a NON_NULL wire. */
    private static boolean absent(JsonNode node, String field) {
        return node.path(field).isMissingNode() || node.path(field).isNull();
    }

    @Test
    void aBatchRowAndTheResearchReceiptStateOneIdenticalPriceForOneSymbol() throws Exception {
        JsonNode batchRow = row(get("/api/quotes?symbols=AAPL,SPY"), "AAPL");
        JsonNode research = get("/api/research/AAPL");

        assertThat(batchRow.get("priced").asBoolean()).isTrue();
        assertThat(batchRow.get("markBasis").asText()).isIn("MID", "LAST", "PREVIOUS_CLOSE");

        assertThat(batchRow.get("displayPrice").decimalValue())
                .as("display price")
                .isEqualByComparingTo(research.get("displayPrice").decimalValue());
        assertThat(batchRow.get("displayChangePct").asDouble())
                .as("day change")
                .isEqualTo(research.get("displayChangePct").asDouble());
        assertThat(batchRow.get("markBasis").asText())
                .as("basis").isEqualTo(research.get("markBasis").asText());
        assertThat(batchRow.get("priceIsPreviousClose").asBoolean())
                .isEqualTo(research.get("priceIsPreviousClose").asBoolean());
        assertThat(batchRow.get("freshness").asText())
                .as("freshness").isEqualTo(research.get("freshness").asText());
        assertThat(batchRow.get("source").asText())
                .as("source").isEqualTo(research.at("/quote/source").asText());
        assertThat(batchRow.get("asOf").asLong())
                .as("as-of").isEqualTo(research.at("/quote/asOf").asLong());

        // The research document's quote slot IS the batch row: one type, not two that agree today.
        assertThat(research.get("quote")).isEqualTo(batchRow);
    }

    @Test
    void aSymbolWithoutAPriceStillGetsARowThatSaysWhy() throws Exception {
        JsonNode batch = get("/api/quotes?symbols=AAPL,ZZZZ");

        // Every requested symbol is answered. Dropping the unpriced one left the caller to invent
        // its own explanation — or, worse, a price.
        assertThat(batch.get("quotes").size()).isEqualTo(2);
        assertThat(batch.get("requested").asInt()).isEqualTo(2);

        JsonNode unpriced = row(batch, "ZZZZ");
        assertThat(unpriced.get("priced").asBoolean()).isFalse();
        assertThat(unpriced.get("markBasis").asText()).isEqualTo("UNAVAILABLE");
        assertThat(unpriced.get("quoteUnavailableReason").asText())
                .isNotBlank()
                .contains("ZZZZ");
        // No number of any kind that a surface could render as a price or a flat day.
        assertThat(absent(unpriced, "displayPrice")).isTrue();
        assertThat(absent(unpriced, "displayChangePct")).isTrue();
        assertThat(absent(unpriced, "last")).isTrue();
        assertThat(absent(unpriced, "prevClose")).isTrue();

        assertThat(row(batch, "AAPL").get("priced").asBoolean()).isTrue();
    }

    @Test
    void everyBatchRowCarriesTheFullDisplayReceiptInEveryLane() throws Exception {
        JsonNode batch = get("/api/quotes");
        assertThat(batch.get("quotes").size()).isPositive();
        for (JsonNode quoteRow : batch.get("quotes")) {
            String symbol = quoteRow.get("symbol").asText();
            assertThat(quoteRow.has("markBasis")).as(symbol + " basis").isTrue();
            assertThat(quoteRow.has("priced")).as(symbol + " priced flag").isTrue();
            if (quoteRow.get("priced").asBoolean()) {
                assertThat(quoteRow.get("displayPrice").decimalValue())
                        .as(symbol + " display price").isGreaterThan(java.math.BigDecimal.ZERO);
                assertThat(quoteRow.get("freshness").asText()).as(symbol + " freshness").isNotBlank();
                assertThat(quoteRow.get("source").asText()).as(symbol + " source").isNotBlank();
                assertThat(quoteRow.get("asOf").asLong()).as(symbol + " as-of").isPositive();
                assertThat(absent(quoteRow, "quoteUnavailableReason")).isTrue();
            } else {
                assertThat(quoteRow.get("quoteUnavailableReason").asText()).isNotBlank();
            }
        }
    }
}
