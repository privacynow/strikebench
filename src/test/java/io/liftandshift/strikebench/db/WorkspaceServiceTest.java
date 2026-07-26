package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.EventBus;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE one workspace context (audit §6): partial writes preserve untouched declarations, a world
 * change is committed atomically, and a stored version this build does not know is refused with a
 * reason instead of half-read.
 */
class WorkspaceServiceTest {

    private Db db;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-09T14:00:00Z"), ZoneOffset.UTC);

    private static final WorkspaceContext.ActiveMarket OBSERVED =
            new WorkspaceContext.ActiveMarket("observed", "OBSERVED", "acct_practice");
    private static final WorkspaceContext.ActiveMarket DEMO =
            new WorkspaceContext.ActiveMarket("demo", "DEMO", "acct_demo");

    @AfterEach void close() { if (db != null) db.close(); }

    private static WorkspaceContext.Patch patch(String json) {
        return Json.read(json, WorkspaceContext.Patch.class);
    }

    private static WorkspaceContext context(String json) {
        return Json.read(json, WorkspaceContext.class);
    }

    private WorkspaceService service() {
        db = TestDb.fresh();
        return new WorkspaceService(db, clock);
    }

    /** The full desk declaration a user builds up before they touch anything else. */
    private static WorkspaceContext.Patch declaredDesk() {
        return patch("""
                {"version":1,"goal":"INCOME","view":"NEUTRAL","horizonDays":45,"riskPosture":"BALANCED",
                 "assignmentPreference":"AVOID","scopeType":"SECTOR","sectorKey":"SEMICONDUCTORS",
                 "focusedSubject":"PACKAGE","focusedSymbol":"NVDA","focusedIdeaId":"plan_abc123",
                 "focusedPositionId":"tr_held_001","focusedEvaluationId":"eval_xyz789",
                 "targetCents":18500,"shareQuantity":200,
                 "routeState":"#/idea/NVDA",
                 "returnFocus":{"subject":"BOOK","routeState":"#/home","scopeType":"SECTOR",
                                "sectorKey":"SEMICONDUCTORS","targetCents":18000,
                                "shareQuantity":300}}""");
    }

    @Test
    void aPartialUpdatePreservesEveryUntouchedField() {
        WorkspaceService ws = service();
        var declared = ws.patch("user-a", declaredDesk(), OBSERVED);
        assertThat(declared.rev()).isEqualTo(1);

        // This is the Import Trade write: it names ONE field. It used to wipe goal, view, horizon,
        // risk and the selected world (audit §6).
        var after = ws.patch("user-a", patch("{\"version\":1,\"routeState\":\"#/import\"}"), OBSERVED);
        WorkspaceContext c = after.context();

        assertThat(c.routeState()).isEqualTo("#/import");
        assertThat(c.goal()).isEqualTo("INCOME");
        assertThat(c.view()).isEqualTo("NEUTRAL");
        assertThat(c.horizonDays()).isEqualTo(45);
        assertThat(c.riskPosture()).isEqualTo("BALANCED");
        assertThat(c.assignmentPreference()).isEqualTo("AVOID");
        assertThat(c.scopeType()).isEqualTo("SECTOR");
        assertThat(c.sectorKey()).isEqualTo("SEMICONDUCTORS");
        assertThat(c.focusedSubject()).isEqualTo("PACKAGE");
        assertThat(c.focusedSymbol()).isEqualTo("NVDA");
        assertThat(c.focusedIdeaId()).isEqualTo("plan_abc123");
        assertThat(c.focusedEvaluationId()).isEqualTo("eval_xyz789");
        assertThat(c.targetCents()).isEqualTo(18500L);
        assertThat(c.shareQuantity()).isEqualTo(200L);
        assertThat(c.returnFocus().sectorKey()).isEqualTo("SEMICONDUCTORS");
        assertThat(c.returnFocus().targetCents()).isEqualTo(18000L);
        assertThat(c.returnFocus().shareQuantity()).isEqualTo(300L);
        assertThat(c.world()).isEqualTo("observed");
        assertThat(c.datasetId()).isEqualTo(DatasetService.OBSERVED);
        assertThat(c.marketLane()).isEqualTo("OBSERVED");
        assertThat(c.accountId()).isEqualTo("acct_practice");
        assertThat(c.generation()).isEqualTo(declared.context().generation());
        assertThat(after.transition()).isNull();
        // Only the named field moved, and exactly one revision was written.
        assertThat(after.rev()).isEqualTo(2);
    }

