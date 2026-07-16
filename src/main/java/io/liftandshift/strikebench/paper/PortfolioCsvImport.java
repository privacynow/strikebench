package io.liftandshift.strikebench.paper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Forgiving import of StrikeBench's normalized transaction-and-leg CSV. */
public final class PortfolioCsvImport {
    public static final String TEMPLATE_HEADER = "transaction_ref,occurred_at,event_type,cash_effect_cents,fees_cents,tax_category,notes,leg_no,instrument,action,position_effect,symbol,option_type,strike,expiration,quantity,multiplier,price,section_1256";
    private static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final int MAX_ROWS = 500_000;

    private PortfolioCsvImport() {}

    public record RejectedRow(int line, String transactionRef, String reason) {}

    public record ImportResult(int rowsRead, int transactionsWritten, List<String> transactionIds,
                               int rejectedRows, List<RejectedRow> quarantine, String note) {}

    private record NumberedRow(int line, List<String> cells) {}

    private record ParsedGroup(String ref, List<NumberedRow> rows,
                               PortfolioAccountingService.TransactionInput input,
                               OffsetDateTime occurredAt) {}

    public static ImportResult run(InputStream input, String ownerId, String accountId,
                                   PortfolioAccountingService books) throws IOException {
        if (input == null) throw new IllegalArgumentException("CSV file is required");
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("CSV file exceeds 25 MB");
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> rows = parseCsv(text);
        if (rows.isEmpty()) throw new IllegalArgumentException("CSV is empty");
        if (rows.size() - 1 > MAX_ROWS) throw new IllegalArgumentException("CSV exceeds 500,000 data rows");
        Map<String, Integer> header = header(rows.getFirst());
        for (String required : List.of("transaction_ref", "occurred_at", "event_type")) {
            if (!header.containsKey(required)) throw new IllegalArgumentException("CSV is missing required column " + required);
        }

        Map<String, List<NumberedRow>> rawGroups = new LinkedHashMap<>();
        List<RejectedRow> quarantine = new ArrayList<>();
        int rowsRead = 0;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.stream().allMatch(String::isBlank)) continue;
            rowsRead++;
            int line = i + 1;
            try {
                String ref = required(row, header, "transaction_ref", line);
                rawGroups.computeIfAbsent(ref, ignored -> new ArrayList<>()).add(new NumberedRow(line, row));
            } catch (RuntimeException e) {
                quarantine.add(new RejectedRow(line, null, reason(e)));
            }
        }
        if (rowsRead == 0) throw new IllegalArgumentException("CSV has no transaction rows");

        List<ParsedGroup> parsed = new ArrayList<>();
        for (var entry : rawGroups.entrySet()) {
            try {
                List<NumberedRow> groupedRows = entry.getValue();
                NumberedRow first = groupedRows.getFirst();
                Group group = Group.from(first.cells(), header, first.line());
                for (NumberedRow numbered : groupedRows) {
                    group.checkMetadata(numbered.cells(), header, numbered.line());
                    String instrument = value(numbered.cells(), header, "instrument");
                    if (!instrument.isBlank()) group.addLeg(numbered.cells(), header, numbered.line());
                }
                var inputGroup = group.toInput(entry.getKey());
                parsed.add(new ParsedGroup(entry.getKey(), List.copyOf(groupedRows), inputGroup,
                        importTime(inputGroup.occurredAt())));
            } catch (RuntimeException e) {
                rejectGroup(quarantine, entry.getKey(), entry.getValue(), reason(e));
            }
        }
        parsed.sort(java.util.Comparator.comparing(ParsedGroup::occurredAt));
        List<String> writtenIds = new ArrayList<>();
        for (ParsedGroup group : parsed) {
            try {
                writtenIds.add(books.record(ownerId, accountId, group.input()).id());
            } catch (RuntimeException e) {
                rejectGroup(quarantine, group.ref(), group.rows(), reason(e));
            }
        }
        quarantine.sort(java.util.Comparator.comparingInt(RejectedRow::line));
        return new ImportResult(rowsRead, writtenIds.size(), List.copyOf(writtenIds), quarantine.size(),
                List.copyOf(quarantine), "Valid transaction groups were committed independently. Every rejected row is labeled; all legs sharing a transaction_ref remain atomic and no partial package is written.");
    }

    private static void rejectGroup(List<RejectedRow> quarantine, String ref,
                                    List<NumberedRow> rows, String reason) {
        for (NumberedRow row : rows) quarantine.add(new RejectedRow(row.line(), ref, reason));
    }

    private static String reason(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "Transaction could not be imported" : message;
    }

    private static OffsetDateTime importTime(String raw) {
        try { return OffsetDateTime.parse(raw); }
        catch (RuntimeException ignored) {
            try { return LocalDate.parse(raw).atTime(12, 0).atOffset(ZoneOffset.UTC); }
            catch (RuntimeException e) { throw new IllegalArgumentException("date/time must be ISO-8601"); }
        }
    }

    private static final class Group {
        private final String occurred;
        private final String event;
        private final Long cash;
        private final Long fees;
        private final String category;
        private final String notes;
        private final Map<Integer, PortfolioAccountingService.LegInput> legs = new java.util.TreeMap<>();

        private Group(String occurred, String event, Long cash, Long fees, String category, String notes) {
            this.occurred = occurred; this.event = event; this.cash = cash; this.fees = fees;
            this.category = category; this.notes = notes;
        }

        private static Group from(List<String> row, Map<String, Integer> h, int line) {
            return new Group(required(row, h, "occurred_at", line), required(row, h, "event_type", line),
                    nullableLong(value(row, h, "cash_effect_cents"), "cash_effect_cents", line),
                    nullableLong(value(row, h, "fees_cents"), "fees_cents", line),
                    blankToNull(value(row, h, "tax_category")), blankToNull(value(row, h, "notes")));
        }

        private void checkMetadata(List<String> row, Map<String, Integer> h, int line) {
            Group other = from(row, h, line);
            if (!java.util.Objects.equals(occurred, other.occurred) || !java.util.Objects.equals(event, other.event)
                    || !java.util.Objects.equals(cash, other.cash) || !java.util.Objects.equals(fees, other.fees)
                    || !java.util.Objects.equals(category, other.category) || !java.util.Objects.equals(notes, other.notes)) {
                throw new IllegalArgumentException("line " + line + " repeats a transaction_ref with different transaction metadata");
            }
        }

        private void addLeg(List<String> row, Map<String, Integer> h, int line) {
            int legNo = integer(required(row, h, "leg_no", line), "leg_no", line);
            if (legNo < 0) throw new IllegalArgumentException("line " + line + ": leg_no cannot be negative");
            String instrument = required(row, h, "instrument", line).toUpperCase(Locale.ROOT);
            PortfolioAccountingService.LegInput leg = new PortfolioAccountingService.LegInput(
                    instrument, required(row, h, "action", line), required(row, h, "position_effect", line),
                    required(row, h, "symbol", line), blankToNull(value(row, h, "option_type")),
                    decimal(value(row, h, "strike"), "strike", line),
                    date(value(row, h, "expiration"), "expiration", line),
                    longValue(required(row, h, "quantity", line), "quantity", line),
                    nullableInteger(value(row, h, "multiplier"), "multiplier", line),
                    decimalRequired(value(row, h, "price"), "price", line),
                    nullableBoolean(value(row, h, "section_1256"), "section_1256", line));
            if (legs.putIfAbsent(legNo, leg) != null) throw new IllegalArgumentException("line " + line + ": duplicate leg_no " + legNo);
        }

        private PortfolioAccountingService.TransactionInput toInput(String ref) {
            return new PortfolioAccountingService.TransactionInput(occurred, event, cash, fees, category,
                    "IMPORT", ref, notes, List.copyOf(legs.values()),
                    switch (event) {
                        case "TRADE", "ROLL", "EXPIRATION", "ASSIGNMENT", "EXERCISE" -> "EXECUTED";
                        case "MARK_TO_MARKET" -> "MODELED";
                        default -> "NOT_APPLICABLE";
                    });
        }
    }

    private static Map<String, Integer> header(List<String> row) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < row.size(); i++) {
            String name = row.get(i).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (name.isBlank()) continue;
            if (out.putIfAbsent(name, i) != null) throw new IllegalArgumentException("duplicate CSV column " + name);
        }
        return out;
    }

    private static String required(List<String> row, Map<String, Integer> h, String name, int line) {
        String value = value(row, h, name);
        if (value.isBlank()) throw new IllegalArgumentException("line " + line + ": " + name + " is required");
        return value;
    }

    private static String value(List<String> row, Map<String, Integer> h, String name) {
        Integer index = h.get(name);
        return index == null || index >= row.size() ? "" : row.get(index).trim();
    }

    private static Long nullableLong(String raw, String label, int line) {
        return raw.isBlank() ? null : longValue(raw, label, line);
    }
    private static long longValue(String raw, String label, int line) {
        try { return Long.parseLong(raw); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("line " + line + ": " + label + " must be an integer"); }
    }
    private static Integer nullableInteger(String raw, String label, int line) {
        return raw.isBlank() ? null : integer(raw, label, line);
    }
    private static int integer(String raw, String label, int line) {
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("line " + line + ": " + label + " must be an integer"); }
    }
    private static BigDecimal decimal(String raw, String label, int line) {
        return raw.isBlank() ? null : decimalRequired(raw, label, line);
    }
    private static BigDecimal decimalRequired(String raw, String label, int line) {
        if (raw.isBlank()) throw new IllegalArgumentException("line " + line + ": " + label + " is required");
        try { return new BigDecimal(raw); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("line " + line + ": " + label + " must be a decimal"); }
    }
    private static Boolean nullableBoolean(String raw, String label, int line) {
        if (raw.isBlank()) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y" -> true;
            case "false", "0", "no", "n" -> false;
            default -> throw new IllegalArgumentException("line " + line + ": " + label + " must be true or false");
        };
    }
    private static LocalDate date(String raw, String label, int line) {
        if (raw.isBlank()) return null;
        try { return LocalDate.parse(raw); }
        catch (RuntimeException e) { throw new IllegalArgumentException("line " + line + ": " + label + " must be YYYY-MM-DD"); }
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else cell.append(ch);
            } else if (ch == '"' && cell.isEmpty()) quoted = true;
            else if (ch == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0); rows.add(row); row = new ArrayList<>();
            } else cell.append(ch);
        }
        if (quoted) throw new IllegalArgumentException("CSV has an unclosed quoted field");
        if (!cell.isEmpty() || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        while (!rows.isEmpty() && rows.getLast().stream().allMatch(String::isBlank)) rows.removeLast();
        return rows;
    }
}
