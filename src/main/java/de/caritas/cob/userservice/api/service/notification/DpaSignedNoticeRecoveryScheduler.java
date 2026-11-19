package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reopens only verified administrator notices that have not begun mail handoff. */
@Component
@Profile("!testing")
@RequiredArgsConstructor
@Slf4j
public class DpaSignedNoticeRecoveryScheduler {
  private static final String TASK_NAME = "dpa-signed-notice-admin-recovery";
  private final DpaSignedNoticeService notices;
  private final ScheduledTaskClaimService claims;
  private final AtomicBoolean queued = new AtomicBoolean();

  @Scheduled(fixedDelayString = "${dpa.signed-notice.recovery.delay-ms:60000}")
  public void retryPending() {
    if (!queued.compareAndSet(false, true)) {
      return;
    }
    try {
      // Reuse the existing bounded executor; the scheduler thread never waits for mail or HTTP.
      notices.submitAdminRecovery(this::runRecovery);
    } catch (RuntimeException failure) {
      queued.set(false);
      log.warn(
          "Could not queue DPA administrator notice recovery ({})",
          failure.getClass().getSimpleName());
    }
  }

  private void runRecovery() {
    try {
      TenantContext.runIn(
          TenantContext.TECHNICAL_TENANT_ID,
          () ->
              claims
                  .tryClaimLease(TASK_NAME, Duration.ofMinutes(3))
                  .ifPresent(
                      lease -> {
                        try {
                          notices.retryPendingAdminNotices();
                        } finally {
                          claims.release(lease);
                        }
                      }));
    } catch (RuntimeException failure) {
      log.warn(
          "DPA administrator notice recovery deferred ({})", failure.getClass().getSimpleName());
    } finally {
      queued.set(false);
    }
  }
}
