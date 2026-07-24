package io.liftandshift.strikebench.paper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic, one-way parser for sanitized broker CSV/TSV exports. It deliberately does
 * not attempt to recreate a broker export: the contract is broker text -> verified facts or
 * quarantined rows. No network, probabilistic parser, or inferred fill price is involved.
 */
public final class BrokerStatementParser {
    public static final String VERSION = "broker-import-1";
    private static final int MAX_CHARS = 25 * 1024 * 1024;
    private static final int MAX_ROWS = 500_000;

    public enum SourceSystem {
        ETRADE("E*TRADE / Power E*TRADE"),
        VANGUARD("Vanguard"),
        THINKORSWIM("ThinkOrSwim"),
        FIDELITY("Fidelity"),
        SCHWAB("Schwab"),
        ROBINHOOD("Robinhood");

        private final String label;
        SourceSystem(String label) { this.label = label; }
        public String label() { return label; }

        public static SourceSystem parse(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("choose the broker export format");
            String normalized = value.trim().toUpperCase(Locale.ROOT)
                    .replace("*", "").replace(" ", "").replace("_", "").replace("-", "");
            return switch (normalized) {
                case "ETRADE", "POWERETRADE" -> ETRADE;
                case "VANGUARD" -> VANGUARD;
                case "THINKORSWIM", "TOS" -> THINKORSWIM;
                case "FIDELITY" -> FIDELITY;
                case "SCHWAB", "CHARLESSCHWAB" -> SCHWAB;
                case "ROBINHOOD" -> ROBINHOOD;
                default -> throw new IllegalArgumentException("unsupported broker format " + value);
            };
        }
    }

    public enum GroupKind { EXACT_FILLS, PACKAGE_NET_PENDING }

    public record Request(String sourceSystem, String sourceAccount, String text, String fingerprintKey) {
        /** Direct parser callers receive deterministic test/local scoping. HTTP/service callers
         * supply an owner-specific secret so low-entropy account labels are not dictionary hashes. */
        public Request(String sourceSystem, String sourceAccount, String text) {
            this(sourceSystem, sourceAccount, text, "strikebench-parser-local-scope");
        }
    }
    public record QuarantinedRow(int line, String externalRef, String reason) {}
    public record FieldCheck(String field, String value, String sourceColumn, boolean verify, String note) {}
    public record Leg(int line, int legNo, String instrumentType, String action, String positionEffect,
                      String symbol, String optionType, BigDecimal strike, LocalDate expiration,
                      long quantity, int multiplier, BigDecimal reportedPrice,
                      BigDecimal pastedMark, String pastedMarkAsOf, List<FieldCheck> checks) {}
    public record VerifiedLeg(int legNo, String instrumentType, String action, String positionEffect,
                              String symbol, String optionType, BigDecimal strike, LocalDate expiration,
                              long quantity, int multiplier, boolean acknowledgeInferred) {}
    public record Group(String groupKey, String externalRef, String accountFingerprint, String broker,
                        OffsetDateTime occurredAt, Long packageNetCents, long feesCents,
                        GroupKind kind, List<Leg> legs, List<String> warnings,
                        String payloadFingerprint) {}
    public record Result(String parserVersion, String sourceSystem, String sourceLabel,
                         String previewFingerprint, int rowsRead, List<Group> groups,
                         List<QuarantinedRow> quarantine) {}

