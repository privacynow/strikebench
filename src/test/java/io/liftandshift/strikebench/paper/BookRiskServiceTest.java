package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.EtfLookThroughService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Battery scenario 6 (the Vanguard concentrated book) against the book-risk lane: a multi-account
 * semiconductor short-premium book with a covered call against short puts, an 8/07 expiry
 * cluster, an INTC sell-then-rebuy churn pair, a JEPQ strategy collision, and both marked and
 * unmarked lots — asserting every §10.2 deliverable including the exact coverage disclosures.
 */
class BookRiskServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-13T16:00:00Z"), ZoneOffset.UTC);

    private Db db;
    private PortfolioAccountingService books;
    private AccountObjectiveService objectives;
    private BookRiskService risk;
    private TradeService trades;
    private Account practice;

    /** Observed-evidence marks: spots for NVDA/AMD/AVGO/JEPQ-none, option greeks per contract. */
    private static final Map<String, String> SPOTS = Map.of(
            "NVDA", "1000.00", "AMD", "160.00", "AVGO", "300.00", "INTC", "140.00");
    private static final Map<String, double[]> OPTION_GREEKS = greeks();

    private static Map<String, double[]> greeks() {
        Map<String, double[]> m = new LinkedHashMap<>(); // delta, gamma, vega
        m.put("NVDA|PUT|950|2026-08-07", new double[]{-0.35, 0.002, 1.20});
        m.put("NVDA|CALL|1000|2026-08-07", new double[]{0.55, 0.002, 1.10});
        m.put("AMD|PUT|160|2026-08-07", new double[]{-0.45, 0.01, 0.35});
        m.put("AMD|PUT|100|2026-09-18", new double[]{-0.05, 0.001, 0.10});
        m.put("AVGO|PUT|280|2026-08-21", new double[]{-0.40, 0.005, 0.50});
        return m;
    }

    private static final MarksSource MARKS = new MarksSource() {
        @Override public Optional<BigDecimal> underlyingMark(String symbol) {
            return Optional.ofNullable(SPOTS.get(symbol)).map(BigDecimal::new);
        }
        @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
            if (leg.isStock()) {
                return Optional.ofNullable(SPOTS.get(symbol)).map(BigDecimal::new)
                        .map(spot -> new LegMark(spot, spot, spot, null, Freshness.DELAYED,
                                1.0, 0.0, 0.0, 0.0, DataEvidence.of("observed-test", Freshness.DELAYED)));
            }
            String key = symbol + "|" + leg.type() + "|" + leg.strike().stripTrailingZeros().toPlainString()
                    + "|" + leg.expiration();
            double[] g = OPTION_GREEKS.get(key);
            if (g == null) return Optional.empty();
            BigDecimal mid = new BigDecimal("10.00");
            return Optional.of(new LegMark(mid, mid, mid, 0.5, Freshness.DELAYED,
                    g[0], g[1], null, g[2], DataEvidence.of("observed-test", Freshness.DELAYED)));
        }
    };

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        books = new PortfolioAccountingService(db, CLOCK, MARKS);
        objectives = new AccountObjectiveService(db, CLOCK);
        var cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        var audit = new AuditLog(db, CLOCK);
        trades = new TradeService(db, cfg, MARKS, audit, CLOCK);
        practice = new AccountService(db, cfg, audit, CLOCK).getOrCreateDefault();
        risk = new BookRiskService(db, CLOCK, MARKS, books, objectives, trades);
    }

    @AfterEach void close() { if (db != null) db.close(); }

    /** Two accounts shaped like battery scenario 6; returns [iraId, taxableId]. */
    private String[] seedVanguardBook() {
        seedObservedBars();
        var ira = books.createAccount("local", account("Vanguard IRA", "TRADITIONAL_IRA", 25_000_000L));
        objectives.declare("local", ira.id(), "ACCUMULATE", "BULLISH", null, "ACCEPT");
        books.record("local", ira.id(), trade("2026-07-01", 0L,
                leg("OPTION", "SELL", "OPEN", "NVDA", "PUT", "950", "2026-08-07", 1, 100, "30.00")));
        books.record("local", ira.id(), trade("2026-07-01", 0L,
                leg("STOCK", "BUY", "OPEN", "NVDA", null, null, null, 100, 1, "900.00")));
        books.record("local", ira.id(), trade("2026-07-02", 0L,
                leg("OPTION", "SELL", "OPEN", "NVDA", "CALL", "1000", "2026-08-07", 2, 100, "25.00")));
        books.record("local", ira.id(), trade("2026-07-02", 0L,
                leg("OPTION", "SELL", "OPEN", "AMD", "PUT", "160", "2026-08-07", 1, 100, "4.00")));
        books.record("local", ira.id(), trade("2026-07-03", 0L,
                leg("OPTION", "SELL", "OPEN", "AVGO", "PUT", "280", "2026-08-21", 1, 100, "6.00")));
        books.record("local", ira.id(), trade("2026-07-03", 0L,
                leg("OPTION", "SELL", "OPEN", "AMD", "PUT", "100", "2026-09-18", 1, 100, "1.00")));

        var taxable = books.createAccount("local", account("Taxable brokerage", "TAXABLE", 20_000_000L));
        books.record("local", taxable.id(), trade("2026-05-05", 0L,
                leg("STOCK", "BUY", "OPEN", "INTC", null, null, null, 100, 1, "100.00")));
        books.record("local", taxable.id(), trade("2026-06-01", 0L,
                leg("STOCK", "BUY", "OPEN", "JEPQ", null, null, null, 100, 1, "50.00")));
        books.record("local", taxable.id(), trade("2026-06-15", 0L,
                leg("OPTION", "SELL", "OPEN", "MU", "PUT", "120", "2026-08-07", 1, 100, "2.50")));
        books.record("local", taxable.id(), trade("2026-06-16", 0L,
                leg("OPTION", "SELL", "OPEN", "JEPQ", "CALL", "55", "2026-08-07", 1, 100, "1.00")));
        books.record("local", taxable.id(), trade("2026-06-20", 0L,
                leg("STOCK", "SELL", "CLOSE", "INTC", null, null, null, 50, 1, "118.00")));
        books.record("local", taxable.id(), trade("2026-06-27", 0L,
                leg("STOCK", "BUY", "OPEN", "INTC", null, null, null, 50, 1, "140.00")));
        return new String[]{ira.id(), taxable.id()};
    }

    /**
     * 36 shared weekday sessions of observed closes: SPY alternates ±1%, NVDA moves 1.5× and
     * AMD 2.0× SPY's return each session, so the regressed betas are 1.5 and 2.0 by construction.
     * AVGO gets no history at all — it must be disclosed as unweighted, never guessed.
     */
    private void seedObservedBars() {
        StringBuilder sql = new StringBuilder("INSERT INTO underlying_bar"
                + "(symbol,d,close,source,observed,dataset_id,adjusted,quality_rank) VALUES ");
        BigDecimal spy = new BigDecimal("500");
        BigDecimal nvda = new BigDecimal("1000");
        BigDecimal amd = new BigDecimal("150");
        LocalDate date = LocalDate.parse("2026-05-04");
        int added = 0, step = 0;
        while (added < 36) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                date = date.plusDays(1);
                continue;
            }
            if (added > 0) {
                double market = step % 2 == 0 ? 0.01 : -0.01;
                spy = scaled(spy, 1 + market);
                nvda = scaled(nvda, 1 + 1.5 * market);
                amd = scaled(amd, 1 + 2.0 * market);
                step++;
            }
            if (added > 0) sql.append(',');
            sql.append(bar("SPY", date, spy)).append(',').append(bar("NVDA", date, nvda))
                    .append(',').append(bar("AMD", date, amd));
            added++;
            date = date.plusDays(1);
        }
        db.exec(sql.toString());
    }

    private static BigDecimal scaled(BigDecimal close, double factor) {
        return close.multiply(BigDecimal.valueOf(factor)).setScale(6, RoundingMode.HALF_UP);
    }

    private static String bar(String symbol, LocalDate d, BigDecimal close) {
        return "('" + symbol + "','" + d + "'," + close.toPlainString() + ",'observed-feed',1,'observed',1,10)";
    }

    @Test
    void betaWeightedDollarGreeksAggregateFromLotsWithCoverageDisclosures() {
        String[] ids = seedVanguardBook();
        var lane = risk.lane("local", practice.id());
        var ira = lane.accounts().stream().filter(a -> a.accountId().equals(ids[0])).findFirst().orElseThrow();

        var greeks = ira.greeks();
        // Unweighted net dollar delta: -35Δ×(-100)×$1000 + .55Δ×(-200)×$1000 + ... = -$55,000.
        assertThat(greeks.netDollarDeltaCents()).isEqualTo(-5_500_000L);
        // Beta weighting: NVDA legs ×1.5, AMD legs ×2.0, AVGO unweighted (no history) ×1.
        assertThat(greeks.betaWeightedDollarDeltaCents()).isCloseTo(-8_450_000L, offset(100L));
        assertThat(greeks.vegaPerPointCents()).isEqualTo(-43_500L);
        assertThat(greeks.gammaPer1PctCents()).isEqualTo(-673_160L);
        assertThat(greeks.optionLots()).isEqualTo(5);
        assertThat(greeks.markedOptionLots()).isEqualTo(5);
        assertThat(greeks.complete()).isTrue();
        assertThat(greeks.greekCoverage()).isEqualTo("Delta aggregated over 5 of 5 option lots; 0 lack observed marks.");
        assertThat(greeks.betaCoverage()).isEqualTo(
                "Betas from 35 sessions of observed closes; 1 symbol lacks history and is shown unweighted.");
        assertThat(greeks.betas()).filteredOn(BookRiskService.BetaRow::weighted)
                .extracting(BookRiskService.BetaRow::symbol).containsExactlyInAnyOrder("NVDA", "AMD");
        var nvdaBeta = greeks.betas().stream().filter(b -> b.symbol().equals("NVDA")).findFirst().orElseThrow();
        assertThat(nvdaBeta.beta()).isCloseTo(1.5, offset(0.001));
        assertThat(nvdaBeta.sessions()).isEqualTo(35);
        assertThat(greeks.betas()).filteredOn(b -> !b.weighted())
                .extracting(BookRiskService.BetaRow::symbol).containsExactly("AVGO");
        assertThat(greeks.basis()).contains("Raw share delta is not additive across names");
    }

    @Test
    void unmarkedLotsAreDisclosedAndNeverSilentlyPartial() {
        String[] ids = seedVanguardBook();
        var lane = risk.lane("local", practice.id());
        var taxable = lane.accounts().stream().filter(a -> a.accountId().equals(ids[1])).findFirst().orElseThrow();

        var greeks = taxable.greeks();
        assertThat(greeks.optionLots()).isEqualTo(2); // MU put + JEPQ call, neither has a mark
        assertThat(greeks.markedOptionLots()).isZero();
        assertThat(greeks.betaWeightedDollarDeltaCents()).isNull();
        assertThat(greeks.netDollarDeltaCents()).isNull();
        assertThat(greeks.complete()).isFalse();
        assertThat(greeks.greekCoverage()).isEqualTo("Delta aggregated over 0 of 2 option lots; 2 lack observed marks.");
    }

    @Test
    void stressedAssignmentCapitalCountsOnlyPutsInTheMoneyUnderTheShock() {
        String[] ids = seedVanguardBook();
        var lane = risk.lane("local", practice.id());
        var ira = lane.accounts().stream().filter(a -> a.accountId().equals(ids[0])).findFirst().orElseThrow();

        var stress = ira.stress();
        // NVDA 950 (spot 1000 -> 900 ITM), AMD 160 (160 -> 144 ITM), AVGO 280 (300 -> 270 ITM);
        // the AMD 100 put stays OUT under the shock (144 > 100) and must not be counted.
        assertThat(stress.shockPct()).isEqualTo(10);
        assertThat(stress.obligationCents()).isEqualTo(9_500_000L + 1_600_000L + 2_800_000L);
        assertThat(stress.contracts()).isEqualTo(3);
        assertThat(stress.unmarkedLots()).isZero();
        assertThat(stress.cashCents()).isEqualTo(16_910_000L);
        assertThat(stress.sentence()).isEqualTo("If these names fall 10%, your short puts obligate "
                + "$139,000.00 vs your $169,100.00 recorded cash in this account.");
        assertThat(stress.basis()).contains("labeled heuristic").contains("not a correlation model");

        // The taxable MU put has no observed underlying mark: disclosed separately, never guessed.
        var taxable = lane.accounts().stream().filter(a -> a.accountId().equals(ids[1])).findFirst().orElseThrow();
        assertThat(taxable.stress().obligationCents()).isZero();
        assertThat(taxable.stress().unmarkedObligationCents()).isEqualTo(1_200_000L);
        assertThat(taxable.stress().unmarkedLots()).isEqualTo(1);
        assertThat(taxable.stress().sentence()).contains("$12,000.00")
                .contains("cannot be stressed — no observed underlying mark");
    }

    @Test
    void expiryCalendarFlagsTheAugustSeventhCluster() {
        String[] ids = seedVanguardBook();
        var lane = risk.lane("local", practice.id());
        var ira = lane.accounts().stream().filter(a -> a.accountId().equals(ids[0])).findFirst().orElseThrow();

        var rows = ira.expiries().rows();
        assertThat(rows).extracting(BookRiskService.ExpiryRow::date)
                .containsExactly("2026-08-07", "2026-08-21", "2026-09-18");
        var cluster = rows.getFirst();
        // 950×100 + 1000×200 + 160×100 notional on one session.
        assertThat(cluster.notionalCents()).isEqualTo(31_100_000L);
        assertThat(cluster.lots()).isEqualTo(3);
        assertThat(cluster.shortPutObligationCents()).isEqualTo(11_100_000L);
        assertThat(cluster.flagged()).isTrue();
        assertThat(rows.get(1).flagged()).isFalse();
        assertThat(ira.expiries().clusterNote()).isEqualTo(
                "$311,000.00 notional expires 2026-08-07 — 89% of this book's option notional on one session.");
        assertThat(ira.expiries().basis()).contains("heuristic threshold");

        // Two lots on one date in the taxable account is below the cluster thresholds.
        var taxable = lane.accounts().stream().filter(a -> a.accountId().equals(ids[1])).findFirst().orElseThrow();
        assertThat(taxable.expiries().clusterNote()).isNull();
    }

    @Test
    void themeConcentrationIsLabeledClassificationAndNamesTheOneSemiconductorBet() {
        String[] ids = seedVanguardBook();
        var lane = risk.lane("local", practice.id());
        var ira = lane.accounts().stream().filter(a -> a.accountId().equals(ids[0])).findFirst().orElseThrow();

        var themes = ira.themes();
        assertThat(themes.rows()).hasSize(1);
        var semis = themes.rows().getFirst();
        assertThat(semis.label()).isEqualTo("Semiconductors, memory & storage");
        assertThat(semis.share()).isEqualTo(1.0);
        assertThat(semis.notionalCents()).isEqualTo(44_900_000L); // options strike notional + shares at spot
        assertThat(semis.netDollarDeltaCents()).isEqualTo(4_500_000L); // shares +$100k, options -$55k
        assertThat(semis.bothSides()).isTrue();
        assertThat(themes.concentrationCallout())
                .contains("Effectively one semiconductors, memory & storage bet")
                .contains("100%").contains("NVDA").contains("AMD").contains("AVGO");
        assertThat(themes.classificationLabel())
                .contains("classification, not measured correlation")
                .contains("observed return series");
    }

    @Test
    void reviewedEtfLookThroughIsComposedWithoutReplacingDirectClassification() {
        risk = new BookRiskService(db, CLOCK, MARKS, books, objectives, trades,
                null, null, new EtfLookThroughService(CLOCK));
        String[] ids = seedVanguardBook();

        var taxable = risk.lane("local", practice.id()).accounts().stream()
                .filter(account -> account.accountId().equals(ids[1])).findFirst().orElseThrow();
        var themes = taxable.themes();

        assertThat(themes.rows()).isNotEmpty(); // original direct-symbol receipt survives
        assertThat(themes.etfLookThrough()).isNotNull();
        assertThat(themes.etfLookThrough().funds())
                .extracting(EtfLookThroughService.FundReceipt::fund).containsExactly("JEPQ");
        assertThat(themes.etfLookThrough().components())
                .extracting(EtfLookThroughService.ComponentExposure::symbol)
                .contains("NVDA", "AAPL", "MSFT", "AVGO");
        assertThat(themes.etfLookThrough().residualNotionalCents()).isPositive();
        assertThat(themes.lookThroughRows()).extracting(BookRiskService.ThemeRow::label)
                .contains("Semiconductors, memory & storage", "ETF residual / undisclosed holdings");
        assertThat(themes.lookThroughRows().stream()
                .mapToLong(BookRiskService.ThemeRow::notionalCents).sum())
                .isEqualTo(themes.symbolNotionals().stream()
                        .mapToLong(BookRiskService.SymbolNotional::notionalCents).sum());
        assertThat(themes.classificationLabel()).contains("reviewed issuer holdings")
                .contains("never imply measured correlation");
    }

    @Test
    void intraThemeContradictionQuotesTheDeclaredObjectiveRevision() {
        String[] ids = seedVanguardBook();
        var lane = risk.lane("local", practice.id());
        var ira = lane.accounts().stream().filter(a -> a.accountId().equals(ids[0])).findFirst().orElseThrow();

        assertThat(ira.objective().objective()).isEqualTo("ACCUMULATE");
        assertThat(ira.objective().direction()).isEqualTo("BULLISH");
        assertThat(ira.contradictions()).hasSize(1);
        var contradiction = ira.contradictions().getFirst();
        assertThat(contradiction.theme()).isEqualTo("Semiconductors, memory & storage");
        assertThat(contradiction.longVia()).contains("short puts: NVDA ×1", "short puts: AMD ×2", "short puts: AVGO ×1");
        assertThat(contradiction.shortVia()).containsExactly("covered calls: NVDA ×2");
        // Net theme delta $45,000 on ~$265,000 gross -> within the ±20% neutral band.
        assertThat(contradiction.netThemeDollarDeltaCents()).isEqualTo(4_500_000L);
        // Lots iterate symbol-ordered (AMD, AVGO, NVDA) — the union preserves that order.
        assertThat(contradiction.message()).isEqualTo("You are both long (short puts) and short "
                + "(covered calls) AMD/AVGO/NVDA — Semiconductors, memory & storage: net thematic dollar delta "
                + "≈ neutral ($45,000.00) — despite a bullish stated goal (this account's declared "
                + "objective, revision 1).");
    }

    @Test
    void coveredCallFundCollisionIsCalledOutAsAHeuristic() {
        String[] ids = seedVanguardBook();
        var lane = risk.lane("local", practice.id());
        var taxable = lane.accounts().stream().filter(a -> a.accountId().equals(ids[1])).findFirst().orElseThrow();

        assertThat(taxable.collisions()).singleElement().satisfies(message ->
                assertThat(message).contains("JEPQ is a covered-call fund")
                        .contains("writing calls yourself").contains("heuristic"));
        // No declared objective on this account: the contradiction machinery must say so
        // rather than invent a direction (MU short put alone is not a contradiction).
        assertThat(taxable.objective()).isNull();
        assertThat(taxable.contradictions()).isEmpty();
    }

    @Test
    void churnAccountingPairsTheIntcSellThenRebuyWithTheSectionFourCost() {
        String[] ids = seedVanguardBook();
        var lane = risk.lane("local", practice.id());
        var taxable = lane.accounts().stream().filter(a -> a.accountId().equals(ids[1])).findFirst().orElseThrow();

        var churn = taxable.churn();
        assertThat(churn.pairs()).hasSize(1);
        var pair = churn.pairs().getFirst();
        assertThat(pair.symbol()).isEqualTo("INTC");
        assertThat(pair.shares()).isEqualTo(50);
        assertThat(pair.exitPerShareCents()).isEqualTo(11_800L);
        assertThat(pair.reentryPerShareCents()).isEqualTo(14_000L);
        assertThat(pair.costCents()).isEqualTo(110_000L); // (140-118) × 50 shares, no fees
        assertThat(pair.message()).isEqualTo(
                "You sold INTC at $118.00 and rebought at $140.00 — the round trip cost $1,100.00 on 50 shares.");
        assertThat(churn.totalCostCents()).isEqualTo(110_000L);
        assertThat(churn.basis()).contains("30 days").contains("heuristic").contains("§4");

        // The IRA never sold-and-rebought: no manufactured churn.
        var ira = lane.accounts().stream().filter(a -> a.accountId().equals(ids[0])).findFirst().orElseThrow();
        assertThat(ira.churn().pairs()).isEmpty();
    }

    @Test
    void crossAccountSubtotalsAggregateExposureOnlyAndPracticeStaysSideBySide() {
        seedVanguardBook();
        var lane = risk.lane("local", practice.id());

        assertThat(lane.accounts()).hasSize(2);
        var cross = lane.crossAccount();
        assertThat(cross).isNotNull();
        assertThat(cross.accounts()).isEqualTo(2);
        assertThat(cross.greeks().greekCoverage()).isEqualTo(
                "Delta aggregated over 5 of 7 option lots; 2 lack observed marks.");
        assertThat(cross.greeks().betaWeightedDollarDeltaCents()).isCloseTo(-8_450_000L, offset(100L));
        assertThat(cross.stressedObligationCents()).isEqualTo(13_900_000L);
        assertThat(cross.unmarkedObligationCents()).isEqualTo(1_200_000L);
        assertThat(cross.stressNote()).contains("never fungible across accounts");
        var august7 = cross.expiries().rows().stream()
                .filter(r -> r.date().equals("2026-08-07")).findFirst().orElseThrow();
        assertThat(august7.notionalCents()).isEqualTo(31_100_000L + 1_750_000L);
        assertThat(august7.lots()).isEqualTo(5);
        assertThat(august7.flagged()).isTrue();
        assertThat(cross.basis()).contains("Practice never enters these numbers");

        var practiceLane = lane.practice();
        assertThat(practiceLane).isNotNull();
        assertThat(practiceLane.basis()).contains("never numerically netted");
        assertThat(practiceLane.dollarDeltaNetCents()).isZero(); // empty practice book, honestly zero
        assertThat(lane.basis()).contains("from open tracked lots directly");
    }

    @Test
    void practiceLanePublishesOneSynchronizedBookArtifactFromLocalHistory() {
        seedObservedBars();
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService market = new MarketDataService(List.<MarketDataProvider>of(fixture),
                List.<NewsFilingsProvider>of(fixture), List.<RatesProvider>of(fixture),
                new io.liftandshift.strikebench.db.StoredCandleStore(db));
        market.setDemoSources(fixture, fixture, fixture);
        var audit = new AuditLog(db, CLOCK);
        var cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        var positionOwner = new PositionsService(db, MARKS, audit, CLOCK, true);
        var pathOwner = new io.liftandshift.strikebench.sim.PathEnsembleService(market, CLOCK);
        risk = new BookRiskService(db, CLOCK, MARKS, books, objectives, trades,
                positionOwner, pathOwner, new EtfLookThroughService(CLOCK));
        db.exec("INSERT INTO positions(id,account_id,symbol,shares,avg_cost_cents,created_at,updated_at) "
                        + "VALUES('pos-amd',?,'AMD',100,15000,?::timestamptz,?::timestamptz)",
                practice.id(), CLOCK.instant().toString(), CLOCK.instant().toString());
        List<Leg> legs = List.of(Leg.option(io.liftandshift.strikebench.model.LegAction.SELL,
                io.liftandshift.strikebench.model.OptionType.PUT, new BigDecimal("160"),
                LocalDate.parse("2026-08-07"), 1, new BigDecimal("4")));
        db.exec("INSERT INTO trades(id,account_id,symbol,strategy,status,qty,legs_json,thesis,horizon," +
                        "risk_mode,entry_underlying_cents,entry_net_premium_cents,max_loss_cents," +
                        "breakevens_json,entry_snapshot_json,is_live,created_at,updated_at) " +
                        "VALUES('trade-amd',?,'AMD','CASH_SECURED_PUT','ACTIVE',1,?::jsonb,'income'," +
                        "'month','conservative',16000,-40000,1560000,'[]'::jsonb,'{}'::jsonb,0," +
                        "?::timestamptz,?::timestamptz)",
                practice.id(), io.liftandshift.strikebench.util.Json.write(legs),
                CLOCK.instant().toString(), CLOCK.instant().toString());

        var measured = risk.lane("local", practice.id()).practice().measuredBook();

        assertThat(measured.available()).as(measured.unavailableReason()).isTrue();
        assertThat(measured.unavailableReason()).isNull();
        assertThat(measured.correlation().available()).isTrue();
        assertThat(measured.scenario().pathCount()).isEqualTo(600);
        assertThat(measured.scenario().positionCount()).isEqualTo(2); // shares + option package
        assertThat(measured.scenario().stepBands()).isNotEmpty();
        assertThat(measured.scenario().displayPaths()).isNotEmpty();
        assertThat(measured.scenario().notes()).anySatisfy(note ->
                assertThat(note).contains("never summed"));
        assertThat(measured.basis()).contains("aligned realized volatility")
                .contains("no live chain IV or provider history was acquired automatically");
    }

    @Test
    void emptyAndArchivedAccountsDegradeHonestly() {
        var empty = books.createAccount("local", account("Empty", "TAXABLE", 5_000_00L));
        var archived = books.createAccount("local", account("Old", "TAXABLE", null));
        books.setArchived("local", archived.id(), true);

        var lane = risk.lane("local", null);
        assertThat(lane.accounts()).extracting(BookRiskService.AccountRisk::accountId)
                .containsExactly(empty.id());
        var account = lane.accounts().getFirst();
        assertThat(account.greeks().optionLots()).isZero();
        assertThat(account.greeks().greekCoverage()).isEqualTo("No open option lots in this account.");
        assertThat(account.stress().obligationCents()).isZero();
        assertThat(account.expiries().rows()).isEmpty();
        assertThat(account.themes().rows()).isEmpty();
        assertThat(account.churn().pairs()).isEmpty();
        assertThat(lane.crossAccount()).isNull(); // one active account: no subtotal to fabricate
        assertThat(lane.practice()).isNull();
    }

    // ---- helpers (same shapes as PortfolioAccountingServiceTest) ----

    private static PortfolioAccountingService.AccountInput account(String name, String type, Long cash) {
        return new PortfolioAccountingService.AccountInput(name, type, null, "FIFO", null, null, null, null, cash);
    }

    private static PortfolioAccountingService.TransactionInput trade(String date, Long fees,
                                                                     PortfolioAccountingService.LegInput leg) {
        return new PortfolioAccountingService.TransactionInput(date, "TRADE", null, fees, null,
                "MANUAL", null, null, List.of(leg), "EXECUTED");
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
