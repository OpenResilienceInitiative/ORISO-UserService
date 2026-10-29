package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteTemporaryAccountsService;
import de.caritas.cob.userservice.api.workflow.delete.service.DirectLinkTemporaryAccountCleanup;
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
  private static final String DIRECT_LINK_TASK_NAME = "direct-link-temporary-account-deletion";

  private final @NonNull DeleteTemporaryAccountsService deleteTemporaryAccountsService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;
  private final @NonNull DirectLinkTemporaryAccountCleanup directLinkCleanup;

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

  /**
   * Deletes direct-link temporary accounts whose own snapshotted lifetime is over (ADR-029, #1476).
   * Runs every few minutes, because a call account goes 15 minutes after hang-up by default.
   */
  @Scheduled(cron = "${user.temporary.directLink.deleteWorkflow.cron}")
  public void performDirectLinkDeletionWorkflow() {
    if (!enabled) {
      return;
    }
    var lease = taskClaimService.tryClaimLease(DIRECT_LINK_TASK_NAME, claimDuration);
    if (lease.isEmpty()) {
      return;
    }
    try {
      tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
      List<DeletionWorkflowError> workflowErrors = new ArrayList<>();
      // Session timestamps are written in UTC; compare on the same clock.
      var now = nowInUtc();
      for (String userId : directLinkCleanup.dueAccountIds(now)) {
        if (!taskClaimService.runIfHeld(
            lease.get(), () -> workflowErrors.addAll(deleteOne(userId, now)))) {
          break;
        }
      }
      deleteTemporaryAccountsService.notifyAbout(workflowErrors);
    } finally {
      TenantContext.clear();
      try {
        taskClaimService.release(lease.get());
      } catch (RuntimeException exception) {
        log.warn(
            "Could not release direct-link temporary account deletion claim ({})",
            exception.getClass().getSimpleName());
      }
    }
  }

  /** One failing account must not cost the rest of the batch its deletion or the error mail. */
  private List<DeletionWorkflowError> deleteOne(String userId, java.time.LocalDateTime now) {
    try {
      return directLinkCleanup.delete(userId, now);
    } catch (RuntimeException exception) {
      log.error(
          "Direct-link temporary account deletion failed ({})",
          exception.getClass().getSimpleName());
      return List.of(
          DeletionWorkflowError.builder()
              .deletionSourceType(
                  de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER)
              .deletionTargetType(
                  de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType.ALL)
              .identifier(userId)
              .reason("Unexpected error during temporary account deletion")
              .timestamp(nowInUtc())
              .build());
    }
  }
}
