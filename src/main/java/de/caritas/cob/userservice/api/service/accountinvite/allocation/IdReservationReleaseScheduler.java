package de.caritas.cob.userservice.api.service.accountinvite.allocation;

import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Replica-safe retry worker for reservation releases left pending by failed direct sends. */
@Component
@Profile("!testing")
@RequiredArgsConstructor
@Slf4j
public class IdReservationReleaseScheduler {

  static final String TASK_NAME = "account-invite-reservation-release";

  private final @NonNull IdReservationReleaseProcessor processor;
  private final @NonNull ScheduledTaskClaimService taskClaimService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull IdentityClientConfig identityClientConfig;
  private final @NonNull IdentityAuthentication identityAuthentication;

  @Value("${account-invite.reservation-release.claim-duration:PT5M}")
  private Duration claimDuration;

  @Scheduled(fixedDelayString = "${account-invite.reservation-release.retry-delay-ms:60000}")
  public void retryPendingReleases() {
    ScheduledTaskClaimService.ClaimLease lease = null;
    try {
      var acquiredLease = taskClaimService.tryClaimLease(TASK_NAME, claimDuration);
      if (acquiredLease.isEmpty()) {
        return;
      }
      lease = acquiredLease.get();
      tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
      var technicalUser = identityClientConfig.getTechnicalUser();
      var login =
          identityAuthentication.login(technicalUser.getUsername(), technicalUser.getPassword());
      TechnicalAccessTokenContext.set(login.accessToken());
      for (Long taskId : processor.pendingTaskIds()) {
        try {
          processor.process(taskId);
        } catch (RuntimeException exception) {
          log.warn("Could not process reservation release task {}", taskId, exception);
        }
      }
    } catch (RuntimeException exception) {
      log.warn("Could not process pending account-invite reservation releases", exception);
    } finally {
      TechnicalAccessTokenContext.clear();
      TenantContext.clear();
      if (lease != null) {
        try {
          taskClaimService.release(lease);
        } catch (RuntimeException exception) {
          log.warn("Could not release account-invite reservation scheduler claim", exception);
        }
      }
    }
  }
}
