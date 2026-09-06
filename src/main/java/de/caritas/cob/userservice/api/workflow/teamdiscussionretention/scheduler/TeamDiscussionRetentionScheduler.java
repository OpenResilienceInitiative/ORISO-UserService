package de.caritas.cob.userservice.api.workflow.teamdiscussionretention.scheduler;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.teamdiscussionretention.service.TeamDiscussionRetentionService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for the team discussion archive purge (KDG epic #1010, #1116).
 *
 * <p>Every replica runs the same cron; the claim makes sure exactly one of them purges. The
 * technical tenant context lifts the Hibernate tenant filter so a single run covers all tenants.
 */
@Component
@RequiredArgsConstructor
public class TeamDiscussionRetentionScheduler {

  static final String TASK_NAME = "team-discussion-archive-retention";

  private final @NonNull TeamDiscussionRetentionService teamDiscussionRetentionService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${team-discussion.archive.retention.claim.duration:PT12H}")
  private Duration claimDuration;

  /** Entry method to purge discussions that have outlived their retention period. */
  @Scheduled(cron = "${team-discussion.archive.retention.cron:0 45 3 * * ?}")
  public void purgeExpiredDiscussions() {
    if (!teamDiscussionRetentionService.isEnabled()) {
      return;
    }
    if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    teamDiscussionRetentionService.purgeExpiredDiscussions();
  }
}
