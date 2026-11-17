package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import de.caritas.cob.userservice.api.service.accountinvite.DeletedUnitInviteRevocation;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Completes a failed revocation of a deleted Träger's or Beratungsstelle's invitations (review of
 * ORISO-UserService#1675): a Träger report has no retry of its own, and the agency pull can be
 * switched off. Database only; one replica at a time through the scheduled-task claim.
 */
@Component
@Profile("!testing")
@RequiredArgsConstructor
@Slf4j
public class DeletedUnitInviteRetryScheduler {

  static final String TASK_NAME = "deleted-unit-invite-retry";

  private final @NonNull DeletedUnitInviteRevocation revocation;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${deletion.invite-revocation.retry-claim:PT10M}")
  private Duration claimDuration;

  @Scheduled(
      initialDelayString = "${deletion.invite-revocation.retry-initial-delay-ms:180000}",
      fixedDelayString = "${deletion.invite-revocation.retry-delay-ms:900000}")
  public void retryUnfinishedRevocations() {
    if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    try {
      tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
      int completed = revocation.retryUnfinished();
      if (completed > 0) {
        log.info("Completed the invitation revocation of {} deleted units", completed);
      }
    } catch (RuntimeException failure) {
      log.warn(
          "Retry of invitation revocations failed; next run retries ({})",
          failure.getClass().getSimpleName());
    }
  }
}
