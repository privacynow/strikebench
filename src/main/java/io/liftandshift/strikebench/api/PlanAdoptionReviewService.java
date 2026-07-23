package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.paper.AccountObjectiveService;
import io.liftandshift.strikebench.paper.CampaignService;
import io.liftandshift.strikebench.paper.PortfolioAccountingService;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.position.HeldPositionEconomicsService;
import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.position.PositionLifecycleReceipt;
import io.liftandshift.strikebench.position.AuthorityFacts;
import io.liftandshift.strikebench.strategy.StrategyCatalog;
import io.liftandshift.strikebench.util.OwnerScope;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Typed read composer for TRADER/OWN Journey C. The ADOPTION receipt remains the immutable
 * baseline; today's exact package is sent through the canonical tracked-package analyzer; and
 * campaign history is read from CampaignService. The two questions stay side by side and no
 * result from either lens is copied into, averaged with, or allowed to overwrite the other.
 */
final class PlanAdoptionReviewService {
    static final String CAMPAIGN_QUESTION =
            "What has the explicitly linked whole campaign earned or lost, beside the frozen adopted baseline?";

    @FunctionalInterface
    interface Analyzer {
        ApiResponses.TrackedPackageAnalysis analyze(String ownerId, String accountId,
                                                     TradeService.OpenRequest request);
    }

    @FunctionalInterface
    interface Surfacer {
        ApiResponses.TrackedPackageAnalysis surface(String ownerId,
                                                     ApiResponses.TrackedPackageAnalysis analysis);
    }

    record BaselineLeg(int legNo, String instrumentType, String action, String symbol,
                       String optionType, BigDecimal strike, LocalDate expiration, long quantity,
                       int multiplier, BigDecimal bid, BigDecimal ask, BigDecimal mid,
                       BigDecimal openingFill, String priceAuthority) {}

    record AdoptionAnchor(String receiptId, String structureId, String structureLabel,
                          String accountId, String accountName, String symbol, String positionState,
                          String authority, String marksAsOf, String evidenceLevel,
                          String frozenObjectiveRevisionId, List<BaselineLeg> legs) {}

    record FreshEyesLens(boolean available, String question, String basis,
                         ApiResponses.TrackedPackageAnalysis analysis,
                         String unavailableReason) {}

    record CampaignLens(boolean available, String question, String basis,
                        List<CampaignService.CampaignView> campaigns,
                        String unavailableReason) {}

    record AdoptionReview(AdoptionAnchor anchor,
                          AccountObjectiveService.Revision currentObjective,
                          FreshEyesLens freshEyes, CampaignLens campaignAnchored) {}

    private record AnchorRow(String receiptId, String structureId, String structureLabel,
                             String accountId, String accountName, String symbol, String structureStatus,
                             String positionState, String authority, String marksAsOf,
                             String evidenceLevel, String objectiveRevisionId) {}

    private final Db db;
    private final Analyzer analyzer;
    private final CampaignService campaigns;
    private final AccountObjectiveService objectives;
    private final PortfolioAccountingService books;
    private final HeldPositionEconomicsService lifecycle;
    private final Surfacer surfacer;

    PlanAdoptionReviewService(Db db, Analyzer analyzer, CampaignService campaigns,
                              AccountObjectiveService objectives, PortfolioAccountingService books,
                              HeldPositionEconomicsService lifecycle) {
        this(db, analyzer, (owner, analysis) -> analysis, campaigns, objectives, books, lifecycle);
    }

    PlanAdoptionReviewService(Db db, Analyzer analyzer, Surfacer surfacer,
                              CampaignService campaigns, AccountObjectiveService objectives,
                              PortfolioAccountingService books, HeldPositionEconomicsService lifecycle) {
        this.db = db;
        this.analyzer = analyzer;
        this.surfacer = surfacer;
        this.campaigns = campaigns;
        this.objectives = objectives;
        this.books = books;
        this.lifecycle = lifecycle;
    }