    /** Applies the user's explicit editor confirmation without changing broker-reported prices. */
    public Group verify(Group original, List<VerifiedLeg> verified) {
        if (original == null || verified == null || verified.size() != original.legs().size()) {
            throw new IllegalArgumentException("verify every leg in the selected broker package");
        }
        Map<Integer, VerifiedLeg> byNo = new LinkedHashMap<>();
        for (VerifiedLeg leg : verified) {
            if (leg == null || byNo.putIfAbsent(leg.legNo(), leg) != null) {
                throw new IllegalArgumentException("each broker leg must be verified exactly once");
            }
        }
        List<Leg> corrected = new ArrayList<>();
        for (Leg imported : original.legs()) {
            VerifiedLeg proposed = byNo.get(imported.legNo());
            if (proposed == null) throw new IllegalArgumentException("verify broker leg " + imported.legNo());
            String instrument = enumLiteral(proposed.instrumentType(), Set.of("STOCK", "OPTION"), "instrument type");
            String action = enumLiteral(proposed.action(), Set.of("BUY", "SELL"), "action");
            String effect = enumLiteral(proposed.positionEffect(), Set.of("OPEN", "CLOSE"), "position effect");
            String verifiedSymbol = symbol(proposed.symbol(), imported.line());
            String optionType = proposed.optionType() == null ? null
                    : enumLiteral(proposed.optionType(), Set.of("CALL", "PUT"), "option type");
            if (proposed.quantity() < 1) throw new IllegalArgumentException("quantity must be positive");
            if (proposed.multiplier() < 1 || proposed.multiplier() > 10_000) {
                throw new IllegalArgumentException("multiplier must be 1..10,000");
            }
            if ("STOCK".equals(instrument)) {
                if (optionType != null || proposed.strike() != null || proposed.expiration() != null
                        || proposed.multiplier() != 1) {
                    throw new IllegalArgumentException("a share leg cannot carry option terms and uses multiplier 1");
                }
            } else if (optionType == null || proposed.strike() == null || proposed.expiration() == null) {
                throw new IllegalArgumentException("an option leg needs put/call, strike, and expiration");
            }
            for (FieldCheck check : imported.checks()) {
                if (!check.verify() || "reportedPrice".equals(check.field())) continue;
                String correctedValue = verifiedValue(check.field(), instrument, action, effect, verifiedSymbol,
                        optionType, proposed.strike(), proposed.expiration(), proposed.quantity(), proposed.multiplier());
                if (Objects.equals(check.value(), correctedValue) && !proposed.acknowledgeInferred()) {
                    throw new IllegalArgumentException("leg " + imported.legNo() + " " + check.field()
                            + " was inferred; correct it or explicitly acknowledge the highlighted value");
                }
            }
            corrected.add(new Leg(imported.line(), imported.legNo(), instrument, action, effect, verifiedSymbol,
                    optionType, proposed.strike(), proposed.expiration(), proposed.quantity(), proposed.multiplier(),
                    imported.reportedPrice(), imported.pastedMark(), imported.pastedMarkAsOf(), imported.checks()));
        }
        corrected.sort(java.util.Comparator.comparingInt(Leg::legNo));
        if (corrected.stream().map(Leg::symbol).distinct().count() != 1) {
            throw new IllegalArgumentException("one managed package cannot span multiple symbols");
        }
        if (original.kind() == GroupKind.EXACT_FILLS) {
            long cash = computedCash(corrected, original.feesCents());
            if (cash != original.packageNetCents()) throw new IllegalArgumentException(
                    "corrected fields produce " + cash + " cents but the broker package fact is "
                            + original.packageNetCents() + " cents");
        }
        String sourceSystem = sourceCodeForLabel(original.broker());
        String payload = groupPayloadFingerprint(sourceSystem, original.accountFingerprint(), original.externalRef(),
                original.occurredAt(), original.packageNetCents(), original.feesCents(), corrected);
        return new Group(original.groupKey(), original.externalRef(), original.accountFingerprint(), original.broker(),
                original.occurredAt(), original.packageNetCents(), original.feesCents(), original.kind(),
                List.copyOf(corrected), original.warnings(), payload);
    }

