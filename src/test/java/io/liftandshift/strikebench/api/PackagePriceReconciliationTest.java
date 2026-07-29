package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.paper.AccountService;
import io.liftandshift.strikebench.paper.AuditLog;
import io.liftandshift.strikebench.paper.MarksSource;
import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.paper.TradePreview;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.market.MarketDataMarks;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.recommend.RecommendationEngine;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE §3.3 test. One package, the candidate rail, ticket, order dock, and outcome engine — all
 * priced by one current-book owner and published through one shared §7.2 receipt. Before this
 * receipt existed the rail printed an option-only net struck at scan time while the dock printed a
 * stock-inclusive net struck live, with no quantity and no basis on either object to explain the
 * gap: exactly the state PROGRAM_ONE_HANDOFF.md §3.3 names financially untrustworthy.
 *
 * <p>A buy-write is the deliberate subject: it is the structure where the option-only net and the
 * whole-package net are furthest apart (a credit and a large debit), so a surface that confuses
 * them is caught here rather than by a customer.</p>
 */
class PackagePriceReconciliationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"),
            ZoneId.of("America/New_York"));
    private static final LocalDate EXP = LocalDate.of(2026, 8, 21);
    private static final long QUOTE_STAMP = 1_785_000_000_000L;
    private static final String SCAN_SYMBOL = "AAPL";
    private static final long SCAN_BUYING_POWER = 10_000_000L; // $100k

    private Db db;
    private TradeService trades;
    private String accountId;
    /** The scan lane and the ticket lane, wired to ONE fixture market so only the engines differ. */
    private RecommendationEngine scanner;
    private TradeService fixtureTrades;
    private String fixtureAccountId;
    private MarketDataService fixtureMarket;
    private OutcomeController outcomes;

    /** Underlying $100 with a real two-sided book; the short call trades 2.40 / 2.60. */
    private static final class BookMarks implements MarksSource {
        @Override public Optional<BigDecimal> underlyingMark(String symbol) {
            return Optional.of(new BigDecimal("100.00"));
        }
        @Override public Optional<Long> underlyingAsOfMs(String symbol) { return Optional.of(QUOTE_STAMP); }
        @Override public Optional<DataEvidence> underlyingEvidence(String symbol, String worldId) {
            return Optional.of(DataEvidence.of("test-demo", Freshness.FIXTURE));
        }
        @Override public Optional<BigDecimal> closeOn(String symbol, LocalDate date) { return Optional.empty(); }
        @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
            DataEvidence evidence = new DataEvidence(io.liftandshift.strikebench.model.DataProvenance.DEMO,
                    DataEvidence.of(null, Freshness.FIXTURE).age(), "test-demo");
            if (leg.isStock()) {
                return Optional.of(new LegMark(new BigDecimal("99.95"), new BigDecimal("100.05"),
                        new BigDecimal("100.00"), null, Freshness.FIXTURE, null, null, null, null,
                        evidence, QUOTE_STAMP));
            }
            return Optional.of(new LegMark(new BigDecimal("2.40"), new BigDecimal("2.60"),
                    new BigDecimal("2.50"), 0.25, Freshness.FIXTURE, null, null, null, null,
                    evidence, QUOTE_STAMP));
        }
    }

    @BeforeEach
    void setUp() {
        db = TestDb.fresh();
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        AuditLog audit = new AuditLog(db, CLOCK);
        trades = new TradeService(db, cfg, new BookMarks(), audit, CLOCK);
        accountId = new AccountService(db, cfg, audit, CLOCK).getOrCreateDefault().id();

        // ONE market for both lanes: the same fixture chain the scan reads is the chain the ticket
        // reprices against, and the SAME fee schedule ApiServer gives both in production. Any gap
        // between the two receipts is then a property of the engines, not of two different worlds.
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        fixtureMarket = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        scanner = new RecommendationEngine(fixtureMarket, CLOCK)
                .withFees(cfg.feePerContractCents(), cfg.feePerOrderCents());
        fixtureTrades = new TradeService(db, cfg, new MarketDataMarks(fixtureMarket), audit, CLOCK);
        fixtureAccountId = accountId;
        outcomes = new OutcomeController(cfg, CLOCK, fixtureMarket,
                null, null, null, null, null, null, null, null);
    }

    @AfterEach
    void closeDb() {
        if (db != null) db.close();
    }

    /** Buy 100 shares and sell the 105 call against them, twice over. */
    private TradeService.OpenRequest buyWrite(OrderInstruction instruction) {
        List<Leg> legs = List.of(
                Leg.stockShares(LegAction.BUY, 100, BigDecimal.ZERO),
                Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("105"), EXP, 1, BigDecimal.ZERO));
        return new TradeService.OpenRequest(accountId, "AAPL", "COVERED_CALL", 2, legs, "neutral",
                "month", "balanced", "INCOME", false, null, "PLAN",
                "PROPOSED", instruction);
    }

    /**
     * The exact ticket uses the preview's receipt while the order dock owns only the instruction.
     * This keeps the decision envelope from serializing the same exact package price twice. The
     * real §3.3 question, two INDEPENDENTLY priced receipts for the same package, is
     * {@link #aScannedCandidateTicketAndOutcomeUseOneStockInclusivePackagePrice}.
     */
    @Test
    void theExactTicketUsesThePreviewsOwnReceiptAndTheOrderDockOwnsOnlyTheInstruction() {
        TradeService.OpenRequest request = buyWrite(OrderInstruction.market());
        TradePreview preview = trades.preview(request);
        assertThat(preview.ok()).as("blocked: %s", preview.blockReasons()).isTrue();

        Candidate candidate = TradeController.exactPreviewCandidate(request, preview);
        ApiResponses.OrderDock dock = PlanDecisionController.orderDock(order(request));

        assertThat(candidate.price()).isSameAs(preview.price());
        assertThat(dock.orderInstruction()).isEqualTo(request.orderInstruction());
        assertThat(candidate.qty()).isEqualTo(preview.price().quantity()).isEqualTo(2);
    }

    /**
     * THE §3.3 test: three consumers price the SAME package from the same captured market evidence:
     * the {@link RecommendationEngine} candidate rail, {@link TradeService} preview dock, and
     * {@link OutcomeController} path evaluator.
     *
     * <p>A buy-write is the deliberate subject: it is the structure where the option-only net and
     * the whole-package net are furthest apart (a credit and a large debit), so a surface that
     * confuses them is caught here rather than by a customer.</p>
     *
     * <p>With an unchanged captured book, agreement is now an identity—not a reconciliation
     * convention. A later quote can still produce a later fingerprint, but no consumer owns a
     * separate bid/ask, stock-mark, multiplier, fee, or rounding policy.</p>
     */
    @Test
    void aScannedCandidateTicketAndOutcomeUseOneStockInclusivePackagePrice() {
        Candidate scanned = scannedBuyWrite();
        PackagePriceReceipt rail = scanned.price();

        List<Leg> sameLegs = scanned.legs().stream().map(LegView::toLeg).toList();
        TradePreview preview = fixtureTrades.preview(new TradeService.OpenRequest(
                fixtureAccountId, SCAN_SYMBOL, scanned.strategy(), scanned.qty(), sameLegs,
                "neutral", "month", "balanced", "INCOME", false, null, "PLAN", "PROPOSED",
                OrderInstruction.market()));
        assertThat(preview.ok()).as("blocked: %s", preview.blockReasons()).isTrue();
        PackagePriceReceipt dock = preview.price();
        List<String> exactExpirations = sameLegs.stream()
                .map(leg -> leg.isStock() ? "" : leg.expiration().toString()).toList();
        OutcomeController.MarketEntry outcome = outcomes.marketEntry(SCAN_SYMBOL,
                new io.liftandshift.strikebench.sim.PathPosition(
                        LocalDate.ofInstant(CLOCK.instant(),
                                io.liftandshift.strikebench.market.MarketHours.EASTERN),
                        sameLegs),
                scanned.qty(), null, null, exactExpirations);
        assertThat(outcome).as("the exact outcome package must be priceable").isNotNull();
        PackagePriceReceipt path = outcome.price();

        // Same captured book, same exact package, same canonical owner: all three surfaces now
        // publish the identical receipt rather than merely carrying enough labels to explain a gap.
        assertThat(dock).isEqualTo(rail);
        assertThat(path).isEqualTo(rail);
        assertThat(dock.quantity()).isEqualTo(rail.quantity()).isEqualTo(scanned.qty());

        // The same option-only net for the same legs: the one amount that must NOT move between a
        // scan and a ticket on identical marks, and the amount the management protocol is measured
        // on. A buy-write is priced here precisely because this is where it used to be confused
        // with the stock-inclusive package net.
        assertThat(dock.optionNetPremiumCents()).isEqualTo(rail.optionNetPremiumCents());
        assertThat(rail.optionNetPremiumCents()).as("the call sold is a credit").isPositive();
        assertThat(rail.stockCashFlowCents()).as("the shares bought are a debit").isNegative();
        assertThat(rail.grossPackageNetCents()).as("the package as a whole is paid for").isNegative();

        // The additive identity on BOTH receipts, each side measured independently of the whole.
        for (PackagePriceReceipt receipt : List.of(rail, dock)) {
            assertThat(receipt.grossPackageNetCents())
                    .isEqualTo(receipt.optionNetPremiumCents() + receipt.stockCashFlowCents());
        }
        assertThat(dock.stockCashFlowCents()).isEqualTo(rail.stockCashFlowCents());

        // The fee identity on both, on the SAME fee schedule — the rail's opening commission is no
        // longer computed for a yield display and thrown away before the price is published.
        assertThat(rail.openingFeesCents()).isEqualTo(dock.openingFeesCents()).isPositive();
        assertThat(rail.afterFeeNetCents())
                .isEqualTo(rail.grossPackageNetCents() - rail.openingFeesCents());
        assertThat(dock.afterFeeNetCents())
                .isEqualTo(dock.grossPackageNetCents() - dock.openingFeesCents());
        assertThat(rail.feeSide()).isEqualTo(dock.feeSide())
                .isEqualTo(PackagePriceReceipt.FeeSide.OPENING);

        // Every surface names the same real basis and observation stamp.
        assertThat(rail.valuationBasis()).isNotEqualTo(PackagePriceReceipt.ValuationBasis.UNAVAILABLE);
        assertThat(dock.valuationBasis()).isNotEqualTo(PackagePriceReceipt.ValuationBasis.UNAVAILABLE);
        assertThat(rail.observedAt()).isNotNull();
        assertThat(dock.observedAt()).isNotNull();
        assertThat(rail.fingerprint()).isNotBlank();
        assertThat(dock.fingerprint()).isNotBlank();
    }

    /** One buy-write straight off the scan, priced by RecommendationEngine and nothing else. */
    private Candidate scannedBuyWrite() {
        RecommendationEngine.Result result = scanner.recommend(new RecommendationEngine.Request(
                SCAN_SYMBOL, "neutral", "month", "balanced", null, null, null,
                List.of("COVERED_CALL"), true, false, "INCOME", null, null), SCAN_BUYING_POWER);
        Candidate buyWrite = result.candidates().stream()
                .filter(candidate -> candidate.legs().stream().anyMatch(leg -> "STOCK".equals(leg.type())))
                .findFirst().orElse(null);
        assertThat(buyWrite).as("no buy-write scanned; notes=%s rejected=%s",
                result.notes(), result.rejected()).isNotNull();
        return buyWrite;
    }

    @Test
    void aBuyWriteSeparatesItsOptionCreditFromItsShareCostAndFromItsFees() {
        TradePreview preview = trades.preview(buyWrite(OrderInstruction.market()));
        PackagePriceReceipt price = preview.price();

        // Buys pay the ask, sells receive the bid: 2 × (−100.05 × 100 shares) for the stock,
        // 2 × (+2.40 × 100) for the calls sold.
        assertThat(price.stockCashFlowCents()).isEqualTo(-2_001_000L);
        assertThat(price.optionNetPremiumCents()).isEqualTo(48_000L);
        assertThat(price.grossPackageNetCents()).isEqualTo(-1_953_000L);

        // The additive identity: the option credit and the share purchase are NAMED, so a screen
        // showing "+$480" and a screen showing "−$19,530" are visibly the same package.
        assertThat(price.grossPackageNetCents())
                .isEqualTo(price.optionNetPremiumCents() + price.stockCashFlowCents());

        // The fee identity, on the fee that is actually charged rather than an override defaulting
        // to zero. Fees ride the OPENING side and the receipt says so.
        assertThat(price.openingFeesCents()).isPositive();
        assertThat(price.afterFeeNetCents())
                .isEqualTo(price.grossPackageNetCents() - price.openingFeesCents());
        assertThat(price.feeSide()).isEqualTo(PackagePriceReceipt.FeeSide.OPENING);

        // §3.1: the receipt is the preview's ONLY package price. This assertion used to prove the
        // legacy `entryNetPremiumCents`/`feesOpenCents` primitives AGREED with the receipt, which is
        // a weaker property than having one author — they agreed on a priced package and diverged
        // (0 vs null) on every refused one. Those fields are gone, and the published shape is what
        // pins it: a surface reading this payload has exactly one net and one commission to read.
        var wire = io.liftandshift.strikebench.util.Json.MAPPER.valueToTree(preview);
        assertThat(wire.has("entryNetPremiumCents")).isFalse();
        assertThat(wire.has("feesOpenCents")).isFalse();
        assertThat(wire.at("/price/grossPackageNetCents").asLong())
                .isEqualTo(price.grossPackageNetCents());
        assertThat(wire.at("/price/openingFeesCents").asLong()).isEqualTo(price.openingFeesCents());
    }

    @Test
    void aRestingLimitIsPricedOnItsOwnBasisAndStillNamesTheExecutableMarket() {
        TradePreview natural = trades.preview(buyWrite(OrderInstruction.market()));
        long executable = natural.price().grossPackageNetCents();

        // A limit MORE favorable than the book cannot fill; it is priced as a resting limit.
        TradePreview resting = trades.preview(buyWrite(OrderInstruction.limit(executable + 1_000)));
        PackagePriceReceipt price = resting.price();

        assertThat(price.valuationBasis()).isEqualTo(PackagePriceReceipt.ValuationBasis.RESTING_LIMIT);
        assertThat(price.executability()).isEqualTo(OrderInstruction.Executability.RESTING);
        assertThat(price.restingLimitNetCents()).isEqualTo(executable + 1_000);
        assertThat(price.grossPackageNetCents()).isEqualTo(executable + 1_000);
        // …while still reporting what the market would actually pay, so the two are comparable.
        assertThat(price.executableNetCents()).isEqualTo(executable);
        assertThat(price.grossPackageNetCents())
                .isEqualTo(price.optionNetPremiumCents() + price.stockCashFlowCents());
    }

    private static TradeOpenRequest order(TradeService.OpenRequest request) {
        return new TradeOpenRequest(request.symbol(), request.strategy(), request.qty(), List.of(),
                request.thesis(), request.horizon(), request.riskMode(), request.intent(), false, null,
                null, "PLAN", List.of(), null, "PROPOSED",
                request.orderInstruction());
    }
}
