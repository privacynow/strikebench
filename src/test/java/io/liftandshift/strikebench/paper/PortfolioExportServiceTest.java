package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioExportServiceTest {
    private Db db;
    private PortfolioAccountingService books;
    private PortfolioExportService exports;
    private PortfolioAccountingService.AccountProfile account;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        books = new PortfolioAccountingService(db,
                Clock.fixed(Instant.parse("2026-07-13T16:00:00Z"), ZoneOffset.UTC));
        exports = new PortfolioExportService(books);
        account = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Taxable book", "TAXABLE", "Example broker", "FIFO", 3000, 1500, 2400, 400, 1_000_000L));
    }

    @AfterEach void close() { if (db != null) db.close(); }

    @Test
    void csvKeepsExactCentsAndNeutralizesSpreadsheetFormulas() {
        books.record("local", account.id(), new PortfolioAccountingService.TransactionInput(
                "2026-07-10", "INTEREST", 1_23L, 0L, null, "MANUAL", "interest-1",
                "=HYPERLINK(\"https://invalid\",\"click\")", List.of()));

        String csv = new String(exports.transactionsCsv("local", account.id()), StandardCharsets.UTF_8);
        assertThat(csv).contains("cash_effect_cents", "section_1256", "123", "interest-1");
        assertThat(csv).contains("\"'=HYPERLINK(\"\"https://invalid\"\",\"\"click\"\")\"");
        assertThat(csv).doesNotContain(",=HYPERLINK");
    }

    @Test
    void multiLegExportEmitsTransactionCashAndFeesOnce() {
        books.record("local", account.id(), new PortfolioAccountingService.TransactionInput(
                "2026-07-10", "TRADE", null, 1_30L, null, "MANUAL", "spread-1", null,
                List.of(option("BUY", "OPEN", "250", "8.25"), option("SELL", "OPEN", "260", "3.10"))));

        String csv = new String(exports.transactionsCsv("local", account.id()), StandardCharsets.UTF_8);
        String[] lines = csv.split("\\r?\\n");
        String[] header = lines[0].replace("\"", "").split(",", -1);
        int primary = java.util.Arrays.asList(header).indexOf("primary_transaction_row");
        int cash = java.util.Arrays.asList(header).indexOf("cash_effect_cents");
        int fees = java.util.Arrays.asList(header).indexOf("fees_cents");
        List<String[]> spreadRows = java.util.Arrays.stream(lines)
                .filter(line -> line.contains("\"spread-1\""))
                .map(line -> line.replace("\"", "").split(",", -1)).toList();
        assertThat(spreadRows).hasSize(2);
        assertThat(spreadRows).extracting(row -> row[primary]).containsExactly("true", "false");
        assertThat(spreadRows).extracting(row -> row[cash]).containsExactly("-51630", "");
        assertThat(spreadRows).extracting(row -> row[fees]).containsExactly("130", "");
    }

    @Test
    void xlsxIsARealOpenXmlWorkbookWithAccountingSheetsAndNoFormulas() throws Exception {
        books.record("local", account.id(), new PortfolioAccountingService.TransactionInput(
                "2026-01-01", "INTEREST", 1_00L, 0L, null, "MANUAL", "xml-control",
                "kept\u0001 removed", List.of()));
        books.addValuation("local", account.id(), new PortfolioAccountingService.ValuationInput(
                "2026-01-01", 1_000_000L, 0L, 1_000_000L, "MANUAL", null, "start"));
        books.addValuation("local", account.id(), new PortfolioAccountingService.ValuationInput(
                "2026-07-13", 1_010_000L, 0L, 1_010_000L, "MANUAL", null, "finish"));

        byte[] xlsx = exports.workbook("local", account.id(), 2026);
        assertThat(xlsx.length).isGreaterThan(2_000);
        List<String> names = new ArrayList<>();
        StringBuilder xml = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
                if (entry.getName().endsWith(".xml")) {
                    byte[] body = zip.readAllBytes();
                    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                            .parse(new ByteArrayInputStream(body));
                    xml.append(new String(body, StandardCharsets.UTF_8));
                }
            }
        }
        assertThat(names).contains("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml",
                "xl/worksheets/sheet1.xml", "xl/worksheets/sheet6.xml");
        assertThat(xml).contains("Summary", "Transactions", "Performance", "Tax 2026", "Modified Dietz",
                "Time-weighted return", "Money-weighted return (XIRR)", "Maximum drawdown", "SPY benchmark return",
                "Primary transaction row", "Not tax advice");
        assertThat(xml).contains("kept removed").doesNotContain("\u0001");
        assertThat(xml).doesNotContain("<f>");
    }

    @Test
    void exportsSection1256ClassificationAndSixtyFortyTaxCharacter() throws Exception {
        books.record("local", account.id(), new PortfolioAccountingService.TransactionInput(
                "2025-12-15T15:00:00-05:00", "TRADE", null, 0L, null, "MANUAL", "spx-open", null,
                List.of(new PortfolioAccountingService.LegInput("OPTION", "BUY", "OPEN", "SPX", "CALL",
                        new java.math.BigDecimal("6000"), java.time.LocalDate.parse("2026-03-20"), 1L, 100,
                        new java.math.BigDecimal("10.00"), null))));
        db.exec("INSERT INTO option_bar(symbol,asof,expiration,strike,opt_type,bid,ask,mark,source,bid_ask_observed,dataset_id) "
                        + "VALUES ('SPX','2025-12-31','2026-03-20',6000,'CALL',11.99,12.01,12.00,'vendor',1,'observed')");
        books.markSection1256YearEnd("local", account.id(), 2025);

        String csv = new String(exports.transactionsCsv("local", account.id()), StandardCharsets.UTF_8);
        assertThat(csv).contains("section_1256", "\"true\"", "MARK_TO_MARKET");

        String xml = workbookXml(exports.workbook("local", account.id(), 2025));
        assertThat(xml).contains("Section 1256", "Section 1256 gain", "Section 1256 short-term 40%",
                "Section 1256 long-term 60%", ">80.00<", ">120.00<");
    }

    private static String workbookXml(byte[] workbook) throws Exception {
        StringBuilder xml = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(workbook), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().endsWith(".xml")) xml.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return xml.toString();
    }

    private static PortfolioAccountingService.LegInput option(String action, String effect, String strike, String price) {
        return new PortfolioAccountingService.LegInput("OPTION", action, effect, "AAPL", "CALL",
                new java.math.BigDecimal(strike), java.time.LocalDate.parse("2026-08-21"), 1L, 100,
                new java.math.BigDecimal(price));
    }
}
