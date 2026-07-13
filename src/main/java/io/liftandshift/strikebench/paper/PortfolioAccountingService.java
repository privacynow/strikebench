package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Owner-scoped accounting for brokerage activity that a user records or imports. This book is
 * intentionally independent from {@link AccountService}: a real fill, contribution, or tax lot
 * can never mutate practice cash or reserve.
 */
public final class PortfolioAccountingService {

    public static final List<String> ACCOUNT_TYPES = List.of(
            "TAXABLE", "TRADITIONAL_IRA", "ROTH_IRA", "TRADITIONAL_401K", "ROTH_401K");
    public static final List<String> LOT_METHODS = List.of("FIFO", "LIFO", "HIFO");
    private static final List<String> CASH_EVENTS = List.of(
            "DEPOSIT", "WITHDRAWAL", "TRANSFER_IN", "TRANSFER_OUT", "INTEREST", "DIVIDEND", "FEE", "ADJUSTMENT");
    private static final List<String> MARKET_EVENTS = List.of("TRADE", "EXPIRATION", "ASSIGNMENT", "EXERCISE");

    private final Db db;
    private final Clock clock;

    public PortfolioAccountingService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public record AccountInput(String name, String accountType, String broker, String lotMethod,
                               Integer shortTermTaxRateBps, Integer longTermTaxRateBps,
                               Integer ordinaryTaxRateBps, Integer stateTaxRateBps,
                               Long openingCashCents) {}

    public record AccountProfile(String id, String ownerId, String name, String accountType,
                                 String broker, String lotMethod, Integer shortTermTaxRateBps,
                                 Integer longTermTaxRateBps, Integer ordinaryTaxRateBps,
                                 Integer stateTaxRateBps, String status, String createdAt,
                                 String updatedAt) {
        public boolean currentlyTaxable() { return "TAXABLE".equals(accountType); }
    }

    public record LegInput(String instrumentType, String action, String positionEffect,
                           String symbol, String optionType, BigDecimal strike,
                           LocalDate expiration, Long quantity, Integer multiplier,
                           BigDecimal price) {}

    public record TransactionInput(String occurredAt, String eventType, Long cashAmountCents,
                                   Long feesCents, String taxCategory, String source,
                                   String externalRef, String notes, List<LegInput> legs) {}

    public record LegView(int legNo, String instrumentType, String action, String positionEffect,
                          String symbol, String optionType, BigDecimal strike, LocalDate expiration,
                          long quantity, int multiplier, BigDecimal price,
                          long grossAmountCents, long allocatedFeeCents) {}

    public record TransactionView(String id, String accountId, String occurredAt, String eventType,
                                  long cashEffectCents, long feesCents, String taxCategory,
                                  String source, String externalRef, String notes,
                                  List<LegView> legs) {}

    public record LotView(String id, String instrumentType, String side, String symbol,
                          String optionType, BigDecimal strike, LocalDate expiration,
                          String openedAt, long originalQuantity, long remainingQuantity,
                          long originalOpenAmountCents, long remainingOpenAmountCents,
                          String status) {}

    public record RealizedLotView(long id, String lotId, String symbol, String instrumentType,
                                  String side, long quantity, String openedAt, String closedAt,
                                  long openAmountCents, long closeAmountCents,
                                  long realizedGainCents, String holdingTerm,
                                  long washSaleAdjustmentCents) {}

    public record ValuationInput(String asOf, Long cashCents, Long securitiesValueCents,
                                 Long totalValueCents, String source, String externalRef,
                                 String notes) {}

    public record ValuationView(String id, String accountId, String asOf, Long cashCents,
                                Long securitiesValueCents, long totalValueCents,
                                String source, String externalRef, String notes) {}

    public record PerformanceView(List<ValuationView> valuations, Long startingValueCents,
                                  Long endingValueCents, long netExternalFlowCents,
                                  Long investmentGainCents, Double modifiedDietzReturn,
                                  Double annualizedReturn, long interestIncomeCents,
                                  long dividendIncomeCents, String note) {}

    public record TaxReport(int year, String accountType, long shortTermGainCents,
                            long longTermGainCents, long ordinaryInterestCents,
                            long ordinaryDividendCents, long qualifiedDividendCents,
                            long capitalGainDistributionCents, long washSaleAdjustmentsCents,
                            Long estimatedFederalTaxCents, Long estimatedStateTaxCents,
                            Long estimatedTotalTaxCents, List<RealizedLotView> realizedLots,
                            String note) {}

    private record PreparedLeg(int legNo, String instrumentType, String action,
                               String positionEffect, String symbol, String optionType,
                               BigDecimal strike, LocalDate expiration, long quantity,
                               int multiplier, BigDecimal price, long grossAmountCents,
                               long allocatedFeeCents) {}

    private record LotRow(String id, String accountId, String instrumentType, String side,
                          String symbol, String optionType, BigDecimal strike, LocalDate expiration,
                          String openedAt, long originalQuantity, long remainingQuantity,
                          long originalOpenAmount, long remainingOpenAmount, String status) {}

    // ---- Accounts ----

    public List<AccountProfile> accounts(String ownerId) {
        return db.query("SELECT * FROM portfolio_account WHERE owner_id=? ORDER BY status, created_at, id",
                PortfolioAccountingService::mapAccount, owner(ownerId));
    }

