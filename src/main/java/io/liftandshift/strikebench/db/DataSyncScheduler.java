package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketHours;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Opt-in, once-per-completed-session daily-history maintenance. */
public final class DataSyncScheduler implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DataSyncScheduler.class);
    private static final LocalTime CLOSE_GRACE = LocalTime.of(16, 20);

    private final AppConfig cfg;
    private final Clock clock;
    private final DataSyncState state;
    private final DataJobService jobs;
    private ScheduledExecutorService executor;

    public DataSyncScheduler(AppConfig cfg, Clock clock, DataSyncState state, DataJobService jobs) {
        this.cfg = cfg;
        this.clock = clock;
        this.state = state;
        this.jobs = jobs;
    }

    public synchronized void start() {
        if (cfg.fixturesOnly() || executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "daily-history-sync"); t.setDaemon(true); return t;
        });
        // Give interactive boot work a full two-minute head start. Subsequent checks are cheap DB reads.
        executor.scheduleWithFixedDelay(this::safeTick, 120, 15 * 60, TimeUnit.SECONDS);
        log.info("Daily history maintenance ready (opt-in; runs once after a completed market session)");
    }

    private void safeTick() {
        try { tick(); }
        catch (RuntimeException e) {
            log.warn("Scheduled daily-history maintenance did not start; it will check again later");
            log.debug("Daily-history maintenance detail", e);
        }
    }

    /** Package-visible for deterministic scheduler tests. */
    void tick() {
        LocalDate completed = latestCompletedSession(clock);
        if (completed == null || jobs.hasActive("sync_underlying")) return;
        for (DataSyncState.Schedule schedule : state.enabledSchedules()) {
            if (schedule.lastRunDate() != null && !schedule.lastRunDate().isBefore(completed)) {
                if (schedule.lastJobId() != null) {
                    var job = jobs.get(schedule.lastJobId()).job();
                    if (job != null && !job.status().equals(schedule.lastStatus())) {
                        state.markScheduleRun(schedule.ownerKey(), completed, job.status(), job.id());
                    }
                }
                continue;
            }
            if (schedule.symbols().isEmpty()) continue;
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("source", schedule.source());
            params.put("symbols", schedule.symbols());
            params.put("from", completed.minusYears(schedule.years()).toString());
            params.put("to", completed.toString());
            try {
                var job = jobs.start("sync_underlying", params, DataSyncState.ownerId(schedule.ownerKey()));
                state.markScheduleRun(schedule.ownerKey(), completed, "QUEUED", job.id());
                return; // serialize schedules; the next owner is considered after this job finishes
            } catch (RuntimeException e) {
                state.markScheduleRun(schedule.ownerKey(), schedule.lastRunDate(), "FAILED_TO_QUEUE", null);
                throw e;
            }
        }
    }

    public static LocalDate latestCompletedSession(Clock clock) {
        ZonedDateTime et = clock.instant().atZone(MarketHours.EASTERN);
        LocalDate candidate = et.toLocalDate();
        if (!MarketHours.isTradingDay(candidate) || et.toLocalTime().isBefore(CLOSE_GRACE)) {
            candidate = candidate.minusDays(1);
        }
        while (!MarketHours.isTradingDay(candidate)) candidate = candidate.minusDays(1);
        return candidate;
    }

    @Override public synchronized void close() {
        if (executor != null) executor.shutdownNow();
        executor = null;
    }
}
