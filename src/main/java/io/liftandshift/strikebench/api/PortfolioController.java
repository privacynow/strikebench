package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountObjectiveService;
import io.liftandshift.strikebench.paper.AccountRiskContext;
import io.liftandshift.strikebench.paper.BookRiskService;
import io.liftandshift.strikebench.paper.PortfolioAccountingService;
import io.liftandshift.strikebench.paper.PortfolioCsvImport;
import io.liftandshift.strikebench.paper.PortfolioExportService;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.recommend.RiskBudgetPolicy;
import io.liftandshift.strikebench.position.PositionLifecycleDecisionService;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** HTTP controller for paper-book risk and tracked-account accounting. */
final class PortfolioController {
    private final Db db;
    private final Clock clock;
    private final PortfolioAccountingService books;
    private final PortfolioExportService exports;
    private final PositionsService positions;
    private final TradeService trades;
    private final TrackedPackageAnalysisService trackedAnalyses;
    private final AccountObjectiveService objectives;
    private final BookRiskService bookRisk;
    private final PositionLifecycleDecisionService lifecycleDecisions;
    private final Function<Context, String> ownerId;
    private final Function<Context, Account> currentAccount;

    PortfolioController(Db db, Clock clock, PortfolioAccountingService books,
                        PortfolioExportService exports, PositionsService positions,
                        TradeService trades, TrackedPackageAnalysisService trackedAnalyses,
                        AccountObjectiveService objectives, BookRiskService bookRisk,
                        PositionLifecycleDecisionService lifecycleDecisions,
                        Function<Context, String> ownerId,
                        Function<Context, Account> currentAccount) {
        this.db = db;
        this.clock = clock;
        this.books = books;
        this.objectives = objectives;
        this.bookRisk = bookRisk;
        this.lifecycleDecisions = lifecycleDecisions;
        this.exports = exports;
        this.positions = positions;
        this.trades = trades;
        this.trackedAnalyses = trackedAnalyses;
        this.ownerId = ownerId;
        this.currentAccount = currentAccount;
    }

    void register(JavalinConfig config) {
        PortfolioRoutes.register(config, new PortfolioRoutes.Handlers(
                this::practiceBook,
                ctx -> ctx.json(AccountRiskContext.load(db, ownerId.apply(ctx))),
                this::updateRiskContext,
                this::riskBudget,
                ctx -> ctx.json(new ApiResponses.Accounts<>(books.accounts(ownerId.apply(ctx)))),
                this::createAccount,
                ctx -> ctx.json(books.account(ownerId.apply(ctx), ctx.pathParam("id"))),
                this::updateAccount,
                ctx -> ctx.json(books.setArchived(ownerId.apply(ctx), ctx.pathParam("id"), true)),
                ctx -> ctx.json(books.setArchived(ownerId.apply(ctx), ctx.pathParam("id"), false)),
                ctx -> ctx.json(books.summary(ownerId.apply(ctx), ctx.pathParam("id"))),
                this::getObjective,
                this::declareObjective,
                this::analyzePackage,
                this::recordLifecycleDecision,
                this::transactions,
                this::createTransaction,
                ctx -> ctx.json(new ApiResponses.Lots<>(books.lots(ownerId.apply(ctx),
                        ctx.pathParam("id"), Boolean.parseBoolean(ctx.queryParam("includeClosed"))))),
                ctx -> ctx.json(new ApiResponses.Realized<>(books.realizedLots(ownerId.apply(ctx),
                        ctx.pathParam("id"), ApiRequest.intParam(ctx, "year", Year.now(clock).getValue())))),
                this::createValuation,
                ctx -> ctx.json(books.performance(ownerId.apply(ctx), ctx.pathParam("id"))),
                ctx -> ctx.json(books.taxReport(ownerId.apply(ctx), ctx.pathParam("id"),
                        ApiRequest.intParam(ctx, "year", Year.now(clock).getValue()))),
                this::saveTaxReconciliation,
                this::clearTaxReconciliation,
                this::markSection1256,
                this::exportCsv,
                this::exportWorkbook,
                this::importTemplate,
                this::importCsv));
    }

