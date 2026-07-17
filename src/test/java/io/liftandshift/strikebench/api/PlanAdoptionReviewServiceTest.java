package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.paper.AccountObjectiveService;
import io.liftandshift.strikebench.paper.CampaignService;
import io.liftandshift.strikebench.paper.PortfolioAccountingService;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.plan.Plan;
import io.liftandshift.strikebench.plan.PlanAdoptionService;
import io.liftandshift.strikebench.plan.PlanService;
import io.liftandshift.strikebench.position.PositionArtifactStore;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PlanAdoptionReviewServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC);
    private Db db;
    private PortfolioAccountingService books;
    private AccountObjectiveService objectives;
    private CampaignService campaigns;
    private PlanAdoptionService adoptions;
    private String accountId;
    private String lotId;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        books = new PortfolioAccountingService(db, CLOCK);
        objectives = new AccountObjectiveService(db, CLOCK);
        campaigns = new CampaignService(db, CLOCK);
        PlanService plans = new PlanService(db, CLOCK);
        adoptions = new PlanAdoptionService(db, CLOCK, plans, new PositionArtifactStore(db));
        accountId = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Existing-position IRA", "ROTH_IRA", "Example", "FIFO",
                null, null, null, null, null)).id();
        var transaction = books.record("local", accountId,
                new PortfolioAccountingService.TransactionInput(
                        "2026-07-01T15:00:00Z", "TRADE", null, 0L, null,
                        "BROKER", "losing-position", null,
                        List.of(new PortfolioAccountingService.LegInput(
                                "STOCK", "BUY", "OPEN", "MU", null, null, null,
                                100L, 1, new BigDecimal("120.00"), null)), "EXECUTED"));
        lotId = db.query("SELECT id FROM portfolio_lot WHERE opening_transaction_id=?",
                r -> r.str("id"), transaction.id()).getFirst();
    }

    @AfterEach void tearDown() { if (db != null) db.close(); }

    @Test void twoLensesShareTheExactAdoptionAnchorButKeepCurrentAndCampaignTruthSeparate() {
        var objectiveAtAdoption = objectives.declare("local", accountId,
                "ACCUMULATE", "BULLISH", 2_000_000L, "PREFER_BELOW_BASIS");
        var adopted = adoptions.adopt("local", Plan.MarketKind.OBSERVED, null,
                new PlanAdoptionService.Request("adopt-loser", accountId, "MU",
                        "Existing MU loser", List.of(new PlanAdoptionService.Allocation(lotId, 100L))));
        var campaign = campaigns.create("local", new CampaignService.CreateInput(
                "MU accumulation campaign", "MU", objectiveAtAdoption.id(), List.of(lotId)));
        campaigns.attach("local", campaign.id(), new CampaignService.MemberInput(
                "STRUCTURE", adopted.artifacts().structureId(), null));

        var objectiveNow = objectives.declare("local", accountId,
                "INCOME", "NON_DIRECTIONAL", null, "ACCEPT");
        AtomicReference<TradeService.OpenRequest> analyzed = new AtomicReference<>();
        var service = new PlanAdoptionReviewService(db, (owner, account, request) -> {
            analyzed.set(request);
            return null; // the composer owns lineage; the production analyzer owns this payload
        }, campaigns, objectives);

        var review = service.reviews("local", adopted.plan().id()).getFirst();

        assertThat(review.anchor().receiptId()).isEqualTo(adopted.artifacts().receiptId());
        assertThat(review.anchor().structureId()).isEqualTo(adopted.artifacts().structureId());
        assertThat(review.anchor().frozenObjectiveRevisionId()).isEqualTo(objectiveAtAdoption.id());
        assertThat(review.currentObjective().id()).isEqualTo(objectiveNow.id());
        assertThat(review.currentObjective().revisionNo()).isEqualTo(2);
        assertThat(review.anchor().legs()).singleElement().satisfies(leg -> {
            assertThat(leg.quantity()).isEqualTo(100L);
            assertThat(leg.openingFill()).isEqualByComparingTo("120.00");
            assertThat(leg.priceAuthority()).isEqualTo("USER_REPORTED");
        });

        assertThat(review.freshEyes().available()).isTrue();
        assertThat(review.freshEyes().question()).contains("open").contains("today");
        assertThat(review.freshEyes().basis()).contains("sunk campaign cash excluded");
        assertThat(analyzed.get().accountId()).isEqualTo(accountId);
        assertThat(analyzed.get().source()).isEqualTo("ADOPTION_REVIEW");
        assertThat(analyzed.get().fillNature()).isEqualTo("PROPOSED");
        assertThat(analyzed.get().legs()).singleElement().satisfies(leg -> {
            assertThat(leg.ratio()).isEqualTo(100);
            assertThat(leg.multiplier()).isEqualTo(1);
            assertThat(leg.entryPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        });

        assertThat(review.campaignAnchored().available()).isTrue();
        assertThat(review.campaignAnchored().question()).contains("whole campaign");
        assertThat(review.campaignAnchored().question()).doesNotContain("from the adopted baseline");
        assertThat(review.campaignAnchored().basis())
                .contains("full history of explicitly confirmed members")
                .contains(adopted.artifacts().receiptId())
                .contains("anchors position identity only, not the campaign's arithmetic start")
                .contains("Tracked tax basis remains separate");
        assertThat(review.campaignAnchored().campaigns()).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo(campaign.id());
            assertThat(view.accountObjectiveRevisionId()).isEqualTo(objectiveAtAdoption.id());
            assertThat(view.economicBasis().available()).isTrue();
        });
    }

    @Test void missingCampaignNeverManufacturesAnchoredEconomicsOrHidesTheFrozenBaseline() {
        var adopted = adoptions.adopt("local", Plan.MarketKind.OBSERVED, null,
                new PlanAdoptionService.Request("adopt-without-campaign", accountId, "MU",
                        "Unlinked MU position", List.of(new PlanAdoptionService.Allocation(lotId, 100L))));
        var service = new PlanAdoptionReviewService(db, (owner, account, request) -> null,
                campaigns, objectives);

        var review = service.reviews("local", adopted.plan().id()).getFirst();

        assertThat(review.anchor().receiptId()).isEqualTo(adopted.artifacts().receiptId());
        assertThat(review.campaignAnchored().available()).isFalse();
        assertThat(review.campaignAnchored().campaigns()).isEmpty();
        assertThat(review.campaignAnchored().unavailableReason())
                .contains("No campaign is linked")
                .contains("remains frozen")
                .contains("explicitly confirmed");
    }
}
