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

        for (String route : List.of("home", "research", "plans", "portfolio", "data")) {
            assertThat(html).contains("data-route=\"" + route + "\"");
        }
        assertThat(html).doesNotContain("data-route=\"trade\"");
        assertThat(views).doesNotContain("async function trade(", "trade: trade", "#/trade/");
        assertThat(views).doesNotContain("#/plan/new", "id === 'new'");
        assertThat(app).doesNotContain("#/plan/new");
        assertThat(views).contains("#/research/", "researchRoute");
        assertThat(views.split("Outcomes\\.workspace\\(", -1)).hasSize(2);
        assertThat(views).contains("function planOutcomeWorkspace(config)");
        assertThat(app).doesNotContain("route === 'trade'", "marketContext", "LANE_KEYS", "_laneStash");
        assertThat(workspace).contains("v: 2").doesNotContain(
                "discoverForm", "builderForm", "backtestForm", "verifyForm", "researchStudy",
                "marketThesis", "evidencePrefill", "decisionCache", "recommendResults");
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
