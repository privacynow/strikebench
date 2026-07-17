package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EvidenceLevel;
import io.liftandshift.strikebench.market.MarketLane;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.position.PositionArtifactStore;
import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.OwnerScope;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Owner-scoped broker-import boundary. Preview is read-only. Confirmation re-parses the exact
 * text and atomically writes only selected verified groups; package-net-only groups enter the
 * existing pending-import quarantine, never the accounting ledger.
 */
public final class BrokerImportService {
    public static final String RESOLUTION_MODEL_VERSION = "broker-import-resolution-1";

    public record PreviewRequest(String parserVersion, String sourceSystem, String sourceAccount, String text) {}
    public record CurrentMark(int legNo, BigDecimal pastedMark, String pastedMarkAsOf,
                              boolean pastedMarkIsCurrent, BigDecimal currentBid, BigDecimal currentAsk,
                              BigDecimal currentMid, String provenance, String age, String source,
                              boolean observedEligible, String currentEvidence, String note) {}
    public record GroupMarks(String groupKey, String externalRef, List<CurrentMark> legs) {}
    public record Preview(BrokerStatementParser.Result parsed, List<GroupMarks> marks, String note) {}

    public record VerifiedGroup(String groupKey, List<BrokerStatementParser.VerifiedLeg> legs) {}
    public record ConfirmRequest(String parserVersion, String previewFingerprint, String sourceSystem,
                                 String sourceAccount, String text,
                                 Map<String, String> destinationAccountByFingerprint,
                                 List<VerifiedGroup> groups, String planId) {}
    public record AdoptionLot(String lotId, long quantity, String symbol) {}
    public record ConfirmedItem(String groupKey, String externalRef, String kind, String id, boolean duplicate,
                                String portfolioAccountId, String symbol, List<AdoptionLot> lots) {}
    public record ConfirmResult(int selected, int exactTransactions, int pendingImports,
                                int duplicates, List<ConfirmedItem> items, String note) {}

    public record PendingLeg(int legNo, String instrumentType, String action, String positionEffect,
                             String symbol, String optionType, BigDecimal strike, LocalDate expiration,
                             long quantity, int multiplier, BigDecimal reportedPrice,
                             String reportedPriceAuthority) {}
    public record PendingView(String id, String sourceSystem, String sourceAccountFingerprint,
                              String externalRef, String broker, String occurredAt,
                              long packageNetCents, long feesCents, String destinationPortfolioAccountId,
                              String payloadFingerprint, String planId, String status,
                              String createdAt, String resolvedAt, List<PendingLeg> legs,
                              ResolutionView resolution, List<ResolutionView> resolutionHistory) {}
    public record ResolutionView(String id, String portfolioAccountId, String transactionId, String receiptId,
                                 String authority, String taxBasisStatus, long packageTotalCents,
                                 long allocatedTotalCents, String resolverUserId, String resolvedAt) {}
    public record PendingList(List<PendingView> imports, int pendingCount, int total,
                              int limit, int offset, boolean hasMore) {}

    public record ResolutionLeg(int legNo, BigDecimal price) {}
    public record ResolveRequest(String portfolioAccountId, String authority, List<ResolutionLeg> legs) {}
    public record ResolveResult(PendingView pending, PortfolioAccountingService.TransactionView transaction,
                                String receiptId, String taxTruth, ConfirmedItem adoptionItem, String note) {}
    public record CommandRequest(String action, String portfolioAccountId, String authority,
                                 List<ResolutionLeg> legs) {}
    public record CommandResult(String action, PendingView pending,
                                PortfolioAccountingService.TransactionView transaction,
                                String receiptId, String taxTruth, ConfirmedItem adoptionItem,
                                String note) {}

    private final Db db;
    private final Clock clock;
    private final PortfolioAccountingService books;
    private final MarksSource marks;
    private final PositionArtifactStore artifacts;
    private final CampaignService campaigns;
    private final BrokerStatementParser parser = new BrokerStatementParser();
    private Consumer<String> ownerChanged = ignored -> {};

    public BrokerImportService(Db db, Clock clock, PortfolioAccountingService books,
                               MarksSource marks, PositionArtifactStore artifacts) {
        this(db, clock, books, marks, artifacts, new CampaignService(db, clock));
    }

    public BrokerImportService(Db db, Clock clock, PortfolioAccountingService books,
                               MarksSource marks, PositionArtifactStore artifacts,
                               CampaignService campaigns) {
        this.db = db;
        this.clock = clock;
        this.books = books;
        this.marks = marks;
        this.artifacts = artifacts;
        this.campaigns = campaigns;
    }

    public void setOwnerChangedHook(Consumer<String> hook) {
        ownerChanged = hook == null ? ignored -> {} : hook;
    }

    public Preview preview(PreviewRequest request) {
        return preview("local", request);
    }

