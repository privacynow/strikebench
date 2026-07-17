package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.position.CampaignMath;
import io.liftandshift.strikebench.util.Ids;
import io.liftandshift.strikebench.util.OwnerScope;
import io.liftandshift.strikebench.util.ResourceNotFoundException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The campaign layer (TRADER_OWN §3.12): a campaign is an INTERPRETATION layer, never the
 * accounting source. Transactions, lots, and matches remain authoritative; membership lives in
 * typed tables only (no polymorphic ids) and never rewrites basis, tax character, or ledgers.
 *
 * Vocabulary contract: the per-share figure here is ALWAYS the "campaign-adjusted economic
 * basis"; the accounting book's figure is the "tracked tax basis". They diverge by design
 * (wash-sale adjustments land on the tax side) and are never merged.
 */
public final class CampaignService {

    public static final List<String> STATUSES = List.of("ACTIVE", "CLOSED", "ARCHIVED");
    public static final List<String> MEMBER_TYPES = List.of(
            "TRANSACTION", "PENDING_IMPORT", "PLAN", "PRACTICE_TRADE", "STRUCTURE");
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    /** Stated cash-benchmark rate: the same disclosed 4% modeled teaching default as pricing. */
    public static final int CASH_BENCHMARK_RATE_BPS = 400;
    /** Review pattern evidence requires a material observed rise, not one noisy uptick. */
    public static final BigDecimal PARTICIPATION_PATTERN_MIN_RISE_PCT = new BigDecimal("5.0");

    private final Db db;
    private final Clock clock;

    public CampaignService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    // ---- Public shapes ----

    public record CreateInput(String title, String symbol, String accountObjectiveRevisionId,
                              List<String> seedLotIds) {}

    public record UpdateInput(String title, String status, String lessonNote) {
        /** Existing callers that rename/close a campaign do not implicitly touch its lesson. */
        public UpdateInput(String title, String status) { this(title, status, null); }
    }

    public record MemberInput(String type, String id, Boolean explicitInterest) {}

    public record MemberView(String type, String id, String label, String detail, String occurredAt,
                             Long cashEffectCents, boolean explicitInterest, boolean countsInMath,
                             String attachedAt) {}

    /** One chronological accumulation-ledger row: the §4 running numbers after this event. */
    public record LedgerEntry(String occurredAt, String memberType, String memberId, String eventType,
                              String label, long cashEffectCents, long sharesDelta, long runningShares,
                              long runningNetOptionCashCents, Long runningBasisPerShareCents,
                              long runningCommittedCapitalCents) {}

    public record BasisView(boolean available, Long perShareCents, long sharesHeld,
                            long shareCashPaidCents, long netOptionCashCents, long dividendsCents,
                            long feesCents, String note) {}

    /** Realized-vs-headline on IDENTICAL denominators (§4.3): both sides divide by the same
     *  peak committed capital over the same window; annualized only "if repeatable". */
    public record YieldView(boolean available, BigDecimal headlinePeriodPct,
                            BigDecimal headlineAnnualizedPct, BigDecimal realizedPeriodPct,
                            BigDecimal realizedAnnualizedPct, BigDecimal realizedTimeWeightedPct,
                            long peakCommittedCapitalCents, long timeWeightedCapitalCents,
                            long grossPremiumCents, long realizedPnlCents, int days, String note) {}

    public record BenchmarkOutcome(boolean available, Long endingValueCents, Long deltaCents, String note) {}

    public record CounterfactualView(BenchmarkOutcome cash, BenchmarkOutcome buyAndHold,
                                     Long campaignEndingValueCents, String valuationNote, String asOf) {}

    public record ChurnPair(String exitAt, String reentryAt, long shares, long costCents) {}

    public record ChurnView(List<ChurnPair> roundTrips, long totalCostCents) {}

    public record AccountSubtotal(String accountId, String name, String accountType, long netCashCents,
                                  long sharesDelta, int transactionCount, int shareOfActivityBps) {}

    /** One authored pin on the exact saved Scenario Canvas receipt. */
    public record AuthoredPathPoint(int tradingDay, String date, long priceCents) {}

    /** One eligible realized close. Generated/demo/model bars can never populate this record. */
    public record RealizedPathPoint(int tradingDay, String date, long priceCents) {}

    public record AuthoredRealizedOverlay(boolean available, String planId, String planTitle,
                                          String scenarioId, String scenarioTitle, String authoredAt,
                                          int contextRevision,
                                          String baseEnsembleId, String scenarioFingerprint,
                                          String baseEnsembleFingerprint, String waypointFill,
                                          String anchorDate, long anchorSpotCents,
                                          String anchorSource, String anchorFreshness,
                                          String marketLane, String realizedSource,
                                          String realizedDataset, Boolean adjusted,
                                          List<AuthoredPathPoint> authored,
                                          List<RealizedPathPoint> realized, String note) {}

    /** Review of one frozen mechanical rule from one Plan decision. */
    public record ProtocolAdherence(String planId, String decisionId, String executionLane,
                                    String rule, Long triggerPnlCents, Integer triggerDaysToExpiry,
                                    String ruleSummary, String status, String triggeredAt,
                                    Long observedPnlAtTriggerCents, String responseKind,
                                    String responseAt, Long responseResultCents,
                                    Long overrideSignedCostCents, String note) {}

    /** Final facts remain separated by execution lane; there is intentionally no combined total. */
    public record ExecutionLaneReview(String lane, int memberCount, int closedMemberCount,
                                      boolean finalResultAvailable, Long realizedPnlCents,
                                      String note) {}

    public record PatternEvidence(String campaignId, String title, String symbol, String executionLane,
                                  BigDecimal underlyingMovePct, String windowStart, String windowEnd,
                                  String observedSource, Long finalResultCents) {}

    public record PatternFinding(String key, String label, String executionLane, String status,
                                 int supportingCampaigns, List<PatternEvidence> evidence, String note) {}

    public record CampaignReview(boolean finalReview, String closedAt,
                                 AuthoredRealizedOverlay authoredVsRealized,
                                 List<ProtocolAdherence> protocolAdherence,
                                 List<ExecutionLaneReview> executionLanes,
                                 List<PatternFinding> patterns, String lessonNote,
                                 List<String> receipts) {}

    public record CampaignView(String id, String title, String symbol, String status,
                               String accountObjectiveRevisionId, String createdAt, String updatedAt,
                               List<MemberView> members, long netOptionCashCents, BasisView economicBasis,
                               YieldView yield, CounterfactualView counterfactuals,
                               long attributedDividendsCents, long explicitInterestCents, ChurnView churn,
                               List<AccountSubtotal> accounts, long unassignedPendingCents,
                               int pendingCount, List<String> receipts, List<LedgerEntry> ledger,
                               String lessonNote, String closedAt, CampaignReview review) {}

    /** An auto-link PROPOSAL: never a member until the user confirms it (honesty contract). */
    public record Proposal(String type, String id, String label, String reason, String occurredAt,
                           Long cashEffectCents) {}

    // ---- Internal shapes ----

    private record CampaignRow(String id, String userId, String symbol, String title, String status,
                               String objectiveRevisionId, String lessonNote, OffsetDateTime closedAt,
                               String createdAt, String updatedAt) {}

    private record Leg(String instrumentType, String action, String positionEffect, String symbol,
                       String optionType, BigDecimal strike, LocalDate expiration, long quantity,
                       int multiplier, BigDecimal price, long grossAmountCents, long allocatedFeeCents) {}

    private record TxnEvent(String id, String accountId, String accountName, String accountType,
                            OffsetDateTime occurredAt, String eventType, long cashEffectCents,
                            long feesCents, boolean explicitInterest, String attachedAt,
                            List<Leg> legs) {}

    private record PendingEvent(String id, OffsetDateTime occurredAt, long packageNetCents,
                                String sourceSystem, String status, String attachedAt) {}

    private record ObservedPoint(LocalDate date, long closeCents) {}
    private record ObservedSeries(String source, boolean adjusted, List<ObservedPoint> points) {}

    // ---- CRUD ----

    public List<CampaignView> list(String ownerId) {
        String owner = OwnerScope.id(ownerId);
        return db.with(c -> {
            List<String> ids = Db.queryOn(c, "SELECT id FROM campaign WHERE user_id=? "
                    + "ORDER BY status, updated_at DESC, id", r -> r.str("id"), owner);
            List<CampaignView> out = new ArrayList<>(ids.size());
            for (String id : ids) out.add(assemble(c, requireCampaign(c, owner, id)));
            return out;
        });
    }

    public CampaignView create(String ownerId, CreateInput input) {
        if (input == null) throw new IllegalArgumentException("campaign details are required");
        String title = text(input.title(), "campaign name", 120);
        String symbol = normalizeSymbol(input.symbol());
        String owner = OwnerScope.id(ownerId);
        String id = Ids.newId("cmp");
        return db.tx(c -> {
            OwnerScope.ensure(c, owner);
            if (input.accountObjectiveRevisionId() != null && !input.accountObjectiveRevisionId().isBlank()) {
                requireOwned(c, owner, "account objective revision", input.accountObjectiveRevisionId(),
                        "SELECT r.id FROM account_objective_revision r "
                                + "JOIN portfolio_account a ON a.id=r.portfolio_account_id "
                                + "WHERE r.id=? AND a.user_id=?");
            }
            Db.execOn(c, "INSERT INTO campaign(id,user_id,symbol,title,status,account_objective_revision_id) "
                            + "VALUES (?,?,?,?, 'ACTIVE', ?)",
                    id, owner, symbol, title,
                    blankToNull(input.accountObjectiveRevisionId()));
            // Seed lots are the user's own deliberate starting point ("start a campaign from this
            // position"), so their opening transactions attach as CONFIRMED members; everything
            // discovered from them stays a proposal until confirmed.
            if (input.seedLotIds() != null) {
                for (String lotId : new LinkedHashSet<>(input.seedLotIds())) {
                    if (lotId == null || lotId.isBlank()) continue;
                    String txId = one(c, "SELECT l.opening_transaction_id v FROM portfolio_lot l "
                                    + "JOIN portfolio_account a ON a.id=l.portfolio_account_id "
                                    + "WHERE l.id=? AND a.user_id=?", lotId, owner)
                            .orElseThrow(() -> new ResourceNotFoundException("No tracked lot " + lotId));
                    Db.execOn(c, "INSERT INTO campaign_transaction_member(campaign_id,transaction_id,explicit_interest) "
                            + "VALUES (?,?,0) ON CONFLICT DO NOTHING", id, txId);
                }
            }
            return assemble(c, requireCampaign(c, owner, id));
        });
    }

    public CampaignView view(String ownerId, String campaignId) {
        String owner = OwnerScope.id(ownerId);
        return db.with(c -> assemble(c, requireCampaign(c, owner, campaignId)));
    }

    public CampaignView update(String ownerId, String campaignId, UpdateInput input) {
        if (input == null) throw new IllegalArgumentException("campaign changes are required");
        String owner = OwnerScope.id(ownerId);
        return db.tx(c -> {
            CampaignRow row = requireCampaign(c, owner, campaignId);
            String title = input.title() == null ? row.title() : text(input.title(), "campaign name", 120);
            String status = input.status() == null ? row.status()
                    : enumValue(input.status(), STATUSES, "campaign status");
            String lesson = input.lessonNote() == null ? row.lessonNote()
                    : optionalText(input.lessonNote(), "lesson note", 4_000);
            OffsetDateTime changedAt = OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
            OffsetDateTime closedAt = "ACTIVE".equals(status) ? null
                    : row.closedAt() == null ? changedAt : row.closedAt();
            Db.execOn(c, "UPDATE campaign SET title=?, status=?, lesson_note=?, closed_at=?, updated_at=? WHERE id=?",
                    title, status, lesson, closedAt, changedAt, row.id());
            return assemble(c, requireCampaign(c, owner, campaignId));
        });
    }

    // ---- Typed membership (attach / detach) ----

