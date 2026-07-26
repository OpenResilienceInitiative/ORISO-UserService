package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteUsersRegisteredOnlyService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduler for deletion of only registered users without sessions. */
@Component
@RequiredArgsConstructor
public class DeleteUsersRegisteredOnlyScheduler {

  private static final String TASK_NAME = "registered-only-user-deletion";

  private final @NonNull DeleteUsersRegisteredOnlyService deleteUsersRegisteredOnlyService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${user.registeredonly.deleteWorkflow.enabled}")
  private boolean userRegisteredOnlyDeleteWorkflowEnabled;

  @Value("${user.registeredonly.deleteWorkflow.afterSessionPurge.enabled}")
  private boolean userRegisteredOnlyDeleteWorkflowAfterSessionPurgeEnabled;

  @Value("${user.registeredonly.deleteWorkflow.claim.duration:PT12H}")
  private Duration claimDuration;

  /** Entry method to perform deletion workflow. */
  @Scheduled(cron = "${user.registeredonly.deleteWorkflow.cron}")
  public void performDeletionWorkflow() {
    if (!userRegisteredOnlyDeleteWorkflowEnabled
        && !userRegisteredOnlyDeleteWorkflowAfterSessionPurgeEnabled) {
      return;
    }
    if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    if (userRegisteredOnlyDeleteWorkflowEnabled) {
      deleteUsersRegisteredOnlyService.deleteUserAccountsTimeSensitive();
    }

    if (userRegisteredOnlyDeleteWorkflowAfterSessionPurgeEnabled) {
      deleteUsersRegisteredOnlyService.deleteUserAccountsTimeInsensitive();
    }
  }
}
