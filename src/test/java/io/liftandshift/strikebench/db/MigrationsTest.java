package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationsTest {

    @Test
    void broadBasedIndexTaxonomyBackfillsExistingLotsAndRealizedMatches() {
        Map<String, String> cfg = TestDb.freshConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Flyway.configure().dataSource(db.dataSource()).locations("classpath:db/migrations")
                    .target("45").load().migrate();
            db.exec("INSERT INTO portfolio_account(id,owner_id,name,account_type) "
                    + "VALUES('pa_taxonomy','local','Taxonomy upgrade','TAXABLE')");
            db.exec("INSERT INTO portfolio_transaction(id,portfolio_account_id,occurred_at,event_type,cash_effect_cents,fees_cents,source,external_ref) VALUES "
                    + "('ptx_open','pa_taxonomy','2025-01-02','TRADE',-102000,0,'BROKER','open'),"
                    + "('ptx_close','pa_taxonomy','2025-02-02','TRADE',120000,0,'BROKER','close')");
            db.exec("INSERT INTO portfolio_transaction_leg(transaction_id,leg_no,instrument_type,action,position_effect,symbol,option_type,strike,expiration,quantity,multiplier,price,gross_amount_cents) VALUES "
                    + "('ptx_open',0,'OPTION','BUY','OPEN','XSP','CALL',600,'2026-03-20',1,100,10,100000),"
                    + "('ptx_open',1,'OPTION','BUY','OPEN','VIXY','CALL',50,'2026-03-20',1,100,2,20000),"
                    + "('ptx_close',0,'OPTION','SELL','CLOSE','XSP','CALL',600,'2026-03-20',1,100,12,120000)");
            db.exec("INSERT INTO portfolio_lot(id,portfolio_account_id,opening_transaction_id,opening_leg_no,instrument_type,side,symbol,option_type,strike,expiration,opened_at,acquired_at,original_quantity,remaining_quantity,original_open_amount_cents,remaining_open_amount_cents,multiplier,status) VALUES "
                    + "('lot_xsp','pa_taxonomy','ptx_open',0,'OPTION','LONG','XSP','CALL',600,'2026-03-20','2025-01-02','2025-01-02',1,0,100000,0,100,'CLOSED'),"
                    + "('lot_vixy','pa_taxonomy','ptx_open',1,'OPTION','LONG','VIXY','CALL',50,'2026-03-20','2025-01-02','2025-01-02',1,1,20000,20000,100,'OPEN')");
            List<String> migratedRoots = List.of(
                    "SPX", "SPXW", "SPXPM", "XSP", "NDX", "NDXP", "VIX", "VIXW",
                    "RUT", "RUTW", "DJX", "OEX", "XEO");
            for (int i = 0; i < migratedRoots.size(); i++) {
                String symbol = migratedRoots.get(i);
                int legNo = i + 2;
                db.exec("INSERT INTO portfolio_transaction_leg(transaction_id,leg_no,instrument_type,action,position_effect,symbol,option_type,strike,expiration,quantity,multiplier,price,gross_amount_cents) "
                                + "VALUES(?,?,'OPTION','BUY','OPEN',?,'CALL',100,'2026-03-20',1,100,1,10000)",
                        "ptx_open", legNo, symbol);
                db.exec("INSERT INTO portfolio_lot(id,portfolio_account_id,opening_transaction_id,opening_leg_no,instrument_type,side,symbol,option_type,strike,expiration,opened_at,acquired_at,original_quantity,remaining_quantity,original_open_amount_cents,remaining_open_amount_cents,multiplier,status) "
                                + "VALUES(?,'pa_taxonomy','ptx_open',?,'OPTION','LONG',?,'CALL',100,'2026-03-20','2025-01-02','2025-01-02',1,1,10000,10000,100,'OPEN')",
                        "lot_taxonomy_" + i, legNo, symbol);
            }
            db.exec("INSERT INTO portfolio_lot_match(portfolio_account_id,lot_id,closing_transaction_id,closing_leg_no,quantity,opened_at,closed_at,open_amount_cents,close_amount_cents,realized_gain_cents,holding_term) "
                    + "VALUES('pa_taxonomy','lot_xsp','ptx_close',0,1,'2025-01-02','2025-02-02',100000,120000,20000,'SHORT_TERM')");

            Migrations.run(db);

            assertThat(db.query("SELECT section_1256 FROM portfolio_transaction_leg WHERE symbol='XSP'",
                    r -> r.bool("section_1256"))).containsOnly(true);
            assertThat(db.query("SELECT section_1256 FROM portfolio_lot WHERE id='lot_xsp'",
                    r -> r.bool("section_1256"))).containsExactly(true);
            assertThat(db.query("SELECT section_1256,holding_term FROM portfolio_lot_match WHERE lot_id='lot_xsp'",
                    r -> Map.entry(r.bool("section_1256"), r.str("holding_term"))))
                    .containsExactly(Map.entry(true, "SECTION_1256"));
            assertThat(db.query("SELECT section_1256 FROM portfolio_lot WHERE id='lot_vixy'",
                    r -> r.bool("section_1256"))).containsExactly(false);
            assertThat(db.query("SELECT symbol FROM portfolio_lot WHERE section_1256=1",
                    r -> r.str("symbol"))).containsAll(migratedRoots);
        }
    }

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