    List<AdoptionReview> reviews(String ownerId, String planId) {
        String owner = OwnerScope.id(ownerId);
        List<AnchorRow> anchors = db.with(c -> Db.queryOn(c,
                "SELECT pr.id receipt_id,ps.id structure_id,ps.label structure_label,"
                        + "ps.portfolio_account_id,pa.name account_name,ps.symbol,ps.status structure_status,"
                        + "psr.position_state,pr.authority,pr.marks_as_of::text marks_as_of,"
                        + "pr.evidence_level,pr.account_objective_revision_id "
                        + "FROM position_receipt pr "
                        + "JOIN plan_portfolio_action ppa ON ppa.receipt_id=pr.id "
                        + "JOIN portfolio_structure_revision psr ON psr.id=pr.structure_revision_id "
                        + "JOIN portfolio_structure ps ON ps.id=psr.structure_id "
                        + "JOIN portfolio_account pa ON pa.id=ps.portfolio_account_id "
                        + "WHERE pr.plan_id=? AND pr.user_id=? AND pr.kind='ADOPTION' "
                        + "ORDER BY pr.created_at,pr.id",
                r -> new AnchorRow(r.str("receipt_id"), r.str("structure_id"),
                        r.str("structure_label"), r.str("portfolio_account_id"),
                        r.str("account_name"), r.str("symbol"), r.str("structure_status"),
                        r.str("position_state"), r.str("authority"), r.str("marks_as_of"),
                        r.str("evidence_level"), r.str("account_objective_revision_id")),
                planId, owner));
        List<AdoptionReview> out = new ArrayList<>(anchors.size());
        for (AnchorRow row : anchors) out.add(review(owner, planId, row));
        return List.copyOf(out);
    }

    private AdoptionReview review(String owner, String planId, AnchorRow row) {
        List<BaselineLeg> baseline = baselineLegs(row.receiptId());
        AdoptionAnchor anchor = new AdoptionAnchor(row.receiptId(), row.structureId(),
                row.structureLabel(), row.accountId(), row.accountName(), row.symbol(),
                row.positionState(), row.authority(), row.marksAsOf(), row.evidenceLevel(),
                row.objectiveRevisionId(), baseline);
        AccountObjectiveService.Revision currentObjective = objectives.latest(owner, row.accountId());
        List<CampaignService.CampaignView> matching = matchingCampaigns(owner, planId, row.structureId());

        FreshEyesLens freshEyes;
        if (!"OPEN".equals(row.structureStatus()) && !"PARTIALLY_CLOSED".equals(row.positionState())) {
            freshEyes = unavailableFreshEyes("This tracked structure is no longer open in the Book.");
        } else {
            try {
                List<Leg> current = currentLegs(row.structureId());
                if (current.isEmpty()) {
                    freshEyes = unavailableFreshEyes(
                            "No open lots remain in this structure, so there is no current package to reprice.");
                } else {
                    var identity = StrategyCatalog.identify(row.symbol(), 1, current);
                    String strategy = identity.family() == null ? "CUSTOM" : identity.family();
                    var request = new TradeService.OpenRequest(row.accountId(), row.symbol(), strategy, 1,
                            current, null, null, null, null, false, null, null,
                            "ADOPTION_REVIEW", "PROPOSED");
                    ApiResponses.TrackedPackageAnalysis analysis = analyzer.analyze(owner, row.accountId(), request);
                    if (analysis != null && analysis.lifecycle() != null) {
                        analysis = surfacer.surface(owner,
                                withHistory(owner, row, baseline, matching, analysis));
                    }
                    freshEyes = new FreshEyesLens(true, io.liftandshift.strikebench.position.HeldPositionEconomicsService.FRESH_EYES_QUESTION,
                            "Current observed executable marks; sunk campaign cash excluded. "
                                    + "The account's latest objective revision supplies coherence only.",
                            analysis, null);
                }
            } catch (RuntimeException e) {
                freshEyes = unavailableFreshEyes(e.getMessage() == null
                        ? "Current tracked-package analysis is unavailable." : e.getMessage());
            }
        }

        CampaignLens campaignLens = matching.isEmpty()
                ? new CampaignLens(false, CAMPAIGN_QUESTION,
                "CampaignService accounting uses the full history of explicitly confirmed members. Adoption receipt "
                        + row.receiptId() + " anchors position identity only, not the campaign's arithmetic start.",
                List.of(), "No campaign is linked to this Plan or tracked structure yet. The adoption baseline "
                        + "remains frozen; campaign-adjusted economic basis and counterfactuals stay unavailable "
                        + "until membership is explicitly confirmed in the Book.")
                : new CampaignLens(true, CAMPAIGN_QUESTION,
                "CampaignService accounting uses the full history of explicitly confirmed members. Adoption receipt "
                        + row.receiptId() + " anchors position identity only, not the campaign's arithmetic start. "
                        + "Tracked tax basis remains separate.",
                matching, null);
        return new AdoptionReview(anchor, currentObjective, freshEyes, campaignLens);
    }

