package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import de.caritas.cob.userservice.api.admin.service.IdentityReactivationRepairService;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteUserAccountService;
import de.caritas.cob.userservice.api.workflow.delete.service.UserHardDeleteClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduler for deletion of users with delete flag. */
@Component
@RequiredArgsConstructor
public class DeleteUserAccountScheduler {

  private static final String TASK_NAME = "account-deletion";

  private final @NonNull DeleteUserAccountService deleteUserAccountService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;
  private final @NonNull UserHardDeleteClaimService userHardDeleteClaimService;
  private final @NonNull IdentityReactivationRepairService identityReactivationRepairService;

  @Value("${user.account.deleteworkflow.claim.duration:PT12H}")
  private Duration claimDuration;

  /** Entry method to perform deletion workflow. */
  @Scheduled(cron = "${user.account.deleteworkflow.cron}")
  public void performDeletionWorkflow() {
    if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    identityReactivationRepairService.retryOutstandingRepairs();
    userHardDeleteClaimService.releaseInterruptedClaims();
    this.deleteUserAccountService.deleteUserAccounts();
  }
}
