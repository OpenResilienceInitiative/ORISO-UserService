package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteUserAnonymousService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduler for deletion of anonymous users. */
@Component
@RequiredArgsConstructor
public class DeleteUserAnonymousScheduler {

  private static final String TASK_NAME = "anonymous-user-deletion";

  private final @NonNull DeleteUserAnonymousService deleteUserAnonymousService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${user.anonymous.deleteworkflow.claim.duration:PT30M}")
  private Duration claimDuration;

  /** Entry method to perform deletion workflow. */
  @Scheduled(cron = "${user.anonymous.deleteworkflow.cron}")
  public void performDeletionWorkflow() {
    if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    deleteUserAnonymousService.deleteInactiveAnonymousUsers();
  }
}
