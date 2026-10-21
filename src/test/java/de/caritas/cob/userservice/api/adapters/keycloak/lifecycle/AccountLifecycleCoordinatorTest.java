package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountLifecycleCoordinatorTest {
  private final IdentityProvisioningCommands remote = mock(IdentityProvisioningCommands.class);
  private final AccountLifecycleJournal journal = mock(AccountLifecycleJournal.class);
  private final AccountLifecycleDispatcher dispatcher = mock(AccountLifecycleDispatcher.class);
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(new byte[32]);
  private final AccountLifecycleCoordinator coordinator =
      new AccountLifecycleCoordinator(remote, journal, dispatcher, codec);
  private final UUID id = UUID.randomUUID();
  private final ExecutionRef execution = new ExecutionRef(UUID.randomUUID(), 0);
  private final CreationScope scope =
      new CreationScope(
          "7",
          "ASKER",
          "/ordinary/asker",
          new Origin(
              "REGISTRATION", "registration:one", "agency:12", null, null, null, List.of("12")));
  private final AccountCreation account =
      new AccountCreation(
          "synthetic-user", null, null, null, "de", "synthetic-test-password", false);

  @Test
  void journalIdentityAndExecutionAreDurableBeforeRemoteCreate() {
    var identity = codec.identity(scope, account);
    when(journal.begin(id, identity)).thenReturn(execution);
    var result = open(identity);
    when(remote.create(id, new CreateRequest(identity, execution, account))).thenReturn(result);
    assertEquals(result, coordinator.create(id, scope, account));
    var order = inOrder(journal, remote);
    order.verify(journal).begin(id, identity);
    order.verify(remote).create(id, new CreateRequest(identity, execution, account));
    order.verify(journal).created(id, identity, execution, result);
  }

  @Test
  void lostCreateReplyStaysUncertainWithoutSearchOrLocalSuccess() {
    var identity = codec.identity(scope, account);
    when(journal.begin(id, identity)).thenReturn(execution);
    when(remote.create(eq(id), any())).thenThrow(new IllegalStateException("timeout"));
    assertThrows(IllegalStateException.class, () -> coordinator.create(id, scope, account));
    verify(journal).createUncertain(id, execution);
    verify(remote, never()).recover(any(), any());
    verify(journal, never()).created(any(), any(), any(), any());
  }

  @Test
  void foreignExecutionCannotBecomeRecordedReceipt() {
    var identity = codec.identity(scope, account);
    when(journal.begin(id, identity)).thenReturn(execution);
    when(remote.create(eq(id), any()))
        .thenReturn(
            new AttemptResult(
                id,
                "account",
                "OPEN",
                new ExecutionRef(UUID.randomUUID(), 0),
                "proof",
                null,
                null));
    assertThrows(IllegalStateException.class, () -> coordinator.create(id, scope, account));
    verify(journal, never()).created(any(), any(), any(), any());
    verify(journal).createUncertain(id, execution);
  }

  @Test
  void commitIntentIsWrittenBeforeAfterCommitDispatchAndSurvivesMissingCallback() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      coordinator.completeAfterLocalSaga(id, execution);
      verify(journal).prepareCommitInSaga(id, execution);
      verifyNoInteractions(dispatcher);
      var callbacks = TransactionSynchronizationManager.getSynchronizations();
      assertEquals(1, callbacks.size());
      callbacks.getFirst().afterCommit();
      verify(dispatcher).dispatch(id);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void rollbackNeverDispatchesCommit() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      coordinator.completeAfterLocalSaga(id, execution);
      TransactionSynchronizationManager.getSynchronizations()
          .getFirst()
          .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
      verifyNoInteractions(dispatcher);
      verify(journal).requestRecovery(id, execution);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void completionWithoutActualLocalTransactionCannotActivate() {
    assertThrows(
        IllegalStateException.class, () -> coordinator.completeAfterLocalSaga(id, execution));
    verifyNoInteractions(journal, dispatcher, remote);
  }

  @Test
  void outerTransactionRollbackSchedulesRecoveryAfterCompletionRatherThanStealingOwnRowLock() {
    var receipt = open(codec.identity(scope, account));
    when(journal.inLocalSaga(eq(id), eq(execution), any()))
        .thenThrow(new IllegalStateException("local failure"));
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThrows(
          IllegalStateException.class, () -> coordinator.localSaga(receipt, attempt -> null));
      verify(journal, never()).requestRecovery(any(), any());
      var callbacks = TransactionSynchronizationManager.getSynchronizations();
      assertEquals(1, callbacks.size());
      callbacks.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
      verify(journal).requestRecovery(id, execution);
      verifyNoInteractions(dispatcher);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void passwordChangesFingerprintButIsNeverInDtoDiagnostics() {
    var changed = new AccountCreation(account.username(), null, null, null, "de", "other", false);
    assertNotEquals(codec.identity(scope, account), codec.identity(scope, changed));
    assertFalse(account.toString().contains(account.password()));
    assertFalse(
        new CreateRequest(codec.identity(scope, account), execution, account)
            .toString()
            .contains(account.password()));
  }

  @Test
  void confirmedUsernameDuplicateCannotRunAnyLocalSagaOrDownstreamEffect() {
    var identity = codec.identity(scope, account);
    when(journal.begin(id, identity)).thenReturn(execution);
    when(remote.create(eq(id), any()))
        .thenThrow(
            new KeycloakAccountLifecycleCommands.RemoteFailure(409, "USERNAME_NOT_AVAILABLE"));
    var failure =
        assertThrows(
            de.caritas.cob.userservice.api.exception.httpresponses
                .CustomValidationHttpStatusException.class,
            () -> coordinator.create(id, scope, account));
    assertEquals(org.springframework.http.HttpStatus.CONFLICT, failure.getHttpStatus());
    assertEquals("USERNAME_NOT_AVAILABLE", failure.getCustomHttpHeaders().getFirst("X-Reason"));
    verify(journal).createUncertain(id, execution);
    verify(journal, never()).created(any(), any(), any(), any());
    verify(journal, never()).inLocalSaga(any(), any(), any());
    verifyNoInteractions(dispatcher);
  }

  @Test
  void arbitraryCreateConflictNeverReleasesOwnershipOrPretendsUsernameDuplicate() {
    var identity = codec.identity(scope, account);
    when(journal.begin(id, identity)).thenReturn(execution);
    var unknown = new KeycloakAccountLifecycleCommands.RemoteFailure(409);
    when(remote.create(eq(id), any())).thenThrow(unknown);
    assertSame(
        unknown,
        assertThrows(
            KeycloakAccountLifecycleCommands.RemoteFailure.class,
            () -> coordinator.create(id, scope, account)));
    verify(journal).createUncertain(id, execution);
    verify(journal, never()).created(any(), any(), any(), any());
    verifyNoInteractions(dispatcher);
  }

  private AttemptResult open(CreationIdentity identity) {
    return new AttemptResult(id, "account", "OPEN", execution, "opaque-proof", null, null);
  }
}
