package io.liftandshift.strikebench.api;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Handler;

/** Canonical HTTP surface for paper-book risk and tracked portfolio accounting. */
public final class PortfolioRoutes {
    public record Handlers(
            Handler summary,
            Handler heat,
            Handler riskContext,
            Handler updateRiskContext,
            Handler riskBudget,
            Handler greeks,
            Handler listAccounts,
            Handler createAccount,
            Handler getAccount,
            Handler updateAccount,
            Handler archiveAccount,
            Handler restoreAccount,
            Handler accountSummary,
            Handler analyzePackage,
            Handler transactions,
            Handler createTransaction,
            Handler lots,
            Handler realized,
            Handler createValuation,
            Handler performance,
            Handler tax,
            Handler saveTaxReconciliation,
            Handler clearTaxReconciliation,
            Handler markSection1256,
            Handler exportCsv,
            Handler exportWorkbook,
            Handler importTemplate,
            Handler importCsv
    ) {}

    private PortfolioRoutes() {}

    public static void register(JavalinConfig config, Handlers h) {
        config.routes.get("/api/portfolio/summary", h.summary());
        config.routes.get("/api/portfolio/heat", h.heat());
        config.routes.get("/api/account/risk-context", h.riskContext());
        config.routes.put("/api/account/risk-context", h.updateRiskContext());
        config.routes.get("/api/risk-budget", h.riskBudget());
        config.routes.get("/api/portfolio/greeks", h.greeks());
        config.routes.get("/api/portfolio/accounts", h.listAccounts());
        config.routes.post("/api/portfolio/accounts", h.createAccount());
        config.routes.get("/api/portfolio/accounts/{id}", h.getAccount());
        config.routes.put("/api/portfolio/accounts/{id}", h.updateAccount());
        config.routes.delete("/api/portfolio/accounts/{id}", h.archiveAccount());
        config.routes.post("/api/portfolio/accounts/{id}/restore", h.restoreAccount());
        config.routes.get("/api/portfolio/accounts/{id}/summary", h.accountSummary());
        config.routes.post("/api/portfolio/accounts/{id}/analyze", h.analyzePackage());
        config.routes.get("/api/portfolio/accounts/{id}/transactions", h.transactions());
        config.routes.post("/api/portfolio/accounts/{id}/transactions", h.createTransaction());
        config.routes.get("/api/portfolio/accounts/{id}/lots", h.lots());
        config.routes.get("/api/portfolio/accounts/{id}/realized", h.realized());
        config.routes.post("/api/portfolio/accounts/{id}/valuations", h.createValuation());
        config.routes.get("/api/portfolio/accounts/{id}/performance", h.performance());
        config.routes.get("/api/portfolio/accounts/{id}/tax", h.tax());
        config.routes.put("/api/portfolio/accounts/{id}/tax/{year}/reconciliation", h.saveTaxReconciliation());
        config.routes.delete("/api/portfolio/accounts/{id}/tax/{year}/reconciliation", h.clearTaxReconciliation());
        config.routes.post("/api/portfolio/accounts/{id}/tax/{year}/mark-1256", h.markSection1256());
        config.routes.get("/api/portfolio/accounts/{id}/export.csv", h.exportCsv());
        config.routes.get("/api/portfolio/accounts/{id}/export.xlsx", h.exportWorkbook());
        config.routes.get("/api/portfolio/import-template.csv", h.importTemplate());
        config.routes.post("/api/portfolio/accounts/{id}/import.csv", h.importCsv());
    }
}
