package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteTemporaryAccountsService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes the accounts of people who joined a self-help group without an account (FE#1499). */
@Component
@RequiredArgsConstructor
@Slf4j
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
    if (!enabled) {
      return;
    }
    var lease = taskClaimService.tryClaimLease(TASK_NAME, claimDuration);
    if (lease.isEmpty()) {
      return;
    }
    try {
      tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
      List<DeletionWorkflowError> workflowErrors = new ArrayList<>();
      for (String userId : deleteTemporaryAccountsService.expiredAccountIds()) {
        // Each deletion runs under the locked lease, so no second replica deletes in parallel.
        if (!taskClaimService.runIfHeld(
            lease.get(),
            () -> workflowErrors.addAll(deleteTemporaryAccountsService.deleteIsolated(userId)))) {
          break;
        }
      }
      deleteTemporaryAccountsService.notifyAbout(workflowErrors);
    } finally {
      // The scheduler thread is pooled; the technical context must not reach the next task.
      TenantContext.clear();
      try {
        taskClaimService.release(lease.get());
      } catch (RuntimeException exception) {
        log.warn(
            "Could not release temporary account deletion claim ({})",
            exception.getClass().getSimpleName());
      }
    }
  }
}