    private static String enumLiteral(String raw, Set<String> allowed, String label) {
        if (raw == null || !allowed.contains(raw.trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String verifiedValue(String field, String instrument, String action, String effect,
                                        String symbol, String optionType, BigDecimal strike, LocalDate expiration,
                                        long quantity, int multiplier) {
        return switch (field) {
            case "instrumentType" -> instrument;
            case "action" -> action;
            case "positionEffect" -> effect;
            case "symbol" -> symbol;
            case "optionType" -> optionType;
            case "strike" -> decimalIdentity(strike);
            case "expiration" -> expiration == null ? null : expiration.toString();
            case "quantity" -> String.valueOf(quantity);
            case "multiplier" -> String.valueOf(multiplier);
            default -> null;
        };
    }

    private static String sourceCodeForLabel(String label) {
        for (SourceSystem source : SourceSystem.values()) if (source.label().equals(label)) return source.name();
        throw new IllegalArgumentException("unsupported broker label " + label);
    }

    private record NumberedRow(int line, List<String> cells) {}
    private record CanonicalHeader(Map<String, Integer> indexes, Map<String, String> original) {}

    public Result parse(Request request) {
        if (request == null) throw new IllegalArgumentException("broker statement is required");
        SourceSystem source = SourceSystem.parse(request.sourceSystem());
        String text = request.text();
        if (text == null || text.isBlank()) throw new IllegalArgumentException("paste or upload broker text first");
        if (text.length() > MAX_CHARS) throw new IllegalArgumentException("broker text exceeds 25 MB");
        if (text.startsWith("\uFEFF")) text = text.substring(1);

        List<List<String>> rows = delimitedRows(text);
        if (rows.isEmpty()) throw new IllegalArgumentException("broker text is empty");
        if (rows.size() - 1 > MAX_ROWS) throw new IllegalArgumentException("broker text exceeds 500,000 data rows");
        CanonicalHeader header = canonicalHeader(source, rows.getFirst());
        requireHeader(header, "external_ref");
        requireHeader(header, "occurred_at");
        requireHeader(header, "symbol");
        requireHeader(header, "action");
        requireHeader(header, "quantity");

        Map<String, List<NumberedRow>> rowsByReference = new LinkedHashMap<>();
        List<QuarantinedRow> quarantine = new ArrayList<>();
        int read = 0;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.stream().allMatch(String::isBlank)) continue;
            read++;
            int line = i + 1;
            String ref = null;
            try {
                ref = required(row, header, "external_ref", line);
                rowsByReference.computeIfAbsent(ref, ignored -> new ArrayList<>()).add(new NumberedRow(line, row));
            } catch (RuntimeException e) {
                quarantine.add(new QuarantinedRow(line, ref, reason(e)));
            }
        }
        if (read == 0) throw new IllegalArgumentException("broker text has no activity rows");

        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<NumberedRow>> entry : rowsByReference.entrySet()) {
            List<List<NumberedRow>> accountGroups;
            try {
                accountGroups = splitBySourceAccount(request.sourceAccount(), header, entry.getValue());
            } catch (RuntimeException e) {
                for (NumberedRow row : entry.getValue()) quarantine.add(
                        new QuarantinedRow(row.line(), entry.getKey(), reason(e)));
                continue;
            }
            for (List<NumberedRow> accountRows : accountGroups) {
                try {
                    groups.add(parseGroup(source, request.sourceAccount(), request.fingerprintKey(),
                            header, entry.getKey(), accountRows));
                } catch (RuntimeException e) {
                    for (NumberedRow row : accountRows) {
                        quarantine.add(new QuarantinedRow(row.line(), entry.getKey(), reason(e)));
                    }
                }
            }
        }
        groups.sort(java.util.Comparator.comparing(Group::occurredAt).thenComparing(Group::groupKey));
        quarantine.sort(java.util.Comparator.comparingInt(QuarantinedRow::line));
        return new Result(VERSION, source.name(), source.label(),
                fingerprint(source, request.sourceAccount(), text, request.fingerprintKey()),
                read, List.copyOf(groups), List.copyOf(quarantine));
    }

    /** A statement-wide import is partitioned by source account before external reference. */
    private static List<List<NumberedRow>> splitBySourceAccount(String requestAccount, CanonicalHeader h,
                                                                 List<NumberedRow> rows) {
        Map<String, List<NumberedRow>> byAccount = new LinkedHashMap<>();
        List<NumberedRow> missing = new ArrayList<>();
        for (NumberedRow row : rows) {
            String raw = value(row.cells(), h, "account");
            if (raw.isBlank()) missing.add(row);
            else byAccount.computeIfAbsent(normalizedAccount(cleanAccount(raw)), ignored -> new ArrayList<>()).add(row);
        }
        String fallback = cleanAccount(requestAccount);
        if (byAccount.isEmpty()) {
            if (fallback == null) throw new IllegalArgumentException("line " + rows.getFirst().line()
                    + ": source account label or last four is required to prevent cross-account duplicate collisions");
            return List.of(List.copyOf(rows));
        }
        if (!missing.isEmpty()) {
            if (byAccount.size() == 1) byAccount.values().iterator().next().addAll(missing);
            else if (fallback != null && byAccount.containsKey(normalizedAccount(fallback))) {
                byAccount.get(normalizedAccount(fallback)).addAll(missing);
            } else {
                throw new IllegalArgumentException("line " + missing.getFirst().line()
                        + ": an account is required because this reference appears in multiple source accounts");
            }
        }
        return byAccount.values().stream().map(List::copyOf).toList();
    }