    private ApiResponses.TrackedPackageAnalysis withHistory(String owner, AnchorRow row,
                                                             List<BaselineLeg> baseline,
                                                             List<CampaignService.CampaignView> matching,
                                                             ApiResponses.TrackedPackageAnalysis analysis) {
        List<HeldPositionEconomicsService.OpeningLeg> opening = baseline.stream()
                .filter(leg -> leg.openingFill() != null)
                .map(leg -> new HeldPositionEconomicsService.OpeningLeg(
                        LegAction.valueOf(leg.action()), leg.instrumentType(), leg.quantity(),
                        leg.multiplier(), leg.openingFill(),
                        "positionReceipt:" + row.receiptId() + "#leg-" + leg.legNo()))
                .toList();
        var stockBasis = books.allocatedStockBasis(owner, row.accountId(),
                currentLotAllocations(row.structureId()));
        AuthorityFacts.MoneyFact taxBasis = stockBasis.available()
                ? new AuthorityFacts.MoneyFact(stockBasis.trackedTaxBasisPerShareCents(),
                    PositionDomain.FactAuthority.SYSTEM_CALCULATED, stockBasis.basis())
                : AuthorityFacts.MoneyFact.unavailable(stockBasis.basis());

        AuthorityFacts.MoneyFact campaignBasis;
        Long campaignResult = null;
        List<String> refs = new ArrayList<>();
        refs.add("positionReceipt:" + row.receiptId());
        if (matching.size() == 1) {
            CampaignService.CampaignView campaign = matching.getFirst();
            refs.add("campaign:" + campaign.id());
            campaignResult = campaign.yield() == null ? null : campaign.yield().realizedPnlCents();
            campaignBasis = campaign.economicBasis() != null && campaign.economicBasis().available()
                    ? new AuthorityFacts.MoneyFact(campaign.economicBasis().perShareCents(),
                        PositionDomain.FactAuthority.SYSTEM_CALCULATED,
                        "CampaignService campaign-adjusted economic basis; never tracked tax basis.")
                    : AuthorityFacts.MoneyFact.unavailable(
                        "The one linked campaign has no defined per-share campaign-adjusted basis.");
        } else if (matching.isEmpty()) {
            campaignBasis = AuthorityFacts.MoneyFact.unavailable(
                    "No explicitly linked campaign supplies a campaign-adjusted basis.");
        } else {
            matching.forEach(campaign -> refs.add("campaign:" + campaign.id()));
            campaignBasis = AuthorityFacts.MoneyFact.unavailable(
                    "Multiple campaigns are linked; StrikeBench will not merge their interpretation-layer bases.");
        }
        var context = new HeldPositionEconomicsService.HistoryContext(opening, null,
                campaignResult, null, taxBasis, campaignBasis,
                "Frozen adoption fills supply opening history. Opening commissions are unavailable in the "
                        + "adoption receipt, so net-after-all-cost history stays unavailable. CampaignService "
                        + "and tracked accounting remain separate owners.", refs);
        var enriched = lifecycle.withHistory(analysis.lifecycle(), context);
        return new ApiResponses.TrackedPackageAnalysis(analysis.preview(), analysis.evaluation(),
                analysis.identity(), analysis.accountId(), analysis.accountName(),
                analysis.availableCashCents(), analysis.marketLane(), analysis.note(), enriched,
                analysis.bookActions(), analysis.capacity(), null);
    }

