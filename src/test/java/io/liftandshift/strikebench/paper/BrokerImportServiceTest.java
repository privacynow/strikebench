package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.DataAge;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.DataProvenance;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.position.PositionArtifactStore;
import io.liftandshift.strikebench.plan.Plan;
import io.liftandshift.strikebench.plan.PlanAdoptionService;
import io.liftandshift.strikebench.plan.PlanService;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrokerImportServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC);
    private Db db;
    private PortfolioAccountingService books;
    private BrokerImportService imports;
    private String accountId;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        books = new PortfolioAccountingService(db, CLOCK);
        accountId = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Tracked IRA", "ROTH_IRA", "Vanguard", "FIFO", null, null, null, null, 50_000_000L)).id();
        imports = service(new ObservedMarks());
    }

    @AfterEach void tearDown() { db.close(); }

    @Test
    void previewIsReadOnlyAndSeparatesSnapshotFromProvenanceSafeObservedBook() throws Exception {
        long before = count("portfolio_transaction");
        var out = preview("ETRADE", resource("etrade-v1.csv"));

        assertThat(out.marks()).singleElement().satisfies(group -> {
            assertThat(group.groupKey()).startsWith("grp-");
            assertThat(group.legs()).singleElement().satisfies(mark -> {
                assertThat(mark.pastedMark()).isEqualByComparingTo("2.10");
                assertThat(mark.pastedMarkIsCurrent()).isFalse();
                assertThat(mark.currentBid()).isEqualByComparingTo("2.90");
                assertThat(mark.currentAsk()).isEqualByComparingTo("3.10");
                assertThat(mark.currentMid()).isEqualByComparingTo("3.00");
                assertThat(mark.observedEligible()).isTrue();
                assertThat(mark.provenance()).isEqualTo("OBSERVED");
            });
        });
        assertThat(count("portfolio_transaction")).isEqualTo(before);
        assertThat(count("portfolio_import_pending")).isZero();

        var unsafe = service(new ModeledMarks()).preview(new BrokerImportService.PreviewRequest(
                BrokerStatementParser.VERSION, "ETRADE", null, resource("etrade-v1.csv")));
        assertThat(unsafe.marks().getFirst().legs().getFirst()).satisfies(mark -> {
            assertThat(mark.currentMid()).isEqualByComparingTo("3.00");
            assertThat(mark.observedEligible()).isFalse();
            assertThat(mark.note()).contains("display-only");
        });
    }

    @Test
    void inferredFieldsRequireCorrectionOrExplicitAcknowledgement() {
        String text = "order_id,account,date,symbol,action,quantity,price,net_amount,leg\n"
                + "infer,IRA 1,2026-07-01,MU,buy,1,100,-100,0\n";
        var preview = preview("ETRADE", text);

        assertThatThrownBy(() -> imports.confirm("local", request(preview, text, accountId, false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inferred")
                .hasMessageContaining("acknowledge");
        var confirmed = imports.confirm("local", request(preview, text, accountId, true));
        assertThat(confirmed.exactTransactions()).isEqualTo(1);
    }

    @Test
    void wholePortfolioSameReferenceIsPartitionedBySourceAccountAndMappedExplicitly() {
        String text = "order_id,account,date,symbol,type,action,position,quantity,price,net_amount,leg\n"
                + "same,IRA 1111,2026-07-01,MU,stock,buy,open,1,100,-100,0\n"
                + "same,IRA 2222,2026-07-01,MU,stock,buy,open,1,100,-100,0\n";
        String second = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Second IRA", "ROTH_IRA", "Vanguard", "FIFO", null, null, null, null, 1_000_000L)).id();
        var preview = preview("ETRADE", text);
        assertThat(preview.parsed().groups()).hasSize(2)
                .extracting(BrokerStatementParser.Group::externalRef).containsOnly("same");

        assertThatThrownBy(() -> imports.confirm("local", request(preview, text, Map.of(), true)))
                .hasMessageContaining("map source account");
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put(preview.parsed().groups().get(0).accountFingerprint(), accountId);
        mapping.put(preview.parsed().groups().get(1).accountFingerprint(), second);
        var out = imports.confirm("local", request(preview, text, mapping, true));
        assertThat(out.items()).extracting(BrokerImportService.ConfirmedItem::portfolioAccountId)
                .containsExactlyInAnyOrder(accountId, second);
    }

    @Test
    void exactImportIsIdempotentButSameKeyChangedPayloadIsAConflict() {
        String text = "order_id,account,date,symbol,type,action,position,quantity,price,net_amount,leg\n"
                + "identity,IRA 1,2026-07-01,MU,stock,buy,open,1,100,-100,0\n";
        var preview = preview("ETRADE", text);
        var first = imports.confirm("local", request(preview, text, accountId, true));
        var replay = imports.confirm("local", request(preview, text, accountId, true));
        assertThat(first.exactTransactions()).isEqualTo(1);
        assertThat(replay.duplicates()).isEqualTo(1);

        String changed = text.replace(",100,-100,", ",101,-101,");
        var changedPreview = preview("ETRADE", changed);
        assertThatThrownBy(() -> imports.confirm("local", request(changedPreview, changed, accountId, true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("different package facts")
                .hasMessageContaining("not a duplicate");
        assertThat(db.query("SELECT COUNT(*) n FROM portfolio_transaction WHERE source='IMPORT'",
                r -> r.lng("n"))).containsExactly(1L);
    }

    @Test
    void userAllocationIsReceiptOnlyThenBrokerAttestationCreatesCanonicalLotsAndHistory() {
        String text = mixedAuthorityPending("pending-attest", "IRA 1");
        String pendingId = imports.confirm("local", request(preview("VANGUARD", text), text,
                accountId, true)).items().getFirst().id();
        long transactionsBefore = count("portfolio_transaction");
        List<BrokerImportService.ResolutionLeg> fills = resolutionFills();

        var provisional = imports.resolve("local", pendingId,
                new BrokerImportService.ResolveRequest(accountId, "USER_ALLOCATED", fills));
        assertThat(provisional.taxTruth()).isEqualTo("PROVISIONAL");
        assertThat(provisional.transaction()).isNull();
        assertThat(provisional.adoptionItem()).isNull();
        assertThat(provisional.pending().status()).isEqualTo("PROVISIONAL");
        assertThat(count("portfolio_transaction")).isEqualTo(transactionsBefore);
        assertThat(count("portfolio_lot")).isZero();
        assertThat(count("portfolio_lot_match")).isZero();
        assertThat(count("portfolio_wash_sale_allocation")).isZero();
        assertThat(db.query("SELECT transaction_id FROM portfolio_import_resolution WHERE pending_id=?",
                r -> r.str("transaction_id"), pendingId)).containsExactly((String) null);
        assertThat(db.query("SELECT reported_price_authority v FROM portfolio_import_pending_leg "
                        + "WHERE pending_id=? ORDER BY leg_no", r -> r.str("v"), pendingId))
                .containsExactly("BROKER_REPORTED", "USER_ALLOCATED");

        var attested = imports.resolve("local", pendingId,
                new BrokerImportService.ResolveRequest(accountId, "BROKER_REPORTED", fills));
        assertThat(attested.pending().status()).isEqualTo("RESOLVED");
        assertThat(attested.transaction()).isNotNull();
        assertThat(attested.adoptionItem().lots()).hasSize(2);
        assertThat(attested.pending().resolutionHistory()).extracting(BrokerImportService.ResolutionView::authority)
                .containsExactly("USER_ALLOCATED", "BROKER_REPORTED");
        assertThat(count("portfolio_lot")).isEqualTo(2);
        assertThat(db.query("SELECT COUNT(*) n FROM portfolio_import_resolution WHERE pending_id=?",
                r -> r.lng("n"), pendingId)).containsExactly(2L);

        var adoptions = new PlanAdoptionService(db, CLOCK, new PlanService(db, CLOCK),
                new PositionArtifactStore(db));
        var adopted = adoptions.adoptBatch("local", Plan.MarketKind.OBSERVED, null,
                new PlanAdoptionService.BatchRequest(List.of(new PlanAdoptionService.BatchItem(
                        "ADOPT", "pending-package-" + pendingId, accountId, "MU",
                        "MU imported broker spread", attested.adoptionItem().lots().stream()
                        .map(lot -> new PlanAdoptionService.Allocation(lot.lotId(), lot.quantity())).toList(), null))));
        assertThat(adopted.adopted()).isEqualTo(1);
        assertThat(db.query("SELECT COUNT(*) n FROM portfolio_structure_member",
                r -> r.lng("n"))).containsExactly(2L);
        assertThat(adopted.items().getFirst().plan().furthestStage()).isEqualTo(Plan.Stage.MANAGE_REVIEW);
        assertThat(db.query("SELECT l.price_authority || ':' || trim(to_char(l.fill_price,'FM999990.000')) v "
                        + "FROM position_receipt_leg l JOIN position_receipt r ON r.id=l.receipt_id "
                        + "WHERE r.kind='ADOPTION' ORDER BY l.leg_no", row -> row.str("v")))
                .containsExactly("BROKER_REPORTED:4.225", "BROKER_REPORTED:2.000");
    }

    @Test
    void onlyAttestedFactsCanPropagateIntoWashSaleBasis() {
        String taxable = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Taxable", "TAXABLE", "Vanguard", "FIFO", 3000, 1500, 3000, 500, 100_000L)).id();
        String text = "confirmation_number,account,date,symbol,type,action,position,quantity,multiplier,price,net_amount,fees,leg\n"
                + "wash-open,Tax 99,2025-07-01,MU,stock,buy,open,1,1,,-100,0,0\n";
        String pendingId = imports.confirm("local", request(preview("VANGUARD", text), text,
                taxable, true)).items().getFirst().id();
        var fill = List.of(new BrokerImportService.ResolutionLeg(0, new BigDecimal("100")));
        imports.resolve("local", pendingId, new BrokerImportService.ResolveRequest(taxable, "USER_ALLOCATED", fill));
        assertThat(count("portfolio_lot")).isZero();
        assertThat(count("portfolio_wash_sale_allocation")).isZero();

        imports.resolve("local", pendingId, new BrokerImportService.ResolveRequest(taxable, "BROKER_REPORTED", fill));
        books.record("local", taxable, trade("2025-07-02T15:00:00Z", "wash-loss", "SELL", "CLOSE", "90"));
        books.record("local", taxable, trade("2025-07-03T15:00:00Z", "wash-replace", "BUY", "OPEN", "95"));
        assertThat(count("portfolio_wash_sale_allocation")).isEqualTo(1);
        assertThat(books.taxReport("local", taxable, 2025).realizedLots()).singleElement()
                .satisfies(match -> assertThat(match.washSaleAdjustmentCents()).isEqualTo(1_000L));
    }

    @Test
    void archivedDestinationCannotReceiveAQuarantinedPackage() {
        String text = pendingStocks("archived-destination");
        var preview = preview("VANGUARD", text);
        books.setArchived("local", accountId, true);

        assertThatThrownBy(() -> imports.confirm("local", request(preview, text, accountId, true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("active tracked account");
        assertThat(count("portfolio_import_pending")).isZero();
        assertThat(count("portfolio_transaction")).isEqualTo(1); // opening cash only
    }

    @Test
    void openQueueIsBoundedAndRejectedPackagesCanBeReopened() {
        String text = pendingStocks("queue-a", "queue-b", "queue-c");
        var confirmed = imports.confirm("local", request(preview("VANGUARD", text), text,
                accountId, true));
        assertThat(confirmed.pendingImports()).isEqualTo(3);

        var first = imports.list("local", "OPEN", 1, 0, accountId);
        assertThat(first.imports()).hasSize(1);
        assertThat(first.pendingCount()).isEqualTo(3);
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.hasMore()).isTrue();
        assertThat(imports.list("local", "OPEN", 1, 1, accountId).imports()).hasSize(1);

        String pendingId = first.imports().getFirst().id();
        var rejected = imports.command("local", pendingId,
                new BrokerImportService.CommandRequest("REJECT", null, null, List.of()));
        assertThat(rejected.pending().status()).isEqualTo("REJECTED");
        assertThat(imports.list("local", "OPEN", 100, 0, accountId).pendingCount()).isEqualTo(2);
        assertThat(imports.list("local", "REJECTED", 100, 0, accountId).imports())
                .singleElement().extracting(BrokerImportService.PendingView::id).isEqualTo(pendingId);

        var reopened = imports.command("local", pendingId,
                new BrokerImportService.CommandRequest("REOPEN", null, null, List.of()));
        assertThat(reopened.pending().status()).isEqualTo("PENDING");
        assertThat(imports.list("local", null, 100, 0, accountId).pendingCount()).isEqualTo(3);
    }

    @Test
    void rejectedProvisionalPackageReopensWithItsAllocationHistory() {
        String text = mixedAuthorityPending("provisional-reopen", "IRA 1");
        String pendingId = imports.confirm("local", request(preview("VANGUARD", text), text,
                accountId, true)).items().getFirst().id();
        imports.command("local", pendingId, new BrokerImportService.CommandRequest("RESOLVE",
                accountId, "USER_ALLOCATED", resolutionFills()));
        imports.command("local", pendingId,
                new BrokerImportService.CommandRequest("REJECT", null, null, List.of()));

        var reopened = imports.command("local", pendingId,
                new BrokerImportService.CommandRequest("REOPEN", null, null, List.of()));
        assertThat(reopened.pending().status()).isEqualTo("PROVISIONAL");
        assertThat(reopened.pending().resolutionHistory())
                .singleElement().extracting(BrokerImportService.ResolutionView::authority)
                .isEqualTo("USER_ALLOCATED");
    }

    @Test
    void resolutionMismatchRollsBackEveryArtifact() throws Exception {
        String text = resource("vanguard-v1.csv");
        String pendingId = imports.confirm("local", request(preview("VANGUARD", text), text,
                accountId, true)).items().getFirst().id();
        long before = count("portfolio_transaction");
        assertThatThrownBy(() -> imports.resolve("local", pendingId,
                new BrokerImportService.ResolveRequest(accountId, "BROKER_REPORTED", List.of(
                        new BrokerImportService.ResolutionLeg(0, new BigDecimal("4.22")),
                        new BrokerImportService.ResolutionLeg(1, new BigDecimal("2.00"))))))
                .hasMessageContaining("exact-cent equality");
        assertThat(count("portfolio_transaction")).isEqualTo(before);
        assertThat(count("position_receipt")).isZero();
        assertThat(imports.get("local", pendingId).status()).isEqualTo("PENDING");
    }

    private BrokerImportService service(MarksSource source) {
        return new BrokerImportService(db, CLOCK, books, source, new PositionArtifactStore(db),
                new CampaignService(db, CLOCK));
    }

    private BrokerImportService.Preview preview(String source, String text) {
        return imports.preview(new BrokerImportService.PreviewRequest(
                BrokerStatementParser.VERSION, source, null, text));
    }

    private static BrokerImportService.ConfirmRequest request(BrokerImportService.Preview preview, String text,
                                                              String account, boolean acknowledge) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (var group : preview.parsed().groups()) mapping.put(group.accountFingerprint(), account);
        return request(preview, text, mapping, acknowledge);
    }

    private static BrokerImportService.ConfirmRequest request(BrokerImportService.Preview preview, String text,
                                                              Map<String, String> mapping, boolean acknowledge) {
        List<BrokerImportService.VerifiedGroup> verified = preview.parsed().groups().stream().map(group ->
                new BrokerImportService.VerifiedGroup(group.groupKey(), group.legs().stream().map(leg ->
                        new BrokerStatementParser.VerifiedLeg(leg.legNo(), leg.instrumentType(), leg.action(),
                                leg.positionEffect(), leg.symbol(), leg.optionType(), leg.strike(), leg.expiration(),
                                leg.quantity(), leg.multiplier(), acknowledge)).toList())).toList();
        return new BrokerImportService.ConfirmRequest(BrokerStatementParser.VERSION,
                preview.parsed().previewFingerprint(), preview.parsed().sourceSystem(), null, text,
                mapping, verified, null);
    }

    private static String mixedAuthorityPending(String ref, String account) {
        return "confirmation_number,account,date,symbol,type,action,position,option_type,strike,expiration,quantity,multiplier,price,net_amount,fees,leg\n"
                + ref + "," + account + ",2026-07-02,MU,option,sell,open,put,98,2026-08-07,10,100,4.225,2212,13,0\n"
                + ref + "," + account + ",2026-07-02,MU,option,buy,open,put,95,2026-08-07,10,100,,2212,13,1\n";
    }

    private static String pendingStocks(String... refs) {
        StringBuilder text = new StringBuilder(
                "confirmation_number,account,date,symbol,type,action,position,quantity,multiplier,price,net_amount,fees,leg\n");
        for (int i = 0; i < refs.length; i++) {
            text.append(refs[i]).append(",IRA 1,2026-07-0").append(i + 1)
                    .append(",MU,stock,buy,open,1,1,,-100,0,0\n");
        }
        return text.toString();
    }

    private static List<BrokerImportService.ResolutionLeg> resolutionFills() {
        return List.of(new BrokerImportService.ResolutionLeg(0, new BigDecimal("4.225")),
                new BrokerImportService.ResolutionLeg(1, new BigDecimal("2.00")));
    }

    private static PortfolioAccountingService.TransactionInput trade(String occurredAt, String ref,
                                                                      String action, String effect, String price) {
        return new PortfolioAccountingService.TransactionInput(occurredAt, "TRADE", null, 0L, null,
                "BROKER", ref, null, List.of(new PortfolioAccountingService.LegInput("STOCK", action,
                effect, "MU", null, null, null, 1L, 1, new BigDecimal(price), null)), "EXECUTED");
    }

    private long count(String table) { return db.query("SELECT COUNT(*) n FROM " + table, r -> r.lng("n")).getFirst(); }

    private static String resource(String name) throws IOException {
        try (var stream = BrokerImportServiceTest.class.getResourceAsStream("/broker-import/" + name)) {
            if (stream == null) throw new IOException("missing fixture " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static class ObservedMarks implements MarksSource {
        @Override public Optional<BigDecimal> underlyingMark(String symbol) {
            return Optional.of(new BigDecimal("101.00"));
        }
        @Override public Optional<DataEvidence> underlyingEvidence(String symbol, String worldId) {
            return Optional.of(new DataEvidence(DataProvenance.OBSERVED, DataAge.DELAYED, "test observed feed"));
        }
        @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
            return Optional.of(new LegMark(new BigDecimal("2.90"), new BigDecimal("3.10"),
                    new BigDecimal("3.00"), .25, Freshness.DELAYED, null, null, null, null,
                    new DataEvidence(DataProvenance.OBSERVED, DataAge.DELAYED, "test observed chain")));
        }
    }

    private static final class ModeledMarks extends ObservedMarks {
        @Override public Optional<LegMark> legMark(String symbol, Leg leg) {
            return Optional.of(new LegMark(new BigDecimal("2.90"), new BigDecimal("3.10"),
                    new BigDecimal("3.00"), .25, Freshness.MODELED));
        }
    }
}
