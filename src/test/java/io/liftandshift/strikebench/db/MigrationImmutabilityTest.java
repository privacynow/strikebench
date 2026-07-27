package io.liftandshift.strikebench.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A migration that has been applied to a real database can never be edited again. Flyway records
 * a checksum when it applies a script; changing the file afterwards does not change the database,
 * it only makes the next startup fail validation — and `flyway repair` is not a fix, because it
 * rewrites the checksum while leaving the schema alone, moving the failure from startup to
 * runtime SQL.
 *
 * <p>This was learned twice in one night. Commit 978351d renamed a column inside
 * {@code V1__baseline.sql} and stopped every box that had already run V1; hours later a second
 * pass tightened a CHECK inside {@code V10}, after V10 had been applied. Both had to be restored
 * byte-for-byte with the change re-issued as a forward migration (V11, V12).</p>
 *
 * <p>So the rule is a test. Every checksum below is a fact about a file that some database has
 * already executed. A failure here means an existing migration was edited: restore it and add a
 * new {@code V(n+1)} instead. Adding a migration means adding a line — a deliberate act, which is
 * the point.</p>
 */
class MigrationImmutabilityTest {

    /** Flyway's own algorithm: CRC32 over each line's bytes, line terminators excluded. */
    private static int checksum(Path script) throws IOException {
        CRC32 crc = new CRC32();
        for (String line : Files.readAllLines(script, StandardCharsets.UTF_8)) {
            crc.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return (int) crc.getValue();
    }

    private static Map<String, Integer> appliedChecksums() {
        Map<String, Integer> pinned = new LinkedHashMap<>();
        pinned.put("V1__baseline.sql", 1891445287);
        pinned.put("V2__canonical_market_event_evidence.sql", -845294101);
        pinned.put("V3__portfolio_liquidity_evidence.sql", 886739179);
        pinned.put("V4__account_capacity_declarations.sql", -1352197333);
        pinned.put("V5__position_lifecycle_decisions.sql", -586081996);
        pinned.put("V6__candidate_leg_quote_receipts.sql", -932643898);
        pinned.put("V7__data_sync_earliest_available.sql", 2118466393);
        pinned.put("V8__candidate_option_net.sql", 224796606);
        pinned.put("V9__backtest_exit_policy_units.sql", 607523799);
        pinned.put("V10__candidate_package_price_receipt.sql", -977288737);
        pinned.put("V11__plan_decision_horizon_sessions.sql", 706271111);
        pinned.put("V12__candidate_after_fee_not_null_check.sql", -1329899912);
        pinned.put("V13__scout_row_identity_and_adoption.sql", -909760999);
        pinned.put("V14__nullable_captured_comparison_fees.sql", -373313129);
        pinned.put("V15__trade_order_limit_authority.sql", -256711359);
        pinned.put("V16__position_lifecycle_evidence_state.sql", 322262752);
        pinned.put("V17__candidate_leg_quote_iv_delta.sql", 1676717654);
        return pinned;
    }

    @Test
    void everyMigrationKeepsTheChecksumSomeDatabaseAlreadyApplied() throws IOException {
        Path dir = Path.of("src/main/resources/db/migrations");
        Map<String, Integer> pinned = appliedChecksums();

        for (Map.Entry<String, Integer> entry : pinned.entrySet()) {
            Path script = dir.resolve(entry.getKey());
            assertThat(script)
                    .as("a pinned migration was deleted or renamed: %s", entry.getKey())
                    .exists();
            assertThat(checksum(script))
                    .as("%s was edited after it was applied. Restore it byte-for-byte and put the "
                            + "change in a new forward migration; do NOT run `flyway repair`, which "
                            + "blesses the checksum and leaves the schema untouched.", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    void everyMigrationOnDiskIsPinnedHere() throws IOException {
        Path dir = Path.of("src/main/resources/db/migrations");
        try (var files = Files.list(dir)) {
            List<String> onDisk = files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".sql")).sorted().toList();
            assertThat(onDisk)
                    .as("a new migration must be pinned in appliedChecksums() the moment it lands, "
                            + "so a later edit to it fails here instead of on someone's startup")
                    .allSatisfy(name -> assertThat(appliedChecksums()).containsKey(name));
        }
    }
}