    private static Group parseGroup(SourceSystem source, String requestAccount, String fingerprintKey,
                                    CanonicalHeader h, String ref, List<NumberedRow> rows) {
        NumberedRow first = rows.getFirst();
        OffsetDateTime occurred = time(required(first.cells(), h, "occurred_at", first.line()), first.line());
        String rowAccount = rows.stream().map(row -> value(row.cells(), h, "account"))
                .filter(value -> !value.isBlank()).findFirst().orElse("");
        String account = !rowAccount.isBlank() ? cleanAccount(rowAccount) : cleanAccount(requestAccount);
        if (account == null) {
            throw new IllegalArgumentException("line " + first.line()
                    + ": source account label or last four is required to prevent cross-account duplicate collisions");
        }
        String accountFingerprint = accountFingerprint(source, account, fingerprintKey);
        Long net = null;
        long fees = 0;
        boolean feesSeen = false;
        List<Leg> legs = new ArrayList<>();
        Set<Integer> legNumbers = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        boolean missingPrice = false;
        for (int i = 0; i < rows.size(); i++) {
            NumberedRow numbered = rows.get(i);
            List<String> row = numbered.cells();
            int line = numbered.line();
            assertSame("occurred_at", occurred, time(required(row, h, "occurred_at", line), line), line);
            String candidateAccount = value(row, h, "account");
            if (!candidateAccount.isBlank() && !normalizedAccount(candidateAccount).equals(normalizedAccount(account))) {
                throw new IllegalArgumentException("line " + line + ": one external reference spans different broker accounts");
            }
            Long rowNet = cents(value(row, h, "package_net"), "package net", line, true);
            if (rowNet != null) {
                if (net == null) net = rowNet;
                else if (!Objects.equals(net, rowNet)) {
                    throw new IllegalArgumentException("line " + line + ": one external reference has different package nets");
                }
            }
            Long rowFees = cents(value(row, h, "fees"), "fees", line, true);
            if (rowFees != null) {
                long absoluteFees;
                try { absoluteFees = Math.absExact(rowFees); }
                catch (ArithmeticException e) {
                    throw new IllegalArgumentException("line " + line + ": fees exceed the supported range");
                }
                if (!feesSeen) { fees = absoluteFees; feesSeen = true; }
                else if (fees != absoluteFees) {
                    throw new IllegalArgumentException("line " + line + ": one external reference has different fees");
                }
            }
            int legNo = integer(value(row, h, "leg_no"), i, "leg number", line);
            if (!legNumbers.add(legNo)) throw new IllegalArgumentException("line " + line + ": duplicate leg number " + legNo);
            Leg leg = parseLeg(row, h, line, legNo, warnings);
            if (leg.reportedPrice() == null) missingPrice = true;
            legs.add(leg);
        }
        legs.sort(java.util.Comparator.comparingInt(Leg::legNo));
        if (legs.isEmpty()) throw new IllegalArgumentException("reference " + ref + " has no security legs");
        if (legs.stream().map(Leg::symbol).distinct().count() != 1) {
            throw new IllegalArgumentException("reference " + ref
                    + " spans multiple symbols; split it into one managed package per symbol");
        }
        GroupKind kind = missingPrice ? GroupKind.PACKAGE_NET_PENDING : GroupKind.EXACT_FILLS;
        if (kind == GroupKind.PACKAGE_NET_PENDING && net == null) {
            throw new IllegalArgumentException("reference " + ref
                    + " is missing per-leg fills and the exact package net; neither may be invented");
        }
        if (kind == GroupKind.EXACT_FILLS) {
            long computed = computedCash(legs, fees);
            if (net != null && computed != net) {
                throw new IllegalArgumentException("reference " + ref + " exact fills produce " + computed
                        + " cents after fees but the broker package net says " + net + " cents");
            }
            if (net == null) net = computed;
        } else {
            warnings.add("The broker supplied a package net but not every leg fill. This stays outside the tracked ledger until you resolve the missing allocation.");
        }
        if (legs.stream().anyMatch(l -> l.pastedMark() != null)) {
            warnings.add("Pasted marks are historical statement snapshots. They are never treated as current; StrikeBench requests a fresh mark separately when available.");
        }
        String groupKey = "grp-" + sha256(source.name() + "\n" + accountFingerprint + "\n" + ref.trim())
                .substring(0, 24);
        List<Leg> frozenLegs = List.copyOf(legs);
        String payloadFingerprint = groupPayloadFingerprint(source.name(), accountFingerprint, ref.trim(),
                occurred, net, fees, frozenLegs);
        return new Group(groupKey, ref.trim(), accountFingerprint, source.label(), occurred, net, fees, kind,
                frozenLegs, List.copyOf(new LinkedHashSet<>(warnings)), payloadFingerprint);
    }

