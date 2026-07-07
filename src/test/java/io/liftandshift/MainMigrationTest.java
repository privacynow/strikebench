package io.liftandshift;

import io.liftandshift.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MainMigrationTest {

    @TempDir Path tmp;

    @Test
    void movesLegacyDbWithSiblingsExactlyOnce() throws Exception {
        Path legacy = tmp.resolve("options-lab.db");
        Path legacyWal = tmp.resolve("options-lab.db-wal");
        Path target = tmp.resolve("strikebench.db");
        Files.writeString(legacy, "legacy-bytes");
        Files.writeString(legacyWal, "wal-bytes");

        Main.moveLegacyDb(legacy, target);
        assertThat(Files.readString(target)).isEqualTo("legacy-bytes"); // data survives the rename
        assertThat(Files.readString(tmp.resolve("strikebench.db-wal"))).isEqualTo("wal-bytes");
        assertThat(Files.exists(legacy)).isFalse();

        // Idempotent: a target already in place is never overwritten, a lingering legacy never consumed
        Files.writeString(legacy, "resurrected");
        Main.moveLegacyDb(legacy, target);
        assertThat(Files.readString(target)).isEqualTo("legacy-bytes");
        assertThat(Files.exists(legacy)).isTrue();
    }

    @Test
    void explicitDbPathIsNeverMigrated() throws Exception {
        // Guard clause: only the DEFAULT path triggers the move; custom DB_PATH is sacrosanct
        AppConfig custom = new AppConfig(Map.of("DB_PATH", tmp.resolve("mine.db").toString()));
        Main.migrateLegacyDefaultDb(custom); // must be a no-op, not an exception
        assertThat(Files.exists(tmp.resolve("mine.db"))).isFalse();

        AppConfig def = new AppConfig(Map.of());
        assertThat(def.dbPath()).isEqualTo("data/strikebench.db"); // pins the new default
    }

    @Test
    void missingLegacyIsANoOp() {
        Main.moveLegacyDb(tmp.resolve("nope.db"), tmp.resolve("target.db"));
        assertThat(Files.exists(tmp.resolve("target.db"))).isFalse();
    }
}
