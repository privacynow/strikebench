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
        assertThat(csv).contains("cash_effect_cents", "123", "interest-1");
        assertThat(csv).contains("\"'=HYPERLINK(\"\"https://invalid\"\",\"\"click\"\")\"");
        assertThat(csv).doesNotContain(",=HYPERLINK");
    }

    @Test
    void xlsxIsARealOpenXmlWorkbookWithAccountingSheetsAndNoFormulas() throws Exception {
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
                if (entry.getName().endsWith(".xml")) xml.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(names).contains("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml",
                "xl/worksheets/sheet1.xml", "xl/worksheets/sheet6.xml");
        assertThat(xml).contains("Summary", "Transactions", "Performance", "Tax 2026", "Modified Dietz");
        assertThat(xml).doesNotContain("<f>");
    }
}
