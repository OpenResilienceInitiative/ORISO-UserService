package de.caritas.cob.userservice.api.workflow.calendarimport.scheduler;

import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.config.CalendarImportExecutorConfig;
import de.caritas.cob.userservice.api.service.calendar.CalendarImportRun;
import de.caritas.cob.userservice.api.service.calendar.CalendarSyncService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes the busy times imported from the counsellors' own calendars (ADR-032,
 * ORISO-UserService#1645). One replica per run: the others find the claim taken.
 *
 * <p>The scheduled method only claims the run and hands it to the import's own pool, so the shared
 * scheduler thread is free again at once. A run starts no import once its time budget is used up,
 * which stays well inside the claim; the calendars it did not reach are then the least recently
 * synced and go first in the next run.
 */
@Slf4j
@Component
public class CalendarImportScheduler {

  private static final String TASK_NAME = "calendar-busy-import";

  private final CalendarSyncService calendarSyncService;
  private final TenantContextProvider tenantContextProvider;
  private final ScheduledTaskClaimService taskClaimService;
  private final Executor executor;
  private final Clock clock;
  private final boolean enabled;
  private final Duration claimDuration;
  private final Duration budget;
  private final int threads;

  public CalendarImportScheduler(
      CalendarSyncService calendarSyncService,
      TenantContextProvider tenantContextProvider,
      ScheduledTaskClaimService taskClaimService,
      @Qualifier(CalendarImportExecutorConfig.EXECUTOR_BEAN) Executor executor,
      Clock clock,
      @Value("${calendar.import.enabled:true}") Boolean enabled,
      @Value("${calendar.import.claim.duration:PT10M}") Duration claimDuration,
      @Value("${calendar.import.budget:PT4M}") Duration budget,
      @Value("${calendar.import.threads:3}") int threads) {
    this.calendarSyncService = calendarSyncService;
    this.tenantContextProvider = tenantContextProvider;
    this.taskClaimService = taskClaimService;
    this.executor = executor;
    this.clock = clock;
    this.enabled = isTrue(enabled);
    this.claimDuration = claimDuration;
    this.budget = budget;
    this.threads = threads;
  }

  @Scheduled(cron = "${calendar.import.cron:0 */15 * * * ?}")
  public void importBusyTimes() {
    if (!enabled || !taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    var run = calendarSyncService.startImportRun(Instant.now(clock).plus(budget));
    try {
      for (int worker = 0; worker < threads; worker++) {
        executor.execute(() -> work(run));
      }
    } catch (RejectedExecutionException stillBusy) {
      log.warn("The calendar import pool is still busy; the next run tries again.");
    }
  }

  private void work(CalendarImportRun run) {
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    try {
      run.work();
    } catch (RuntimeException failure) {
      log.warn("A calendar import run failed: {}", failure.getClass().getSimpleName());
    } finally {
      TenantContext.clear();
    }
  }
}
