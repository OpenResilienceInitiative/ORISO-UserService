package de.caritas.cob.userservice.api.service.accountinvite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationCancellation;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.ProtectedAdminOnboardingService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Scheduled public boundary with explicit owner doubles; lease writer has its own SQL contract. */
class ProtectedInvitationRecoverySweepTest {
  private final TaskActorConfiguration actors = mock(TaskActorConfiguration.class);
  private final ScheduledTaskClaimService claims = mock(ScheduledTaskClaimService.class);
  private final JdbcProtectedInvitationCancellation cancellation =
      mock(JdbcProtectedInvitationCancellation.class);
  private final ProtectedAdminOnboardingService coordinator =
      mock(ProtectedAdminOnboardingService.class);
  private final AccountInviteService invites = mock(AccountInviteService.class);
  private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
  private final ScheduledTaskClaimService.ClaimLease lease =
      new ScheduledTaskClaimService.ClaimLease(
          "protected-invitation-terminal-recovery", LocalDateTime.now(clock).plusMinutes(5));

  @BeforeEach
  void setup() {
    lenient().when(actors.isEnabled()).thenReturn(true);
    lenient()
        .when(claims.tryClaimLease(lease.taskName(), Duration.ofMinutes(5)))
        .thenReturn(Optional.of(lease));
  }

  @Test
  void oneOwnedBoundedPageReconcilesOutsideSqlAndReleasesOnlyItsExactLease() {
    when(cancellation.recoveryInviteIds(100)).thenReturn(List.of(73L));
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return null;
            })
        .when(coordinator)
        .reconcileTerminal(73);
    sweep().retryPending();
    var order = inOrder(claims, cancellation, coordinator, invites);
    order.verify(claims).tryClaimLease(lease.taskName(), Duration.ofMinutes(5));
    order.verify(cancellation).recoveryInviteIds(100);
    order.verify(cancellation).touchRecovery(73);
    order.verify(coordinator).reconcileTerminal(73);
    order.verify(invites).expireElapsedInvites();
    order.verify(claims).release(lease);
    verify(claims, never()).runIfHeld(any(), any());
    reset(claims, cancellation, coordinator, invites);
    when(claims.tryClaimLease(lease.taskName(), Duration.ofMinutes(5)))
        .thenReturn(Optional.empty());
    sweep().retryPending();
    verifyNoInteractions(cancellation, coordinator, invites);
    verify(claims, never()).release(any());
  }

  @Test
  void oneUnknownAccountDoesNotStarveTheFollowingAccountOrLeakTheLease() {
    when(cancellation.recoveryInviteIds(100)).thenReturn(List.of(73L, 74L));
    doThrow(new IllegalStateException("synthetic unknown outcome"))
        .when(coordinator)
        .reconcileTerminal(73);
    assertDoesNotThrow(() -> sweep().retryPending());
    verify(cancellation).touchRecovery(73);
    verify(coordinator).reconcileTerminal(74);
    verify(cancellation).touchRecovery(74);
    verify(claims).release(lease);
  }

  @Test
  void actualAmbientSqlRefusesBeforeClaimOrAnyRemoteOwnerIsInvoked() {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:sweep-" + UUID.randomUUID());
    new TransactionTemplate(new DataSourceTransactionManager(source))
        .executeWithoutResult(
            tx -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              assertThrows(IllegalStateException.class, () -> sweep().retryPending());
            });
    verifyNoInteractions(claims, cancellation, coordinator, invites);
  }

  @Test
  void legacyModelDoesNotActivateThisProtectedRecoveryWorker() {
    when(actors.isEnabled()).thenReturn(false);
    sweep().retryPending();
    verifyNoInteractions(claims, cancellation, coordinator, invites);
  }

  private ProtectedInvitationRecoverySweep sweep() {
    return new ProtectedInvitationRecoverySweep(
        actors, claims, cancellation, coordinator, invites, clock);
  }
}
