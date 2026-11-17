package de.caritas.cob.userservice.api.workflow.inviteretention.scheduler;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.inviteretention.service.InviteRetentionService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly purge of settled invitations past their retention period (ORISO-Admin#1522). */
@Component
@RequiredArgsConstructor
public class InviteRetentionScheduler {

  static final String TASK_NAME = "account-invite-retention";

  private final @NonNull InviteRetentionService inviteRetentionService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${account-invite.retention.claim.duration:PT12H}")
  private Duration claimDuration;

  @Scheduled(cron = "${account-invite.retention.cron:0 0 4 * * ?}")
  public void purgeSettledInvites() {
    if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    inviteRetentionService.purge();
  }
}