    public AccountProfile account(String ownerId, String id) {
        return db.with(c -> requireAccount(c, owner(ownerId), id, false));
    }

    public AccountProfile createAccount(String ownerId, AccountInput input) {
        if (input == null) throw new IllegalArgumentException("account details are required");
        String name = text(input.name(), "account name", 100);
        String type = enumValue(input.accountType(), ACCOUNT_TYPES, "account type");
        String method = input.lotMethod() == null || input.lotMethod().isBlank()
                ? "FIFO" : enumValue(input.lotMethod(), LOT_METHODS, "lot method");
        validateRate(input.shortTermTaxRateBps(), "short-term rate");
        validateRate(input.longTermTaxRateBps(), "long-term rate");
        validateRate(input.ordinaryTaxRateBps(), "ordinary-income rate");
        validateRate(input.stateTaxRateBps(), "state rate");
        if (input.openingCashCents() != null && input.openingCashCents() < 0) {
            throw new IllegalArgumentException("opening cash cannot be negative");
        }
        String id = Ids.newId("pacct");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String owner = owner(ownerId);
        return db.tx(c -> {
            Db.execOn(c, "INSERT INTO portfolio_account(id,owner_id,name,account_type,broker,lot_method,"
                            + "short_term_tax_rate_bps,long_term_tax_rate_bps,ordinary_tax_rate_bps,state_tax_rate_bps,"
                            + "status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
                    id, owner, name, type, trim(input.broker(), 100), method,
                    input.shortTermTaxRateBps(), input.longTermTaxRateBps(), input.ordinaryTaxRateBps(),
                    input.stateTaxRateBps(), now, now);
            if (input.openingCashCents() != null && input.openingCashCents() > 0) {
                recordOn(c, requireAccount(c, owner, id, true), new TransactionInput(now.toString(), "DEPOSIT",
                        input.openingCashCents(), 0L, null, "MANUAL", "opening-balance", "Opening cash", List.of()));
            }
            return requireAccount(c, owner, id, false);
        });
    }

    public AccountProfile updateAccount(String ownerId, String id, AccountInput input) {
        if (input == null) throw new IllegalArgumentException("account details are required");
        String owner = owner(ownerId);
        return db.tx(c -> {
            AccountProfile current = requireAccount(c, owner, id, true);
            String name = input.name() == null ? current.name() : text(input.name(), "account name", 100);
            String type = input.accountType() == null ? current.accountType()
                    : enumValue(input.accountType(), ACCOUNT_TYPES, "account type");
            String method = input.lotMethod() == null ? current.lotMethod()
                    : enumValue(input.lotMethod(), LOT_METHODS, "lot method");
            Integer st = input.shortTermTaxRateBps() == null ? current.shortTermTaxRateBps() : input.shortTermTaxRateBps();
            Integer lt = input.longTermTaxRateBps() == null ? current.longTermTaxRateBps() : input.longTermTaxRateBps();
            Integer ordinary = input.ordinaryTaxRateBps() == null ? current.ordinaryTaxRateBps() : input.ordinaryTaxRateBps();
            Integer state = input.stateTaxRateBps() == null ? current.stateTaxRateBps() : input.stateTaxRateBps();
            validateRate(st, "short-term rate"); validateRate(lt, "long-term rate");
            validateRate(ordinary, "ordinary-income rate"); validateRate(state, "state rate");
            String broker = input.broker() == null ? current.broker() : trim(input.broker(), 100);
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            Db.execOn(c, "UPDATE portfolio_account SET name=?,account_type=?,broker=?,lot_method=?,"
                            + "short_term_tax_rate_bps=?,long_term_tax_rate_bps=?,ordinary_tax_rate_bps=?,"
                            + "state_tax_rate_bps=?,updated_at=? WHERE id=?",
                    name, type, broker, method, st, lt, ordinary, state, now, id);
            return requireAccount(c, owner, id, false);
        });
    }

    // ---- Transactions and lots ----

    public TransactionView record(String ownerId, String accountId, TransactionInput input) {
        String owner = owner(ownerId);
        return db.tx(c -> recordOn(c, requireAccount(c, owner, accountId, true), input));
    }

