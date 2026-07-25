package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvaluationService;
import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * #10-backend (G1): {@code GET /api/research/{symbol}} decouples the quote slot from the rest of the
 * bundle. A missing quote returns 200 with the quote marked unavailable (with a reason), while
 * history and option-surface evidence are still computed and reported independently.
 */
class ResearchControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"),
            ZoneId.of("America/New_York"));

    private Db db;
    private Javalin app;
    private HttpClient http;
    private String base;

    /** Demo fixtures with the quote read suppressed, so history/options remain but the quote is gone. */
    private static final class QuotelessFixture implements MarketDataProvider {
        private final FixtureProvider delegate;
        QuotelessFixture(FixtureProvider delegate) { this.delegate = delegate; }
        @Override public String name() { return delegate.name(); }
        @Override public Set<Domain> domains() { return delegate.domains(); }
        @Override public List<SymbolMatch> lookup(String query) { return delegate.lookup(query); }
        @Override public Optional<Quote> quote(String symbol) { return Optional.empty(); } // the only suppression
        @Override public List<LocalDate> expirations(String symbol) { return delegate.expirations(symbol); }
        @Override public Optional<OptionChain> chain(String symbol, LocalDate expiration) {
            return delegate.chain(symbol, expiration);
        }
        @Override public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
            return delegate.candles(symbol, from, to);
        }
    }

    @BeforeEach
    void setUp() {
        Map<String, String> conf = new HashMap<>(TestDb.freshConfig());
        conf.put("FIXTURES_ONLY", "true");
        AppConfig cfg = new AppConfig(conf);
        db = Db.forConfig(cfg);

        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService market = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        // Mount the quote-suppressed fixture behind the explicit Demo lane. history/options still resolve.
        market.setDemoSources(new QuotelessFixture(fixture), fixture, fixture);

        EventService events = new EventService(market, db, CLOCK);
        EvaluationService evaluations = new EvaluationService(market, db, CLOCK);
        ResearchController research = new ResearchController(cfg, db, CLOCK, market, events, evaluations,
                ctx -> "test-user",
                ctx -> "demo",
                ctx -> AnalysisContext.OBSERVED,
                (symbol, lane, quote, expirations, optionEvidence) ->
                        new PlanController.PlanSymbolEligibility(true, "unused when quote is present"));

        app = Javalin.create(research::register).start(0);
        base = "http://localhost:" + app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        if (db != null) db.close();
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("body=%s", res.body()).isEqualTo(200);
        return Json.parse(res.body());
    }

    @Test
    void missingQuoteReturns200WithQuoteUnavailableButHistoryAndOptionsPresent() throws Exception {
        JsonNode body = get("/api/research/AAPL");

        // Quote slot: the SAME typed row /api/quotes serves (§5.5), marked unpriced with a reason,
        // and MISSING provenance in the per-input evidence map. The slot is always answered — an
        // absent quote is a stated absence, not a hole the browser has to interpret.
        assertThat(body.at("/quote/priced").asBoolean()).isFalse();
        assertThat(body.at("/quote/markBasis").asText()).isEqualTo("UNAVAILABLE");
        assertThat(body.at("/quote/quoteUnavailableReason").asText()).contains("AAPL");
        JsonNode slotPrice = body.path("quote").path("displayPrice");
        assertThat(slotPrice.isNull() || slotPrice.isMissingNode()).isTrue();
        assertThat(body.path("displayPrice").isNull()).isTrue();
        assertThat(body.get("markBasis").asText()).isEqualTo("UNAVAILABLE");
        assertThat(body.get("quoteUnavailableReason").asText()).contains("AAPL");
        assertThat(body.at("/evidence/inputs/quote/provenance").asText()).isEqualTo("MISSING");

        // History slot: still computed from the same fixtures, independent of the quote.
        assertThat(body.get("hvHistoryDays").asInt()).isGreaterThan(0);
        assertThat(body.at("/evidence/inputs/history/provenance").asText()).isNotEqualTo("MISSING");

        // Option slot: expirations + surface evidence still present.
        assertThat(body.get("expirations").size()).isGreaterThan(0);
        assertThat(body.at("/evidence/inputs/options/provenance").asText()).isNotEqualTo("MISSING");

        // The plan-build affordance honestly reports it cannot proceed without a quote.
        assertThat(body.get("planEligible").asBoolean()).isFalse();
        assertThat(body.get("freshness").asText()).isEqualTo("UNAVAILABLE");
    }

    /**
     * The realized one-month ±1σ envelope is now a backend receipt: {@code bandUp}/{@code bandDn}
     * per bar equal {@code sma20 · exp(±rv20 · √(21/252))} off the SAME rv20/sma20 the response
     * carries (no second client estimator), and are null wherever either input is null.
     */
    @Test
    void historyBandIsBackendComputedFromTheSameRv20AndSma20() throws Exception {
        JsonNode overlays = get("/api/research/AAPL/history?range=1y").get("overlays");

        JsonNode rv20 = overlays.get("rv20");
        JsonNode sma20 = overlays.get("sma20");
        JsonNode bandUp = overlays.get("bandUp");
        JsonNode bandDn = overlays.get("bandDn");

        // One value per candle, same length across every overlay series.
        int n = rv20.size();
        assertThat(n).isGreaterThan(20);
        assertThat(sma20.size()).isEqualTo(n);
        assertThat(bandUp.size()).isEqualTo(n);
        assertThat(bandDn.size()).isEqualTo(n);

        double sigmaMonth = Math.sqrt(21.0 / 252.0);
        int checkedMath = 0;
        int checkedNull = 0;
        for (int i = 0; i < n; i++) {
            boolean inputsPresent = !sma20.get(i).isNull() && !rv20.get(i).isNull();
            if (inputsPresent) {
                double m = sma20.get(i).asDouble();
                double v = rv20.get(i).asDouble();
                assertThat(bandUp.get(i).asDouble()).as("bandUp[%d]", i)
                        .isCloseTo(m * Math.exp(v * sigmaMonth), within(1e-9));
                assertThat(bandDn.get(i).asDouble()).as("bandDn[%d]", i)
                        .isCloseTo(m * Math.exp(-v * sigmaMonth), within(1e-9));
                checkedMath++;
            } else {
                // Null wherever either input is null (the early bars lack trailing history).
                assertThat(bandUp.get(i).isNull()).as("bandUp[%d] null-guard", i).isTrue();
                assertThat(bandDn.get(i).isNull()).as("bandDn[%d] null-guard", i).isTrue();
                checkedNull++;
            }
        }
        // Both branches were actually exercised: real math on some bars, null-guarding on the leading ones.
        assertThat(checkedMath).isGreaterThan(0);
        assertThat(checkedNull).isGreaterThan(0);
    }
}