    private List<PortfolioAccountingService.LotAllocation> currentLotAllocations(String structureId) {
        return db.with(c -> Db.queryOn(c,
                "SELECT psm.lot_id,psm.allocated_quantity FROM portfolio_structure ps "
                        + "JOIN portfolio_structure_member psm ON psm.revision_id=ps.current_revision_id "
                        + "WHERE ps.id=? ORDER BY psm.leg_no",
                r -> new PortfolioAccountingService.LotAllocation(
                        r.str("lot_id"), r.lng("allocated_quantity")), structureId));
    }

    private FreshEyesLens unavailableFreshEyes(String reason) {
        return new FreshEyesLens(false, io.liftandshift.strikebench.position.HeldPositionEconomicsService.FRESH_EYES_QUESTION,
                "Current observed executable marks; sunk campaign cash excluded.", null, reason);
    }

    private List<BaselineLeg> baselineLegs(String receiptId) {
        return db.with(c -> Db.queryOn(c,
                "SELECT leg_no,instrument_type,action,symbol,option_type,strike,expiration,quantity,"
                        + "multiplier,bid,ask,mid,fill_price,price_authority "
                        + "FROM position_receipt_leg WHERE receipt_id=? AND position_phase='AFTER' ORDER BY leg_no",
                r -> new BaselineLeg(r.intv("leg_no"), r.str("instrument_type"), r.str("action"),
                        r.str("symbol"), r.str("option_type"), r.bd("strike"), r.date("expiration"),
                        r.lng("quantity"), r.intv("multiplier"), r.bd("bid"), r.bd("ask"),
                        r.bd("mid"), r.bd("fill_price"), r.str("price_authority")), receiptId));
    }

    private List<Leg> currentLegs(String structureId) {
        return db.with(c -> Db.queryOn(c,
                "SELECT pl.side,pl.instrument_type,pl.option_type,pl.strike,pl.expiration,pl.multiplier,"
                        + "psm.allocated_quantity "
                        + "FROM portfolio_structure ps "
                        + "JOIN portfolio_structure_member psm ON psm.revision_id=ps.current_revision_id "
                        + "JOIN portfolio_lot pl ON pl.id=psm.lot_id "
                        + "WHERE ps.id=? ORDER BY psm.leg_no",
                r -> new Leg("LONG".equals(r.str("side")) ? LegAction.BUY : LegAction.SELL,
                        "STOCK".equals(r.str("instrument_type")) ? null
                                : OptionType.valueOf(r.str("option_type")),
                        r.bd("strike"), r.date("expiration"),
                        Math.toIntExact(r.lng("allocated_quantity")), BigDecimal.ZERO,
                        r.intv("multiplier")), structureId));
    }

    private List<CampaignService.CampaignView> matchingCampaigns(String owner, String planId,
                                                                  String structureId) {
        List<String> ids = db.with(c -> Db.queryOn(c,
                "SELECT DISTINCT c.id,c.updated_at FROM campaign c "
                        + "LEFT JOIN campaign_structure_member csm ON csm.campaign_id=c.id "
                        + "LEFT JOIN campaign_plan_member cpm ON cpm.campaign_id=c.id "
                        + "WHERE c.user_id=? AND (csm.structure_id=? OR cpm.plan_id=?) "
                        + "ORDER BY c.updated_at DESC,c.id",
                r -> r.str("id"), owner, structureId, planId));
        List<CampaignService.CampaignView> out = new ArrayList<>(ids.size());
        for (String id : new LinkedHashSet<>(ids)) out.add(campaigns.view(owner, id));
        return List.copyOf(out);
    }
}
