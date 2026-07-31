package io.liftandshift.strikebench.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountRiskContext;
import io.liftandshift.strikebench.paper.BookRiskService;
import io.liftandshift.strikebench.paper.PortfolioAccountingService;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TrackedPackageReadService;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.position.AccountLiquidityReceipt;

import java.util.List;
import java.util.Objects;

/**
 * Versioned, canonical read model for the owner's whole Book.
 *
 * <p>The embedded {@link TradeService.PracticeBookSnapshot} owns the active option roster and
 * its one captured mark map. Heat, liquidation value, dollar delta, and Greeks are fields of that
 * exact snapshot. Book risk and selected-book facts are composed from that same object; liquidity
 * is composed from its heat and the exact Practice account ledger balances. The one marked share
 * roster and the existing liquidation summary are captured once beside it. Every active tracked
 * account is attached as a separately named lane using the accounting service's canonical summary;
 * Practice and Tracked facts are never arithmetically blended.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PracticeBookRead(
        String schemaVersion,
        String snapshotId,
        AccountFacts account,
        ApiResponses.PortfolioSummary summary,
        TradeService.PracticeBookSnapshot snapshot,
        List<PositionsService.PositionView> sharePositions,
        List<TrackedLane> trackedLanes,
        BookRiskService.PracticeLane bookRisk,
        AccountLiquidityReceipt liquidity,
        AccountRiskContext declaredRiskContext,
        BookRiskService.SelectedBookReceipt selectedBook,
        String basis
) {
    public static final String SCHEMA_VERSION = "book-read-v2";

    public PracticeBookRead {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported Practice Book read schema");
        }
        required(snapshotId, "snapshot id");
        if (account == null || summary == null || snapshot == null || bookRisk == null || liquidity == null
                || declaredRiskContext == null || selectedBook == null) {
            throw new IllegalArgumentException(
                    "Practice Book read requires account, summary, snapshot, risk, liquidity, "
                            + "context, and selection receipts");
        }
        sharePositions = sharePositions == null ? List.of() : List.copyOf(sharePositions);
        trackedLanes = trackedLanes == null ? List.of() : List.copyOf(trackedLanes);
        required(basis, "basis");

        if (!snapshotId.equals(snapshot.snapshotId())) {
            throw new IllegalArgumentException(
                    "Practice Book response and snapshot identities must match");
        }
        String accountId = account.accountId();
        if (!accountId.equals(snapshot.accountId())
                || !accountId.equals(liquidity.accountId())
                || !accountId.equals(bookRisk.shareRoster().accountId())
                || !accountId.equals(selectedBook.accountId())) {
            throw new IllegalArgumentException(
                    "Every Practice Book receipt must describe the same account");
        }

        TradeService.PortfolioHeat heat = snapshot.heat();
        if (!Objects.equals(account.settlementBalanceCents(),
                liquidity.settlementBalance().cents())
                || !Objects.equals(account.recordedReserveCents(),
                liquidity.recordedOrReportedReserve().cents())
                || !Objects.equals(account.genuinelyFreeBuyingPowerCents(),
                liquidity.genuinelyFreeBuyingPower().cents())) {
            throw new IllegalArgumentException(
                    "Practice Book account and liquidity balances must be identical");
        }
        if (!Objects.equals(heat.reservedCents(),
                liquidity.recordedOrReportedReserve().cents())
                || !Objects.equals(heat.earlyAssignmentLiquidityCents(),
                liquidity.theoreticalShortPutObligation().cents())) {
            throw new IllegalArgumentException(
                    "Practice Book heat and liquidity must consume the same reserve and obligation facts");
        }
        if (!Objects.equals(summary.cashCents(), account.settlementBalanceCents())
                || !Objects.equals(summary.reservedCents(), account.recordedReserveCents())
                || !Objects.equals(summary.buyingPowerCents(),
                        account.genuinelyFreeBuyingPowerCents())
                || summary.startingCashCents() != account.startingBalanceCents()
                || summary.sharesPositions() != sharePositions.size()
                || summary.openTradesCount() != snapshot.openPositions().openTradesCount()
                || summary.openTradesValueCents() != snapshot.openPositions().valueCents()
                || summary.openTradesUnrealizedCents() != snapshot.openPositions().unrealizedCents()
                || !Objects.equals(summary.liquidity(), liquidity)) {
            throw new IllegalArgumentException(
                    "Practice Book summary must project the same account, shares, snapshot, and liquidity");
        }
        BookRiskService.BookShareRoster roster = bookRisk.shareRoster();
        if (roster.positions() != snapshot.activeTrades().size()) {
            throw new IllegalArgumentException(
                    "Practice Book risk roster must describe the snapshot's active positions");
        }
        if (roster.denominatorCents() != null
                && roster.denominatorCents() != heat.totalMaxLossCents()) {
            throw new IllegalArgumentException(
                    "Practice Book risk denominator must be the snapshot heat total");
        }
        if (!Objects.equals(bookRisk.dollarDeltaNetCents(),
                snapshot.greeks().netDollarDeltaCents())
                || !Objects.equals(bookRisk.dollarDeltaGrossCents(),
                snapshot.greeks().grossDollarDeltaCents())
                || !Objects.equals(bookRisk.thetaCentsPerDay(),
                snapshot.greeks().thetaCentsPerDay())
                || !Objects.equals(bookRisk.vegaCentsPerPoint(),
                snapshot.greeks().vegaCentsPerPoint())) {
            throw new IllegalArgumentException(
                    "Practice Book risk and Greeks must project the same snapshot values");
        }
        for (TrackedLane lane : trackedLanes) {
            if (lane == null || lane.summary() == null || lane.summary().account() == null) {
                throw new IllegalArgumentException("Every tracked Book lane requires its canonical summary");
            }
            if (!lane.accountId().equals(lane.summary().account().id())) {
                throw new IllegalArgumentException(
                        "Tracked Book lane and accounting summary identities must match");
            }
            if (!"ACTIVE".equals(lane.summary().account().status())) {
                throw new IllegalArgumentException("Archived tracked accounts do not belong in the active Book");
            }
            for (TrackedPackageReadService.OpenPackage trackedPackage : lane.openPackages()) {
                if (trackedPackage == null
                        || !lane.accountId().equals(trackedPackage.portfolioAccountId())) {
                    throw new IllegalArgumentException(
                            "Every tracked package must belong to its destination Book lane");
                }
            }
        }
    }

    /** One active, owner-scoped tracked account. Its summary remains the accounting authority. */
    public record TrackedLane(
            String kind,
            String accountId,
            PortfolioAccountingService.PortfolioSummary summary,
            List<TrackedPackageReadService.OpenPackage> openPackages,
            String basis
    ) {
        public TrackedLane {
            if (!"TRACKED".equals(kind)) {
                throw new IllegalArgumentException("tracked Book lane kind must be TRACKED");
            }
            required(accountId, "tracked account id");
            if (summary == null) throw new IllegalArgumentException("tracked account summary is required");
            openPackages = openPackages == null ? List.of() : List.copyOf(openPackages);
            required(basis, "tracked lane basis");
        }

        public static TrackedLane from(
                PortfolioAccountingService.PortfolioSummary summary,
                List<TrackedPackageReadService.OpenPackage> openPackages) {
            if (summary == null || summary.account() == null) {
                throw new IllegalArgumentException("tracked account summary is required");
            }
            return new TrackedLane("TRACKED", summary.account().id(), summary, openPackages,
                    "Owner-scoped tracked ledger and executable observed liquidation marks. "
                            + "Open packages preserve explicit structure or exact opening-transaction "
                            + "identity. This lane is not combined with Practice cash, risk, or P/L.");
        }
    }

    public record AccountFacts(
            String accountId,
            String name,
            String type,
            String worldId,
            long startingBalanceCents,
            Long settlementBalanceCents,
            Long recordedReserveCents,
            Long genuinelyFreeBuyingPowerCents
    ) {
        public AccountFacts {
            required(accountId, "account id");
            required(name, "account name");
            required(type, "account type");
        }

        public static AccountFacts from(Account account) {
            if (account == null) throw new IllegalArgumentException("Practice account is required");
            return new AccountFacts(account.id(), account.name(), account.type(), account.worldId(),
                    account.startingCashCents(), account.cashCents(), account.reservedCents(),
                    account.buyingPowerCents());
        }
    }

    private static void required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
    }
}
