package io.liftandshift.strikebench.paper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exact, owner-scoped exports for the tracked portfolio book. */
public final class PortfolioExportService {
    private final PortfolioAccountingService books;

    public PortfolioExportService(PortfolioAccountingService books) {
        this.books = books;
    }

    public byte[] transactionsCsv(String ownerId, String accountId) {
        var account = books.account(ownerId, accountId);
        StringBuilder out = new StringBuilder();
        row(out, "account", "transaction_id", "primary_transaction_row", "occurred_at", "event_type", "source", "external_ref",
                "cash_effect_cents", "fees_cents", "tax_category", "leg_no", "instrument", "action",
                "position_effect", "symbol", "option_type", "strike", "expiration", "quantity",
                "multiplier", "price", "gross_amount_cents", "allocated_fee_cents", "notes");
        for (var tx : allTransactions(ownerId, accountId)) {
            if (tx.legs().isEmpty()) {
                row(out, text(account.name()), text(tx.id()), text("true"), text(tx.occurredAt()), text(tx.eventType()), text(tx.source()),
                        text(tx.externalRef()), number(tx.cashEffectCents()), number(tx.feesCents()), text(tx.taxCategory()),
                        "", "", "", "", "", "", "", "", "", "", "", "", text(tx.notes()));
            } else for (int i = 0; i < tx.legs().size(); i++) {
                var leg = tx.legs().get(i);
                row(out, text(account.name()), text(tx.id()), text(i == 0 ? "true" : "false"), text(tx.occurredAt()), text(tx.eventType()), text(tx.source()),
                        text(tx.externalRef()), i == 0 ? number(tx.cashEffectCents()) : "", i == 0 ? number(tx.feesCents()) : "", text(tx.taxCategory()),
                        number(leg.legNo()), text(leg.instrumentType()), text(leg.action()), text(leg.positionEffect()),
                        text(leg.symbol()), text(leg.optionType()), decimal(leg.strike()), text(leg.expiration()),
                        number(leg.quantity()), number(leg.multiplier()), decimal(leg.price()),
                        number(leg.grossAmountCents()), number(leg.allocatedFeeCents()), text(tx.notes()));
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] workbook(String ownerId, String accountId, int year) {
        var summary = books.summary(ownerId, accountId);
        var performance = books.performance(ownerId, accountId);
        var tax = books.taxReport(ownerId, accountId, year);
        var txs = allTransactions(ownerId, accountId);
        var lots = books.lots(ownerId, accountId, true);
        List<Sheet> sheets = List.of(
                new Sheet("Summary", summaryRows(summary)),
                new Sheet("Transactions", transactionRows(txs)),
                new Sheet("Lots", lotRows(lots)),
                new Sheet("Realized " + year, realizedRows(tax.realizedLots())),
                new Sheet("Performance", performanceRows(performance)),
                new Sheet("Tax " + year, taxRows(tax)));
        try {
            return writeWorkbook(sheets);
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the Excel workbook", e);
        }
    }

    private List<PortfolioAccountingService.TransactionView> allTransactions(String owner, String account) {
        List<PortfolioAccountingService.TransactionView> out = new ArrayList<>();
        for (int page = 0; page < 20_000; page++) {
            var rows = books.transactions(owner, account, page, 500);
            out.addAll(rows);
            if (rows.size() < 500) break;
        }
        return out;
    }

    private static List<List<Cell>> summaryRows(PortfolioAccountingService.PortfolioSummary s) {
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(cells("Metric", "Value"));
        rows.add(cells("Account", s.account().name()));
        rows.add(cells("Account type", s.account().accountType()));
        rows.add(cells("Broker", s.account().broker()));
        rows.add(moneyRow("Book cash", s.bookCashCents()));
        rows.add(moneyRow("Securities liquidation value", s.securitiesLiquidationValueCents()));
        rows.add(moneyRow("Total value", s.totalValueCents()));
        rows.add(moneyRow("Realized P/L", s.realizedPnlCents()));
        rows.add(moneyRow("Unrealized P/L", s.unrealizedPnlCents()));
        rows.add(moneyRow("Interest income", s.interestIncomeCents()));
        rows.add(moneyRow("Dividend income", s.dividendIncomeCents()));
        rows.add(moneyRow("Fees recorded", s.feesCents()));
        rows.add(moneyRow("Known cash blocked", s.collateral().knownBlockedCashCents()));
        rows.add(moneyRow("Available cash estimate", s.collateral().availableCashCents()));
        rows.add(cells("Complete current valuation", String.valueOf(s.complete())));
        rows.add(cells("Valuation basis", s.valuationBasis()));
        if (!s.missingMarks().isEmpty()) rows.add(cells("Missing marks", String.join(", ", s.missingMarks())));
        for (String note : s.collateral().notes()) rows.add(cells("Collateral note", note));
        return rows;
    }

    private static List<List<Cell>> transactionRows(List<PortfolioAccountingService.TransactionView> txs) {
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(cells("Occurred", "Event", "Transaction", "Primary transaction row", "Source", "Reference", "Cash effect", "Fees",
                "Leg", "Instrument", "Action", "Effect", "Symbol", "Type", "Strike", "Expiration",
                "Quantity", "Multiplier", "Price", "Gross", "Allocated fee", "Notes"));
        for (var tx : txs) {
            if (tx.legs().isEmpty()) rows.add(List.of(s(tx.occurredAt()), s(tx.eventType()), s(tx.id()), s("true"), s(tx.source()),
                    s(tx.externalRef()), money(tx.cashEffectCents()), money(tx.feesCents()), blank(), blank(), blank(),
                    blank(), blank(), blank(), blank(), blank(), blank(), blank(), blank(), blank(), blank(), s(tx.notes())));
            else for (int i = 0; i < tx.legs().size(); i++) {
                var leg = tx.legs().get(i);
                rows.add(List.of(s(tx.occurredAt()), s(tx.eventType()), s(tx.id()), s(i == 0 ? "true" : "false"), s(tx.source()),
                    s(tx.externalRef()), i == 0 ? money(tx.cashEffectCents()) : blank(), i == 0 ? money(tx.feesCents()) : blank(), n(leg.legNo()),
                    s(leg.instrumentType()), s(leg.action()), s(leg.positionEffect()), s(leg.symbol()), s(leg.optionType()),
                    decimalCell(leg.strike()), s(leg.expiration()), n(leg.quantity()), n(leg.multiplier()),
                    decimalCell(leg.price()), money(leg.grossAmountCents()), money(leg.allocatedFeeCents()), s(tx.notes())));
            }
        }
        return rows;
    }

    private static List<List<Cell>> lotRows(List<PortfolioAccountingService.LotView> lots) {
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(cells("Opened", "Status", "Instrument", "Side", "Symbol", "Type", "Strike", "Expiration",
                "Multiplier", "Original quantity", "Remaining quantity", "Original basis/proceeds", "Remaining basis/proceeds"));
        for (var lot : lots) rows.add(List.of(s(lot.openedAt()), s(lot.status()), s(lot.instrumentType()), s(lot.side()),
                s(lot.symbol()), s(lot.optionType()), decimalCell(lot.strike()), s(lot.expiration()), n(lot.multiplier()),
                n(lot.originalQuantity()), n(lot.remainingQuantity()), money(lot.originalOpenAmountCents()),
                money(lot.remainingOpenAmountCents())));
        return rows;
    }

    private static List<List<Cell>> realizedRows(List<PortfolioAccountingService.RealizedLotView> realized) {
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(cells("Opened", "Closed", "Instrument", "Side", "Symbol", "Quantity", "Open basis/proceeds",
                "Close proceeds/cost", "Realized gain", "Holding term", "Wash-sale adjustment"));
        for (var r : realized) rows.add(List.of(s(r.openedAt()), s(r.closedAt()), s(r.instrumentType()), s(r.side()),
                s(r.symbol()), n(r.quantity()), money(r.openAmountCents()), money(r.closeAmountCents()),
                money(r.realizedGainCents()), s(r.holdingTerm()), money(r.washSaleAdjustmentCents())));
        return rows;
    }

    private static List<List<Cell>> performanceRows(PortfolioAccountingService.PerformanceView p) {
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(cells("As of", "Cash", "Securities", "Total", "Source", "Reference", "Notes"));
        for (var v : p.valuations()) rows.add(List.of(s(v.asOf()), money(v.cashCents()), money(v.securitiesValueCents()),
                money(v.totalValueCents()), s(v.source()), s(v.externalRef()), s(v.notes())));
        rows.add(List.of());
        rows.add(moneyRow("Investment gain", p.investmentGainCents()));
        rows.add(moneyRow("Net external flows", p.netExternalFlowCents()));
        rows.add(cells("Modified Dietz return", p.modifiedDietzReturn() == null ? "Unavailable" : percent(p.modifiedDietzReturn())));
        rows.add(cells("Annualized return", p.annualizedReturn() == null ? "Unavailable" : percent(p.annualizedReturn())));
        rows.add(cells("Method note", p.note()));
        return rows;
    }

    private static List<List<Cell>> taxRows(PortfolioAccountingService.TaxReport t) {
        return List.of(cells("Tax field", "Value"), cells("Tax year", String.valueOf(t.year())),
                cells("Account type", t.accountType()), moneyRow("Short-term gain", t.shortTermGainCents()),
                moneyRow("Long-term gain", t.longTermGainCents()), moneyRow("Ordinary interest", t.ordinaryInterestCents()),
                moneyRow("Ordinary dividends", t.ordinaryDividendCents()), moneyRow("Qualified dividends", t.qualifiedDividendCents()),
                moneyRow("Capital-gain distributions", t.capitalGainDistributionCents()),
                moneyRow("Estimated federal tax", t.estimatedFederalTaxCents()),
                moneyRow("Estimated state tax", t.estimatedStateTaxCents()),
                moneyRow("Estimated total tax", t.estimatedTotalTaxCents()), cells("Limitations", t.note()));
    }

    private record Sheet(String name, List<List<Cell>> rows) {}
    private record Cell(String value, boolean number, int style) {}
    private static Cell s(Object value) { return new Cell(value == null ? "" : String.valueOf(value), false, 0); }
    private static Cell blank() { return s(""); }
    private static Cell n(Number value) { return value == null ? blank() : new Cell(String.valueOf(value), true, 0); }
    private static Cell money(Long cents) { return cents == null ? blank() : new Cell(BigDecimal.valueOf(cents).movePointLeft(2).toPlainString(), true, 2); }
    private static Cell decimalCell(BigDecimal value) { return value == null ? blank() : new Cell(value.toPlainString(), true, 0); }
    private static List<Cell> cells(Object... values) { return java.util.Arrays.stream(values).map(PortfolioExportService::s).toList(); }
    private static List<Cell> moneyRow(String label, Long cents) { return List.of(s(label), money(cents)); }
    private static String percent(double value) { return String.format(Locale.ROOT, "%.2f%%", value * 100); }

    private static byte[] writeWorkbook(List<Sheet> sheets) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypes(sheets.size()));
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(zip, "xl/workbook.xml", workbookXml(sheets));
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size()));
            put(zip, "xl/styles.xml", stylesXml());
            for (int i = 0; i < sheets.size(); i++) put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets.get(i)));
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String body) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes(int count) {
        StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= count; i++) s.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        return s.append("</Types>").toString();
    }

    private static String workbookXml(List<Sheet> sheets) {
        StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        for (int i = 0; i < sheets.size(); i++) s.append("<sheet name=\"").append(xml(sheets.get(i).name())).append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        return s.append("</sheets></workbook>").toString();
    }

    private static String workbookRels(int count) {
        StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= count; i++) s.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        return s.append("<Relationship Id=\"rId").append(count + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>").toString();
    }

    private static String stylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"&quot;$&quot;#,##0.00;[Red]-&quot;$&quot;#,##0.00\"/></numFmts><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Aptos\"/></font><font><b/><color rgb=\"FFFFFFFF\"/><sz val=\"11\"/><name val=\"Aptos\"/></font></fonts><fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF2563EB\"/><bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/><xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";
    }

    private static String sheetXml(Sheet sheet) {
        StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"1\" max=\"30\" width=\"18\" customWidth=\"1\"/></cols><sheetData>");
        for (int r = 0; r < sheet.rows().size(); r++) {
            s.append("<row r=\"").append(r + 1).append("\">");
            List<Cell> row = sheet.rows().get(r);
            for (int c = 0; c < row.size(); c++) {
                Cell cell = row.get(c);
                int style = r == 0 ? 1 : cell.style();
                String ref = column(c + 1) + (r + 1);
                if (cell.number() && !cell.value().isBlank()) s.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"><v>").append(xml(cell.value())).append("</v></c>");
                else s.append("<c r=\"").append(ref).append("\" t=\"inlineStr\" s=\"").append(style).append("\"><is><t xml:space=\"preserve\">").append(xml(cell.value())).append("</t></is></c>");
            }
            s.append("</row>");
        }
        return s.append("</sheetData><autoFilter ref=\"A1:").append(column(Math.max(1, sheet.rows().isEmpty() ? 1 : sheet.rows().getFirst().size()))).append("1\"/></worksheet>").toString();
    }

    private static String column(int n) {
        StringBuilder s = new StringBuilder();
        while (n > 0) { n--; s.append((char) ('A' + (n % 26))); n /= 26; }
        return s.reverse().toString();
    }

    private static String xml(String raw) {
        return safeText(raw).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String safeText(String raw) {
        if (raw == null) return "";
        StringBuilder cleaned = new StringBuilder(raw.length());
        raw.codePoints().filter(PortfolioExportService::validXmlCodePoint).forEach(cleaned::appendCodePoint);
        String value = cleaned.toString();
        if (!value.isEmpty() && (value.charAt(0) == '=' || value.charAt(0) == '+' || value.charAt(0) == '-'
                || value.charAt(0) == '@' || value.charAt(0) == '\t' || value.charAt(0) == '\r')) return "'" + value;
        return value;
    }

    private static boolean validXmlCodePoint(int cp) {
        return cp == 0x9 || cp == 0xA || cp == 0xD
                || (cp >= 0x20 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD)
                || (cp >= 0x10000 && cp <= 0x10FFFF);
    }

    private static String text(Object value) { return quote(safeText(value == null ? "" : String.valueOf(value))); }
    private static String number(Number value) { return value == null ? "" : String.valueOf(value); }
    private static String decimal(BigDecimal value) { return value == null ? "" : value.toPlainString(); }
    private static String quote(String raw) { return '"' + raw.replace("\"", "\"\"") + '"'; }
    private static void row(StringBuilder out, String... cells) { out.append(String.join(",", cells)).append("\r\n"); }
}