    private TransactionView recordOn(Connection c, AccountProfile account, TransactionInput input) throws SQLException {
        if (!"ACTIVE".equals(account.status())) throw new IllegalStateException("This tracked account is archived and read-only.");
        if (input == null) throw new IllegalArgumentException("transaction details are required");
        String event = enumValue(input.eventType(), concat(CASH_EVENTS, MARKET_EVENTS), "event type");
        OffsetDateTime occurred = parseTime(input.occurredAt());
        long fees = input.feesCents() == null ? 0 : input.feesCents();
        if (fees < 0) throw new IllegalArgumentException("fees cannot be negative");
        String source = input.source() == null || input.source().isBlank() ? "MANUAL"
                : enumValue(input.source(), List.of("MANUAL", "BROKER", "IMPORT"), "source");
        String externalRef = trim(input.externalRef(), 160);
        if (externalRef != null) {
            var duplicate = Db.queryOn(c, "SELECT id FROM portfolio_transaction WHERE portfolio_account_id=? AND source=? AND external_ref=?",
                    r -> r.str("id"), account.id(), source, externalRef);
            if (!duplicate.isEmpty()) throw new IllegalStateException("That " + source.toLowerCase(Locale.ROOT)
                    + " reference is already recorded as " + duplicate.getFirst() + ".");
        }

        List<PreparedLeg> legs = prepareLegs(input.legs(), fees, event);
        long computedCash = legs.stream().mapToLong(l -> "SELL".equals(l.action())
                ? l.grossAmountCents() : -l.grossAmountCents()).sum() - fees;
        long cashEffect;
        if (CASH_EVENTS.contains(event)) {
            if (!legs.isEmpty()) throw new IllegalArgumentException(event + " does not take security legs");
            if (input.cashAmountCents() == null) throw new IllegalArgumentException("cash amount is required for " + event);
            cashEffect = normalizedCash(event, input.cashAmountCents());
            if ("FEE".equals(event)) fees = Math.abs(cashEffect);
        } else {
            if (legs.isEmpty()) throw new IllegalArgumentException(event + " requires at least one security leg");
            if (input.cashAmountCents() != null && Math.abs(input.cashAmountCents() - computedCash) > 1) {
                throw new IllegalArgumentException("The stated cash effect does not reconcile with the exact legs and fees: legs produce "
                        + Money.fmt(computedCash) + " but the transaction says " + Money.fmt(input.cashAmountCents()) + ".");
            }
            cashEffect = input.cashAmountCents() == null ? computedCash : input.cashAmountCents();
        }

        String txId = Ids.newId("ptx");
        Db.execOn(c, "INSERT INTO portfolio_transaction(id,portfolio_account_id,occurred_at,event_type,cash_effect_cents,"
                        + "fees_cents,tax_category,source,external_ref,notes) VALUES (?,?,?,?,?,?,?,?,?,?)",
                txId, account.id(), occurred, event, cashEffect, fees,
                normalizeTaxCategory(input.taxCategory(), event), source, externalRef, trim(input.notes(), 1000));
        for (PreparedLeg leg : legs) {
            insertLeg(c, txId, leg);
        }
        if ("ASSIGNMENT".equals(event) || "EXERCISE".equals(event)) {
            applyOptionConversion(c, account, txId, occurred, event, legs);
        } else {
            for (PreparedLeg leg : legs) applyLeg(c, account, txId, occurred, leg, 0);
        }
        return transaction(c, account.id(), txId);
    }

    public List<TransactionView> transactions(String ownerId, String accountId, int page, int size) {
        AccountProfile account = account(ownerId, accountId);
        int bounded = Math.max(1, Math.min(500, size));
        int offset = Math.max(0, page) * bounded;
        return db.with(c -> Db.queryOn(c, "SELECT id FROM portfolio_transaction WHERE portfolio_account_id=? "
                        + "ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?", r -> r.str("id"),
                account.id(), bounded, offset).stream().map(id -> {
                    try { return transaction(c, account.id(), id); }
                    catch (SQLException e) { throw new Db.DbException(e); }
                }).toList());
    }

    public List<LotView> lots(String ownerId, String accountId, boolean includeClosed) {
        AccountProfile account = account(ownerId, accountId);
        String sql = "SELECT * FROM portfolio_lot WHERE portfolio_account_id=?"
                + (includeClosed ? "" : " AND status='OPEN'") + " ORDER BY symbol,instrument_type,opened_at,id";
        return db.query(sql, PortfolioAccountingService::mapLotView, account.id());
    }

    public List<RealizedLotView> realizedLots(String ownerId, String accountId, int year) {
        AccountProfile account = account(ownerId, accountId);
        return db.query("SELECT m.*,l.symbol,l.instrument_type,l.side FROM portfolio_lot_match m JOIN portfolio_lot l ON l.id=m.lot_id "
                        + "WHERE m.portfolio_account_id=? AND EXTRACT(YEAR FROM m.closed_at)=? ORDER BY m.closed_at,m.id",
                PortfolioAccountingService::mapRealized, account.id(), year);
    }

    // ---- Valuation, performance, and taxes ----

    public ValuationView addValuation(String ownerId, String accountId, ValuationInput input) {
        AccountProfile account = account(ownerId, accountId);
        if (input == null || input.totalValueCents() == null) throw new IllegalArgumentException("total account value is required");
        if (input.totalValueCents() < 0) throw new IllegalArgumentException("total account value cannot be negative");
        OffsetDateTime asOf = parseTime(input.asOf());
        if (input.cashCents() != null && input.securitiesValueCents() != null
                && input.cashCents() + input.securitiesValueCents() != input.totalValueCents()) {
            throw new IllegalArgumentException("cash plus securities value must equal total account value");
        }
        String source = input.source() == null || input.source().isBlank() ? "MANUAL"
                : enumValue(input.source(), List.of("MANUAL", "BROKER", "CALCULATED", "IMPORT"), "valuation source");
        String id = Ids.newId("pval");
        db.exec("INSERT INTO portfolio_valuation(id,portfolio_account_id,as_of,cash_cents,securities_value_cents,total_value_cents,"
                        + "source,external_ref,notes) VALUES (?,?,?,?,?,?,?,?,?)",
                id, account.id(), asOf, input.cashCents(), input.securitiesValueCents(), input.totalValueCents(),
                source, trim(input.externalRef(), 160), trim(input.notes(), 1000));
        return new ValuationView(id, account.id(), asOf.toString(), input.cashCents(), input.securitiesValueCents(),
                input.totalValueCents(), source, trim(input.externalRef(), 160), trim(input.notes(), 1000));
    }

