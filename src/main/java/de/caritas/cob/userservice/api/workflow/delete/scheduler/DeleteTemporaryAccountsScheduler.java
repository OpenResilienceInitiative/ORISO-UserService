package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteTemporaryAccountsService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes the accounts of people who joined a self-help group without an account (FE#1499). */
@Component
@RequiredArgsConstructor
public class DeleteTemporaryAccountsScheduler {

  private static final String TASK_NAME = "temporary-account-deletion";

  private final @NonNull DeleteTemporaryAccountsService deleteTemporaryAccountsService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${user.temporary.deleteWorkflow.enabled}")
  private boolean enabled;

  @Value("${user.temporary.deleteWorkflow.claim.duration:PT30M}")
  private Duration claimDuration;

  /** Entry method to perform the deletion workflow. */
  @Scheduled(cron = "${user.temporary.deleteWorkflow.cron}")
  public void performDeletionWorkflow() {
    if (!enabled || !taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    deleteTemporaryAccountsService.deleteExpiredTemporaryAccounts();
  }
}