    public Preview preview(String userId, PreviewRequest request) {
        if (request == null) throw new IllegalArgumentException("broker import preview is required");
        requireParserVersion(request.parserVersion());
        String owner = OwnerScope.id(userId);
        BrokerStatementParser.Result parsed = parser.parse(new BrokerStatementParser.Request(
                request.sourceSystem(), request.sourceAccount(), request.text(), fingerprintKey(owner)));
        List<GroupMarks> current = new ArrayList<>();
        for (BrokerStatementParser.Group group : parsed.groups()) {
            List<CurrentMark> legMarks = new ArrayList<>();
            for (BrokerStatementParser.Leg leg : group.legs()) legMarks.add(currentMark(leg));
            current.add(new GroupMarks(group.groupKey(), group.externalRef(), List.copyOf(legMarks)));
        }
        return new Preview(parsed, List.copyOf(current),
                "Preview only. No tracked account, lot, pending import, Plan, or order was changed. "
                        + "Pasted marks remain historical snapshots even when a separately sourced current mark is available.");
    }

    public ConfirmResult confirm(String userId, ConfirmRequest request) {
        if (request == null) throw new IllegalArgumentException("broker import confirmation is required");
        requireParserVersion(request.parserVersion());
        String owner = OwnerScope.id(userId);
        BrokerStatementParser.Result parsed = parser.parse(new BrokerStatementParser.Request(
                request.sourceSystem(), request.sourceAccount(), request.text(), fingerprintKey(owner)));
        if (request.previewFingerprint() == null
                || !request.previewFingerprint().equals(parsed.previewFingerprint())) {
            throw new IllegalStateException("The broker text or account label changed after preview. Preview it again before confirming.");
        }
        Map<String, BrokerStatementParser.Group> available = new LinkedHashMap<>();
        for (BrokerStatementParser.Group group : parsed.groups()) available.put(group.groupKey(), group);
        if (request.groups() == null || request.groups().isEmpty()) {
            throw new IllegalArgumentException("choose at least one verified broker group");
        }
        Map<String, VerifiedGroup> submitted = new LinkedHashMap<>();
        for (VerifiedGroup group : request.groups()) {
            if (group == null || group.groupKey() == null
                    || submitted.putIfAbsent(group.groupKey(), group) != null) {
                throw new IllegalArgumentException("each selected package must appear exactly once");
            }
            if (!available.containsKey(group.groupKey())) {
                throw new IllegalArgumentException("selected package " + group.groupKey()
                        + " was not in the verified preview");
            }
        }
        List<BrokerStatementParser.Group> selected = parsed.groups().stream()
                .filter(g -> submitted.containsKey(g.groupKey()))
                .map(g -> parser.verify(g, submitted.get(g.groupKey()).legs())).toList();
        if (request.destinationAccountByFingerprint() == null) {
            throw new IllegalArgumentException("map each source account to its tracked destination account");
        }
        Map<String, String> destinations = new LinkedHashMap<>();
        for (BrokerStatementParser.Group group : selected) {
            String destination = request.destinationAccountByFingerprint().get(group.accountFingerprint());
            if (destination == null || destination.isBlank()) throw new IllegalArgumentException(
                    "map source account " + group.accountFingerprint() + " to its tracked destination account");
            destinations.put(group.accountFingerprint(), destination);
        }

        List<ConfirmedItem> items = db.tx(c -> {
            for (String destination : new LinkedHashSet<>(destinations.values())) {
                if (Db.queryOn(c, "SELECT id FROM portfolio_account WHERE id=? AND user_id=? "
                                + "AND status='ACTIVE' FOR UPDATE",
                        r -> r.str("id"), destination, owner).isEmpty()) {
                    throw new IllegalStateException("Every broker destination must be an active tracked account.");
                }
            }
            if (request.planId() != null && !request.planId().isBlank()
                    && Db.queryOn(c, "SELECT 1 ok FROM plans WHERE id=? AND user_id=?",
                    r -> r.intv("ok"), request.planId(), owner).isEmpty()) {
                throw new IllegalArgumentException("the import Plan does not exist in this owner scope");
            }
            List<ConfirmedItem> out = new ArrayList<>();
            for (BrokerStatementParser.Group group : selected) {
                String destination = destinations.get(group.accountFingerprint());
                if (group.kind() == BrokerStatementParser.GroupKind.PACKAGE_NET_PENDING) {
                    out.add(confirmPending(c, owner, destination, group, request.planId()));
                } else {
                    out.add(confirmExact(c, owner, destination, parsed.sourceSystem(), group));
                }
            }
            return List.copyOf(out);
        });
        notifyChanged(owner);
        int exact = (int) items.stream().filter(i -> "EXACT_TRANSACTION".equals(i.kind()) && !i.duplicate()).count();
        int pending = (int) items.stream().filter(i -> "PENDING_IMPORT".equals(i.kind()) && !i.duplicate()).count();
        int duplicates = (int) items.stream().filter(ConfirmedItem::duplicate).count();
        return new ConfirmResult(items.size(), exact, pending, duplicates, items,
                "Confirmed exact fills entered the selected tracked account through its canonical ledger. "
                        + "Package-net-only groups remain quarantined outside tracked accounting until their per-leg cash is resolved.");
    }