    public PerformanceView performance(String ownerId, String accountId) {
        AccountProfile account = account(ownerId, accountId);
        List<ValuationView> values = db.query("SELECT * FROM portfolio_valuation WHERE portfolio_account_id=? ORDER BY as_of,id",
                PortfolioAccountingService::mapValuation, account.id());
        long interest = income(account.id(), "INTEREST", null);
        long dividends = income(account.id(), "DIVIDEND", null);
        if (values.size() < 2) {
            return new PerformanceView(values, values.isEmpty() ? null : values.getFirst().totalValueCents(),
                    values.isEmpty() ? null : values.getLast().totalValueCents(), 0, null, null, null,
                    interest, dividends, "Record at least two account-value snapshots to calculate a contribution-adjusted return.");
        }
        OffsetDateTime start = OffsetDateTime.parse(values.getFirst().asOf());
        OffsetDateTime end = OffsetDateTime.parse(values.getLast().asOf());
        double totalSeconds = Math.max(1, Duration.between(start, end).toSeconds());
        List<long[]> flows = db.query("SELECT cash_effect_cents,EXTRACT(EPOCH FROM (occurred_at-?::timestamptz)) AS secs "
                        + "FROM portfolio_transaction WHERE portfolio_account_id=? AND occurred_at>? AND occurred_at<=? "
                        + "AND event_type IN ('DEPOSIT','WITHDRAWAL','TRANSFER_IN','TRANSFER_OUT')",
                r -> new long[]{r.lng("cash_effect_cents"), Math.round(r.dbl("secs"))}, start, account.id(), start, end);
        long netFlows = flows.stream().mapToLong(f -> f[0]).sum();
        double weightedFlows = flows.stream().mapToDouble(f -> f[0] * Math.max(0, totalSeconds - f[1]) / totalSeconds).sum();
        long begin = values.getFirst().totalValueCents();
        long finish = values.getLast().totalValueCents();
        long gain = finish - begin - netFlows;
        double denominator = begin + weightedFlows;
        Double dietz = denominator == 0 ? null : gain / denominator;
        long days = Math.max(1, ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()));
        Double annualized = dietz == null || dietz <= -1 || days < 30 ? null
                : Math.pow(1 + dietz, 365.0 / days) - 1;
        return new PerformanceView(values, begin, finish, netFlows, gain, dietz, annualized,
                interest, dividends, "Modified Dietz weights external cash flows by time in the account; returns are only as complete as the recorded valuations and flows.");
    }

    public TaxReport taxReport(String ownerId, String accountId, int year) {
        AccountProfile account = account(ownerId, accountId);
        if (year < 1970 || year > 9999) throw new IllegalArgumentException("invalid tax year");
        List<RealizedLotView> realized = realizedLots(ownerId, accountId, year);
        long st = realized.stream().filter(r -> "SHORT_TERM".equals(r.holdingTerm())).mapToLong(RealizedLotView::realizedGainCents).sum();
        long lt = realized.stream().filter(r -> "LONG_TERM".equals(r.holdingTerm())).mapToLong(RealizedLotView::realizedGainCents).sum();
        long interest = income(account.id(), "INTEREST", year);
        long ordinaryDiv = incomeByCategory(account.id(), year, "ORDINARY_DIVIDEND");
        long qualifiedDiv = incomeByCategory(account.id(), year, "QUALIFIED_DIVIDEND");
        long capDist = incomeByCategory(account.id(), year, "CAPITAL_GAIN_DISTRIBUTION");
        long wash = realized.stream().mapToLong(RealizedLotView::washSaleAdjustmentCents).sum();
        Long federal = null, state = null, total = null;
        String note;
        if (!account.currentlyTaxable()) {
            federal = state = total = 0L;
            note = "This wrapper has no current per-trade capital-gains estimate. Contributions, withdrawals, conversions, penalties, and future distributions follow separate tax rules.";
        } else if (account.shortTermTaxRateBps() != null && account.longTermTaxRateBps() != null
                && account.ordinaryTaxRateBps() != null) {
            federal = taxAt(st, account.shortTermTaxRateBps())
                    + taxAt(interest + ordinaryDiv, account.ordinaryTaxRateBps())
                    + taxAt(lt + capDist + qualifiedDiv, account.longTermTaxRateBps());
            state = account.stateTaxRateBps() == null ? null
                    : taxAt(st + lt + interest + ordinaryDiv + qualifiedDiv + capDist, account.stateTaxRateBps());
            total = federal + (state == null ? 0 : state);
            note = taxLimitNote();
        } else {
            note = "Basis and holding periods are calculated, but a tax estimate needs the account's short-term, long-term, and ordinary-income rates. " + taxLimitNote();
        }
        return new TaxReport(year, account.accountType(), st, lt, interest, ordinaryDiv, qualifiedDiv,
                capDist, wash, federal, state, total, realized, note);
    }

    // ---- Lot application ----

    private void applyLeg(Connection c, AccountProfile account, String txId, OffsetDateTime occurred,
                          PreparedLeg leg, long amountAdjustment) throws SQLException {
        if ("OPEN".equals(leg.positionEffect())) {
            long amount = openingAmount(leg) + amountAdjustment;
            if (amount < 0) throw new IllegalArgumentException("opening basis/proceeds became negative after assignment adjustment");
            insertLot(c, account.id(), txId, occurred, leg, amount);
        } else {
            closeLots(c, account, txId, occurred, leg, closingAmount(leg) + amountAdjustment, false);
        }
    }

    private void applyOptionConversion(Connection c, AccountProfile account, String txId,
                                       OffsetDateTime occurred, String event,
                                       List<PreparedLeg> legs) throws SQLException {
        if (legs.size() != 2) throw new IllegalArgumentException(event + " requires exactly one closing option leg and one stock leg");
        PreparedLeg option = legs.stream().filter(l -> "OPTION".equals(l.instrumentType())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(event + " requires an option leg"));
        PreparedLeg stock = legs.stream().filter(l -> "STOCK".equals(l.instrumentType())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(event + " requires a stock leg"));
        if (!"CLOSE".equals(option.positionEffect()) || option.price().signum() != 0) {
            throw new IllegalArgumentException(event + " option leg must CLOSE at $0; its premium transfers into stock basis/proceeds");
        }
        if (!option.symbol().equals(stock.symbol()) || stock.quantity() != option.quantity() * 100L) {
            throw new IllegalArgumentException(event + " stock quantity must equal option contracts x 100 for the same symbol");
        }
        if (stock.price().compareTo(option.strike()) != 0) {
            throw new IllegalArgumentException(event + " stock price must equal the option strike");
        }
        String expectedSide = "ASSIGNMENT".equals(event) ? "SHORT" : "LONG";
        String closingSide = "BUY".equals(option.action()) ? "SHORT" : "LONG";
        if (!expectedSide.equals(closingSide)) throw new IllegalArgumentException(event + " must close a " + expectedSide.toLowerCase(Locale.ROOT) + " option lot");
        long transferred = closeLots(c, account, txId, occurred, option, 0, true);
        boolean put = "PUT".equals(option.optionType());
        long adjustment;
        if (("SHORT".equals(expectedSide) && put) || ("LONG".equals(expectedSide) && !put)) {
            if (!"BUY".equals(stock.action()) || !"OPEN".equals(stock.positionEffect())) {
                throw new IllegalArgumentException(event + " of this option requires BUY/OPEN stock");
            }
            adjustment = "SHORT".equals(expectedSide) ? -transferred : transferred;
        } else {
            if (!"SELL".equals(stock.action()) || !"CLOSE".equals(stock.positionEffect())) {
                throw new IllegalArgumentException(event + " of this option requires SELL/CLOSE stock");
            }
            adjustment = "SHORT".equals(expectedSide) ? transferred : -transferred;
        }
        applyLeg(c, account, txId, occurred, stock, adjustment);
    }

    /** Returns opening basis/proceeds consumed. In roll mode the option itself realizes $0. */
    private long closeLots(Connection c, AccountProfile account, String txId, OffsetDateTime occurred,
                           PreparedLeg leg, long totalCloseAmount, boolean rolled) throws SQLException {
        String side = "BUY".equals(leg.action()) ? "SHORT" : "LONG";
        List<LotRow> candidates = matchingLots(c, account, leg, side);
        long available = candidates.stream().mapToLong(LotRow::remainingQuantity).sum();
        if (available < leg.quantity()) {
            throw new IllegalStateException("Cannot close " + leg.quantity() + " " + leg.symbol() + " "
                    + leg.instrumentType().toLowerCase(Locale.ROOT) + " units; only " + available + " matching "
                    + side.toLowerCase(Locale.ROOT) + " units are open.");
        }
        long remainingQty = leg.quantity();
        long remainingClose = totalCloseAmount;
        long transferred = 0;
        for (LotRow lot : candidates) {
            if (remainingQty == 0) break;
            long qty = Math.min(remainingQty, lot.remainingQuantity());
            long openPart = allocate(lot.remainingOpenAmount(), qty, lot.remainingQuantity());
            long closePart = remainingQty == qty ? remainingClose : allocate(remainingClose, qty, remainingQty);
            long realized = rolled ? 0 : "LONG".equals(side) ? closePart - openPart : openPart - closePart;
            String term = rolled ? "ROLLED" : holdingTerm(lot, occurred);
            Db.execOn(c, "INSERT INTO portfolio_lot_match(portfolio_account_id,lot_id,closing_transaction_id,closing_leg_no,"
                            + "quantity,opened_at,closed_at,open_amount_cents,close_amount_cents,realized_gain_cents,holding_term) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    account.id(), lot.id(), txId, leg.legNo(), qty, OffsetDateTime.parse(lot.openedAt()), occurred,
                    openPart, rolled ? openPart : closePart, realized, term);
            long lotQtyAfter = lot.remainingQuantity() - qty;
            long lotAmountAfter = lot.remainingOpenAmount() - openPart;
            Db.execOn(c, "UPDATE portfolio_lot SET remaining_quantity=?,remaining_open_amount_cents=?,status=? WHERE id=?",
                    lotQtyAfter, lotAmountAfter, lotQtyAfter == 0 ? (rolled ? "ROLLED" : "CLOSED") : "OPEN", lot.id());
            remainingQty -= qty;
            remainingClose -= closePart;
            transferred += openPart;
        }
        return transferred;
    }

    private List<LotRow> matchingLots(Connection c, AccountProfile account, PreparedLeg leg, String side) throws SQLException {
        String order = switch (account.lotMethod()) {
            case "LIFO" -> "opened_at DESC,id DESC";
            case "HIFO" -> "(remaining_open_amount_cents::numeric/remaining_quantity) DESC,opened_at,id";
            default -> "opened_at,id";
        };
        return Db.queryOn(c, "SELECT * FROM portfolio_lot WHERE portfolio_account_id=? AND status='OPEN' "
                        + "AND instrument_type=? AND side=? AND symbol=? AND option_type IS NOT DISTINCT FROM ? "
                        + "AND strike IS NOT DISTINCT FROM ? AND expiration IS NOT DISTINCT FROM ? ORDER BY " + order + " FOR UPDATE",
                PortfolioAccountingService::mapLot, account.id(), leg.instrumentType(), side, leg.symbol(),
                leg.optionType(), leg.strike(), leg.expiration());
    }

    private static String holdingTerm(LotRow lot, OffsetDateTime closed) {
        if ("SHORT".equals(lot.side())) return "SHORT_TERM";
        long days = ChronoUnit.DAYS.between(OffsetDateTime.parse(lot.openedAt()).toLocalDate(), closed.toLocalDate());
        return days > 365 ? "LONG_TERM" : "SHORT_TERM";
    }

    private static long openingAmount(PreparedLeg leg) {
        return "BUY".equals(leg.action())
                ? Math.addExact(leg.grossAmountCents(), leg.allocatedFeeCents())
                : Math.subtractExact(leg.grossAmountCents(), leg.allocatedFeeCents());
    }

    private static long closingAmount(PreparedLeg leg) {
        return "SELL".equals(leg.action())
                ? Math.subtractExact(leg.grossAmountCents(), leg.allocatedFeeCents())
                : Math.addExact(leg.grossAmountCents(), leg.allocatedFeeCents());
    }

    private void insertLot(Connection c, String accountId, String txId, OffsetDateTime occurred,
                           PreparedLeg leg, long amount) throws SQLException {
        String side = "BUY".equals(leg.action()) ? "LONG" : "SHORT";
        Db.execOn(c, "INSERT INTO portfolio_lot(id,portfolio_account_id,opening_transaction_id,opening_leg_no,"
                        + "instrument_type,side,symbol,option_type,strike,expiration,opened_at,original_quantity,"
                        + "remaining_quantity,original_open_amount_cents,remaining_open_amount_cents,status) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'OPEN')",
                Ids.newId("plot"), accountId, txId, leg.legNo(), leg.instrumentType(), side,
                leg.symbol(), leg.optionType(), leg.strike(), leg.expiration(), occurred,
                leg.quantity(), leg.quantity(), amount, amount);
    }

    // ---- Mapping and validation ----

    private List<PreparedLeg> prepareLegs(List<LegInput> raw, long fees, String event) {
        List<LegInput> legs = raw == null ? List.of() : raw;
        List<PreparedLeg> base = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            LegInput in = legs.get(i);
            if (in == null) throw new IllegalArgumentException("leg " + (i + 1) + " is missing");
            String instrument = enumValue(in.instrumentType(), List.of("STOCK", "OPTION"), "instrument type");
            String action = enumValue(in.action(), List.of("BUY", "SELL"), "action");
            String effect = enumValue(in.positionEffect(), List.of("OPEN", "CLOSE"), "position effect");
            String symbol = symbol(in.symbol());
            long quantity = in.quantity() == null ? 0 : in.quantity();
            if (quantity <= 0 || quantity > 10_000_000) throw new IllegalArgumentException("leg quantity must be 1..10,000,000");
            int multiplier = "STOCK".equals(instrument) ? 1 : in.multiplier() == null ? 100 : in.multiplier();
            if (multiplier <= 0 || multiplier > 10_000) throw new IllegalArgumentException("leg multiplier must be 1..10,000");
            BigDecimal price = in.price() == null ? BigDecimal.ZERO : in.price().setScale(Money.PRICE_SCALE, RoundingMode.HALF_UP);
            if (price.signum() < 0) throw new IllegalArgumentException("leg price cannot be negative");
            String optionType = null;
            BigDecimal strike = null;
            LocalDate expiration = null;
            if ("OPTION".equals(instrument)) {
                optionType = enumValue(in.optionType(), List.of("CALL", "PUT"), "option type");
                if (in.strike() == null || in.strike().signum() <= 0) throw new IllegalArgumentException("option strike must be positive");
                if (in.expiration() == null) throw new IllegalArgumentException("option expiration is required");
                strike = in.strike().setScale(Money.PRICE_SCALE, RoundingMode.HALF_UP);
                expiration = in.expiration();
            }
            if ("EXPIRATION".equals(event) && (!"CLOSE".equals(effect) || price.signum() != 0)) {
                throw new IllegalArgumentException("Expiration legs must CLOSE at $0");
            }
            long units = Math.multiplyExact(quantity, (long) multiplier);
            long gross = Money.centsFromPrice(price, units);
            base.add(new PreparedLeg(i, instrument, action, effect, symbol, optionType, strike,
                    expiration, quantity, multiplier, price, gross, 0));
        }
        if (base.isEmpty() || fees == 0) return base;
        long totalGross = base.stream().mapToLong(PreparedLeg::grossAmountCents).sum();
        long remaining = fees;
        List<PreparedLeg> allocated = new ArrayList<>(base.size());
        for (int i = 0; i < base.size(); i++) {
            PreparedLeg leg = base.get(i);
            long fee = i == base.size() - 1 ? remaining
                    : totalGross == 0 ? 0 : BigDecimal.valueOf(fees).multiply(BigDecimal.valueOf(leg.grossAmountCents()))
                    .divide(BigDecimal.valueOf(totalGross), 0, RoundingMode.HALF_UP).longValueExact();
            fee = Math.min(remaining, fee);
            remaining -= fee;
            allocated.add(new PreparedLeg(leg.legNo(), leg.instrumentType(), leg.action(), leg.positionEffect(),
                    leg.symbol(), leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(), leg.multiplier(),
                    leg.price(), leg.grossAmountCents(), fee));
        }
        return allocated;
    }

    private static void insertLeg(Connection c, String txId, PreparedLeg leg) throws SQLException {
        Db.execOn(c, "INSERT INTO portfolio_transaction_leg(transaction_id,leg_no,instrument_type,action,position_effect,"
                        + "symbol,option_type,strike,expiration,quantity,multiplier,price,gross_amount_cents,allocated_fee_cents) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                txId, leg.legNo(), leg.instrumentType(), leg.action(), leg.positionEffect(), leg.symbol(),
                leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(), leg.multiplier(), leg.price(),
                leg.grossAmountCents(), leg.allocatedFeeCents());
    }

    private TransactionView transaction(Connection c, String accountId, String txId) throws SQLException {
        var rows = Db.queryOn(c, "SELECT * FROM portfolio_transaction WHERE id=? AND portfolio_account_id=?",
                r -> new Object[]{r.str("id"), r.str("portfolio_account_id"), iso(r.odt("occurred_at")), r.str("event_type"),
                        r.lng("cash_effect_cents"), r.lng("fees_cents"), r.str("tax_category"), r.str("source"),
                        r.str("external_ref"), r.str("notes")}, txId, accountId);
        if (rows.isEmpty()) throw new IllegalArgumentException("no such portfolio transaction " + txId);
        Object[] row = rows.getFirst();
        List<LegView> legs = Db.queryOn(c, "SELECT * FROM portfolio_transaction_leg WHERE transaction_id=? ORDER BY leg_no",
                PortfolioAccountingService::mapLeg, txId);
        return new TransactionView((String) row[0], (String) row[1], (String) row[2], (String) row[3],
                (Long) row[4], (Long) row[5], (String) row[6], (String) row[7], (String) row[8], (String) row[9], legs);
    }

    private static AccountProfile requireAccount(Connection c, String owner, String id, boolean lock) throws SQLException {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("portfolio account id is required");
        var rows = Db.queryOn(c, "SELECT * FROM portfolio_account WHERE id=? AND owner_id=?" + (lock ? " FOR UPDATE" : ""),
                PortfolioAccountingService::mapAccount, id, owner);
        if (rows.isEmpty()) throw new java.util.NoSuchElementException("No tracked portfolio account " + id);
        return rows.getFirst();
    }

    private static AccountProfile mapAccount(Db.Row r) {
        return new AccountProfile(r.str("id"), r.str("owner_id"), r.str("name"), r.str("account_type"),
                r.str("broker"), r.str("lot_method"), nullableInt(r, "short_term_tax_rate_bps"),
                nullableInt(r, "long_term_tax_rate_bps"), nullableInt(r, "ordinary_tax_rate_bps"),
                nullableInt(r, "state_tax_rate_bps"), r.str("status"), iso(r.odt("created_at")),
                iso(r.odt("updated_at")));
    }

    private static Integer nullableInt(Db.Row r, String col) {
        Long value = r.lngOrNull(col);
        return value == null ? null : Math.toIntExact(value);
    }

    private static LegView mapLeg(Db.Row r) {
        return new LegView(r.intv("leg_no"), r.str("instrument_type"), r.str("action"), r.str("position_effect"),
                r.str("symbol"), r.str("option_type"), r.bd("strike"), r.date("expiration"),
                r.lng("quantity"), r.intv("multiplier"), r.bd("price"), r.lng("gross_amount_cents"),
                r.lng("allocated_fee_cents"));
    }

    private static LotRow mapLot(Db.Row r) {
        return new LotRow(r.str("id"), r.str("portfolio_account_id"), r.str("instrument_type"), r.str("side"),
                r.str("symbol"), r.str("option_type"), r.bd("strike"), r.date("expiration"),
                iso(r.odt("opened_at")), r.lng("original_quantity"), r.lng("remaining_quantity"),
                r.lng("original_open_amount_cents"), r.lng("remaining_open_amount_cents"), r.str("status"));
    }

    private static LotView mapLotView(Db.Row r) {
        LotRow x = mapLot(r);
        return new LotView(x.id(), x.instrumentType(), x.side(), x.symbol(), x.optionType(), x.strike(),
                x.expiration(), x.openedAt(), x.originalQuantity(), x.remainingQuantity(),
                x.originalOpenAmount(), x.remainingOpenAmount(), x.status());
    }

    private static RealizedLotView mapRealized(Db.Row r) {
        return new RealizedLotView(r.lng("id"), r.str("lot_id"), r.str("symbol"), r.str("instrument_type"),
                r.str("side"), r.lng("quantity"), iso(r.odt("opened_at")), iso(r.odt("closed_at")),
                r.lng("open_amount_cents"), r.lng("close_amount_cents"), r.lng("realized_gain_cents"),
                r.str("holding_term"), r.lng("wash_sale_adjustment_cents"));
    }

    private static ValuationView mapValuation(Db.Row r) {
        return new ValuationView(r.str("id"), r.str("portfolio_account_id"), iso(r.odt("as_of")),
                r.lngOrNull("cash_cents"), r.lngOrNull("securities_value_cents"), r.lng("total_value_cents"),
                r.str("source"), r.str("external_ref"), r.str("notes"));
    }

    private long income(String accountId, String event, Integer year) {
        String yearSql = year == null ? "" : " AND EXTRACT(YEAR FROM occurred_at)=?";
        Object[] args = year == null ? new Object[]{accountId, event} : new Object[]{accountId, event, year};
        return db.query("SELECT COALESCE(SUM(cash_effect_cents),0) n FROM portfolio_transaction WHERE portfolio_account_id=? AND event_type=?" + yearSql,
                r -> r.lng("n"), args).getFirst();
    }

    private long incomeByCategory(String accountId, int year, String category) {
        return db.query("SELECT COALESCE(SUM(cash_effect_cents),0) n FROM portfolio_transaction WHERE portfolio_account_id=? "
                        + "AND EXTRACT(YEAR FROM occurred_at)=? AND tax_category=?",
                r -> r.lng("n"), accountId, year, category).getFirst();
    }

    private static String normalizeTaxCategory(String raw, String event) {
        if (raw == null || raw.isBlank()) {
            return switch (event) {
                case "INTEREST" -> "ORDINARY_INTEREST";
                case "DIVIDEND" -> "ORDINARY_DIVIDEND";
                default -> null;
            };
        }
        return enumValue(raw, List.of("ORDINARY_INTEREST", "ORDINARY_DIVIDEND", "QUALIFIED_DIVIDEND",
                "RETURN_OF_CAPITAL", "CAPITAL_GAIN_DISTRIBUTION"), "tax category");
    }

    private static long normalizedCash(String event, long amount) {
        return switch (event) {
            case "DEPOSIT", "TRANSFER_IN", "INTEREST", "DIVIDEND" -> Math.abs(amount);
            case "WITHDRAWAL", "TRANSFER_OUT", "FEE" -> -Math.abs(amount);
            case "ADJUSTMENT" -> amount;
            default -> throw new IllegalArgumentException("not a cash event " + event);
        };
    }

    private static long allocate(long amount, long qty, long totalQty) {
        if (qty == totalQty) return amount;
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(qty))
                .divide(BigDecimal.valueOf(totalQty), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long taxAt(long cents, int bps) {
        return BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static String taxLimitNote() {
        return "Estimate only: this does not apply wash-sale deferrals automatically, qualified-covered-call rules, Section 1256 treatment, straddle rules, loss limits, state-specific rules, or filing elections. Reconcile against broker tax forms.";
    }

    private static void validateRate(Integer bps, String label) {
        if (bps != null && (bps < 0 || bps > 10_000)) throw new IllegalArgumentException(label + " must be 0..10000 basis points");
    }

    private OffsetDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        try { return OffsetDateTime.parse(raw); }
        catch (RuntimeException ignored) {
            // Noon UTC preserves the user's calendar date across every ordinary local offset.
            try { return LocalDate.parse(raw).atTime(12, 0).atOffset(ZoneOffset.UTC); }
            catch (RuntimeException e) { throw new IllegalArgumentException("date/time must be ISO-8601"); }
        }
    }

    private static String enumValue(String raw, List<String> values, String label) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(label + " is required");
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!values.contains(value)) throw new IllegalArgumentException(label + " must be one of " + String.join(", ", values));
        return value;
    }

    private static String symbol(String raw) {
        String value = text(raw, "symbol", 20).toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z][A-Z0-9._-]{0,19}")) throw new IllegalArgumentException("invalid symbol " + value);
        return value;
    }

    private static String text(String raw, String label, int max) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException(label + " is required");
        if (value.length() > max) throw new IllegalArgumentException(label + " is too long");
        return value;
    }

    private static String trim(String raw, int max) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.length() > max) throw new IllegalArgumentException("text is too long (max " + max + ")");
        return value;
    }

    private static String owner(String raw) { return raw == null || raw.isBlank() ? "local" : raw; }
    private static String iso(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toString();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a.size() + b.size()); out.addAll(a); out.addAll(b); return out;
    }
}