    public static String groupPayloadFingerprint(String sourceSystem, String accountFingerprint, String externalRef,
                                                 OffsetDateTime occurredAt, Long packageNetCents, long feesCents,
                                                 List<Leg> legs) {
        StringBuilder canonical = new StringBuilder(VERSION).append('\n').append(sourceSystem).append('\n')
                .append(accountFingerprint).append('\n').append(externalRef).append('\n')
                .append(occurredAt).append('\n').append(packageNetCents).append('\n').append(feesCents);
        for (Leg leg : legs.stream().sorted(java.util.Comparator.comparingInt(Leg::legNo)).toList()) {
            canonical.append('\n').append(leg.legNo()).append('|').append(leg.instrumentType()).append('|')
                    .append(leg.action()).append('|').append(leg.positionEffect()).append('|')
                    .append(leg.symbol()).append('|').append(leg.optionType()).append('|')
                    .append(decimalIdentity(leg.strike())).append('|').append(leg.expiration()).append('|')
                    .append(leg.quantity()).append('|').append(leg.multiplier()).append('|')
                    .append(decimalIdentity(leg.reportedPrice()));
        }
        return "import-" + sha256(canonical.toString());
    }

    private static String decimalIdentity(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static Leg parseLeg(List<String> row, CanonicalHeader h, int line, int legNo,
                                List<String> warnings) {
        List<FieldCheck> checks = new ArrayList<>();
        String rawAction = required(row, h, "action", line);
        String action = normalizeAction(rawAction, line);
        String effect = normalizeEffect(value(row, h, "position_effect"), rawAction);
        boolean effectInferred = value(row, h, "position_effect").isBlank()
                && !(rawAction.toUpperCase(Locale.ROOT).contains("OPEN")
                || rawAction.toUpperCase(Locale.ROOT).contains("CLOSE"));
        if (effectInferred) warnings.add("One or more rows omitted open/close; OPEN is highlighted for verification.");
        String optionType = normalizeOptionType(value(row, h, "option_type"), line);
        BigDecimal strike = decimal(value(row, h, "strike"), "strike", line, true);
        LocalDate expiration = date(value(row, h, "expiration"), "expiration", line, true);
        String instrument = normalizeInstrument(value(row, h, "instrument"), optionType, strike, expiration);
        if ("OPTION".equals(instrument)) {
            if (optionType == null || strike == null || expiration == null) {
                throw new IllegalArgumentException("line " + line + ": an option needs put/call, strike, and expiration");
            }
        } else if (optionType != null || strike != null || expiration != null) {
            throw new IllegalArgumentException("line " + line + ": share rows cannot carry option terms");
        }
        String symbol = symbol(required(row, h, "symbol", line), line);
        long quantity = positiveLong(required(row, h, "quantity", line), "quantity", line);
        int multiplier = integer(value(row, h, "multiplier"), "OPTION".equals(instrument) ? 100 : 1,
                "multiplier", line);
        if (multiplier < 1 || multiplier > 10_000) {
            throw new IllegalArgumentException("line " + line + ": multiplier must be 1..10,000");
        }
        BigDecimal price = decimal(value(row, h, "price"), "price", line, true);
        if (price != null && price.signum() < 0) throw new IllegalArgumentException("line " + line + ": price cannot be negative");
        BigDecimal pastedMark = decimal(value(row, h, "mark"), "pasted mark", line, true);
        if (pastedMark != null && pastedMark.signum() < 0) throw new IllegalArgumentException("line " + line + ": pasted mark cannot be negative");
        String markAsOf = value(row, h, "mark_as_of");
        if (pastedMark != null && markAsOf.isBlank()) {
            warnings.add("A pasted mark has no source timestamp; it is labeled stale and cannot become a current value.");
        }

        checks.add(check("symbol", symbol, h, "symbol", false, "Copied exactly from the broker row."));
        checks.add(check("action", action, h, "action", false, "Normalized from " + rawAction + "."));
        checks.add(check("positionEffect", effect, h, "position_effect", effectInferred,
                effectInferred ? "Broker row omitted open/close; verify OPEN." : "Copied from the broker row."));
        checks.add(check("instrumentType", instrument, h, "instrument", value(row, h, "instrument").isBlank(),
                value(row, h, "instrument").isBlank() ? "Inferred only from the option fields; verify." : "Copied from the broker row."));
        checks.add(check("quantity", String.valueOf(quantity), h, "quantity", false, "Absolute broker quantity."));
        checks.add(check("multiplier", String.valueOf(multiplier), h, "multiplier",
                value(row, h, "multiplier").isBlank() && "OPTION".equals(instrument),
                value(row, h, "multiplier").isBlank() && "OPTION".equals(instrument)
                        ? "Standard 100 multiplier inferred; verify adjusted contracts." : "Copied from the broker row."));
        checks.add(check("reportedPrice", price == null ? "missing" : price.toPlainString(), h, "price",
                price == null, price == null ? "No per-leg fill was supplied; package remains pending." : "Exact broker fill."));
        return new Leg(line, legNo, instrument, action, effect, symbol, optionType, strike, expiration,
                quantity, multiplier, price, pastedMark, markAsOf.isBlank() ? null : markAsOf, List.copyOf(checks));
    }

    private static FieldCheck check(String field, String value, CanonicalHeader h, String canonical,
                                    boolean verify, String note) {
        return new FieldCheck(field, value, h.original().get(canonical), verify, note);
    }

    public static long computedCash(List<Leg> legs, long fees) {
        long out = Math.negateExact(fees);
        for (Leg leg : legs) {
            if (leg.reportedPrice() == null) throw new IllegalArgumentException("every leg needs an exact price");
            long gross = leg.reportedPrice().multiply(BigDecimal.valueOf(leg.quantity()))
                    .multiply(BigDecimal.valueOf(leg.multiplier())).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            out = Math.addExact(out, "SELL".equals(leg.action()) ? gross : Math.negateExact(gross));
        }
        return out;
    }

    private static CanonicalHeader canonicalHeader(SourceSystem source, List<String> row) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        Map<String, String> original = new LinkedHashMap<>();
        Map<String, String> aliases = aliases(source);
        for (int i = 0; i < row.size(); i++) {
            String raw = row.get(i).trim();
            if (raw.isBlank()) continue;
            String normalized = headerName(raw);
            String canonical = aliases.getOrDefault(normalized, normalized);
            if (Set.of("external_ref", "account", "occurred_at", "symbol", "instrument", "action",
                    "position_effect", "option_type", "strike", "expiration", "quantity", "multiplier",
                    "price", "package_net", "fees", "leg_no", "mark", "mark_as_of").contains(canonical)) {
                if (indexes.putIfAbsent(canonical, i) != null) {
                    throw new IllegalArgumentException("broker text maps more than one column to " + canonical);
                }
                original.put(canonical, raw);
            }
        }
        return new CanonicalHeader(Map.copyOf(indexes), Map.copyOf(original));
    }

