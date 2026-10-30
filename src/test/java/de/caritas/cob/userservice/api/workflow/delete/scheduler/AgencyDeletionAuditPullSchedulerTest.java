package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.workflow.delete.service.AgencyDeletionAuditPull;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(MockitoExtension.class)
class AgencyDeletionAuditPullSchedulerTest {

  @Mock private AgencyDeletionAuditPull pull;
  @Mock private ScheduledTaskClaimService claims;
  @Mock private IdentityClientConfig identity;

  private AgencyDeletionAuditPullScheduler scheduler(MockEnvironment environment) {
    return new AgencyDeletionAuditPullScheduler(pull, claims, identity, environment);
  }

  private ScheduledTaskClaimService.ClaimLease leaseGranted() {
    var lease =
        new ScheduledTaskClaimService.ClaimLease(
            AgencyDeletionAuditPullScheduler.TASK_NAME, LocalDateTime.now().plusMinutes(5));
    when(claims.tryClaimLease(eq(AgencyDeletionAuditPullScheduler.TASK_NAME), any(Duration.class)))
        .thenReturn(Optional.of(lease));
    return lease;
  }

  @Test
  void defaultsToOnInFiveActorMode() {
    when(identity.isFiveActorMode()).thenReturn(true);
    var lease = leaseGranted();

    scheduler(new MockEnvironment()).pull();

    verify(pull).pullOnce();
    verify(claims).release(lease);
  }

  @Test
  void defaultsToOffInLegacyModeBecauseAgencyServiceRefusesLegacyTokens() {
    when(identity.isFiveActorMode()).thenReturn(false);

    scheduler(new MockEnvironment()).pull();

    verifyNoInteractions(pull, claims);
  }

  @Test
  void anExplicitOffWinsInFiveActorMode() {
    scheduler(new MockEnvironment().withProperty("deletion.audit.agency-outbox.enabled", "false"))
        .pull();

    verifyNoInteractions(pull, claims);
  }

  @Test
  void anExplicitOnCannotForceALegacyPull() {
    when(identity.isFiveActorMode()).thenReturn(false);

    scheduler(new MockEnvironment().withProperty("deletion.audit.agency-outbox.enabled", "true"))
        .pull();

    verifyNoInteractions(pull, claims);
  }

  @Test
  void anotherReplicaHoldingTheLeaseSkipsThisRun() {
    when(identity.isFiveActorMode()).thenReturn(true);
    when(claims.tryClaimLease(eq(AgencyDeletionAuditPullScheduler.TASK_NAME), any(Duration.class)))
        .thenReturn(Optional.empty());

    scheduler(new MockEnvironment()).pull();

    verifyNoInteractions(pull);
  }

  @Test
  void aFailingPullStillReleasesTheLease() {
    when(identity.isFiveActorMode()).thenReturn(true);
    var lease = leaseGranted();
    when(pull.pullOnce()).thenThrow(new IllegalStateException("AgencyService down"));

    scheduler(new MockEnvironment()).pull();

    verify(claims).release(lease);
  }
}
