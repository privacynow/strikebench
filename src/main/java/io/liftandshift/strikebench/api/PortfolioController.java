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

import java.time.Clock;
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
    private final io.liftandshift.strikebench.eval.EvaluationService evaluations;
    private final AccountObjectiveService objectives;
    private final BookRiskService bookRisk;
    private final Function<Context, String> ownerId;
    private final Function<Context, Account> currentAccount;

    PortfolioController(Db db, Clock clock, PortfolioAccountingService books,
                        PortfolioExportService exports, PositionsService positions,
                        TradeService trades, io.liftandshift.strikebench.eval.EvaluationService evaluations,
                        AccountObjectiveService objectives, BookRiskService bookRisk,
                        Function<Context, String> ownerId,
                        Function<Context, Account> currentAccount) {
        this.db = db;
        this.clock = clock;
        this.books = books;
        this.objectives = objectives;
        this.bookRisk = bookRisk;
        this.exports = exports;
        this.positions = positions;
        this.trades = trades;
        this.evaluations = evaluations;
        this.ownerId = ownerId;
        this.currentAccount = currentAccount;
    }

    void register(JavalinConfig config) {
        PortfolioRoutes.register(config, new PortfolioRoutes.Handlers(
                this::summary,
                ctx -> ctx.json(trades.portfolioHeat(currentAccount.apply(ctx).id())),
                ctx -> ctx.json(AccountRiskContext.load(db, ownerId.apply(ctx))),
                this::updateRiskContext,
                this::riskBudget,
                ctx -> ctx.json(trades.portfolioGreeks(currentAccount.apply(ctx).id())),
                ctx -> ctx.json(bookRisk.lane(ownerId.apply(ctx), currentAccount.apply(ctx).id())),
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

    private void updateRiskContext(Context ctx) {
        AccountRiskContext risk = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, AccountRiskContext.class));
        AccountRiskContext.save(db, ownerId.apply(ctx), risk);
        ctx.json(risk);
    }

    /** What this account is FOR — the declared side of the coherence diagnostic (§3.7). */
    public record ObjectiveDeclaration(String objective, String direction, Long targetExposureCents,
                                       String assignmentPreference) {}

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
                input.assignmentPreference()));
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
        var account = books.account(ownerId.apply(ctx), id);
        var summary = books.summary(ownerId.apply(ctx), id);
        TradeOpenRequest body = ApiRequest.requireBody(
                ApiRequest.bodyOrNull(ctx, TradeOpenRequest.class));
        TradeService.OpenRequest request = TradeController.toAnalysisOpenRequest(body, id);
        var preview = trades.previewTracked(request, summary.bookCashCents());
        var candidate = TradeController.exactPreviewCandidate(request, preview);
        var current = books.portfolioDollarDelta(ownerId.apply(ctx), id, request.symbol());
        var exposure = new io.liftandshift.strikebench.eval.PortfolioExposureContext(
                io.liftandshift.strikebench.position.PositionDomain.ExecutionLane.REAL,
                current.grossCents(), current.netCents(), current.focusSymbolGrossCents(),
                current.complete(), current.basis());
        var evaluation = evaluations.assessExact(request.symbol(), candidate, summary.bookCashCents(),
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED, null,
                preview.ok(), preview.blockReasons(), Math.multiplyExact(preview.feesOpenCents(), 2L), exposure,
                declaredAccountObjective(ownerId.apply(ctx), id));
        String analysisLane = analysisLane(evaluation.evidence().perDimension().get("pricing"));
        ctx.json(new ApiResponses.TrackedPackageAnalysis(preview,
                ApiResponses.EvaluationReceipt.of(evaluation),
                io.liftandshift.strikebench.strategy.StrategyCatalog.identify(
                        request.symbol(), request.qty(), request.legs()), id, account.name(),
                summary.bookCashCents(), analysisLane,
                "Read-only analysis uses " + analysisLane.toLowerCase(java.util.Locale.ROOT)
                        + " evidence and this tracked account's cash. It never changes tracked lots, tax basis,"
                        + " or the Practice account."));
    }

    /**
     * The account's declared objective as the exact assessment's DECLARED side. Direction maps to
     * a thesis the coherence engine understands; NON_DIRECTIONAL declares no direction at all.
     */
    private io.liftandshift.strikebench.eval.DeclaredObjective declaredAccountObjective(String owner, String accountId) {
        AccountObjectiveService.Revision revision = objectives.latest(owner, accountId);
        if (revision == null) return null;
        String thesis = revision.direction() == null || "NON_DIRECTIONAL".equals(revision.direction())
                ? null : revision.direction();
        return new io.liftandshift.strikebench.eval.DeclaredObjective(revision.objective(), thesis, null,
                revision.assignmentPreference(), "this account's declared objective (revision " + revision.revisionNo() + ")");
    }

    private static String analysisLane(io.liftandshift.strikebench.eval.EvidenceLevel pricing) {
        if (pricing == null) return "UNKNOWN";
        return switch (pricing) {
            case OBSERVED_LIVE, OBSERVED_DELAYED, OBSERVED_EOD -> "OBSERVED";
            case DEMO_FIXTURE -> "DEMO";
            case SIMULATED -> "SIMULATED";
            case MODELED -> "MODELED";
            case UNKNOWN -> "UNKNOWN";
        };
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

    /** Cash + share value + executable close value; reserve is a lien inside cash. */
    private void summary(Context ctx) {
        Account account = currentAccount.apply(ctx);
        long sharesValue = 0;
        int sharesCount = 0;
        boolean complete = true;
        for (var position : positions.list(account.id())) {
            sharesCount++;
            if (position.marketValueCents() == null) {
                complete = false;
            } else {
                sharesValue += position.marketValueCents();
            }
        }
        TradeService.OpenPositionsValue open = trades.openPositionsValue(account.id());
        if (!open.complete()) complete = false;
        long total = account.cashCents() + sharesValue + open.valueCents();
        ctx.json(new ApiResponses.PortfolioSummary(account.cashCents(), account.reservedCents(),
                account.buyingPowerCents(), account.startingCashCents(), sharesValue, sharesCount,
                open.openTradesCount(), open.valueCents(), open.unrealizedCents(), total,
                total - account.startingCashCents(), complete, open.freshness(),
                "Liquidation view at current marks: cash + shares + closing every open trade at executable prices, BEFORE close fees. Reserve is part of cash, never double-counted."));
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
