package io.liftandshift.strikebench.market.sim;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.EventBus;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationSessionsPersistedSymbolCompatibilityTest {
    private Db db;

    @AfterEach
    void close() {
        if (db != null) db.close();
    }

    @Test
    void restoreCanonicalizesAndPersistsLegacyAliasesAcrossEverySymbolMap() {
        db = TestDb.fresh();
        String owner = "legacy-world-owner";
        SimulationSessions original = new SimulationSessions(db, new EventBus());
        SimulatedWorld world = original.create(config(null, Map.of("MSFT", 1.0),
                Map.of("MSFT", 400.0)), owner);
        original.clearResident();

        Map<String, Object> legacy = configDocument(world.worldId(),
                Map.of("brk-b", 1.0), Map.of("BRK/B", 410.0),
                Map.of("brk-b", 0.24), Map.of("BRK-B", 0.29));
        db.exec("UPDATE sim_session SET config=?::jsonb WHERE id=?",
                Json.write(legacy), world.worldId());

        SimulationSessions restarted = new SimulationSessions(db, new EventBus());
        SimulatedWorld restored = restarted.getOrRestore(world.worldId(), owner).orElseThrow();

        assertThat(restored.config().symbolBetas()).containsOnlyKeys("BRK.B");
        assertThat(restored.config().startSpots()).containsOnlyKeys("BRK.B");
        assertThat(restored.config().symbolVols()).containsOnlyKeys("BRK.B");
        assertThat(restored.config().symbolIvs()).containsOnlyKeys("BRK.B");
        String persisted = db.query("SELECT config::text c FROM sim_session WHERE id=?",
                r -> r.str("c"), world.worldId()).getFirst();
        assertThat(persisted).contains("\"BRK.B\"")
                .doesNotContain("brk-b")
                .doesNotContain("BRK-B")
                .doesNotContain("BRK/B");
    }

    @Test
    void collisionAndMalformedMembersAreDurablyDisabledWithoutBreakingHealthyWorldReads() {
        db = TestDb.fresh();
        String owner = "mixed-world-owner";
        SimulationSessions original = new SimulationSessions(db, new EventBus());
        SimulatedWorld healthy = original.create(config(null, Map.of("AAPL", 1.0),
                Map.of("AAPL", 200.0)), owner);
        SimulatedWorld collision = original.create(config(null, Map.of("MSFT", 1.0),
                Map.of("MSFT", 400.0)), owner);
        SimulatedWorld malformed = original.create(config(null, Map.of("QQQ", 1.0),
                Map.of("QQQ", 500.0)), owner);
        original.clearResident();

        Map<String, Double> collidingBetas = new LinkedHashMap<>();
        collidingBetas.put("BRK.B", 1.0);
        collidingBetas.put("BRK-B", 0.8);
        db.exec("UPDATE sim_session SET config=?::jsonb WHERE id=?",
                Json.write(configDocument(collision.worldId(), collidingBetas,
                        Map.of("BRK.B", 410.0), null, null)), collision.worldId());
        db.exec("UPDATE sim_session SET config=?::jsonb WHERE id=?",
                Json.write(configDocument(malformed.worldId(), Map.of("../AAPL", 1.0),
                        Map.of("../AAPL", 200.0), null, null)), malformed.worldId());

        SimulationSessions restarted = new SimulationSessions(db, new EventBus());
        List<Map<String, Object>> rows = restarted.list(owner);

        assertThat(rows).hasSize(3);
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("id")).isEqualTo(healthy.worldId());
            assertThat(row.get("status")).isEqualTo("CREATED");
            assertThat(row).doesNotContainKey("compatibilityError");
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("id")).isEqualTo(collision.worldId());
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(String.valueOf(row.get("compatibilityError")))
                    .contains("canonical symbol collision").contains("BRK.B");
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("id")).isEqualTo(malformed.worldId());
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(String.valueOf(row.get("compatibilityError"))).contains("invalid symbol");
        });
        assertThat(restarted.getOrRestore(healthy.worldId(), owner)).isPresent();
        assertThatThrownBy(() -> restarted.getOrRestore(collision.worldId(), owner))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled")
                .hasMessageContaining("canonical symbol collision");
        assertThat(db.query("SELECT status FROM sim_session WHERE id IN (?,?) ORDER BY id",
                r -> r.str("status"), collision.worldId(), malformed.worldId()))
                .containsOnly("FAILED");
        assertThat(restarted.anchors(collision.worldId(), owner))
                .containsEntry("compatibilityStatus", "DISABLED")
                .containsKey("compatibilityReason");
    }

    @Test
    void persistedMoveSymbolsAreCanonicalizedAndOutsiderEventsDisableOnlyTheirWorld() {
        db = TestDb.fresh();
        String owner = "legacy-event-owner";
        SimulationSessions original = new SimulationSessions(db, new EventBus());
        SimulatedWorld canonicalWorld = original.create(
                config(null, Map.of("BRK.B", 1.0), Map.of("BRK.B", 410.0)), owner);
        SimulatedWorld outsider = original.create(config(null, Map.of("AAPL", 1.0),
                Map.of("AAPL", 200.0)), owner);
        original.clearResident();

        db.exec("INSERT INTO sim_session_event(sim_session_id,event_index,quantum,kind,symbol,value) "
                        + "VALUES(?,0,0,'MOVE','brk/b',0.01)",
                canonicalWorld.worldId());
        db.exec("INSERT INTO sim_session_event(sim_session_id,event_index,quantum,kind,symbol,value) "
                        + "VALUES(?,0,0,'MOVE','MSFT',0.01)",
                outsider.worldId());

        SimulationSessions restarted = new SimulationSessions(db, new EventBus());
        List<Map<String, Object>> rows = restarted.list(owner);

        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("id")).isEqualTo(canonicalWorld.worldId());
            assertThat(row.get("status")).isEqualTo("CREATED");
            assertThat(row).doesNotContainKey("compatibilityError");
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("id")).isEqualTo(outsider.worldId());
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(String.valueOf(row.get("compatibilityError")))
                    .contains("outside the simulated-world universe");
        });
        assertThat(db.query("SELECT symbol FROM sim_session_event WHERE sim_session_id=?",
                r -> r.str("symbol"), canonicalWorld.worldId())).containsExactly("BRK.B");
        @SuppressWarnings("unchecked")
        List<SimulatedWorld.WorldEvent> events = (List<SimulatedWorld.WorldEvent>)
                restarted.replayRecord(canonicalWorld.worldId(), owner).get("events");
        assertThat(events).extracting(SimulatedWorld.WorldEvent::symbol).containsExactly("BRK.B");
    }

    private static SimulatedWorld.Config config(String worldId, Map<String, Double> betas,
                                                Map<String, Double> spots) {
        return new SimulatedWorld.Config(worldId, "Compatibility world", betas, spots,
                "CHOP", 0.25, 42L, "2026-07-27T09:30:00", 1.0, null, null);
    }

    private static Map<String, Object> configDocument(String worldId,
                                                      Map<String, Double> betas,
                                                      Map<String, Double> spots,
                                                      Map<String, Double> vols,
                                                      Map<String, Double> ivs) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("worldId", worldId);
        doc.put("name", "Persisted compatibility world");
        doc.put("symbolBetas", betas);
        doc.put("startSpots", spots);
        doc.put("scenario", "CHOP");
        doc.put("volAnnual", 0.25);
        doc.put("seed", 42L);
        doc.put("startSimTime", "2026-07-27T09:30:00");
        doc.put("speed", 1.0);
        if (vols != null) doc.put("symbolVols", vols);
        if (ivs != null) doc.put("symbolIvs", ivs);
        return doc;
    }
}