    @Test
    void onlyAnExplicitClearUndeclaresAField() {
        WorkspaceService ws = service();
        ws.patch("user-a", declaredDesk(), OBSERVED);
        var after = ws.patch("user-a",
                patch("{\"version\":1,\"clear\":[\"goal\",\"focusedSymbol\"]}"), OBSERVED);

        assertThat(after.context().goal()).isNull();
        assertThat(after.context().focusedSymbol()).isNull();
        assertThat(after.context().view()).isEqualTo("NEUTRAL");
        assertThat(after.context().horizonDays()).isEqualTo(45);
        // A typo in a clear list would silently keep a declaration the user meant to drop.
        assertThatThrownBy(() -> ws.patch("user-a",
                patch("{\"version\":1,\"clear\":[\"goals\"]}"), OBSERVED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot clear unknown workspace field 'goals'");
    }

    @Test
    void undeclaredValuesStayUndeclaredAndNothingIsDefaultedIn() {
        WorkspaceService ws = service();
        // A first declaration that names ONLY a symbol. §3.5: no goal, view, horizon or risk
        // may appear because the desk asked for a symbol.
        var state = ws.patch("user-a",
                patch("{\"version\":1,\"scopeType\":\"SYMBOL\",\"focusedSymbol\":\"aapl\"}"), OBSERVED);
        WorkspaceContext c = state.context();
        assertThat(c.focusedSymbol()).isEqualTo("AAPL");
        assertThat(c.goal()).isNull();
        assertThat(c.view()).isNull();
        assertThat(c.horizonDays()).isNull();
        assertThat(c.riskPosture()).isNull();
        assertThat(c.assignmentPreference()).isNull();
        // And the stored JSON does not carry them at all — absent is absent on the wire too.
        String stored = ws.get("user-a").orElseThrow().stateJson();
        assertThat(stored).doesNotContain("goal").doesNotContain("riskPosture")
                .doesNotContain("horizonDays").doesNotContain("view");
    }

    @Test
    void readingAnUndeclaredWorkspaceCreatesNothing() {
        WorkspaceService ws = service();
        var state = ws.context("user-a", OBSERVED);
        assertThat(state.rev()).isZero();
        assertThat(state.context()).isNull();
        assertThat(state.unreadable()).isNull();
        assertThat(ws.get("user-a")).isEmpty();
    }

    @Test
    void aWorldChangeNeverLeavesAHalfAppliedContextObservable() {
        WorkspaceService ws = service();
        var before = ws.patch("user-a", declaredDesk(), OBSERVED);
        assertThat(ws.get("user-a").orElseThrow().stateJson()).contains("NVDA").contains("observed");

        // The user switched to the built-in demo market. The first touch of the workspace commits
        // the whole transition: clear what belonged to observed, stamp demo, advance the generation.
        var after = ws.context("user-a", DEMO);
        WorkspaceContext c = after.context();

        assertThat(c.world()).isEqualTo("demo");
        assertThat(c.marketLane()).isEqualTo("DEMO");
        assertThat(c.accountId()).isEqualTo("acct_demo");
        assertThat(c.generation()).isEqualTo(before.context().generation() + 1);
        assertThat(c.focusedSymbol()).isNull();
        assertThat(c.sectorKey()).isNull();
        assertThat(c.scopeType()).isNull();
        assertThat(c.focusedSubject()).isNull();
        assertThat(c.focusedIdeaId()).isNull();
        assertThat(c.focusedEvaluationId()).isNull();
        assertThat(c.targetCents()).isNull();
        assertThat(c.shareQuantity()).isNull();
        assertThat(c.routeState()).isNull();
        assertThat(c.returnFocus()).isNull();
        // Declarations are not market facts. They survive.
        assertThat(c.goal()).isEqualTo("INCOME");
        assertThat(c.view()).isEqualTo("NEUTRAL");
        assertThat(c.horizonDays()).isEqualTo(45);
        assertThat(c.riskPosture()).isEqualTo("BALANCED");
        assertThat(c.assignmentPreference()).isEqualTo("AVOID");

        // The receipt says what was lost instead of losing it silently.
        assertThat(after.transition().fromWorld()).isEqualTo("observed");
        assertThat(after.transition().toWorld()).isEqualTo("demo");
        assertThat(after.transition().cleared())
                .contains("focusedSymbol", "sectorKey", "scopeType", "focusedIdeaId", "returnFocus");
        assertThat(after.transition().reason()).contains("observed").contains("demo");

        // Nothing half-applied is observable: what was published IS what was committed, in ONE
        // revision, and the old market's focus is not in the row beside the new world.
        String stored = ws.get("user-a").orElseThrow().stateJson();
        assertThat(stored).doesNotContain("NVDA").doesNotContain("SEMICONDUCTORS")
                .contains("\"world\": \"demo\"").contains("\"datasetId\": \"observed\"");
        assertThat(Json.canonical(WorkspaceContext.read(stored).context()))
                .isEqualTo(Json.canonical(c));
        assertThat(after.rev()).isEqualTo(before.rev() + 1);

        // Re-reading the same world is idempotent: no second transition, no second write.
        var again = ws.context("user-a", DEMO);
        assertThat(again.transition()).isNull();
        assertThat(again.rev()).isEqualTo(after.rev());
        assertThat(again.context().generation()).isEqualTo(c.generation());
    }

    @Test
    void datasetAndAccountArePartOfTheMarketIdentityAndClearOwnedFocus() {
        WorkspaceService ws = service();
        var original = ws.patch("user-a", declaredDesk(), OBSERVED);
        db.exec("INSERT INTO settings(k,v,updated_at) VALUES (?,?,now())",
                SettingsStore.activeDatasetKey("user-a"), "ds_scenario_a");

        var datasetMoved = ws.context("user-a",
                new WorkspaceContext.ActiveMarket(
                        "observed", "ds_scenario_a", "SCENARIO", "acct_practice"));
        assertThat(datasetMoved.context().generation())
                .isEqualTo(original.context().generation() + 1);
        assertThat(datasetMoved.context().datasetId()).isEqualTo("ds_scenario_a");
        assertThat(datasetMoved.context().focusedSymbol()).isNull();
        assertThat(datasetMoved.context().routeState()).isNull();
        assertThat(datasetMoved.context().goal()).isEqualTo("INCOME");

        var redeclared = ws.patch("user-a", patch("""
                {"version":1,"scopeType":"SYMBOL","focusedSymbol":"AMD",
                 "focusedSubject":"POSITION","focusedPositionId":"tr_amd",
                 "routeState":"#/position/tr_amd"}"""),
                new WorkspaceContext.ActiveMarket(
                        "observed", "ds_scenario_a", "SCENARIO", "acct_practice"));
        var accountMoved = ws.context("user-a",
                new WorkspaceContext.ActiveMarket(
                        "observed", "ds_scenario_a", "SCENARIO", "acct_other"));
        assertThat(accountMoved.context().generation())
                .isEqualTo(redeclared.context().generation() + 1);
        assertThat(accountMoved.context().accountId()).isEqualTo("acct_other");
        assertThat(accountMoved.context().focusedPositionId()).isNull();
        assertThat(accountMoved.context().focusedSymbol()).isNull();
        assertThat(accountMoved.context().goal()).isEqualTo("INCOME");
    }

    @Test
    void staleDatasetIdentityCannotPublishIntoTheCurrentWorkspace() {
        WorkspaceService ws = service();
        ws.patch("user-a", declaredDesk(), OBSERVED);
        db.exec("INSERT INTO settings(k,v,updated_at) VALUES (?,?,now())",
                SettingsStore.activeDatasetKey("user-a"), "ds_new");

        assertThatThrownBy(() -> ws.patch("user-a",
                patch("{\"version\":1,\"focusedSymbol\":\"AMD\"}"), OBSERVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dataset moved to 'ds_new'")
                .hasMessageContaining("targeted 'observed'");
        assertThatThrownBy(() -> ws.context("user-a",
                new WorkspaceContext.ActiveMarket(
                        "observed", "ds_new", "OBSERVED", "acct_practice")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match world 'observed' and dataset 'ds_new'");
        assertThat(ws.get("user-a").orElseThrow().stateJson()).contains("NVDA");
    }

    @Test
    void staleIdentityIsRejectedEvenWhenBothMarketsHaveNoWorkspaceRowAtRevisionZero() {
        WorkspaceService ws = service();
        WorkspaceContext.ActiveMarket scenario = new WorkspaceContext.ActiveMarket(
                "observed", "ds_new", "SCENARIO", "acct_practice");
        db.exec("INSERT INTO settings(k,v,updated_at) VALUES (?,?,now())",
                SettingsStore.activeDatasetKey("user-a"), "ds_new");

        assertThatThrownBy(() -> ws.patch("user-a", patch("""
                {"version":1,"world":"observed","expectedRev":0,
                 "expectedDatasetId":"observed","expectedMarketLane":"OBSERVED",
                 "expectedAccountId":"acct_practice","expectedGeneration":0,
                 "focusedSymbol":"AMD"}
                """), scenario))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("made against dataset 'observed'")
                .hasMessageContaining("active dataset is 'ds_new'");

        assertThat(ws.get("user-a")).isEmpty();
    }

    @Test
    void returnFocusAcquisitionValuesAreValidatedAndOldReceiptsStayReadable() {
        WorkspaceService ws = service();
        var stored = ws.patch("user-a", patch("""
                {"version":1,"returnFocus":{"subject":"MARKET","symbol":"AMD",
                 "scopeType":"SYMBOL","targetCents":45000,"shareQuantity":500,
                 "routeState":"#/home"}}"""), OBSERVED);
        assertThat(stored.context().returnFocus().targetCents()).isEqualTo(45000L);
        assertThat(stored.context().returnFocus().shareQuantity()).isEqualTo(500L);

        assertThatThrownBy(() -> ws.patch("user-a", patch("""
                {"version":1,"returnFocus":{"targetCents":0}}"""), OBSERVED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("returnFocus.targetCents must be positive");

        WorkspaceContext.Stored old = WorkspaceContext.read("""
                {"version":1,"generation":1,"world":"observed","marketLane":"OBSERVED",
                 "accountId":"acct_practice",
                 "returnFocus":{"subject":"BOOK","routeState":"#/home"}}""");
        assertThat(old.readable()).isTrue();
        assertThat(old.context().returnFocus().targetCents()).isNull();
        assertThat(old.context().returnFocus().shareQuantity()).isNull();
    }

    /**
     * Drift guard: the two lists that DOCUMENT what a market owns are checked against what a world
     * change actually does. A field added to the record and to MARKET_OWNED but forgotten in the
     * clearing path would otherwise survive into the next market in silence.
     */
    @Test
    void everyMarketOwnedFieldIsClearedAndEveryDeclarationSurvives() throws Exception {
        WorkspaceService ws = service();
        WorkspaceContext before = ws.patch("user-a", declaredDesk(), OBSERVED).context();
        WorkspaceContext after = ws.context("user-a", DEMO).context();

        for (String field : WorkspaceContext.MARKET_OWNED) {
            assertThat(read(before, field)).as(field + " must be declared for this guard to mean anything")
                    .isNotNull();
            assertThat(read(after, field)).as(field + " belongs to the market that was left").isNull();
        }
        for (String field : WorkspaceContext.DECLARATIONS) {
            assertThat(read(after, field)).as(field + " is user intent and must survive").isNotNull();
        }
        assertThat(WorkspaceContext.CLIENT_FIELDS)
                .containsAll(WorkspaceContext.MARKET_OWNED)
                .containsAll(WorkspaceContext.DECLARATIONS);
    }

    private static Object read(WorkspaceContext context, String field) throws Exception {
        return WorkspaceContext.class.getMethod(field).invoke(context);
    }

    @Test
    void concurrentReadsCommitTheWorldChangeExactlyOnce() throws Exception {
        WorkspaceService ws = service();
        var before = ws.patch("user-a", declaredDesk(), OBSERVED);
        CountDownLatch start = new CountDownLatch(1);
        List<WorkspaceService.ContextState> seen = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    seen.add(ws.context("user-a", DEMO));
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(seen).hasSize(2);
        // The row lock serializes them: one commits the transition, the other sees it already done.
        assertThat(ws.get("user-a").orElseThrow().rev()).isEqualTo(before.rev() + 1);
        assertThat(seen).allSatisfy(state -> {
            assertThat(state.context().world()).isEqualTo("demo");
            assertThat(state.context().generation()).isEqualTo(before.context().generation() + 1);
            assertThat(state.context().focusedSymbol()).isNull();
        });
    }

    @Test
    void anUnknownStoredVersionIsRefusedWithAStatedReason() {
        WorkspaceService ws = service();
        ws.put("user-a", "{\"version\":99,\"goal\":\"INCOME\",\"focusedSymbol\":\"NVDA\"}");

        var state = ws.context("user-a", OBSERVED);
        assertThat(state.context()).isNull();
        assertThat(state.unreadable().storedVersion()).isEqualTo(99);
        assertThat(state.unreadable().supportedVersion()).isEqualTo(WorkspaceContext.CURRENT_VERSION);
        assertThat(state.unreadable().reason())
                .contains("version 99")
                .contains("newer build")
                .contains("it was not read");
        // Nothing was taken from it and nothing overwrote it.
        assertThat(ws.get("user-a").orElseThrow().stateJson()).contains("99").contains("NVDA");
        assertThat(state.rev()).isEqualTo(1);

        // A partial write cannot merge onto a base it could not read.
        assertThatThrownBy(() -> ws.patch("user-a", patch("{\"version\":1,\"goal\":\"HEDGE\"}"), OBSERVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 99")
                .hasMessageContaining("PUT /api/workspace");

        // A full replace heals it, because it declares everything itself.
        var healed = ws.replace("user-a", context("{\"version\":1,\"goal\":\"HEDGE\"}"), OBSERVED);
        assertThat(healed.context().goal()).isEqualTo("HEDGE");
        assertThat(healed.context().version()).isEqualTo(WorkspaceContext.CURRENT_VERSION);
        assertThat(healed.unreadable()).isNull();
    }

    @Test
    void anUnversionedStoredBlobIsRefusedRatherThanPartiallyRead() {
        WorkspaceService ws = service();
        // The pre-context free-form blob the desk used to store.
        ws.put("user-a", "{\"route\":\"#/research/AAPL\",\"symbol\":\"AAPL\",\"forms\":{\"goal\":\"INCOME\"}}");
        var state = ws.context("user-a", OBSERVED);
        assertThat(state.context()).isNull();
        assertThat(state.unreadable().storedVersion()).isNull();
        assertThat(state.unreadable().reason())
                .contains("predates the versioned workspace context")
                .contains("nothing was taken from it");
    }

    @Test
    void aFullReplaceUndeclaresOmittedFieldsButKeepsServerFacts() {
        WorkspaceService ws = service();
        ws.patch("user-a", declaredDesk(), OBSERVED);
        var replaced = ws.replace("user-a",
                context("{\"version\":1,\"goal\":\"ACCUMULATE\",\"world\":\"observed\"}"), OBSERVED);

        assertThat(replaced.context().goal()).isEqualTo("ACCUMULATE");
        assertThat(replaced.context().view()).isNull();
        assertThat(replaced.context().focusedSymbol()).isNull();
        assertThat(replaced.context().sectorKey()).isNull();
        // Server facts are never taken from the body.
        assertThat(replaced.context().world()).isEqualTo("observed");
        assertThat(replaced.context().marketLane()).isEqualTo("OBSERVED");
        assertThat(replaced.context().accountId()).isEqualTo("acct_practice");
    }

    @Test
    void aWriteFromAnOlderContextGenerationIsRefused() {
        WorkspaceService ws = service();
        var first = ws.patch("user-a", declaredDesk(), OBSERVED);

        // A late response from the observed desk tries to publish after the user moved to demo.
        assertThatThrownBy(() -> ws.patch("user-a",
                patch("{\"version\":1,\"world\":\"observed\",\"focusedSymbol\":\"AMD\"}"), DEMO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active market is 'demo'");

        // The same guard on revisions: a write that expected an older revision does not land.
        ws.patch("user-a", patch("{\"version\":1,\"focusedSymbol\":\"AMD\"}"), OBSERVED);
        assertThatThrownBy(() -> ws.patch("user-a",
                patch("{\"version\":1,\"expectedRev\":" + first.rev() + ",\"focusedSymbol\":\"INTC\"}"),
                OBSERVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the workspace moved on");
        assertThat(ws.context("user-a", OBSERVED).context().focusedSymbol()).isEqualTo("AMD");
    }

    @Test
    void aWriteDeclaringAnUnsupportedVersionIsRefused() {
        WorkspaceService ws = service();
        assertThatThrownBy(() -> ws.patch("user-a", patch("{\"version\":2,\"goal\":\"INCOME\"}"), OBSERVED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("this build reads workspace context version 1")
                .hasMessageContaining("declared version 2");
        assertThatThrownBy(() -> ws.patch("user-a", patch("{\"goal\":\"INCOME\"}"), OBSERVED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must declare version 1");
    }

    @Test
    void halfDeclaredIdentitiesAndUnknownTokensAreRefused() {
        WorkspaceService ws = service();
        assertThatThrownBy(() -> ws.patch("user-a", patch("{\"version\":1,\"scopeType\":\"SECTOR\"}"), OBSERVED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SECTOR scope must name its sectorKey");
        assertThatThrownBy(() -> ws.patch("user-a",
                patch("{\"version\":1,\"focusedSubject\":\"PACKAGE\"}"), OBSERVED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PACKAGE subject must name the proposed package");
        assertThatThrownBy(() -> ws.patch("user-a", patch("{\"version\":1,\"goal\":\"PROFIT\"}"), OBSERVED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("goal must be one of");
        assertThatThrownBy(() -> ws.patch("user-a",
                patch("{\"version\":1,\"sectorKey\":\"semis\"}"), OBSERVED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sectorKey must be a canonical sector key");
        assertThatThrownBy(() -> ws.patch("user-a",
                patch("{\"version\":1,\"horizonDays\":0}"), OBSERVED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("horizonDays must be a positive number of days");
        assertThat(ws.get("user-a")).isEmpty(); // no refusal wrote anything
    }

    @Test
    void aScoutRowKeepsItsExactEvaluationIdentityIntoTheProposedPackage() {
        WorkspaceService ws = service();
        // Home sends the row's immutable evaluation, not just its symbol (audit §8.2). The Plan
        // does not exist yet, so the evaluation IS the identity at this moment.
        var opened = ws.patch("user-a", patch("""
                {"version":1,"focusedSubject":"PACKAGE","focusedSymbol":"NVDA",
                 "focusedEvaluationId":"eval_scout_001","goal":"INCOME"}"""), OBSERVED);
        assertThat(opened.context().focusedEvaluationId()).isEqualTo("eval_scout_001");
        assertThat(opened.context().focusedIdeaId()).isNull();

        // Adoption creates the Plan; the evaluation identity survives beside it.
        var adopted = ws.patch("user-a",
                patch("{\"version\":1,\"focusedIdeaId\":\"plan_from_scout\"}"), OBSERVED);
        assertThat(adopted.context().focusedIdeaId()).isEqualTo("plan_from_scout");
        assertThat(adopted.context().focusedEvaluationId()).isEqualTo("eval_scout_001");
        assertThat(adopted.context().focusedSymbol()).isEqualTo("NVDA");
        assertThat(adopted.context().goal()).isEqualTo("INCOME");
    }

    @Test
    void usersAreIsolatedAndAnonymousSharesTheLocalKey() {
        WorkspaceService ws = service();
        ws.patch("user-a", patch("{\"version\":1,\"focusedSymbol\":\"AAPL\"}"), OBSERVED);
        ws.patch("user-b", patch("{\"version\":1,\"focusedSymbol\":\"TSLA\"}"), OBSERVED);
        ws.patch(null, patch("{\"version\":1,\"focusedSymbol\":\"SPY\"}"), OBSERVED);
        assertThat(ws.context("user-a", OBSERVED).context().focusedSymbol()).isEqualTo("AAPL");
        assertThat(ws.context("user-b", OBSERVED).context().focusedSymbol()).isEqualTo("TSLA");
        // null and blank both mean the anonymous local workspace.
        assertThat(ws.context("", OBSERVED).context().focusedSymbol()).isEqualTo("SPY");
    }

    @Test
    void oversizedBlobsAreRejected() {
        WorkspaceService ws = service();
        String big = "{\"x\":\"" + "y".repeat(WorkspaceService.MAX_STATE_BYTES) + "\"}";
        assertThatThrownBy(() -> ws.put(null, big)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void writesAnnounceTheRevisionAndTheMarketTogether() throws InterruptedException {
        WorkspaceService ws = service();
        EventBus bus = new EventBus();
        ws.setEvents(bus);
        List<EventBus.Event> seen = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);
        bus.subscribe(event -> { seen.add(event); delivered.countDown(); });
        var state = ws.patch("user-a", declaredDesk(), OBSERVED);
        assertThat(delivered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).anyMatch(e -> e.type().equals("workspace.updated")
                && Long.valueOf(state.rev()).equals(e.data().get("rev"))
                && "observed".equals(e.data().get("world"))
                && "OBSERVED".equals(e.data().get("marketLane")));
    }

    @Test
    void eventBusReplaysSinceAndSurvivesBadSubscribers() throws InterruptedException {
        EventBus bus = new EventBus();
        bus.subscribe(e -> { throw new RuntimeException("bad subscriber"); });
        List<EventBus.Event> seen = new CopyOnWriteArrayList<>();
        CountDownLatch firstTwoDelivered = new CountDownLatch(2);
        bus.subscribe(event -> { seen.add(event); firstTwoDelivered.countDown(); });
        var e1 = bus.publish("job.progress", Map.of("id", "j1", "done", 1));
        var e2 = bus.publish("job.complete", Map.of("id", "j1", "status", "DONE"));
        assertThat(firstTwoDelivered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).containsExactly(e1, e2); // the throwing subscriber didn't break delivery
        // The replay ring is synchronous truth regardless of the async pump.
        assertThat(bus.since(e1.seq())).containsExactly(e2);
        assertThat(bus.since(0)).hasSize(2);
        assertThat(bus.currentSeq()).isEqualTo(e2.seq());
        // The replay ring is capped — old events fall off instead of growing forever.
        for (int i = 0; i < 400; i++) bus.publish("tick", Map.of("i", i));
        assertThat(bus.since(0)).hasSize(256);
        // Per-subscriber queues are also deliberately capped at 256 hints and may drop the
        // oldest items during this 400-event burst. Do not require lossless async delivery here.
        // A fresh subscriber gives the unsubscribe check a deterministic delivery barrier.
        List<EventBus.Event> markerSeen = new CopyOnWriteArrayList<>();
        CountDownLatch markerDelivered = new CountDownLatch(1);
        bus.subscribe(event -> { markerSeen.add(event); markerDelivered.countDown(); });
        List<EventBus.Event> removedSeen = new CopyOnWriteArrayList<>();
        Runnable unsub = bus.subscribe(removedSeen::add);
        unsub.run();
        var after = bus.publish("after", Map.of());
        assertThat(markerDelivered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(markerSeen).anyMatch(event -> event.seq() == after.seq());
        assertThat(removedSeen).isEmpty();
    }
}
