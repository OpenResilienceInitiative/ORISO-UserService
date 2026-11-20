package de.caritas.cob.userservice.api.workflow.statisticsretention.scheduler;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.statisticsretention.service.StatisticsRetentionService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduler for the retention of message counts and the statistics settings change log. */
@Component
@RequiredArgsConstructor
public class StatisticsRetentionScheduler {

  private static final String TASK_NAME = "statistics-retention";

  private final @NonNull StatisticsRetentionService statisticsRetentionService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${statistics.retention.claim.duration:PT12H}")
  private Duration claimDuration;

  /** Entry method to purge statistics data that has outlived its retention period. */
  @Scheduled(cron = "${statistics.retention.cron:0 30 3 * * ?}")
  public void purgeExpiredStatistics() {
    if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    this.statisticsRetentionService.purgeExpiredStatistics();
  }
}
