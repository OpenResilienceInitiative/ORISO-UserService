package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationCancellation;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.ProtectedAdminOnboardingService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded replica-neutral retry of original protected ownership; no public cleanup endpoint. */
public final class ProtectedInvitationRecoverySweep {
  private static final String TASK = "protected-invitation-terminal-recovery";
  private static final int PAGE = 100;
  private static final Duration LEASE = Duration.ofMinutes(5);
  private static final Logger LOG = LoggerFactory.getLogger(ProtectedInvitationRecoverySweep.class);
  private final TaskActorConfiguration actors;
  private final ScheduledTaskClaimService claims;
  private final JdbcProtectedInvitationCancellation cancellation;
  private final ProtectedAdminOnboardingService coordinator;
  private final AccountInviteService invites;
  private final Clock clock;

  public ProtectedInvitationRecoverySweep(
      TaskActorConfiguration actors,
      ScheduledTaskClaimService claims,
      JdbcProtectedInvitationCancellation cancellation,
      ProtectedAdminOnboardingService coordinator,
      AccountInviteService invites,
      Clock clock) {
    this.actors = Objects.requireNonNull(actors);
    this.claims = Objects.requireNonNull(claims);
    this.cancellation = Objects.requireNonNull(cancellation);
    this.coordinator = Objects.requireNonNull(coordinator);
    this.invites = Objects.requireNonNull(invites);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Configured worker boundary; runtime identity context remains closed until its tracer passes. */
  public ProtectedInvitationRecoverySweep(
      TaskActorConfiguration actors,
      ScheduledTaskClaimService claims,
      JdbcProtectedInvitationCancellation cancellation,
      ProtectedAdminOnboardingService coordinator,
      AccountInviteService invites,
      Clock clock,
      de.caritas.cob.userservice.api.port.out.IdentityClientConfig identity,
      de.caritas.cob.userservice.api.port.out.IdentityAuthentication authentication,
      de.caritas.cob.userservice.api.tenant.TenantContextProvider tenantContext) {
    this(actors, claims, cancellation, coordinator, invites, clock);
    Objects.requireNonNull(identity);
    Objects.requireNonNull(authentication);
    Objects.requireNonNull(tenantContext);
  }

  public void retryPending() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()
        || !TransactionSynchronizationManager.getResourceMap().isEmpty())
      throw new IllegalStateException("Protected recovery worker requires completed local SQL");
    if (!actors.isEnabled()) return;
    var acquired = claims.tryClaimLease(TASK, LEASE);
    if (acquired.isEmpty()) return;
    var lease = acquired.get();
    try {
      var ids = new LinkedHashSet<>(cancellation.recoveryInviteIds(PAGE));
      if (ids.size() > PAGE)
        throw new IllegalStateException("Protected recovery page exceeds limit");
      int pending = 0;
      for (Long id : ids) {
        if (!lease.claimedUntil().isAfter(LocalDateTime.now(clock))) break;
        try {
          if (id == null || id < 1) throw new IllegalStateException("Invalid recovery target");
          cancellation.touchRecovery(id);
          coordinator.reconcileTerminal(id);
        } catch (RuntimeException failure) {
          pending++;
        }
      }
      if (lease.claimedUntil().isAfter(LocalDateTime.now(clock))) invites.expireElapsedInvites();
      if (pending > 0)
        LOG.warn("Protected invitation recovery retained {} pending account(s)", pending);
    } catch (RuntimeException failure) {
      LOG.warn("Protected invitation recovery remains pending");
    } finally {
      try {
        claims.release(lease);
      } catch (RuntimeException failure) {
        LOG.warn("Protected invitation recovery lease remains pending");
      }
    }
  }
}
