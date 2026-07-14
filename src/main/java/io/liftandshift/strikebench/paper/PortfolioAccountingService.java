package io.liftandshift.strikebench.paper;

import com.fasterxml.jackson.core.type.TypeReference;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.MarketLane;
import io.liftandshift.strikebench.market.Universes;
import io.liftandshift.strikebench.model.BroadBasedIndexOptions;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.Json;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Owner-scoped accounting for brokerage activity that a user records or imports. This book is
 * intentionally independent from {@link AccountService}: a real fill, contribution, or tax lot
 * can never mutate practice cash or reserve.
 */
public final class PortfolioAccountingService {

    public static final List<String> ACCOUNT_TYPES = List.of(
            "TAXABLE", "TRADITIONAL_IRA", "ROTH_IRA", "TRADITIONAL_401K", "ROTH_401K");
    public static final List<String> LOT_METHODS = List.of("FIFO", "LIFO", "HIFO");
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    private static final List<String> CASH_EVENTS = List.of(
            "OPENING_BALANCE", "DEPOSIT", "WITHDRAWAL", "TRANSFER_IN", "TRANSFER_OUT", "INTEREST", "DIVIDEND", "FEE", "ADJUSTMENT");
    private static final List<String> MARKET_EVENTS = List.of(
            "TRADE", "ROLL", "EXPIRATION", "ASSIGNMENT", "EXERCISE", "MARK_TO_MARKET");

    private final Db db;
    private final Clock clock;
    private final MarksSource marks;

    public PortfolioAccountingService(Db db, Clock clock) {
        this(db, clock, null);
    }

    public PortfolioAccountingService(Db db, Clock clock, MarksSource marks) {
        this.db = db;
        this.clock = clock;
        this.marks = marks;
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
                           BigDecimal price, Boolean section1256) {
        public LegInput(String instrumentType, String action, String positionEffect,
                        String symbol, String optionType, BigDecimal strike,
                        LocalDate expiration, Long quantity, Integer multiplier,
                        BigDecimal price) {
            this(instrumentType, action, positionEffect, symbol, optionType, strike, expiration,
                    quantity, multiplier, price, null);
        }
    }

    public record TransactionInput(String occurredAt, String eventType, Long cashAmountCents,
                                   Long feesCents, String taxCategory, String source,
                                   String externalRef, String notes, List<LegInput> legs) {}

    public record LegView(int legNo, String instrumentType, String action, String positionEffect,
                          String symbol, String optionType, BigDecimal strike, LocalDate expiration,
                          long quantity, int multiplier, BigDecimal price,
                          long grossAmountCents, long allocatedFeeCents, boolean section1256) {}

    public record RollView(String replacementLotId, long quantity,
                           long closingPremiumCents, long openingPremiumCents,
                           long premiumCarryoverCents, List<Long> realizedMatchIds) {}

    public record TransactionView(String id, String accountId, String occurredAt, String eventType,
                                  long cashEffectCents, long feesCents, String taxCategory,
                                  String source, String externalRef, String notes,
                                  List<LegView> legs, RollView roll) {}

    public record LotView(String id, String instrumentType, String side, String symbol,
                          String optionType, BigDecimal strike, LocalDate expiration,
                          int multiplier, String openedAt, long originalQuantity, long remainingQuantity,
                          long originalOpenAmountCents, long remainingOpenAmountCents,
                          String status, boolean section1256,
                          long economicOriginalOpenAmountCents,
                          long economicRemainingOpenAmountCents) {}

    public record RealizedLotView(long id, String lotId, String symbol, String instrumentType,
                                  String side, long quantity, String openedAt, String closedAt,
                                  long openAmountCents, long closeAmountCents,
                                  long realizedGainCents, String holdingTerm,
                                  long washSaleAdjustmentCents, boolean section1256,
                                  long economicOpenAmountCents,
                                  long economicCloseAmountCents,
                                  long economicRealizedGainCents) {}

    public record ValuationInput(String asOf, Long cashCents, Long securitiesValueCents,
                                 Long totalValueCents, String source, String externalRef,
                                 String notes) {}

    public record ValuationView(String id, String accountId, String asOf, Long cashCents,
                                Long securitiesValueCents, long totalValueCents,
                                String source, String externalRef, String notes,
                                boolean complete, List<String> missingMarks) {}

    public record BenchmarkPoint(String asOf, long portfolioValueCents,
                                 long normalizedBenchmarkValueCents) {}

    public record BenchmarkView(String symbol, Double returnValue, List<BenchmarkPoint> points,
                                String source, String note) {}

    public record CalculatedValuationRun(int accounts, int complete, int partial, int failed) {}

    public record PerformanceView(List<ValuationView> valuations, Long startingValueCents,
                                  Long endingValueCents, long netExternalFlowCents,
                                  Long investmentGainCents, Double modifiedDietzReturn,
                                  Double annualizedReturn, Double timeWeightedReturn,
                                  Double moneyWeightedIrr, Double maxDrawdown,
                                  String drawdownPeakAt, String drawdownTroughAt,
                                  BenchmarkView benchmark, long interestIncomeCents,
                                  long dividendIncomeCents, String note) {}

    public record TaxReport(int year, String accountType, long shortTermGainCents,
                            long longTermGainCents, long ordinaryInterestCents,
                            long ordinaryDividendCents, long qualifiedDividendCents,
                            long capitalGainDistributionCents, long washSaleAdjustmentsCents,
                            long section1256GainCents, long section1256ShortTermCents,
                            long section1256LongTermCents,
                            Long scenarioFederalTaxCents, Long scenarioStateTaxCents,
                            Long scenarioTotalTaxCents, List<RealizedLotView> realizedLots,
                            TaxRules.View rules, String note) {}

    public record PositionView(String key, String instrumentType, String side, String symbol,
                               String optionType, BigDecimal strike, LocalDate expiration,
                               int multiplier, long quantity, long openAmountCents,
                               BigDecimal liquidationPrice, Long liquidationValueCents,
                               Long unrealizedPnlCents, String provenance, String age,
                               String source, boolean complete, boolean section1256) {}

    public record ExposureRow(String key, String label, long longExposureCents,
                              long shortExposureCents, long grossExposureCents,
                              long netExposureCents, Double percentOfKnownGross) {}

    public record AllocationView(long longExposureCents, long shortExposureCents,
                                 long grossExposureCents, long netExposureCents,
                                 List<ExposureRow> byAssetClass, List<ExposureRow> bySector,
                                 List<ExposureRow> byDirection, List<ExposureRow> bySymbol) {}

    public record CollateralView(long knownBlockedCashCents, Long availableCashCents,
                                 long cashSecuredPutContracts, long definedRiskPutContracts,
                                 long coveredCallContracts, long definedRiskCallContracts,
                                 long uncoveredShortCallShares, boolean complete,
                                 List<String> notes) {}

    public record PortfolioSummary(AccountProfile account, long bookCashCents,
                                   Long securitiesLiquidationValueCents, Long totalValueCents,
                                   long realizedPnlCents, Long unrealizedPnlCents,
                                   long interestIncomeCents, long dividendIncomeCents,
                                   long feesCents, long netExternalFlowsCents,
                                   CollateralView collateral, List<PositionView> positions,
                                   AllocationView allocation, List<String> missingMarks,
                                   boolean complete, String valuationBasis) {}

    private record PreparedLeg(int legNo, String instrumentType, String action,
                               String positionEffect, String symbol, String optionType,
                               BigDecimal strike, LocalDate expiration, long quantity,
                               int multiplier, BigDecimal price, long grossAmountCents,
                               long allocatedFeeCents, boolean section1256) {}

    private record LotRow(String id, String accountId, String instrumentType, String side,
                          String symbol, String optionType, BigDecimal strike, LocalDate expiration,
                          int multiplier, String openedAt, String acquiredAt,
                          long originalQuantity, long remainingQuantity,
                          long originalOpenAmount, long remainingOpenAmount,
                          long economicOriginalOpenAmount, long economicRemainingOpenAmount,
                          String status,
                          boolean section1256) {}

    private record WashLoss(long matchId, String lossLotId, String instrumentType, String symbol,
                            String optionType, BigDecimal strike, LocalDate expiration, int multiplier,
                            OffsetDateTime openedAt, OffsetDateTime closedAt, long quantity,
                            long realizedGainCents, long allocatedQuantity, long allocatedCents) {}

    private record WashAllocation(long quantity, String remainingReplacementLotId) {}

    private record CloseResult(long taxOpeningAmountTransferred,
                               long economicOpeningAmountTransferred,
                               List<Long> matchIds) {}

    private record YearEndMark(BigDecimal price, LocalDate asOf, String source) {}

    private record ExternalFlow(OffsetDateTime occurredAt, long cents) {}

    private record ReturnPath(Double timeWeightedReturn, Double maxDrawdown,
                              String peakAt, String troughAt, boolean exactFlowBoundaries,
                              List<Long> normalizedValues) {}

    private record BenchmarkBar(LocalDate date, BigDecimal close, String source,
                                boolean adjusted, int qualityRank) {}

    private record AccountKey(String ownerId, String accountId) {}

    private static final class ExposureBucket {
        private final String key;
        private final String label;
        private long longCents;
        private long shortCents;

        private ExposureBucket(String key, String label) {
            this.key = key;
            this.label = label;
        }

        private void add(long signedCents) {
            if (signedCents >= 0) longCents = Math.addExact(longCents, signedCents);
            else shortCents = Math.addExact(shortCents, absolute(signedCents, "short exposure"));
        }
    }

