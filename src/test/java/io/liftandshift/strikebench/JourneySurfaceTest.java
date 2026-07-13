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

        for (String route : List.of("home", "research", "portfolio", "data")) {
            assertThat(html).contains("data-route=\"" + route + "\"");
        }
        assertThat(html).doesNotContain("data-route=\"trade\"");
        assertThat(views).doesNotContain("async function trade(", "trade: trade", "#/trade/");
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
}
