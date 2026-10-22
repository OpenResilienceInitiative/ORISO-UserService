package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedAccountActivation.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.HistoricalAcceptance;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.*;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal coordinator seam; SQL/native proof owners are explicit private-handle port doubles. */
class ProtectedAdminTerminalRecoveryTest {
  private final AccountInviteService invites=mock(AccountInviteService.class);
  private final TaskActorConfiguration actors=mock(TaskActorConfiguration.class);
  private final JdbcProtectedInvitationAcceptance acceptance=mock(JdbcProtectedInvitationAcceptance.class);
  private final ProtectedAccountCodec codec=mock(ProtectedAccountCodec.class);
  private final JdbcProtectedAccountCreation creation=mock(JdbcProtectedAccountCreation.class);
  private final KeycloakProtectedAccounts nativeAccounts=mock(KeycloakProtectedAccounts.class);
  private final ProtectedOtpBootstrap otp=mock(ProtectedOtpBootstrap.class);
  private final JdbcProtectedAccountActivation activation=mock(JdbcProtectedAccountActivation.class);
  private final AccountInactivityEnrollmentService inactivity=mock(AccountInactivityEnrollmentService.class);
  private final ProtectedAccountRecovery recovery=mock(ProtectedAccountRecovery.class);
  private final JdbcProtectedInvitationCancellation cancellation=mock(JdbcProtectedInvitationCancellation.class);
  private final HistoricalAcceptance history=mock(HistoricalAcceptance.class);
  private final LocalOutcome local=new LocalOutcome(1,UUID.fromString("6496ad22-ec5a-4a1c-a977-ef229843405a"),"e".repeat(64));

  @BeforeEach
  void exactOriginalHistory() {
    when(actors.isEnabled()).thenReturn(true);
    when(history.inviteId()).thenReturn(73L);
    when(acceptance.historical(73)).thenReturn(history);
  }

  @Test
  void lostCommittedReplyAfterExpiryUsesOnlyIndependentStatusAndHistoricalBookkeeping() {
    when(activation.phase(history)).thenReturn(Phase.READY);
    var ready=mock(HistoricalReady.class);var created=mock(VerifiedCreated.class);var committed=mock(VerifiedCommitted.class);
    when(activation.historicalReady(history)).thenReturn(ready);
    when(ready.created()).thenReturn(created);when(ready.outcome()).thenReturn(local);
    when(recovery.consumedStatus(created,local)).thenAnswer(call->{
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return committed;
    });
    assertDoesNotThrow(()->service().reconcileTerminal(73));
    var order=inOrder(acceptance,activation,recovery);
    order.verify(acceptance).historical(73);order.verify(activation).phase(history);
    order.verify(activation).historicalReady(history);order.verify(recovery).consumedStatus(created,local);
    order.verify(activation).acknowledge(ready,committed);
    verifyNoInteractions(cancellation,creation,nativeAccounts,otp,invites);
  }

  @Test
  void exactRestrictionThenSavedRecoveryAndLocalCleanupAlwaysPrecedeIndependentFinalStatus() {
    when(activation.phase(history)).thenReturn(Phase.WAITING_OTP);
    var restricted=mock(ProtectedInvitationRestrictionCodec.VerifiedRestriction.class);
    when(restricted.cleanupRequired()).thenReturn(true);
    when(restricted.responseJson()).thenReturn("{\"boundAttemptId\":\"15adb2ed-e40b-48ce-aa4a-49f7f2d71937\",\"fencedExecution\":{\"generation\":1}}");
    when(cancellation.verifiedRestriction(73)).thenReturn(restricted);
    var prepared=mock(PreparedRecovery.class);var claimed=mock(VerifiedRecovery.class);
    when(creation.recovery(history,1)).thenReturn(prepared);when(recovery.recover(prepared)).thenReturn(claimed);
    when(activation.discard(history,claimed)).thenReturn(local);
    when(recovery.compensate(claimed,local)).thenAnswer(call->{
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return mock(VerifiedCleanup.class);
    });
    assertDoesNotThrow(()->service().reconcileTerminal(73));
    var order=inOrder(acceptance,activation,cancellation,creation,recovery);
    order.verify(acceptance).historical(73);order.verify(activation).phase(history);
    order.verify(cancellation).cancelPendingActivation(history,activation);
    order.verify(cancellation).verifiedRestriction(73);order.verify(creation).recovery(history,1);
    order.verify(recovery).recover(prepared);order.verify(activation).discard(history,claimed);
    order.verify(recovery).compensate(claimed,local);order.verify(cancellation).refreshAfterCleanup(73,claimed,local);
    verify(cancellation,never()).withAcknowledged(anyLong(),any());
    verifyNoInteractions(nativeAccounts,otp,invites);
  }

  @Test
  void uncertainStatusCannotAuthorizeCleanupAndCommittedLocalPhaseCannotBeReduced() {
    when(activation.phase(history)).thenReturn(Phase.READY);
    var ready=mock(HistoricalReady.class);var created=mock(VerifiedCreated.class);
    when(activation.historicalReady(history)).thenReturn(ready);when(ready.created()).thenReturn(created);when(ready.outcome()).thenReturn(local);
    when(recovery.consumedStatus(created,local)).thenThrow(new KeycloakProtectedAccounts.RemoteFailure(0));
    doThrow(new KeycloakProtectedInvitations.RemoteFailure(0)).when(cancellation).cancelPendingActivation(history,activation);
    assertThrows(RuntimeException.class,()->service().reconcileTerminal(73));
    verify(recovery).consumedStatus(created,local);verify(cancellation).cancelPendingActivation(history,activation);
    verifyNoInteractions(creation,nativeAccounts,otp,invites);
    verify(activation,never()).discard(any(HistoricalAcceptance.class),any());
    reset(activation,cancellation,recovery);
    when(activation.phase(history)).thenReturn(Phase.COMMITTED);
    assertDoesNotThrow(()->service().reconcileTerminal(73));
    verifyNoInteractions(cancellation,recovery,creation,nativeAccounts,otp,invites);
  }

  private ProtectedAdminOnboardingService service(){return new ProtectedAdminOnboardingService(invites,actors,acceptance,codec,creation,nativeAccounts,otp,activation,inactivity,mock(UsernameTranscoder.class),mock(ProtectedInvitationProfiles.class),mock(OperatorDpaContentClient.class),mock(TenantCreationClient.class),Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"),ZoneOffset.UTC),recovery,cancellation);}
}