    private record SummaryLedgerSnapshot(AccountProfile account, List<LotView> openLots,
                                         long cashCents, long realizedCents, long feesCents,
                                         long externalFlowsCents, long interestCents,
                                         long dividendCents) {}

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
                recordOn(c, requireAccount(c, owner, id, true), new TransactionInput(now.toString(), "OPENING_BALANCE",
                        input.openingCashCents(), 0L, null, "MANUAL", "opening-balance", "Opening cash", List.of()), true);
            }
            return requireAccount(c, owner, id, false);
        });
    }

    public AccountProfile updateAccount(String ownerId, String id, AccountInput input) {
        if (input == null) throw new IllegalArgumentException("account details are required");
        String owner = owner(ownerId);
        return db.tx(c -> {
            AccountProfile current = requireAccount(c, owner, id, true);
            requireActive(current);
            String name = input.name() == null ? current.name() : text(input.name(), "account name", 100);
            String type = input.accountType() == null ? current.accountType()
                    : enumValue(input.accountType(), ACCOUNT_TYPES, "account type");
            if (!type.equals(current.accountType())) {
                throw new IllegalArgumentException("account type is fixed after creation; create a separate tracked account for a different tax wrapper");
            }
            String method = input.lotMethod() == null ? current.lotMethod()
                    : enumValue(input.lotMethod(), LOT_METHODS, "lot method");
            // PUT is a full settings replacement. Null rates deliberately clear an estimate input;
            // they never alter lots, basis, or transaction history.
            Integer st = input.shortTermTaxRateBps();
            Integer lt = input.longTermTaxRateBps();
            Integer ordinary = input.ordinaryTaxRateBps();
            Integer state = input.stateTaxRateBps();
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

    public AccountProfile setArchived(String ownerId, String id, boolean archived) {
        String owner = owner(ownerId);
        return db.tx(c -> {
            requireAccount(c, owner, id, true);
            Db.execOn(c, "UPDATE portfolio_account SET status=?,updated_at=? WHERE id=?",
                    archived ? "ARCHIVED" : "ACTIVE",
                    OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), id);
            return requireAccount(c, owner, id, false);
        });
    }

    // ---- Transactions and lots ----

    public TransactionView record(String ownerId, String accountId, TransactionInput input) {
        String owner = owner(ownerId);
        try {
            return db.tx(c -> recordOn(c, requireAccount(c, owner, accountId, true), input));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("transaction amounts exceed the supported range");
        }
    }

    public List<TransactionView> recordBatch(String ownerId, String accountId, List<TransactionInput> inputs) {
        if (inputs == null || inputs.isEmpty()) throw new IllegalArgumentException("at least one transaction is required");
        if (inputs.size() > 100_000) throw new IllegalArgumentException("an import may contain at most 100,000 transactions");
        String owner = owner(ownerId);
        List<TransactionInput> ordered = new ArrayList<>(inputs);
        ordered.sort(Comparator.comparing(input -> parseTime(input == null ? null : input.occurredAt())));
        try {
            return db.tx(c -> {
                AccountProfile account = requireAccount(c, owner, accountId, true);
                List<TransactionView> out = new ArrayList<>(ordered.size());
                for (TransactionInput input : ordered) out.add(recordOn(c, account, input));
                return List.copyOf(out);
            });
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("transaction amounts exceed the supported range");
        }
    }

    private TransactionView recordOn(Connection c, AccountProfile account, TransactionInput input) throws SQLException {
        return recordOn(c, account, input, false);
    }

    private TransactionView recordOn(Connection c, AccountProfile account, TransactionInput input,
                                     boolean allowOpeningBalance) throws SQLException {
        if (!"ACTIVE".equals(account.status())) throw new IllegalStateException("This tracked account is archived and read-only.");
        if (input == null) throw new IllegalArgumentException("transaction details are required");
        String event = enumValue(input.eventType(), concat(CASH_EVENTS, MARKET_EVENTS), "event type");
        if ("OPENING_BALANCE".equals(event) && !allowOpeningBalance) {
            throw new IllegalArgumentException("opening balance is created only when a tracked account is set up");
        }
        OffsetDateTime occurred = parseTime(input.occurredAt());
        requireNotFuture(input.occurredAt(), occurred, "transaction");
        long fees = input.feesCents() == null ? 0 : input.feesCents();
        if (fees < 0) throw new IllegalArgumentException("fees cannot be negative");
        String source = input.source() == null || input.source().isBlank() ? "MANUAL"
                : enumValue(input.source(), List.of("MANUAL", "BROKER", "IMPORT", "CALCULATED"), "source");
        String externalRef = trim(input.externalRef(), 160);
        if ("BROKER".equals(source) && externalRef == null) {
            throw new IllegalArgumentException("broker-sourced activity requires a stable order or statement reference");
        }
        if (externalRef != null) {
            var duplicate = Db.queryOn(c, "SELECT id FROM portfolio_transaction WHERE portfolio_account_id=? AND source=? AND external_ref=?",
                    r -> r.str("id"), account.id(), source, externalRef);
            if (!duplicate.isEmpty()) throw new IllegalStateException("That " + source.toLowerCase(Locale.ROOT)
                    + " reference is already recorded as " + duplicate.getFirst() + ".");
        }
        if (MARKET_EVENTS.contains(event)) requireChronologicalMarketActivity(c, account.id(), occurred);

        List<PreparedLeg> legs = prepareLegs(input.legs(), fees, event,
                occurred.atZoneSameInstant(MARKET_ZONE).toLocalDate());
        if (account.currentlyTaxable() && MARKET_EVENTS.contains(event) && !"MARK_TO_MARKET".equals(event)) {
            int completedYear = occurred.atZoneSameInstant(MARKET_ZONE).getYear() - 1;
            ensureSection1256Through(c, account, completedYear);
        }
        long computedCash = Math.negateExact(fees);
        for (PreparedLeg leg : legs) {
            long signed = "SELL".equals(leg.action()) ? leg.grossAmountCents()
                    : Math.negateExact(leg.grossAmountCents());
            computedCash = Math.addExact(computedCash, signed);
        }
        long cashEffect;
        if (CASH_EVENTS.contains(event)) {
            if (!legs.isEmpty()) throw new IllegalArgumentException(event + " does not take security legs");
            if (input.cashAmountCents() == null) throw new IllegalArgumentException("cash amount is required for " + event);
            if (input.cashAmountCents() == 0) throw new IllegalArgumentException("cash amount cannot be zero for " + event);
            if (fees != 0) throw new IllegalArgumentException("cash-only activity cannot carry separate leg fees; record a FEE event instead");
            cashEffect = normalizedCash(event, input.cashAmountCents());
            if ("FEE".equals(event)) fees = Math.abs(cashEffect);
        } else {
            if (legs.isEmpty()) throw new IllegalArgumentException(event + " requires at least one security leg");
            if (input.cashAmountCents() != null && !withinOneCent(input.cashAmountCents(), computedCash)) {
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
        if ("MARK_TO_MARKET".equals(event)) {
            if (!"CALCULATED".equals(source)) {
                throw new IllegalArgumentException("Section 1256 year-end marks are calculated from an observed stored mark");
            }
            applySection1256Mark(c, account, txId, occurred, legs);
        } else if ("ASSIGNMENT".equals(event) || "EXERCISE".equals(event)) {
            applyOptionConversion(c, account, txId, occurred, event, legs);
        } else if ("ROLL".equals(event)) {
            applyRoll(c, account, txId, occurred, legs);
        } else {
            for (PreparedLeg leg : legs) applyLeg(c, account, txId, occurred, leg, 0);
        }
        return transaction(c, account.id(), txId);
    }

    public List<TransactionView> transactions(String ownerId, String accountId, int page, int size) {
        AccountProfile account = account(ownerId, accountId);
        int bounded = Math.max(1, Math.min(500, size));
        long offset = Math.multiplyExact((long) Math.max(0, page), bounded);
        return db.with(c -> Db.queryOn(c, "SELECT id FROM portfolio_transaction WHERE portfolio_account_id=? "
                        + "ORDER BY occurred_at DESC,record_seq DESC LIMIT ? OFFSET ?", r -> r.str("id"),
                account.id(), bounded, offset).stream().map(id -> {
                    try { return transaction(c, account.id(), id); }
                    catch (SQLException e) { throw new Db.DbException(e); }
                }).toList());
    }

    public List<LotView> lots(String ownerId, String accountId, boolean includeClosed) {
        AccountProfile account = account(ownerId, accountId);
        String sql = "SELECT l.* FROM portfolio_lot l JOIN portfolio_transaction t ON t.id=l.opening_transaction_id "
                + "WHERE l.portfolio_account_id=?" + (includeClosed ? "" : " AND l.status='OPEN'")
                + " ORDER BY l.symbol,l.instrument_type,l.opened_at,t.record_seq,l.id";
        return db.query(sql, PortfolioAccountingService::mapLotView, account.id());
    }

    public List<RealizedLotView> realizedLots(String ownerId, String accountId, int year) {
        AccountProfile account = account(ownerId, accountId);
        return db.query("SELECT m.*,l.symbol,l.instrument_type,l.side FROM portfolio_lot_match m JOIN portfolio_lot l ON l.id=m.lot_id "
                        + "WHERE m.portfolio_account_id=? AND EXTRACT(YEAR FROM (m.closed_at AT TIME ZONE 'America/New_York'))=? "
                        + "ORDER BY m.closed_at,m.id",
                PortfolioAccountingService::mapRealized, account.id(), year);
    }

    // ---- Valuation, performance, and taxes ----

    public ValuationView addValuation(String ownerId, String accountId, ValuationInput input) {
        if (input == null || input.totalValueCents() == null) throw new IllegalArgumentException("total account value is required");
        if (input.totalValueCents() < 0) throw new IllegalArgumentException("total account value cannot be negative");
        OffsetDateTime asOf = parseTime(input.asOf());
        requireNotFuture(input.asOf(), asOf, "valuation");
        if (input.cashCents() != null && input.securitiesValueCents() != null
                && exactValuationTotal(input.cashCents(), input.securitiesValueCents()) != input.totalValueCents()) {
            throw new IllegalArgumentException("cash plus securities value must equal total account value");
        }
        String source = input.source() == null || input.source().isBlank() ? "MANUAL"
                : enumValue(input.source(), List.of("MANUAL", "BROKER", "CALCULATED", "IMPORT"), "valuation source");
        String id = Ids.newId("pval");
        String owner = owner(ownerId);
        return db.tx(c -> {
            AccountProfile account = requireAccount(c, owner, accountId, true);
            requireActive(account);
            Db.execOn(c, "INSERT INTO portfolio_valuation(id,portfolio_account_id,as_of,cash_cents,securities_value_cents,total_value_cents,"
                            + "source,external_ref,notes) VALUES (?,?,?,?,?,?,?,?,?)",
                    id, account.id(), asOf, input.cashCents(), input.securitiesValueCents(), input.totalValueCents(),
                    source, trim(input.externalRef(), 160), trim(input.notes(), 1000));
            return new ValuationView(id, account.id(), asOf.toString(), input.cashCents(), input.securitiesValueCents(),
                    input.totalValueCents(), source, trim(input.externalRef(), 160), trim(input.notes(), 1000),
                    true, List.of());
        });
    }

    /** Records one observed-only, executable-side valuation without touching practice money. */
    public ValuationView recordCalculatedValuation(String ownerId, String accountId) {
        return recordCalculatedValuation(ownerId, accountId, clock.instant());
    }

    ValuationView recordCalculatedValuation(String ownerId, String accountId, Instant instant) {
        if (instant == null || instant.isAfter(clock.instant())) {
            throw new IllegalArgumentException("calculated valuation time must not be in the future");
        }
        String owner = owner(ownerId);
        OffsetDateTime asOf = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        return db.tx(c -> {
            AccountProfile account = requireAccount(c, owner, accountId, true);
            requireActive(account);
            PortfolioSummary summary = assembleSummary(ledgerSnapshot(c, account));
            long knownSecurities = 0;
            for (PositionView position : summary.positions()) {
                if (position.liquidationValueCents() != null) {
                    knownSecurities = Math.addExact(knownSecurities, position.liquidationValueCents());
                }
            }
            long knownTotal = Math.addExact(summary.bookCashCents(), knownSecurities);
            String notes = summary.complete()
                    ? "Observed executable-side account valuation."
                    : "Known subtotal only; missing observed executable marks: " + String.join(", ", summary.missingMarks());
            String id = Ids.newId("pval");
            Db.execOn(c, "INSERT INTO portfolio_valuation(id,portfolio_account_id,as_of,cash_cents,securities_value_cents,total_value_cents,"
                            + "source,external_ref,notes,complete,missing_marks) VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT (portfolio_account_id,source,as_of) DO UPDATE SET cash_cents=EXCLUDED.cash_cents,"
                            + "securities_value_cents=EXCLUDED.securities_value_cents,total_value_cents=EXCLUDED.total_value_cents,"
                            + "notes=EXCLUDED.notes,complete=EXCLUDED.complete,missing_marks=EXCLUDED.missing_marks",
                    id, account.id(), asOf, summary.bookCashCents(), knownSecurities, knownTotal,
                    "CALCULATED", "observed-executable-nav", notes, summary.complete(), Json.write(summary.missingMarks()));
            return Db.queryOn(c, "SELECT * FROM portfolio_valuation WHERE portfolio_account_id=? AND source='CALCULATED' AND as_of=?",
                    PortfolioAccountingService::mapValuation, account.id(), asOf).getFirst();
        });
    }

    /** One scheduler tick across all active owner-scoped books; one failure never drops the others. */
    public CalculatedValuationRun recordActiveCalculatedValuations() {
        List<AccountKey> accounts = db.query("SELECT owner_id,id FROM portfolio_account WHERE status='ACTIVE' ORDER BY owner_id,id",
                r -> new AccountKey(r.str("owner_id"), r.str("id")));
        int complete = 0, partial = 0, failed = 0;
        for (AccountKey account : accounts) {
            try {
                if (recordCalculatedValuation(account.ownerId(), account.accountId()).complete()) complete++;
                else partial++;
            } catch (RuntimeException e) {
                failed++;
            }
        }
        return new CalculatedValuationRun(accounts.size(), complete, partial, failed);
    }

    public PerformanceView performance(String ownerId, String accountId) {
        String owner = owner(ownerId);
        return db.tx(c -> {
            AccountProfile account = requireAccount(c, owner, accountId, true);
            List<ValuationView> values = Db.queryOn(c,
                    "SELECT * FROM portfolio_valuation WHERE portfolio_account_id=? ORDER BY as_of,id",
                    PortfolioAccountingService::mapValuation, account.id());
            List<ValuationView> completeValues = values.stream().filter(ValuationView::complete).toList();
            long interest = incomeOn(c, account.id(), "INTEREST", null);
            long dividends = incomeOn(c, account.id(), "DIVIDEND", null);
            if (completeValues.size() < 2) {
                return new PerformanceView(values,
                        completeValues.isEmpty() ? null : completeValues.getFirst().totalValueCents(),
                        completeValues.isEmpty() ? null : completeValues.getLast().totalValueCents(),
                        0, null, null, null, null, null, null, null, null,
                        unavailableBenchmark("At least two complete valuations are needed to align an observed SPY comparison."),
                        interest, dividends, "At least two complete account valuations are needed. Partial valuations remain visible but never enter a return calculation.");
            }
            OffsetDateTime start = OffsetDateTime.parse(completeValues.getFirst().asOf());
            OffsetDateTime end = OffsetDateTime.parse(completeValues.getLast().asOf());
            double totalSeconds = Math.max(1, Duration.between(start, end).toSeconds());
            List<ExternalFlow> flows = Db.queryOn(c,
                    "SELECT occurred_at,cash_effect_cents "
                            + "FROM portfolio_transaction WHERE portfolio_account_id=? AND occurred_at>? AND occurred_at<=? "
                            + "AND event_type IN ('DEPOSIT','WITHDRAWAL','TRANSFER_IN','TRANSFER_OUT') ORDER BY occurred_at,record_seq",
                    r -> new ExternalFlow(r.odt("occurred_at"), r.lng("cash_effect_cents")),
                    account.id(), start, end);
            long netFlows = 0;
            for (ExternalFlow flow : flows) netFlows = Math.addExact(netFlows, flow.cents());
            double weightedFlows = flows.stream().mapToDouble(f ->
                    f.cents() * (Math.max(0, totalSeconds - Duration.between(start, f.occurredAt()).toSeconds())
                            / totalSeconds)).sum();
            long begin = completeValues.getFirst().totalValueCents();
            long finish = completeValues.getLast().totalValueCents();
            long gain = Math.subtractExact(Math.subtractExact(finish, begin), netFlows);
            double denominator = begin + weightedFlows;
            Double dietz = denominator == 0 ? null : gain / denominator;
            long days = Math.max(1, ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()));
            Double annualized = dietz == null || dietz <= -1 || days < 30 ? null
                    : Math.pow(1 + dietz, 365.0 / days) - 1;
            ReturnPath path = returnPath(completeValues, flows);
            Double irr = xirr(start, begin, end, finish, flows);
            BenchmarkView benchmark = benchmark(c, completeValues, path.normalizedValues());
            String note = "TWR geometrically chains cash-flow-adjusted valuation periods; it is shown only when each external flow has a complete after-flow valuation on that market date. "
                    + "IRR solves the actual dated investor cash flows. Drawdown uses the cash-flow-adjusted wealth path. Modified Dietz remains a disclosed approximation. "
                    + "Partial valuations never enter these metrics.";
            return new PerformanceView(values, begin, finish, netFlows, gain, dietz, annualized,
                    path.timeWeightedReturn(), irr, path.maxDrawdown(), path.peakAt(), path.troughAt(),
                    benchmark, interest, dividends, note);
        });
    }

    private static ReturnPath returnPath(List<ValuationView> values, List<ExternalFlow> flows) {
        boolean exactBoundaries = flows.stream().allMatch(flow -> {
            LocalDate flowDate = flow.occurredAt().atZoneSameInstant(MARKET_ZONE).toLocalDate();
            boolean before = values.stream().map(value -> OffsetDateTime.parse(value.asOf()))
                    .anyMatch(at -> !at.isAfter(flow.occurredAt())
                            && at.atZoneSameInstant(MARKET_ZONE).toLocalDate().equals(flowDate));
            boolean after = values.stream().map(value -> OffsetDateTime.parse(value.asOf()))
                    .anyMatch(at -> !at.isBefore(flow.occurredAt())
                            && at.atZoneSameInstant(MARKET_ZONE).toLocalDate().equals(flowDate));
            return before && after;
        });
        double wealth = 1.0;
        double peak = 1.0;
        double maxDrawdown = 0.0;
        String peakAt = values.getFirst().asOf();
        String drawdownPeakAt = peakAt;
        String troughAt = peakAt;
        boolean valid = true;
        List<Long> normalized = new ArrayList<>();
        normalized.add(values.getFirst().totalValueCents());
        for (int i = 1; i < values.size(); i++) {
            ValuationView previous = values.get(i - 1);
            ValuationView current = values.get(i);
            OffsetDateTime from = OffsetDateTime.parse(previous.asOf());
            OffsetDateTime to = OffsetDateTime.parse(current.asOf());
            long intervalFlows = 0;
            for (ExternalFlow flow : flows) {
                if (flow.occurredAt().isAfter(from) && !flow.occurredAt().isAfter(to)) {
                    intervalFlows = Math.addExact(intervalFlows, flow.cents());
                }
            }
            if (previous.totalValueCents() <= 0) { valid = false; break; }
            double factor = (current.totalValueCents() - (double) intervalFlows) / previous.totalValueCents();
            if (!Double.isFinite(factor) || factor < 0) { valid = false; break; }
            wealth *= factor;
            normalized.add(BigDecimal.valueOf(values.getFirst().totalValueCents())
                    .multiply(BigDecimal.valueOf(wealth)).setScale(0, RoundingMode.HALF_UP).longValueExact());
            if (wealth > peak) {
                peak = wealth;
                peakAt = current.asOf();
            }
            double drawdown = peak == 0 ? 0 : wealth / peak - 1.0;
            if (drawdown < maxDrawdown) {
                maxDrawdown = drawdown;
                drawdownPeakAt = peakAt;
                troughAt = current.asOf();
            }
        }
        return new ReturnPath(valid && exactBoundaries ? wealth - 1.0 : null,
                valid ? maxDrawdown : null, valid ? drawdownPeakAt : null,
                valid ? troughAt : null, exactBoundaries, valid ? List.copyOf(normalized) : List.of());
    }

    private static Double xirr(OffsetDateTime start, long begin, OffsetDateTime end, long finish,
                               List<ExternalFlow> flows) {
        List<ExternalFlow> investorFlows = new ArrayList<>();
        investorFlows.add(new ExternalFlow(start, Math.negateExact(begin)));
        for (ExternalFlow flow : flows) investorFlows.add(new ExternalFlow(flow.occurredAt(), Math.negateExact(flow.cents())));
        investorFlows.add(new ExternalFlow(end, finish));
        boolean positive = investorFlows.stream().anyMatch(f -> f.cents() > 0);
        boolean negative = investorFlows.stream().anyMatch(f -> f.cents() < 0);
        if (!positive || !negative || !end.isAfter(start)) return null;
        double[] candidates = {-0.999999, -0.99, -0.9, -0.5, -0.2, 0, 0.1, 0.25, 0.5, 1, 2, 5, 10, 100, 1_000, 1_000_000};
        double low = candidates[0], lowValue = xnpv(low, start, investorFlows);
        for (int i = 1; i < candidates.length; i++) {
            double high = candidates[i];
            double highValue = xnpv(high, start, investorFlows);
            if (Math.abs(lowValue) < 0.000001) return low;
            if (Double.isFinite(lowValue) && Double.isFinite(highValue) && Math.signum(lowValue) != Math.signum(highValue)) {
                for (int j = 0; j < 200; j++) {
                    double mid = (low + high) / 2.0;
                    double midValue = xnpv(mid, start, investorFlows);
                    if (Math.abs(midValue) < 0.000001) return mid;
                    if (Math.signum(lowValue) == Math.signum(midValue)) {
                        low = mid;
                        lowValue = midValue;
                    } else high = mid;
                }
                return (low + high) / 2.0;
            }
            low = high;
            lowValue = highValue;
        }
        return null;
    }

    private static double xnpv(double rate, OffsetDateTime start, List<ExternalFlow> flows) {
        double base = 1.0 + rate;
        if (base <= 0) return Double.NaN;
        double value = 0;
        for (ExternalFlow flow : flows) {
            double years = Duration.between(start, flow.occurredAt()).toSeconds() / (365.0 * 86_400.0);
            value += flow.cents() / Math.pow(base, years);
        }
        return value;
    }

    private static BenchmarkView benchmark(Connection c, List<ValuationView> values,
                                           List<Long> normalizedPortfolioValues) throws SQLException {
        if (normalizedPortfolioValues.size() != values.size()) {
            return unavailableBenchmark("The account return path is incomplete, so an observed SPY comparison would be misleading.");
        }
        LocalDate start = OffsetDateTime.parse(values.getFirst().asOf()).atZoneSameInstant(MARKET_ZONE).toLocalDate();
        LocalDate end = OffsetDateTime.parse(values.getLast().asOf()).atZoneSameInstant(MARKET_ZONE).toLocalDate();
        List<BenchmarkBar> rows = Db.queryOn(c,
                "SELECT d,close,source,adjusted,quality_rank FROM underlying_bar WHERE symbol='SPY' "
                        + "AND dataset_id='observed' AND observed=1 AND d BETWEEN ? AND ? "
                        + "AND LOWER(source) NOT IN ('fixture','synthetic','simulation','scenario','model') ORDER BY source,adjusted,d",
                r -> new BenchmarkBar(r.date("d"), r.bd("close"), r.str("source"), r.bool("adjusted"), r.intv("quality_rank")),
                start.minusDays(10), end);
        Map<String, List<BenchmarkBar>> candidates = new LinkedHashMap<>();
        for (BenchmarkBar row : rows) {
            candidates.computeIfAbsent(row.source() + "|" + row.adjusted(), ignored -> new ArrayList<>()).add(row);
        }
        List<List<BenchmarkBar>> eligible = candidates.values().stream().filter(series ->
                series.stream().anyMatch(row -> !row.date().isAfter(start))
                        && series.stream().anyMatch(row -> !row.date().isAfter(end))).sorted((a, b) -> {
            LocalDate aEnd = a.getLast().date(), bEnd = b.getLast().date();
            int recency = bEnd.compareTo(aEnd);
            if (recency != 0) return recency;
            int quality = Integer.compare(b.stream().mapToInt(BenchmarkBar::qualityRank).max().orElse(0),
                    a.stream().mapToInt(BenchmarkBar::qualityRank).max().orElse(0));
            return quality != 0 ? quality : Integer.compare(b.size(), a.size());
        }).toList();
        if (eligible.isEmpty()) return unavailableBenchmark("Observed SPY history is unavailable for this valuation window.");
        List<BenchmarkBar> chosen = eligible.getFirst();
        TreeMap<LocalDate, BenchmarkBar> byDate = new TreeMap<>();
        for (BenchmarkBar row : chosen) byDate.put(row.date(), row);
        BenchmarkBar baseline = byDate.floorEntry(start).getValue();
        BenchmarkBar ending = byDate.floorEntry(end).getValue();
        if (ChronoUnit.DAYS.between(baseline.date(), start) > 7
                || ChronoUnit.DAYS.between(ending.date(), end) > 7) {
            return unavailableBenchmark("Observed SPY history does not cover the same valuation window closely enough for an honest comparison.");
        }
        List<BenchmarkPoint> points = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            ValuationView value = values.get(i);
            LocalDate date = OffsetDateTime.parse(value.asOf()).atZoneSameInstant(MARKET_ZONE).toLocalDate();
            var bar = byDate.floorEntry(date);
            if (bar == null) continue;
            long normalized = BigDecimal.valueOf(values.getFirst().totalValueCents()).multiply(bar.getValue().close())
                    .divide(baseline.close(), 0, RoundingMode.HALF_UP).longValueExact();
            points.add(new BenchmarkPoint(value.asOf(), normalizedPortfolioValues.get(i), normalized));
        }
        double benchmarkReturn = ending.close().divide(baseline.close(), 12, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).doubleValue();
        return new BenchmarkView("SPY", benchmarkReturn, points, chosen.getFirst().source(),
                "Observed SPY closes from one coherent source compared with the account's cash-flow-adjusted index; both start at the same account value. No generated or cross-source bars are used.");
    }

    private static BenchmarkView unavailableBenchmark(String note) {
        return new BenchmarkView("SPY", null, List.of(), null, note);
    }

    public TaxReport taxReport(String ownerId, String accountId, int year) {
        if (year < 1970 || year > 9999) throw new IllegalArgumentException("invalid tax year");
        TaxRules.View rules = TaxRules.forYear(year);
        String owner = owner(ownerId);
        return db.tx(c -> {
            AccountProfile account = requireAccount(c, owner, accountId, true);
            if (rules.reviewed()) requireSection1256YearComplete(c, account, year);
            List<RealizedLotView> realized = Db.queryOn(c,
                    "SELECT m.*,l.symbol,l.instrument_type,l.side FROM portfolio_lot_match m "
                            + "JOIN portfolio_lot l ON l.id=m.lot_id WHERE m.portfolio_account_id=? "
                            + "AND EXTRACT(YEAR FROM (m.closed_at AT TIME ZONE 'America/New_York'))=? ORDER BY m.closed_at,m.id",
                    PortfolioAccountingService::mapRealized, account.id(), year);
            long ordinarySt = exactSum(realized.stream().filter(r -> "SHORT_TERM".equals(r.holdingTerm()))
                    .map(r -> Math.addExact(r.realizedGainCents(), r.washSaleAdjustmentCents())).toList(), "short-term gains");
            long ordinaryLt = exactSum(realized.stream().filter(r -> "LONG_TERM".equals(r.holdingTerm()))
                    .map(r -> Math.addExact(r.realizedGainCents(), r.washSaleAdjustmentCents())).toList(), "long-term gains");
            long sectionTotal = exactSum(realized.stream().filter(RealizedLotView::section1256)
                    .map(RealizedLotView::realizedGainCents).toList(), "Section 1256 gains");
            // Character applies to the year's net Section 1256 result. Splitting each match first
            // can move cents between the 60% and 40% buckets as the number of matches changes.
            long sectionLong = percentage(sectionTotal, 60);
            long sectionShort = Math.subtractExact(sectionTotal, sectionLong);
            long st = Math.addExact(ordinarySt, sectionShort);
            long lt = Math.addExact(ordinaryLt, sectionLong);
            long interest = incomeOn(c, account.id(), "INTEREST", year);
            long ordinaryDiv = incomeByCategoryOn(c, account.id(), year, "ORDINARY_DIVIDEND");
            long qualifiedDiv = incomeByCategoryOn(c, account.id(), year, "QUALIFIED_DIVIDEND");
            long capDist = incomeByCategoryOn(c, account.id(), year, "CAPITAL_GAIN_DISTRIBUTION");
            long wash = exactSum(realized.stream().map(RealizedLotView::washSaleAdjustmentCents).toList(),
                    "wash-sale adjustments");
            Long federal = null, state = null, total = null;
            String note;
            if (!account.currentlyTaxable()) {
                note = "No user-rate scenario is calculated for this retirement wrapper. Contributions, withdrawals, conversions, penalties, required distributions, and future distributions follow separate rules. "
                        + taxLimitNote(rules);
            } else if (!rules.userRateScenarioAvailable()) {
                note = "Recorded lots and income remain visible, but the user-rate scenario is withheld because the "
                        + year + " ruleset is " + rules.status().name().toLowerCase() + ". " + taxLimitNote(rules);
            } else if (st < 0 || lt < 0) {
                note = "Basis and realized losses are calculated, but the user-rate scenario is withheld because loss netting, carryovers, and the rest of the tax return are not recorded here. "
                        + taxLimitNote(rules);
            } else if (account.shortTermTaxRateBps() != null && account.longTermTaxRateBps() != null
                    && account.ordinaryTaxRateBps() != null) {
                federal = Math.addExact(taxAt(st, account.shortTermTaxRateBps()),
                        Math.addExact(taxAt(Math.addExact(interest, ordinaryDiv), account.ordinaryTaxRateBps()),
                                taxAt(Math.addExact(lt, Math.addExact(capDist, qualifiedDiv)), account.longTermTaxRateBps())));
                state = account.stateTaxRateBps() == null ? null
                        : taxAt(Math.addExact(Math.addExact(Math.addExact(st, lt), Math.addExact(interest, ordinaryDiv)),
                                Math.addExact(qualifiedDiv, capDist)), account.stateTaxRateBps());
                total = Math.addExact(federal, state == null ? 0 : state);
                note = taxLimitNote(rules);
            } else {
                note = "Basis and holding periods are calculated, but the user-rate scenario needs the account's short-term, long-term, and ordinary-income scenario rates. "
                        + taxLimitNote(rules);
            }
            return new TaxReport(year, account.accountType(), st, lt, interest, ordinaryDiv, qualifiedDiv,
                    capDist, wash, sectionTotal, sectionShort, sectionLong,
                    federal, state, total, realized, rules, note);
        });
    }

    public int markSection1256YearEnd(String ownerId, String accountId, int year) {
        if (year < 1970 || year > 9999) throw new IllegalArgumentException("invalid tax year");
        TaxRules.requireAutomatedYear(year, "Automated Section 1256 year-end marking");
        String owner = owner(ownerId);
        return db.tx(c -> {
            AccountProfile account = requireAccount(c, owner, accountId, true);
            requireActive(account);
            if (!account.currentlyTaxable()) return 0;
            return ensureSection1256Through(c, account, year);
        });
    }

    private int ensureSection1256Through(Connection c, AccountProfile account, int throughYear) throws SQLException {
        int target = Math.min(throughYear, lastCompletedSection1256Year());
        if (target < 1970) return 0;
        List<Long> earliest = Db.queryOn(c,
                "SELECT EXTRACT(YEAR FROM MIN(acquired_at AT TIME ZONE 'America/New_York')) y "
                        + "FROM portfolio_lot WHERE portfolio_account_id=? AND status='OPEN' AND section_1256=1 "
                        + "HAVING COUNT(*)>0",
                r -> r.lng("y"), account.id());
        if (earliest.isEmpty()) return 0;
        int count = 0;
        for (int year = Math.toIntExact(earliest.getFirst()); year <= target; year++) {
            TaxRules.requireAutomatedYear(year, "Automated Section 1256 year-end marking");
            count += ensureSection1256Year(c, account, year);
        }
        return count;
    }

    private void requireSection1256YearComplete(Connection c, AccountProfile account,
                                                int year) throws SQLException {
        if (!account.currentlyTaxable() || year > lastCompletedSection1256Year()) return;
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        OffsetDateTime markTime = yearEnd.atTime(16, 0).atZone(MARKET_ZONE).toOffsetDateTime();
        boolean hasOpen1256 = !Db.queryOn(c,
                "SELECT 1 ok FROM portfolio_lot WHERE portfolio_account_id=? AND status='OPEN' "
                        + "AND section_1256=1 AND acquired_at<=? AND expiration>? LIMIT 1",
                r -> r.intv("ok"), account.id(), markTime, yearEnd).isEmpty();
        boolean marked = !Db.queryOn(c,
                "SELECT 1 ok FROM portfolio_transaction WHERE portfolio_account_id=? "
                        + "AND source='CALCULATED' AND external_ref=? LIMIT 1",
                r -> r.intv("ok"), account.id(), "section-1256-mtm-" + year).isEmpty();
        if (hasOpen1256 && !marked) {
            throw new IllegalStateException("Section 1256 positions still need their observed " + year
                    + " year-end mark. Run the year-end mark command before using this tax report.");
        }
    }

    private int lastCompletedSection1256Year() {
        var now = clock.instant().atZone(MARKET_ZONE);
        return now.isAfter(LocalDate.of(now.getYear(), 12, 31).atTime(16, 0).atZone(MARKET_ZONE))
                ? now.getYear() : now.getYear() - 1;
    }

    private int ensureSection1256Year(Connection c, AccountProfile account, int year) throws SQLException {
        String ref = "section-1256-mtm-" + year;
        if (!Db.queryOn(c, "SELECT 1 ok FROM portfolio_transaction WHERE portfolio_account_id=? "
                        + "AND source='CALCULATED' AND external_ref=?",
                r -> r.intv("ok"), account.id(), ref).isEmpty()) return 0;
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        OffsetDateTime markTime = yearEnd.atTime(16, 0).atZone(MARKET_ZONE).toOffsetDateTime();
        List<LotRow> open = Db.queryOn(c,
                "SELECT * FROM portfolio_lot WHERE portfolio_account_id=? AND status='OPEN' "
                        + "AND section_1256=1 AND acquired_at<=? AND expiration>? ORDER BY symbol,expiration,strike,side,id FOR UPDATE",
                PortfolioAccountingService::mapLot, account.id(), markTime, yearEnd);
        if (open.isEmpty()) return 0;

        Map<String, List<LotRow>> groups = new LinkedHashMap<>();
        for (LotRow lot : open) groups.computeIfAbsent(section1256Key(lot), ignored -> new ArrayList<>()).add(lot);
        List<LegInput> closeLegs = new ArrayList<>();
        List<LegInput> openLegs = new ArrayList<>();
        List<String> receipts = new ArrayList<>();
        for (List<LotRow> group : groups.values()) {
            LotRow first = group.getFirst();
            long quantity = exactSum(group.stream().map(LotRow::remainingQuantity).toList(),
                    "Section 1256 contracts");
            YearEndMark mark = observedSection1256Mark(c, first, yearEnd).orElseThrow(() ->
                    new IllegalStateException("Cannot mark " + positionLabel(first) + " for " + year
                            + ": no observed year-end option mark is stored. Import the broker's year-end mark before recording later market activity."));
            String closeAction = "LONG".equals(first.side()) ? "SELL" : "BUY";
            String openAction = "LONG".equals(first.side()) ? "BUY" : "SELL";
            closeLegs.add(new LegInput("OPTION", closeAction, "CLOSE", first.symbol(), first.optionType(),
                    first.strike(), first.expiration(), quantity, first.multiplier(), mark.price(), true));
            openLegs.add(new LegInput("OPTION", openAction, "OPEN", first.symbol(), first.optionType(),
                    first.strike(), first.expiration(), quantity, first.multiplier(), mark.price(), true));
            receipts.add(first.symbol() + " " + first.expiration() + " @ "
                    + mark.price().stripTrailingZeros().toPlainString() + " (" + mark.source() + " " + mark.asOf() + ")");
        }
        List<LegInput> legs = new ArrayList<>(closeLegs.size() + openLegs.size());
        legs.addAll(closeLegs);
        legs.addAll(openLegs);
        recordOn(c, account, new TransactionInput(markTime.toString(), "MARK_TO_MARKET", 0L, 0L,
                null, "CALCULATED", ref, "Section 1256 year-end mark-to-market: " + String.join("; ", receipts), legs));
        return 1;
    }

    private static Optional<YearEndMark> observedSection1256Mark(Connection c, LotRow lot,
                                                                  LocalDate yearEnd) throws SQLException {
        return Db.queryOn(c,
                "SELECT asof,mark,source FROM option_bar WHERE dataset_id='observed' AND symbol=? "
                        + "AND expiration=? AND strike=? AND opt_type=? AND asof<=? AND asof>=? "
                        + "AND bid_ask_observed=1 AND mark IS NOT NULL AND mark>0 "
                        + "AND LOWER(source) NOT IN ('fixture','model','simulation','scenario') "
                        + "ORDER BY asof DESC,created_at DESC,id DESC LIMIT 1",
                r -> new YearEndMark(r.bd("mark"), r.date("asof"), r.str("source")),
                lot.symbol(), lot.expiration(), lot.strike(), lot.optionType(), yearEnd, yearEnd.minusDays(7))
                .stream().findFirst();
    }

    private static String section1256Key(LotRow lot) {
        return String.join("|", lot.side(), lot.symbol(), lot.optionType(),
                lot.strike().toPlainString(), lot.expiration().toString(), String.valueOf(lot.multiplier()));
    }

    /**
     * Current tracked-book view. Values use executable closing sides (long at bid, short at ask),
     * never a midpoint. A missing mark makes aggregate value/P&L unavailable instead of becoming $0.
     */
    public PortfolioSummary summary(String ownerId, String accountId) {
        String owner = owner(ownerId);
        SummaryLedgerSnapshot ledger = db.tx(c -> ledgerSnapshot(c, requireAccount(c, owner, accountId, true)));
        return assembleSummary(ledger);
    }

    private static SummaryLedgerSnapshot ledgerSnapshot(Connection c, AccountProfile account) throws SQLException {
        List<LotView> openLots = Db.queryOn(c,
                "SELECT l.* FROM portfolio_lot l JOIN portfolio_transaction t ON t.id=l.opening_transaction_id "
                        + "WHERE l.portfolio_account_id=? AND l.status='OPEN' "
                        + "ORDER BY l.symbol,l.instrument_type,l.opened_at,t.record_seq,l.id",
                PortfolioAccountingService::mapLotView, account.id());
        return new SummaryLedgerSnapshot(account, openLots,
                scalarOn(c, "SELECT COALESCE(SUM(cash_effect_cents),0) n FROM portfolio_transaction WHERE portfolio_account_id=?", account.id()),
                scalarOn(c, "SELECT COALESCE(SUM(economic_realized_gain_cents),0) n FROM portfolio_lot_match WHERE portfolio_account_id=?", account.id()),
                scalarOn(c, "SELECT COALESCE(SUM(fees_cents),0) n FROM portfolio_transaction WHERE portfolio_account_id=?", account.id()),
                scalarOn(c, "SELECT COALESCE(SUM(cash_effect_cents),0) n FROM portfolio_transaction WHERE portfolio_account_id=? "
                        + "AND event_type IN ('DEPOSIT','WITHDRAWAL','TRANSFER_IN','TRANSFER_OUT')", account.id()),
                incomeOn(c, account.id(), "INTEREST", null), incomeOn(c, account.id(), "DIVIDEND", null));
    }

    private PortfolioSummary assembleSummary(SummaryLedgerSnapshot ledger) {
        AccountProfile account = ledger.account();
        List<LotView> open = ledger.openLots();
        Map<String, List<LotView>> groups = new LinkedHashMap<>();
        for (LotView lot : open) groups.computeIfAbsent(lotKey(lot), ignored -> new ArrayList<>()).add(lot);

        List<PositionView> positions = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        long knownSecurities = 0;
        long knownUnrealized = 0;
        boolean complete = true;
        for (var entry : groups.entrySet()) {
            List<LotView> lots = entry.getValue();
            LotView first = lots.getFirst();
            long quantity = 0;
            long openAmount = 0;
            for (LotView lot : lots) {
                quantity = Math.addExact(quantity, lot.remainingQuantity());
                openAmount = Math.addExact(openAmount, lot.economicRemainingOpenAmountCents());
            }
            Optional<MarksSource.LegMark> mark = mark(first);
            BigDecimal closePrice = null;
            Long value = null;
            Long unrealized = null;
            DataEvidence evidence = DataEvidence.missing("no current executable mark");
            if (mark.isPresent()) {
                LegAction close = "LONG".equals(first.side()) ? LegAction.SELL : LegAction.BUY;
                closePrice = mark.get().executable(close);
                evidence = mark.get().evidence() == null
                        ? DataEvidence.of(null, mark.get().freshness()) : mark.get().evidence();
                if (closePrice != null) {
                    long absolute = Money.centsFromPrice(closePrice,
                            Math.multiplyExact(quantity, (long) first.multiplier()));
                    value = "LONG".equals(first.side()) ? absolute : Math.negateExact(absolute);
                    unrealized = "LONG".equals(first.side())
                            ? Math.subtractExact(value, openAmount)
                            : Math.addExact(openAmount, value);
                    knownSecurities = Math.addExact(knownSecurities, value);
                    knownUnrealized = Math.addExact(knownUnrealized, unrealized);
                }
            }
            boolean rowComplete = value != null;
            if (!rowComplete) {
                complete = false;
                missing.add(positionLabel(first));
            }
            positions.add(new PositionView(entry.getKey(), first.instrumentType(), first.side(), first.symbol(),
                    first.optionType(), first.strike(), first.expiration(), first.multiplier(), quantity,
                    openAmount, closePrice, value, unrealized,
                    evidence.provenance().name(), evidence.age().name(), evidence.source(), rowComplete,
                    first.section1256()));
        }

        long cash = ledger.cashCents();
        long realized = ledger.realizedCents();
        long fees = ledger.feesCents();
        long flows = ledger.externalFlowsCents();
        CollateralView collateral = collateral(open, cash);
        AllocationView allocation = allocation(positions, cash);
        Long securities = complete ? knownSecurities : null;
        Long total = complete ? Math.addExact(cash, knownSecurities) : null;
        Long unrealized = complete ? knownUnrealized : null;
        return new PortfolioSummary(account, cash, securities, total, realized, unrealized,
                ledger.interestCents(), ledger.dividendCents(), fees, flows,
                collateral, positions, allocation, List.copyOf(missing), complete,
                "Current liquidation uses observed or broker marks at executable closing sides. Demo, simulated, and modeled marks are never applied to tracked external accounts. Fees to close and broker-specific margin rules are not modeled. Missing marks never become zero.");
    }

    private Optional<MarksSource.LegMark> mark(LotView lot) {
        if (marks == null) return Optional.empty();
        Leg leg;
        if ("STOCK".equals(lot.instrumentType())) {
            leg = Leg.stock(LegAction.BUY, 1, BigDecimal.ZERO);
        } else {
            leg = Leg.option(LegAction.BUY, OptionType.valueOf(lot.optionType()), lot.strike(),
                    lot.expiration(), 1, BigDecimal.ZERO);
        }
        return marks.legMark(lot.symbol(), leg).filter(mark -> {
            DataEvidence evidence = mark.evidence() == null
                    ? DataEvidence.of(null, mark.freshness()) : mark.evidence();
            return evidence.executableIn(MarketLane.OBSERVED);
        });
    }

    private CollateralView collateral(List<LotView> lots, long cash) {
        long blocked = 0;
        long cashSecuredPuts = 0, spreadPuts = 0, coveredCalls = 0, spreadCalls = 0;
        long uncoveredCallShares = 0;
        List<String> notes = new ArrayList<>();

        Map<String, Long> freeShares = new LinkedHashMap<>();
        for (LotView lot : lots) if ("STOCK".equals(lot.instrumentType())) {
            long signed = "LONG".equals(lot.side()) ? lot.remainingQuantity() : -lot.remainingQuantity();
            freeShares.merge(lot.symbol(), signed, Math::addExact);
            if ("SHORT".equals(lot.side())) notes.add(lot.symbol() + " includes short shares; broker margin is not modeled.");
        }

        List<CollateralLot> longPuts = collateralLots(lots, "PUT", "LONG");
        List<CollateralLot> longCalls = collateralLots(lots, "CALL", "LONG");
        for (CollateralLot shortPut : collateralLots(lots, "PUT", "SHORT")) {
            long remaining = shortPut.quantity;
            for (CollateralLot protective : longPuts) {
                if (remaining == 0 || !shortPut.sameContractGroup(protective) || protective.quantity == 0) continue;
                long paired = Math.min(remaining, protective.quantity);
                BigDecimal width = shortPut.strike.subtract(protective.strike).max(BigDecimal.ZERO);
                blocked = Math.addExact(blocked, Money.centsFromPrice(width,
                        Math.multiplyExact(paired, (long) shortPut.multiplier)));
                spreadPuts += paired;
                remaining -= paired;
                protective.quantity -= paired;
            }
            if (remaining > 0) {
                blocked = Math.addExact(blocked, Money.centsFromPrice(shortPut.strike,
                        Math.multiplyExact(remaining, (long) shortPut.multiplier)));
                cashSecuredPuts += remaining;
            }
        }

        for (CollateralLot shortCall : collateralLots(lots, "CALL", "SHORT")) {
            long remaining = shortCall.quantity;
            long sharesPerContract = shortCall.multiplier;
            long availableShares = Math.max(0, freeShares.getOrDefault(shortCall.symbol, 0L));
            long byShares = Math.min(remaining, availableShares / sharesPerContract);
            if (byShares > 0) {
                coveredCalls += byShares;
                remaining -= byShares;
                freeShares.put(shortCall.symbol, availableShares - byShares * sharesPerContract);
            }
            for (CollateralLot protective : longCalls) {
                if (remaining == 0 || !shortCall.sameContractGroup(protective) || protective.quantity == 0) continue;
                long paired = Math.min(remaining, protective.quantity);
                BigDecimal width = protective.strike.subtract(shortCall.strike).max(BigDecimal.ZERO);
                blocked = Math.addExact(blocked, Money.centsFromPrice(width,
                        Math.multiplyExact(paired, (long) shortCall.multiplier)));
                spreadCalls += paired;
                remaining -= paired;
                protective.quantity -= paired;
            }
            uncoveredCallShares += Math.multiplyExact(remaining, sharesPerContract);
        }
        boolean complete = uncoveredCallShares == 0 && notes.isEmpty();
        if (uncoveredCallShares > 0) notes.add(uncoveredCallShares
                + " short-call shares are not covered by recorded shares or same-expiration long calls; their broker margin is not estimated.");
        notes.add("Cash blocked is a cash-secured/defined-risk estimate from recorded lots, not a broker margin quote.");
        return new CollateralView(blocked, complete ? Math.subtractExact(cash, blocked) : null,
                cashSecuredPuts, spreadPuts, coveredCalls, spreadCalls, uncoveredCallShares,
                complete, List.copyOf(notes));
    }

    private static List<CollateralLot> collateralLots(List<LotView> lots, String type, String side) {
        Comparator<LotView> byStrike = "PUT".equals(type)
                ? Comparator.comparing(LotView::strike).reversed()
                : Comparator.comparing(LotView::strike);
        return lots.stream().filter(l -> "OPTION".equals(l.instrumentType()) && type.equals(l.optionType())
                        && side.equals(l.side()))
                .sorted(Comparator.comparing(LotView::symbol).thenComparing(LotView::expiration)
                        .thenComparing(byStrike))
                .map(l -> new CollateralLot(l.symbol(), l.expiration(), l.multiplier(), l.strike(), l.remainingQuantity()))
                .toList();
    }

    private static AllocationView allocation(List<PositionView> positions, long cash) {
        Map<String, ExposureBucket> assets = new LinkedHashMap<>();
        Map<String, ExposureBucket> sectors = new LinkedHashMap<>();
        Map<String, ExposureBucket> symbols = new LinkedHashMap<>();
        long longExposure = 0;
        long shortExposure = 0;
        if (cash != 0) {
            addExposure(assets, "CASH", "Cash", cash);
            addExposure(sectors, "CASH", "Cash", cash);
            addExposure(symbols, "CASH", "Cash", cash);
            if (cash > 0) longExposure = Math.addExact(longExposure, cash);
            else shortExposure = Math.addExact(shortExposure, absolute(cash, "cash exposure"));
        }
        for (PositionView p : positions) if (p.liquidationValueCents() != null) {
            long value = p.liquidationValueCents();
            addExposure(assets, p.instrumentType(), "STOCK".equals(p.instrumentType()) ? "Stocks" : "Options", value);
            String sector = Universes.allocationSectorLabel(p.symbol());
            addExposure(sectors, sector.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_"), sector, value);
            addExposure(symbols, p.symbol(), p.symbol(), value);
            if (value >= 0) longExposure = Math.addExact(longExposure, value);
            else shortExposure = Math.addExact(shortExposure, absolute(value, "short exposure"));
        }
        long gross = Math.addExact(longExposure, shortExposure);
        long net = Math.subtractExact(longExposure, shortExposure);
        ExposureBucket longBucket = new ExposureBucket("LONG", "Long");
        longBucket.add(longExposure);
        ExposureBucket shortBucket = new ExposureBucket("SHORT", "Short");
        shortBucket.add(Math.negateExact(shortExposure));
        List<ExposureRow> directions = List.of(
                exposureRow(longBucket, gross), exposureRow(shortBucket, gross));
        return new AllocationView(longExposure, shortExposure, gross, net,
                exposureRows(assets, gross), exposureRows(sectors, gross), directions, exposureRows(symbols, gross));
    }

    private static void addExposure(Map<String, ExposureBucket> groups, String key, String label, long signedCents) {
        groups.computeIfAbsent(key, ignored -> new ExposureBucket(key, label)).add(signedCents);
    }

    private static List<ExposureRow> exposureRows(Map<String, ExposureBucket> groups, long knownGross) {
        return groups.values().stream().map(bucket -> exposureRow(bucket, knownGross))
                .sorted(Comparator.comparingLong(ExposureRow::grossExposureCents).reversed()
                        .thenComparing(ExposureRow::label)).toList();
    }

    private static ExposureRow exposureRow(ExposureBucket bucket, long knownGross) {
        long gross = Math.addExact(bucket.longCents, bucket.shortCents);
        long net = Math.subtractExact(bucket.longCents, bucket.shortCents);
        return new ExposureRow(bucket.key, bucket.label, bucket.longCents, bucket.shortCents, gross, net,
                knownGross == 0 ? null : gross / (double) knownGross);
    }

    private static long scalarOn(Connection c, String sql, Object... args) throws SQLException {
        return Db.queryOn(c, sql, r -> r.lng("n"), args).getFirst();
    }

    private static String lotKey(LotView lot) {
        return String.join("|", lot.instrumentType(), lot.side(), lot.symbol(),
                String.valueOf(lot.optionType()), String.valueOf(lot.strike()),
                String.valueOf(lot.expiration()), String.valueOf(lot.multiplier()));
    }

    private static String positionLabel(LotView lot) {
        if ("STOCK".equals(lot.instrumentType())) return lot.symbol() + " shares";
        return lot.symbol() + " " + lot.expiration() + " " + lot.strike().stripTrailingZeros().toPlainString()
                + " " + lot.optionType().toLowerCase(Locale.ROOT);
    }

    private static String positionLabel(LotRow lot) {
        return lot.symbol() + " " + lot.expiration() + " "
                + lot.strike().stripTrailingZeros().toPlainString() + " "
                + lot.optionType().toLowerCase(Locale.ROOT);
    }

    private static final class CollateralLot {
        private final String symbol;
        private final LocalDate expiration;
        private final int multiplier;
        private final BigDecimal strike;
        private long quantity;

        private CollateralLot(String symbol, LocalDate expiration, int multiplier, BigDecimal strike, long quantity) {
            this.symbol = symbol;
            this.expiration = expiration;
            this.multiplier = multiplier;
            this.strike = strike;
            this.quantity = quantity;
        }

        private boolean sameContractGroup(CollateralLot other) {
            return symbol.equals(other.symbol) && expiration.equals(other.expiration) && multiplier == other.multiplier;
        }
    }

    // ---- Lot application ----

    private void applyLeg(Connection c, AccountProfile account, String txId, OffsetDateTime occurred,
                          PreparedLeg leg, long amountAdjustment) throws SQLException {
        applyLeg(c, account, txId, occurred, leg, amountAdjustment, amountAdjustment);
    }

    private void applyLeg(Connection c, AccountProfile account, String txId, OffsetDateTime occurred,
                          PreparedLeg leg, long taxAmountAdjustment,
                          long economicAmountAdjustment) throws SQLException {
        if ("OPEN".equals(leg.positionEffect())) {
            openLot(c, account, txId, occurred, leg, taxAmountAdjustment, economicAmountAdjustment);
        } else {
            if (taxAmountAdjustment != economicAmountAdjustment) {
                throw new IllegalArgumentException("closing adjustments must reconcile to one recorded cash amount");
            }
            closeLots(c, account, txId, occurred, leg,
                    closingAmount(leg) + taxAmountAdjustment, false);
        }
    }

    private String openLot(Connection c, AccountProfile account, String txId, OffsetDateTime occurred,
                           PreparedLeg leg, long amountAdjustment) throws SQLException {
        return openLot(c, account, txId, occurred, leg, amountAdjustment, amountAdjustment);
    }

    private String openLot(Connection c, AccountProfile account, String txId, OffsetDateTime occurred,
                           PreparedLeg leg, long taxAmountAdjustment,
                           long economicAmountAdjustment) throws SQLException {
        long taxAmount = Math.addExact(openingAmount(leg), taxAmountAdjustment);
        long economicAmount = Math.addExact(openingAmount(leg), economicAmountAdjustment);
        if (taxAmount < 0 || economicAmount < 0) {
            throw new IllegalArgumentException("opening basis/proceeds became negative after assignment adjustment");
        }
        String lotId = insertLot(c, account.id(), txId, occurred, leg, taxAmount, economicAmount);
        if (account.currentlyTaxable() && "BUY".equals(leg.action())) {
            applyPriorLossesToReplacement(c, account, lotId);
        }
        return lotId;
    }

    private void applySection1256Mark(Connection c, AccountProfile account, String txId,
                                      OffsetDateTime occurred, List<PreparedLeg> legs) throws SQLException {
        if (legs.isEmpty() || legs.size() % 2 != 0 || legs.stream().anyMatch(l ->
                !"OPTION".equals(l.instrumentType()) || !l.section1256())) {
            throw new IllegalArgumentException("A Section 1256 mark requires paired close and reopen option legs");
        }
        Map<String, PreparedLeg> opens = new LinkedHashMap<>();
        for (PreparedLeg leg : legs) if ("OPEN".equals(leg.positionEffect())) {
            if (opens.put(markContractKey(leg), leg) != null) {
                throw new IllegalArgumentException("A Section 1256 mark contains a duplicate reopen contract");
            }
        }
        int closed = 0;
        for (PreparedLeg close : legs) if ("CLOSE".equals(close.positionEffect())) {
            PreparedLeg open = opens.remove(markContractKey(close));
            if (open == null || close.quantity() != open.quantity()
                    || close.action().equals(open.action())
                    || close.price().compareTo(open.price()) != 0) {
                throw new IllegalArgumentException("Each Section 1256 close must have an equal, opposite reopen at the same observed mark");
            }
            CloseResult result = closeLots(c, account, txId, occurred, close,
                    closingAmount(close), false, true);
            long taxAmount = openingAmount(open);
            insertLot(c, account.id(), txId, occurred, open, taxAmount,
                    result.economicOpeningAmountTransferred());
            closed++;
        }
        if (closed == 0 || !opens.isEmpty()) {
            throw new IllegalArgumentException("A Section 1256 mark requires one close and one reopen per contract");
        }
    }

    private static String markContractKey(PreparedLeg leg) {
        String side = "OPEN".equals(leg.positionEffect())
                ? ("BUY".equals(leg.action()) ? "LONG" : "SHORT")
                : ("BUY".equals(leg.action()) ? "SHORT" : "LONG");
        return String.join("|", side, leg.symbol(), leg.optionType(), leg.strike().toPlainString(),
                leg.expiration().toString(), String.valueOf(leg.multiplier()));
    }

    private void applyRoll(Connection c, AccountProfile account, String txId,
                           OffsetDateTime occurred, List<PreparedLeg> legs) throws SQLException {
        if (legs.size() != 2 || legs.stream().anyMatch(l -> !"OPTION".equals(l.instrumentType()))) {
            throw new IllegalArgumentException("A roll requires exactly one closing option leg and one replacement option leg.");
        }
        PreparedLeg close = legs.stream().filter(l -> "CLOSE".equals(l.positionEffect())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("A roll requires one closing option leg."));
        PreparedLeg open = legs.stream().filter(l -> "OPEN".equals(l.positionEffect())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("A roll requires one replacement option leg."));
        if (legs.stream().filter(l -> "CLOSE".equals(l.positionEffect())).count() != 1
                || legs.stream().filter(l -> "OPEN".equals(l.positionEffect())).count() != 1) {
            throw new IllegalArgumentException("A roll requires exactly one close and one replacement open.");
        }
        if (!close.symbol().equals(open.symbol()) || !close.optionType().equals(open.optionType())
                || close.quantity() != open.quantity() || close.multiplier() != open.multiplier()
                || close.section1256() != open.section1256()) {
            throw new IllegalArgumentException("A roll must keep the same symbol, option type, quantity, and multiplier.");
        }
        String closingSide = "BUY".equals(close.action()) ? "SHORT" : "LONG";
        String openingSide = "BUY".equals(open.action()) ? "LONG" : "SHORT";
        if (!closingSide.equals(openingSide)) {
            throw new IllegalArgumentException("A roll must replace the position on the same long or short side.");
        }
        if (close.strike().compareTo(open.strike()) == 0 && close.expiration().equals(open.expiration())) {
            throw new IllegalArgumentException("A replacement contract must change the strike or expiration.");
        }
        CloseResult closed = closeLots(c, account, txId, occurred, close, closingAmount(close), false);
        String replacementLotId = openLot(c, account, txId, occurred, open, 0);
        long closingPremium = signedPremium(close);
        long openingPremium = signedPremium(open);
        long carryover = Math.addExact(closingPremium, openingPremium);
        Db.execOn(c, "INSERT INTO portfolio_roll(transaction_id,portfolio_account_id,closing_leg_no,opening_leg_no,"
                        + "replacement_lot_id,quantity,closing_premium_cents,opening_premium_cents,premium_carryover_cents) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                txId, account.id(), close.legNo(), open.legNo(), replacementLotId, open.quantity(),
                closingPremium, openingPremium, carryover);
        for (long matchId : closed.matchIds()) {
            Db.execOn(c, "INSERT INTO portfolio_roll_match(transaction_id,lot_match_id) VALUES (?,?)", txId, matchId);
        }
    }

    private static long signedPremium(PreparedLeg leg) {
        return "SELL".equals(leg.action()) ? leg.grossAmountCents() : Math.negateExact(leg.grossAmountCents());
    }

    private void applyOptionConversion(Connection c, AccountProfile account, String txId,
                                       OffsetDateTime occurred, String event,
                                       List<PreparedLeg> legs) throws SQLException {
        if (legs.size() != 2) throw new IllegalArgumentException(event + " requires exactly one closing option leg and one stock leg");
        PreparedLeg option = legs.stream().filter(l -> "OPTION".equals(l.instrumentType())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(event + " requires an option leg"));
        PreparedLeg stock = legs.stream().filter(l -> "STOCK".equals(l.instrumentType())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(event + " requires a stock leg"));
        if (option.section1256()) {
            throw new IllegalArgumentException("Section 1256 broad-based index options are cash-settled and cannot deliver shares. "
                    + "Record the exact cash settlement as a closing option transaction instead.");
        }
        if (!"CLOSE".equals(option.positionEffect()) || option.price().signum() != 0) {
            throw new IllegalArgumentException(event + " option leg must CLOSE at $0; its premium transfers into stock basis/proceeds");
        }
        long deliverable = Math.multiplyExact(option.quantity(), (long) option.multiplier());
        if (!option.symbol().equals(stock.symbol()) || stock.quantity() != deliverable) {
            throw new IllegalArgumentException(event + " stock quantity must equal option contracts x multiplier for the same symbol");
        }
        if (stock.price().compareTo(option.strike()) != 0) {
            throw new IllegalArgumentException(event + " stock price must equal the option strike");
        }
        String expectedSide = "ASSIGNMENT".equals(event) ? "SHORT" : "LONG";
        String closingSide = "BUY".equals(option.action()) ? "SHORT" : "LONG";
        if (!expectedSide.equals(closingSide)) throw new IllegalArgumentException(event + " must close a " + expectedSide.toLowerCase(Locale.ROOT) + " option lot");
        CloseResult closed = closeLots(c, account, txId, occurred, option, 0, true);
        boolean put = "PUT".equals(option.optionType());
        long taxAdjustment;
        long economicAdjustment;
        if (("SHORT".equals(expectedSide) && put) || ("LONG".equals(expectedSide) && !put)) {
            if (!"BUY".equals(stock.action())) {
                throw new IllegalArgumentException(event + " of this option requires a BUY stock leg");
            }
            taxAdjustment = "SHORT".equals(expectedSide)
                    ? -closed.taxOpeningAmountTransferred() : closed.taxOpeningAmountTransferred();
            economicAdjustment = "SHORT".equals(expectedSide)
                    ? -closed.economicOpeningAmountTransferred() : closed.economicOpeningAmountTransferred();
        } else {
            if (!"SELL".equals(stock.action())) {
                throw new IllegalArgumentException(event + " of this option requires a SELL stock leg");
            }
            taxAdjustment = "SHORT".equals(expectedSide)
                    ? closed.taxOpeningAmountTransferred() : -closed.taxOpeningAmountTransferred();
            economicAdjustment = "SHORT".equals(expectedSide)
                    ? closed.economicOpeningAmountTransferred() : -closed.economicOpeningAmountTransferred();
        }
        applyLeg(c, account, txId, occurred, stock, taxAdjustment, economicAdjustment);
    }

    /** Returns opening basis/proceeds consumed. In roll mode the option itself realizes $0. */
    private CloseResult closeLots(Connection c, AccountProfile account, String txId, OffsetDateTime occurred,
                                  PreparedLeg leg, long totalCloseAmount, boolean rolled) throws SQLException {
        return closeLots(c, account, txId, occurred, leg, totalCloseAmount, rolled, false);
    }

    private CloseResult closeLots(Connection c, AccountProfile account, String txId, OffsetDateTime occurred,
                                  PreparedLeg leg, long totalCloseAmount, boolean rolled,
                                  boolean taxOnly) throws SQLException {
        String side = "BUY".equals(leg.action()) ? "SHORT" : "LONG";
        List<LotRow> candidates = matchingLots(c, account, leg, side);
        long available = 0;
        for (LotRow candidate : candidates) available = Math.addExact(available, candidate.remainingQuantity());
        if (available < leg.quantity()) {
            throw new IllegalStateException("Cannot close " + leg.quantity() + " " + leg.symbol() + " "
                    + leg.instrumentType().toLowerCase(Locale.ROOT) + " units; only " + available + " matching "
                    + side.toLowerCase(Locale.ROOT) + " units are open.");
        }
        long remainingQty = leg.quantity();
        long remainingClose = totalCloseAmount;
        long transferredTax = 0;
        long transferredEconomic = 0;
        List<Long> matchIds = new ArrayList<>();
        for (LotRow lot : candidates) {
            if (remainingQty == 0) break;
            long qty = Math.min(remainingQty, lot.remainingQuantity());
            long openPart = allocate(lot.remainingOpenAmount(), qty, lot.remainingQuantity());
            long economicOpenPart = allocate(lot.economicRemainingOpenAmount(), qty, lot.remainingQuantity());
            long closePart = remainingQty == qty ? remainingClose : allocate(remainingClose, qty, remainingQty);
            long realized = rolled ? 0 : "LONG".equals(side)
                    ? Math.subtractExact(closePart, openPart) : Math.subtractExact(openPart, closePart);
            long economicClosePart = rolled || taxOnly ? economicOpenPart : closePart;
            long economicRealized = rolled || taxOnly ? 0 : "LONG".equals(side)
                    ? Math.subtractExact(economicClosePart, economicOpenPart)
                    : Math.subtractExact(economicOpenPart, economicClosePart);
            String term = rolled ? "ROLLED" : lot.section1256() ? "SECTION_1256" : holdingTerm(lot, occurred);
            long matchId = Db.insertReturningId(c, "INSERT INTO portfolio_lot_match(portfolio_account_id,lot_id,closing_transaction_id,closing_leg_no,"
                            + "quantity,opened_at,closed_at,open_amount_cents,close_amount_cents,realized_gain_cents,holding_term,section_1256,"
                            + "economic_open_amount_cents,economic_close_amount_cents,economic_realized_gain_cents) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    account.id(), lot.id(), txId, leg.legNo(), qty, OffsetDateTime.parse(lot.openedAt()), occurred,
                    openPart, rolled ? openPart : closePart, realized, term, lot.section1256(),
                    economicOpenPart, economicClosePart, economicRealized);
            matchIds.add(matchId);
            long lotQtyAfter = Math.subtractExact(lot.remainingQuantity(), qty);
            long lotAmountAfter = Math.subtractExact(lot.remainingOpenAmount(), openPart);
            long lotEconomicAfter = Math.subtractExact(lot.economicRemainingOpenAmount(), economicOpenPart);
            Db.execOn(c, "UPDATE portfolio_lot SET remaining_quantity=?,remaining_open_amount_cents=?,"
                            + "economic_remaining_open_amount_cents=?,status=? WHERE id=?",
                    lotQtyAfter, lotAmountAfter, lotEconomicAfter,
                    lotQtyAfter == 0 ? (rolled ? "ROLLED" : "CLOSED") : "OPEN", lot.id());
            if (account.currentlyTaxable() && !lot.section1256() && !rolled && "LONG".equals(side) && realized < 0) {
                applyExistingReplacementsToLoss(c, account, matchId);
            }
            remainingQty = Math.subtractExact(remainingQty, qty);
            remainingClose = Math.subtractExact(remainingClose, closePart);
            transferredTax = Math.addExact(transferredTax, openPart);
            transferredEconomic = Math.addExact(transferredEconomic, economicOpenPart);
        }
        return new CloseResult(transferredTax, transferredEconomic, List.copyOf(matchIds));
    }

    private void applyExistingReplacementsToLoss(Connection c, AccountProfile account,
                                                  long matchId) throws SQLException {
        WashLoss loss = washLoss(c, matchId);
        if (!reviewedWashLoss(loss) || loss.realizedGainCents() >= 0
                || loss.allocatedQuantity() >= loss.quantity()) return;
        List<String> replacements = Db.queryOn(c,
                "SELECT l.id FROM portfolio_lot l JOIN portfolio_transaction t ON t.id=l.opening_transaction_id "
                        + "WHERE l.portfolio_account_id=? AND l.side='LONG' AND l.remaining_quantity>0 "
                        + "AND l.id<>? AND l.instrument_type=? AND l.symbol=? "
                        + "AND l.option_type IS NOT DISTINCT FROM ? AND l.strike IS NOT DISTINCT FROM ? "
                        + "AND l.expiration IS NOT DISTINCT FROM ? AND l.multiplier=? AND l.section_1256=? "
                        + "AND l.acquired_at>=? AND l.acquired_at<=? "
                        + "AND NOT EXISTS (SELECT 1 FROM portfolio_wash_sale_allocation w WHERE w.replacement_lot_id=l.id) "
                        + "ORDER BY l.acquired_at,t.record_seq,l.id FOR UPDATE OF l",
                r -> r.str("id"), account.id(), loss.lossLotId(), loss.instrumentType(), loss.symbol(),
                loss.optionType(), loss.strike(), loss.expiration(), loss.multiplier(), false,
                loss.closedAt().minusDays(30), loss.closedAt().plusDays(30));
        for (String replacementId : replacements) {
            loss = washLoss(c, matchId);
            long needed = Math.subtractExact(loss.quantity(), loss.allocatedQuantity());
            if (needed == 0) break;
            LotRow replacement = lot(c, replacementId, true);
            allocateWash(c, account, loss, replacement, Math.min(needed, replacement.remainingQuantity()));
        }
    }

    private void applyPriorLossesToReplacement(Connection c, AccountProfile account,
                                                String replacementLotId) throws SQLException {
        LotRow replacement = lot(c, replacementLotId, true);
        if (!"LONG".equals(replacement.side())) return;
        OffsetDateTime acquired = OffsetDateTime.parse(replacement.acquiredAt());
        List<Long> losses = Db.queryOn(c,
                "SELECT m.id FROM portfolio_lot_match m JOIN portfolio_lot l ON l.id=m.lot_id "
                        + "WHERE m.portfolio_account_id=? AND m.realized_gain_cents<0 AND l.side='LONG' "
                        + "AND l.instrument_type=? AND l.symbol=? "
                        + "AND l.option_type IS NOT DISTINCT FROM ? AND l.strike IS NOT DISTINCT FROM ? "
                        + "AND l.expiration IS NOT DISTINCT FROM ? AND l.multiplier=? AND l.section_1256=0 "
                        + "AND m.closed_at>=? AND m.closed_at<=? "
                        + "AND COALESCE((SELECT SUM(w.quantity) FROM portfolio_wash_sale_allocation w "
                        + "WHERE w.loss_match_id=m.id),0)<m.quantity "
                        + "ORDER BY m.closed_at,m.id FOR UPDATE OF m",
                r -> r.lng("id"), account.id(), replacement.instrumentType(), replacement.symbol(),
                replacement.optionType(), replacement.strike(), replacement.expiration(), replacement.multiplier(),
                acquired.minusDays(30), acquired);
        String availableLotId = replacementLotId;
        for (Long matchId : losses) {
            if (availableLotId == null) break;
            WashLoss loss = washLoss(c, matchId);
            if (!reviewedWashLoss(loss)) continue;
            LotRow available = lot(c, availableLotId, true);
            long needed = Math.subtractExact(loss.quantity(), loss.allocatedQuantity());
            WashAllocation allocation = allocateWash(c, account, loss, available,
                    Math.min(needed, available.remainingQuantity()));
            availableLotId = allocation.remainingReplacementLotId();
        }
    }

    private WashAllocation allocateWash(Connection c, AccountProfile account, WashLoss loss,
                                         LotRow replacement, long quantity) throws SQLException {
        if (!account.currentlyTaxable() || quantity <= 0) return new WashAllocation(0, replacement.id());
        long remainingLossQuantity = Math.subtractExact(loss.quantity(), loss.allocatedQuantity());
        long totalAdjustment = Math.negateExact(loss.realizedGainCents());
        long remainingAdjustment = Math.subtractExact(totalAdjustment, loss.allocatedCents());
        long adjustment = quantity == remainingLossQuantity ? remainingAdjustment
                : allocate(remainingAdjustment, quantity, remainingLossQuantity);
        if (adjustment <= 0) return new WashAllocation(0, replacement.id());

        String adjustedLotId = replacement.id();
        String unallocatedLotId = null;
        boolean mustSplit = quantity < replacement.remainingQuantity()
                || replacement.remainingQuantity() < replacement.originalQuantity();
        if (mustSplit) {
            long basisPart = allocate(replacement.remainingOpenAmount(), quantity, replacement.remainingQuantity());
            long economicBasisPart = allocate(replacement.economicRemainingOpenAmount(), quantity,
                    replacement.remainingQuantity());
            long sourceOriginalQuantity = Math.subtractExact(replacement.originalQuantity(), quantity);
            long sourceRemainingQuantity = Math.subtractExact(replacement.remainingQuantity(), quantity);
            long sourceOriginalAmount = Math.subtractExact(replacement.originalOpenAmount(), basisPart);
            long sourceRemainingAmount = Math.subtractExact(replacement.remainingOpenAmount(), basisPart);
            long sourceEconomicOriginalAmount = Math.subtractExact(
                    replacement.economicOriginalOpenAmount(), economicBasisPart);
            long sourceEconomicRemainingAmount = Math.subtractExact(
                    replacement.economicRemainingOpenAmount(), economicBasisPart);
            Db.execOn(c, "UPDATE portfolio_lot SET original_quantity=?,remaining_quantity=?,"
                            + "original_open_amount_cents=?,remaining_open_amount_cents=?,"
                            + "economic_original_open_amount_cents=?,economic_remaining_open_amount_cents=?,"
                            + "status=? WHERE id=?",
                    sourceOriginalQuantity, sourceRemainingQuantity, sourceOriginalAmount, sourceRemainingAmount,
                    sourceEconomicOriginalAmount, sourceEconomicRemainingAmount,
                    sourceRemainingQuantity == 0 ? "CLOSED" : "OPEN", replacement.id());
            Object[] opening = Db.queryOn(c,
                    "SELECT opening_transaction_id,opening_leg_no FROM portfolio_lot WHERE id=?",
                    r -> new Object[]{r.str("opening_transaction_id"), r.intv("opening_leg_no")},
                    replacement.id()).getFirst();
            adjustedLotId = Ids.newId("plot");
            Db.execOn(c, "INSERT INTO portfolio_lot(id,portfolio_account_id,opening_transaction_id,opening_leg_no,"
                            + "instrument_type,side,symbol,option_type,strike,expiration,opened_at,acquired_at,"
                            + "original_quantity,remaining_quantity,original_open_amount_cents,remaining_open_amount_cents,"
                            + "economic_original_open_amount_cents,economic_remaining_open_amount_cents,"
                            + "multiplier,section_1256,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'OPEN')",
                    adjustedLotId, replacement.accountId(), opening[0], opening[1], replacement.instrumentType(),
                    replacement.side(), replacement.symbol(), replacement.optionType(), replacement.strike(),
                    replacement.expiration(), OffsetDateTime.parse(replacement.openedAt()),
                    OffsetDateTime.parse(replacement.acquiredAt()), quantity, quantity, basisPart, basisPart,
                    economicBasisPart, economicBasisPart,
                    replacement.multiplier(), replacement.section1256());
            if (sourceRemainingQuantity > 0) unallocatedLotId = replacement.id();
        }

        LotRow adjusted = lot(c, adjustedLotId, true);
        OffsetDateTime carriedOpen = OffsetDateTime.parse(adjusted.openedAt()).isBefore(loss.openedAt())
                ? OffsetDateTime.parse(adjusted.openedAt()) : loss.openedAt();
        Db.execOn(c, "UPDATE portfolio_lot SET opened_at=?,original_open_amount_cents=?,"
                        + "remaining_open_amount_cents=? WHERE id=?",
                carriedOpen, Math.addExact(adjusted.originalOpenAmount(), adjustment),
                Math.addExact(adjusted.remainingOpenAmount(), adjustment), adjustedLotId);
        Db.execOn(c, "INSERT INTO portfolio_wash_sale_allocation(portfolio_account_id,loss_match_id,"
                        + "replacement_lot_id,quantity,adjustment_cents,ruleset_id,classification_status) "
                        + "VALUES (?,?,?,?,?,?,?)",
                account.id(), loss.matchId(), adjustedLotId, quantity, adjustment,
                TaxRules.RULESET_ID, "MODELED_COMMON_CASE");
        Db.execOn(c, "UPDATE portfolio_lot_match SET wash_sale_adjustment_cents="
                        + "wash_sale_adjustment_cents+? WHERE id=?",
                adjustment, loss.matchId());
        return new WashAllocation(quantity, unallocatedLotId);
    }

    private static boolean reviewedWashLoss(WashLoss loss) {
        int year = loss.closedAt().atZoneSameInstant(MARKET_ZONE).getYear();
        return TaxRules.forYear(year).reviewed();
    }

    private static WashLoss washLoss(Connection c, long matchId) throws SQLException {
        return Db.queryOn(c,
                "SELECT m.id match_id,m.lot_id,l.instrument_type,l.symbol,l.option_type,l.strike,l.expiration,"
                        + "l.multiplier,m.opened_at,m.closed_at,m.quantity,m.realized_gain_cents,"
                        + "COALESCE((SELECT SUM(w.quantity) FROM portfolio_wash_sale_allocation w "
                        + "WHERE w.loss_match_id=m.id),0) allocated_quantity,"
                        + "COALESCE((SELECT SUM(w.adjustment_cents) FROM portfolio_wash_sale_allocation w "
                        + "WHERE w.loss_match_id=m.id),0) allocated_cents "
                        + "FROM portfolio_lot_match m JOIN portfolio_lot l ON l.id=m.lot_id WHERE m.id=?",
                r -> new WashLoss(r.lng("match_id"), r.str("lot_id"), r.str("instrument_type"),
                        r.str("symbol"), r.str("option_type"), r.bd("strike"), r.date("expiration"),
                        r.intv("multiplier"), r.odt("opened_at"), r.odt("closed_at"), r.lng("quantity"),
                        r.lng("realized_gain_cents"), r.lng("allocated_quantity"), r.lng("allocated_cents")),
                matchId).getFirst();
    }

    private static LotRow lot(Connection c, String lotId, boolean lock) throws SQLException {
        return Db.queryOn(c, "SELECT * FROM portfolio_lot WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                PortfolioAccountingService::mapLot, lotId).getFirst();
    }

    private List<LotRow> matchingLots(Connection c, AccountProfile account, PreparedLeg leg, String side) throws SQLException {
        String order = switch (account.lotMethod()) {
            case "LIFO" -> "l.opened_at DESC,t.record_seq DESC,l.id DESC";
            case "HIFO" -> "(l.remaining_open_amount_cents::numeric/l.remaining_quantity) "
                    + ("SHORT".equals(side) ? "ASC" : "DESC") + ",l.opened_at,t.record_seq,l.id";
            default -> "l.opened_at,t.record_seq,l.id";
        };
        return Db.queryOn(c, "SELECT l.* FROM portfolio_lot l JOIN portfolio_transaction t ON t.id=l.opening_transaction_id "
                        + "WHERE l.portfolio_account_id=? AND l.status='OPEN' "
                        + "AND l.instrument_type=? AND l.side=? AND l.symbol=? AND l.option_type IS NOT DISTINCT FROM ? "
                        + "AND l.strike IS NOT DISTINCT FROM ? AND l.expiration IS NOT DISTINCT FROM ? AND l.multiplier=? "
                        + "AND l.section_1256=? ORDER BY " + order + " FOR UPDATE OF l",
                PortfolioAccountingService::mapLot, account.id(), leg.instrumentType(), side, leg.symbol(),
                leg.optionType(), leg.strike(), leg.expiration(), leg.multiplier(), leg.section1256());
    }

    private static String holdingTerm(LotRow lot, OffsetDateTime closed) {
        if ("SHORT".equals(lot.side())) return "SHORT_TERM";
        LocalDate openedDate = OffsetDateTime.parse(lot.openedAt()).atZoneSameInstant(MARKET_ZONE).toLocalDate();
        LocalDate closedDate = closed.atZoneSameInstant(MARKET_ZONE).toLocalDate();
        return closedDate.isAfter(openedDate.plusYears(1)) ? "LONG_TERM" : "SHORT_TERM";
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

    private String insertLot(Connection c, String accountId, String txId, OffsetDateTime occurred,
                             PreparedLeg leg, long taxAmount, long economicAmount) throws SQLException {
        String side = "BUY".equals(leg.action()) ? "LONG" : "SHORT";
        String id = Ids.newId("plot");
        Db.execOn(c, "INSERT INTO portfolio_lot(id,portfolio_account_id,opening_transaction_id,opening_leg_no,"
                        + "instrument_type,side,symbol,option_type,strike,expiration,opened_at,acquired_at,original_quantity,"
                        + "remaining_quantity,original_open_amount_cents,remaining_open_amount_cents,"
                        + "economic_original_open_amount_cents,economic_remaining_open_amount_cents,"
                        + "multiplier,section_1256,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'OPEN')",
                id, accountId, txId, leg.legNo(), leg.instrumentType(), side,
                leg.symbol(), leg.optionType(), leg.strike(), leg.expiration(), occurred, occurred,
                leg.quantity(), leg.quantity(), taxAmount, taxAmount, economicAmount, economicAmount,
                leg.multiplier(), leg.section1256());
        return id;
    }

    // ---- Mapping and validation ----

    private List<PreparedLeg> prepareLegs(List<LegInput> raw, long fees, String event, LocalDate eventDate) {
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
            if ("EXPIRATION".equals(event) && !"OPTION".equals(instrument)) {
                throw new IllegalArgumentException("Expiration accepts option legs only");
            }
            if ("EXPIRATION".equals(event) && expiration.isAfter(eventDate)) {
                throw new IllegalArgumentException("An option cannot be recorded as expired before its expiration date");
            }
            long units = Math.multiplyExact(quantity, (long) multiplier);
            long gross = Money.centsFromPrice(price, units);
            boolean section1256 = section1256(in.section1256(), instrument, symbol);
            base.add(new PreparedLeg(i, instrument, action, effect, symbol, optionType, strike,
                    expiration, quantity, multiplier, price, gross, 0, section1256));
        }
        if (base.isEmpty() || fees == 0) return base;
        long totalGross = 0;
        long totalUnits = 0;
        for (PreparedLeg leg : base) totalGross = Math.addExact(totalGross, leg.grossAmountCents());
        for (PreparedLeg leg : base) totalUnits = Math.addExact(totalUnits,
                Math.multiplyExact(leg.quantity(), (long) leg.multiplier()));
        long remaining = fees;
        List<PreparedLeg> allocated = new ArrayList<>(base.size());
        for (int i = 0; i < base.size(); i++) {
            PreparedLeg leg = base.get(i);
            long fee = i == base.size() - 1 ? remaining
                    : proportionalFee(fees, totalGross == 0
                            ? Math.multiplyExact(leg.quantity(), (long) leg.multiplier()) : leg.grossAmountCents(),
                            totalGross == 0 ? totalUnits : totalGross);
            fee = Math.min(remaining, fee);
            remaining -= fee;
            allocated.add(new PreparedLeg(leg.legNo(), leg.instrumentType(), leg.action(), leg.positionEffect(),
                    leg.symbol(), leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(), leg.multiplier(),
                    leg.price(), leg.grossAmountCents(), fee, leg.section1256()));
        }
        return allocated;
    }

    private static long proportionalFee(long fees, long weight, long totalWeight) {
        if (fees == 0 || weight == 0 || totalWeight == 0) return 0;
        return BigDecimal.valueOf(fees).multiply(BigDecimal.valueOf(weight))
                .divide(BigDecimal.valueOf(totalWeight), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static void insertLeg(Connection c, String txId, PreparedLeg leg) throws SQLException {
        Db.execOn(c, "INSERT INTO portfolio_transaction_leg(transaction_id,leg_no,instrument_type,action,position_effect,"
                        + "symbol,option_type,strike,expiration,quantity,multiplier,price,gross_amount_cents,allocated_fee_cents,section_1256) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                txId, leg.legNo(), leg.instrumentType(), leg.action(), leg.positionEffect(), leg.symbol(),
                leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(), leg.multiplier(), leg.price(),
                leg.grossAmountCents(), leg.allocatedFeeCents(), leg.section1256());
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
        RollView roll = roll(c, txId);
        return new TransactionView((String) row[0], (String) row[1], (String) row[2], (String) row[3],
                (Long) row[4], (Long) row[5], (String) row[6], (String) row[7], (String) row[8], (String) row[9], legs, roll);
    }

    private static RollView roll(Connection c, String txId) throws SQLException {
        var rows = Db.queryOn(c, "SELECT replacement_lot_id,quantity,closing_premium_cents,opening_premium_cents,"
                        + "premium_carryover_cents FROM portfolio_roll WHERE transaction_id=?",
                r -> new Object[]{r.str("replacement_lot_id"), r.lng("quantity"), r.lng("closing_premium_cents"),
                        r.lng("opening_premium_cents"), r.lng("premium_carryover_cents")}, txId);
        if (rows.isEmpty()) return null;
        Object[] row = rows.getFirst();
        List<Long> matches = Db.queryOn(c, "SELECT lot_match_id FROM portfolio_roll_match WHERE transaction_id=? ORDER BY lot_match_id",
                r -> r.lng("lot_match_id"), txId);
        return new RollView((String) row[0], (Long) row[1], (Long) row[2], (Long) row[3], (Long) row[4], matches);
    }

    private static AccountProfile requireAccount(Connection c, String owner, String id, boolean lock) throws SQLException {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("portfolio account id is required");
        var rows = Db.queryOn(c, "SELECT * FROM portfolio_account WHERE id=? AND owner_id=?" + (lock ? " FOR UPDATE" : ""),
                PortfolioAccountingService::mapAccount, id, owner);
        if (rows.isEmpty()) throw new io.liftandshift.strikebench.util.ResourceNotFoundException("No tracked portfolio account " + id);
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
                r.lng("allocated_fee_cents"), r.bool("section_1256"));
    }

    private static LotRow mapLot(Db.Row r) {
        return new LotRow(r.str("id"), r.str("portfolio_account_id"), r.str("instrument_type"), r.str("side"),
                r.str("symbol"), r.str("option_type"), r.bd("strike"), r.date("expiration"),
                r.intv("multiplier"), iso(r.odt("opened_at")), iso(r.odt("acquired_at")),
                r.lng("original_quantity"), r.lng("remaining_quantity"),
                r.lng("original_open_amount_cents"), r.lng("remaining_open_amount_cents"),
                r.lng("economic_original_open_amount_cents"), r.lng("economic_remaining_open_amount_cents"),
                r.str("status"),
                r.bool("section_1256"));
    }

    private static LotView mapLotView(Db.Row r) {
        LotRow x = mapLot(r);
        return new LotView(x.id(), x.instrumentType(), x.side(), x.symbol(), x.optionType(), x.strike(),
                x.expiration(), x.multiplier(), x.openedAt(), x.originalQuantity(), x.remainingQuantity(),
                x.originalOpenAmount(), x.remainingOpenAmount(), x.status(), x.section1256(),
                x.economicOriginalOpenAmount(), x.economicRemainingOpenAmount());
    }

    private static RealizedLotView mapRealized(Db.Row r) {
        return new RealizedLotView(r.lng("id"), r.str("lot_id"), r.str("symbol"), r.str("instrument_type"),
                r.str("side"), r.lng("quantity"), iso(r.odt("opened_at")), iso(r.odt("closed_at")),
                r.lng("open_amount_cents"), r.lng("close_amount_cents"), r.lng("realized_gain_cents"),
                r.str("holding_term"), r.lng("wash_sale_adjustment_cents"), r.bool("section_1256"),
                r.lng("economic_open_amount_cents"), r.lng("economic_close_amount_cents"),
                r.lng("economic_realized_gain_cents"));
    }

    private static ValuationView mapValuation(Db.Row r) {
        return new ValuationView(r.str("id"), r.str("portfolio_account_id"), iso(r.odt("as_of")),
                r.lngOrNull("cash_cents"), r.lngOrNull("securities_value_cents"), r.lng("total_value_cents"),
                r.str("source"), r.str("external_ref"), r.str("notes"), r.bool("complete"),
                Json.read(r.str("missing_marks"), new TypeReference<List<String>>() {}));
    }

    private static long incomeOn(Connection c, String accountId, String event, Integer year) throws SQLException {
        String yearSql = year == null ? ""
                : " AND EXTRACT(YEAR FROM (occurred_at AT TIME ZONE 'America/New_York'))=?";
        Object[] args = year == null ? new Object[]{accountId, event} : new Object[]{accountId, event, year};
        return scalarOn(c, "SELECT COALESCE(SUM(cash_effect_cents),0) n FROM portfolio_transaction "
                + "WHERE portfolio_account_id=? AND event_type=?" + yearSql, args);
    }

    private static long incomeByCategoryOn(Connection c, String accountId, int year,
                                           String category) throws SQLException {
        return scalarOn(c, "SELECT COALESCE(SUM(cash_effect_cents),0) n FROM portfolio_transaction "
                + "WHERE portfolio_account_id=? "
                + "AND EXTRACT(YEAR FROM (occurred_at AT TIME ZONE 'America/New_York'))=? AND tax_category=?",
                accountId, year, category);
    }

    private static String normalizeTaxCategory(String raw, String event) {
        if ("INTEREST".equals(event)) {
            if (raw == null || raw.isBlank()) return "ORDINARY_INTEREST";
            return enumValue(raw, List.of("ORDINARY_INTEREST"), "tax category for interest");
        }
        if ("DIVIDEND".equals(event)) {
            if (raw == null || raw.isBlank()) return "ORDINARY_DIVIDEND";
            return enumValue(raw, List.of("ORDINARY_DIVIDEND", "QUALIFIED_DIVIDEND",
                    "CAPITAL_GAIN_DISTRIBUTION"), "tax category for a dividend");
        }
        if (raw != null && !raw.isBlank()) {
            throw new IllegalArgumentException("tax category applies only to interest or dividend activity");
        }
        return null;
    }

    private static long normalizedCash(String event, long amount) {
        if (amount == Long.MIN_VALUE) throw new IllegalArgumentException("cash amount is outside the supported range");
        return switch (event) {
            case "OPENING_BALANCE", "DEPOSIT", "TRANSFER_IN", "INTEREST", "DIVIDEND" -> Math.abs(amount);
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

    private static long percentage(long cents, int percent) {
        return BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long exactValuationTotal(long cash, long securities) {
        try { return Math.addExact(cash, securities); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("valuation amounts exceed the supported range"); }
    }

    private static long exactSum(List<Long> values, String label) {
        try {
            long total = 0;
            for (Long value : values) total = Math.addExact(total, value == null ? 0 : value);
            return total;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(label + " exceed the supported range");
        }
    }

    private static long absolute(long value, String label) {
        if (value == Long.MIN_VALUE) throw new IllegalArgumentException(label + " exceeds the supported range");
        return Math.abs(value);
    }

    private static boolean withinOneCent(long first, long second) {
        return first == second
                || (first != Long.MAX_VALUE && first + 1 == second)
                || (second != Long.MAX_VALUE && second + 1 == first);
    }

    private static void requireChronologicalMarketActivity(Connection c, String accountId,
                                                           OffsetDateTime occurred) throws SQLException {
        List<OffsetDateTime> latest = Db.queryOn(c,
                "SELECT occurred_at FROM portfolio_transaction WHERE portfolio_account_id=? "
                        + "AND event_type IN ('TRADE','ROLL','EXPIRATION','ASSIGNMENT','EXERCISE','MARK_TO_MARKET') "
                        + "ORDER BY occurred_at DESC,record_seq DESC LIMIT 1",
                r -> r.odt("occurred_at"), accountId);
        if (!latest.isEmpty() && latest.getFirst().isAfter(occurred)) {
            throw new IllegalStateException("Market activity must be recorded oldest to newest so tax-lot matches remain exact. "
                    + "Import a chronological history into a new tracked account when adding older activity.");
        }
    }

    private static String taxLimitNote(TaxRules.View rules) {
        return "Ruleset " + rules.id() + " is " + rules.status().name().toLowerCase()
                + " for tax year " + rules.taxYear() + " and was reviewed through " + rules.reviewedThrough()
                + ". Automated results cover only recorded same-account exact-instrument wash-sale candidates and "
                + "identified broad-based-index Section 1256 treatment. They do not resolve cross-account, spouse, "
                + "IRA, substantially-identical-security, or short-sale wash rules; qualified-covered-call or straddle "
                + "rules; loss limits or carryovers; state-specific rules; filing elections; or the rest of the return. "
                + "Reconcile every figure against broker forms and a qualified tax professional.";
    }

    private static boolean section1256(Boolean explicit, String instrumentType, String symbol) {
        if (Boolean.TRUE.equals(explicit) && !"OPTION".equals(instrumentType)) {
            throw new IllegalArgumentException("Section 1256 classification applies only to eligible contracts, not stock");
        }
        if (!"OPTION".equals(instrumentType)) return false;
        if (BroadBasedIndexOptions.isKnownRoot(symbol)) return true;
        return Boolean.TRUE.equals(explicit);
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

    private void requireNotFuture(String raw, OffsetDateTime value, String label) {
        if (raw != null && raw.matches("\\d{4}-\\d{2}-\\d{2}")) {
            LocalDate today = LocalDate.now(clock.withZone(ZoneId.systemDefault()));
            if (LocalDate.parse(raw).isAfter(today)) {
                throw new IllegalArgumentException(label + " date/time cannot be in the future");
            }
            return;
        }
        if (value.toInstant().isAfter(clock.instant().plus(Duration.ofMinutes(5)))) {
            throw new IllegalArgumentException(label + " date/time cannot be in the future");
        }
    }

    private static void requireActive(AccountProfile account) {
        if (!"ACTIVE".equals(account.status())) {
            throw new IllegalStateException("This tracked account is archived and read-only.");
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
