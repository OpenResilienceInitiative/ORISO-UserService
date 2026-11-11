package de.caritas.cob.userservice.api.workflow.callobservationretention.scheduler;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.callobservationretention.service.CallObservationRetentionService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduler for the call-observation retention purge (#1624). */
@Component
@RequiredArgsConstructor
public class CallObservationRetentionScheduler {

  private static final String TASK_NAME = "matrix-call-observation-retention";

  private final @NonNull CallObservationRetentionService retentionService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${matrix.call.observation.retention.claim.duration:PT12H}")
  private Duration claimDuration;

  /** Entry method: purges observations that have outlived their retention period. */
  @Scheduled(cron = "${matrix.call.observation.retention.cron:0 30 3 * * ?}")
  public void purgeExpiredObservations() {
    if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    retentionService.purgeExpired();
  }
}
