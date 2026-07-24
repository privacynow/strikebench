package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketHours;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.OwnerScope;

/** Once-per-completed-session daily-history maintenance. */
public final class DataSyncScheduler implements AutoCloseable, DataResetService.SchedulerControl {
    private static final Logger log = LoggerFactory.getLogger(DataSyncScheduler.class);
    private static final LocalTime CLOSE_GRACE = LocalTime.of(16, 20);

    private final AppConfig cfg;
    private final Clock clock;
    private final DataSyncState state;
    private final DataJobService jobs;
    private final UniverseService universe;
    private ScheduledExecutorService executor;

    public DataSyncScheduler(AppConfig cfg, Clock clock, DataSyncState state, DataJobService jobs) {
        this(cfg, clock, state, jobs, null);
    }

    public DataSyncScheduler(AppConfig cfg, Clock clock, DataSyncState state, DataJobService jobs,
                             UniverseService universe) {
        this.cfg = cfg;
        this.clock = clock;
        this.state = state;
        this.jobs = jobs;
        this.universe = universe;
    }

    public synchronized void start() {
        if (cfg.fixturesOnly() || executor != null) return;
        configureDefaultYahooSchedule();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "daily-history-sync"); t.setDaemon(true); return t;
        });
        // Give interactive boot work a full two-minute head start. Subsequent checks are cheap DB reads.
        executor.scheduleWithFixedDelay(this::safeTick, 120, 15 * 60, TimeUnit.SECONDS);
        log.info("Daily history maintenance ready (durable Yahoo universe sync: {}; runs once after a completed market session)",
                cfg.yahooHistorySyncEnabled() ? "enabled" : "disabled");
    }

    /**
     * The owner-authorized Yahoo lane is a product feed, not a browser-session preference. Keep a
     * separate system schedule over the canonical curated universe so history survives restarts and
     * fills only missing sessions. An explicit disable persists as a disabled system schedule.
     */
    void configureDefaultYahooSchedule() {
        if (cfg.fixturesOnly() || universe == null) return;
        boolean enabled = cfg.yahooEnabled()
                && cfg.yahooAutomationPermissionConfirmed()
                && cfg.yahooHistorySyncEnabled();
        state.saveSchedule(OwnerScope.SYSTEM, enabled, "yahoo", universe.warmSymbols(),
                cfg.yahooHistorySyncYears());
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
        for (DataSyncState.Schedule loaded : state.enabledSchedules()) {
            DataSyncState.Schedule schedule = loaded;
            if (schedule.covers(completed)) continue;
            if (schedule.symbols().isEmpty()) continue;

            // Reconcile the prior attempt before considering another request. A job is coverage-
            // complete only when every exact scheduled symbol finished, for the persisted hash and
            // completed-session date carried by that job. DONE with PARTIAL/SKIPPED items is not done.
            if (schedule.lastJobId() != null) {
                DataJobService.JobView view = jobs.get(schedule.lastJobId());
                if (view.job() != null && isActive(view.job().status())) return;
                String attemptedHash = textParam(view, "coverageHash");
                LocalDate attemptedSession = dateParam(view, "completedSession");
                boolean currentContract = Objects.equals(schedule.coverageHash(), attemptedHash);
                if (currentContract && attemptedSession != null && fullyCovered(view, schedule)) {
                    state.markScheduleComplete(schedule.userId(), attemptedSession, "COMPLETE",
                            view.job().id(), attemptedHash);
                    schedule = state.schedule(schedule.userId());
                    if (schedule.covers(completed)) continue;
                    // A slow job may finish after another session closes. Its exact date is now
                    // durable; continue below and queue only the newly missing session.
                } else if (currentContract && view.job() != null) {
                    state.markScheduleAttempt(schedule.userId(), retryStatus(view), view.job().id());
                    if (!jobs.retryReady(view.job().id(), retryCooldown(schedule))) continue;
                    // UnderlyingBackfill replans against storage, so this full contract retry makes
                    // provider requests only for ranges still missing after the partial attempt.
                }
                // A different/missing hash is an obsolete attempt after a configuration expansion;
                // it must not delay the current same-day contract.
            }

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("source", schedule.source());
            params.put("symbols", schedule.symbols());
            params.put("from", completed.minusYears(schedule.years()).toString());
            params.put("to", completed.toString());
            params.put("completedSession", completed.toString());
            params.put("coverageHash", schedule.coverageHash());
            try {
                var job = jobs.start("sync_underlying", params, schedule.userId());
                state.markScheduleAttempt(schedule.userId(), "QUEUED", job.id());
                return; // serialize schedules; the next owner is considered after this job finishes
            } catch (RuntimeException e) {
                state.markScheduleAttempt(schedule.userId(), "FAILED_TO_QUEUE", null);
                throw e;
            }
        }
    }

    Duration retryCooldown(DataSyncState.Schedule schedule) {
        int minutes = "yahoo".equalsIgnoreCase(schedule.source())
                ? cfg.yahooCooldownMinutes() : 15;
        return Duration.ofMinutes(Math.max(5, minutes));
    }

    private static boolean isActive(String status) {
        return "QUEUED".equals(status) || "RUNNING".equals(status);
    }

    private static boolean fullyCovered(DataJobService.JobView view, DataSyncState.Schedule schedule) {
        if (view.job() == null || !"DONE".equals(view.job().status())) return false;
        if (view.job().total() != schedule.symbols().size()
                || view.items().size() != schedule.symbols().size()) return false;
        for (int i = 0; i < view.items().size(); i++) {
            DataJobService.DataJobItem item = view.items().get(i);
            if (!schedule.symbols().get(i).equals(item.label()) || !"DONE".equals(item.status())) return false;
        }
        return true;
    }

    private static String retryStatus(DataJobService.JobView view) {
        if (view.job() == null) return "RETRY_WAIT_MISSING_JOB";
        boolean incomplete = view.items().stream().anyMatch(i -> !"DONE".equals(i.status()));
        return incomplete ? "RETRY_WAIT_PARTIAL" : "RETRY_WAIT_" + view.job().status();
    }

    private static String textParam(DataJobService.JobView view, String key) {
        if (view == null || view.paramsJson() == null) return null;
        var node = Json.parse(view.paramsJson()).path(key);
        return node.isTextual() ? node.asText() : null;
    }

    private static LocalDate dateParam(DataJobService.JobView view, String key) {
        String raw = textParam(view, key);
        try { return raw == null ? null : LocalDate.parse(raw); }
        catch (RuntimeException ignored) { return null; }
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

    /** Stop scheduler ticks during a destructive data reset and report whether it was mounted. */
    public synchronized boolean pauseForReset() {
        boolean running = executor != null;
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Daily-history maintenance did not stop safely; the reset was not started.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Daily-history maintenance stop was interrupted.", e);
            }
        }
        executor = null;
        return running;
    }

    /** Recreate the durable system schedule deleted by EVERYTHING, then remount if needed. */
    public synchronized void resumeAfterReset(boolean wasRunning) {
        configureDefaultYahooSchedule();
        if (wasRunning) start();
    }

    @Override public synchronized void close() {
        pauseForReset();
    }
}
