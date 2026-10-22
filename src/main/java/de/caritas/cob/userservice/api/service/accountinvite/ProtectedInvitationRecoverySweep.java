package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationCancellation;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.ProtectedAdminOnboardingService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Clock;

/** Bounded replica-neutral retry of original protected ownership; no public cleanup endpoint. */
public final class ProtectedInvitationRecoverySweep {
  public ProtectedInvitationRecoverySweep(
      TaskActorConfiguration actors,
      ScheduledTaskClaimService claims,
      JdbcProtectedInvitationCancellation cancellation,
      ProtectedAdminOnboardingService coordinator,
      AccountInviteService invites,
      Clock clock) {}

  public void retryPending() {}
}