    public CampaignView attach(String ownerId, String campaignId, MemberInput input) {
        if (input == null) throw new IllegalArgumentException("member details are required");
        String type = enumValue(input.type(), MEMBER_TYPES, "member type");
        String memberId = text(input.id(), "member id", 120);
        String owner = OwnerScope.id(ownerId);
        return db.tx(c -> {
            CampaignRow row = requireCampaign(c, owner, campaignId);
            switch (type) {
                case "TRANSACTION" -> {
                    requireOwned(c, owner, "tracked transaction", memberId,
                            "SELECT t.id FROM portfolio_transaction t "
                                    + "JOIN portfolio_account a ON a.id=t.portfolio_account_id "
                                    + "WHERE t.id=? AND a.user_id=?");
                    boolean interest = one(c, "SELECT event_type v FROM portfolio_transaction WHERE id=?", memberId)
                            .map("INTEREST"::equals).orElse(false);
                    boolean tagged = Boolean.TRUE.equals(input.explicitInterest());
                    if (interest && !tagged) {
                        throw new IllegalArgumentException("Interest joins a campaign only with an explicit tag: "
                                + "account-level sweep interest is never auto-assigned. Pass explicitInterest=true "
                                + "to state that this interest belongs to this campaign.");
                    }
                    if (tagged && !interest) {
                        throw new IllegalArgumentException("Only an INTEREST transaction can carry the explicit "
                                + "campaign-interest tag.");
                    }
                    Db.execOn(c, "INSERT INTO campaign_transaction_member(campaign_id,transaction_id,explicit_interest) "
                            + "VALUES (?,?,?) ON CONFLICT DO NOTHING", row.id(), memberId, tagged ? 1 : 0);
                }
                case "PENDING_IMPORT" -> {
                    requireOwned(c, owner, "pending import", memberId,
                            "SELECT id FROM portfolio_import_pending WHERE id=? AND user_id=? "
                                    + "AND status='PENDING'");
                    Db.execOn(c, "INSERT INTO campaign_pending_member(campaign_id,pending_id) VALUES (?,?) "
                            + "ON CONFLICT DO NOTHING", row.id(), memberId);
                }
                case "PLAN" -> {
                    requireOwned(c, owner, "Plan", memberId,
                            "SELECT id FROM plans WHERE id=? AND user_id=?");
                    Db.execOn(c, "INSERT INTO campaign_plan_member(campaign_id,plan_id) VALUES (?,?) "
                            + "ON CONFLICT DO NOTHING", row.id(), memberId);
                }
                case "PRACTICE_TRADE" -> {
                    requireOwned(c, owner, "practice trade", memberId,
                            "SELECT t.id FROM trades t JOIN accounts a ON a.id=t.account_id "
                                    + "WHERE t.id=? AND a.user_id=?");
                    Db.execOn(c, "INSERT INTO campaign_practice_trade_member(campaign_id,trade_id) VALUES (?,?) "
                            + "ON CONFLICT DO NOTHING", row.id(), memberId);
                }
                case "STRUCTURE" -> {
                    requireOwned(c, owner, "position structure", memberId,
                            "SELECT id FROM portfolio_structure WHERE id=? AND user_id=?");
                    Db.execOn(c, "INSERT INTO campaign_structure_member(campaign_id,structure_id) VALUES (?,?) "
                            + "ON CONFLICT DO NOTHING", row.id(), memberId);
                }
                default -> throw new IllegalArgumentException("unsupported member type " + type);
            }
            Db.execOn(c, "UPDATE campaign SET updated_at=now() WHERE id=?", row.id());
            return assemble(c, requireCampaign(c, owner, campaignId));
        });
    }

    public CampaignView detach(String ownerId, String campaignId, String typeValue, String memberId) {
        String type = enumValue(typeValue, MEMBER_TYPES, "member type");
        String id = text(memberId, "member id", 120);
        String owner = OwnerScope.id(ownerId);
        return db.tx(c -> {
            CampaignRow row = requireCampaign(c, owner, campaignId);
            String sql = switch (type) {
                case "TRANSACTION" -> "DELETE FROM campaign_transaction_member WHERE campaign_id=? AND transaction_id=?";
                case "PENDING_IMPORT" -> "DELETE FROM campaign_pending_member WHERE campaign_id=? AND pending_id=?";
                case "PLAN" -> "DELETE FROM campaign_plan_member WHERE campaign_id=? AND plan_id=?";
                case "PRACTICE_TRADE" -> "DELETE FROM campaign_practice_trade_member WHERE campaign_id=? AND trade_id=?";
                default -> "DELETE FROM campaign_structure_member WHERE campaign_id=? AND structure_id=?";
            };
            if (Db.execOn(c, sql, row.id(), id) == 0) {
                throw new ResourceNotFoundException("That " + type.toLowerCase(Locale.ROOT).replace('_', ' ')
                        + " is not a member of this campaign");
            }
            Db.execOn(c, "UPDATE campaign SET updated_at=now() WHERE id=?", row.id());
            return assemble(c, requireCampaign(c, owner, campaignId));
        });
    }

    /**
     * Replaces every typed pending-import membership with the canonical transaction produced by
     * resolution. The caller owns the surrounding database transaction so the campaign can never
     * observe both the package placeholder and its resolved ledger fact (or neither one).
     */
    public void resolvePendingMembershipOn(Connection c, String ownerId, String pendingId,
                                           String transactionId) throws SQLException {
        String owner = OwnerScope.id(ownerId);
        requireOwned(c, owner, "pending import", pendingId,
                "SELECT id FROM portfolio_import_pending WHERE id=? AND user_id=?");
        requireOwned(c, owner, "tracked transaction", transactionId,
                "SELECT t.id FROM portfolio_transaction t JOIN portfolio_account a "
                        + "ON a.id=t.portfolio_account_id WHERE t.id=? AND a.user_id=?");
        List<Object[]> attached = Db.queryOn(c,
                "SELECT m.campaign_id,m.attached_at FROM campaign_pending_member m "
                        + "JOIN campaign x ON x.id=m.campaign_id "
                        + "WHERE m.pending_id=? AND x.user_id=? ORDER BY m.campaign_id",
                r -> new Object[]{r.str("campaign_id"), r.odt("attached_at")}, pendingId, owner);
        for (Object[] row : attached) {
            String campaignId = (String) row[0];
            Db.execOn(c, "INSERT INTO campaign_transaction_member"
                            + "(campaign_id,transaction_id,explicit_interest,attached_at) VALUES (?,?,0,?) "
                            + "ON CONFLICT DO NOTHING",
                    campaignId, transactionId, row[1]);
            Db.execOn(c, "DELETE FROM campaign_pending_member WHERE campaign_id=? AND pending_id=?",
                    campaignId, pendingId);
            Db.execOn(c, "UPDATE campaign SET updated_at=now() WHERE id=?", campaignId);
        }
    }

    /** Removes a rejected package placeholder from campaign math in the caller-owned transaction. */
    public void rejectPendingMembershipOn(Connection c, String ownerId, String pendingId)
            throws SQLException {
        String owner = OwnerScope.id(ownerId);
        requireOwned(c, owner, "pending import", pendingId,
                "SELECT id FROM portfolio_import_pending WHERE id=? AND user_id=?");
        List<String> attached = Db.queryOn(c,
                "SELECT m.campaign_id FROM campaign_pending_member m "
                        + "JOIN campaign x ON x.id=m.campaign_id "
                        + "WHERE m.pending_id=? AND x.user_id=? ORDER BY m.campaign_id",
                r -> r.str("campaign_id"), pendingId, owner);
        for (String campaignId : attached) {
            Db.execOn(c, "DELETE FROM campaign_pending_member WHERE campaign_id=? AND pending_id=?",
                    campaignId, pendingId);
            Db.execOn(c, "UPDATE campaign SET updated_at=now() WHERE id=?", campaignId);
        }
    }

    // ---- Auto-link proposals ----

