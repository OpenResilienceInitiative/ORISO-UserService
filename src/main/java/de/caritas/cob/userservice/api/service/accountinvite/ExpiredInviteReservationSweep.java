package de.caritas.cob.userservice.api.service.accountinvite;

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

/**
 * Replica-safe worker that expires elapsed invites holding a reserved Träger / agency number and
 * gives the number back (ORISO-Admin#1026). Invites expire by date, not by an event, so without
 * this sweep an expired founding invite would keep its number reserved forever.
 */
@Component
@Profile("!testing")
@RequiredArgsConstructor
@Slf4j
public class ExpiredInviteReservationSweep {

  static final String TASK_NAME = "account-invite-expiry-number-release";

  private final @NonNull AccountInviteService accountInviteService;
  private final @NonNull ScheduledTaskClaimService taskClaimService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull IdentityClientConfig identityClientConfig;
  private final @NonNull IdentityAuthentication identityAuthentication;

  @Value("${account-invite.expiry-sweep.claim-duration:PT5M}")
  private Duration claimDuration;

  @Scheduled(fixedDelayString = "${account-invite.expiry-sweep.delay-ms:300000}")
  public void expireElapsedInvites() {
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
      int expired = accountInviteService.expireElapsedInvites();
      if (expired > 0) {
        log.info("Expired {} elapsed invites that held a reserved number", expired);
      }
    } catch (RuntimeException exception) {
      log.warn("Could not expire elapsed invites holding a reserved number", exception);
    } finally {
      TechnicalAccessTokenContext.clear();
      TenantContext.clear();
      if (lease != null) {
        try {
          taskClaimService.release(lease);
        } catch (RuntimeException exception) {
          log.warn("Could not release the invite expiry sweep claim", exception);
        }
      }
    }
  }
}