    private static Map<String, String> aliases(SourceSystem source) {
        Map<String, String> out = new LinkedHashMap<>();
        alias(out, "external_ref", "external_ref", "order_id", "order_number", "order_no", "transaction_id",
                "confirmation_number", "reference", "reference_number", "activity_id");
        alias(out, "account", "account", "account_number", "account_id", "account_name");
        alias(out, "occurred_at", "occurred_at", "trade_date", "date", "execution_time", "executed_at",
                "transaction_date", "activity_date", "time");
        alias(out, "symbol", "symbol", "underlying", "ticker");
        alias(out, "instrument", "instrument", "security_type", "asset_type", "type", "product");
        alias(out, "action", "action", "side", "transaction_type", "description_action");
        alias(out, "position_effect", "position_effect", "open_close", "open_or_close", "effect", "position");
        alias(out, "option_type", "option_type", "put_call", "call_put", "put_or_call");
        alias(out, "strike", "strike", "strike_price");
        alias(out, "expiration", "expiration", "expiration_date", "expiry", "expiry_date");
        alias(out, "quantity", "quantity", "qty", "contracts", "shares");
        alias(out, "multiplier", "multiplier", "contract_multiplier", "deliverable");
        alias(out, "price", "price", "fill_price", "execution_price", "trade_price", "average_price", "avg_price");
        alias(out, "package_net", "package_net", "net_amount", "net", "amount", "net_cash", "proceeds");
        alias(out, "fees", "fees", "fee", "commission", "commissions_and_fees", "commission_fees");
        alias(out, "leg_no", "leg_no", "leg", "leg_number", "sequence");
        alias(out, "mark", "mark", "market_price", "current_price", "last_price");
        alias(out, "mark_as_of", "mark_as_of", "as_of", "price_as_of", "statement_date");
        // Source-specific spellings are deliberately explicit and versioned here.
        switch (source) {
            case ETRADE -> alias(out, "external_ref", "order_number", "order_no");
            case VANGUARD -> alias(out, "external_ref", "confirmation_number", "transaction_id");
            case THINKORSWIM -> alias(out, "external_ref", "order_id", "exec_id");
            case FIDELITY -> alias(out, "external_ref", "reference_number", "run_date_reference");
            case SCHWAB -> alias(out, "external_ref", "order_id", "transaction_id");
            case ROBINHOOD -> alias(out, "external_ref", "activity_id", "order_id");
        }
        return Map.copyOf(out);
    }