    /**
     * Follows the book's own lineage — portfolio_roll / portfolio_roll_match chains ride on the
     * lot↔transaction edges (a roll closes lots via matches and opens a replacement lot), and
     * plan_link / plan_portfolio_action carry Plan lineage — to PROPOSE members. Proposals are
     * returned, never persisted: only a user confirmation writes a typed membership row.
     */
    public List<Proposal> propose(String ownerId, String campaignId, String seedTransactionId,
                                  String seedStructureId, String seedLotId) {
        String owner = OwnerScope.id(ownerId);
        return db.with(c -> {
            CampaignRow row = requireCampaign(c, owner, campaignId);
            Set<String> memberTxns = new LinkedHashSet<>(Db.queryOn(c,
                    "SELECT transaction_id FROM campaign_transaction_member WHERE campaign_id=?",
                    r -> r.str("transaction_id"), row.id()));
            Set<String> memberPlans = new LinkedHashSet<>(Db.queryOn(c,
                    "SELECT plan_id FROM campaign_plan_member WHERE campaign_id=?", r -> r.str("plan_id"), row.id()));
            Set<String> memberStructures = new LinkedHashSet<>(Db.queryOn(c,
                    "SELECT structure_id FROM campaign_structure_member WHERE campaign_id=?",
                    r -> r.str("structure_id"), row.id()));
            Set<String> memberTrades = new LinkedHashSet<>(Db.queryOn(c,
                    "SELECT trade_id FROM campaign_practice_trade_member WHERE campaign_id=?",
                    r -> r.str("trade_id"), row.id()));
            Set<String> memberPending = new LinkedHashSet<>(Db.queryOn(c,
                    "SELECT pending_id FROM campaign_pending_member WHERE campaign_id=?",
                    r -> r.str("pending_id"), row.id()));

            Set<String> seedTxns = new LinkedHashSet<>(memberTxns);
            if (seedTransactionId != null && !seedTransactionId.isBlank()) {
                requireOwned(c, owner, "tracked transaction", seedTransactionId,
                        "SELECT t.id FROM portfolio_transaction t JOIN portfolio_account a "
                                + "ON a.id=t.portfolio_account_id WHERE t.id=? AND a.user_id=?");
                seedTxns.add(seedTransactionId);
            }
            if (seedLotId != null && !seedLotId.isBlank()) {
                one(c, "SELECT l.opening_transaction_id v FROM portfolio_lot l "
                        + "JOIN portfolio_account a ON a.id=l.portfolio_account_id "
                        + "WHERE l.id=? AND a.user_id=?", seedLotId, owner).ifPresent(seedTxns::add);
            }
            Set<String> seedStructures = new LinkedHashSet<>(memberStructures);
            if (seedStructureId != null && !seedStructureId.isBlank()) {
                requireOwned(c, owner, "position structure", seedStructureId,
                        "SELECT id FROM portfolio_structure WHERE id=? AND user_id=?");
                seedStructures.add(seedStructureId);
            }
            for (String structureId : seedStructures) {
                seedTxns.addAll(inList(c, "SELECT DISTINCT transaction_id v FROM portfolio_structure_revision "
                        + "WHERE structure_id=? AND transaction_id IS NOT NULL", List.of(structureId)));
            }

            // 1) Lot / roll-chain closure: transaction -> lots it opened or closed -> the
            //    transactions that opened or closed those lots, to a fixpoint.
            Map<String, String> chainReason = new LinkedHashMap<>();
            Set<String> chain = new LinkedHashSet<>(seedTxns);
            Set<String> seenLots = new LinkedHashSet<>();
            ArrayDeque<String> frontier = new ArrayDeque<>(seedTxns);
            int hops = 0;
            while (!frontier.isEmpty() && hops++ < 64) {
                List<String> batch = new ArrayList<>(frontier);
                frontier.clear();
                Set<String> lots = new LinkedHashSet<>();
                lots.addAll(inList(c, "SELECT id v FROM portfolio_lot WHERE opening_transaction_id IN (%s)", batch));
                lots.addAll(inList(c, "SELECT lot_id v FROM portfolio_lot_match WHERE closing_transaction_id IN (%s)", batch));
                lots.removeAll(seenLots);
                if (lots.isEmpty()) continue;
                seenLots.addAll(lots);
                List<String> lotList = new ArrayList<>(lots);
                Set<String> next = new LinkedHashSet<>();
                next.addAll(inList(c, "SELECT opening_transaction_id v FROM portfolio_lot WHERE id IN (%s)", lotList));
                next.addAll(inList(c, "SELECT closing_transaction_id v FROM portfolio_lot_match WHERE lot_id IN (%s)", lotList));
                next.removeAll(chain);
                for (String txId : next) {
                    chain.add(txId);
                    chainReason.put(txId, "Linked through this campaign's lot and roll chain");
                    frontier.add(txId);
                }
            }

            // 2) Plan lineage: chain transactions -> plans -> sibling recorded transactions and
            //    rehearsal practice trades.
            Set<String> planIds = new LinkedHashSet<>(memberPlans);
            planIds.addAll(inList(c, "SELECT DISTINCT plan_id v FROM plan_portfolio_action "
                    + "WHERE transaction_id IN (%s)", new ArrayList<>(chain)));
            Map<String, String> planReason = new LinkedHashMap<>();
            for (String planId : planIds) {
                if (!memberPlans.contains(planId)) {
                    planReason.put(planId, "This Plan recorded activity already inside the campaign");
                }
            }
            Set<String> planTxns = new LinkedHashSet<>();
            Set<String> planTrades = new LinkedHashSet<>();
            if (!planIds.isEmpty()) {
                List<String> planList = new ArrayList<>(planIds);
                planTxns.addAll(inList(c, "SELECT DISTINCT transaction_id v FROM plan_portfolio_action "
                        + "WHERE plan_id IN (%s)", planList));
                planTrades.addAll(inList(c, "SELECT DISTINCT trade_id v FROM plan_link "
                        + "WHERE trade_id IS NOT NULL AND plan_id IN (%s)", planList));
            }

            // 3) Structures whose revisions record chain transactions.
            Set<String> structures = new LinkedHashSet<>();
            structures.addAll(inList(c, "SELECT DISTINCT structure_id v FROM portfolio_structure_revision "
                    + "WHERE transaction_id IN (%s)", new ArrayList<>(chain)));

            // 4) Same-symbol activity and cash events during the campaign's holding window —
            //    weaker evidence, so the reason says exactly why and asks for confirmation.
            Set<String> windowTxns = new LinkedHashSet<>();
            Set<String> windowCash = new LinkedHashSet<>();
            Set<String> pendings = new LinkedHashSet<>();
            if (!chain.isEmpty()) {
                List<String> chainList = new ArrayList<>(chain);
                List<String> accountIds = inListDistinct(c, "SELECT DISTINCT portfolio_account_id v "
                        + "FROM portfolio_transaction WHERE id IN (%s)", chainList);
                var window = Db.queryOn(c, params("SELECT MIN(occurred_at) lo, MAX(occurred_at) hi "
                                + "FROM portfolio_transaction WHERE id IN (%s)", chainList.size()),
                        r -> new OffsetDateTime[]{r.odt("lo"), r.odt("hi")}, chainList.toArray());
                OffsetDateTime lo = window.isEmpty() ? null : window.getFirst()[0];
                OffsetDateTime hi = window.isEmpty() ? null : window.getFirst()[1];
                if (hi != null && "ACTIVE".equals(row.status())) hi = OffsetDateTime.now(clock);
                if (lo != null && !accountIds.isEmpty()) {
                    if (row.symbol() != null) {
                        Object[] args = concat(accountIds.toArray(), row.symbol(), lo, hi);
                        windowTxns.addAll(Db.queryOn(c, params("SELECT DISTINCT t.id v FROM portfolio_transaction t "
                                        + "JOIN portfolio_transaction_leg l ON l.transaction_id=t.id "
                                        + "WHERE t.portfolio_account_id IN (%s) AND l.symbol=? "
                                        + "AND t.occurred_at BETWEEN ? AND ?", accountIds.size()),
                                r -> r.str("v"), args));
                    }
                    Object[] cashArgs = concat(accountIds.toArray(), lo, hi);
                    windowCash.addAll(Db.queryOn(c, params("SELECT id v FROM portfolio_transaction "
                                    + "WHERE portfolio_account_id IN (%s) AND event_type IN ('DIVIDEND','INTEREST') "
                                    + "AND occurred_at BETWEEN ? AND ?", accountIds.size()),
                            r -> r.str("v"), cashArgs));
                    if (row.symbol() != null) {
                        pendings.addAll(Db.queryOn(c, "SELECT p.id v FROM portfolio_import_pending p "
                                        + "WHERE p.user_id=? AND p.status IN ('PENDING','PROVISIONAL') "
                                        + "AND p.occurred_at BETWEEN ? AND ? AND EXISTS (SELECT 1 "
                                        + "FROM portfolio_import_pending_leg pil WHERE pil.pending_id=p.id "
                                        + "AND pil.symbol=?)",
                                r -> r.str("v"), owner, lo, hi, row.symbol()));
                    }
                }
            }

            // Assemble proposals, excluding anything already confirmed.
            List<Proposal> out = new ArrayList<>();
            Set<String> proposedTxns = new LinkedHashSet<>();
            for (String txId : chain) {
                if (memberTxns.contains(txId) || !proposedTxns.add(txId)) continue;
                out.add(transactionProposal(c, owner, txId, chainReason.getOrDefault(txId,
                        "Linked through this campaign's lot and roll chain")));
            }
            for (String txId : planTxns) {
                if (memberTxns.contains(txId) || chain.contains(txId) || !proposedTxns.add(txId)) continue;
                out.add(transactionProposal(c, owner, txId, "Recorded by a Plan already tied to this campaign"));
            }
            for (String txId : windowTxns) {
                if (memberTxns.contains(txId) || chain.contains(txId) || planTxns.contains(txId)
                        || !proposedTxns.add(txId)) continue;
                out.add(transactionProposal(c, owner, txId, row.symbol() + " activity while this campaign "
                        + "held the position — confirm it belongs here"));
            }
            for (String txId : windowCash) {
                if (memberTxns.contains(txId) || chain.contains(txId) || planTxns.contains(txId)
                        || !proposedTxns.add(txId)) continue;
                out.add(transactionProposal(c, owner, txId, "Cash event inside the campaign window — "
                        + "attribution needs your confirmation, it is never automatic"));
            }
            for (String planId : planIds) {
                if (memberPlans.contains(planId)) continue;
                var plan = Db.queryOn(c, "SELECT id, symbol, custom_title, status FROM plans WHERE id=? AND user_id=?",
                        r -> new String[]{r.str("id"), r.str("symbol"), r.str("custom_title"), r.str("status")},
                        planId, owner);
                if (plan.isEmpty()) continue;
                String[] p = plan.getFirst();
                out.add(new Proposal("PLAN", p[0], (p[2] != null && !p[2].isBlank() ? p[2] : p[1] + " Plan")
                        + " · " + p[3].toLowerCase(Locale.ROOT),
                        planReason.getOrDefault(planId, "Plan lineage"), null, null));
            }
            for (String tradeId : planTrades) {
                if (memberTrades.contains(tradeId)) continue;
                var trade = Db.queryOn(c, "SELECT t.id, t.symbol, t.strategy, t.status FROM trades t "
                                + "JOIN accounts a ON a.id=t.account_id WHERE t.id=? AND a.user_id=?",
                        r -> new String[]{r.str("id"), r.str("symbol"), r.str("strategy"), r.str("status")},
                        tradeId, owner);
                if (trade.isEmpty()) continue;
                String[] t = trade.getFirst();
                out.add(new Proposal("PRACTICE_TRADE", t[0], t[1] + " " + t[2].toLowerCase(Locale.ROOT)
                        + " · practice · " + t[3].toLowerCase(Locale.ROOT),
                        "Rehearsed under a Plan tied to this campaign (practice is listed side-by-side, "
                                + "never netted)", null, null));
            }
            for (String structureId : structures) {
                if (memberStructures.contains(structureId)) continue;
                var st = Db.queryOn(c, "SELECT id, symbol, label FROM portfolio_structure WHERE id=? AND user_id=?",
                        r -> new String[]{r.str("id"), r.str("symbol"), r.str("label")}, structureId, owner);
                if (st.isEmpty()) continue;
                String[] s = st.getFirst();
                out.add(new Proposal("STRUCTURE", s[0], (s[2] != null && !s[2].isBlank() ? s[2] : s[1] + " structure"),
                        "This structure's revisions record campaign transactions", null, null));
            }
            for (String pendingId : pendings) {
                if (memberPending.contains(pendingId)) continue;
                var pend = Db.queryOn(c, "SELECT id, source_system, occurred_at, package_net_cents "
                                + "FROM portfolio_import_pending WHERE id=? AND user_id=? "
                                + "AND status IN ('PENDING','PROVISIONAL')",
                        r -> new Object[]{r.str("id"), r.str("source_system"), r.str("occurred_at"),
                                r.lng("package_net_cents")}, pendingId, owner);
                if (pend.isEmpty()) continue;
                Object[] p = pend.getFirst();
                out.add(new Proposal("PENDING_IMPORT", (String) p[0],
                        "Unresolved " + p[1] + " import", "Pending import inside the campaign window — "
                        + "it would contribute exact package cash only", (String) p[2], (Long) p[3]));
            }
            return List.copyOf(out);
        });
    }

    private Proposal transactionProposal(Connection c, String owner, String txId, String reason)
            throws SQLException {
        var rows = Db.queryOn(c, "SELECT t.id, t.occurred_at, t.event_type, t.cash_effect_cents "
                        + "FROM portfolio_transaction t JOIN portfolio_account a ON a.id=t.portfolio_account_id "
                        + "WHERE t.id=? AND a.user_id=?",
                r -> new Object[]{r.str("id"), r.str("occurred_at"), r.str("event_type"),
                        r.lng("cash_effect_cents")}, txId, owner);
        if (rows.isEmpty()) throw new ResourceNotFoundException("No tracked transaction " + txId);
        Object[] t = rows.getFirst();
        return new Proposal("TRANSACTION", (String) t[0],
                legsLabel(c, (String) t[0], (String) t[2]), reason, (String) t[1], (Long) t[3]);
    }

    // ---- Assembly: the §4 outputs, all through CampaignMath ----

