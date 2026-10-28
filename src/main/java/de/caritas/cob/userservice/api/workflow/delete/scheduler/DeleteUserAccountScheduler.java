package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import de.caritas.cob.userservice.api.admin.service.IdentityReactivationRepairService;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteUserAccountService;
import de.caritas.cob.userservice.api.workflow.delete.service.ScheduledAskerMaintenance;
import de.caritas.cob.userservice.api.workflow.delete.service.ScheduledConsultantMaintenance;
import de.caritas.cob.userservice.api.workflow.delete.service.UserHardDeleteClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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

  @Autowired(required = false)
  private ScheduledAskerMaintenance scopedMaintenance;

  @Autowired(required = false)
  private ScheduledConsultantMaintenance scopedConsultants;

  @Autowired(required = false)
  private de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ScopedAskerReactivation
      scopedReactivation;

  @Value("${TASK_IDENTITY_MODEL:LEGACY}")
  private String taskIdentityModel = "LEGACY";

  @Value("${user.account.deleteworkflow.claim.duration:PT12H}")
  private Duration claimDuration;

  @PostConstruct
  public void verifyScopedMaintenanceAvailability() {
    if ("FIVE_ACTORS".equals(taskIdentityModel)
        && (scopedMaintenance == null || scopedConsultants == null || scopedReactivation == null)) {
      throw new IllegalStateException("Five-actor deletion requires the complete scoped scheduler");
    }
  }

  /** Local secret retention has its own lease and never enters account deletion or native HTTP. */
  @Scheduled(fixedDelay = 300000L, initialDelay = 300000L)
  public void performReactivationSecretErasure() {
    if (!"FIVE_ACTORS".equals(taskIdentityModel)) return;
    if (scopedReactivation == null)
      throw new IllegalStateException("Five-actor secret retention requires its scoped owner");
    taskClaimService
        .tryClaimLease(
            de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ScopedAskerReactivation
                .SECRET_ERASURE_TASK,
            Duration.ofMinutes(5))
        .ifPresent(scopedReactivation::eraseExpiredScopedSecrets);
    // Keep the row and cursor; replicas must wait for the original lease's natural expiry.
  }

  /** Entry method to perform deletion workflow. */
  @Scheduled(cron = "${user.account.deleteworkflow.cron}")
  public void performDeletionWorkflow() {
    if (scopedMaintenance != null) {
      scopedMaintenance.performDeletionWorkflow();
      if (scopedConsultants != null) {
        scopedConsultants.performDeletionWorkflow();
      }
      return;
    }
    if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    identityReactivationRepairService.retryOutstandingRepairs();
    userHardDeleteClaimService.releaseInterruptedClaims();
    this.deleteUserAccountService.deleteUserAccounts();
  }
}