    private static void alias(Map<String, String> target, String canonical, String... names) {
        for (String name : names) target.put(headerName(name), canonical);
    }

    private static List<List<String>> delimitedRows(String text) {
        String firstLine = text.lines().findFirst().orElse("");
        if (firstLine.indexOf('\t') < 0 || firstLine.indexOf(',') >= 0) return PortfolioCsvImport.parseCsv(text);
        List<List<String>> rows = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (String line : lines) {
            if (line.isBlank() && rows.isEmpty()) continue;
            rows.add(List.of(line.split("\\t", -1)));
        }
        while (!rows.isEmpty() && rows.getLast().stream().allMatch(String::isBlank)) rows.removeLast();
        return rows;
    }

    private static void requireHeader(CanonicalHeader header, String name) {
        if (!header.indexes().containsKey(name)) throw new IllegalArgumentException(
                "broker text is missing a recognized " + name.replace('_', ' ') + " column");
    }

    private static String required(List<String> row, CanonicalHeader h, String name, int line) {
        String value = value(row, h, name);
        if (value.isBlank()) throw new IllegalArgumentException("line " + line + ": "
                + name.replace('_', ' ') + " is required");
        return value;
    }

    private static String value(List<String> row, CanonicalHeader h, String name) {
        Integer index = h.indexes().get(name);
        return index == null || index >= row.size() ? "" : row.get(index).trim();
    }

    private static String normalizeAction(String raw, int line) {
        String v = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (Set.of("BUY", "BOT", "BOUGHT", "BUY_TO_OPEN", "BUY_TO_CLOSE", "BTO", "BTC").contains(v)) return "BUY";
        if (Set.of("SELL", "SOLD", "SLD", "SELL_TO_OPEN", "SELL_TO_CLOSE", "STO", "STC", "SELL_SHORT").contains(v)) return "SELL";
        throw new IllegalArgumentException("line " + line + ": unsupported action " + raw);
    }

    private static String normalizeEffect(String raw, String action) {
        String source = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        String actionValue = action.toUpperCase(Locale.ROOT).replace(' ', '_');
        if (source.equals("OPEN") || source.equals("OPENING") || source.equals("TO_OPEN")) return "OPEN";
        if (source.equals("CLOSE") || source.equals("CLOSING") || source.equals("TO_CLOSE")) return "CLOSE";
        if (actionValue.contains("CLOSE") || actionValue.equals("BTC") || actionValue.equals("STC")) return "CLOSE";
        return "OPEN";
    }