    private CampaignView assemble(Connection c, CampaignRow row) throws SQLException {
        List<TxnEvent> txns = loadTransactionMembers(c, row.id());
        List<PendingEvent> pending = loadPendingMembers(c, row.id());
        List<MemberView> members = new ArrayList<>();
        List<String> receipts = new ArrayList<>();

        // Chronological accumulation ledger over real transactions + pending package cash.
        record Event(OffsetDateTime at, TxnEvent txn, PendingEvent pend) {}
        List<Event> events = new ArrayList<>();
        for (TxnEvent t : txns) events.add(new Event(t.occurredAt(), t, null));
        for (PendingEvent p : pending) events.add(new Event(p.occurredAt(), null, p));
        events.sort(Comparator.comparing(Event::at)
                .thenComparing(e -> e.txn() != null ? e.txn().id() : e.pend().id()));

        long runShares = 0, runNetCash = 0, runShareCash = 0, runDividends = 0, runFees = 0;
        long runObligation = 0, peakCommitted = 0;
        List<Long> optionCashPieces = new ArrayList<>();
        long grossPremium = 0, dividends = 0, interest = 0;
        List<LedgerEntry> ledger = new ArrayList<>();
        OffsetDateTime prevAt = null;
        long prevCommitted = 0;
        BigDecimal weightedCapitalDays = BigDecimal.ZERO;
        OffsetDateTime firstAt = events.isEmpty() ? null : events.getFirst().at();

        for (Event e : events) {
            long cash, sharesDelta = 0;
            String memberType, memberId, eventType, label;
            if (e.txn() != null) {
                TxnEvent t = e.txn();
                memberType = "TRANSACTION"; memberId = t.id(); eventType = t.eventType();
                cash = t.cashEffectCents();
                long optionCash = 0;
                for (Leg leg : t.legs()) {
                    boolean sell = "SELL".equals(leg.action());
                    long signed = sell ? leg.grossAmountCents() : Math.negateExact(leg.grossAmountCents());
                    if ("OPTION".equals(leg.instrumentType())) {
                        optionCash = Math.addExact(optionCash,
                                Math.subtractExact(signed, leg.allocatedFeeCents()));
                        if (sell) grossPremium = Math.addExact(grossPremium, leg.grossAmountCents());
                        if ("PUT".equals(leg.optionType())) {
                            long strikeCash = strikeCents(leg.strike(), leg.quantity(), leg.multiplier());
                            if (sell && "OPEN".equals(leg.positionEffect())) {
                                runObligation = Math.addExact(runObligation, strikeCash);
                            } else if (!sell && "CLOSE".equals(leg.positionEffect())) {
                                runObligation = Math.max(0, Math.subtractExact(runObligation, strikeCash));
                            }
                        }
                    } else {
                        runShareCash = Math.addExact(runShareCash, Math.negateExact(signed));
                        sharesDelta = Math.addExact(sharesDelta, sell ? -leg.quantity() : leg.quantity());
                        runFees = Math.addExact(runFees, leg.allocatedFeeCents());
                    }
                }
                if (t.legs().stream().anyMatch(l -> "OPTION".equals(l.instrumentType()))) {
                    optionCashPieces.add(optionCash);
                }
                if ("DIVIDEND".equals(eventType)) { dividends = Math.addExact(dividends, cash); runDividends = dividends; }
                if ("FEE".equals(eventType)) runFees = Math.addExact(runFees, Math.abs(cash));
                if ("INTEREST".equals(eventType) && t.explicitInterest()) interest = Math.addExact(interest, cash);
                label = legsLabel(c, t.id(), eventType);
            } else {
                PendingEvent p = e.pend();
                memberType = "PENDING_IMPORT"; memberId = p.id(); eventType = "PENDING_IMPORT";
                cash = p.packageNetCents();
                label = "Unresolved " + p.sourceSystem() + " import · exact package cash only";
            }
            runShares = Math.addExact(runShares, sharesDelta);
            runNetCash = Math.addExact(runNetCash, cash);
            long committed = Math.max(0, Math.subtractExact(runObligation, runNetCash));
            if (prevAt != null) {
                long days = ChronoUnit.DAYS.between(prevAt.toLocalDate(), e.at().toLocalDate());
                if (days > 0) weightedCapitalDays = weightedCapitalDays
                        .add(BigDecimal.valueOf(prevCommitted).multiply(BigDecimal.valueOf(days)));
            }
            prevAt = e.at(); prevCommitted = committed;
            peakCommitted = Math.max(peakCommitted, committed);
            Long runningBasis = null;
            if (runShares > 0) {
                try {
                    runningBasis = CampaignMath.campaignAdjustedEconomicBasisPerShareCents(
                            runShareCash, CampaignMath.campaignNetCredit(optionCashPieces),
                            runDividends, runFees, runShares);
                } catch (RuntimeException ignored) { /* stays honestly null */ }
            }
            ledger.add(new LedgerEntry(iso(e.at()), memberType, memberId, eventType, label, cash,
                    sharesDelta, runShares, CampaignMath.campaignNetCredit(optionCashPieces),
                    runningBasis, committed));
        }

        long netOptionCash = CampaignMath.campaignNetCredit(optionCashPieces);

        // §4.2 campaign-adjusted economic basis (NEVER presented as tax basis).
        BasisView basis;
        if (runShares > 0) {
            long perShare = CampaignMath.campaignAdjustedEconomicBasisPerShareCents(
                    runShareCash, netOptionCash, dividends, runFees, runShares);
            basis = new BasisView(true, perShare, runShares, runShareCash, netOptionCash, dividends,
                    runFees, "Interpretation layer only; the tracked tax basis is separate and can "
                    + "legitimately differ (wash-sale adjustments land there).");
        } else {
            basis = new BasisView(false, null, runShares, runShareCash, netOptionCash, dividends,
                    runFees, "No shares are currently held inside this campaign, so a per-share "
                    + "campaign-adjusted economic basis is not defined.");
        }

        // Realized campaign P/L at recorded basis: every member cash flow plus the recorded
        // economic carrying value of what the members still hold — marks never enter this number.
        long carry = 0;
        boolean openOptionCarry = false;
        if (!txns.isEmpty()) {
            List<String> txnIds = txns.stream().map(TxnEvent::id).toList();
            var lots = Db.queryOn(c, params("SELECT instrument_type, side, economic_remaining_open_amount_cents v "
                            + "FROM portfolio_lot WHERE remaining_quantity>0 AND opening_transaction_id IN (%s)",
                    txnIds.size()),
                    r -> new Object[]{r.str("instrument_type"), r.str("side"), r.lng("v")}, txnIds.toArray());
            for (Object[] lot : lots) {
                long amount = (Long) lot[2];
                carry = Math.addExact(carry, "LONG".equals(lot[1]) ? amount : Math.negateExact(amount));
                if ("OPTION".equals(lot[0])) openOptionCarry = true;
            }
        }
        long realizedPnl = Math.addExact(runNetCash, carry);

        // §4.3 realized-vs-headline on identical denominators.
        OffsetDateTime asOf = "ACTIVE".equals(row.status()) || events.isEmpty()
                ? OffsetDateTime.now(clock) : events.getLast().at();
        int days = firstAt == null ? 0
                : (int) Math.max(1, ChronoUnit.DAYS.between(firstAt.toLocalDate(), asOf.toLocalDate()));
        if (prevAt != null) {
            long tailDays = ChronoUnit.DAYS.between(prevAt.toLocalDate(), asOf.toLocalDate());
            if (tailDays > 0) weightedCapitalDays = weightedCapitalDays
                    .add(BigDecimal.valueOf(prevCommitted).multiply(BigDecimal.valueOf(tailDays)));
        }
        YieldView yield;
        if (peakCommitted > 0 && days > 0) {
            var comparison = CampaignMath.realizedVsHeadlineYield(grossPremium, peakCommitted,
                    realizedPnl, peakCommitted, days, days);
            long twc = weightedCapitalDays.signum() > 0
                    ? weightedCapitalDays.divide(BigDecimal.valueOf(days), 0, RoundingMode.HALF_EVEN).longValueExact()
                    : peakCommitted;
            BigDecimal twcPct = twc > 0
                    ? BigDecimal.valueOf(realizedPnl).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(twc), 6, RoundingMode.HALF_EVEN)
                    : null;
            yield = new YieldView(true, comparison.headlinePeriodPct(), comparison.headlineAnnualizedPct(),
                    comparison.realizedPeriodPct(), comparison.realizedAnnualizedPct(), twcPct,
                    peakCommitted, twc, grossPremium, realizedPnl, days,
                    "Both sides divide by the same peak committed capital over the same "
                            + days + "-day window. Annualized figures assume the result repeats.");
        } else {
            yield = new YieldView(false, null, null, null, null, null, peakCommitted, 0,
                    grossPremium, realizedPnl, days,
                    "No external capital has been committed yet, so an honest yield has no denominator.");
        }

        // §4.4 counterfactual benchmarks, live during the campaign.
        CounterfactualView counterfactuals = counterfactuals(c, row, txns, pending, asOf, openOptionCarry);

        // §4 items 8*/rounding: churn round trips with largest-remainder-safe fee splits.
        ChurnView churn = churn(txns);

        // Per-account subtotals (§3.12: IRA and taxable cash are not fungible).
        List<AccountSubtotal> accounts = accountSubtotals(txns);
        long unassignedPending = pending.stream().mapToLong(PendingEvent::packageNetCents).sum();

        // Members list (typed; practice and plans listed but never netted).
        for (TxnEvent t : txns) {
            boolean counted = !"INTEREST".equals(t.eventType()) || t.explicitInterest();
            members.add(new MemberView("TRANSACTION", t.id(), legsLabel(c, t.id(), t.eventType()),
                    t.accountName() + " · " + t.accountType(), iso(t.occurredAt()), t.cashEffectCents(),
                    t.explicitInterest(), counted, t.attachedAt()));
        }
        for (PendingEvent p : pending) {
            members.add(new MemberView("PENDING_IMPORT", p.id(),
                    "Unresolved " + p.sourceSystem() + " import", "exact package cash only",
                    iso(p.occurredAt()), p.packageNetCents(), false, true, p.attachedAt()));
        }
        members.addAll(loadSimpleMembers(c, row.id()));

        if (!pending.isEmpty()) {
            receipts.add("Contains unresolved imports — tax figures withheld. Pending imports "
                    + "contribute exact package cash only until they are resolved.");
        }
        if (members.stream().anyMatch(m -> "PRACTICE_TRADE".equals(m.type()))) {
            receipts.add("Practice members are shown side-by-side for lineage and are never "
                    + "numerically netted with real results.");
        }
        if (openOptionCarry) {
            receipts.add("Open option legs are carried at their recorded basis, not marks.");
        }
        long strayClosers = strayClosingTransactions(c, txns, row.id());
        if (strayClosers > 0) {
            receipts.add(strayClosers + " recorded transaction" + (strayClosers == 1 ? "" : "s")
                    + " close lots opened inside this campaign but are not members — review the "
                    + "suggestions so the campaign's figures stay complete.");
        }
        receipts.add("A campaign is an interpretation layer, never the accounting source: it never "
                + "rewrites the tracked tax basis, tax character, or account ledgers.");

        CampaignReview review = campaignReview(c, row, txns, pending, yield, counterfactuals);

