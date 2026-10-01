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

/** Replica-safe retry for self-help group admissions queued in the join-request row. */
@Component
@Profile("!testing")
@RequiredArgsConstructor
@Slf4j
public class GroupChatAdmissionScheduler {

  private static final String TASK_NAME = "group-chat-admission";

  private final GroupChatAdmissionProcessor processor;
  private final ScheduledTaskClaimService claims;

  @Value("${group.chat.admission.claim-duration:PT2M}")
  private Duration claimDuration;

  @Scheduled(fixedDelayString = "${group.chat.admission.retry-delay-ms:60000}")
  public void retryPendingAdmissions() {
    var lease = claims.tryClaimLease(TASK_NAME, claimDuration);
    if (lease.isEmpty()) {
      return;
    }
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      for (var requestId : processor.pendingIds()) {
        if (!claims.runIfHeld(lease.get(), () -> processSafely(requestId))) {
          break;
        }
      }
    } finally {
      TenantContext.clear();
      try {
        claims.release(lease.get());
      } catch (RuntimeException exception) {
        log.warn(
            "Could not release group admission scheduler claim ({})",
            exception.getClass().getSimpleName());
      }
    }
  }

  private void processSafely(Long requestId) {
    try {
      processor.process(requestId);
    } catch (RuntimeException exception) {
      try {
        processor.recordFailure(requestId);
      } catch (RuntimeException retryFailure) {
        log.warn(
            "Could not record failed group admission {} ({})",
            requestId,
            retryFailure.getClass().getSimpleName());
      }
      log.warn(
          "Group chat admission {} could not be processed ({})",
          requestId,
          exception.getClass().getSimpleName());
    }
  }
}
