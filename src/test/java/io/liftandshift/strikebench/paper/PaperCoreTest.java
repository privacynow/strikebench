package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaperCoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));
    private static final LocalDate EXP = LocalDate.of(2026, 8, 21);
    private static final long START = 10_000_000L; // $100k

    @TempDir Path tmp;
    private Db db;
    private AppConfig cfg;
    private AuditLog audit;
    private AccountService accounts;
    private TradeService trades;
    private PositionsService positions;
    private StubMarks marks;

    /**
     * Deterministic mark source: underlying $100; zero-spread books keyed by type+strike
     * (bid = ask = mid, so legacy arithmetic is unchanged). Exact per-contract books with
     * real spreads/one-sided sides go in {@code exact}.
     */
    static final class StubMarks implements MarksSource {
        BigDecimal underlying = new BigDecimal("100.00");
        BigDecimal closeOnValue = null; // when set, the close used for settlement (any date)
        Map<LocalDate, BigDecimal> closesByDate = new java.util.HashMap<>();
        Freshness freshness = Freshness.FIXTURE;
        Map<String, BigDecimal> mids = new java.util.HashMap<>(Map.of(
                "PUT100", new BigDecimal("3.00"),
                "PUT95", new BigDecimal("1.20"),
                "CALL100", new BigDecimal("2.50"),
                "CALL105", new BigDecimal("1.10")));
        Map<String, LegMark> exact = new java.util.HashMap<>();
        @Override public Optional<BigDecimal> underlyingMark(String symbol) { return Optional.of(underlying); }
        @Override public Optional<BigDecimal> closeOn(String symbol, LocalDate date) {
            if (closesByDate.containsKey(date)) return Optional.of(closesByDate.get(date));
            return Optional.ofNullable(closeOnValue);
        }
        @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
            if (leg.isStock()) return Optional.of(new LegMark(underlying, underlying, underlying, null, freshness));
            String key = leg.type().name() + leg.strike().stripTrailingZeros().toPlainString();
            LegMark custom = exact.get(key);
            if (custom != null) return Optional.of(custom);
            BigDecimal mid = mids.get(key);
            return mid == null ? Optional.empty() : Optional.of(new LegMark(mid, mid, mid, 0.25, freshness));
        }
    }

    @BeforeEach
    void setUp() {
        db = TestDb.fresh();
        cfg = new AppConfig(tmp.resolve("none.properties"));
        audit = new AuditLog(db, CLOCK);
        accounts = new AccountService(db, cfg, audit, CLOCK);
        marks = new StubMarks();
        trades = new TradeService(db, cfg, marks, audit, CLOCK);
        positions = new PositionsService(db, marks, audit, CLOCK);
    }

    @AfterEach
    void closeDb() {
        if (db != null) db.close();
    }

    private TradeService.OpenRequest coveredCallOnHeldShares(String accountId) {
        return new TradeService.OpenRequest(accountId, "AAPL", "COVERED_CALL", 1,
                List.of(call(LegAction.SELL, "105", "0")),
                "neutral", "month", "balanced", "EXIT", true);
    }

    private static Leg put(LegAction a, String strike, String prem) {
        return Leg.option(a, OptionType.PUT, new BigDecimal(strike), EXP, 1, new BigDecimal(prem));
    }

    private static Leg call(LegAction a, String strike, String prem) {
        return Leg.option(a, OptionType.CALL, new BigDecimal(strike), EXP, 1, new BigDecimal(prem));
    }

    private TradeService.OpenRequest creditPutSpread(String accountId, int qty) {
        return new TradeService.OpenRequest(accountId, "AAPL", "CREDIT_PUT_SPREAD", qty,
                List.of(put(LegAction.SELL, "100", "0"), put(LegAction.BUY, "95", "0")),
                "bullish", "month", "balanced");
    }

    /** Replays the full ledger and asserts running balances match every row and the account. */
    private void assertLedgerInvariants(String accountId) {
        Account acct = accounts.get(accountId);
        List<LedgerEntry> rows = db.query("SELECT * FROM ledger WHERE account_id=? ORDER BY id", AccountService::mapLedger, accountId);
        long cash = 0, reserved = 0;
        for (LedgerEntry row : rows) {
            if (LedgerEntry.movesCash(row.type())) cash += row.amountCents();
            else reserved += row.amountCents();
            assertThat(row.cashAfterCents()).as("cash_after on ledger #" + row.id()).isEqualTo(cash);
            assertThat(row.reservedAfterCents()).as("reserved_after on ledger #" + row.id()).isEqualTo(reserved);
        }
        assertThat(cash).as("sum of cash rows == account cash").isEqualTo(acct.cashCents());
        assertThat(reserved).as("sum of reserve rows == account reserve").isEqualTo(acct.reservedCents());
    }

    @Test
    void accountBootstrapsWithDepositRow() {
        Account acct = accounts.getOrCreateDefault();
        assertThat(acct.cashCents()).isEqualTo(START);
        assertThat(acct.reservedCents()).isZero();
        assertThat(acct.hasTraded()).isFalse();
        assertThat(accounts.getOrCreateDefault().id()).isEqualTo(acct.id()); // idempotent
        assertLedgerInvariants(acct.id());
    }

    @Test
    void creditSpreadOpensWithExactLedger() {
        Account acct = accounts.getOrCreateDefault();
        TradeRecord t = trades.create(creditPutSpread(acct.id(), 1));

        assertThat(t.status()).isEqualTo("ACTIVE");
        assertThat(t.entryNetPremiumCents()).isEqualTo(18000);   // $1.80 credit at stub mids
        assertThat(t.maxLossCents()).isEqualTo(32000);
        assertThat(t.feesOpenCents()).isEqualTo(130);            // 2 contracts x $0.65

        Account after = accounts.get(acct.id());
        assertThat(after.cashCents()).isEqualTo(START + 18000 - 130);
        assertThat(after.reservedCents()).isEqualTo(50000);      // gross width reserved
        assertThat(after.buyingPowerCents()).isEqualTo(START - 32000 - 130); // net BP impact = maxLoss + fees
        assertThat(after.hasTraded()).isTrue();

        List<LedgerEntry> rows = db.query("SELECT * FROM ledger WHERE trade_id=? ORDER BY id", AccountService::mapLedger, t.id());
        assertThat(rows).extracting(LedgerEntry::type).containsExactly("PREMIUM_OPEN", "FEE", "RESERVE_HOLD");
        assertLedgerInvariants(acct.id());
    }

    @Test
    void debitTradeReservesNothingExtra() {
        Account acct = accounts.getOrCreateDefault();
        TradeRecord t = trades.create(new TradeService.OpenRequest(acct.id(), "AAPL", "LONG_CALL", 1,
                List.of(call(LegAction.BUY, "100", "0")), "bullish", "month", "balanced"));
        assertThat(t.entryNetPremiumCents()).isEqualTo(-25000); // $2.50 debit
        assertThat(t.maxLossCents()).isEqualTo(25000);
        Account after = accounts.get(acct.id());
        assertThat(after.reservedCents()).isZero();             // premium already left cash
        assertThat(after.buyingPowerCents()).isEqualTo(START - 25000 - 65);
        assertLedgerInvariants(acct.id());
    }

    @Test
    void unwindReleasesReserveExactlyAndComputesRealized() {
        Account acct = accounts.getOrCreateDefault();
        TradeRecord t = trades.create(creditPutSpread(acct.id(), 1));

        TradeService.CloseResult result = trades.unwind(t.id(), true);
        assertThat(result.trade().status()).isEqualTo("CLOSED");
        // Round trip at identical marks loses exactly open+close fees
        assertThat(result.realizedPnlCents()).isEqualTo(-260);

        Account after = accounts.get(acct.id());
        assertThat(after.cashCents()).isEqualTo(START - 260);
        assertThat(after.reservedCents()).isZero();
        assertLedgerInvariants(acct.id());
    }

    @Test
    void settleAtIntrinsicAfterExpiry() {
        Account acct = accounts.getOrCreateDefault();
        TradeRecord t = trades.create(creditPutSpread(acct.id(), 1));

        // Not expired yet
        assertThatThrownBy(() -> trades.settle(t.id(), true)).isInstanceOf(TradeRejectedException.class);

        // Move past expiry with spot at $92: short 100P costs $8, long 95P pays $3 -> max loss
        Clock later = Clock.fixed(Instant.parse("2026-08-24T15:30:00Z"), ZoneId.of("America/New_York"));
        marks.underlying = new BigDecimal("92.00");
        TradeService laterTrades = new TradeService(db, cfg, marks, audit, later);
        TradeService.CloseResult result = laterTrades.settle(t.id(), true);

        assertThat(result.trade().status()).isEqualTo("EXPIRED");
        assertThat(result.realizedPnlCents()).isEqualTo(-32130); // -(maxLoss + open fees)
        Account after = accounts.get(acct.id());
        assertThat(after.cashCents()).isEqualTo(START - 32130);
        assertThat(after.reservedCents()).isZero();
        assertLedgerInvariants(acct.id());
    }

    @Test
    void deleteVoidsTradeAndRestoresCashExactly() {
        Account acct = accounts.getOrCreateDefault();
        TradeRecord t = trades.create(creditPutSpread(acct.id(), 1));
        TradeRecord deleted = trades.delete(t.id(), true);
        assertThat(deleted.status()).isEqualTo("DELETED");
        Account after = accounts.get(acct.id());
        assertThat(after.cashCents()).isEqualTo(START);          // full reversal via ADJUSTMENT rows
        assertThat(after.reservedCents()).isZero();
        List<LedgerEntry> adj = db.query("SELECT * FROM ledger WHERE trade_id=? AND type='ADJUSTMENT' ORDER BY id", AccountService::mapLedger, t.id());
        assertThat(adj).hasSize(2);                              // reverses PREMIUM_OPEN and FEE
        assertLedgerInvariants(acct.id());
        // Voided trades cannot be double-deleted or closed
        assertThatThrownBy(() -> trades.delete(t.id(), true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> trades.unwind(t.id(), true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectionMutatesNothingButAudit() {
        Account acct = accounts.getOrCreateDefault();
        // 100 cash-secured puts need ~$970k buying power on a $100k account (within the qty cap)
        TradeService.OpenRequest oversized = new TradeService.OpenRequest(acct.id(), "AAPL", "CASH_SECURED_PUT", 100,
                List.of(put(LegAction.SELL, "100", "0")), "bullish", "month", "balanced");
        assertThatThrownBy(() -> trades.create(oversized))
                .isInstanceOf(TradeRejectedException.class)
                .hasMessageContaining("buying power");

        Account after = accounts.get(acct.id());
        assertThat(after.cashCents()).isEqualTo(START);
        assertThat(after.reservedCents()).isZero();
        assertThat(after.hasTraded()).isFalse();
        assertThat(db.query("SELECT id FROM trades", r -> r.str("id"))).isEmpty();
        assertThat(db.query("SELECT type FROM ledger WHERE type NOT IN ('DEPOSIT')", r -> r.str("type"))).isEmpty();
        List<Map<String, Object>> auditRows = audit.page(0, 10);
        assertThat(auditRows).anySatisfy(row -> {
            assertThat(row.get("action")).isEqualTo("TRADE_REJECTED");
            assertThat(row.get("level")).isEqualTo("BLOCK");
        });
    }

    @Test
    void nakedShortCallIsBlockedByUndefinedRisk() {
        Account acct = accounts.getOrCreateDefault();
        TradeService.OpenRequest naked = new TradeService.OpenRequest(acct.id(), "AAPL", "NAKED_CALL", 1,
                List.of(call(LegAction.SELL, "105", "0")), "neutral", "month", "aggressive");
        TradePreview preview = trades.preview(naked);
        assertThat(preview.ok()).isFalse();
        assertThat(preview.blockReasons()).anySatisfy(r -> assertThat(r).containsIgnoringCase("risk"));
        assertThatThrownBy(() -> trades.create(naked)).isInstanceOf(TradeRejectedException.class);
        assertThat(accounts.get(acct.id()).cashCents()).isEqualTo(START);
    }

    @Test
    void staleMarksBlockCreation() {
        Account acct = accounts.getOrCreateDefault();
        marks.freshness = Freshness.STALE;
        assertThatThrownBy(() -> trades.create(creditPutSpread(acct.id(), 1)))
                .isInstanceOf(TradeRejectedException.class)
                .hasMessageContaining("STALE");
    }

    @Test
    void previewNeverMutates() {
        Account acct = accounts.getOrCreateDefault();
        TradePreview p = trades.preview(creditPutSpread(acct.id(), 1));
        assertThat(p.ok()).isTrue();
        assertThat(p.entryNetPremiumCents()).isEqualTo(18000);
        assertThat(p.buyingPowerAfterCents()).isEqualTo(START - 32130);
        assertThat(p.breakevens()).containsExactly("98.2000");
        assertThat(p.popEntry()).isBetween(0.0, 1.0);
        assertThat(accounts.get(acct.id()).cashCents()).isEqualTo(START);
        assertThat(db.query("SELECT id FROM trades", r -> r.str("id"))).isEmpty();
    }

    @Test
    void refreshWritesMarksOnlyNeverCash() {
        Account acct = accounts.getOrCreateDefault();
        TradeRecord t = trades.create(creditPutSpread(acct.id(), 1));
        long cashBefore = accounts.get(acct.id()).cashCents();

        TradeService.MarkView view = trades.refresh(t.id());
        assertThat(view.unrealizedCents()).isZero();             // same marks as entry
        assertThat(view.closeCostCents()).isEqualTo(-18000);
        assertThat(view.popNow()).isBetween(0.0, 1.0);

        assertThat(accounts.get(acct.id()).cashCents()).isEqualTo(cashBefore);
        assertThat(trades.marksHistory(t.id(), 10)).hasSize(1);
        assertLedgerInvariants(acct.id());
    }

    @Test
    void listPaginatesAndFilters() {
        Account acct = accounts.getOrCreateDefault();
        TradeRecord t1 = trades.create(creditPutSpread(acct.id(), 1));
        trades.create(creditPutSpread(acct.id(), 2));
        trades.unwind(t1.id(), true);

        assertThat(trades.list(acct.id(), "ACTIVE", 0, 10).trades()).hasSize(1);
        assertThat(trades.list(acct.id(), "CLOSED", 0, 10).trades()).hasSize(1);
        TradeService.Page all = trades.list(acct.id(), null, 0, 1);
        assertThat(all.total()).isEqualTo(2);
        assertThat(all.trades()).hasSize(1);
    }

    @Test
    void netShortMultiExpirationDebitIsBlocked() {
        Account acct = accounts.getOrCreateDefault();
        LocalDate far = EXP.plusDays(28);
        // Net debit, but 2 short near calls covered by only 1 long far call -> undefined risk
        TradeService.OpenRequest reverse = new TradeService.OpenRequest(acct.id(), "AAPL", "CUSTOM", 1,
                List.of(Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"), far, 1, BigDecimal.ZERO),
                        Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("105"), EXP, 2, BigDecimal.ZERO)),
                "neutral", "month", "aggressive");
        assertThatThrownBy(() -> trades.create(reverse))
                .isInstanceOf(TradeRejectedException.class)
                .hasMessageContaining("not fully covered");
        assertThat(accounts.get(acct.id()).cashCents()).isEqualTo(START);

        // Short leg outliving its cover is equally blocked, debit or not
        TradeService.OpenRequest reverseCalendar = new TradeService.OpenRequest(acct.id(), "AAPL", "CUSTOM", 1,
                List.of(Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"), EXP, 1, BigDecimal.ZERO),
                        Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("105"), far, 1, BigDecimal.ZERO)),
                "neutral", "month", "aggressive");
        assertThatThrownBy(() -> trades.create(reverseCalendar)).isInstanceOf(TradeRejectedException.class);
    }

    @Test
    void legitimateDiagonalIsAllowedAndPopStaysNull() {
        Account acct = accounts.getOrCreateDefault();
        LocalDate far = EXP.plusDays(28);
        TradeRecord t = trades.create(new TradeService.OpenRequest(acct.id(), "AAPL", "DIAGONAL_CALL", 1,
                List.of(Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"), far, 1, BigDecimal.ZERO),
                        Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("105"), EXP, 1, BigDecimal.ZERO)),
                "bullish", "month", "aggressive"));
        assertThat(t.status()).isEqualTo("ACTIVE");
        assertThat(t.entryNetPremiumCents()).isEqualTo(-14000); // 2.50 debit vs 1.10 credit
        assertThat(t.maxLossCents()).isEqualTo(14000);          // capped at the debit
        assertThat(t.maxProfitCents()).isNull();                // model-dependent
        assertThat(t.popEntry()).isNull();
        assertThat(accounts.get(acct.id()).reservedCents()).isZero();

        TradeService.MarkView view = trades.refresh(t.id());
        assertThat(view.popNow()).isNull();                     // no fake POP for calendars/diagonals
        assertLedgerInvariants(acct.id());
    }

    @Test
    void settleUsesExpirationDayCloseNotTodaysSpot() {
        Account acct = accounts.getOrCreateDefault();
        TradeRecord t = trades.create(creditPutSpread(acct.id(), 1));

        Clock later = Clock.fixed(Instant.parse("2026-09-07T15:30:00Z"), ZoneId.of("America/New_York"));
        marks.underlying = new BigDecimal("92.00");      // spot weeks after expiry: would be max loss
        marks.closeOnValue = new BigDecimal("101.00");   // but at expiry both puts finished worthless
        TradeService laterTrades = new TradeService(db, cfg, marks, audit, later);
        TradeService.CloseResult result = laterTrades.settle(t.id(), true);

        assertThat(result.realizedPnlCents()).isEqualTo(17870); // full credit minus open fees
        assertLedgerInvariants(acct.id());
    }

    @Test
    void expiredContractsCannotBeOpened() {
        Account acct = accounts.getOrCreateDefault();
        LocalDate past = LocalDate.of(2026, 6, 19); // before the fixed clock's today
        TradeService.OpenRequest expired = new TradeService.OpenRequest(acct.id(), "AAPL", "LONG_CALL", 1,
                List.of(Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"), past, 1, BigDecimal.ZERO)),
                "bullish", "week", "balanced");
        assertThat(trades.preview(expired).ok()).isFalse();
        assertThatThrownBy(() -> trades.create(expired))
                .isInstanceOf(TradeRejectedException.class)
                .hasMessageContaining("expired");
        assertThat(accounts.get(acct.id()).cashCents()).isEqualTo(START);
    }

    @Test
    void tslaRisklessCollarReplay_blockedAsDeadContract() {
        // Exact replay of the real incident: collar placed 2026-07-07T01:24Z (9:24pm ET,
        // 5.4h after the close) with legs expiring 2026-07-06 — quotes were corpses.
        Account acct = accounts.getOrCreateDefault();
        Clock afterClose = Clock.fixed(Instant.parse("2026-07-07T01:24:46Z"), ZoneId.of("America/New_York"));
        StubMarks tsla = new StubMarks();
        tsla.underlying = new BigDecimal("416.97");
        tsla.exact.put("PUT417.5", new MarksSource.LegMark(new BigDecimal("0.00"), new BigDecimal("0.01"), new BigDecimal("0.005"), 0.3036, Freshness.DELAYED));
        tsla.exact.put("CALL420", new MarksSource.LegMark(new BigDecimal("0.05"), new BigDecimal("0.08"), new BigDecimal("0.065"), 0.1248, Freshness.DELAYED));
        TradeService tslaTrades = new TradeService(db, cfg, tsla, audit, afterClose);
        LocalDate deadExpiry = LocalDate.of(2026, 7, 6);

        TradeService.OpenRequest collar = new TradeService.OpenRequest(acct.id(), "TSLA", "PROTECTIVE_COLLAR", 1,
                List.of(Leg.stock(LegAction.BUY, 1, BigDecimal.ZERO),
                        Leg.option(LegAction.BUY, OptionType.PUT, new BigDecimal("417.5"), deadExpiry, 1, BigDecimal.ZERO),
                        Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("420"), deadExpiry, 1, BigDecimal.ZERO)),
                "neutral", "0DTE", "balanced");

        assertThat(tslaTrades.preview(collar).ok()).isFalse();
        assertThatThrownBy(() -> tslaTrades.create(collar))
                .isInstanceOf(TradeRejectedException.class)
                .hasMessageContaining("expired")
                .hasMessageContaining("4:00pm ET");
        assertThat(accounts.get(acct.id()).cashCents()).isEqualTo(START);
    }

    @Test
    void belowIntrinsicQuoteBlockedEvenOnLivingContract() {
        // Same corpse quote but on a not-yet-expired contract, during market hours:
        // the intrinsic-floor guardrail must catch it.
        Account acct = accounts.getOrCreateDefault();
        StubMarks tsla = new StubMarks();
        tsla.underlying = new BigDecimal("416.97");
        tsla.exact.put("PUT417.5", new MarksSource.LegMark(new BigDecimal("0.00"), new BigDecimal("0.01"), new BigDecimal("0.005"), 0.30, Freshness.DELAYED));
        TradeService tslaTrades = new TradeService(db, cfg, tsla, audit, CLOCK); // Wed 11:30 ET, open
        TradeService.OpenRequest req = new TradeService.OpenRequest(acct.id(), "TSLA", "LONG_PUT", 1,
                List.of(Leg.option(LegAction.BUY, OptionType.PUT, new BigDecimal("417.5"), LocalDate.of(2026, 7, 10), 1, BigDecimal.ZERO)),
                "bearish", "week", "balanced");
        assertThatThrownBy(() -> tslaTrades.create(req))
                .isInstanceOf(TradeRejectedException.class)
                .hasMessageContaining("Quote integrity")
                .hasMessageContaining("intrinsic");
    }

    @Test
    void computedRiskFreeStructureBlocked() {
        // Credit exceeding the spread width => computed max loss $0 => broken quotes, refuse.
        Account acct = accounts.getOrCreateDefault();
        marks.mids.put("PUT100", new BigDecimal("6.00")); // absurd: credit 5.50 on a $5 wide spread
        marks.mids.put("PUT95", new BigDecimal("0.50"));
        assertThatThrownBy(() -> trades.create(creditPutSpread(acct.id(), 1)))
                .isInstanceOf(TradeRejectedException.class)
                .hasMessageContaining("risk-free");
        assertThat(accounts.get(acct.id()).cashCents()).isEqualTo(START);
    }

    @Test
    void fillsUseExecutableSidesNotMids() {
        // Real spreads: entry credit = short bid - long ask; unwind pays ask / receives bid.
        Account acct = accounts.getOrCreateDefault();
        marks.exact.put("PUT100", new MarksSource.LegMark(new BigDecimal("2.90"), new BigDecimal("3.10"), new BigDecimal("3.00"), 0.25, Freshness.FIXTURE));
        marks.exact.put("PUT95", new MarksSource.LegMark(new BigDecimal("1.10"), new BigDecimal("1.30"), new BigDecimal("1.20"), 0.25, Freshness.FIXTURE));

        TradeRecord t = trades.create(creditPutSpread(acct.id(), 1));
        assertThat(t.entryNetPremiumCents()).isEqualTo(16000);   // 2.90 bid - 1.30 ask, NOT the 1.80 mid credit
        assertThat(t.maxLossCents()).isEqualTo(34000);           // width 5.00 - 1.60 executable credit

        TradeService.CloseResult closed = trades.unwind(t.id(), true);
        // close: buy back short @ask 3.10 (-310), sell long @bid 1.10 (+110) => -200.00
        assertThat(closed.realizedPnlCents()).isEqualTo(16000 - 130 - 20000 - 130);
        assertLedgerInvariants(acct.id());
    }

    @Test
    void oneSidedBookIsNotExecutable() {
        Account acct = accounts.getOrCreateDefault();
        marks.exact.put("PUT95", new MarksSource.LegMark(new BigDecimal("1.10"), null, new BigDecimal("1.20"), 0.25, Freshness.FIXTURE));
        TradePreview p = trades.preview(creditPutSpread(acct.id(), 1)); // BUY leg needs an ask
        assertThat(p.ok()).isFalse();
        assertThat(p.blockReasons()).anySatisfy(r -> assertThat(r).containsIgnoringCase("no executable ask"));
    }

    @Test
    void closedMarketWarnsButFutureContractsStillTradable() {
        Account acct = accounts.getOrCreateDefault();
        Clock saturday = Clock.fixed(Instant.parse("2026-07-11T15:00:00Z"), ZoneId.of("America/New_York"));
        TradeService weekend = new TradeService(db, cfg, marks, audit, saturday);
        TradePreview p = weekend.preview(creditPutSpread(acct.id(), 1)); // legs expire 2026-08-21
        assertThat(p.ok()).isTrue();
        assertThat(p.warnings()).anySatisfy(w -> assertThat(w).containsIgnoringCase("market is closed"));
    }

    @Test
    void crossedBookIsNotExecutable() {
        Account acct = accounts.getOrCreateDefault();
        // Stale crossed book: bid 1.40 > ask 1.00 — buying the ask and selling the bid would mint money
        marks.exact.put("CALL100", new MarksSource.LegMark(new BigDecimal("1.40"), new BigDecimal("1.00"), new BigDecimal("1.20"), 0.25, Freshness.FIXTURE));
        TradePreview p = trades.preview(new TradeService.OpenRequest(acct.id(), "AAPL", "LONG_CALL", 1,
                List.of(call(LegAction.BUY, "100", "0")), "bullish", "month", "balanced"));
        assertThat(p.ok()).isFalse();
        assertThat(p.blockReasons()).anySatisfy(r -> assertThat(r).containsIgnoringCase("crossed"));
    }

    @Test
    void settleRefusedWhileExpiryDayContractsStillAlive() {
        // Open a spread expiring TODAY (fixed clock = Wednesday 11:30 ET, market open) and
        // try to cash-settle intraday — the contract is alive until 16:00 ET.
        Account acct = accounts.getOrCreateDefault();
        LocalDate today = LocalDate.of(2026, 7, 8);
        TradeService.OpenRequest zeroDte = new TradeService.OpenRequest(acct.id(), "AAPL", "CREDIT_PUT_SPREAD", 1,
                List.of(Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("100"), today, 1, BigDecimal.ZERO),
                        Leg.option(LegAction.BUY, OptionType.PUT, new BigDecimal("95"), today, 1, BigDecimal.ZERO)),
                "bullish", "0DTE", "aggressive");
        TradeRecord t = trades.create(zeroDte);
        assertThatThrownBy(() -> trades.settle(t.id(), true))
                .isInstanceOf(TradeRejectedException.class)
                .hasMessageContaining("still alive");

        // After the 4pm ET bell with the expiry-day close available, settlement works
        Clock evening = Clock.fixed(Instant.parse("2026-07-08T21:00:00Z"), ZoneId.of("America/New_York"));
        marks.closesByDate.put(today, new BigDecimal("101.00")); // both puts OTM at the close
        TradeService eveningTrades = new TradeService(db, cfg, marks, audit, evening);
        TradeService.CloseResult result = eveningTrades.settle(t.id(), true);
        assertThat(result.realizedPnlCents()).isEqualTo(18000 - 130); // full credit minus open fees
        assertLedgerInvariants(acct.id());
    }

    @Test
    void settleWithoutExpiryCloseRejectedUntilNextDayThenLabeledFallback() {
        Account acct = accounts.getOrCreateDefault();
        LocalDate today = LocalDate.of(2026, 7, 8);
        TradeRecord t = trades.create(new TradeService.OpenRequest(acct.id(), "AAPL", "CREDIT_PUT_SPREAD", 1,
                List.of(Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("100"), today, 1, BigDecimal.ZERO),
                        Leg.option(LegAction.BUY, OptionType.PUT, new BigDecimal("95"), today, 1, BigDecimal.ZERO)),
                "bullish", "0DTE", "aggressive"));

        // Same evening, no close published: refuse (no settling against yesterday's price or intraday gaps)
        Clock evening = Clock.fixed(Instant.parse("2026-07-08T21:00:00Z"), ZoneId.of("America/New_York"));
        TradeService eveningTrades = new TradeService(db, cfg, marks, audit, evening);
        assertThatThrownBy(() -> eveningTrades.settle(t.id(), true))
                .isInstanceOf(TradeRejectedException.class)
                .hasMessageContaining("not available yet");

        // A day later, still no close source: labeled fallback to the current quote
        Clock nextDay = Clock.fixed(Instant.parse("2026-07-09T15:00:00Z"), ZoneId.of("America/New_York"));
        marks.underlying = new BigDecimal("102.00");
        TradeService nextDayTrades = new TradeService(db, cfg, marks, audit, nextDay);
        TradeService.CloseResult result = nextDayTrades.settle(t.id(), true);
        assertThat(result.trade().closeReason()).contains("CURRENT market price");
        assertThat(result.realizedPnlCents()).isEqualTo(18000 - 130);
        assertLedgerInvariants(acct.id());
    }

    @Test
    void mixedExpirationSettleValuesEachLegAtItsOwnExpiryClose() {
        Account acct = accounts.getOrCreateDefault();
        LocalDate near = EXP;                    // 2026-08-21
        LocalDate far = EXP.plusDays(28);        // 2026-09-18
        TradeRecord t = trades.create(new TradeService.OpenRequest(acct.id(), "AAPL", "DIAGONAL_CALL", 1,
                List.of(Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"), far, 1, BigDecimal.ZERO),
                        Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("105"), near, 1, BigDecimal.ZERO)),
                "bullish", "month", "aggressive"));
        assertThat(t.entryNetPremiumCents()).isEqualTo(-14000);

        // Underlying spiked to 120 at the NEAR expiry (short 105C cost $15) then faded to 98 by the FAR expiry
        marks.closesByDate.put(near, new BigDecimal("120.00"));
        marks.closesByDate.put(far, new BigDecimal("98.00"));
        Clock afterAll = Clock.fixed(Instant.parse("2026-09-21T15:00:00Z"), ZoneId.of("America/New_York"));
        TradeService laterTrades = new TradeService(db, cfg, marks, audit, afterAll);
        TradeService.CloseResult result = laterTrades.settle(t.id(), true);

        // short 105C settles at -1500 (its OWN expiry close 120), long 100C worthless at 98
        assertThat(result.realizedPnlCents()).isEqualTo(-14000 - 130 - 150000);
        assertLedgerInvariants(acct.id());
    }

    @Test
    void positionGreeksAggregateAcrossLegs() {
        Account acct = accounts.getOrCreateDefault();
        // Short put delta -0.30 -> short leg contributes +0.30*100; long put delta -0.10 -> -0.10*100
        marks.exact.put("PUT100", new MarksSource.LegMark(new BigDecimal("3.00"), new BigDecimal("3.00"),
                new BigDecimal("3.00"), 0.25, Freshness.FIXTURE, -0.30, 0.02, -0.05, 0.10));
        marks.exact.put("PUT95", new MarksSource.LegMark(new BigDecimal("1.20"), new BigDecimal("1.20"),
                new BigDecimal("1.20"), 0.25, Freshness.FIXTURE, -0.10, 0.01, -0.02, 0.05));
        TradeRecord t = trades.create(creditPutSpread(acct.id(), 2)); // qty 2

        TradeService.MarkView view = trades.currentMark(t.id());
        assertThat(view.greeks()).isNotNull();
        assertThat(view.greeks().complete()).isTrue();
        // delta: SELL(-1)*(-0.30)*100*2 + BUY(+1)*(-0.10)*100*2 = 60 - 20 = 40 share-equivalents
        assertThat(view.greeks().deltaShares()).isEqualTo(40.0);
        // theta: SELL(-1)*(-0.05)*200 + BUY(+1)*(-0.02)*200 = 10 - 4 = +6 $/day (short premium earns decay)
        assertThat(view.greeks().thetaPerDay()).isEqualTo(6.0);
        assertThat(view.greeks().vegaPerPoint()).isEqualTo(-10.0); // -0.10*200 + 0.05*200
        assertThat(view.legGreeks()).hasSize(2);
        assertThat(view.legGreeks().getFirst()).containsKeys("leg", "delta", "bid", "ask");

        Map<String, Object> pg = trades.portfolioGreeks(acct.id());
        assertThat((Double) pg.get("deltaShares")).isEqualTo(40.0);
        assertThat((Boolean) pg.get("complete")).isTrue();
        assertThat(pg.get("positions")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
    }

    @Test
    void resetBlockedAfterTradingUnlessForced() {
        Account acct = accounts.getOrCreateDefault();
        trades.create(creditPutSpread(acct.id(), 1));

        assertThatThrownBy(() -> accounts.reset(5_000_000L, true, false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accounts.reset(5_000_000L, false, true)).isInstanceOf(IllegalArgumentException.class);

        Account reset = accounts.reset(5_000_000L, true, true);
        assertThat(reset.cashCents()).isEqualTo(5_000_000L);
        assertThat(reset.reservedCents()).isZero();
        assertThat(reset.hasTraded()).isFalse();
        assertThat(trades.list(acct.id(), "ACTIVE", 0, 10).trades()).isEmpty();
        assertLedgerInvariants(acct.id());
    }
    // ---- Equity holdings, share-covered calls, physical assignment ----

    @Test
    void buyAndSellSharesKeepLedgerExactAndWeightedBasis() {
        Account acct = accounts.getOrCreateDefault();
        var buy = positions.buy(acct.id(), "AAPL", 100);
        assertThat(buy.pricePerShareCents()).isEqualTo(10_000);
        assertThat(buy.totalCents()).isEqualTo(1_000_000);
        assertThat(buy.position().shares()).isEqualTo(100);
        assertThat(buy.position().avgCostCents()).isEqualTo(10_000);
        assertThat(accounts.get(acct.id()).cashCents()).isEqualTo(START - 1_000_000);

        marks.underlying = new BigDecimal("108.00");
        var sell = positions.sell(acct.id(), "AAPL", 40);
        assertThat(sell.totalCents()).isEqualTo(432_000);
        assertThat(sell.realizedPnlCents()).isEqualTo(32_000); // (108 - 100) x 40 sh
        assertThat(sell.position().shares()).isEqualTo(60);
        assertThat(sell.position().avgCostCents()).isEqualTo(10_000); // basis unchanged by sells

        var buyMore = positions.buy(acct.id(), "AAPL", 60);
        assertThat(buyMore.position().shares()).isEqualTo(120);
        assertThat(buyMore.position().avgCostCents()).isEqualTo(10_400); // (60x100 + 60x108) / 120

        assertThat(accounts.get(acct.id()).cashCents())
                .isEqualTo(START - 1_000_000 + 432_000 - 648_000);
        assertLedgerInvariants(acct.id());
    }

    @Test
    void sharesCannotBeSoldOrBoughtBeyondLimits() {
        Account acct = accounts.getOrCreateDefault();
        assertThatThrownBy(() -> positions.sell(acct.id(), "AAPL", 10))
                .isInstanceOf(TradeRejectedException.class).hasMessageContaining("do not hold");
        assertThatThrownBy(() -> positions.buy(acct.id(), "AAPL", 0))
                .isInstanceOf(IllegalArgumentException.class);
        // Buying more than cash allows is blocked and mutates nothing
        assertThatThrownBy(() -> positions.buy(acct.id(), "AAPL", 2_000))
                .isInstanceOf(TradeRejectedException.class).hasMessageContaining("buying power");
        assertThat(accounts.get(acct.id()).cashCents()).isEqualTo(START);
        assertLedgerInvariants(acct.id());
    }

    @Test
    void coveredCallOnHeldSharesLocksSharesAndReservesNoCash() {
        Account acct = accounts.getOrCreateDefault();
        positions.buy(acct.id(), "AAPL", 100);

        TradePreview preview = trades.preview(coveredCallOnHeldShares(acct.id()));
        assertThat(preview.ok()).as(String.join("; ", preview.blockReasons())).isTrue();
        assertThat(preview.reserveCents()).isZero();          // no NEW cash at risk
        assertThat(preview.maxLossCents()).isZero();          // incremental risk of the order itself
        assertThat(preview.popEntry()).isNotNull();           // combined-with-shares model
        assertThat(preview.breakevens()).isNotEmpty();        // ~ share cost minus the premium

        TradeRecord t = trades.create(coveredCallOnHeldShares(acct.id()));
        assertThat(t.sharesLocked()).isEqualTo(100);
        assertThat(t.intent()).isEqualTo("EXIT");
        long credit = 11_000; // CALL105 mid 1.10 x 100 shares, zero-spread stub
        long fees = 65;
        assertThat(accounts.get(acct.id()).cashCents()).isEqualTo(START - 1_000_000 + credit - fees);
        assertThat(accounts.get(acct.id()).reservedCents()).isZero();

        // The locked lot cannot be sold out from under the call, nor double-pledged
        assertThatThrownBy(() -> positions.sell(acct.id(), "AAPL", 100))
                .isInstanceOf(TradeRejectedException.class).hasMessageContaining("locked");
        assertThatThrownBy(() -> trades.create(coveredCallOnHeldShares(acct.id())))
                .isInstanceOf(TradeRejectedException.class).hasMessageContaining("free shares");
        assertThat(positions.freeShares(acct.id(), "AAPL")).isZero();

        // Unwinding the call releases the lock
        trades.unwind(t.id(), true);
        assertThat(positions.freeShares(acct.id(), "AAPL")).isEqualTo(100);
        assertLedgerInvariants(acct.id());
    }

    @Test
    void nakedCallWithoutHeldSharesStaysBlocked() {
        Account acct = accounts.getOrCreateDefault();
        // No shares held: the identical request must be rejected, not silently covered
        assertThatThrownBy(() -> trades.create(coveredCallOnHeldShares(acct.id())))
                .isInstanceOf(TradeRejectedException.class).hasMessageContaining("free shares");
        // And without useHeldShares the same single short call is undefined risk
        assertThatThrownBy(() -> trades.create(new TradeService.OpenRequest(acct.id(), "AAPL", "NAKED_CALL", 1,
                List.of(call(LegAction.SELL, "105", "0")), "neutral", "month", "balanced")))
                .isInstanceOf(TradeRejectedException.class).hasMessageContaining("Undefined");
        assertLedgerInvariants(acct.id());
    }

    @Test
    void coveredCallAssignmentDeliversHeldSharesAtTheStrike() {
        Account acct = accounts.getOrCreateDefault();
        positions.buy(acct.id(), "AAPL", 100);
        TradeRecord t = trades.create(coveredCallOnHeldShares(acct.id()));

        marks.closesByDate.put(EXP, new BigDecimal("110.00")); // finishes ITM: 110 > 105
        Clock after = Clock.fixed(Instant.parse("2026-08-22T16:00:00Z"), ZoneId.of("America/New_York"));
        TradeService settleSvc = new TradeService(db, cfg, marks, audit, after);
        TradeService.CloseResult res = settleSvc.settle(t.id(), true);

        // Option side: keep the premium; stock side: shares sold at the strike, not at the close
        assertThat(res.realizedPnlCents()).isEqualTo(11_000 - 65);
        assertThat(res.trade().closeReason()).contains("called away");
        assertThat(db.query("SELECT COUNT(*) AS n FROM positions", r -> r.lng("n")).getFirst()).isZero();
        long stockSale = 1_050_000; // 100 sh x $105 strike
        assertThat(accounts.get(acct.id()).cashCents())
                .isEqualTo(START - 1_000_000 + 11_000 - 65 + stockSale);
        assertThat(accounts.get(acct.id()).reservedCents()).isZero();
        List<LedgerEntry> stockRows = db.query(
                "SELECT * FROM ledger WHERE trade_id=? AND type='STOCK_SELL'", AccountService::mapLedger, t.id());
        assertThat(stockRows).hasSize(1);
        assertThat(stockRows.getFirst().memo()).contains("called away").contains("105");
        assertLedgerInvariants(acct.id());
    }

    @Test
    void coveredCallExpiringWorthlessReleasesTheLockAndKeepsShares() {
        Account acct = accounts.getOrCreateDefault();
        positions.buy(acct.id(), "AAPL", 100);
        TradeRecord t = trades.create(coveredCallOnHeldShares(acct.id()));

        marks.closesByDate.put(EXP, new BigDecimal("99.00")); // OTM
        Clock after = Clock.fixed(Instant.parse("2026-08-22T16:00:00Z"), ZoneId.of("America/New_York"));
        TradeService settleSvc = new TradeService(db, cfg, marks, audit, after);
        TradeService.CloseResult res = settleSvc.settle(t.id(), true);

        assertThat(res.realizedPnlCents()).isEqualTo(11_000 - 65); // premium kept, shares kept
        assertThat(positions.freeShares(acct.id(), "AAPL")).isEqualTo(100);
        assertThat(accounts.get(acct.id()).cashCents()).isEqualTo(START - 1_000_000 + 11_000 - 65);
        assertLedgerInvariants(acct.id());
    }

    @Test
    void cashSecuredPutAssignmentBuysSharesAtTheStrike() {
        Account acct = accounts.getOrCreateDefault();
        TradeRecord t = trades.create(new TradeService.OpenRequest(acct.id(), "AAPL", "CASH_SECURED_PUT", 1,
                List.of(put(LegAction.SELL, "100", "0")), "neutral", "month", "balanced", "ACQUIRE", null));
        long credit = 30_000; // PUT100 mid 3.00
        long fees = 65;
        assertThat(accounts.get(acct.id()).reservedCents()).isEqualTo(1_000_000); // full strike cash secured

        marks.closesByDate.put(EXP, new BigDecimal("90.00")); // ITM: assigned
        marks.underlying = new BigDecimal("90.00");
        Clock after = Clock.fixed(Instant.parse("2026-08-22T16:00:00Z"), ZoneId.of("America/New_York"));
        TradeService settleSvc = new TradeService(db, cfg, marks, audit, after);
        TradeService.CloseResult res = settleSvc.settle(t.id(), true);

        // Shares put to you at the strike; premium stays option income; reserve fully released
        assertThat(res.realizedPnlCents()).isEqualTo(credit - fees);
        assertThat(res.trade().closeReason()).contains("bought 100 sh");
        var view = positions.get(acct.id(), "AAPL");
        assertThat(view.shares()).isEqualTo(100);
        assertThat(view.avgCostCents()).isEqualTo(10_000); // basis = strike
        assertThat(view.unrealizedCents()).isEqualTo(-100_000); // marked at today's $90
        assertThat(accounts.get(acct.id()).cashCents()).isEqualTo(START + credit - fees - 1_000_000);
        assertThat(accounts.get(acct.id()).reservedCents()).isZero();
        assertLedgerInvariants(acct.id());
    }

    @Test
    void accountResetClearsEquityPositions() {
        Account acct = accounts.getOrCreateDefault();
        positions.buy(acct.id(), "AAPL", 100);
        accounts.reset(START, true, true);
        assertThat(positions.list(acct.id())).isEmpty();
        assertThat(accounts.get(acct.id()).cashCents()).isEqualTo(START);
        assertLedgerInvariants(acct.id());
    }
    // ---- Adversarial-review regressions: physical assignment must be structural and funded ----

    @Test
    void relabeledPutSpreadNeverAssignsPhysically() {
        // A defined-risk put spread labeled CASH_SECURED_PUT must cash-settle: the label is
        // user-supplied, and physical delivery would spend strike cash the reserve never held.
        Account acct = accounts.getOrCreateDefault();
        TradeRecord t = trades.create(new TradeService.OpenRequest(acct.id(), "AAPL", "CASH_SECURED_PUT", 1,
                List.of(put(LegAction.SELL, "100", "0"), put(LegAction.BUY, "95", "0")),
                "bullish", "month", "balanced", "INCOME", null));
        long reserveHeld = accounts.get(acct.id()).reservedCents();
        assertThat(reserveHeld).isEqualTo(50_000); // spread width, NOT the full strike

        marks.closesByDate.put(EXP, new BigDecimal("80.00")); // both puts deep ITM
        Clock after = Clock.fixed(Instant.parse("2026-08-22T16:00:00Z"), ZoneId.of("America/New_York"));
        TradeService settleSvc = new TradeService(db, cfg, marks, audit, after);
        TradeService.CloseResult res = settleSvc.settle(t.id(), true);

        // Cash settlement at intrinsic: short 100P pays -(100-80)x100, long 95P returns +(95-80)x100
        long expectedSettle = -200_000 + 150_000;
        assertThat(res.trade().closeReason()).doesNotContain("bought");
        assertThat(db.query("SELECT COUNT(*) AS n FROM ledger WHERE type='STOCK_BUY'", r -> r.lng("n")).getFirst()).isZero();
        assertThat(db.query("SELECT COUNT(*) AS n FROM positions", r -> r.lng("n")).getFirst()).isZero();
        assertThat(res.realizedPnlCents()).isEqualTo(t.entryNetPremiumCents() - t.feesOpenCents() + expectedSettle);
        assertThat(accounts.get(acct.id()).cashCents()).isGreaterThan(0);
        assertThat(accounts.get(acct.id()).reservedCents()).isZero();
        assertLedgerInvariants(acct.id());
    }

    @Test
    void callAssignmentIsCappedAtTheLockedShares() {
        // SELL 2x 105C + BUY 1x 100C on 100 held shares: only ONE short unit is share-covered
        // (the long 100C covers the other) — settle must deliver exactly the locked 100 shares
        // and cash-settle the remaining unit, never touching shares beyond the lock.
        Account acct = accounts.getOrCreateDefault();
        positions.buy(acct.id(), "AAPL", 100);
        TradeRecord t = trades.create(new TradeService.OpenRequest(acct.id(), "AAPL", "CUSTOM", 1,
                List.of(Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("105"), EXP, 2, BigDecimal.ZERO),
                        call(LegAction.BUY, "100", "0")),
                "neutral", "month", "balanced", null, true));
        assertThat(t.sharesLocked()).isEqualTo(100);

        marks.closesByDate.put(EXP, new BigDecimal("110.00")); // everything ITM
        Clock after = Clock.fixed(Instant.parse("2026-08-22T16:00:00Z"), ZoneId.of("America/New_York"));
        TradeService settleSvc = new TradeService(db, cfg, marks, audit, after);
        TradeService.CloseResult res = settleSvc.settle(t.id(), true);

        // Physical: 100 sh called away at 105 (+$10,500). Cash: the SECOND short 105C unit
        // settles -(110-105)x100 = -$500; the long 100C settles +(110-100)x100 = +$1,000.
        assertThat(res.trade().closeReason()).contains("100 sh called away");
        List<LedgerEntry> stockSells = db.query("SELECT * FROM ledger WHERE type='STOCK_SELL'", AccountService::mapLedger);
        assertThat(stockSells).hasSize(1);
        assertThat(stockSells.getFirst().amountCents()).isEqualTo(1_050_000);
        List<LedgerEntry> settleRows = db.query("SELECT * FROM ledger WHERE type='SETTLEMENT'", AccountService::mapLedger);
        assertThat(settleRows.getFirst().amountCents()).isEqualTo(-50_000 + 100_000);
        assertThat(db.query("SELECT COUNT(*) AS n FROM positions", r -> r.lng("n")).getFirst()).isZero();
        assertLedgerInvariants(acct.id());
    }

    @Test
    void protectivePutOnHeldSharesPreviewsTheCombinedPosition() {
        // The Ideas candidate for a hedge shows the SHARES+PUT curve; the preview must match —
        // a bare long put's "profit when the stock falls" framing is the opposite of a hedge.
        Account acct = accounts.getOrCreateDefault();
        positions.buy(acct.id(), "AAPL", 100);
        TradeService.OpenRequest pp = new TradeService.OpenRequest(acct.id(), "AAPL", "PROTECTIVE_PUT", 1,
                List.of(put(LegAction.BUY, "95", "0")), "bullish", "month", "balanced", "HEDGE", true);
        TradePreview preview = trades.preview(pp);
        assertThat(preview.ok()).as(String.join("; ", preview.blockReasons())).isTrue();
        assertThat(preview.maxLossCents()).isEqualTo(12_000 + 0); // the hedge costs its debit (PUT95 mid 1.20)
        // Combined breakeven sits ABOVE spot (stock cost + premium), not below (bare put)
        assertThat(new BigDecimal(preview.breakevens().getFirst())).isGreaterThan(new BigDecimal("100"));
        TradeRecord t = trades.create(pp);
        assertThat(t.sharesLocked()).isZero(); // long options never lock shares

        // Without the shares, the same request is refused — the combined framing needs them
        TradeService.OpenRequest noShares = new TradeService.OpenRequest(acct.id(), "SPY", "PROTECTIVE_PUT", 1,
                List.of(put(LegAction.BUY, "95", "0")), "bullish", "month", "balanced", "HEDGE", true);
        assertThatThrownBy(() -> trades.create(noShares))
                .isInstanceOf(TradeRejectedException.class).hasMessageContaining("free shares");
        assertLedgerInvariants(acct.id());
    }

}