    /**
     * The one versioned Practice Book read. All option-position facts descend from one captured
     * PracticeBookSnapshot; share inventory and declared real-account context are attached as
     * separately owned, non-derived facts.
     */
    private void practiceBook(Context ctx) {
        Account account = currentAccount.apply(ctx);
        TradeService.PracticeBookSnapshot snapshot = trades.practiceBookSnapshot(account.id());
        List<PositionsService.PositionView> sharePositions = positions.list(account.id());
        BookRiskService.PracticeLane risk = bookRisk.practiceLane(snapshot);
        var liquidity = io.liftandshift.strikebench.position.AccountLiquidityReceipt.practice(
                account.id(), account.cashCents(), account.reservedCents(),
                account.buyingPowerCents(), snapshot.heat().earlyAssignmentLiquidityCents(),
                OffsetDateTime.ofInstant(java.time.Instant.parse(snapshot.asOf()), ZoneOffset.UTC));
        ApiResponses.PortfolioSummary summary =
                practiceSummary(account, snapshot, sharePositions, liquidity);
        var selected = bookRisk.selectedBook(snapshot, selectedTradeIds(ctx));
        ctx.header("Cache-Control", "no-store");
        ctx.json(new PracticeBookRead(
                PracticeBookRead.SCHEMA_VERSION,
                snapshot.snapshotId(),
                PracticeBookRead.AccountFacts.from(account),
                summary,
                snapshot,
                sharePositions,
                risk,
                liquidity,
                AccountRiskContext.load(db, ownerId.apply(ctx)),
                selected,
                "One current Practice snapshot owns active option positions, marks, heat, "
                        + "liquidation value, dollar delta, and Greeks. Book risk and any selected "
                        + "subset project that exact snapshot. The Practice ledger owns liquidity; "
                        + "one marked share roster and the user's declared risk limits remain "
                        + "separate named facts. No browser arithmetic is required."));
    }

