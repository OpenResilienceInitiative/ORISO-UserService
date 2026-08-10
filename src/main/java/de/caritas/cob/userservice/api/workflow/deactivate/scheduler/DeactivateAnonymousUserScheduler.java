package de.caritas.cob.userservice.api.workflow.deactivate.scheduler;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.deactivate.service.DeactivateAnonymousUserService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeactivateAnonymousUserScheduler {

  private static final String TASK_NAME = "anonymous-user-deactivation";

  private final @NonNull DeactivateAnonymousUserService deactivateAnonymousUserService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${user.anonymous.deactivateworkflow.claim.duration:PT30M}")
  private Duration claimDuration;

  @Scheduled(cron = "${user.anonymous.deactivateworkflow.cron}")
  public void performDeactivationWorkflow() {
    if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    deactivateAnonymousUserService.deactivateStaleAnonymousUsers();
  }
}
