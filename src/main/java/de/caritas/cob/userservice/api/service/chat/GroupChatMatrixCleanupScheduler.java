package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService.ClaimLease;
import java.time.Duration;
import java.util.function.Consumer;
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
  private final GroupRosterRemovalRecovery rosterRecovery;
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
      reconcilePending(lease.get());
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

  private void reconcilePending(ClaimLease lease) {
    var roster = rosterRecovery.readyIds().iterator();
    var cleanup = repair.readyIds().iterator();
    while (roster.hasNext() || cleanup.hasNext()) {
      if (roster.hasNext()
          && !reconcileIfHeld(lease, "roster", roster.next(), rosterRecovery::retry)) {
        return;
      }
      if (cleanup.hasNext() && !reconcileIfHeld(lease, "cleanup", cleanup.next(), repair::retry)) {
        return;
      }
    }
  }

  private boolean reconcileIfHeld(
      ClaimLease lease, String kind, Long taskId, Consumer<Long> retry) {
    return claims.runIfHeld(lease, () -> reconcileSafely(kind, taskId, retry));
  }

  private void reconcileSafely(String kind, Long taskId, Consumer<Long> retry) {
    try {
      retry.accept(taskId);
    } catch (RuntimeException exception) {
      log.warn(
          "Group chat Matrix {} repair {} failed ({})",
          kind,
          taskId,
          exception.getClass().getSimpleName());
    }
  }
}