    private static String normalizeOptionType(String raw, int line) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "C", "CALL" -> "CALL";
            case "P", "PUT" -> "PUT";
            default -> throw new IllegalArgumentException("line " + line + ": option type must be put or call");
        };
    }

    private static String normalizeInstrument(String raw, String optionType, BigDecimal strike, LocalDate expiration) {
        if (raw == null || raw.isBlank()) return optionType != null || strike != null || expiration != null ? "OPTION" : "STOCK";
        String value = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (Set.of("OPTION", "OPTIONS", "EQUITY_OPTION", "CALL", "PUT").contains(value)) return "OPTION";
        if (Set.of("STOCK", "SHARE", "SHARES", "EQUITY", "ETF").contains(value)) return "STOCK";
        throw new IllegalArgumentException("unsupported instrument type " + raw);
    }

    private static OffsetDateTime time(String raw, int line) {
        String v = raw.trim();
        try { return OffsetDateTime.parse(v); } catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(v).atOffset(ZoneOffset.UTC); } catch (DateTimeParseException ignored) { }
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("M/d/uuuu"), DateTimeFormatter.ofPattern("MM/dd/uuuu"))) {
            try { return LocalDate.parse(v, formatter).atTime(12, 0).atOffset(ZoneOffset.UTC); }
            catch (DateTimeParseException ignored) { }
        }
        throw new IllegalArgumentException("line " + line + ": date/time is not recognized");
    }

    private static LocalDate date(String raw, String label, int line, boolean nullable) {
        if (raw == null || raw.isBlank()) return nullable ? null : failDate(label, line);
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("M/d/uuuu"), DateTimeFormatter.ofPattern("MM/dd/uuuu"))) {
            try { return LocalDate.parse(raw.trim(), formatter); } catch (DateTimeParseException ignored) { }
        }
        throw new IllegalArgumentException("line " + line + ": " + label + " is not a recognized date");
    }

    private static LocalDate failDate(String label, int line) {
        throw new IllegalArgumentException("line " + line + ": " + label + " is required");
    }

    private static BigDecimal decimal(String raw, String label, int line, boolean nullable) {
        if (raw == null || raw.isBlank() || raw.equals("--") || raw.equalsIgnoreCase("n/a")) {
            if (nullable) return null;
            throw new IllegalArgumentException("line " + line + ": " + label + " is required");
        }
        String clean = moneyText(raw);
        try { return new BigDecimal(clean); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("line " + line + ": " + label + " is not a number"); }
    }

    private static Long cents(String raw, String label, int line, boolean nullable) {
        BigDecimal value = decimal(raw, label, line, nullable);
        return value == null ? null : value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static String moneyText(String raw) {
        String clean = raw.trim().replace("$", "").replace(",", "");
        if (clean.startsWith("(") && clean.endsWith(")")) clean = "-" + clean.substring(1, clean.length() - 1);
        return clean;
    }

    private static long positiveLong(String raw, String label, int line) {
        try {
            long value = new BigDecimal(moneyText(raw)).abs().longValueExact();
            if (value < 1) throw new ArithmeticException();
            return value;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("line " + line + ": " + label + " must be a positive whole number");
        }
    }

    private static int integer(String raw, int fallback, String label, int line) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("line " + line + ": " + label + " must be an integer"); }
    }

    private static String symbol(String raw, int line) {
        String symbol = raw.trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9._/-]{1,24}")) throw new IllegalArgumentException("line " + line + ": symbol is invalid");
        return symbol;
    }

    private static String cleanAccount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String clean = raw.trim();
        if (clean.length() > 200) throw new IllegalArgumentException("source account label is too long");
        return clean;
    }

    private static String normalizedAccount(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    public static String accountFingerprint(SourceSystem source, String account) {
        return accountFingerprint(source, account, "strikebench-parser-local-scope");
    }

    public static String accountFingerprint(SourceSystem source, String account, String fingerprintKey) {
        String normalized = normalizedAccount(account);
        if (normalized.isBlank()) throw new IllegalArgumentException("source account label or last four is required");
        return "acct-" + hmac(fingerprintKey, "strikebench-broker-account-v2\n"
                + source.name() + "\n" + normalized).substring(0, 24);
    }

    private static String fingerprint(SourceSystem source, String account, String text, String fingerprintKey) {
        return "preview-" + hmac(fingerprintKey, VERSION + "\n" + source.name() + "\n"
                + (account == null ? "" : normalizedAccount(account)) + "\n" + text);
    }

    private static String hmac(String key, String text) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("broker fingerprint scope is required");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String headerName(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static void assertSame(String label, Object first, Object next, int line) {
        if (!Objects.equals(first, next)) throw new IllegalArgumentException("line " + line
                + ": one external reference has different " + label.replace('_', ' '));
    }

    private static String reason(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "row could not be parsed" : e.getMessage();
    }
}
