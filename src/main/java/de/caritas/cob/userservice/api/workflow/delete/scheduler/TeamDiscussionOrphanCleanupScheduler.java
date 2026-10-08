package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionRoomCleanupService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.TeamDiscussionOrphanCleanupService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Replica-safe scheduler for orphaned discussions and failed losing-room cleanup. */
@Component
@RequiredArgsConstructor
public class TeamDiscussionOrphanCleanupScheduler {

  static final String TASK_NAME = "team-discussion-orphan-cleanup";

  private final @NonNull TeamDiscussionOrphanCleanupService teamDiscussionOrphanCleanupService;
  private final @NonNull TeamDiscussionRoomCleanupService roomCleanupService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${team.discussion.orphan.cleanup.claim.duration:PT12H}")
  private Duration claimDuration;

  /** Purges orphaned discussions and retries durable losing-room cleanup handles. */
  @Scheduled(cron = "${team.discussion.orphan.cleanup.cron:0 30 3 * * ?}")
  public void purgeOrphanedDiscussions() {
    try {
      if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
        return;
      }
      tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
      this.teamDiscussionOrphanCleanupService.purgeOrphanedDiscussions();
      this.roomCleanupService.retryPendingCleanup();
    } finally {
      // Scheduler threads are pooled: leave no tenant context behind on any exit path, so neither
      // the technical context nor a leaked one from before reaches the next task.
      TenantContext.clear();
    }
  }
}
