package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteInactiveSessionsAndUserService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduler for deletion of inactive sessions and user. */
@Component
@RequiredArgsConstructor
public class DeleteInactiveSessionsAndUserScheduler {

  private static final String TASK_NAME = "inactive-session-deletion";

  private final @NonNull DeleteInactiveSessionsAndUserService deleteInactiveSessionsAndUserService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${session.inactive.deleteWorkflow.enabled}")
  private boolean sessionInactiveDeleteWorkflowEnabled;

  @Value("${session.inactive.deleteWorkflow.claim.duration:PT12H}")
  private Duration claimDuration;

  /** Entry method to perform deletion workflow. */
  @Scheduled(cron = "${session.inactive.deleteWorkflow.cron}")
  public void performDeletionWorkflow() {
    if (!sessionInactiveDeleteWorkflowEnabled) {
      return;
    }
    if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    this.deleteInactiveSessionsAndUserService.deleteInactiveSessionsAndUsers();
  }
}
