package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Replica-safe retry of Matrix cleanup after a rolled-back group chat. */
@Component
@Profile("!testing")
@RequiredArgsConstructor
@Slf4j
public class GroupChatMatrixCleanupScheduler {

  private static final String TASK_NAME = "group-chat-matrix-cleanup";

  private final GroupChatMatrixCleanupService repair;
  private final ScheduledTaskClaimService claims;

  @Value("${group.chat.matrix-cleanup.claim-duration:PT2M}")
  private Duration claimDuration;

  @Scheduled(fixedDelayString = "${group.chat.matrix-cleanup.retry-delay-ms:60000}")
  public void retryRepairs() {
    var lease = claims.tryClaimLease(TASK_NAME, claimDuration);
    if (lease.isEmpty()) {
      return;
    }
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      for (var taskId : repair.readyIds()) {
        if (!claims.runIfHeld(lease.get(), () -> reconcileSafely(taskId))) {
          break;
        }
      }
    } finally {
      TenantContext.clear();
      try {
        claims.release(lease.get());
      } catch (RuntimeException exception) {
        log.warn(
            "Could not release group chat Matrix repair claim ({})",
            exception.getClass().getSimpleName());
      }
    }
  }

  private void reconcileSafely(Long taskId) {
    try {
      repair.retry(taskId);
    } catch (RuntimeException exception) {
      log.warn(
          "Group chat Matrix repair {} failed ({})", taskId, exception.getClass().getSimpleName());
    }
  }
}
