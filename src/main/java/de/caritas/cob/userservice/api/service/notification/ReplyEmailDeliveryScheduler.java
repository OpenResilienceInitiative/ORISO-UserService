package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers durable reply-email claims without holding a Matrix sync thread during SMTP. */
@Component
@Profile("!testing")
@RequiredArgsConstructor
@Slf4j
public class ReplyEmailDeliveryScheduler {
  private static final String TASK_NAME = "reply-email-delivery";
  private final @NonNull ReplyEmailDeliveryWriter writer;
  private final @NonNull AdviceSeekerReplyEmailService service;
  private final @NonNull ScheduledTaskClaimService claims;
  private long lastObservedUncertainCount = -1;

  @Scheduled(fixedDelayString = "${notification.reply-email.retry-delay-ms:60000}")
  public void deliverPending() {
    var lease = claims.tryClaimLease(TASK_NAME, Duration.ofMinutes(2));
    if (lease.isEmpty()) {
      return;
    }
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      int uncertain = writer.markStaleSendingUncertain(Duration.ofMinutes(10));
      if (uncertain > 0) {
        log.error("{} reply-email SMTP outcomes require operator reconciliation", uncertain);
      }
      long outstanding = writer.uncertainCount();
      if (outstanding != lastObservedUncertainCount) {
        if (outstanding > 0) {
          log.error(
              "{} reply-email deliveries remain UNCERTAIN; reconcile before retry", outstanding);
        } else if (lastObservedUncertainCount > 0) {
          log.info("All uncertain reply-email deliveries have been reconciled");
        }
        lastObservedUncertainCount = outstanding;
      }
      writer.pendingIds().forEach(this::deliverSafely);
    } finally {
      TenantContext.clear();
      try {
        claims.release(lease.get());
      } catch (RuntimeException failure) {
        log.warn(
            "Could not release reply-email delivery claim ({})",
            failure.getClass().getSimpleName());
      }
    }
  }

  private void deliverSafely(long id) {
    try {
      service.deliverPending(id);
    } catch (RuntimeException failure) {
      log.error(
          "Reply-email delivery {} needs investigation ({})",
          id,
          failure.getClass().getSimpleName());
    }
  }
}
