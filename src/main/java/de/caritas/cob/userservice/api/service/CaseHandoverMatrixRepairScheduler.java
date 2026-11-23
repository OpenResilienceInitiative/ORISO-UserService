package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Replica-safe worker for durable Case Handover Matrix compensation tasks. */
@Component
@Profile("!testing")
@RequiredArgsConstructor
@Slf4j
public class CaseHandoverMatrixRepairScheduler {

  static final String TASK_NAME = "case-handover-matrix-repair";

  private final @NonNull CaseHandoverMatrixRepairService repairService;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${case.handover.matrix-repair.claim-duration:PT2M}")
  private Duration claimDuration;

  @Scheduled(fixedDelayString = "${case.handover.matrix-repair.retry-delay-ms:60000}")
  public void retryPendingRepairs() {
    var lease = taskClaimService.tryClaimLease(TASK_NAME, claimDuration);
    if (lease.isEmpty()) {
      return;
    }
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      repairService.pendingTaskIds().forEach(this::processSafely);
    } finally {
      TenantContext.clear();
      try {
        taskClaimService.release(lease.get());
      } catch (RuntimeException exception) {
        log.warn(
            "Could not release Case Handover Matrix repair scheduler claim ({})",
            exception.getClass().getSimpleName());
      }
    }
  }

  private void processSafely(Long taskId) {
    try {
      repairService.process(taskId);
    } catch (RuntimeException exception) {
      log.warn(
          "Could not process Case Handover Matrix repair task {} ({})",
          taskId,
          exception.getClass().getSimpleName());
    }
  }
}