        return new CampaignView(row.id(), row.title(), row.symbol(), row.status(),
                row.objectiveRevisionId(), row.createdAt(), row.updatedAt(), List.copyOf(members),
                netOptionCash, basis, yield, counterfactuals, dividends, interest, churn,
                accounts, unassignedPending, pending.size(), List.copyOf(receipts), List.copyOf(ledger),
                row.lessonNote(), iso(row.closedAt()), review);
    }

    // ---- Journey E: close -> review -> lesson, on the canonical campaign read ----

    private CampaignReview campaignReview(Connection c, CampaignRow row, List<TxnEvent> txns,
                                          List<PendingEvent> pending, YieldView yield,
                                          CounterfactualView counterfactuals) throws SQLException {
        boolean finalReview = !"ACTIVE".equals(row.status()) && row.closedAt() != null;
        List<String> reviewReceipts = new ArrayList<>();
        reviewReceipts.add("Review facts are read from frozen Plan decisions, saved Scenario Canvas receipts, "
                + "recorded marks/actions, and the campaign's accounting members. Saving a lesson cannot rewrite them.");
        if (!finalReview) {
            reviewReceipts.add("This campaign is still active. The review is a live preview; its final window "
                    + "and final accounting freeze when the campaign closes.");
        }
        AuthoredRealizedOverlay overlay = authoredRealizedOverlay(c, row, finalReview);
        List<ProtocolAdherence> adherence = protocolAdherence(c, row, finalReview);
        List<ExecutionLaneReview> lanes = executionLaneReviews(c, row, txns, pending, yield);
        List<PatternFinding> patterns = patternFindings(c, row);
        return new CampaignReview(finalReview, iso(row.closedAt()), overlay, adherence, lanes,
                patterns, row.lessonNote(), List.copyOf(reviewReceipts));
    }

    private AuthoredRealizedOverlay authoredRealizedOverlay(Connection c, CampaignRow row,
                                                            boolean finalReview) throws SQLException {
        record ScenarioHead(String planId, String planTitle, String symbol, String marketLane,
                            String scenarioId, String scenarioTitle, OffsetDateTime authoredAt,
                            int contextRev,
                            String baseEnsembleId, String scenarioFingerprint,
                            String baseFingerprint, String waypointFill, LocalDate anchorDate,
                            long anchorSpotCents, String anchorSource, String anchorFreshness,
                            int horizonDays) {}
        OffsetDateTime authoredCutoff = finalReview ? row.closedAt()
                : OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
        List<ScenarioHead> heads = Db.queryOn(c,
                "SELECT p.id plan_id,COALESCE(NULLIF(p.custom_title,''),p.symbol||' Plan') plan_title," +
                        "p.symbol,p.market_kind,a.id scenario_id,a.title scenario_title,a.created_at authored_at," +
                        "a.context_rev," +
                        "a.base_ensemble_id,a.fingerprint scenario_fingerprint," +
                        "pe.fingerprint base_fingerprint,a.waypoint_fill," +
                        "COALESCE(pc.anchor_date,(pe.as_of AT TIME ZONE 'America/New_York')::date) anchor_date," +
                        "pe.anchor_spot_cents,pe.anchor_source,pe.anchor_freshness,a.spec_horizon_days " +
                        "FROM campaign_plan_member cm JOIN plans p ON p.id=cm.plan_id " +
                        "JOIN authored_scenario a ON a.plan_id=p.id " +
                        "JOIN plan_ensemble pe ON pe.id=a.base_ensemble_id AND pe.plan_id=a.plan_id " +
                        "LEFT JOIN plan_ensemble_canvas pc ON pc.ensemble_id=pe.id " +
                        "WHERE cm.campaign_id=? AND a.created_at<=? " +
                        "ORDER BY a.created_at DESC,a.id DESC LIMIT 1",
                r -> new ScenarioHead(r.str("plan_id"), r.str("plan_title"), r.str("symbol"),
                        r.str("market_kind"), r.str("scenario_id"), r.str("scenario_title"),
                        r.odt("authored_at"), r.intv("context_rev"), r.str("base_ensemble_id"),
                        r.str("scenario_fingerprint"), r.str("base_fingerprint"),
                        r.str("waypoint_fill"), r.date("anchor_date"), r.lng("anchor_spot_cents"),
                        r.str("anchor_source"), r.str("anchor_freshness"),
                        r.intv("spec_horizon_days")), row.id(), authoredCutoff);
        if (heads.isEmpty()) {
            return new AuthoredRealizedOverlay(false, null, null, null, null, null, 0,
                    null, null, null, null, null, 0, null, null, null,
                    null, null, null, List.of(), List.of(),
                    "Unavailable — no confirmed Plan member in this campaign has an authored scenario saved "
                            + (finalReview ? "on or before the campaign close. " : "as of this review. ")
                            + "StrikeBench will not substitute a post-hoc or fresh simulation, or an unattached Plan.");
        }
        ScenarioHead head = heads.getFirst();
        List<AuthoredPathPoint> authored = new ArrayList<>();
        authored.add(new AuthoredPathPoint(0, head.anchorDate().toString(), head.anchorSpotCents()));
        Db.queryOn(c, "SELECT day_index,price_ratio FROM authored_scenario_waypoint " +
                        "WHERE scenario_id=? ORDER BY day_index",
                r -> new Object[]{r.intv("day_index"), r.dbl("price_ratio")}, head.scenarioId())
                .forEach(raw -> {
                    int day = (Integer) raw[0];
                    long cents = BigDecimal.valueOf((Double) raw[1])
                            .multiply(BigDecimal.valueOf(head.anchorSpotCents()))
                            .setScale(0, RoundingMode.HALF_EVEN).longValueExact();
                    authored.add(new AuthoredPathPoint(day,
                            MarketHours.tradingDateAfter(head.anchorDate(), day).toString(), cents));
                });
        LocalDate horizonEnd = MarketHours.tradingDateAfter(head.anchorDate(), head.horizonDays());
        LocalDate cutoff = finalReview ? marketDate(row.closedAt())
                : LocalDate.ofInstant(clock.instant(), MARKET_ZONE);
        if (cutoff.isAfter(horizonEnd)) cutoff = horizonEnd;
        if (cutoff.isBefore(head.anchorDate())) cutoff = head.anchorDate();
        if (!"OBSERVED".equals(head.marketLane())) {
            return new AuthoredRealizedOverlay(false, head.planId(), head.planTitle(), head.scenarioId(),
                    head.scenarioTitle(), iso(head.authoredAt()), head.contextRev(), head.baseEnsembleId(),
                    head.scenarioFingerprint(), head.baseFingerprint(), head.waypointFill(),
                    head.anchorDate().toString(), head.anchorSpotCents(), head.anchorSource(),
                    head.anchorFreshness(), head.marketLane(), null, null, null, List.copyOf(authored),
                    List.of(), "Unavailable — this saved scenario belongs to the " + head.marketLane()
                            + " market lane. A generated path is never relabeled as realized market history.");
        }
        ObservedSeries series = observedSeries(c, head.symbol(), head.anchorDate(), cutoff);
        List<RealizedPathPoint> realized = series.points().stream()
                .map(point -> new RealizedPathPoint(
                        MarketHours.tradingDaysBetween(head.anchorDate(), point.date()),
                        point.date().toString(), point.closeCents())).toList();
        boolean hasAuthoredLine = authored.size() >= 2;
        boolean available = hasAuthoredLine && realized.size() >= 2;
        String note = available
                ? "Authored " + iso(head.authoredAt()) + "; the line connects only the exact saved pins "
                    + "(plus its anchor) and is not a forecast. "
                    + "Realized closes are one coherent observed series. Fill between pins remains labeled "
                    + head.waypointFill() + "."
                : !hasAuthoredLine
                    ? "Unavailable — the saved Canvas receipt is a stochastic fan with no authored waypoint, "
                        + "so it has no single authored line to compare. Its fill label remains "
                        + head.waypointFill() + "."
                    : "Unavailable — fewer than two coherent eligible observed closes cover the saved scenario window. "
                        + "No Demo, simulated, scenario, fixture, or modeled bars were substituted.";
        return new AuthoredRealizedOverlay(available, head.planId(), head.planTitle(), head.scenarioId(),
                head.scenarioTitle(), iso(head.authoredAt()), head.contextRev(), head.baseEnsembleId(),
                head.scenarioFingerprint(),
                head.baseFingerprint(), head.waypointFill(), head.anchorDate().toString(),
                head.anchorSpotCents(), head.anchorSource(), head.anchorFreshness(), head.marketLane(),
                series.source(), series.source() == null ? null : "observed", series.source() == null
                ? null : series.adjusted(), List.copyOf(authored), realized, note);
    }

    private List<ProtocolAdherence> protocolAdherence(Connection c, CampaignRow row,
                                                      boolean finalReview) throws SQLException {
        record Decision(String planId, String id, String action, Integer qty, long entryCents,
                        OffsetDateTime at, LocalDate nearestExpiry) {}
        record Action(String kind, OffsetDateTime at, Long unrealizedCents, Long realizedCents,
                      String tradeId, Integer tradeQty) {}
        record Seen(String rule, int actionIndex, OffsetDateTime at, Long pnlCents) {}
        List<Decision> decisions = Db.queryOn(c,
                "SELECT d.plan_id,d.id,d.action,d.qty,d.proposed_net_cents,d.quote_as_of," +
                        "(SELECT MIN(l.expiration) FROM plan_decision_leg l " +
                        "WHERE l.decision_id=d.id AND l.expiration IS NOT NULL) nearest_expiry " +
                        "FROM campaign_plan_member cm JOIN plan_decision d ON d.plan_id=cm.plan_id " +
                        "WHERE cm.campaign_id=? AND d.action IN ('TRADE','BROKER') " +
                        "AND d.proposed_net_cents IS NOT NULL ORDER BY d.quote_as_of,d.id",
                r -> new Decision(r.str("plan_id"), r.str("id"), r.str("action"),
                        integerOrNull(r, "qty"), r.lng("proposed_net_cents"), r.odt("quote_as_of"),
                        r.date("nearest_expiry")),
                row.id());
        List<ProtocolAdherence> out = new ArrayList<>();
        for (Decision decision : decisions) {
            List<Action> actions = Db.queryOn(c, "SELECT a.kind,a.action_at,a.unrealized_cents,a.realized_cents," +
                            "a.trade_id,t.qty trade_qty FROM plan_management_action a " +
                            "LEFT JOIN trades t ON t.id=a.trade_id WHERE a.decision_id=? ORDER BY a.action_at,a.id",
                    r -> new Action(r.str("kind"), r.odt("action_at"),
                            r.lngOrNull("unrealized_cents"), r.lngOrNull("realized_cents"),
                            r.str("trade_id"), integerOrNull(r, "trade_qty")), decision.id());
            List<ProtocolEvaluator.Rule> rules = ProtocolEvaluator.rules(decision.entryCents());
            Map<String, Seen> seen = new LinkedHashMap<>();
            Integer entryDte = daysToExpiry(decision.at(), decision.nearestExpiry());
            for (ProtocolEvaluator.Trigger trigger : ProtocolEvaluator.evaluate(
                    new ProtocolEvaluator.Inputs(decision.entryCents(), 0L, entryDte))) {
                seen.putIfAbsent(trigger.rule(), new Seen(trigger.rule(), -1, decision.at(), 0L));
            }
            boolean hasRecordedMark = false;
            for (int i = 0; i < actions.size(); i++) {
                Action action = actions.get(i);
                if ("MARK".equals(action.kind())) hasRecordedMark = true;
                Integer dte = daysToExpiry(action.at(), decision.nearestExpiry());
                for (ProtocolEvaluator.Trigger trigger : ProtocolEvaluator.evaluate(
                        new ProtocolEvaluator.Inputs(decision.entryCents(), action.unrealizedCents(), dte))) {
                    seen.putIfAbsent(trigger.rule(), new Seen(trigger.rule(), i, action.at(),
                            action.unrealizedCents()));
                }
            }
            for (ProtocolEvaluator.Rule rule : rules) {
                Seen trigger = seen.get(rule.rule());
                String lane = "BROKER".equals(decision.action()) ? "REAL" : "PRACTICE";
                if (trigger == null) {
                    boolean knowable = rule.triggerDaysToExpiry() != null
                            ? decision.nearestExpiry() != null : hasRecordedMark;
                    out.add(new ProtocolAdherence(decision.planId(), decision.id(), lane, rule.rule(),
                            rule.triggerPnlCents(), rule.triggerDaysToExpiry(), rule.summary(),
                            knowable ? "NOT_TRIGGERED" : "UNAVAILABLE", null, null, null, null,
                            null, null, knowable
                            ? "The frozen line was not crossed in the recorded evidence."
                            : rule.triggerDaysToExpiry() != null
                                ? "Unavailable — the frozen decision has no option expiry for this time rule."
                                : "Unavailable — no recorded marks can establish whether this price rule fired."));
                    continue;
                }
                int responseIndex = -1;
                for (int i = trigger.actionIndex() + 1; i < actions.size(); i++) {
                    if (isPositionResponse(actions.get(i).kind())) { responseIndex = i; break; }
                }
                Action response = responseIndex < 0 ? null : actions.get(responseIndex);
                boolean triggerPassedBeforeResponse = false;
                int limit = responseIndex < 0 ? actions.size() : responseIndex;
                for (int i = trigger.actionIndex() + 1; i < limit; i++) {
                    Action later = actions.get(i);
                    if (!"MARK".equals(later.kind()) || later.unrealizedCents() == null
                            || rule.triggerDaysToExpiry() != null) continue;
                    Integer laterDte = daysToExpiry(later.at(), decision.nearestExpiry());
                    boolean stillTriggered = ProtocolEvaluator.evaluate(new ProtocolEvaluator.Inputs(
                                    decision.entryCents(), later.unrealizedCents(), laterDte)).stream()
                            .anyMatch(t -> t.rule().equals(rule.rule()));
                    if (!stillTriggered) { triggerPassedBeforeResponse = true; break; }
                }
                boolean allowed = response != null && responseHonors(rule.rule(), response.kind());
                String status = response == null
                        ? (finalReview ? "OVERRIDDEN" : "OPEN_AFTER_TRIGGER")
                        : allowed && !triggerPassedBeforeResponse ? "RESPECTED" : "OVERRIDDEN";
                boolean changedBeforeTrigger = false;
                for (int i = 0; i <= trigger.actionIndex() && i < actions.size(); i++) {
                    if (isPositionResponse(actions.get(i).kind())) { changedBeforeTrigger = true; break; }
                }
                Action triggerAction = trigger.actionIndex() < 0 ? null : actions.get(trigger.actionIndex());
                boolean wholePackageComparable = response != null && triggerAction != null
                        && Set.of("CLOSE", "SETTLE", "ROLL").contains(response.kind())
                        && response.tradeId() != null && response.tradeId().equals(triggerAction.tradeId())
                        && decision.qty() != null && response.tradeQty() != null
                        && decision.qty().equals(response.tradeQty()) && !changedBeforeTrigger;
                Long signedCost = "OVERRIDDEN".equals(status) && wholePackageComparable
                        && response.realizedCents() != null && trigger.pnlCents() != null
                        ? Math.subtractExact(response.realizedCents(), trigger.pnlCents()) : null;
                String note = switch (status) {
                    case "RESPECTED" -> "The next recorded position action answered the trigger; mark refreshes alone do not manufacture nonadherence.";
                    case "OPEN_AFTER_TRIGGER" -> "The rule fired, but the campaign is still open and has no recorded response yet.";
                    default -> signedCost == null
                            ? "The record shows an override, but the trigger and response do not prove the same whole-package quantity/basis; signed cost is withheld."
                            : "Signed override cost = recorded response result minus the P/L at the trigger mark; negative cost hurt, positive cost helped.";
                };
                out.add(new ProtocolAdherence(decision.planId(), decision.id(), lane, rule.rule(),
                        rule.triggerPnlCents(), rule.triggerDaysToExpiry(), rule.summary(), status,
                        iso(trigger.at()), trigger.pnlCents(), response == null ? null : response.kind(),
                        response == null ? null : iso(response.at()),
                        response == null ? null : response.realizedCents(), signedCost, note));
            }
        }
        return List.copyOf(out);
    }

    private static Integer daysToExpiry(OffsetDateTime at, LocalDate expiry) {
        if (at == null || expiry == null) return null;
        long days = ChronoUnit.DAYS.between(marketDate(at), expiry);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, days));
    }

    private static boolean isPositionResponse(String kind) {
        return Set.of("ROLL", "ADJUST", "PARTIAL_CLOSE", "CLOSE", "ASSIGNMENT", "EXERCISE",
                "EXPIRATION", "SETTLE", "VOID").contains(kind);
    }

    private static boolean responseHonors(String rule, String responseKind) {
        if (ProtocolEvaluator.ROLL.equals(rule) || ProtocolEvaluator.TIME_EXIT.equals(rule)) {
            return Set.of("ROLL", "CLOSE", "PARTIAL_CLOSE").contains(responseKind);
        }
        return Set.of("CLOSE", "PARTIAL_CLOSE", "ROLL").contains(responseKind);
    }

    private List<ExecutionLaneReview> executionLaneReviews(Connection c, CampaignRow row,
                                                           List<TxnEvent> txns,
                                                           List<PendingEvent> pending,
                                                           YieldView realYield) throws SQLException {
        boolean finalReview = !"ACTIVE".equals(row.status()) && row.closedAt() != null;
        int realOpenLots = 0;
        if (!txns.isEmpty()) {
            List<String> ids = txns.stream().map(TxnEvent::id).toList();
            realOpenLots = Math.toIntExact(Db.queryOn(c,
                    params("SELECT COUNT(*) n FROM portfolio_lot WHERE remaining_quantity>0 " +
                            "AND opening_transaction_id IN (%s)", ids.size()), r -> r.lng("n"),
                    ids.toArray()).getFirst());
        }
        boolean realAvailable = finalReview && !txns.isEmpty() && pending.isEmpty() && realOpenLots == 0;
        ExecutionLaneReview real = new ExecutionLaneReview("REAL", txns.size(),
                realAvailable ? txns.size() : Math.max(0, txns.size() - realOpenLots), realAvailable,
                realAvailable ? realYield.realizedPnlCents() : null,
                txns.isEmpty() ? "No recorded-at-broker campaign members."
                        : realAvailable ? "Final recorded-at-broker result from campaign members; Practice is not included."
                        : pending.isEmpty()
                            ? "Unavailable as a final REAL result — the campaign is active or tracked lots remain open."
                            : "Unavailable as a final REAL result — unresolved imports still withhold final accounting.");

        record PracticeFacts(long members, long terminal, long withResult, Long result) {}
        PracticeFacts pf = Db.queryOn(c, "SELECT COUNT(*) members," +
                        "COUNT(*) FILTER (WHERE t.status IN ('CLOSED','EXPIRED')) terminal," +
                        "COUNT(t.realized_pnl_cents) FILTER (WHERE t.status IN ('CLOSED','EXPIRED')) with_result," +
                        "SUM(t.realized_pnl_cents) FILTER (WHERE t.status IN ('CLOSED','EXPIRED')) result " +
                        "FROM campaign_practice_trade_member m JOIN trades t ON t.id=m.trade_id " +
                        "WHERE m.campaign_id=?",
                r -> new PracticeFacts(r.lng("members"), r.lng("terminal"), r.lng("with_result"),
                        r.lngOrNull("result")), row.id()).getFirst();
        boolean practiceAvailable = finalReview && pf.members() > 0 && pf.terminal() == pf.members()
                && pf.withResult() == pf.members();
        ExecutionLaneReview practice = new ExecutionLaneReview("PRACTICE", Math.toIntExact(pf.members()),
                Math.toIntExact(pf.terminal()), practiceAvailable,
                practiceAvailable ? pf.result() : null,
                pf.members() == 0 ? "No Practice campaign members."
                        : practiceAvailable ? "Final Practice result from its isolated learning ledger; REAL is not included."
                        : "Unavailable as a final Practice result — every Practice member must be closed with a recorded result.");
        return List.of(real, practice);
    }

    /**
     * Cross-campaign participation patterns.  A label needs at least two independently closed
     * campaign windows in the same execution lane; one supporting window is reported as
     * insufficient, never promoted to a "pattern".  REAL and PRACTICE evidence are not pooled.
     */
    private List<PatternFinding> patternFindings(Connection c, CampaignRow current) throws SQLException {
        List<CampaignRow> closed = Db.queryOn(c, "SELECT * FROM campaign WHERE user_id=? " +
                        "AND status IN ('CLOSED','ARCHIVED') AND closed_at IS NOT NULL " +
                        "ORDER BY closed_at,id",
                r -> new CampaignRow(r.str("id"), r.str("user_id"), r.str("symbol"), r.str("title"),
                        r.str("status"), r.str("account_objective_revision_id"), r.str("lesson_note"),
                        r.odt("closed_at"), r.str("created_at"), r.str("updated_at")), current.userId());
        List<PatternFinding> out = new ArrayList<>();
        for (String lane : List.of("REAL", "PRACTICE")) {
            for (String key : List.of("CSP_REGRET", "COVERED_CALL_MELT_UP")) {
                List<PatternEvidence> evidence = new ArrayList<>();
                for (CampaignRow candidate : closed) {
                    PatternEvidence item = patternEvidence(c, candidate, lane, key);
                    if (item != null) evidence.add(item);
                }
                String label = "CSP_REGRET".equals(key)
                        ? "Cash-secured-put regret in a rising market"
                        : "Covered-call melt-up mirror";
                String status = evidence.size() >= 2 ? "IDENTIFIED"
                        : evidence.size() == 1 ? "INSUFFICIENT" : "UNAVAILABLE";
                String note = switch (status) {
                    case "IDENTIFIED" -> "Repeated closed-campaign evidence: the same low-participation "
                            + "structure met an observed rise of at least "
                            + PARTICIPATION_PATTERN_MIN_RISE_PCT.stripTrailingZeros().toPlainString()
                            + "% in " + evidence.size() + " " + lane + " campaigns.";
                    case "INSUFFICIENT" -> "One closed " + lane + " campaign supports this shape, but one "
                            + "example is not a cross-campaign pattern.";
                    default -> "Unavailable — no repeated closed " + lane + " campaign has the required "
                            + "structure, final result, and one coherent observed price window. Nothing was inferred.";
                };
                out.add(new PatternFinding(key, label, lane, status, evidence.size(),
                        List.copyOf(evidence), note));
            }
        }
        return List.copyOf(out);
    }

    private PatternEvidence patternEvidence(Connection c, CampaignRow row, String lane, String key)
            throws SQLException {
        if (row.symbol() == null || row.closedAt() == null) return null;
        OffsetDateTime start;
        Long result;
        boolean signature;
        if ("REAL".equals(lane)) {
            List<TxnEvent> txns = loadTransactionMembers(c, row.id());
            if (txns.isEmpty() || !loadPendingMembers(c, row.id()).isEmpty()) return null;
            start = txns.stream().map(TxnEvent::occurredAt).min(Comparator.naturalOrder()).orElse(null);
            boolean shortPut = false, longPut = false, shortCall = false;
            boolean stockOpened = false, stockClosed = false;
            for (TxnEvent txn : txns) {
                for (Leg leg : txn.legs()) {
                    if ("OPTION".equals(leg.instrumentType()) && "PUT".equals(leg.optionType())
                            && "OPEN".equals(leg.positionEffect())) {
                        if ("SELL".equals(leg.action())) shortPut = true;
                        else longPut = true;
                    }
                    if ("OPTION".equals(leg.instrumentType()) && "CALL".equals(leg.optionType())
                            && "SELL".equals(leg.action()) && "OPEN".equals(leg.positionEffect())) {
                        shortCall = true;
                    }
                    if ("STOCK".equals(leg.instrumentType()) && "BUY".equals(leg.action())
                            && "OPEN".equals(leg.positionEffect())) stockOpened = true;
                    if ("STOCK".equals(leg.instrumentType()) && "SELL".equals(leg.action())
                            && "CLOSE".equals(leg.positionEffect())) stockClosed = true;
                }
            }
            int openLots = 0;
            List<String> ids = txns.stream().map(TxnEvent::id).toList();
            if (!ids.isEmpty()) {
                openLots = Math.toIntExact(Db.queryOn(c,
                        params("SELECT COUNT(*) n FROM portfolio_lot WHERE remaining_quantity>0 " +
                                "AND opening_transaction_id IN (%s)", ids.size()), r -> r.lng("n"),
                        ids.toArray()).getFirst());
            }
            if (openLots > 0) return null;
            signature = "CSP_REGRET".equals(key)
                    ? shortPut && !longPut && !stockOpened
                    : shortCall && stockOpened && stockClosed;
            result = txns.stream().filter(t -> !"INTEREST".equals(t.eventType()) || t.explicitInterest())
                    .mapToLong(TxnEvent::cashEffectCents).sum();
        } else {
            record Practice(String status, Long realized, OffsetDateTime createdAt,
                            OffsetDateTime closedAt, long sharesLocked, String legsJson) {}
            List<Practice> trades = Db.queryOn(c, "SELECT t.status,t.realized_pnl_cents,t.created_at," +
                            "t.closed_at,t.shares_locked,t.legs_json::text legs_json " +
                            "FROM campaign_practice_trade_member m JOIN trades t ON t.id=m.trade_id " +
                            "WHERE m.campaign_id=? ORDER BY t.created_at,t.id",
                    r -> new Practice(r.str("status"), r.lngOrNull("realized_pnl_cents"),
                            r.odt("created_at"), r.odt("closed_at"), r.lng("shares_locked"),
                            r.str("legs_json")), row.id());
            if (trades.isEmpty() || trades.stream().anyMatch(t ->
                    !("CLOSED".equals(t.status()) || "EXPIRED".equals(t.status()))
                            || t.realized() == null)) return null;
            start = trades.stream().map(Practice::createdAt).min(Comparator.naturalOrder()).orElse(null);
            boolean shortPut = false, longPut = false, shortCall = false, stock = false, covered = false;
            try {
                for (Practice trade : trades) {
                    for (io.liftandshift.strikebench.model.Leg leg : TradeRecord.legsFromJson(trade.legsJson())) {
                        if (leg.type() == io.liftandshift.strikebench.model.OptionType.PUT) {
                            if (leg.action() == io.liftandshift.strikebench.model.LegAction.SELL) shortPut = true;
                            else longPut = true;
                        }
                        if (leg.type() == io.liftandshift.strikebench.model.OptionType.CALL
                                && leg.action() == io.liftandshift.strikebench.model.LegAction.SELL) shortCall = true;
                        if (leg.isStock() && leg.action() == io.liftandshift.strikebench.model.LegAction.BUY) {
                            stock = true;
                        }
                    }
                    if (trade.sharesLocked() > 0) covered = true;
                }
            } catch (RuntimeException malformedHistoricalTrade) {
                return null;
            }
            signature = "CSP_REGRET".equals(key)
                    ? shortPut && !longPut && !stock
                    : shortCall && (stock || covered);
            result = trades.stream().mapToLong(t -> t.realized()).sum();
        }
        if (!signature || start == null) return null;
        LocalDate from = marketDate(start);
        LocalDate to = marketDate(row.closedAt());
        if (!to.isAfter(from)) return null;
        ObservedSeries series = observedSeries(c, row.symbol(), from, to);
        if (series.points().size() < 2) return null;
        ObservedPoint first = series.points().getFirst();
        ObservedPoint last = series.points().getLast();
        if (first.closeCents() <= 0) return null;
        BigDecimal move = BigDecimal.valueOf(last.closeCents() - first.closeCents())
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(first.closeCents()), 4, RoundingMode.HALF_EVEN);
        if (move.compareTo(PARTICIPATION_PATTERN_MIN_RISE_PCT) < 0) return null;
        return new PatternEvidence(row.id(), row.title(), row.symbol(), lane, move,
                first.date().toString(), last.date().toString(), series.source(), result);
    }

    private CounterfactualView counterfactuals(Connection c, CampaignRow row, List<TxnEvent> txns,
                                               List<PendingEvent> pending, OffsetDateTime asOf,
                                               boolean openOptionCarry) throws SQLException {
        List<CampaignMath.DatedCashFlow> flows = new ArrayList<>();
        for (TxnEvent t : txns) {
            if (!"INTEREST".equals(t.eventType()) || t.explicitInterest()) {
                flows.add(new CampaignMath.DatedCashFlow(marketDate(t.occurredAt()),
                        Math.negateExact(t.cashEffectCents())));
            }
        }
        for (PendingEvent p : pending) {
            flows.add(new CampaignMath.DatedCashFlow(marketDate(p.occurredAt()),
                    Math.negateExact(p.packageNetCents())));
        }
        flows.sort(Comparator.comparing(CampaignMath.DatedCashFlow::date));
        LocalDate endDate = marketDate(asOf);
        if (flows.isEmpty()) {
            return new CounterfactualView(
                    new BenchmarkOutcome(false, null, null, "No campaign cash flows yet."),
                    new BenchmarkOutcome(false, null, null, "No campaign cash flows yet."),
                    null, "No members carry cash flows yet.", endDate.toString());
        }

        // What the campaign's members are worth now, from the book's records: stock at the most
        // recent eligible observed close, open option legs at recorded basis (stated below).
        List<CampaignMath.DatedPrice> prices = row.symbol() == null ? List.of()
                : observedCloses(c, row.symbol(), flows.getFirst().date().minusDays(14), endDate);
        Long latestClose = null;
        for (CampaignMath.DatedPrice p : prices) {
            if (!p.date().isAfter(endDate)) latestClose = p.closePerShareCents();
        }
        Long campaignValue = null;
        String valuationNote;
        if (!txns.isEmpty()) {
            List<String> txnIds = txns.stream().map(TxnEvent::id).toList();
            var lots = Db.queryOn(c, params("SELECT instrument_type, side, symbol, remaining_quantity, "
                            + "economic_remaining_open_amount_cents FROM portfolio_lot "
                            + "WHERE remaining_quantity>0 AND opening_transaction_id IN (%s)", txnIds.size()),
                    r -> new Object[]{r.str("instrument_type"), r.str("side"), r.str("symbol"),
                            r.lng("remaining_quantity"), r.lng("economic_remaining_open_amount_cents")},
                    txnIds.toArray());
            long value = 0;
            boolean basisCarriedStock = false;
            for (Object[] lot : lots) {
                long sign = "LONG".equals(lot[1]) ? 1 : -1;
                if ("STOCK".equals(lot[0]) && latestClose != null
                        && ((String) lot[2]).equalsIgnoreCase(String.valueOf(row.symbol()))) {
                    value = Math.addExact(value, sign * Math.multiplyExact((Long) lot[3], latestClose));
                } else {
                    if ("STOCK".equals(lot[0])) basisCarriedStock = true;
                    value = Math.addExact(value, sign * (Long) lot[4]);
                }
            }
            campaignValue = value;
            valuationNote = (latestClose != null
                    ? "Held " + row.symbol() + " shares valued at the latest eligible observed close"
                    : "No eligible observed close was available, so held positions stay at recorded basis")
                    + (openOptionCarry ? "; open option legs stay at recorded basis, not marks." : ".")
                    + (basisCarriedStock && latestClose != null
                        ? " Shares outside " + row.symbol() + " stay at recorded basis." : "");
        } else {
            valuationNote = "Only pending package cash is attached; there is nothing to value yet.";
            campaignValue = 0L;
        }

        long cashValue = CampaignMath.cashBenchmark(flows, endDate, CASH_BENCHMARK_RATE_BPS);
        BenchmarkOutcome cash = new BenchmarkOutcome(true, cashValue,
                campaignValue == null ? null : Math.subtractExact(campaignValue, cashValue),
                "Same external flows held as cash at a stated modeled 4% annual rate (the disclosed "
                        + "teaching default), flow timing exact.");

        BenchmarkOutcome buyHold;
        if (row.symbol() == null) {
            buyHold = new BenchmarkOutcome(false, null, null,
                    "This campaign has no primary symbol, so a buy-and-hold twin is undefined.");
        } else if (prices.isEmpty()) {
            buyHold = new BenchmarkOutcome(false, null, null,
                    "No eligible observed " + row.symbol() + " closes cover the campaign window — "
                            + "the comparison stays honestly unavailable instead of using generated bars.");
        } else {
            try {
                long value = CampaignMath.buyAndHoldBenchmark(flows, prices, List.of(), endDate,
                        CampaignMath.DividendTreatment.CASH);
                buyHold = new BenchmarkOutcome(true, value,
                        campaignValue == null ? null : Math.subtractExact(campaignValue, value),
                        "Same external flows buying " + row.symbol() + " at each flow date's close. "
                                + "No sourced dividend calendar is connected, so benchmark dividends are "
                                + "omitted (stated treatment: retained as cash).");
            } catch (IllegalArgumentException e) {
                buyHold = new BenchmarkOutcome(false, null, null,
                        "A buy-and-hold twin is undefined for these flows: " + e.getMessage());
            }
        }
        return new CounterfactualView(cash, buyHold, campaignValue, valuationNote, endDate.toString());
    }

    /** §4 churn: exits later re-entered at a higher price are a real, countable cost. */
    private ChurnView churn(List<TxnEvent> txns) {
        record StockLeg(OffsetDateTime at, long quantity, long remaining, BigDecimal price, long feeCents,
                        long[] usedShares, List<Integer> pairIndexes) {}
        List<TxnEvent> ordered = new ArrayList<>(txns);
        ordered.sort(Comparator.comparing(TxnEvent::occurredAt));
        List<StockLeg> exits = new ArrayList<>();
        List<ChurnPair> pairs = new ArrayList<>();
        List<long[]> pairShares = new ArrayList<>();
        List<BigDecimal[]> pairPrices = new ArrayList<>();
        List<String[]> pairDates = new ArrayList<>();
        List<long[]> pairFees = new ArrayList<>();
        for (TxnEvent t : ordered) {
            for (Leg leg : t.legs()) {
                if (!"STOCK".equals(leg.instrumentType())) continue;
                if ("SELL".equals(leg.action()) && "CLOSE".equals(leg.positionEffect())) {
                    exits.add(new StockLeg(t.occurredAt(), leg.quantity(), leg.quantity(), leg.price(),
                            leg.allocatedFeeCents(), new long[]{0}, new ArrayList<>()));
                } else if ("BUY".equals(leg.action()) && "OPEN".equals(leg.positionEffect())) {
                    long need = leg.quantity();
                    long reFeeUsed = 0;
                    for (StockLeg exit : exits) {
                        if (need <= 0 || exit.remaining() - exit.usedShares()[0] <= 0) continue;
                        long take = Math.min(need, exit.remaining() - exit.usedShares()[0]);
                        exit.usedShares()[0] += take;
                        need -= take;
                        long exitFee = proportional(exit.feeCents(), take, exit.quantity());
                        long reFee = proportional(leg.allocatedFeeCents(), take, leg.quantity());
                        reFeeUsed += reFee;
                        pairShares.add(new long[]{take});
                        pairPrices.add(new BigDecimal[]{exit.price(), leg.price()});
                        pairDates.add(new String[]{iso(exit.at()), iso(t.occurredAt())});
                        pairFees.add(new long[]{Math.addExact(exitFee, reFee)});
                    }
                    // Any rounding residue from the re-entry leg's fee split lands on its last pair
                    // so the exact recorded fee total is preserved (largest-remainder discipline).
                    if (!pairFees.isEmpty() && need < leg.quantity()) {
                        long consumed = leg.quantity() - need;
                        if (consumed == leg.quantity()) {
                            long delta = leg.allocatedFeeCents() - reFeeUsed;
                            pairFees.getLast()[0] = Math.addExact(pairFees.getLast()[0], delta);
                        }
                    }
                }
            }
        }
        long total = 0;
        for (int i = 0; i < pairShares.size(); i++) {
            long shares = pairShares.get(i)[0];
            long exitCents = perShareCents(pairPrices.get(i)[0]);
            long reCents = perShareCents(pairPrices.get(i)[1]);
            long cost = CampaignMath.churnRoundTripCostCents(exitCents, reCents, shares,
                    Math.max(0, pairFees.get(i)[0]));
            total = Math.addExact(total, cost);
            pairs.add(new ChurnPair(pairDates.get(i)[0], pairDates.get(i)[1], shares, cost));
        }
        return new ChurnView(List.copyOf(pairs), total);
    }

    private List<AccountSubtotal> accountSubtotals(List<TxnEvent> txns) {
        record Bucket(String id, String name, String type, long[] cash, long[] shares, int[] count,
                      long[] volume) {}
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        for (TxnEvent t : txns) {
            Bucket b = buckets.computeIfAbsent(t.accountId(), id -> new Bucket(id, t.accountName(),
                    t.accountType(), new long[1], new long[1], new int[1], new long[1]));
            boolean counted = !"INTEREST".equals(t.eventType()) || t.explicitInterest();
            if (counted) b.cash()[0] = Math.addExact(b.cash()[0], t.cashEffectCents());
            b.count()[0]++;
            b.volume()[0] = Math.addExact(b.volume()[0], Math.abs(t.cashEffectCents()));
            for (Leg leg : t.legs()) {
                if ("STOCK".equals(leg.instrumentType())) {
                    b.shares()[0] += "SELL".equals(leg.action()) ? -leg.quantity() : leg.quantity();
                }
            }
        }
        List<Bucket> ordered = new ArrayList<>(buckets.values());
        long volumeTotal = ordered.stream().mapToLong(b -> b.volume()[0]).sum();
        List<Integer> shareBps = new ArrayList<>();
        if (volumeTotal > 0) {
            // Exact-total percentage split via §4's largest-remainder rule: the shares of activity
            // always sum to exactly 100.00%.
            List<BigDecimal> exact = ordered.stream().map(b -> BigDecimal.valueOf(b.volume()[0])
                    .multiply(BigDecimal.valueOf(10_000))
                    .divide(BigDecimal.valueOf(volumeTotal), 8, RoundingMode.HALF_EVEN)).toList();
            CampaignMath.largestRemainderCents(exact, 10_000)
                    .forEach(v -> shareBps.add(Math.toIntExact(v)));
        } else {
            ordered.forEach(b -> shareBps.add(0));
        }
        List<AccountSubtotal> out = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            Bucket b = ordered.get(i);
            out.add(new AccountSubtotal(b.id(), b.name(), b.type(), b.cash()[0], b.shares()[0],
                    b.count()[0], shareBps.get(i)));
        }
        return List.copyOf(out);
    }

    // ---- Loaders ----

    private List<TxnEvent> loadTransactionMembers(Connection c, String campaignId) throws SQLException {
        var rows = Db.queryOn(c, "SELECT t.id, t.portfolio_account_id, a.name, a.account_type, "
                        + "t.occurred_at occ, t.event_type, t.cash_effect_cents, t.fees_cents, "
                        + "m.explicit_interest, m.attached_at "
                        + "FROM campaign_transaction_member m "
                        + "JOIN portfolio_transaction t ON t.id=m.transaction_id "
                        + "JOIN portfolio_account a ON a.id=t.portfolio_account_id "
                        + "WHERE m.campaign_id=? ORDER BY t.occurred_at, t.record_seq",
                r -> new Object[]{r.str("id"), r.str("portfolio_account_id"), r.str("name"),
                        r.str("account_type"), r.odt("occ"), r.str("event_type"),
                        r.lng("cash_effect_cents"), r.lng("fees_cents"), r.bool("explicit_interest"),
                        r.str("attached_at")}, campaignId);
        List<TxnEvent> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            List<Leg> legs = Db.queryOn(c, "SELECT * FROM portfolio_transaction_leg WHERE transaction_id=? "
                            + "ORDER BY leg_no",
                    r -> new Leg(r.str("instrument_type"), r.str("action"), r.str("position_effect"),
                            r.str("symbol"), r.str("option_type"), r.bd("strike"), r.date("expiration"),
                            r.lng("quantity"), r.intv("multiplier"), r.bd("price"),
                            r.lng("gross_amount_cents"), r.lng("allocated_fee_cents")), (String) row[0]);
            out.add(new TxnEvent((String) row[0], (String) row[1], (String) row[2], (String) row[3],
                    (OffsetDateTime) row[4], (String) row[5], (Long) row[6], (Long) row[7],
                    (Boolean) row[8], (String) row[9], legs));
        }
        return out;
    }

    private List<PendingEvent> loadPendingMembers(Connection c, String campaignId) throws SQLException {
        return Db.queryOn(c, "SELECT p.id, p.occurred_at occ, p.package_net_cents, p.source_system, "
                        + "p.status, m.attached_at FROM campaign_pending_member m "
                        + "JOIN portfolio_import_pending p ON p.id=m.pending_id WHERE m.campaign_id=? "
                        + "AND p.status='PENDING' "
                        + "ORDER BY p.occurred_at",
                r -> new PendingEvent(r.str("id"), r.odt("occ"), r.lng("package_net_cents"),
                        r.str("source_system"), r.str("status"), r.str("attached_at")), campaignId);
    }

    private List<MemberView> loadSimpleMembers(Connection c, String campaignId) throws SQLException {
        List<MemberView> out = new ArrayList<>();
        out.addAll(Db.queryOn(c, "SELECT s.id, s.symbol, s.label, s.status, m.attached_at "
                        + "FROM campaign_structure_member m JOIN portfolio_structure s ON s.id=m.structure_id "
                        + "WHERE m.campaign_id=? ORDER BY m.attached_at",
                r -> new MemberView("STRUCTURE", r.str("id"),
                        r.str("label") != null && !r.str("label").isBlank() ? r.str("label")
                                : r.str("symbol") + " structure",
                        r.str("status").toLowerCase(Locale.ROOT), null, null, false, false,
                        r.str("attached_at")), campaignId));
        out.addAll(Db.queryOn(c, "SELECT p.id, p.symbol, p.custom_title, p.status, m.attached_at "
                        + "FROM campaign_plan_member m JOIN plans p ON p.id=m.plan_id "
                        + "WHERE m.campaign_id=? ORDER BY m.attached_at",
                r -> new MemberView("PLAN", r.str("id"),
                        r.str("custom_title") != null && !r.str("custom_title").isBlank()
                                ? r.str("custom_title") : r.str("symbol") + " Plan",
                        r.str("status").toLowerCase(Locale.ROOT), null, null, false, false,
                        r.str("attached_at")), campaignId));
        out.addAll(Db.queryOn(c, "SELECT t.id, t.symbol, t.strategy, t.status, m.attached_at "
                        + "FROM campaign_practice_trade_member m JOIN trades t ON t.id=m.trade_id "
                        + "WHERE m.campaign_id=? ORDER BY m.attached_at",
                r -> new MemberView("PRACTICE_TRADE", r.str("id"),
                        r.str("symbol") + " " + r.str("strategy").toLowerCase(Locale.ROOT) + " · practice",
                        r.str("status").toLowerCase(Locale.ROOT), null, null, false, false,
                        r.str("attached_at")), campaignId));
        return out;
    }

    private long strayClosingTransactions(Connection c, List<TxnEvent> txns, String campaignId)
            throws SQLException {
        if (txns.isEmpty()) return 0;
        List<String> txnIds = txns.stream().map(TxnEvent::id).toList();
        Object[] args = concat(txnIds.toArray(), campaignId);
        var rows = Db.queryOn(c, params("SELECT COUNT(DISTINCT m.closing_transaction_id) n "
                        + "FROM portfolio_lot_match m JOIN portfolio_lot l ON l.id=m.lot_id "
                        + "WHERE l.opening_transaction_id IN (%s) "
                        + "AND m.closing_transaction_id NOT IN "
                        + "(SELECT transaction_id FROM campaign_transaction_member WHERE campaign_id=?)",
                txnIds.size()), r -> r.lng("n"), args);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    /** One coherent eligible observed close series (same discipline as the account benchmark:
     *  never mixed sources, never generated bars). */
    private List<CampaignMath.DatedPrice> observedCloses(Connection c, String symbol, LocalDate from,
                                                         LocalDate to) throws SQLException {
        return observedSeries(c, symbol, from, to).points().stream()
                .map(point -> new CampaignMath.DatedPrice(point.date(), point.closeCents()))
                .toList();
    }

    /** The coherent source identity is returned with the points so review provenance cannot drift. */
    private ObservedSeries observedSeries(Connection c, String symbol, LocalDate from,
                                          LocalDate to) throws SQLException {
        record Bar(LocalDate d, BigDecimal close, String source, boolean adjusted, int quality) {}
        List<Bar> rows = Db.queryOn(c, "SELECT d, close, source, adjusted, quality_rank FROM underlying_bar "
                        + "WHERE symbol=? AND dataset_id='observed' AND observed=1 AND d BETWEEN ? AND ? "
                        + "AND LOWER(source) NOT IN ('fixture','synthetic','simulation','scenario','model') "
                        + "ORDER BY source, adjusted, d",
                r -> new Bar(r.date("d"), r.bd("close"), r.str("source"), r.bool("adjusted"),
                        r.intv("quality_rank")), symbol, from, to);
        Map<String, List<Bar>> groups = new LinkedHashMap<>();
        for (Bar bar : rows) {
            groups.computeIfAbsent(bar.source() + "|" + bar.adjusted(), k -> new ArrayList<>()).add(bar);
        }
        return groups.values().stream()
                .max(Comparator.<List<Bar>>comparingInt(List::size)
                        .thenComparing(series -> series.getLast().d())
                        .thenComparingInt(series -> series.stream().mapToInt(Bar::quality).max().orElse(0)))
                .map(series -> new ObservedSeries(series.getFirst().source(), series.getFirst().adjusted(),
                        series.stream().map(bar -> new ObservedPoint(bar.d(),
                                bar.close().movePointRight(2).setScale(0, RoundingMode.HALF_UP)
                                        .longValueExact())).toList()))
                .orElse(new ObservedSeries(null, false, List.of()));
    }

    // ---- Helpers ----

    private static CampaignRow requireCampaign(Connection c, String owner, String id) throws SQLException {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("campaign id is required");
        var rows = Db.queryOn(c, "SELECT * FROM campaign WHERE id=? AND user_id=?",
                r -> new CampaignRow(r.str("id"), r.str("user_id"), r.str("symbol"), r.str("title"),
                        r.str("status"), r.str("account_objective_revision_id"),
                        r.str("lesson_note"), r.odt("closed_at"),
                        r.str("created_at"), r.str("updated_at")), id, owner);
        if (rows.isEmpty()) throw new ResourceNotFoundException("No campaign " + id);
        return rows.getFirst();
    }

    private static void requireOwned(Connection c, String owner, String what, String id, String sql)
            throws SQLException {
        if (Db.queryOn(c, sql, r -> r.str("id"), id, owner).isEmpty()) {
            throw new ResourceNotFoundException("No " + what + " " + id);
        }
    }

    private static java.util.Optional<String> one(Connection c, String sql, Object... args)
            throws SQLException {
        var rows = Db.queryOn(c, sql, r -> r.str("v"), args);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.ofNullable(rows.getFirst());
    }

    private static List<String> inList(Connection c, String sqlTemplate, List<String> ids)
            throws SQLException {
        return inListDistinct(c, sqlTemplate, ids);
    }

    private static List<String> inListDistinct(Connection c, String sqlTemplate, List<String> ids)
            throws SQLException {
        if (ids.isEmpty()) return List.of();
        String sql = sqlTemplate.contains("%s") ? params(sqlTemplate, ids.size()) : sqlTemplate;
        return Db.queryOn(c, sql, r -> r.str("v"), ids.toArray());
    }

    private static String params(String template, int n) {
        return template.formatted("?" + ",?".repeat(Math.max(0, n - 1)));
    }

    private static Object[] concat(Object[] head, Object... tail) {
        Object[] out = new Object[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    private String legsLabel(Connection c, String txId, String eventType) throws SQLException {
        var legs = Db.queryOn(c, "SELECT * FROM portfolio_transaction_leg WHERE transaction_id=? ORDER BY leg_no",
                r -> {
                    String base = r.str("action") + " " + r.lng("quantity") + " " + r.str("symbol");
                    if ("OPTION".equals(r.str("instrument_type"))) {
                        return base + " " + r.date("expiration") + " "
                                + r.bd("strike").stripTrailingZeros().toPlainString() + " "
                                + r.str("option_type").toLowerCase(Locale.ROOT);
                    }
                    return base + " shares";
                }, txId);
        String pretty = eventType.replace('_', ' ').toLowerCase(Locale.ROOT);
        if (legs.isEmpty()) return pretty;
        if (legs.size() == 1) return pretty + " · " + legs.getFirst();
        return pretty + " · " + legs.getFirst() + " +" + (legs.size() - 1) + " leg"
                + (legs.size() == 2 ? "" : "s");
    }

    private static long strikeCents(BigDecimal strike, long quantity, int multiplier) {
        if (strike == null) return 0;
        return strike.movePointRight(2).multiply(BigDecimal.valueOf(quantity))
                .multiply(BigDecimal.valueOf(multiplier)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long perShareCents(BigDecimal price) {
        return price.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long proportional(long totalCents, long part, long whole) {
        if (whole <= 0 || totalCents == 0) return 0;
        return BigDecimal.valueOf(totalCents).multiply(BigDecimal.valueOf(part))
                .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_EVEN).longValueExact();
    }

    private static LocalDate marketDate(OffsetDateTime at) {
        return at.atZoneSameInstant(MARKET_ZONE).toLocalDate();
    }

    private static String iso(OffsetDateTime at) {
        return at == null ? null : at.toInstant().toString();
    }

    private static String text(String value, String what, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(what + " is required");
        String trimmed = value.trim();
        if (trimmed.length() > max) throw new IllegalArgumentException(what + " is limited to " + max + " characters");
        return trimmed;
    }

    private static String enumValue(String value, List<String> allowed, String what) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(what + " is required");
        String upper = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw new IllegalArgumentException(what + " must be one of " + String.join(", ", allowed));
        }
        return upper;
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String upper = symbol.trim().toUpperCase(Locale.ROOT);
        if (upper.length() > 20) throw new IllegalArgumentException("symbol is limited to 20 characters");
        return upper;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Integer integerOrNull(Db.Row row, String column) {
        Long value = row.lngOrNull(column);
        return value == null ? null : Math.toIntExact(value);
    }

    private static String optionalText(String value, String what, int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(what + " is limited to " + max + " characters");
        }
        return trimmed;
    }
}
