package io.liftandshift.strikebench;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JourneySurfaceTest {
    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/resources/public").resolve(relative));
    }

    private static String viewSource() throws Exception {
        return source("js/views.js") + "\n" + source("js/views-portfolio.js")
                + "\n" + source("js/views-data.js");
    }

    @Test void portfolioHasAnExplicitViewModuleBoundary() throws Exception {
        String html = source("index.html");
        String views = source("js/views.js");
        String portfolio = source("js/views-portfolio.js");

        assertThat(html.indexOf("/js/views.js")).isLessThan(html.indexOf("/js/views-portfolio.js"));
        assertThat(html.indexOf("/js/views-portfolio.js")).isLessThan(html.indexOf("/js/app.js"));
        assertThat(views).contains("window.ViewPortfolio.portfolio(root, params)",
                        "window.ViewPortfolio.tradeDetail(content", "var INTENT_BADGE",
                        "function intentBadge(intent)")
                .doesNotContain("async function portfolio(root, params)",
                        "async function tradeDetail(root, params, options)");
        assertThat(portfolio).contains("var S = window.ViewShared", "intentBadge = S.intentBadge",
                "async function portfolio(root, params)",
                "async function tradeDetail(root, params, options)",
                "window.ViewPortfolio = Object.freeze({ portfolio: portfolio, tradeDetail: tradeDetail })")
                .doesNotContain("var INTENT_BADGE", "function intentBadge(intent)");
    }

    @Test void dataCenterHasAnExplicitViewModuleBoundary() throws Exception {
        String html = source("index.html");
        String views = source("js/views.js");
        String data = source("js/views-data.js");

        assertThat(html.indexOf("/js/views.js")).isLessThan(html.indexOf("/js/views-data.js"));
        assertThat(html.indexOf("/js/views-data.js")).isLessThan(html.indexOf("/js/app.js"));
        assertThat(views).contains("window.ViewShared = Object.freeze", "window.ViewData.data(root, params)")
                .doesNotContain("async function data(root, params)", "var STATE_BADGE", "var JOB_BADGE");
        assertThat(data).contains("var S = window.ViewShared", "async function data(root, params)",
                "window.ViewData = Object.freeze({ data: data })");
    }

    @Test void navigationAndRoutesHaveOnePlanCenteredOwnershipModel() throws Exception {
        String html = source("index.html");
        String views = viewSource();
        String app = source("js/app.js");
        String workspace = source("js/workspace.js");
        String contracts = source("js/contracts.js");

        for (String route : List.of("home", "research", "plans", "portfolio", "data")) {
            assertThat(html).contains("data-route=\"" + route + "\"");
        }
        assertThat(html).doesNotContain("data-route=\"trade\"");
        assertThat(views).doesNotContain("async function trade(", "trade: trade", "#/trade/");
        assertThat(views).doesNotContain("#/plan/new", "id === 'new'");
        assertThat(app).doesNotContain("#/plan/new");
        assertThat(workspace).doesNotContain("new\\?symbol");
        assertThat(views).contains("#/research/", "researchRoute");
        assertThat(views.split("Outcomes\\.workspace\\(", -1)).hasSize(2);
        assertThat(views).contains("function planOutcomeWorkspace(config)");
        assertThat(app).doesNotContain("route === 'trade'", "marketContext", "LANE_KEYS", "_laneStash");
        assertThat(app).contains("Product.Routes.valid", "Product.Routes.navOwner");
        assertThat(workspace).contains("Product.Routes.canonical");
        assertThat(contracts).contains("args[0] === 'book'", "keyForSessions", "expiryDays");
        assertThat(workspace).contains("v: 2").doesNotContain(
                "discoverForm", "builderForm", "backtestForm", "verifyForm", "researchStudy",
                "marketThesis", "evidencePrefill", "decisionCache", "recommendResults");
    }

    @Test void namedHorizonsHaveOneFrontendOwner() throws Exception {
        String contracts = source("js/contracts.js");
        String views = viewSource();
        String app = source("js/app.js");
        String scenario = source("js/scenario.js");

        assertThat(contracts).contains("quarter: { sessions: 63, expiryDays: 90 }");
        assertThat(views).contains("Product.Horizon.sessions").doesNotContain("horizonDaysFromContext");
        assertThat(app).contains("Product.Horizon.sessions");
        assertThat(scenario).contains("Product.Horizon.sessions('quarter')");
    }

    @Test void legacyRecommendationAndBacktestRoutesAreNotRegistered() throws Exception {
        String api = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/ApiServer.java"));
        assertThat(api).doesNotContain(
                "routes.post(\"/api/recommend\"",
                "routes.post(\"/api/recommend/auto\"",
                "routes.post(\"/api/recommend/ladder\"",
                "routes.get(\"/api/backtests/{id}\"");
        assertThat(api).contains(
                "routes.post(\"/api/research/scout\"",
                "routes.post(\"/api/research/{symbol}/intent-ladder\"",
                "routes.post(\"/api/plans/{id}/strategy/fit\"",
                "routes.get(\"/api/plans/{id}/outcomes/backtests/{backtestId}\"");
    }

    @Test void supersededJourneyArtifactsStayDeletedWhileTheLiveChartDependencyRemains() throws Exception {
        String views = viewSource();
        String client = source("js/api.js");
        String ui = source("js/ui.js");
        String html = source("index.html");
        String server = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/ApiServer.java"));
        String engine = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/recommend/RecommendationEngine.java"));

        assertThat(views).doesNotContain(
                "function brandName(", "function quickAction(", "function lockedSymbolBar(",
                "function decisionScoreOf(", "function canBacktest(", "function renderEconomicGroups(",
                "function portfolioToday(", "function contextHorizonDays(",
                "function contextHorizonForDays(", "function toolField(", "function metricFact(",
                "App.state.planReturn", "API.prefetch('/api/sparklines?symbols=' + marketSymbols");
        assertThat(views).contains("lazySparklines(tiles, marketSymbols");
        assertThat(client).doesNotContain("recommend($|/)");
        assertThat(server).doesNotContain("path.startsWith(\"/api/backtest\")");
        assertThat(engine).doesNotContain("recommendInner", "ladderInner");
        assertThat(Path.of("scripts/finish-java25-cleanup.sh")).doesNotExist();

        // D3 was reported as dead, but the production range chart still reaches it through
        // UI.candleChart. Keep this pin so a future cleanup cannot delete a live dependency.
        assertThat(ui).contains("function fact(", "fact: fact", "function candleChart(",
                "d3.scaleBand()", "d3.scaleLinear()", "d3.create('svg')");
        assertThat(html).contains("/vendor/d3.min.js");
    }

    @Test void onlyDeliberateResourceAbsenceMapsToHttp404() throws Exception {
        String api = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/ApiServer.java"));
        assertThat(api).contains("ResourceNotFoundException.class", "DataUnavailableException.class");
        assertThat(api).doesNotContain("exception(java.util.NoSuchElementException.class");
    }

    @Test void canonicalOwnersRetainTheFullDecisionAndLearningToolset() throws Exception {
        String views = viewSource();
        String builder = source("js/builder.js");
        String plans = source("js/plans.js");

        assertThat(views).contains(
                "Scout this sector", "Proposed trades", "All strategies", "Option prices", "Scout",
                "Ranked field", "Name your buy price", "Name your sale price", "Choose a protection floor",
                "Your income picture", "One position, separate lenses", "Market odds", "Model futures",
                "Past analogs", "Which proposal handles this evidence best?", "Rule replay",
                "Previous Plan replays", "Price the decision now",
                "Construct across ideas", "Record a real trade (from your broker)", "Your record",
                "Possible futures", "Past evidence");
        assertThat(builder).contains(
                "Every structure, with its payoff shape", "Fit to my limits", "Size this synthetic long by exposure",
                "id: 'builder-add-leg'", "Theoretical max loss", "Theoretical max profit");
        assertThat(plans).contains(
                "/strategy/run", "/strategy/fit", "/strategy/custom", "/scout/run", "/outcomes/run",
                "/outcomes/compare",
                "/outcomes/backtest", "/decision/preview", "/rehearsals");
    }
}