    public PendingList list(String userId, String status) {
        return list(userId, status, 100, 0, null);
    }

    /** Bounded queue/history read. The default is the active queue, never all historical rows. */
    public PendingList list(String userId, String status, int requestedLimit, int requestedOffset) {
        return list(userId, status, requestedLimit, requestedOffset, null);
    }

    public PendingList list(String userId, String status, int requestedLimit, int requestedOffset,
                            String portfolioAccountId) {
        String owner = OwnerScope.id(userId);
        String normalized = status == null || status.isBlank()
                ? "OPEN" : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("OPEN", "PENDING", "PROVISIONAL", "RESOLVED", "REJECTED", "ALL").contains(normalized)) {
            throw new IllegalArgumentException("pending-import status must be OPEN, PENDING, PROVISIONAL, RESOLVED, REJECTED, or ALL");
        }
        int limit = Math.max(1, Math.min(250, requestedLimit));
        int offset = Math.max(0, requestedOffset);
        String accountCondition = portfolioAccountId == null || portfolioAccountId.isBlank()
                ? "" : " AND destination_portfolio_account_id=?";
        String condition = accountCondition + switch (normalized) {
            case "OPEN" -> " AND status IN ('PENDING','PROVISIONAL')";
            case "ALL" -> "";
            default -> " AND status=?";
        };
        List<Object> base = new ArrayList<>();
        base.add(owner);
        if (!accountCondition.isEmpty()) base.add(portfolioAccountId);
        if (!"OPEN".equals(normalized) && !"ALL".equals(normalized)) base.add(normalized);
        return db.with(c -> {
            try {
                List<Object> openParams = new ArrayList<>(); openParams.add(owner);
                if (!accountCondition.isEmpty()) openParams.add(portfolioAccountId);
                int pendingCount = Db.queryOn(c, "SELECT COUNT(*) n FROM portfolio_import_pending "
                                + "WHERE user_id=?" + accountCondition
                                + " AND status IN ('PENDING','PROVISIONAL')",
                        r -> r.intv("n"), openParams.toArray()).getFirst();
                int total = Db.queryOn(c, "SELECT COUNT(*) n FROM portfolio_import_pending WHERE user_id=?"
                                + condition, r -> r.intv("n"), base.toArray()).getFirst();
                List<Object> pageParams = new ArrayList<>(base);
                pageParams.add(limit); pageParams.add(offset);
                List<String> ids = Db.queryOn(c, "SELECT id FROM portfolio_import_pending WHERE user_id=?"
                                + condition + " ORDER BY occurred_at DESC,created_at DESC,id LIMIT ? OFFSET ?",
                        r -> r.str("id"), pageParams.toArray());
                List<PendingView> out = new ArrayList<>(ids.size());
                for (String id : ids) out.add(pendingOn(c, owner, id, false));
                return new PendingList(List.copyOf(out), pendingCount, total, limit, offset,
                        offset + out.size() < total);
            } catch (SQLException e) { throw new Db.DbException(e); }
        });
    }

    public PendingView get(String userId, String pendingId) {
        return db.with(c -> {
            try { return pendingOn(c, OwnerScope.id(userId), requiredId(pendingId), false); }
            catch (SQLException e) { throw new Db.DbException(e); }
        });
    }

    /** One canonical mutation boundary for pending-package resolution, rejection and recovery. */
    public CommandResult command(String userId, String pendingId, CommandRequest request) {
        if (request == null || request.action() == null || request.action().isBlank()) {
            throw new IllegalArgumentException("pending-import command is required");
        }
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        return switch (action) {
            case "RESOLVE" -> {
                ResolveResult resolved = resolve(userId, pendingId, new ResolveRequest(
                        request.portfolioAccountId(), request.authority(), request.legs()));
                yield new CommandResult(action, resolved.pending(), resolved.transaction(),
                        resolved.receiptId(), resolved.taxTruth(), resolved.adoptionItem(), resolved.note());
            }
            case "REJECT" -> {
                PendingView pending = reject(userId, pendingId);
                yield new CommandResult(action, pending, null, null, null, null,
                        "Rejected from the active queue. The immutable package remains in Rejected history and can be reopened.");
            }
            case "REOPEN" -> {
                PendingView pending = reopen(userId, pendingId);
                yield new CommandResult(action, pending, null, null,
                        "PROVISIONAL".equals(pending.status()) ? "PROVISIONAL" : null, null,
                        "The package is back in the active resolution queue with its immutable history intact.");
            }
            default -> throw new IllegalArgumentException("pending-import command must be RESOLVE, REJECT, or REOPEN");
        };
    }

    public PendingView reject(String userId, String pendingId) {
        String owner = OwnerScope.id(userId);
        PendingView rejected = db.tx(c -> {
            pendingOn(c, owner, requiredId(pendingId), true);
            int changed = Db.execOn(c, "UPDATE portfolio_import_pending SET status='REJECTED',resolved_at=? "
                            + "WHERE id=? AND user_id=? AND status IN ('PENDING','PROVISIONAL')",
                    now(), pendingId, owner);
            if (changed != 1) throw new IllegalStateException("Only a pending or provisional import can be rejected");
            return pendingOn(c, owner, pendingId, false);
        });
        notifyChanged(owner);
        return rejected;
    }

    public PendingView reopen(String userId, String pendingId) {
        String owner = OwnerScope.id(userId);
        PendingView reopened = db.tx(c -> {
            PendingView current = pendingOn(c, owner, requiredId(pendingId), true);
            if (!"REJECTED".equals(current.status())) {
                throw new IllegalStateException("Only a rejected pending import can be reopened");
            }
            List<OffsetDateTime> provisional = Db.queryOn(c,
                    "SELECT resolved_at FROM portfolio_import_resolution WHERE pending_id=? "
                            + "AND authority='USER_ALLOCATED' ORDER BY resolved_at DESC,id DESC",
                    r -> r.odt("resolved_at"), current.id());
            String next = provisional.isEmpty() ? "PENDING" : "PROVISIONAL";
            OffsetDateTime resolvedAt = provisional.isEmpty() ? null : provisional.getFirst();
            int changed = Db.execOn(c, "UPDATE portfolio_import_pending SET status=?,resolved_at=? "
                            + "WHERE id=? AND user_id=? AND status='REJECTED'",
                    next, resolvedAt, current.id(), owner);
            if (changed != 1) throw new IllegalStateException("The rejected import changed before it could reopen");
            return pendingOn(c, owner, current.id(), false);
        });
        notifyChanged(owner);
        return reopened;
    }

    public ResolveResult resolve(String userId, String pendingId, ResolveRequest request) {
        if (request == null || request.portfolioAccountId() == null || request.portfolioAccountId().isBlank()) {
            throw new IllegalArgumentException("choose the tracked account that owns these fills");
        }
        String owner = OwnerScope.id(userId);
        PositionDomain.ReceiptAuthority authority = resolutionAuthority(request.authority());
        ResolveResult result = db.tx(c -> {
            PendingView pending = pendingOn(c, owner, requiredId(pendingId), true);
            if (!request.portfolioAccountId().equals(pending.destinationPortfolioAccountId())) {
                throw new IllegalArgumentException("this package is mapped to tracked account "
                        + pending.destinationPortfolioAccountId() + "; change the import mapping instead of moving its facts");
            }
            if (authority == PositionDomain.ReceiptAuthority.USER_ALLOCATED && !"PENDING".equals(pending.status())) {
                throw new IllegalStateException("A user allocation can only provisionally resolve a pending import");
            }
            if (authority == PositionDomain.ReceiptAuthority.BROKER_REPORTED
                    && !Set.of("PENDING", "PROVISIONAL").contains(pending.status())) {
                throw new IllegalStateException("Broker attestation can only resolve a pending or provisional import");
            }
            Map<Integer, BigDecimal> entered = resolutionPrices(request.legs(), pending.legs());
            List<PortfolioAccountingService.LegInput> ledgerLegs = new ArrayList<>();
            List<BrokerStatementParser.Leg> cashLegs = new ArrayList<>();
            List<PositionArtifactStore.ReceiptLeg> receiptLegs = new ArrayList<>();
            boolean anyOpen = false;
            boolean allObservedMarks = true;
            for (PendingLeg leg : pending.legs()) {
                BigDecimal price = entered.get(leg.legNo());
                if (authority == PositionDomain.ReceiptAuthority.USER_ALLOCATED
                        && "BROKER_REPORTED".equals(leg.reportedPriceAuthority())
                        && leg.reportedPrice().compareTo(price) != 0) {
                    throw new IllegalArgumentException("leg " + leg.legNo()
                            + " already has a broker-reported fill and cannot be replaced by a user allocation");
                }
                anyOpen |= "OPEN".equals(leg.positionEffect());
                ledgerLegs.add(new PortfolioAccountingService.LegInput(leg.instrumentType(), leg.action(),
                        leg.positionEffect(), leg.symbol(), leg.optionType(), leg.strike(), leg.expiration(),
                        leg.quantity(), leg.multiplier(), price, null));
                cashLegs.add(new BrokerStatementParser.Leg(0, leg.legNo(), leg.instrumentType(), leg.action(),
                        leg.positionEffect(), leg.symbol(), leg.optionType(), leg.strike(), leg.expiration(),
                        leg.quantity(), leg.multiplier(), price, null, null, List.of()));
                CurrentMark mark = currentMark(cashLegs.getLast());
                allObservedMarks &= mark.observedEligible();
                PositionDomain.PriceAuthority priceAuthority = authority == PositionDomain.ReceiptAuthority.BROKER_REPORTED
                        || "BROKER_REPORTED".equals(leg.reportedPriceAuthority())
                        ? PositionDomain.PriceAuthority.BROKER_REPORTED
                        : PositionDomain.PriceAuthority.USER_REPORTED;
                receiptLegs.add(new PositionArtifactStore.ReceiptLeg("AFTER", leg.legNo(), leg.instrumentType(),
                        leg.action(), leg.symbol(), leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(),
                        leg.multiplier(), mark.currentBid(), mark.currentAsk(), mark.currentMid(), price,
                        priceAuthority));
            }
            long allocated = BrokerStatementParser.computedCash(cashLegs, pending.feesCents());
            if (allocated != pending.packageNetCents()) {
                throw new IllegalArgumentException("Entered leg fills produce " + allocated
                        + " cents after fees; the broker package fact is " + pending.packageNetCents()
                        + " cents. Resolution requires exact-cent equality.");
            }
            String ref = ledgerReference(pending.sourceSystem(), pending.sourceAccountFingerprint(),
                    pending.externalRef());
            PortfolioAccountingService.TransactionView transaction = null;
            if (authority == PositionDomain.ReceiptAuthority.BROKER_REPORTED) {
                transaction = books.recordOn(c, owner, request.portfolioAccountId(),
                        new PortfolioAccountingService.TransactionInput(pending.occurredAt(), "TRADE",
                                pending.packageNetCents(), pending.feesCents(), null, "IMPORT", ref,
                                "Broker-attested per-leg fills for pending import " + pending.id()
                                        + "; any earlier user allocation remains only as immutable provisional history.",
                                ledgerLegs, "EXECUTED"));
                Db.execOn(c, "UPDATE portfolio_transaction SET import_payload_fingerprint=? WHERE id=?",
                        pending.payloadFingerprint(), transaction.id());
            }
            OffsetDateTime resolvedAt = now();
            String receiptId = artifacts.recordImportResolution(c,
                    new PositionArtifactStore.ImportResolutionAction(owner, pending.id(),
                            request.portfolioAccountId(), transaction == null ? null : transaction.id(), authority,
                            anyOpen ? PositionDomain.PositionState.OPEN : PositionDomain.PositionState.CLOSED,
                            resolvedAt, allObservedMarks ? EvidenceLevel.OBSERVED_DELAYED : EvidenceLevel.UNKNOWN,
                            RESOLUTION_MODEL_VERSION, pending.sourceSystem(),
                            pending.packageNetCents(), allocated, receiptLegs));
            if (transaction != null) campaigns.resolvePendingMembershipOn(c, owner, pending.id(), transaction.id());
            for (PendingLeg leg : pending.legs()) {
                String priceAuthority = authority == PositionDomain.ReceiptAuthority.BROKER_REPORTED
                        ? "BROKER_REPORTED"
                        : "BROKER_REPORTED".equals(leg.reportedPriceAuthority())
                        ? "BROKER_REPORTED" : "USER_ALLOCATED";
                Db.execOn(c, "UPDATE portfolio_import_pending_leg SET reported_price=?,reported_price_authority=? "
                                + "WHERE pending_id=? AND leg_no=?",
                        entered.get(leg.legNo()), priceAuthority, pending.id(), leg.legNo());
            }
            PendingView resolved = pendingOn(c, owner, pending.id(), false);
            String taxTruth = authority == PositionDomain.ReceiptAuthority.BROKER_REPORTED
                    ? "AUTHORITATIVE" : "PROVISIONAL";
            ConfirmedItem adoptionItem = transaction == null ? null : new ConfirmedItem(
                    "pending:" + pending.id(), pending.externalRef(), "EXACT_TRANSACTION", transaction.id(), false,
                    request.portfolioAccountId(), pending.legs().getFirst().symbol(), adoptionLots(c, transaction.id()));
            return new ResolveResult(resolved, transaction, receiptId, taxTruth, adoptionItem,
                    taxTruth.equals("AUTHORITATIVE")
                            ? "Broker-confirmed fills are eligible for tracked tax basis. Not tax advice."
                            : "The package cash is reconciled, but this user allocation is quarantined: it creates no transaction, lot, match, wash-sale basis, or export row until broker fills attest it. Not tax advice.");
        });
        notifyChanged(owner);
        return result;
    }

    private ConfirmedItem confirmPending(Connection c, String owner, String destinationAccountId,
                                         BrokerStatementParser.Group group, String planId)
            throws SQLException {
        String sourceSystem = sourceCode(group.broker());
        String ref = ledgerReference(sourceSystem, group.accountFingerprint(), group.externalRef());
        lockImportIdentity(c, owner, ref);
        ExistingTransaction exact = existingTransaction(c, owner, ref);
        if (exact != null) throw conflictingPayload(group.externalRef(), exact.payloadFingerprint(),
                group.payloadFingerprint());
        List<ExistingPending> existing = Db.queryOn(c, "SELECT id,payload_fingerprint,destination_portfolio_account_id "
                        + "FROM portfolio_import_pending WHERE user_id=? "
                        + "AND source_system=? AND source_account_fingerprint=? AND external_ref=?",
                r -> new ExistingPending(r.str("id"), r.str("payload_fingerprint"),
                        r.str("destination_portfolio_account_id")),
                owner, sourceSystem, group.accountFingerprint(), group.externalRef());
        if (!existing.isEmpty()) {
            ExistingPending prior = existing.getFirst();
            if (!group.payloadFingerprint().equals(prior.payloadFingerprint())) {
                throw conflictingPayload(group.externalRef(), prior.payloadFingerprint(), group.payloadFingerprint());
            }
            return new ConfirmedItem(group.groupKey(), group.externalRef(), "PENDING_IMPORT",
                    prior.id(), true, prior.accountId(), group.legs().getFirst().symbol(), List.of());
        }
        String id = Ids.newId("pimp");
        Db.execOn(c, "INSERT INTO portfolio_import_pending(id,user_id,source_system,source_account_fingerprint,"
                        + "external_ref,broker,occurred_at,package_net_cents,fees_cents,"
                        + "destination_portfolio_account_id,payload_fingerprint,plan_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id, owner, sourceSystem, group.accountFingerprint(), group.externalRef(), group.broker(),
                group.occurredAt(), group.packageNetCents(), group.feesCents(), destinationAccountId,
                group.payloadFingerprint(),
                planId == null || planId.isBlank() ? null : planId);
        for (BrokerStatementParser.Leg leg : group.legs()) {
            Db.execOn(c, "INSERT INTO portfolio_import_pending_leg(pending_id,leg_no,instrument_type,action,"
                            + "position_effect,symbol,option_type,strike,expiration,quantity,multiplier,"
                            + "reported_price,reported_price_authority) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, leg.legNo(), leg.instrumentType(), leg.action(), leg.positionEffect(), leg.symbol(),
                    leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(), leg.multiplier(),
                    leg.reportedPrice(), leg.reportedPrice() == null ? "MISSING" : "BROKER_REPORTED");
        }
        return new ConfirmedItem(group.groupKey(), group.externalRef(), "PENDING_IMPORT", id, false,
                destinationAccountId,
                group.legs().getFirst().symbol(), List.of());
    }

    private ConfirmedItem confirmExact(Connection c, String owner, String accountId, String sourceSystem,
                                       BrokerStatementParser.Group group) throws SQLException {
        String ref = ledgerReference(sourceSystem, group.accountFingerprint(), group.externalRef());
        lockImportIdentity(c, owner, ref);
        ExistingTransaction existing = existingTransaction(c, owner, ref);
        if (existing != null) {
            if (!group.payloadFingerprint().equals(existing.payloadFingerprint())) {
                throw conflictingPayload(group.externalRef(), existing.payloadFingerprint(), group.payloadFingerprint());
            }
            return new ConfirmedItem(group.groupKey(), group.externalRef(), "EXACT_TRANSACTION",
                    existing.id(), true, existing.accountId(), group.legs().getFirst().symbol(),
                    adoptionLots(c, existing.id()));
        }
        List<ExistingPending> pending = Db.queryOn(c, "SELECT id,payload_fingerprint,destination_portfolio_account_id "
                        + "FROM portfolio_import_pending WHERE user_id=? "
                        + "AND source_system=? AND source_account_fingerprint=? AND external_ref=?",
                r -> new ExistingPending(r.str("id"), r.str("payload_fingerprint"),
                        r.str("destination_portfolio_account_id")),
                owner, sourceSystem, group.accountFingerprint(), group.externalRef());
        if (!pending.isEmpty()) {
            throw conflictingPayload(group.externalRef(), pending.getFirst().payloadFingerprint(),
                    group.payloadFingerprint());
        }
        List<PortfolioAccountingService.LegInput> legs = group.legs().stream().map(leg ->
                new PortfolioAccountingService.LegInput(leg.instrumentType(), leg.action(), leg.positionEffect(),
                        leg.symbol(), leg.optionType(), leg.strike(), leg.expiration(), leg.quantity(),
                        leg.multiplier(), leg.reportedPrice(), null)).toList();
        var transaction = books.recordOn(c, owner, accountId, new PortfolioAccountingService.TransactionInput(
                group.occurredAt().toString(), "TRADE", group.packageNetCents(), group.feesCents(), null,
                "IMPORT", ref, "Confirmed exact per-leg fills imported from " + group.broker()
                + ". Pasted marks, if any, were not used as current prices.", legs, "EXECUTED"));
        Db.execOn(c, "UPDATE portfolio_transaction SET import_payload_fingerprint=? WHERE id=?",
                group.payloadFingerprint(), transaction.id());
        return new ConfirmedItem(group.groupKey(), group.externalRef(), "EXACT_TRANSACTION", transaction.id(), false,
                accountId, group.legs().getFirst().symbol(), adoptionLots(c, transaction.id()));
    }

    private static ExistingTransaction existingTransaction(Connection c, String owner, String reference)
            throws SQLException {
        List<ExistingTransaction> rows = Db.queryOn(c,
                "SELECT t.id,t.portfolio_account_id,t.import_payload_fingerprint FROM portfolio_transaction t "
                        + "JOIN portfolio_account a ON a.id=t.portfolio_account_id "
                        + "WHERE a.user_id=? AND t.source='IMPORT' AND t.external_ref=? "
                        + "ORDER BY t.record_seq,t.id",
                r -> new ExistingTransaction(r.str("id"), r.str("portfolio_account_id"),
                        r.str("import_payload_fingerprint")), owner, reference);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static void lockImportIdentity(Connection c, String owner, String reference)
            throws SQLException {
        Db.queryOn(c, "SELECT pg_advisory_xact_lock(hashtext(?),hashtext(?)),1 ok",
                r -> r.intv("ok"), owner, "broker-import:" + reference);
    }

    private static List<AdoptionLot> adoptionLots(Connection c, String transactionId) throws SQLException {
        return Db.queryOn(c, "SELECT id,remaining_quantity,symbol FROM portfolio_lot "
                        + "WHERE opening_transaction_id=? AND remaining_quantity>0 ORDER BY opening_leg_no,id",
                r -> new AdoptionLot(r.str("id"), r.lng("remaining_quantity"), r.str("symbol")), transactionId);
    }

    private record ExistingTransaction(String id, String accountId, String payloadFingerprint) {}
    private record ExistingPending(String id, String payloadFingerprint, String accountId) {}

    private static IllegalStateException conflictingPayload(String externalRef, String existing, String incoming) {
        return new IllegalStateException("Broker reference " + externalRef
                + " already exists with different package facts (existing fingerprint " + existing
                + ", incoming " + incoming + "). Correct the source or resolve the existing package; it is not a duplicate.");
    }

    private CurrentMark currentMark(BrokerStatementParser.Leg imported) {
        BigDecimal bid = null, ask = null, mid = null;
        DataEvidence dataEvidence = DataEvidence.missing("current mark unavailable");
        try {
            if ("STOCK".equals(imported.instrumentType())) {
                mid = marks.underlyingMark(imported.symbol()).orElse(null);
                dataEvidence = marks.underlyingEvidence(imported.symbol(), null)
                        .orElseGet(() -> DataEvidence.missing("underlying source did not disclose provenance"));
            } else {
                Leg leg = new Leg(LegAction.valueOf(imported.action()), OptionType.valueOf(imported.optionType()),
                        imported.strike(), imported.expiration(), Math.toIntExact(imported.quantity()),
                        BigDecimal.ZERO, imported.multiplier());
                MarksSource.LegMark mark = marks.legMark(imported.symbol(), leg).orElse(null);
                if (mark != null) {
                    bid = mark.bid(); ask = mark.ask(); mid = mark.mid();
                    dataEvidence = mark.evidence() == null
                            ? DataEvidence.missing("option source did not disclose provenance") : mark.evidence();
                }
            }
        } catch (RuntimeException ignored) {
            dataEvidence = DataEvidence.missing("current mark unavailable");
        }
        boolean observed = mid != null && dataEvidence.executableIn(MarketLane.OBSERVED);
        String evidence = dataEvidence.provenance() + " / " + dataEvidence.age()
                + (dataEvidence.source() == null || dataEvidence.source().isBlank()
                ? "" : " / " + dataEvidence.source());
        String safety = observed ? "Observed executable evidence."
                : "Not an Observed current mark: stale, modeled, simulated, demo, fixture, EOD, or undisclosed evidence is display-only.";
        return new CurrentMark(imported.legNo(), imported.pastedMark(), imported.pastedMarkAsOf(), false,
                bid, ask, mid, dataEvidence.provenance().name(), dataEvidence.age().name(), dataEvidence.source(),
                observed, evidence, (imported.pastedMark() == null
                ? "No pasted mark. A separate current mark is shown only when available."
                : "PASTED SNAPSHOT — never current. Compare with the separately sourced mark; neither changes the reported fill.")
                + " " + safety);
    }

    private PendingView pendingOn(Connection c, String owner, String id, boolean lock) throws SQLException {
        String sql = "SELECT p.* FROM portfolio_import_pending p WHERE p.id=? AND p.user_id=?"
                + (lock ? " FOR UPDATE OF p" : "");
        List<PendingView> rows = Db.queryOn(c, sql, row -> {
            return new PendingView(row.str("id"), row.str("source_system"),
                    row.str("source_account_fingerprint"), row.str("external_ref"), row.str("broker"),
                    iso(row.odt("occurred_at")), row.lng("package_net_cents"), row.lng("fees_cents"),
                    row.str("destination_portfolio_account_id"), row.str("payload_fingerprint"),
                    row.str("plan_id"), row.str("status"), iso(row.odt("created_at")),
                    iso(row.odt("resolved_at")), List.of(), null, List.of());
        }, id, owner);
        if (rows.isEmpty()) throw new io.liftandshift.strikebench.util.ResourceNotFoundException("Pending import not found");
        PendingView p = rows.getFirst();
        List<PendingLeg> legs = Db.queryOn(c, "SELECT * FROM portfolio_import_pending_leg WHERE pending_id=? ORDER BY leg_no",
                row -> new PendingLeg(row.intv("leg_no"), row.str("instrument_type"), row.str("action"),
                        row.str("position_effect"), row.str("symbol"), row.str("option_type"),
                        row.bd("strike"), row.date("expiration"), row.lng("quantity"),
                        row.intv("multiplier"), row.bd("reported_price"),
                        row.str("reported_price_authority")), id);
        List<ResolutionView> history = Db.queryOn(c, "SELECT * FROM portfolio_import_resolution "
                        + "WHERE pending_id=? ORDER BY resolved_at,"
                        + "CASE authority WHEN 'USER_ALLOCATED' THEN 0 ELSE 1 END,id",
                row -> new ResolutionView(row.str("id"), row.str("portfolio_account_id"),
                        row.str("transaction_id"), row.str("receipt_id"), row.str("authority"),
                        row.str("tax_basis_status"), row.lng("package_total_cents"),
                        row.lng("allocated_total_cents"), row.str("resolver_user_id"),
                        iso(row.odt("resolved_at"))), id);
        ResolutionView current = history.isEmpty() ? null : history.getLast();
        return new PendingView(p.id(), p.sourceSystem(), p.sourceAccountFingerprint(), p.externalRef(), p.broker(),
                p.occurredAt(), p.packageNetCents(), p.feesCents(), p.destinationPortfolioAccountId(),
                p.payloadFingerprint(), p.planId(), p.status(), p.createdAt(), p.resolvedAt(), legs,
                current, List.copyOf(history));
    }

    private static Map<Integer, BigDecimal> resolutionPrices(List<ResolutionLeg> entered,
                                                              List<PendingLeg> expected) {
        if (entered == null || entered.isEmpty()) throw new IllegalArgumentException("enter every per-leg fill");
        Map<Integer, BigDecimal> prices = new LinkedHashMap<>();
        for (ResolutionLeg leg : entered) {
            if (leg == null || leg.price() == null || leg.price().signum() < 0) {
                throw new IllegalArgumentException("every resolution leg needs a non-negative exact fill");
            }
            if (prices.putIfAbsent(leg.legNo(), leg.price()) != null) {
                throw new IllegalArgumentException("resolution repeats leg " + leg.legNo());
            }
        }
        Set<Integer> expectedNos = expected.stream().map(PendingLeg::legNo)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!prices.keySet().equals(expectedNos)) {
            throw new IllegalArgumentException("resolution must price every pending leg exactly once");
        }
        return prices;
    }

    private static PositionDomain.ReceiptAuthority resolutionAuthority(String value) {
        if (value == null) throw new IllegalArgumentException("choose where the per-leg fills came from");
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "BROKER_REPORTED" -> PositionDomain.ReceiptAuthority.BROKER_REPORTED;
            case "USER_ALLOCATED" -> PositionDomain.ReceiptAuthority.USER_ALLOCATED;
            default -> throw new IllegalArgumentException("resolution authority must be BROKER_REPORTED or USER_ALLOCATED");
        };
    }

    private static String ledgerReference(String sourceSystem, String fingerprint, String ref) {
        String value = sourceSystem + ":" + fingerprint + ":" + ref.trim();
        if (value.length() > 160) throw new IllegalArgumentException("broker reference is too long after provenance is attached");
        return value;
    }

    private static String sourceCode(String brokerLabel) {
        for (BrokerStatementParser.SourceSystem source : BrokerStatementParser.SourceSystem.values()) {
            if (source.label().equals(brokerLabel)) return source.name();
        }
        return brokerLabel.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static String requiredId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("pending import id is required");
        return id;
    }

    private static String iso(OffsetDateTime value) { return value == null ? null : value.toString(); }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    private static void requireParserVersion(String version) {
        if (version == null || !BrokerStatementParser.VERSION.equals(version)) {
            throw new IllegalStateException("Broker import parser version changed. Preview the statement again.");
        }
    }

    /** Stable installation-local pepper per owner. Only the opaque HMAC output reaches product rows. */
    private String fingerprintKey(String owner) {
        return db.tx(c -> {
            OwnerScope.ensure(c, owner);
            List<String> existing = Db.queryOn(c,
                    "SELECT secret FROM broker_import_fingerprint_key WHERE user_id=?",
                    r -> r.str("secret"), owner);
            if (!existing.isEmpty()) return existing.getFirst();
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String generated = Base64.getEncoder().encodeToString(bytes);
            Db.execOn(c, "INSERT INTO broker_import_fingerprint_key(user_id,secret,created_at) "
                            + "VALUES(?,?,?) ON CONFLICT(user_id) DO NOTHING",
                    owner, generated, now());
            return Db.queryOn(c, "SELECT secret FROM broker_import_fingerprint_key WHERE user_id=?",
                            r -> r.str("secret"), owner)
                    .getFirst();
        });
    }

    private void notifyChanged(String owner) {
        try { ownerChanged.accept(owner); } catch (RuntimeException ignored) { }
    }
}
