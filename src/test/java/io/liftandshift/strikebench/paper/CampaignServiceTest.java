package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.position.CampaignMath;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Campaign layer contract (TRADER_OWN §3.12 + §4): typed membership over the authoritative book,
 * auto-link proposals that are NEVER members until confirmed, and the §4 outputs assembled through
 * CampaignMath — including realized-vs-headline on identical denominators.
 */
class CampaignServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-13T16:00:00Z"), ZoneOffset.UTC);
    private Db db;
    private PortfolioAccountingService books;
    private CampaignService campaigns;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        books = new PortfolioAccountingService(db, CLOCK);
        campaigns = new CampaignService(db, CLOCK);
    }

    @AfterEach void close() { if (db != null) db.close(); }

    @Test
    void campaignAssemblyOverARollChainReportsTheSection4NumbersOnIdenticalDenominators() {
        var account = books.createAccount("local", account("Taxable brokerage", "TAXABLE", 20_000_000L));
        // The MU accumulation story: short put -> roll -> assignment -> covered call ->
        // dividend -> expiration -> exit/re-entry churn -> tagged interest.
        var tx1 = books.record("local", account.id(), tx("2026-01-05", "TRADE", null, 0L, null, "MANUAL", null,
                List.of(leg("OPTION", "SELL", "OPEN", "MU", "PUT", "960", "2026-03-20", 1, 100, "2.50"))));
        var tx2 = books.record("local", account.id(), tx("2026-02-02", "ROLL", null, 130L, null, "MANUAL", null,
                List.of(leg("OPTION", "BUY", "CLOSE", "MU", "PUT", "960", "2026-03-20", 1, 100, "1.00"),
                        leg("OPTION", "SELL", "OPEN", "MU", "PUT", "960", "2026-04-17", 1, 100, "3.00"))));
        var tx3 = books.record("local", account.id(), tx("2026-04-17", "ASSIGNMENT", null, 0L, null, "MANUAL", null,
                List.of(leg("OPTION", "BUY", "CLOSE", "MU", "PUT", "960", "2026-04-17", 1, 100, "0"),
                        leg("STOCK", "BUY", "OPEN", "MU", null, null, null, 100, 1, "960"))));
        var tx4 = books.record("local", account.id(), tx("2026-05-01", "TRADE", null, 0L, null, "MANUAL", null,
                List.of(leg("OPTION", "SELL", "OPEN", "MU", "CALL", "1000", "2026-06-19", 1, 100, "2.00"))));
        var tx5 = books.record("local", account.id(), tx("2026-05-20", "DIVIDEND", 2_500L, 0L,
                "ORDINARY_DIVIDEND", "MANUAL", null, List.of()));
        var tx6 = books.record("local", account.id(), tx("2026-06-19", "EXPIRATION", null, 0L, null, "MANUAL", null,
                List.of(leg("OPTION", "BUY", "CLOSE", "MU", "CALL", "1000", "2026-06-19", 1, 100, "0"))));
        var tx7 = books.record("local", account.id(), tx("2026-06-25", "TRADE", null, 0L, null, "MANUAL", null,
                List.of(leg("STOCK", "SELL", "CLOSE", "MU", null, null, null, 40, 1, "990"))));
        var tx9 = books.record("local", account.id(), tx("2026-06-30", "INTEREST", 1_234L, 0L,
                "ORDINARY_INTEREST", "MANUAL", null, List.of()));
        var tx10 = books.record("local", account.id(), tx("2026-07-01", "INTEREST", 5_000L, 0L,
                "ORDINARY_INTEREST", "MANUAL", null, List.of()));
        var tx8 = books.record("local", account.id(), tx("2026-07-06", "TRADE", null, 200L, null, "MANUAL", null,
                List.of(leg("STOCK", "BUY", "OPEN", "MU", null, null, null, 40, 1, "1000"))));
        db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed,dataset_id,adjusted,quality_rank,bar_kind) "
                + "VALUES ('MU','2026-01-02',950.00,'stooq',1,'observed',0,0,'CLOSE_ONLY')");
        db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed,dataset_id,adjusted,quality_rank,bar_kind) "
                + "VALUES ('MU','2026-07-10',1005.00,'stooq',1,'observed',0,0,'CLOSE_ONLY')");

        var created = campaigns.create("local", new CampaignService.CreateInput(
                "MU accumulation", "mu", null, null));
        assertThat(created.symbol()).isEqualTo("MU");
        assertThat(created.status()).isEqualTo("ACTIVE");
        campaigns.attach("local", created.id(), member("TRANSACTION", tx1.id(), null));

        // Auto-link: proposals only — the typed member tables stay untouched until confirmation.
        var proposals = campaigns.propose("local", created.id(), null, null, null);
        assertThat(proposals).extracting(CampaignService.Proposal::id)
                .contains(tx2.id(), tx3.id(), tx7.id(), tx4.id(), tx6.id(), tx8.id(), tx5.id(), tx9.id())
                .doesNotContain(tx1.id());
        assertThat(byId(proposals, tx2.id()).reason()).contains("lot and roll chain");
        assertThat(byId(proposals, tx3.id()).reason()).contains("lot and roll chain");
        assertThat(byId(proposals, tx4.id()).reason()).contains("MU activity");
        assertThat(byId(proposals, tx5.id()).reason()).contains("never automatic");
        assertThat(campaigns.view("local", created.id()).members()).hasSize(1);

        // Interest joins only with the explicit tag (§4.6) — and only interest can carry it.
        assertThatThrownBy(() -> campaigns.attach("local", created.id(), member("TRANSACTION", tx9.id(), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never auto-assigned");
        assertThatThrownBy(() -> campaigns.attach("local", created.id(), member("TRANSACTION", tx4.id(), true)))
                .isInstanceOf(IllegalArgumentException.class);

        for (var txn : List.of(tx2, tx3, tx4, tx5, tx6, tx7, tx8)) {
            campaigns.attach("local", created.id(), member("TRANSACTION", txn.id(), null));
        }
        var view = campaigns.attach("local", created.id(), member("TRANSACTION", tx9.id(), true));

        // §4.1 campaign net credit (option cash net of option-leg fees, signed exactly).
        long expectedNetCredit = CampaignMath.campaignNetCredit(List.of(25_000L, 19_870L, 0L, 20_000L, 0L));
        assertThat(view.netOptionCashCents()).isEqualTo(expectedNetCredit).isEqualTo(64_870L);

        // §4.2 campaign-adjusted economic basis per share, through the scale-out/scale-in.
        long expectedBasis = CampaignMath.campaignAdjustedEconomicBasisPerShareCents(
                9_640_000L, 64_870L, 2_500L, 200L, 100L);
        assertThat(view.economicBasis().available()).isTrue();
        assertThat(view.economicBasis().perShareCents()).isEqualTo(expectedBasis).isEqualTo(95_728L);
        assertThat(view.economicBasis().sharesHeld()).isEqualTo(100L);
        assertThat(view.economicBasis().note()).contains("tracked tax basis");

        // §4.5 dividends by explicit attachment; §4.6 interest strictly by explicit tag.
        assertThat(view.attributedDividendsCents()).isEqualTo(2_500L);
        assertThat(view.explicitInterestCents()).isEqualTo(1_234L);
        assertThat(view.members()).extracting(CampaignService.MemberView::id).doesNotContain(tx10.id());

        // §4 churn: 40 shares sold at 990 and re-bought at 1000 with $2.00 of re-entry fees.
        long expectedChurn = CampaignMath.churnRoundTripCostCents(99_000L, 100_000L, 40L, 200L);
        assertThat(view.churn().totalCostCents()).isEqualTo(expectedChurn).isEqualTo(40_200L);
        assertThat(view.churn().roundTrips()).hasSize(1);

        // §4.3 realized-vs-headline: IDENTICAL denominators (one peak committed capital), one window.
        long netCash = 25_000L + 19_870L - 9_600_000L + 20_000L + 2_500L + 0L
                + 3_960_000L + 1_234L - 4_000_200L;
        long carry = books.lots("local", account.id(), false).stream()
                .mapToLong(l -> "LONG".equals(l.side()) ? l.economicRemainingOpenAmountCents()
                        : -l.economicRemainingOpenAmountCents()).sum();
        long realized = netCash + carry;
        int days = (int) java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.parse("2026-01-05"), LocalDate.parse("2026-07-13"));
        var oracle = CampaignMath.realizedVsHeadlineYield(75_000L, 9_575_000L, realized, 9_575_000L, days, days);
        assertThat(view.yield().available()).isTrue();
        assertThat(view.yield().peakCommittedCapitalCents()).isEqualTo(9_575_000L);
        assertThat(view.yield().grossPremiumCents()).isEqualTo(75_000L);
        assertThat(view.yield().realizedPnlCents()).isEqualTo(realized);
        assertThat(view.yield().days()).isEqualTo(days);
        assertThat(view.yield().headlinePeriodPct()).isEqualByComparingTo(oracle.headlinePeriodPct());
        assertThat(view.yield().realizedPeriodPct()).isEqualByComparingTo(oracle.realizedPeriodPct());
        assertThat(view.yield().headlineAnnualizedPct()).isEqualByComparingTo(oracle.headlineAnnualizedPct());
        assertThat(view.yield().realizedAnnualizedPct()).isEqualByComparingTo(oracle.realizedAnnualizedPct());
        assertThat(view.yield().note()).contains("same peak committed capital", "repeats");

        // §4.4 counterfactuals live: cash benchmark at the stated modeled rate; a campaign whose
        // first flow RETURNS cash has no buy-and-hold twin and says so instead of inventing one.
        List<CampaignMath.DatedCashFlow> flows = List.of(
                new CampaignMath.DatedCashFlow(LocalDate.parse("2026-01-05"), -25_000L),
                new CampaignMath.DatedCashFlow(LocalDate.parse("2026-02-02"), -19_870L),
                new CampaignMath.DatedCashFlow(LocalDate.parse("2026-04-17"), 9_600_000L),
                new CampaignMath.DatedCashFlow(LocalDate.parse("2026-05-01"), -20_000L),
                new CampaignMath.DatedCashFlow(LocalDate.parse("2026-05-20"), -2_500L),
                new CampaignMath.DatedCashFlow(LocalDate.parse("2026-06-19"), 0L),
                new CampaignMath.DatedCashFlow(LocalDate.parse("2026-06-25"), -3_960_000L),
                new CampaignMath.DatedCashFlow(LocalDate.parse("2026-06-30"), -1_234L),
                new CampaignMath.DatedCashFlow(LocalDate.parse("2026-07-06"), 4_000_200L));
        long cashOracle = CampaignMath.cashBenchmark(flows, LocalDate.parse("2026-07-13"),
                CampaignService.CASH_BENCHMARK_RATE_BPS);
        assertThat(view.counterfactuals().cash().available()).isTrue();
        assertThat(view.counterfactuals().cash().endingValueCents()).isEqualTo(cashOracle);
        assertThat(view.counterfactuals().campaignEndingValueCents()).isEqualTo(100L * 100_500L);
        assertThat(view.counterfactuals().cash().deltaCents()).isEqualTo(10_050_000L - cashOracle);
        assertThat(view.counterfactuals().buyAndHold().available()).isFalse();
        assertThat(view.counterfactuals().buyAndHold().note()).contains("undefined");

        // The accumulation ledger runs chronologically with running §4 numbers.
        assertThat(view.ledger()).hasSize(9);
        var last = view.ledger().getLast();
        assertThat(last.memberId()).isEqualTo(tx8.id());
        assertThat(last.runningShares()).isEqualTo(100L);
        assertThat(last.runningNetOptionCashCents()).isEqualTo(64_870L);
        assertThat(last.runningBasisPerShareCents()).isEqualTo(95_728L);
        assertThat(view.ledger().getFirst().memberId()).isEqualTo(tx1.id());
        assertThat(view.ledger().getFirst().runningCommittedCapitalCents()).isEqualTo(9_575_000L);

        // Per-account subtotals with an exact-total largest-remainder activity share.
        assertThat(view.accounts()).hasSize(1);
        assertThat(view.accounts().getFirst().shareOfActivityBps()).isEqualTo(10_000);
        assertThat(view.accounts().getFirst().netCashCents()).isEqualTo(netCash);

        // The interpretation-layer receipt is always present; nothing here touched the book.
        assertThat(view.receipts()).anyMatch(r -> r.contains("never the accounting source"));

        // Detaching the dividend removes its attribution — and nothing else.
        var without = campaigns.detach("local", created.id(), "TRANSACTION", tx5.id());
        assertThat(without.attributedDividendsCents()).isZero();
        assertThat(without.netOptionCashCents()).isEqualTo(64_870L);
    }

    @Test
    void pendingImportsContributeExactPackageCashOnlyAndWithholdTaxFigures() {
        var account = books.createAccount("local", account("Brokerage", "TAXABLE", null));
        var tx = books.record("local", account.id(), tx("2026-03-02", "TRADE", null, 0L, null, "MANUAL", null,
                List.of(leg("STOCK", "BUY", "OPEN", "MU", null, null, null, 10, 1, "900"))));
        db.exec("INSERT INTO portfolio_import_pending(id,user_id,source_system,source_account_fingerprint,"
                + "external_ref,occurred_at,package_net_cents) VALUES ('pend-1','local','FIDELITY','fp-1',"
                + "'ref-1','2026-03-09T15:00:00Z',-50_00)");

        var campaign = campaigns.create("local", new CampaignService.CreateInput("MU build", "MU", null, null));
        campaigns.attach("local", campaign.id(), member("TRANSACTION", tx.id(), null));
        var view = campaigns.attach("local", campaign.id(), member("PENDING_IMPORT", "pend-1", null));

        assertThat(view.pendingCount()).isEqualTo(1);
        assertThat(view.unassignedPendingCents()).isEqualTo(-5_000L);
        assertThat(view.receipts()).anyMatch(r -> r.contains("tax figures withheld"));
        assertThat(view.ledger()).hasSize(2);
        assertThat(view.ledger().getLast().memberType()).isEqualTo("PENDING_IMPORT");
        assertThat(view.ledger().getLast().cashEffectCents()).isEqualTo(-5_000L);
        // Pending package cash reaches the running cash math but never the share or option figures.
        assertThat(view.economicBasis().perShareCents())
                .isEqualTo(CampaignMath.campaignAdjustedEconomicBasisPerShareCents(900_000L, 0L, 0L, 0L, 10L));
    }

    @Test
    void seedLotsAttachTheirOpeningTransactionsAndPlansListWithoutNetting() {
        var account = books.createAccount("local", account("Brokerage", "TAXABLE", null));
        books.record("local", account.id(), tx("2026-02-02", "TRADE", null, 0L, null, "MANUAL", null,
                List.of(leg("STOCK", "BUY", "OPEN", "NVDA", null, null, null, 5, 1, "1200"))));
        var lot = books.lots("local", account.id(), false).getFirst();
        db.exec("INSERT INTO plans(id,user_id,symbol,market_kind,status) "
                + "VALUES ('plan-camp-1','local','NVDA','OBSERVED','ACTIVE')");

        var view = campaigns.create("local", new CampaignService.CreateInput(
                "NVDA accumulation", "NVDA", null, List.of(lot.id())));
        assertThat(view.members()).hasSize(1);
        assertThat(view.economicBasis().sharesHeld()).isEqualTo(5L);

        view = campaigns.attach("local", view.id(), member("PLAN", "plan-camp-1", null));
        var plan = view.members().stream().filter(m -> "PLAN".equals(m.type())).findFirst().orElseThrow();
        assertThat(plan.countsInMath()).isFalse();
        assertThat(view.economicBasis().sharesHeld()).isEqualTo(5L);

        // Membership is owner-scoped end to end.
        var foreign = books.createAccount("owner-b", account("Elsewhere", "TAXABLE", null));
        var foreignTx = books.record("owner-b", foreign.id(), tx("2026-02-03", "DEPOSIT", 1_000L, 0L,
                null, "MANUAL", null, List.of()));
        String campaignId = view.id();
        assertThatThrownBy(() -> campaigns.attach("local", campaignId,
                member("TRANSACTION", foreignTx.id(), null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> campaigns.view("owner-b", campaignId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static CampaignService.MemberInput member(String type, String id, Boolean explicitInterest) {
        return new CampaignService.MemberInput(type, id, explicitInterest);
    }

    private static CampaignService.Proposal byId(List<CampaignService.Proposal> proposals, String id) {
        return proposals.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    private static PortfolioAccountingService.AccountInput account(String name, String type, Long cash) {
        return new PortfolioAccountingService.AccountInput(name, type, null, "FIFO", null, null, null, null, cash);
    }

    private static PortfolioAccountingService.TransactionInput tx(String date, String event, Long cash,
                                                                  Long fees, String category, String source,
                                                                  String ref,
                                                                  List<PortfolioAccountingService.LegInput> legs) {
        String fillNature = switch (event) {
            case "TRADE", "ROLL", "EXPIRATION", "ASSIGNMENT", "EXERCISE" -> "EXECUTED";
            case "MARK_TO_MARKET" -> "MODELED";
            default -> "NOT_APPLICABLE";
        };
        return new PortfolioAccountingService.TransactionInput(date, event, cash, fees, category, source, ref,
                null, legs, fillNature);
    }

    private static PortfolioAccountingService.LegInput leg(String instrument, String action, String effect,
                                                           String symbol, String optionType, String strike,
                                                           String expiration, long quantity, int multiplier,
                                                           String price) {
        return new PortfolioAccountingService.LegInput(instrument, action, effect, symbol, optionType,
                strike == null ? null : new BigDecimal(strike),
                expiration == null ? null : LocalDate.parse(expiration),
                quantity, multiplier, new BigDecimal(price), null);
    }
}
