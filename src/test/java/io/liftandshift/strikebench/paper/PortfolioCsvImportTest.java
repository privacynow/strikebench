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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortfolioCsvImportTest {
    private Db db;
    private PortfolioAccountingService books;
    private String accountId;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        books = new PortfolioAccountingService(db,
                Clock.fixed(Instant.parse("2026-07-13T16:00:00Z"), ZoneOffset.UTC));
        accountId = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Imported", "TAXABLE", null, "FIFO", null, null, null, null, null)).id();
    }

    @AfterEach void close() { if (db != null) db.close(); }

    @Test
    void multiLegAndCashTransactionsImportAtomicallyWithStableReferences() throws Exception {
        String csv = PortfolioCsvImport.TEMPLATE_HEADER + "\r\n"
                + "spread-1,2026-07-01,TRADE,,130,,\"Opening, exact vertical\",0,OPTION,BUY,OPEN,AAPL,CALL,250,2026-08-21,1,100,8.25\r\n"
                + "spread-1,2026-07-01,TRADE,,130,,\"Opening, exact vertical\",1,OPTION,SELL,OPEN,AAPL,CALL,260,2026-08-21,1,100,3.10\r\n"
                + "interest-1,2026-07-02,INTEREST,425,0,ORDINARY_INTEREST,Monthly interest,,,,,,,,,,,\r\n";

        var result = PortfolioCsvImport.run(stream(csv), "local", accountId, books);
        assertThat(result.transactionsWritten()).isEqualTo(2);
        assertThat(result.transactionIds()).hasSize(2);
        assertThat(books.transactions("local", accountId, 0, 20)).hasSize(2);
        assertThat(books.lots("local", accountId, false)).hasSize(2);
        assertThat(books.taxReport("local", accountId, 2026).ordinaryInterestCents()).isEqualTo(425);
        assertThat(books.transactions("local", accountId, 0, 20).stream()
                .filter(t -> "spread-1".equals(t.externalRef())).findFirst().orElseThrow().notes())
                .isEqualTo("Opening, exact vertical");
    }

    @Test
    void oneInvalidCloseRollsBackEveryTransactionInTheFile() {
        String csv = PortfolioCsvImport.TEMPLATE_HEADER + "\n"
                + "interest-2,2026-07-02,INTEREST,500,0,ORDINARY_INTEREST,Would otherwise be valid,,,,,,,,,,,\n"
                + "bad-close,2026-07-03,TRADE,,0,,,0,STOCK,SELL,CLOSE,NVDA,,,,1,1,200\n";

        assertThatThrownBy(() -> PortfolioCsvImport.run(stream(csv), "local", accountId, books))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("only 0 matching long units");
        assertThat(books.transactions("local", accountId, 0, 20)).isEmpty();
        assertThat(books.lots("local", accountId, true)).isEmpty();
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
