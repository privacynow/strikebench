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

    @Test void navigationAndRoutesHaveOnePlanCenteredOwnershipModel() throws Exception {
        String html = source("index.html");
        String views = source("js/views.js");
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
        String views = source("js/views.js");
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
        String views = source("js/views.js");
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
        String views = source("js/views.js");
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
