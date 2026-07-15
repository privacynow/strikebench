package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationsTest {

    @Test
    void lateCollisionGuardBootsDatabaseAlreadyAtV56(@TempDir Path legacyMigrations) throws IOException {
        Path source = Path.of("src/main/resources/db/migrations");
        try (var files = Files.list(source)) {
            for (Path migration : files.toList()) {
                String name = migration.getFileName().toString();
                if (!name.equals("V49_0_1__prepare_late_owner_collision_guard.sql")
                        && !name.equals("V49_1__dedupe_canonical_owner_collisions.sql")
                        && !name.equals("V49_2__remove_late_owner_collision_aliases.sql")) {
                    Files.copy(migration, legacyMigrations.resolve(migration.getFileName()));
                }
            }
        }

        Map<String, String> cfg = TestDb.freshConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Flyway.configure().dataSource(db.dataSource())
                    .locations("filesystem:" + legacyMigrations.toAbsolutePath())
                    .target("56").load().migrate();

            assertThat(db.query("SELECT version FROM flyway_schema_history WHERE success "
                            + "ORDER BY installed_rank DESC LIMIT 1", r -> r.str("version")))
                    .containsExactly("56");
            assertThat(db.query("SELECT version FROM flyway_schema_history "
                            + "WHERE version IN ('49.0.1','49.1','49.2') ORDER BY version",
                    r -> r.str("version"))).isEmpty();
            assertThatThrownBy(() -> Flyway.configure().dataSource(db.dataSource())
                    .locations("classpath:db/migrations").load().migrate())
                    .isInstanceOf(FlywayValidateException.class)
                    .hasMessageContaining("resolved migration not applied");

            Migrations.run(db);

            assertThat(db.query("SELECT version FROM flyway_schema_history "
                            + "WHERE version IN ('49.0.1','49.1','49.2') AND success ORDER BY installed_rank",
                    r -> r.str("version"))).containsExactly("49.0.1", "49.1", "49.2");
            assertThat(db.query("SELECT version FROM flyway_schema_history WHERE success "
                            + "ORDER BY installed_rank DESC LIMIT 1",
                    r -> r.str("version"))).containsExactly("49.2");
            assertThat(db.query("SELECT table_name || '.' || column_name AS alias "
                            + "FROM information_schema.columns WHERE table_schema='public' "
                            + "AND table_name IN ('data_sync_schedule','data_sync_cursor','plan_create_request') "
                            + "AND column_name='owner_key'", r -> r.str("alias"))).isEmpty();
        }
    }

    @Test
    void planProgressMigrationPreservesExistingSavedStage() {
        Map<String, String> cfg = TestDb.freshConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Flyway.configure().dataSource(db.dataSource()).locations("classpath:db/migrations")
                    .target("55").load().migrate();
            db.exec("INSERT INTO plans(id,user_id,symbol,market_kind,status,active_stage) "
                    + "VALUES('plan_progress','local','AAPL','DEMO','ACTIVE','OUTCOMES')");

            Migrations.run(db);

            assertThat(db.query("SELECT furthest_stage FROM plans WHERE id='plan_progress'", r -> r.str("furthest_stage")))
                    .containsExactly("OUTCOMES");
            assertThat(db.query("SELECT column_name FROM information_schema.columns WHERE table_name='plans' "
                            + "AND column_name='active_stage'", r -> r.str("column_name")))
                    .isEmpty();
        }
    }

    @Test
    void normalizedBacktestMigrationPreservesOrderedReportsAndDropsBlobs() {
        Map<String, String> cfg = TestDb.freshConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Flyway.configure().dataSource(db.dataSource()).locations("classpath:db/migrations")
                    .target("53").load().migrate();
            String request = """
                    {"symbol":"AAPL","strategy":"LONG_CALL","from":"2026-01-02","to":"2026-06-30",
                     "targetDte":30,"entryEveryDays":5,"qty":1,"slippagePct":0.005,
                     "startingCashCents":10000000}
                    """;
            String trade = """
                    {"entryDate":"2026-01-02","exitDate":"2026-02-02","label":"BUY 250C",
                     "entryNetPremiumCents":-50000,"exitValueCents":61000,"feesCents":1000,
                     "pnlCents":10000,"maxLossCents":50000,"returnOnRisk":0.2,
                     "exitReason":"EXPIRED","assigned":false,"entryUnderlyingCents":25500}
                    """.strip();
            String report = """
                    {"id":"bt_normalize","symbol":"AAPL","strategy":"LONG_CALL",
                     "from":"2026-01-02","to":"2026-06-30","pricingMode":"MODELED_FROM_UNDERLYING",
                     "confidence":"medium","daysRequested":128,"daysCovered":124,"sampleSize":2,
                     "winRate":1.0,"avgReturnOnRisk":0.2,"startingCents":10000000,
                     "endingCents":10020000,"maxDrawdownPct":0.01,"worstTrade":%s,
                     "trades":[%s,%s],"skipped":[{"date":"2026-03-02","reason":"no chain"}],
                     "assumptions":{"slippagePct":0.005,"pricing":"executable"},
                     "equityCurve":[{"date":"2026-01-02","equityCents":10000000},
                                    {"date":"2026-06-30","equityCents":10020000}],
                     "notes":["First","Second"],"assignments":0,"demoUnderlying":false,
                     "disclaimer":"Educational"}
                    """.formatted(trade, trade, trade);
            db.exec("INSERT INTO backtests(id,user_id,created_at,request_json,report_json) VALUES(?,?,?,?,?)",
                    "bt_normalize", "local", "2026-06-30T20:00:00Z", request, report);

            Migrations.run(db);

            assertThat(db.query("SELECT run_kind || ':' || symbol || ':' || target_dte || ':' || sample_size AS run "
                    + "FROM backtests WHERE id='bt_normalize'", r -> r.str("run")))
                    .containsExactly("SINGLE:AAPL:30:2");
            assertThat(db.query("SELECT trade_index || ':' || pnl_cents || ':' || is_worst AS item FROM backtest_trade "
                    + "WHERE backtest_id='bt_normalize' ORDER BY trade_index", r -> r.str("item")))
                    .containsExactly("0:10000:1", "1:10000:0");
            assertThat(db.query("SELECT point_index || ':' || equity_cents AS item FROM backtest_equity_point "
                    + "WHERE backtest_id='bt_normalize' ORDER BY point_index", r -> r.str("item")))
                    .containsExactly("0:10000000", "1:10020000");
            assertThat(db.query("SELECT assumption_value #>> '{}' AS value FROM backtest_assumption "
                    + "WHERE backtest_id='bt_normalize' AND assumption_key='pricing'", r -> r.str("value")))
                    .containsExactly("executable");
            assertThat(db.query("SELECT column_name FROM information_schema.columns WHERE table_name='backtests' "
                    + "AND column_name IN ('request_json','report_json')", r -> r.str("column_name"))).isEmpty();
        }
    }

    @Test
    void canonicalJsonMigrationPreservesReceiptsAndRemovesParallelProfiles() {
        Map<String, String> cfg = TestDb.freshConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Flyway.configure().dataSource(db.dataSource()).locations("classpath:db/migrations")
                    .target("52").load().migrate();
            db.exec("INSERT INTO accounts(id,user_id,name,type,starting_cash_cents,cash_cents,reserved_cents,created_at,updated_at) "
                    + "VALUES('acct_json','local','JSON','PAPER',100000,100000,0,now(),now())");
            db.exec("INSERT INTO trades(id,account_id,symbol,strategy,status,qty,legs_json,entry_underlying_cents," +
                            "entry_net_premium_cents,max_loss_cents,breakevens_json,entry_snapshot_json,created_at,updated_at) " +
                            "VALUES('trade_json','acct_json','AAPL','LONG_CALL','ACTIVE',1,?,10000,-100,100,?,?,now(),now())",
                    "[{\"action\":\"BUY\"}]", "[101.25]", "{\"freshness\":\"DELAYED\"}");
            db.exec("INSERT INTO strategy_evaluation(id,user_id,symbol,strategy,score,evidence_level,risk_json," +
                            "economics_json,explanation_json) VALUES('eval_json','local','AAPL','LONG_CALL',42,'MODELED',?::jsonb,?::jsonb,?::jsonb)",
                    "{\"scenarios\":[1,2]}", "{\"verdict\":\"MIXED\"}", "{\"assumptions\":[\"x\"]}");
            db.exec("INSERT INTO plans(id,user_id,symbol,market_kind,status,active_stage) " +
                    "VALUES('plan_json','local','AAPL','DEMO','ACTIVE','STRATEGY')");
            db.exec("INSERT INTO plan_context_revision(id,plan_id,rev,horizon_days,input_hash,engine_version) " +
                    "VALUES('pctx_json','plan_json',1,30,'ctx-hash','ctx-1')");
            db.exec("UPDATE plans SET active_context_rev=1 WHERE id='plan_json'");
            db.exec("INSERT INTO plan_strategy_run(id,plan_id,context_rev,run_kind,scope_kind,horizon,risk_mode,intent," +
                    "input_hash,engine_version,state) VALUES('psr_json','plan_json',1,'COMPETITION','PLAN','month'," +
                    "'balanced','DIRECTIONAL','run-hash','run-1','CURRENT')");
            db.exec("INSERT INTO plan_strategy_param(run_id,param_key,value_text) VALUES('psr_json','filters.kind','calls')");
            db.exec("INSERT INTO plan_strategy_param(run_id,param_key,value_boolean) VALUES('psr_json','allow0dte',1)");
            db.exec("INSERT INTO plan_candidate(id,plan_id,context_rev,family,input_hash,state,run_id,evaluation_snapshot) " +
                    "VALUES('pcand_json','plan_json',1,'LONG_CALL','candidate-hash','CURRENT','psr_json',?)",
                    "{\"risk\":{\"maxLossCents\":100}}");

            Migrations.run(db);

            assertThat(db.query("SELECT jsonb_typeof(legs_json) || ':' || jsonb_typeof(entry_snapshot_json) AS shape " +
                    "FROM trades WHERE id='trade_json'", r -> r.str("shape"))).containsExactly("array:object");
            assertThat(db.query("SELECT receipt #>> '{risk,scenarios,1}' AS scenario,receipt #>> '{economics,verdict}' verdict " +
                            "FROM strategy_evaluation WHERE id='eval_json'",
                    r -> r.str("scenario") + ":" + r.str("verdict"))).containsExactly("2:MIXED");
            assertThat(db.query("SELECT request_snapshot->>'filters.kind' kind," +
                            "request_snapshot->>'allow0dte' allow0dte FROM plan_strategy_run WHERE id='psr_json'",
                    r -> r.str("kind") + ":" + r.str("allow0dte"))).containsExactly("calls:true");
            assertThat(db.query("SELECT evaluation_snapshot #>> '{risk,maxLossCents}' value FROM plan_candidate " +
                    "WHERE id='pcand_json'", r -> r.str("value"))).containsExactly("100");
            assertThat(db.query("SELECT table_name FROM information_schema.tables WHERE table_name='plan_strategy_param'",
                    r -> r.str("table_name"))).isEmpty();
            assertThat(db.query("SELECT column_name FROM information_schema.columns WHERE table_name='strategy_evaluation' " +
                            "AND column_name LIKE '%_json'", r -> r.str("column_name"))).isEmpty();
            assertThatThrownBy(() -> db.exec("UPDATE trades SET legs_json='{}'::jsonb WHERE id='trade_json'"))
                    .hasMessageContaining("trades_legs_json_array");
        }
    }

    @Test
    void structuredCollectionsMigrationPreservesOrderAndDropsJsonColumns() {
        Map<String, String> cfg = TestDb.freshConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Flyway.configure().dataSource(db.dataSource()).locations("classpath:db/migrations")
                    .target("51").load().migrate();
            db.exec("INSERT INTO portfolio_account(id,user_id,name,account_type) "
                    + "VALUES('pa_collections','local','Collections','TAXABLE')");
            db.exec("INSERT INTO portfolio_valuation(id,portfolio_account_id,as_of,total_value_cents,source,complete,missing_marks) "
                            + "VALUES('pval_collections','pa_collections','2026-07-13T20:00:00Z',100000,'CALCULATED',0,?)",
                    "[\"AAPL option\",\"SPY\"]");
            db.exec("INSERT INTO sim_session(id,name,user_id,config,status,events) VALUES(?,?,?,?,?,?::jsonb)",
                    "sim_collections", "Collections", "local", "{}", "PAUSED",
                    "[{\"quantum\":2,\"kind\":\"SPEED\",\"symbol\":null,\"value\":26.0},"
                            + "{\"quantum\":5,\"kind\":\"MOVE\",\"symbol\":\"AAPL\",\"value\":-0.05}]");

            Migrations.run(db);

            assertThat(db.query("SELECT ordinal || ':' || mark_label AS mark FROM portfolio_valuation_missing_mark "
                            + "WHERE valuation_id='pval_collections' ORDER BY ordinal", r -> r.str("mark")))
                    .containsExactly("0:AAPL option", "1:SPY");
            assertThat(db.query("SELECT event_index || ':' || quantum || ':' || kind || ':' || COALESCE(symbol,'-') "
                            + "AS event FROM sim_session_event WHERE sim_session_id='sim_collections' ORDER BY event_index",
                    r -> r.str("event"))).containsExactly("0:2:SPEED:-", "1:5:MOVE:AAPL");
            assertThat(db.query("SELECT table_name || '.' || column_name AS legacy_column "
                            + "FROM information_schema.columns WHERE table_schema='public' AND "
                            + "((table_name='portfolio_valuation' AND column_name='missing_marks') OR "
                            + "(table_name='sim_session' AND column_name='events'))",
                    r -> r.str("legacy_column"))).isEmpty();
        }
    }

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
            assertThatThrownBy(() -> db.exec("INSERT INTO plans(id,user_id,symbol,market_kind,world_id,status,furthest_stage) "
                            + "VALUES('bad-world','local','AAPL','SIMULATED','missing-world','ACTIVE','UNDERSTAND')"))
                    .hasMessageContaining("foreign key");
        }
    }

    @Test
    void canonicalOwnerMigrationMergesGrownDatabaseIdentityCollisions() {
        Map<String, String> cfg = TestDb.freshConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Flyway.configure().dataSource(db.dataSource()).locations("classpath:db/migrations")
                    .target("49").load().migrate();

            db.exec("INSERT INTO workspace(user_id,state,rev,updated_at) VALUES "
                    + "('local','{\"snapshot\":\"old\"}',2,'2025-01-01T00:00:00Z'),"
                    + "('__local__','{\"snapshot\":\"new\"}',3,'2025-02-01T00:00:00Z')");
            db.exec("INSERT INTO data_sync_schedule(owner_key,enabled,source_key,symbols,years,updated_at) VALUES "
                    + "('local',0,'csv','AAPL',2,'2025-01-01T00:00:00Z'),"
                    + "('user:local',1,'yahoo','QQQ,SPY',5,'2025-02-01T00:00:00Z')");
            db.exec("INSERT INTO data_sync_cursor(owner_key,source_key,symbol,status,requested_from,requested_to,"
                    + "last_success_date,last_attempt_at,next_allowed_at,failure_count,rows_written,note,updated_at) VALUES "
                    + "('local','yahoo','QQQ','COMPLETE','2024-02-01','2024-12-31','2024-12-31',"
                    + "'2025-01-01T00:00:00Z',NULL,0,300,'older success','2025-01-01T00:00:00Z'),"
                    + "('__local__','yahoo','QQQ','FAILED','2024-01-01','2025-01-31','2024-11-30',"
                    + "'2025-02-01T00:00:00Z','2025-02-02T00:00:00Z',2,10,'latest failure','2025-02-01T00:00:00Z')");
            db.exec("INSERT INTO plans(id,client_request_id,create_input_hash,symbol,market_kind,status,active_stage) VALUES "
                    + "('plan_first','plan-first-request','plan-first-hash','AAPL','DEMO','ACTIVE','UNDERSTAND'),"
                    + "('plan_later','plan-later-request','plan-later-hash','QQQ','DEMO','ACTIVE','UNDERSTAND')");
            db.exec("INSERT INTO plan_create_request(owner_key,client_request_id,input_hash,plan_id,created_at) VALUES "
                    + "('local','same-request','first-hash','plan_first','2025-01-01T00:00:00Z'),"
                    + "('__local__','same-request','later-hash','plan_later','2025-02-01T00:00:00Z')");

            Migrations.run(db);

            assertThat(db.query("SELECT user_id,state->>'snapshot' snapshot,rev FROM workspace",
                    r -> r.str("user_id") + ":" + r.str("snapshot") + ":" + r.lng("rev")))
                    .containsExactly("local:new:3");
            assertThat(db.query("SELECT user_id,enabled,source_key,symbols,years FROM data_sync_schedule",
                    r -> r.str("user_id") + ":" + r.bool("enabled") + ":" + r.str("source_key")
                            + ":" + r.str("symbols") + ":" + r.intv("years")))
                    .containsExactly("local:true:yahoo:QQQ,SPY:5");
            assertThat(db.query("SELECT user_id,status,requested_from::text rf,requested_to::text rt,"
                            + "last_success_date::text ls,to_char(next_allowed_at AT TIME ZONE 'UTC',"
                            + "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') na,rows_written,note "
                            + "FROM data_sync_cursor",
                    r -> List.of(r.str("user_id"), r.str("status"), r.str("rf"), r.str("rt"),
                            r.str("ls"), r.str("na"), Long.toString(r.lng("rows_written")), r.str("note"))))
                    .containsExactly(List.of("local", "FAILED", "2024-01-01", "2025-01-31",
                            "2024-12-31", "2025-02-02T00:00:00Z", "300", "latest failure"));
            assertThat(db.query("SELECT user_id,input_hash,plan_id FROM plan_create_request "
                            + "WHERE client_request_id='same-request'",
                    r -> r.str("user_id") + ":" + r.str("input_hash") + ":" + r.str("plan_id")))
                    .containsExactly("local:first-hash:plan_first");
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
