package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountObjectiveServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-22T16:00:00Z"), ZoneOffset.UTC);
    private Db db;
    private PortfolioAccountingService books;
    private AccountObjectiveService objectives;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        books = new PortfolioAccountingService(db, CLOCK);
        objectives = new AccountObjectiveService(db, CLOCK);
    }

    @AfterEach void close() { db.close(); }

    @Test
    void packageQuantitiesAndHardOrAdvisoryCeilingsAreImmutableVersionedDeclarations() {
        var account = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Capacity book", "TAXABLE", "Broker", "FIFO", null, null, null, null, 150_000_000L));
        String amd = "a".repeat(64);
        String qqq = "b".repeat(64);
        var policy = new AccountObjectiveService.AccountCapacityPolicy(
                List.of(
                        new AccountObjectiveService.ScopedCeiling("QQQ", 25_000_000L,
                                AccountObjectiveService.Enforcement.ADVISORY),
                        new AccountObjectiveService.ScopedCeiling("AMD", 41_000_000L,
                                AccountObjectiveService.Enforcement.HARD)),
                List.of(new AccountObjectiveService.ScopedCeiling("SEMICONDUCTORS", 65_000_000L,
                        AccountObjectiveService.Enforcement.ADVISORY)),
                List.of(new AccountObjectiveService.ScopedCeiling("2026-08-07", 75_000_000L,
                        AccountObjectiveService.Enforcement.HARD)),
                new AccountObjectiveService.CapacityCeiling(105_000_000L,
                        AccountObjectiveService.Enforcement.HARD));
        var first = objectives.declare("local", account.id(), "INCOME", "NON_DIRECTIONAL", null,
                "ACCEPT", List.of(
                        new AccountObjectiveService.PackageCapacity(qqq, "qqq", 300L, 20_100_000L,
                                65_500L, null, null),
                        new AccountObjectiveService.PackageCapacity(amd, "amd", 900L, 41_000_000L,
                                44_200L, 400L, 20_000_000L)), policy);

        assertThat(first.revisionNo()).isEqualTo(1);
        assertThat(first.declarationFingerprint()).hasSize(64);
        assertThat(first.packageCapacities()).extracting(AccountObjectiveService.PackageCapacity::symbol)
                .containsExactly("AMD", "QQQ");
        assertThat(first.capacityPolicy().symbolCeilings())
                .extracting(AccountObjectiveService.ScopedCeiling::key).containsExactly("AMD", "QQQ");
        assertThat(AccountObjectiveService.capacityContext(first, amd).packageMatchStatus())
                .isEqualTo("EXACT_FINGERPRINT");
        assertThat(AccountObjectiveService.capacityContext(first, "c".repeat(64)).packageCapacity()).isNull();

        var revised = objectives.declare("local", account.id(), "INCOME", "NON_DIRECTIONAL", null,
                "ACCEPT", List.of(new AccountObjectiveService.PackageCapacity(amd, "AMD", 500L,
                        25_000_000L, 44_200L, 200L, 10_000_000L)), policy);
        assertThat(revised.revisionNo()).isEqualTo(2);
        assertThat(revised.declarationFingerprint()).isNotEqualTo(first.declarationFingerprint());
        assertThat(objectives.history("local", account.id())).satisfiesExactly(
                original -> assertThat(original.packageCapacities().getFirst().acceptedAssignmentShares())
                        .isEqualTo(900L),
                current -> assertThat(current.packageCapacities().getFirst().acceptedAssignmentShares())
                        .isEqualTo(500L));
        assertThat(db.query("SELECT jsonb_typeof(package_capacities) kind FROM account_objective_revision "
                        + "WHERE id=?", r -> r.str("kind"), first.id())).containsExactly("array");
    }

    @Test
    void invalidOrDuplicateCapacityFactsAreRejectedBeforeAnyRevisionIsWritten() {
        var account = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Guarded capacity", "TAXABLE", null, "FIFO", null, null, null, null, null));
        String fingerprint = "d".repeat(64);
        var capacity = new AccountObjectiveService.PackageCapacity(fingerprint, "NVDA", 100L,
                18_000_000L, 179_530L, null, null);

        assertThatThrownBy(() -> objectives.declare("local", account.id(), "INCOME", null, null,
                "ACCEPT", List.of(capacity, capacity), AccountObjectiveService.AccountCapacityPolicy.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate package capacity");
        assertThatThrownBy(() -> new AccountObjectiveService.AccountCapacityPolicy(
                List.of(), List.of(), List.of(new AccountObjectiveService.ScopedCeiling(
                "next friday", 100L, AccountObjectiveService.Enforcement.ADVISORY)), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ISO date");
        assertThat(db.query("SELECT COUNT(*) n FROM account_objective_revision",
                r -> r.lng("n"))).containsExactly(0L);
    }

    @Test
    void oneCapacityComparatorServesCurrentAndHypotheticalBookUsage() {
        var policy = new AccountObjectiveService.AccountCapacityPolicy(
                List.of(new AccountObjectiveService.ScopedCeiling("AMD", 10_000L,
                        AccountObjectiveService.Enforcement.HARD)),
                List.of(new AccountObjectiveService.ScopedCeiling("TECHNOLOGY", 20_000L,
                        AccountObjectiveService.Enforcement.ADVISORY)),
                List.of(new AccountObjectiveService.ScopedCeiling("2026-08-07", 15_000L,
                        AccountObjectiveService.Enforcement.HARD)),
                new AccountObjectiveService.CapacityCeiling(12_000L,
                        AccountObjectiveService.Enforcement.HARD));
        var usage = new AccountObjectiveService.CapacityUsage(
                Map.of("amd", 11_000L), Map.of("technology", 19_000L),
                Map.of("2026-08-07", 16_000L), null, "canonical hypothetical Book snapshot");

        var checks = AccountObjectiveService.assessCapacity(policy, usage);
        assertThat(checks).extracting(AccountObjectiveService.CapacityCheck::scope,
                        AccountObjectiveService.CapacityCheck::key,
                        AccountObjectiveService.CapacityCheck::available,
                        AccountObjectiveService.CapacityCheck::breached)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SYMBOL", "AMD", true, true),
                        org.assertj.core.groups.Tuple.tuple("THEME", "TECHNOLOGY", true, false),
                        org.assertj.core.groups.Tuple.tuple("EXPIRY", "2026-08-07", true, true),
                        org.assertj.core.groups.Tuple.tuple("ENCUMBRANCE", "ACCOUNT", false, false));
    }
}
