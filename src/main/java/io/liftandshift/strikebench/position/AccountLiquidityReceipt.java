package io.liftandshift.strikebench.position;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Authority-aware account liquidity receipt. This composes balances supplied by the existing
 * Practice or tracked-account owners; it is not an account, collateral, or margin calculator.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountLiquidityReceipt(
        String schemaVersion,
        String accountId,
        String lane,
        AuthorityFacts.SignedMoneyFact settlementBalance,
        AuthorityFacts.MoneyFact pendingActivity,
        AuthorityFacts.MoneyFact recordedOrReportedReserve,
        AuthorityFacts.MoneyFact theoreticalShortPutObligation,
        AuthorityFacts.SignedMoneyFact genuinelyFreeBuyingPower,
        AuthorityFacts.RateFact concurrentCollateralIncome,
        AuthorityFacts.SignedMoneyFact reconciliationDifference,
        String reconciliationStatus,
        String reconciliationReason,
        OffsetDateTime evidenceAsOf,
        List<String> sourceRefs
) {
    public static final String SCHEMA_VERSION = "account-liquidity-v1";

    public AccountLiquidityReceipt {
        required(schemaVersion, "schema version");
        required(accountId, "account id");
        required(lane, "lane");
        if (settlementBalance == null || pendingActivity == null
                || recordedOrReportedReserve == null || theoreticalShortPutObligation == null
                || genuinelyFreeBuyingPower == null || concurrentCollateralIncome == null
                || reconciliationDifference == null) {
            throw new IllegalArgumentException("every liquidity fact must be present, even when unavailable");
        }
        required(reconciliationStatus, "reconciliation status");
        required(reconciliationReason, "reconciliation reason");
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }

    public static AccountLiquidityReceipt practice(String accountId, long settlementCents,
                                                     long reserveCents, long freeCents,
                                                     long theoreticalShortPutObligationCents,
                                                     OffsetDateTime asOf) {
        long difference = Math.subtractExact(
                Math.subtractExact(settlementCents, reserveCents), freeCents);
        return new AccountLiquidityReceipt(SCHEMA_VERSION, accountId, "PRACTICE",
                signedMoney(settlementCents, "Exact Practice cash ledger balance; reserve remains inside cash."),
                money(0, "Practice entries settle synchronously, so there is no pending broker activity."),
                money(reserveCents, "Exact reserve held by the canonical Practice ledger."),
                money(theoreticalShortPutObligationCents,
                        "Gross strike obligation across active short puts from canonical trade geometry."),
                signedMoney(freeCents, "Exact Practice cash less exact recorded reserve."),
                AuthorityFacts.RateFact.unavailable(
                        "Practice cash has no broker-reported settlement-fund income receipt."),
                new AuthorityFacts.SignedMoneyFact(difference,
                        PositionDomain.FactAuthority.SYSTEM_CALCULATED,
                        "Settlement less pending activity, reserve, and genuinely free buying power."),
                difference == 0 ? "RECONCILED" : "DIFFERENCE",
                difference == 0
                        ? "Practice settlement, reserve, and buying power reconcile exactly."
                        : "Practice ledger values do not reconcile; this is an invariant failure.",
                asOf, List.of("accounts.cash_cents", "accounts.reserved_cents", "ledger", "trades"));
    }

    /**
     * Compose tracked liquidity. A model obligation never authorizes a free-cash claim; genuinely
     * free buying power needs direct broker evidence or all three broker-reported components.
     */
    public static AccountLiquidityReceipt tracked(String accountId, long ledgerCashCents,
                                                    long theoreticalShortPutObligationCents,
                                                    BrokerEvidence broker) {
        boolean brokerSnapshot = broker != null;
        AuthorityFacts.SignedMoneyFact settlement = brokerSnapshot && broker.settlementCents() != null
                ? brokerSignedMoney(broker.settlementCents(), "Broker-reported settlement/cash balance.")
                : new AuthorityFacts.SignedMoneyFact(ledgerCashCents, PositionDomain.FactAuthority.MODEL_DERIVED,
                        "Cash reconstructed from recorded tracked-account transactions; not a broker buying-power quote.");
        AuthorityFacts.MoneyFact pending = brokerSnapshot && broker.pendingDebitCents() != null
                ? brokerMoney(broker.pendingDebitCents(), "Broker-reported pending debit/activity.")
                : AuthorityFacts.MoneyFact.unavailable("No broker-reported pending-activity value is linked.");
        AuthorityFacts.MoneyFact reserve = brokerSnapshot && broker.reserveCents() != null
                ? brokerMoney(broker.reserveCents(), "Broker-reported reserve or buying-power encumbrance.")
                : AuthorityFacts.MoneyFact.unavailable("No broker-reported reserve is linked; lot geometry is not broker margin.");
        AuthorityFacts.MoneyFact obligation = new AuthorityFacts.MoneyFact(
                theoreticalShortPutObligationCents, PositionDomain.FactAuthority.MODEL_DERIVED,
                "Gross assignment-at-strike obligation for unpaired recorded short puts.");

        AuthorityFacts.SignedMoneyFact free;
        if (brokerSnapshot && broker.buyingPowerCents() != null) {
            free = brokerSignedMoney(broker.buyingPowerCents(), "Broker-reported genuinely available buying power.");
        } else if (brokerSnapshot && broker.settlementCents() != null
                && broker.pendingDebitCents() != null && broker.reserveCents() != null) {
            free = new AuthorityFacts.SignedMoneyFact(
                    Math.subtractExact(Math.subtractExact(broker.settlementCents(), broker.pendingDebitCents()),
                            broker.reserveCents()), PositionDomain.FactAuthority.SYSTEM_CALCULATED,
                    "Computed only from broker-reported settlement, pending activity, and reserve components.");
        } else {
            free = AuthorityFacts.SignedMoneyFact.unavailable(
                    "Genuinely free buying power is unavailable without broker-reported buying power or complete broker liquidity components.");
        }

        AuthorityFacts.RateFact income = brokerSnapshot && broker.collateralIncomeAnnualRatePct() != null
                ? new AuthorityFacts.RateFact(broker.collateralIncomeAnnualRatePct(),
                        broker.collateralIncomeCents(), PositionDomain.FactAuthority.BROKER_REPORTED,
                        "Broker-reported annual settlement-fund rate; collateral income remains separate from option carry.")
                : AuthorityFacts.RateFact.unavailable(
                        "No broker-reported settlement-fund rate is linked; option premium is not substituted.");

        AuthorityFacts.SignedMoneyFact difference;
        String status;
        String reason;
        if (brokerSnapshot && broker.settlementCents() != null && broker.pendingDebitCents() != null
                && broker.reserveCents() != null && free.cents() != null) {
            long delta = Math.subtractExact(
                    Math.subtractExact(Math.subtractExact(broker.settlementCents(), broker.pendingDebitCents()),
                            broker.reserveCents()), free.cents());
            difference = new AuthorityFacts.SignedMoneyFact(delta,
                    PositionDomain.FactAuthority.SYSTEM_CALCULATED,
                    "Broker settlement less broker pending debit, broker reserve, and genuinely free buying power.");
            status = delta == 0 ? "RECONCILED" : "EXPLAINED_DIFFERENCE";
            reason = delta == 0
                    ? "Broker liquidity components reconcile exactly."
                    : "Broker-reported buying power differs from cash arithmetic; broker margin, settlement, or sweep treatment controls.";
        } else {
            difference = AuthorityFacts.SignedMoneyFact.unavailable(
                    "Reconciliation needs broker settlement, pending activity, reserve, and buying power.");
            status = "UNAVAILABLE";
            reason = "Recorded lots disclose theoretical obligation but cannot establish broker buying power.";
        }
        return new AccountLiquidityReceipt(SCHEMA_VERSION, accountId, "TRACKED", settlement,
                pending, reserve, obligation, free, income, difference, status, reason,
                brokerSnapshot ? broker.asOf() : null,
                brokerSnapshot ? List.of("portfolio_valuation:" + broker.valuationId(), "portfolio_lot")
                        : List.of("portfolio_transaction", "portfolio_lot"));
    }

    public record BrokerEvidence(String valuationId, OffsetDateTime asOf, Long settlementCents,
                                 Long pendingDebitCents, Long reserveCents, Long buyingPowerCents,
                                 Double collateralIncomeAnnualRatePct, Long collateralIncomeCents) {}

    private static AuthorityFacts.MoneyFact money(long cents, String basis) {
        return new AuthorityFacts.MoneyFact(cents, PositionDomain.FactAuthority.SYSTEM_CALCULATED, basis);
    }

    private static AuthorityFacts.SignedMoneyFact signedMoney(long cents, String basis) {
        return new AuthorityFacts.SignedMoneyFact(cents, PositionDomain.FactAuthority.SYSTEM_CALCULATED, basis);
    }

    private static AuthorityFacts.MoneyFact brokerMoney(long cents, String basis) {
        return new AuthorityFacts.MoneyFact(cents, PositionDomain.FactAuthority.BROKER_REPORTED, basis);
    }

    private static AuthorityFacts.SignedMoneyFact brokerSignedMoney(long cents, String basis) {
        return new AuthorityFacts.SignedMoneyFact(cents, PositionDomain.FactAuthority.BROKER_REPORTED, basis);
    }

    private static void required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    }
}
