package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationsTest {

    @Test
    void canonicalTimeAndPriceMigrationPreservesInstantsAndSixDecimalPrices() {
        Map<String, String> cfg = TestDb.freshConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Flyway.configure().dataSource(db.dataSource()).locations("classpath:db/migrations")
                    .target("50").load().migrate();
            db.exec("INSERT INTO accounts(id,user_id,name,type,starting_cash_cents,cash_cents,reserved_cents," +
                            "created_at,updated_at) VALUES('acct_time','local','Time test','PAPER',10000,10000,0,?,?)",
                    "2024-02-29T23:59:59.123456Z", "2024-03-01T00:00:00.123456Z");
            db.exec("INSERT INTO market_snapshot(symbol,last,bid,ask,prev_close,as_of) " +
                    "VALUES('PREC',1.2345,1.2300,1.2400,1.2200,1709251199123)");

            Migrations.run(db);

            assertThat(db.query("SELECT created_at FROM accounts WHERE id='acct_time'", r -> r.odt("created_at")))
                    .containsExactly(java.time.OffsetDateTime.parse("2024-02-29T23:59:59.123456Z"));
            assertThat(db.query("SELECT created_at FROM accounts WHERE id='acct_time'", r -> r.str("created_at")))
                    .containsExactly("2024-02-29T23:59:59.123456Z");
            db.exec("UPDATE market_snapshot SET last=1.234567 WHERE symbol='PREC'");
            assertThat(db.query("SELECT last FROM market_snapshot WHERE symbol='PREC'", r -> r.bd("last")))
                    .containsExactly(new java.math.BigDecimal("1.234567"));
            assertThat(db.query("SELECT data_type FROM information_schema.columns " +
                            "WHERE table_name='accounts' AND column_name='created_at'", r -> r.str("data_type")))
                    .containsExactly("timestamp with time zone");
            assertThat(db.query("SELECT numeric_precision || ':' || numeric_scale AS shape " +
                            "FROM information_schema.columns WHERE table_name='option_bar' AND column_name='bid'",
                    r -> r.str("shape"))).containsExactly("19:6");
            assertThat(db.query("SELECT table_name || '.' || column_name AS column_name " +
                            "FROM information_schema.columns WHERE table_schema='public' AND data_type='numeric' " +
                            "AND numeric_scale <> 6",
                    r -> r.str("column_name"))).isEmpty();
            assertThat(db.query("SELECT as_of FROM market_snapshot WHERE symbol='PREC'", r -> r.odt("as_of")))
                    .containsExactly(java.time.OffsetDateTime.parse("2024-02-29T23:59:59.123Z"));
            assertThat(db.query("SELECT table_name || '.' || column_name AS column_name " +
                            "FROM information_schema.columns WHERE table_schema='public' " +
                            "AND data_type <> 'timestamp with time zone' " +
                            "AND (column_name ~ '_at$' OR column_name IN ('as_of','ts'))",
                    r -> r.str("column_name"))).isEmpty();
        }
    }

    @Test
    void canonicalOwnerMigrationPreservesRowsAndEnforcesOneIdentityKey() {
        Map<String, String> cfg = TestDb.freshConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Flyway.configure().dataSource(db.dataSource()).locations("classpath:db/migrations")
                    .target("49").load().migrate();
            db.exec("INSERT INTO accounts(id,name,type,starting_cash_cents,cash_cents,created_at,updated_at) "
                    + "VALUES('acct_local','Local','PAPER',100000,100000,'now','now')");
            db.exec("INSERT INTO dataset(id,name,kind) VALUES('ds_local','Legacy local','SYNTHETIC_PURE')");
            db.exec("INSERT INTO workspace(user_id,state) VALUES('__local__','{}')");
            db.exec("INSERT INTO settings(k,v,updated_at) VALUES('active_world:null','demo','now')");
            db.exec("INSERT INTO sim_session(id,name,user_id,config,status) "
                    + "VALUES('sim_legacy','Legacy world','user:legacy-owner','{}','CREATED')");
            db.exec("INSERT INTO portfolio_account(id,owner_id,name,account_type) "
                    + "VALUES('pa_legacy','legacy-owner','Legacy book','TAXABLE')");
            db.exec("INSERT INTO data_sync_cursor(owner_key,source_key,symbol) "
                    + "VALUES('user:legacy-owner','csv','AAPL')");
            db.exec("INSERT INTO plans(id,client_request_id,create_input_hash,symbol,market_kind,status,active_stage) "
                    + "VALUES('plan_local','legacy-request','legacy-hash','AAPL','DEMO','ACTIVE','UNDERSTAND')");
            db.exec("INSERT INTO plan_create_request(owner_key,client_request_id,input_hash,plan_id) "
                    + "VALUES('__local__','legacy-request','legacy-hash','plan_local') ON CONFLICT DO NOTHING");

            Migrations.run(db);

            assertThat(db.query("SELECT user_id FROM accounts WHERE id='acct_local'", r -> r.str("user_id")))
                    .containsExactly("local");
            assertThat(db.query("SELECT user_id FROM dataset WHERE id='ds_local'", r -> r.str("user_id")))
                    .containsExactly("local");
            assertThat(db.query("SELECT user_id FROM workspace", r -> r.str("user_id"))).containsExactly("local");
            assertThat(db.query("SELECT v FROM settings WHERE k='active_world:local'", r -> r.str("v")))
                    .containsExactly("demo");
            assertThat(db.query("SELECT user_id FROM sim_session WHERE id='sim_legacy'", r -> r.str("user_id")))
                    .containsExactly("legacy-owner");
            assertThat(db.query("SELECT user_id FROM portfolio_account WHERE id='pa_legacy'", r -> r.str("user_id")))
                    .containsExactly("legacy-owner");
            assertThat(db.query("SELECT user_id FROM data_sync_cursor", r -> r.str("user_id")))
                    .containsExactly("legacy-owner");
            assertThat(db.query("SELECT user_id FROM plan_create_request WHERE plan_id='plan_local'", r -> r.str("user_id")))
                    .containsExactly("local");
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_name='plans' AND column_name IN ('client_request_id','create_input_hash')",
                    r -> r.str("column_name"))).isEmpty();
            assertThatThrownBy(() -> db.exec("INSERT INTO workspace(user_id,state) VALUES('missing-owner','{}')"))
                    .hasMessageContaining("foreign key");
            assertThatThrownBy(() -> db.exec("INSERT INTO plans(id,user_id,symbol,market_kind,world_id,status,active_stage) "
                            + "VALUES('bad-world','local','AAPL','SIMULATED','missing-world','ACTIVE','UNDERSTAND')"))
                    .hasMessageContaining("foreign key");
        }
    }

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