    private static List<String> selectedTradeIds(Context ctx) {
        String selected = ctx.queryParam("selectedTradeIds");
        if (selected == null || selected.isBlank()) return List.of();
        return java.util.Arrays.stream(selected.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }

    private void updateRiskContext(Context ctx) {
        AccountRiskContext risk = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, AccountRiskContext.class));
        AccountRiskContext.save(db, ownerId.apply(ctx), risk);
        ctx.json(risk);
    }

    /** What this account is FOR — the declared side of the coherence diagnostic (§3.7). */
    public record ObjectiveDeclaration(String objective, String direction, Long targetExposureCents,
                                       String assignmentPreference,
                                       List<AccountObjectiveService.PackageCapacity> packageCapacities,
                                       AccountObjectiveService.AccountCapacityPolicy capacityPolicy) {}

    private void getObjective(Context ctx) {
        String owner = ownerId.apply(ctx);
        String accountId = ctx.pathParam("id");
        ctx.json(new ApiResponses.AccountObjective(
                objectives.latest(owner, accountId), objectives.history(owner, accountId)));
    }

    private void declareObjective(Context ctx) {
        var input = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx, ObjectiveDeclaration.class));
        ctx.status(201).json(objectives.declare(ownerId.apply(ctx), ctx.pathParam("id"),
                input.objective(), input.direction(), input.targetExposureCents(),
                input.assignmentPreference(), input.packageCapacities(), input.capacityPolicy()));
    }

    private void createAccount(Context ctx) {
        var input = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, PortfolioAccountingService.AccountInput.class));
        ctx.status(201).json(books.createAccount(ownerId.apply(ctx), input));
    }

    private void updateAccount(Context ctx) {
        var input = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, PortfolioAccountingService.AccountInput.class));
        ctx.json(books.updateAccount(ownerId.apply(ctx), ctx.pathParam("id"), input));
    }

    private void transactions(Context ctx) {
        String source = ctx.queryParam("source");
        String externalRef = ctx.queryParam("externalRef");
        if (source != null || externalRef != null) {
            if (source == null || externalRef == null) {
                throw new IllegalArgumentException("source and externalRef must be supplied together");
            }
            ctx.json(new ApiResponses.Transactions<>(books.transactionsByReference(ownerId.apply(ctx),
                    ctx.pathParam("id"), source, externalRef)));
            return;
        }
        ctx.json(new ApiResponses.Transactions<>(books.transactions(ownerId.apply(ctx), ctx.pathParam("id"),
                ApiRequest.intParam(ctx, "page", 0), Math.clamp(ApiRequest.intParam(ctx, "size", 50), 1, 500))));
    }

    private void analyzePackage(Context ctx) {
        String id = ctx.pathParam("id");
        TradeOpenRequest body = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, TradeOpenRequest.class));
        TradeService.OpenRequest request = TradeController.toAnalysisOpenRequest(body, id);
        String owner = ownerId.apply(ctx);
        ctx.json(trackedAnalyses.surface(owner, trackedAnalyses.analyze(owner, id, request)));
    }

    private void recordLifecycleDecision(Context ctx) {
        var input = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx,
                PositionLifecycleDecisionService.DecisionInput.class));
        ctx.status(201).json(lifecycleDecisions.recordUserDecision(ownerId.apply(ctx),
                ctx.pathParam("id"), input));
    }

    private void createTransaction(Context ctx) {
        var input = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, PortfolioAccountingService.TransactionInput.class));
        ctx.status(201).json(books.record(ownerId.apply(ctx), ctx.pathParam("id"), input));
    }

    private void createValuation(Context ctx) {
        var input = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, PortfolioAccountingService.ValuationInput.class));
        ctx.status(201).json(books.addValuation(ownerId.apply(ctx), ctx.pathParam("id"), input));
    }

    private void saveTaxReconciliation(Context ctx) {
        var input = ApiRequest.requireBody(ApiRequest.bodyOrNull(ctx,
                PortfolioAccountingService.TaxReconciliationInput.class));
        ctx.json(books.saveTaxReconciliation(ownerId.apply(ctx), ctx.pathParam("id"),
                Integer.parseInt(ctx.pathParam("year")), input));
    }

    private void clearTaxReconciliation(Context ctx) {
        books.clearTaxReconciliation(ownerId.apply(ctx), ctx.pathParam("id"),
                Integer.parseInt(ctx.pathParam("year")));
        ctx.json(new ApiResponses.Ok(true));
    }

    private void markSection1256(Context ctx) {
        ctx.json(new ApiResponses.TransactionsWritten(
                books.markSection1256YearEnd(ownerId.apply(ctx), ctx.pathParam("id"),
                        Integer.parseInt(ctx.pathParam("year")))));
    }

    private void exportCsv(Context ctx) {
        String id = ctx.pathParam("id");
        books.account(ownerId.apply(ctx), id);
        ctx.header("Content-Disposition", "attachment; filename=StrikeBench-transactions-" + id + ".csv");
        ctx.header("Cache-Control", "no-store");
        ctx.contentType("text/csv; charset=utf-8");
        ctx.result(exports.transactionsCsv(ownerId.apply(ctx), id));
    }

    private void exportWorkbook(Context ctx) {
        String id = ctx.pathParam("id");
        int year = ApiRequest.intParam(ctx, "year", Year.now(clock).getValue());
        books.account(ownerId.apply(ctx), id);
        ctx.header("Content-Disposition",
                "attachment; filename=StrikeBench-portfolio-" + id + "-" + year + ".xlsx");
        ctx.header("Cache-Control", "no-store");
        ctx.contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        ctx.result(exports.workbook(ownerId.apply(ctx), id, year));
    }

    private void importTemplate(Context ctx) {
        ctx.header("Content-Disposition", "attachment; filename=StrikeBench-portfolio-import-template.csv");
        ctx.header("Cache-Control", "no-store");
        ctx.contentType("text/csv; charset=utf-8");
        String examples = "\r\ntrade-001,2026-07-01,TRADE,,130,,Opening vertical,0,OPTION,BUY,OPEN,AAPL,CALL,250,2026-08-21,1,100,8.25"
                + "\r\ntrade-001,2026-07-01,TRADE,,130,,Opening vertical,1,OPTION,SELL,OPEN,AAPL,CALL,260,2026-08-21,1,100,3.10"
                + "\r\ninterest-001,2026-07-02,INTEREST,425,0,ORDINARY_INTEREST,Monthly interest,,,,,,,,,,,\r\n";
        ctx.result((PortfolioCsvImport.TEMPLATE_HEADER + examples)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void importCsv(Context ctx) throws java.io.IOException {
        String id = ctx.pathParam("id");
        books.account(ownerId.apply(ctx), id);
        ctx.multipartConfig().maxFileSize(25, io.javalin.config.SizeUnit.MB);
        ctx.multipartConfig().maxTotalRequestSize(26, io.javalin.config.SizeUnit.MB);
        io.javalin.http.UploadedFile file = ctx.uploadedFile("file");
        if (file == null) throw new IllegalArgumentException("CSV file is required");
        ctx.status(201).json(PortfolioCsvImport.run(file.content(), ownerId.apply(ctx), id, books));
    }

    /**
     * THE Practice-account summary projection. Both the canonical Book document and the temporary
     * compatibility endpoint call this exact composer with one captured option snapshot and one
     * captured marked-share roster.
     */
    private static ApiResponses.PortfolioSummary practiceSummary(
            Account account,
            TradeService.PracticeBookSnapshot snapshot,
            List<PositionsService.PositionView> sharePositions,
            io.liftandshift.strikebench.position.AccountLiquidityReceipt liquidity) {
        long sharesValue = 0;
        boolean complete = true;
        for (var position : sharePositions) {
            if (position.marketValueCents() == null) {
                complete = false;
            } else {
                sharesValue += position.marketValueCents();
            }
        }
        TradeService.OpenPositionsValue open = snapshot.openPositions();
        if (!open.complete()) complete = false;
        long total = account.cashCents() + sharesValue + open.valueCents();
        return new ApiResponses.PortfolioSummary(account.cashCents(), account.reservedCents(),
                account.buyingPowerCents(), account.startingCashCents(), sharesValue,
                sharePositions.size(),
                open.openTradesCount(), open.valueCents(), open.unrealizedCents(), total,
                total - account.startingCashCents(), complete, open.freshness(),
                "Liquidation view at current marks: cash + shares + closing every open trade at executable prices, BEFORE close fees. Reserve is part of cash, never double-counted.",
                liquidity);
    }

    private void riskBudget(Context ctx) {
        Account account = currentAccount.apply(ctx);
        AccountRiskContext risk = AccountRiskContext.load(db, ownerId.apply(ctx));
        Long cap = risk.riskCapitalCents() != null && risk.riskCapitalCents() > 0
                ? risk.riskCapitalCents() : null;
        List<ApiResponses.RiskModeBudget> modes = new ArrayList<>();
        for (RecommendationEngine.RiskMode mode : RecommendationEngine.RiskMode.values()) {
            var budget = RiskBudgetPolicy.compute(mode, account.buyingPowerCents(), cap);
            modes.add(new ApiResponses.RiskModeBudget(budget.mode(), budget.label(), budget.percent(),
                    budget.policyBudgetCents(), budget.effectiveBudgetCents(), budget.capped()));
        }
        ctx.json(new ApiResponses.RiskBudget<>("BUYING_POWER", account.buyingPowerCents(), account.type(),
                cap, cap != null ? "RISK_CAPITAL" : null, modes,
                "Per-idea budget = percent \u00d7 buying power (cash minus reserves; this practice "
                        + "account is cash-only, no margin). Your declared risk capital, when set, caps every mode. "
                        + "The screening engine enforces these same numbers server-side.",
                "Buy-shares-at-a-discount ideas are capped by buying power instead \u2014 "
                        + "a cash-secured put sets aside the full purchase price by design."));
    }
}
