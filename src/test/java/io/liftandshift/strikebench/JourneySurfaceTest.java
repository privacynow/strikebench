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
        return source("js/views.js") + "\n" + source("js/views-research.js")
                + "\n" + source("js/views-plan.js")
                + "\n" + source("js/views-portfolio.js")
                + "\n" + source("js/views-data.js");
    }

    private static String apiRouteSource() throws Exception {
        StringBuilder routes = new StringBuilder();
        for (String name : List.of("ApiServer", "ApiTelemetry", "CoreRoutes", "CoreController",
                "MarketUniverseView", "MarketStreamController", "SparklineController",
                "PlanRoutes", "DataRoutes",
                "DataController", "ResearchRoutes", "WorldRoutes", "TradeRoutes", "TradeController",
                "ResearchController", "PortfolioRoutes", "PortfolioController", "BrokerRoutes",
                "BrokerController", "OutcomeRoutes", "OutcomeController", "WorldController",
                "DiscoveryRoutes", "DiscoveryController", "PlanController",
                "PlanStrategyController", "PlanOutcomeController", "PlanDecisionController")) {
            routes.append(Files.readString(Path.of(
                    "src/main/java/io/liftandshift/strikebench/api/" + name + ".java"))).append('\n');
        }
        return routes.toString();
    }

    @Test void researchHasAnExplicitViewModuleBoundary() throws Exception {
        String html = source("index.html");
        String views = source("js/views.js");
        String research = source("js/views-research.js");
        String plan = source("js/views-plan.js");
        String plans = source("js/plans.js");

        assertThat(html.indexOf("/js/views.js")).isLessThan(html.indexOf("/js/views-research.js"));
        assertThat(html.indexOf("/js/views-research.js")).isLessThan(html.indexOf("/js/views-plan.js"));
        assertThat(html.indexOf("/js/views-research.js")).isLessThan(html.indexOf("/js/app.js"));
        assertThat(views).contains("window.ViewResearch.route(root, params)",
                        "window.ViewResearch.sectorRail", "window.ViewResearch.lazySparklines",
                        "window.ViewResearch.missingSparkCopy", "stripZeros: stripZeros")
                .doesNotContain("async function research(root, params, embedded)",
                        "async function sectorExplorer(root, context)", "function verdictPanel(",
                        "var THESIS_BADGE");
        assertThat(research).contains("var S = window.ViewShared",
                "async function research(root, params, embedded)",
                "async function sectorExplorer(root, context)", "function verdictPanel(",
                "var THESIS_BADGE", "await research(root, [symbol])",
                "window.ViewPlan.planStartCard(symbol)", "function researchLocalNav(symbol, active)",
                "overview: { label: 'Overview'", "evidence: { label: 'Evidence & scenarios'",
                "options: { label: 'Options'",
                "missingSparkCopy: missingSparkCopy", "stripZeros = S.stripZeros",
                "window.ViewResearch = Object.freeze({")
                .doesNotContain("window.ViewPlan.provisionalStage(root, symbol)")
                .doesNotContain("async function home(root, params)");
        assertThat(plan).contains("startPlan = S.startPlan", "await startPlan(handoff, planIntentDestination(intent))")
                .doesNotContain("PlanStore.promote", "function provisionalPlanStage(");
        assertThat(plans).doesNotContain("function promote(fields)", "function provisional(symbol)",
                "promote: promote", "provisional: provisional");
    }

    @Test void planJourneyHasAnExplicitViewModuleBoundary() throws Exception {
        String html = source("index.html");
        String views = source("js/views.js");
        String research = source("js/views-research.js");
        String plan = source("js/views-plan.js");

        assertThat(html.indexOf("/js/views.js")).isLessThan(html.indexOf("/js/views-plan.js"));
        assertThat(html.indexOf("/js/views-research.js")).isLessThan(html.indexOf("/js/views-plan.js"));
        assertThat(html.indexOf("/js/views-plan.js")).isLessThan(html.indexOf("/js/views-portfolio.js"));
        assertThat(html.indexOf("/js/views-plan.js")).isLessThan(html.indexOf("/js/app.js"));
        assertThat(views).contains("window.ViewPlan.planWorkspace(root, params)",
                        "window.ViewPlan.economicAssessmentBlock", "window.ViewPlan.stages")
                .doesNotContain("async function planWorkspace(root, params)",
                        "function planOutcomeWorkspace(config)", "function candidateCard(",
                        "var PLAN_STAGES", "var THESIS_BADGE");
        assertThat(research).contains("var THESIS_BADGE");
        assertThat(plan).contains("var S = window.ViewShared",
                "async function planWorkspace(root, params)", "function planOutcomeWorkspace(config)",
                "function candidateCard(", "var PLAN_STAGES = Object.freeze([",
                "verdictPanel = window.ViewResearch.verdictPanel", "stages: PLAN_STAGES",
                "window.ViewPlan = Object.freeze({")
                .doesNotContain("var THESIS_BADGE");
    }

    @Test void portfolioHasAnExplicitViewModuleBoundary() throws Exception {
        String html = source("index.html");
        String views = source("js/views.js");
        String plan = source("js/views-plan.js");
        String portfolio = source("js/views-portfolio.js");

        assertThat(html.indexOf("/js/views.js")).isLessThan(html.indexOf("/js/views-portfolio.js"));
        assertThat(html.indexOf("/js/views-portfolio.js")).isLessThan(html.indexOf("/js/app.js"));
        assertThat(views).contains("window.ViewPortfolio.portfolio(root, params)", "var INTENT_BADGE",
                        "function intentBadge(intent)")
                .doesNotContain("async function portfolio(root, params)",
                        "async function tradeDetail(root, params, options)");
        assertThat(plan).contains("window.ViewPortfolio.tradeDetail(content")
                .doesNotContain("async function tradeDetail(root, params, options)");
        assertThat(portfolio).contains("var S = window.ViewShared", "intentBadge = S.intentBadge",
                "var guideBlock = window.ViewPlan.guideBlock",
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

    @Test void browserAssetsAreBuildStampedAndRevalidated() throws Exception {
        String sourceHtml = source("index.html");
        String builtHtml = Files.readString(Path.of("target/classes/public/index.html"));
        String server = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/ApiServer.java"));

        assertThat(sourceHtml).contains("/css/app.css?v=@asset.version@",
                "/js/app.js?v=@asset.version@");
        assertThat(builtHtml).doesNotContain("@asset.version@")
                .containsPattern("/css/app\\.css\\?v=\\d{14}")
                .containsPattern("/js/app\\.js\\?v=\\d{14}");
        assertThat(server).contains("public, max-age=0, must-revalidate")
                .doesNotContain("sf.headers = Map.of(\"Cache-Control\", \"no-store\")");
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
        String surface = apiRouteSource();
        assertThat(surface).doesNotContain(
                "routes.post(\"/api/recommend\"",
                "routes.post(\"/api/recommend/auto\"",
                "routes.post(\"/api/recommend/ladder\"",
                "routes.get(\"/api/backtests/{id}\"");
        assertThat(surface).contains(
                "routes.post(\"/api/research/scout\"",
                "routes.post(\"/api/research/{symbol}/intent-ladder\"",
                "routes.post(\"/api/plans/{id}/strategy/fit\"",
                "routes.get(\"/api/plans/{id}/outcomes/backtests/{backtestId}\"");
    }

    @Test void planRoutesHaveOneRegistrationOwner() throws Exception {
        String api = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/ApiServer.java"));
        String controller = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/PlanController.java"));
        String routes = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/PlanRoutes.java"));

        assertThat(api).contains("planController.register(c);")
                .doesNotContain("PlanRoutes.register(", "c.routes.get(\"/api/plans",
                        "c.routes.post(\"/api/plans", "c.routes.put(\"/api/plans",
                        "c.routes.delete(\"/api/plans");
        assertThat(controller).contains("PlanRoutes.register(config, new PlanRoutes.Handlers(");
        assertThat(routes).contains("public record Handlers(",
                "config.routes.get(\"/api/plans\"", "config.routes.post(\"/api/plans\"",
                "config.routes.post(\"/api/plans/{id}/decision/trade\"",
                "config.routes.post(\"/api/plans/{id}/manage/review\"");
    }

    @Test void apiDomainsHaveExplicitRegistrationOwners() throws Exception {
        String api = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/ApiServer.java"));

        String core = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/CoreController.java"));
        assertThat(api).contains("coreController.register(c, telemetry);")
                .doesNotContain("CoreRoutes.register(c, new CoreRoutes.Handlers(",
                        "private void quotesBatch(", "private void marketStream(",
                        "private void sparklines(", "private void workspaceGet(");
        assertThat(core).contains("CoreRoutes.register(config, new CoreRoutes.Handlers(");
        String telemetry = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/ApiTelemetry.java"));
        assertThat(api).contains("telemetry.register(c);", "telemetry.recordError();")
                .doesNotContain("class IpThrottle", "private void recordLatency(");
        assertThat(telemetry).contains("final class ApiTelemetry", "static final class IpThrottle",
                "void metrics(Context ctx)");
        String plan = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/PlanController.java"));
        assertThat(api).contains("planController.register(c);");
        assertThat(plan).contains("PlanRoutes.register(config, new PlanRoutes.Handlers(");
        assertThat(plan).contains("strategyController::planStrategyRun",
                "strategyController::planScoutRun", "planOutcomeController::planOutcomeRun",
                "planDecisionController::planDecisionTrade", "planDecisionController::planManageReview")
                .doesNotContain("private void planStrategyRun(", "private void planOutcomeRun(",
                        "private void planDecisionTrade(", "private void planManageReview(");
        String planOutcomes = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/PlanOutcomeController.java"));
        String planDecisions = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/PlanDecisionController.java"));
        assertThat(planOutcomes).contains("void planOutcomeRun(", "void planBacktestRun(")
                .doesNotContain("void planDecisionTrade(", "void planManageReview(",
                        "void planRehearsalCreate(");
        assertThat(planDecisions).contains("void planDecisionTrade(", "void planManageReview(",
                "void planRehearsalCreate(")
                .doesNotContain("void planOutcomeRun(", "void planBacktestRun(");
        assertThat(api).doesNotContain("private void planCreate(",
                "private void planStrategyRun(", "private void planOutcomeRun(",
                "private void planDecisionTrade(", "private void planManageReview(");
        String portfolio = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/PortfolioController.java"));
        assertThat(api).contains("portfolioController.register(c);");
        assertThat(portfolio).contains("PortfolioRoutes.register(config, new PortfolioRoutes.Handlers(");
        String trade = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/TradeController.java"));
        assertThat(api).contains("tradeController.register(c);");
        assertThat(trade).contains("TradeRoutes.register(config, new TradeRoutes.Handlers(");
        assertThat(api).doesNotContain("private void tradePreview(",
                "private void tradeCreate(", "private void positionsList(",
                "private void auditPage(", "private void adminSnapshot(");
        String data = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/DataController.java"));
        assertThat(api).contains("dataController.register(c);");
        assertThat(data).contains("DataRoutes.register(config, new DataRoutes.Handlers(");
        assertThat(api).doesNotContain("private void dataOverview(",
                "private void dataSyncPlan(", "private void dataResetRoute(",
                "private void datasetSetActive(");
        String research = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/ResearchController.java"));
        assertThat(api).contains("researchController.register(c);");
        assertThat(research).contains("ResearchRoutes.register(config, new ResearchRoutes.Handlers(",
                "private void symbolResearch(Context ctx)",
                "PlanEligibility planEligibility");
        assertThat(api).doesNotContain("private void noteCreate(",
                "private void expirations(Context", "private void history(Context",
                "private void calibrationResolve(", "private void research(Context");
        String broker = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/BrokerController.java"));
        assertThat(api).contains("brokerController.register(c);");
        assertThat(broker).contains("BrokerRoutes.register(config, new BrokerRoutes.Handlers(");
        assertThat(api).doesNotContain("private void brokerVerify(",
                "private void brokerPreview(", "private void brokerPlace(",
                "private void brokerCancel(");
        String world = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/WorldController.java"));
        assertThat(api).contains("worldController.register(c);");
        assertThat(world).contains("WorldRoutes.register(config, new WorldRoutes.Handlers(");
        assertThat(api).doesNotContain("private void simMarketCreate(",
                "private void simMarketReport(", "private void resolveWorldInBackground(");
        String outcome = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/OutcomeController.java"));
        assertThat(api).contains("outcomeController.register(c);");
        assertThat(outcome).contains("OutcomeRoutes.register(config, new OutcomeRoutes.Handlers(");
        assertThat(api).doesNotContain("private void evaluate(Context ctx)",
                "private Object simCompareResult(", "private Object simStrategyResult(",
                "private Map<String, Object> riskNeutralPositionResult(");
        String discovery = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/DiscoveryController.java"));
        assertThat(api).contains("discoveryController.register(c);");
        assertThat(discovery).contains("DiscoveryRoutes.register(config, new DiscoveryRoutes.Handlers(",
                "ApiResponses.DecisionCompetition decisionCompetition(");
        assertThat(api).doesNotContain("private void welcomeTeachingExample(",
                "private void researchScout(", "private void researchIntentLadder(",
                "private void opportunities(", "private void optimize(",
                "decisionEvaluationResult(", "BUY_AND_HOLD");
        assertThat(api).doesNotContain(
                "c.routes.get(\"/api/metrics\"",
                "c.routes.get(\"/api/research/",
                "c.routes.get(\"/api/world\"",
                "c.routes.post(\"/api/trades",
                "c.routes.get(\"/api/portfolio",
                "c.routes.get(\"/api/data/",
                "c.routes.get(\"/api/broker/");
    }

    @Test void apiUsesNamedTopLevelResponseContracts() throws Exception {
        String api = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/ApiServer.java"));
        String contracts = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/ApiResponses.java"));
        String portfolio = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/PortfolioController.java"));
        String trade = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/TradeController.java"));
        String data = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/DataController.java"));
        String research = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/ResearchController.java"));
        String discovery = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/DiscoveryController.java"));

        assertThat(api).doesNotContain("ctx.json(Map.of(", "ctx.json(body)",
                "Map<String, Object> tradePreviewPayload");
        assertThat(research).contains("new ApiResponses.ResearchDetail<>(");
        assertThat(discovery).contains("new ApiResponses.DecisionCompetition(",
                "new ApiResponses.DecisionBaseline(");
        assertThat(portfolio).contains("new ApiResponses.PortfolioSummary(");
        assertThat(trade).contains("new ApiResponses.TradePreviewResponse(",
                "new ApiResponses.TradeDetail<>(");
        assertThat(data).contains("new ApiResponses.DataOverview<>(",
                "new ApiResponses.DataSyncPlan<>(");
        assertThat(contracts).contains("public record ErrorBody(",
                "public record TradePreviewResponse(", "public record ResearchDetail<",
                "public record DataSyncPlan<", "public record PortfolioSummary(",
                "public record DecisionCompetition(", "public record DecisionBaseline(");
    }

    @Test void httpControllersDoNotOwnDomainPersistence() throws Exception {
        String server = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/ApiServer.java"));
        String trades = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/TradeController.java"));
        String worlds = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/WorldController.java"));
        String transition = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/WorldTransitionService.java"));

        assertThat(server).doesNotContain("db.query(", "db.exec(");
        assertThat(trades).doesNotContain("Db.queryOn(", "db.query(", "db.exec(");
        assertThat(worlds).doesNotContain("Db.queryOn(", "db.query(", "db.exec(");
        assertThat(transition).contains("db.tx(", "Db.execOn(");
    }

    @Test void scenarioAndDecisionVolatilityHaveOneResolver() throws Exception {
        String resolver = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/sim/MarketVolatilityResolver.java"));
        String outcome = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/OutcomeController.java"));
        String discovery = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/DiscoveryController.java"));

        assertThat(resolver).contains("MarketHours.tradingDateAfter(",
                "public SimulationEngine.MarketVolInput resolve(",
                "public Double atmIv(");
        assertThat(outcome).contains("marketVolatility.resolve(", "marketVolatility.atmIv(")
                .doesNotContain("new io.liftandshift.strikebench.sim.SimulationEngine.MarketVolInput(",
                        "Scenario horizons are trading sessions. Resolve that date");
        assertThat(discovery).contains("marketVolatility.atmIv(")
                .doesNotContain("market.expirations(result.symbol()");
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

    @Test void decisionPolicyIsTheOnlyStrategyRankingContract() throws Exception {
        String candidate = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/recommend/Candidate.java"));
        String engine = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/recommend/RecommendationEngine.java"));
        String scout = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/recommend/AutoRecommender.java"));
        String discovery = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/DiscoveryController.java"));
        String views = source("js/views-plan.js");
        String learn = source("js/learn.js");

        assertThat(candidate).doesNotContain("double score");
        assertThat(engine).doesNotContain("Candidate::score", "double score = 100 *");
        assertThat(scout).contains("private final EvaluationService evaluations")
                .doesNotContain("autoScore(", "withEvaluationService", "rankingScore");
        assertThat(discovery).contains("no alternate ranking was substituted")
                .doesNotContain("showing the screened order", "partial evaluation: keep screen order");
        assertThat(views).doesNotContain("Screen score", "c.score");
        assertThat(learn).contains("UNKNOWN receives no evidence credit")
                .doesNotContain("screenscore:", "UNKNOWN gates the decision score to non-viable");
    }

    @Test void opportunityScanningIsOutsideTheEvaluationKernel() throws Exception {
        String evaluation = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/eval/EvaluationService.java"));
        String scanner = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/recommend/OpportunityScanner.java"));
        String discovery = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/DiscoveryController.java"));

        assertThat(evaluation).doesNotContain("RecommendationEngine", "ScanResult", " scan(",
                "newVirtualThreadPerTaskExecutor", "Semaphore");
        assertThat(scanner).contains("class OpportunityScanner", "newVirtualThreadPerTaskExecutor",
                "evaluations.evaluate(", "evaluations.persist(");
        assertThat(discovery).contains("opportunityScanner.scan(")
                .doesNotContain("evaluations.scan(");
    }

    @Test void trackedAccountTaxCopyPreservesTheReconciliationBoundary() throws Exception {
        String portfolio = source("js/views-portfolio.js");
        assertThat(portfolio).contains("always tracks lots, basis, income, and realized gains",
                        "add a reconciliation scenario — never a claim about tax owed",
                        "Not tax advice. Recorded facts and a bounded user-rate scenario")
                .doesNotContain("estimates current capital-gains and income tax");
    }

    @Test void everyExecutableFillUsesOneBookRule() throws Exception {
        String rule = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/market/ExecutablePrice.java"));
        String marks = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/paper/MarksSource.java"));
        String recommendations = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/recommend/RecommendationEngine.java"));
        String backtester = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/backtest/Backtester.java"));
        String replay = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/backtest/HistoricalReplayKernel.java"));

        assertThat(rule).contains("forAction(", "bid.compareTo(ask) > 0", "midpoint(");
        assertThat(marks).contains("ExecutablePrice.forAction(bid, ask, action)");
        assertThat(recommendations).contains("ExecutablePrice.forAction(");
        assertThat(backtester).contains("ExecutablePrice.forAction(");
        assertThat(replay).contains("ExecutablePrice.forAction(", "ExecutablePrice.midpoint(");
    }

    @Test void everyReplayUsesExplicitLaneOwnedModelInputs() throws Exception {
        String inputs = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/backtest/BacktestModelInputs.java"));
        String backtester = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/backtest/Backtester.java"));
        String replay = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/backtest/HistoricalReplayKernel.java"));
        String controller = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/PlanOutcomeController.java"));

        assertThat(inputs).contains("market.riskFreeRateQuote(", "not a historical yield curve");
        assertThat(backtester).contains("BacktestModelInputs.resolve(", "modelInputs.annualRate()")
                .doesNotContain("RISK_FREE", "FLAT_IV");
        assertThat(replay).doesNotContain("private static final double RATE")
                .contains("double annualRate");
        assertThat(controller).contains("PlanController.worldParam(root.activeWorld(ctx))",
                "root.ownerId(ctx), world");
    }

    @Test void everyHistoricalReplayUsesOneNoLookAheadTimeline() throws Exception {
        String backtester = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/backtest/Backtester.java"));
        String replay = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/backtest/HistoricalReplayKernel.java"));

        assertThat(backtester).containsOnlyOnce("replay.forEachDay(replayWindow")
                .containsOnlyOnce("replay.forEachDay(rw")
                .doesNotContain("for (Candle day : window)",
                        "allCandles.stream().filter(c -> !c.date().isAfter(date))",
                        "HistoricalReplayKernel.known(");
        assertThat(replay).contains("public <S> S forEachDay(",
                        "while (visible < window.all().size()",
                        "!window.all().get(visible).date().isAfter(candle.date())")
                .doesNotContain("public static List<Candle> known(");
    }

    @Test void everyPathOutcomeUsesTheCanonicalLegAndOneValuationKernel() throws Exception {
        String simulator = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/sim/ScenarioSimulator.java"));
        String position = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/sim/PathPosition.java"));
        String valuation = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/sim/PathValuationKernel.java"));
        String controller = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/OutcomeController.java"));

        assertThat(simulator).contains("PathPosition position", "PathValuationKernel.value(")
                .doesNotContain("record SimLeg", "portfolioValue(", "BlackScholes.price(");
        assertThat(position).contains("List<Leg> legs", "MarketHours.tradingDaysBetween(");
        assertThat(valuation).contains("for (Leg leg : position.legs())", "BlackScholes.price(",
                "settlementSpot", "leg.multiplier()");
        assertThat(controller).contains("toPathPosition(ctx, position.legs())",
                        "new io.liftandshift.strikebench.sim.PathPosition(today, resolved)")
                .doesNotContain("SimLeg", "toSimLegs");
    }

    @Test void marketTransitionsHaveOneBackendOwner() throws Exception {
        String api = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/ApiServer.java"));
        String data = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/DataController.java"));
        String world = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/WorldController.java"));
        String transitions = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/WorldTransitionService.java"));

        assertThat(api + data + world).contains(
                "worldTransitions.current(ownerId.apply(ctx))",
                "worldTransitions.transition(world, ownerId.apply(ctx))",
                "worldTransitions.afterFinish(wasActive, owner)",
                "worldTransitions.resetAfterDataReset(ownerId.apply(ctx))",
                "return worldTransitions.active(owner);")
                .doesNotContain("active_world:", "active_dataset:", "worldRevision");
        assertThat(transitions).contains(
                "Object universe = universeResolver.apply(world, owner)",
                "persist(owner, world, datasetReset, null)",
                "events.publish(\"world.selected\", event)",
                "public record Result(", "public record FinishResult(");
    }

    @Test void marketFramesAreSharedAndHttpPollingIsFallbackOnly() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/MarketStreamController.java"));
        String broadcaster = Files.readString(Path.of(
                "src/main/java/io/liftandshift/strikebench/api/MarketFrameBroadcaster.java"));
        String app = Files.readString(Path.of(
                "src/main/resources/public/js/app.js"));
        String workspace = Files.readString(Path.of(
                "src/main/resources/public/js/workspace.js"));

        assertThat(controller).contains("broadcaster.subscribe(request", "broadcaster.invalidateOwner(")
                .contains("customSymbols ? requestedSymbols : universe.active().symbols()")
                .doesNotContain("scheduleWithFixedDelay(push", "newScheduledThreadPool(2");
        assertThat(broadcaster).contains("Map<Request, Group> groups", "newVirtualThreadPerTaskExecutor()",
                "if (group.subscribers.isEmpty() || !group.computing.compareAndSet(false, true)) return;",
                "CLIENT_QUEUE_CAPACITY", "while (pending.size() >= CLIENT_QUEUE_CAPACITY) pending.removeFirst()");
        assertThat(app).contains("document.visibilityState === 'visible' && !marketStreamHealthy()",
                        "function marketStreamHealthy()", "closeRealtimeStreams();",
                        "App._eventsES.close()", "subscribeMarketStream();", "subscribeEvents();",
                        "new BroadcastChannel(REALTIME_CHANNEL)", "strikebench.realtime.leader.v1",
                        "relayRealtime('quotes', data)", "relayRealtime('event', data, type)",
                        "if (!App._marketES) subscribeMarketStream();",
                        "if (!App._eventsES) subscribeEvents();", "App.eventsStreamHealthy",
                        "App.marketStreamHealthy = marketStreamHealthy", "msg.kind === 'health'")
                .doesNotContain("setInterval(refreshTape, 45 * 1000)");
        assertThat(workspace).contains("if (document.hidden) return;", "function reconcile()",
                "return adoptRemote(remote, false);", "if (json === lastRemoteJson) return;");
    }

    @Test void canonicalOwnersRetainTheFullDecisionAndLearningToolset() throws Exception {
        String views = viewSource();
        String builder = source("js/builder.js");
        String plans = source("js/plans.js");

        assertThat(views).contains(
                "Scout this sector", "Proposed trades", "All strategies", "Your trade", "Option prices", "Scout",
                "Ranked field", "Name your buy price", "Name your sale price", "Choose a protection floor",
                "Your income picture", "One position, separate lenses", "Market odds", "Model futures",
                "Past analogs", "Which proposal handles this evidence best?", "Rule replay",
                "Previous Plan replays", "Price the decision now",
                "Construct across ideas", "Enter the exact broker trade", "Your record",
                "Possible futures", "Past evidence");
        assertThat(builder).contains(
                "Every structure, with its payoff shape", "Fit to my limits", "Size this synthetic long by exposure",
                "id: 'builder-add-leg'", "UI.vocabulary('theoreticalMaxLoss')", "Theoretical max profit");
        assertThat(plans).contains(
                "/strategy/run", "/strategy/fit", "/strategy/custom", "/scout/run", "/outcomes/run",
                "/outcomes/compare",
                "/outcomes/backtest", "/decision/preview", "/rehearsals");
    }

    @Test void onePositionEditorOwnsHypotheticalAnalysisAndTrackedRecording() throws Exception {
        String html = source("index.html");
        String editor = source("js/position-editor.js");
        String plan = source("js/views-plan.js");
        String portfolio = source("js/views-portfolio.js");
        String workspace = source("js/workspace.js");

        assertThat(html).contains("/js/position-editor.js?v=@asset.version@")
                .satisfies(value -> assertThat(value.indexOf("/js/position-editor.js"))
                        .isLessThan(value.indexOf("/js/views-plan.js")));
        assertThat(editor).contains("window.PositionEditor =", "function parseTerminal(",
                        "function normalizedForAnalysis(", "function recordPayload(",
                        "strategy: 'CUSTOM'", "analysisCurrent && lastAnalysis.identity",
                        "identified.summary",
                        "Blank never means zero", "StrikeBench will not merge them",
                        "Broker-sourced activity requires a stable order or statement reference",
                        "exact total fees from the broker", "one underlying per position package",
                        "adjusted contract", "function refreshVisual()", "Partial payoff only",
                        "Last defensible curve kept on screen", "Record factual activity",
                        "UI.vocabulary('hypothetical')",
                        "API.post('/api/strategies/identify'", "scheduleIdentity()")
                .doesNotContain("API.post('/api/trades/external'", "function identify(",
                        "PositionEditor.identify", "function paint()");
        assertThat(plan).contains("key: 'yourTrade'", "PositionEditor.render(body",
                "Analyze and use in this Plan", "UI.vocabulary('economicExposure')",
                "UI.vocabulary('scenarioLoss')", "UI.vocabulary('practice')");
        assertThat(portfolio).contains("PositionEditor.render(sharedTradeHost",
                        "ensureSharedTradeEditor()", "recordPrimary: true",
                        "UI.vocabulary('campaignEconomicBasis')")
                .doesNotContain("id: 'record-real-card'", "id: 'ext-symbol'", "/api/trades/external");
        assertThat(workspace).contains("'positionDrafts'");
    }

    @Test void traderOwnVocabularyAndDisclosureStateHaveOneOwner() throws Exception {
        String learn = source("js/learn.js");
        String ui = source("js/ui.js");
        String app = source("js/app.js");
        String productCopy = source("index.html") + "\n" + viewSource() + "\n" + source("js/builder.js");

        assertThat(learn).contains("var VOCABULARY = Object.freeze({",
                "assignmentCapital: { label: 'Assignment capital'",
                "brokerReserve: { label: 'Broker reserve'",
                "economicExposure: { label: 'Economic exposure'",
                "theoreticalMaxLoss: { label: 'Theoretical max loss'",
                "scenarioLoss: { label: 'Scenario loss'",
                "hypothetical: { label: 'Hypothetical'",
                "practice: { label: 'Practice'",
                "recordedAtBroker: { label: 'Recorded at broker'",
                "campaignEconomicBasis: { label: 'Campaign-adjusted economic basis'",
                "trackedTaxBasis: { label: 'Tracked tax basis'");
        assertThat(ui).contains("function vocabulary(key, display)",
                "function beginExpandableRender()", "expandableOccurrences.get(baseKey)",
                "beginExpandableRender: beginExpandableRender");
        assertThat(app).contains("if (UI.beginExpandableRender) UI.beginExpandableRender();");
        assertThat(productCopy).doesNotContain("Theoretical worst case", "theoretical worst case",
                "paper trading by default", "paper money", "$100k paper account",
                "Record a real trade (from your broker)", "Paper position opened");
    }
}
