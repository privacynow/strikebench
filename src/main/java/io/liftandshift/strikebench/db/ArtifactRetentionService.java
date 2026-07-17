package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Reclaims only superseded computed material. User facts and decision evidence are permanent:
 * accounting, audit, observed market history, frozen Plans, selected decisions, rehearsal paths,
 * and resolved recommendation outcomes never enter these predicates.
 */
public final class ArtifactRetentionService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ArtifactRetentionService.class);

    public record Policy(boolean enabled, int stalePlanDays, int orphanEnsembleDays,
                         int initialDelaySeconds, int intervalHours) {
        public Policy {
            stalePlanDays = Math.max(1, stalePlanDays);
            orphanEnsembleDays = Math.max(stalePlanDays, orphanEnsembleDays);
            initialDelaySeconds = Math.max(0, initialDelaySeconds);
            intervalHours = Math.max(1, intervalHours);
        }

        public static Policy from(AppConfig cfg) {
            return new Policy(cfg.artifactRetentionEnabled(), cfg.stalePlanArtifactRetentionDays(),
                    cfg.orphanEnsembleRetentionDays(), cfg.artifactRetentionInitialDelaySeconds(),
                    cfg.artifactRetentionIntervalHours());
        }

        long intervalSeconds() { return Math.multiplyExact((long) intervalHours, 3600L); }
    }

    public record CleanupResult(int evidenceRuns, int strategyRuns, int outcomeRuns,
                                int outcomeComparisons, int planBacktests, int planEnsembles,
                                int ensembleArtifacts) {
        public int total() {
            return evidenceRuns + strategyRuns + outcomeRuns + outcomeComparisons + planBacktests
                    + planEnsembles + ensembleArtifacts;
        }
    }

    private final Db db;
    private final Clock clock;
    private final Policy policy;
    private ScheduledExecutorService executor;

    public ArtifactRetentionService(Db db, Clock clock, AppConfig cfg) {
        this(db, clock, Policy.from(cfg));
    }

    ArtifactRetentionService(Db db, Clock clock, Policy policy) {
        this.db = db;
        this.clock = clock;
        this.policy = policy;
    }

    public synchronized void start() {
        if (!policy.enabled() || executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "artifact-retention");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::safeRun, policy.initialDelaySeconds(),
                policy.intervalSeconds(), TimeUnit.SECONDS);
        log.info("Computed-artifact retention ready (stale Plans {}d, orphan ensembles {}d; user reports are permanent)",
                policy.stalePlanDays(), policy.orphanEnsembleDays());
    }

    private void safeRun() {
        try {
            CleanupResult result = runOnce();
            if (result.total() > 0) log.info("Reclaimed {} superseded computed records", result.total());
        } catch (RuntimeException e) {
            log.warn("Computed-artifact cleanup did not complete; the next run will retry");
            log.debug("Computed-artifact cleanup detail", e);
        }
    }

    public CleanupResult runOnce() {
        OffsetDateTime staleCutoff = cutoff(policy.stalePlanDays());
        OffsetDateTime orphanCutoff = cutoff(policy.orphanEnsembleDays());
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return db.tx(c -> cleanup(c, staleCutoff, orphanCutoff, now));
    }

    private OffsetDateTime cutoff(int days) {
        return clock.instant().minus(Duration.ofDays(days)).atOffset(ZoneOffset.UTC);
    }

    private static CleanupResult cleanup(Connection c, OffsetDateTime staleCutoff,
                                         OffsetDateTime orphanCutoff, OffsetDateTime now) throws SQLException {
        // Comparisons/runs must go before their candidate and ensemble parents. A selected decision
        // or a replay source protects the whole decision-bearing package even if its state is stale.
        int comparisons = Db.execOn(c, "DELETE FROM plan_outcome_comparison poc USING plans p "
                + "WHERE poc.plan_id=p.id AND p.status IN ('DRAFT','ACTIVE') "
                + "AND poc.state IN ('STALE','BLOCKED') AND poc.created_at < ? "
                + "AND NOT EXISTS (SELECT 1 FROM plan_decision pd WHERE pd.plan_id=poc.plan_id "
                + "AND (pd.ensemble_id=poc.ensemble_id OR EXISTS (SELECT 1 FROM plan_outcome_comparison_item i "
                + "WHERE i.comparison_id=poc.id AND i.candidate_id=pd.candidate_id))) "
                + "AND NOT EXISTS (SELECT 1 FROM sim_replay_source s WHERE s.ensemble_id=poc.ensemble_id)",
                staleCutoff);

        int outcomes = Db.execOn(c, "DELETE FROM plan_outcome_run por USING plans p "
                + "WHERE por.plan_id=p.id AND p.status IN ('DRAFT','ACTIVE') "
                + "AND por.state IN ('STALE','BLOCKED') AND por.created_at < ? "
                + "AND NOT EXISTS (SELECT 1 FROM plan_decision pd WHERE pd.plan_id=por.plan_id "
                + "AND (pd.candidate_id=por.candidate_id OR pd.ensemble_id=por.ensemble_id)) "
                + "AND NOT EXISTS (SELECT 1 FROM sim_replay_source s WHERE s.ensemble_id=por.ensemble_id)",
                staleCutoff);

        int backtests = Db.execOn(c, "DELETE FROM plan_backtest pb USING plans p "
                + "WHERE pb.plan_id=p.id AND p.status IN ('DRAFT','ACTIVE') "
                + "AND pb.state IN ('STALE','BLOCKED') AND pb.created_at < ? "
                + "AND NOT EXISTS (SELECT 1 FROM plan_decision pd WHERE pd.plan_id=pb.plan_id "
                + "AND pd.candidate_id=pb.candidate_id)", staleCutoff);

        int evidence = Db.execOn(c, "DELETE FROM plan_evidence pe USING plans p "
                + "WHERE pe.plan_id=p.id AND p.status IN ('DRAFT','ACTIVE') "
                + "AND pe.state IN ('STALE','BLOCKED') AND pe.created_at < ?", staleCutoff);

        int strategies = Db.execOn(c, "DELETE FROM plan_strategy_run psr USING plans p "
                + "WHERE psr.plan_id=p.id AND p.status IN ('DRAFT','ACTIVE') "
                + "AND psr.state IN ('STALE','BLOCKED') AND psr.created_at < ? "
                + "AND NOT EXISTS (SELECT 1 FROM plan_candidate pc WHERE pc.run_id=psr.id AND (pc.selected=1 "
                + "OR EXISTS (SELECT 1 FROM plan_decision pd WHERE pd.candidate_id=pc.id) "
                + "OR EXISTS (SELECT 1 FROM plan_outcome_run por WHERE por.candidate_id=pc.id) "
                + "OR EXISTS (SELECT 1 FROM plan_outcome_comparison_item i WHERE i.candidate_id=pc.id)))",
                staleCutoff);

        int ensembles = Db.execOn(c, "DELETE FROM plan_ensemble pe USING plans p "
                + "WHERE pe.plan_id=p.id AND p.status IN ('DRAFT','ACTIVE') "
                + "AND pe.state IN ('STALE','BLOCKED') AND pe.created_at < ? "
                + "AND NOT EXISTS (SELECT 1 FROM plan_decision pd WHERE pd.ensemble_id=pe.id) "
                + "AND NOT EXISTS (SELECT 1 FROM sim_replay_source s WHERE s.ensemble_id=pe.id) "
                + "AND NOT EXISTS (SELECT 1 FROM plan_outcome_run por WHERE por.ensemble_id=pe.id) "
                + "AND NOT EXISTS (SELECT 1 FROM plan_outcome_comparison poc WHERE poc.ensemble_id=pe.id)",
                staleCutoff);

        int artifacts = Db.execOn(c, "DELETE FROM ensemble_artifact ea WHERE ea.pinned=0 "
                + "AND ea.created_at < ? "
                + "AND NOT EXISTS (SELECT 1 FROM plan_ensemble pe WHERE pe.fingerprint=ea.fingerprint) "
                + "AND NOT EXISTS (SELECT 1 FROM research_ensemble_receipt rr "
                + "WHERE rr.fingerprint=ea.fingerprint AND rr.state='AVAILABLE' AND rr.expires_at>?) "
                + "AND NOT EXISTS (SELECT 1 FROM sim_replay_source s WHERE s.fingerprint=ea.fingerprint)",
                orphanCutoff, now);

        return new CleanupResult(evidence, strategies, outcomes, comparisons, backtests, ensembles,
                artifacts);
    }

    @Override public synchronized void close() {
        if (executor != null) executor.shutdownNow();
        executor = null;
    }
}
