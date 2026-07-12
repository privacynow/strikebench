package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationsTest {

    @Test
    void ambiguousLegacyTradesAreNotPromotedToObservedEvidence() {
        Map<String, String> cfg = TestDb.freshConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Flyway.configure().dataSource(db.dataSource()).locations("classpath:db/migrations")
                    .target("14").load().migrate();
            db.exec("INSERT INTO accounts(id,name,type,starting_cash_cents,cash_cents,created_at,updated_at) "
                    + "VALUES('acct_migration','Migration','PAPER',100000,100000,'now','now')");
            insertLegacyTrade(db, "tr_ambiguous", "{\"legs\":[]}");
            insertLegacyTrade(db, "tr_observed", "{\"freshness\":\"DELAYED\"}");

            Migrations.run(db); // V15 only

            assertThat(db.query("SELECT data_provenance FROM trades WHERE id='tr_ambiguous'",
                    r -> r.str("data_provenance"))).containsExactly("UNKNOWN");
            assertThat(db.query("SELECT data_provenance FROM trades WHERE id='tr_observed'",
                    r -> r.str("data_provenance"))).containsExactly("OBSERVED");
        }
    }

    private static void insertLegacyTrade(Db db, String id, String snapshot) {
        db.exec("INSERT INTO trades(id,account_id,symbol,strategy,status,qty,legs_json,entry_underlying_cents,"
                        + "entry_net_premium_cents,max_loss_cents,breakevens_json,entry_snapshot_json,created_at,updated_at,"
                        + "data_provenance) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, "acct_migration", "AAPL", "LONG_CALL", "CLOSED", 1, "[]", 10000,
                -100, 100, "[]", snapshot, "now", "now", "OBSERVED");
    }
}
