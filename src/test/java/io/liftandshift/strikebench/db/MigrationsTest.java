package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.Json;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationsTest {

    @Test void emptyDatabaseMigratesToTheCurrentSchemaAndIsIdempotent() {
        var cfg = TestDb.emptyConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Migrations.run(db);
            Migrations.run(db); // second run applies nothing

            // Flyway owns the history; the baseline landed successfully.
            assertThat(db.query(
                    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                    r -> r.str("version"))).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
                    "11", "12", "13", "14", "15", "16", "17");

            // The baseline carries its seed rows and the current column shape.
            assertThat(db.query("SELECT id FROM users ORDER BY id", r -> r.str("id")))
                    .containsExactly("local", "system");
            assertThat(db.query("SELECT id FROM dataset ORDER BY id", r -> r.str("id")))
                    .containsExactly("demo-fixture", "observed");
            assertThat(db.query("SELECT COUNT(*) n FROM plan_candidate_warning", r -> r.lng("n")))
                    .containsExactly(0L);
            // V10: the persisted candidate carries the whole §7.2 price receipt, not two amounts.
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_candidate' "
                            + "AND column_name IN ('stock_cash_flow_cents','after_fee_net_cents',"
                            + "'valuation_basis','price_observed_at_epoch_ms','price_fingerprint') "
                            + "ORDER BY column_name",
                    r -> r.str("column_name"))).containsExactly("after_fee_net_cents",
                    "price_fingerprint", "price_observed_at_epoch_ms", "stock_cash_flow_cents",
                    "valuation_basis");
            assertThat(db.query("SELECT COUNT(*) n FROM market_event_evidence", r -> r.lng("n")))
                    .containsExactly(0L);
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='portfolio_valuation' "
                            + "AND column_name='broker_buying_power_cents'",
                    r -> r.str("column_name"))).containsExactly("broker_buying_power_cents");
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='account_objective_revision' "
                            + "AND column_name IN ('package_capacities','capacity_policy') ORDER BY column_name",
                    r -> r.str("column_name"))).containsExactly("capacity_policy", "package_capacities");
            // V11 renames the frozen decision's review horizon to the unit it actually holds.
            // It is a FORWARD migration on purpose: V1 is applied to every existing database and
            // editing it there renames nothing, it only breaks checksum validation.
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_decision' "
                            + "AND column_name LIKE 'review_horizon%'",
                    r -> r.str("column_name"))).containsExactly("review_horizon_sessions");
            assertThat(db.query("SELECT conname FROM pg_constraint "
                            + "WHERE conname LIKE 'plan_decision_review_horizon%'",
                    r -> r.str("conname"))).containsExactly("plan_decision_review_horizon_sessions_check");
            assertThat(db.query("SELECT COUNT(*) n FROM position_lifecycle_decision_receipt",
                    r -> r.lng("n"))).containsExactly(0L);
            assertThat(db.query("SELECT COUNT(*) n FROM position_lifecycle_user_decision",
                    r -> r.lng("n"))).containsExactly(0L);
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_strategy_run' "
                            + "AND column_name='sentiment_scorer_version'",
                    r -> r.str("column_name"))).containsExactly("sentiment_scorer_version");
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='data_sync_cursor' "
                            + "AND column_name='earliest_available'",
                    r -> r.str("column_name"))).containsExactly("earliest_available");
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_candidate' "
                            + "AND column_name='option_net_cents'",
                    r -> r.str("column_name"))).containsExactly("option_net_cents");
            // V14: opening and round-trip commission are captured independently on candidates,
            // and a frozen decision has one package-price receipt instead of a primitive twin.
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_candidate' "
                            + "AND column_name='estimated_round_trip_fees_cents'",
                    r -> r.str("column_name"))).containsExactly("estimated_round_trip_fees_cents");
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_decision' "
                            + "AND column_name IN ('price_receipt','proposed_net_cents') ORDER BY column_name",
                    r -> r.str("column_name"))).containsExactly("price_receipt");
            // V15: a typed LIMIT is the only persisted proposed package price. The ambiguous
            // aggregate legacy field (which also carried recorded-fill nets) is gone.
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='trades' "
                            + "AND column_name IN ('order_limit_net_cents','proposed_net_cents') "
                            + "ORDER BY column_name",
                    r -> r.str("column_name"))).containsExactly("order_limit_net_cents");
            // V9: the managed backtest stores its exit knobs in the ONE management-policy
            // vocabulary, so the old max-profit/max-loss/calendar-DTE column names are gone.
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='backtests' "
                            + "AND column_name IN ('take_profit_fraction','stop_multiple',"
                            + "'time_rule_sessions','profit_target_pct','stop_fraction','roll_dte') "
                            + "ORDER BY column_name",
                    r -> r.str("column_name"))).containsExactly(
                            "stop_multiple", "take_profit_fraction", "time_rule_sessions");
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_candidate_leg' "
                            + "AND column_name IN ('quote_bid','quote_ask','quote_as_of_epoch_ms',"
                            + "'quote_source','quote_freshness','quote_iv','quote_delta') "
                            + "ORDER BY column_name",
                    r -> r.str("column_name"))).containsExactly(
                            "quote_as_of_epoch_ms",
                            "quote_ask",
                            "quote_bid",
                            "quote_delta",
                            "quote_freshness",
                            "quote_iv",
                            "quote_source");
            // V13: a scanned row stays adoptable as the exact package it showed. The evaluation
            // records the market lane that priced it, and an adopted Plan structure records which
            // immutable evaluation it came from.
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='strategy_evaluation' "
                            + "AND column_name='world_id'",
                    r -> r.str("column_name"))).containsExactly("world_id");
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_candidate' "
                            + "AND column_name='source_evaluation_id'",
                    r -> r.str("column_name"))).containsExactly("source_evaluation_id");
        }
    }

    @Test void aLegacyNonFlywayDatabaseIsRejectedInsteadOfSilentlyAdopted() {
        // A database that predates Flyway (real tables, no history) must not be silently baselined —
        // pre-release databases are disposable, so the honest answer is "recreate it", not "guess".
        var cfg = TestDb.emptyConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            db.exec("CREATE TABLE old_shape(id TEXT PRIMARY KEY)");
            assertThatThrownBy(() -> Migrations.run(db))
                    .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
                    .hasMessageContaining("non-empty schema");
        }
    }

    @Test void anEditedAppliedMigrationFailsValidationInsteadOfDrifting() {
        var cfg = TestDb.emptyConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Migrations.run(db);
            // Simulate an applied migration being edited after the fact: its recorded checksum no
            // longer matches the file. Flyway must refuse to migrate rather than drift silently.
            db.exec("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'");
            assertThatThrownBy(() -> Migrations.run(db))
                    .isInstanceOf(FlywayValidateException.class);
        }
    }

    @Test void v14UpgradesHistoricalDecisionsToOneHonestPackagePriceReceipt() {
        var cfg = TestDb.emptyConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Flyway.configure()
                    .dataSource(db.dataSource())
                    .locations("classpath:db/migrations")
                    .target("13")
                    .load()
                    .migrate();

            legacyPlan(db, "plan-option", "AAPL");
            legacyPlan(db, "plan-stock", "NVDA");
            legacyPlan(db, "plan-unpriced", "SPY");
            legacyPlan(db, "plan-missing-leg-price", "MSFT");
            legacyPlan(db, "plan-zero-unavailable", "QQQ");

            db.exec("INSERT INTO plan_decision(id,plan_id,context_rev,action,qty,proposed_net_cents,"
                            + "quote_as_of,economic_verdict,evidence_provenance,model_version,"
                            + "review_horizon_sessions,decision_seq) "
                            + "VALUES('decision-option','plan-option',1,'TRADE',2,100000,"
                            + "'2026-07-20T14:30:00Z','MIXED','DELAYED','decision-legacy',30,1),"
                            + "('decision-stock','plan-stock',1,'TRADE',2,-1960000,"
                            + "'2026-07-20T14:31:00Z','MIXED','DELAYED','decision-legacy',30,1),"
                            + "('decision-unpriced','plan-unpriced',1,'CASH',NULL,NULL,"
                            + "'2026-07-20T14:32:00Z','CASH','UNAVAILABLE','decision-legacy',30,1),"
                            + "('decision-missing-leg-price','plan-missing-leg-price',1,'TRADE',1,-980000,"
                            + "'2026-07-20T14:33:00Z','MIXED','DELAYED','decision-legacy',30,1),"
                            + "('decision-zero-unavailable','plan-zero-unavailable',1,'TRADE',1,0,"
                            + "'2026-07-20T14:34:00Z','UNAVAILABLE','UNAVAILABLE','decision-legacy',30,1)");

            // The option-only decision already stored the full-quantity package net.
            legacyMetric(db, "decision-option", "entryNetPremiumCents", 100000L, null);
            legacyMetric(db, "decision-option", "feesOpenCents", 130L, null);
            legacyMetric(db, "decision-option", "orderLimitNetCents", 99000L, null);
            legacyMetric(db, "decision-option", "orderExecutability", null, "IMMEDIATE");
            legacyMetric(db, "decision-option", "orderValuationBasis", null, "EXECUTABLE_BOOK");

            // A buy-write's option and stock sides have to be recovered from the immutable legs,
            // rather than mislabeling the whole package debit as option premium.
            db.exec("INSERT INTO plan_decision_leg(decision_id,leg_index,action,instrument_type,"
                            + "strike_price,expiration,ratio,bid_price,ask_price,mid_price,fill_price,iv,multiplier) "
                            + "VALUES('decision-stock',0,'BUY','STOCK',NULL,NULL,100,100,100,100,100,NULL,1),"
                            + "('decision-stock',1,'SELL','CALL',140,'2026-08-21',1,2,2,2,2,0.25,100)");
            legacyMetric(db, "decision-stock", "entryNetPremiumCents", -1960000L, null);
            legacyMetric(db, "decision-stock", "feesOpenCents", 500L, null);
            legacyMetric(db, "decision-stock", "orderExecutability", null, "RESTING");
            legacyMetric(db, "decision-stock", "orderValuationBasis", null, "RECORDED_FILL");

            db.exec("INSERT INTO plan_decision_leg(decision_id,leg_index,action,instrument_type,"
                            + "strike_price,expiration,ratio,bid_price,ask_price,mid_price,fill_price,iv,multiplier) "
                            + "VALUES('decision-missing-leg-price',0,'BUY','STOCK',NULL,NULL,100,"
                            + "100,100,100,100,NULL,1),"
                            + "('decision-missing-leg-price',1,'SELL','CALL',110,'2026-08-21',1,"
                            + "NULL,NULL,NULL,NULL,NULL,100)");
            legacyMetric(db, "decision-missing-leg-price", "feesOpenCents", 100L, null);
            legacyMetric(db, "decision-missing-leg-price", "orderValuationBasis", null, "RECORDED_FILL");
            legacyMetric(db, "decision-zero-unavailable", "feesOpenCents", 0L, null);
            legacyMetric(db, "decision-zero-unavailable", "orderValuationBasis", null, "UNAVAILABLE");

            Migrations.run(db);

            PackagePriceReceipt option = receipt(db, "decision-option");
            assertThat(option.quantity()).isEqualTo(2);
            assertThat(option.optionNetPremiumCents()).isEqualTo(100000L);
            assertThat(option.stockCashFlowCents()).isZero();
            assertThat(option.grossPackageNetCents()).isEqualTo(100000L);
            assertThat(option.openingFeesCents()).isEqualTo(130L);
            assertThat(option.estimatedRoundTripFeesCents()).isEqualTo(260L);
            assertThat(option.afterFeeNetCents()).isEqualTo(99870L);
            assertThat(option.restingLimitNetCents()).isEqualTo(99000L);
            assertThat(option.valuationBasis())
                    .isEqualTo(PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK);

            PackagePriceReceipt stock = receipt(db, "decision-stock");
            assertThat(stock.optionNetPremiumCents()).isEqualTo(40000L);
            assertThat(stock.stockCashFlowCents()).isEqualTo(-2000000L);
            assertThat(stock.grossPackageNetCents()).isEqualTo(-1960000L);
            assertThat(stock.openingFeesCents()).isEqualTo(500L);
            assertThat(stock.estimatedRoundTripFeesCents()).isEqualTo(1000L);
            assertThat(stock.afterFeeNetCents()).isEqualTo(-1960500L);

            PackagePriceReceipt unavailable = receipt(db, "decision-unpriced");
            assertThat(unavailable.priced()).isFalse();
            assertThat(unavailable.grossPackageNetCents()).isNull();
            assertThat(unavailable.unavailableReason()).contains("no recorded package net");

            PackagePriceReceipt missingLegPrice = receipt(db, "decision-missing-leg-price");
            assertThat(missingLegPrice.priced()).isFalse();
            assertThat(missingLegPrice.optionNetPremiumCents()).isNull();
            assertThat(missingLegPrice.stockCashFlowCents()).isNull();
            assertThat(missingLegPrice.openingFeesCents()).isNull();
            assertThat(missingLegPrice.unavailableReason()).contains("cannot prove both sides");

            PackagePriceReceipt legacyZero = receipt(db, "decision-zero-unavailable");
            assertThat(legacyZero.priced()).isFalse();
            assertThat(legacyZero.grossPackageNetCents()).isNull();
            assertThat(legacyZero.openingFeesCents()).isNull();
            assertThat(legacyZero.unavailableReason()).contains("explicitly recorded");

            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_decision' "
                            + "AND column_name='proposed_net_cents'", r -> r.str("column_name")))
                    .isEmpty();
            assertThat(db.query("SELECT metric_key FROM plan_decision_metric "
                            + "WHERE metric_key IN ('entryNetPremiumCents','feesOpenCents',"
                            + "'orderExecutability','orderValuationBasis') ORDER BY metric_key",
                    r -> r.str("metric_key"))).isEmpty();

            assertThatThrownBy(() -> db.exec("UPDATE plan_decision SET price_receipt = "
                    + "jsonb_set(price_receipt,'{optionNetPremiumCents}','100'::jsonb) "
                    + "WHERE id='decision-unpriced'"))
                    .hasMessageContaining("plan_decision_price_receipt");
        }
    }

    @Test void v15KeepsOnlyTypedLimitProvenanceAndDropsTheAmbiguousTradePrice() {
        var cfg = TestDb.emptyConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Flyway.configure()
                    .dataSource(db.dataSource())
                    .locations("classpath:db/migrations")
                    .target("14")
                    .load()
                    .migrate();

            db.exec("INSERT INTO accounts(id,user_id,name,type,starting_cash_cents,cash_cents,"
                    + "reserved_cents,created_at,updated_at) VALUES"
                    + "('order-migration','local','Order migration','PAPER',100000,100000,0,"
                    + "'2026-07-20T12:00:00Z','2026-07-20T12:00:00Z')");
            String insert = "INSERT INTO trades(id,account_id,symbol,strategy,status,qty,legs_json,"
                    + "entry_underlying_cents,entry_net_premium_cents,max_loss_cents,breakevens_json,"
                    + "fees_open_cents,fees_close_cents,entry_snapshot_json,is_live,created_at,updated_at,"
                    + "proposed_net_cents) VALUES(?, 'order-migration','AAPL','CUSTOM','ACTIVE',1,"
                    + "'[]'::jsonb,10000,?,10000,'[]'::jsonb,0,0,?::jsonb,0,"
                    + "'2026-07-20T12:00:00Z','2026-07-20T12:00:00Z',?)";
            db.exec(insert, "typed-limit", 12_300L,
                    "{\"orderInstruction\":{\"type\":\"LIMIT\",\"limitNetCents\":12300}}", 12_300L);
            db.exec(insert, "recorded-fill", 9_900L,
                    "{\"orderInstruction\":{\"type\":\"MARKET\"}}", 9_900L);

            Flyway.configure()
                    .dataSource(db.dataSource())
                    .locations("classpath:db/migrations")
                    .target("15")
                    .load()
                    .migrate();

            assertThat(db.query("SELECT order_limit_net_cents FROM trades WHERE id='typed-limit'",
                    r -> r.lngOrNull("order_limit_net_cents"))).containsExactly(12_300L);
            assertThat(db.query("SELECT order_limit_net_cents FROM trades WHERE id='recorded-fill'",
                    r -> r.lngOrNull("order_limit_net_cents"))).containsExactly((Long) null);
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='trades' "
                            + "AND column_name='proposed_net_cents'",
                    r -> r.str("column_name"))).isEmpty();
        }
    }

    private static void legacyPlan(Db db, String id, String symbol) {
        db.exec("INSERT INTO plans(id,user_id,symbol,market_kind,status) "
                + "VALUES(?,'local',?,'OBSERVED','ACTIVE')", id, symbol);
        db.exec("INSERT INTO plan_context_revision(id,plan_id,rev,horizon_days,input_hash,engine_version) "
                + "VALUES(?, ?,1,30,?,'context-legacy')", "ctx-" + id, id, "hash-" + id);
        db.exec("UPDATE plans SET active_context_rev=1 WHERE id=?", id);
    }

    private static void legacyMetric(Db db, String decisionId, String key, Long cents, String text) {
        db.exec("INSERT INTO plan_decision_metric(decision_id,metric_key,value_cents,value_text) "
                + "VALUES(?,?,?,?)", decisionId, key, cents, text);
    }

    private static PackagePriceReceipt receipt(Db db, String decisionId) {
        String raw = db.query("SELECT price_receipt::text receipt FROM plan_decision WHERE id=?",
                r -> r.str("receipt"), decisionId).getFirst();
        return Json.read(raw, PackagePriceReceipt.class);
    }
}
