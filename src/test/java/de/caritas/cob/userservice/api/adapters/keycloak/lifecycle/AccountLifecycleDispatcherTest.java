package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AccountLifecycleDispatcherTest {
  private final IdentityProvisioningCommands remote = mock(IdentityProvisioningCommands.class);
  private final AccountLifecycleJournal journal = mock(AccountLifecycleJournal.class);
  private final AccountLifecycleEffects effects = mock(AccountLifecycleEffects.class);
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(new byte[32]);
  private final AccountLifecycleDispatcher dispatcher =
      new AccountLifecycleDispatcher(remote, journal, effects, codec);
  private final UUID id = UUID.randomUUID();
  private final ExecutionRef execution = new ExecutionRef(UUID.randomUUID(), 0);
  private final CreationIdentity identity =
      new CreationIdentity(
          new CreationScope(
              "7",
              "ASKER",
              "/ordinary/asker",
              new Origin(
                  "REGISTRATION",
                  "registration:one",
                  "agency:12",
                  null,
                  null,
                  null,
                  List.of("12"))),
          "a".repeat(64));

  @Test
  void recoveryClaimsRemoteBeforeAnyOwnedCleanup() {
    var request = new RecoverRequest(identity, 0, UUID.randomUUID());
    var pending = pending("RECOVER", "RECOVERY_REQUESTED", codec.canonical(request), null);
    var receipt =
        new AttemptResult(
            id,
            "account",
            "RECOVERY_CLAIMED",
            new ExecutionRef(request.recoveryId(), 1),
            "proof",
            "recovery-receipt",
            null);
    when(remote.recover(id, request)).thenReturn(receipt);
    dispatch(pending);
    var order = inOrder(remote, journal);
    order.verify(remote).recover(id, request);
    order.verify(journal).recovered(pending, receipt);
    verifyNoInteractions(effects);
  }

  @Test
  void abandonedRemoteDoesNotMarkLocalEffectsComplete() {
    var request = new RecoverRequest(identity, 0, UUID.randomUUID());
    var pending = pending("RECOVER", "RECOVERY_REQUESTED", codec.canonical(request), null);
    var receipt =
        new AttemptResult(
            id,
            null,
            "ABANDONED",
            new ExecutionRef(request.recoveryId(), 1),
            null,
            "recovery",
            "terminal");
    when(remote.recover(id, request)).thenReturn(receipt);
    dispatch(pending);
    verify(journal).recovered(pending, receipt);
    verify(journal, never()).finished(any(), any());
    verify(journal, never()).cleaned(any());
  }

  @Test
  void failedOwnedCleanupCannotSendCompensationOrMarkCleaned() {
    var pending = pending("CLEANUP", "LOCAL_CLEANUP_REQUESTED", "{}", null);
    doThrow(new IllegalStateException("uncertain downstream effect"))
        .when(effects)
        .clean(pending.attempt());
    assertThrows(IllegalStateException.class, () -> dispatch(pending));
    verify(journal, never()).cleaned(any());
    verifyNoInteractions(remote);
  }

  @Test
  void cleanupMustCompleteBeforeCompensationIntent() {
    var pending = pending("CLEANUP", "LOCAL_CLEANUP_REQUESTED", "{}", null);
    dispatch(pending);
    var order = inOrder(effects, journal);
    order.verify(effects).clean(pending.attempt());
    order.verify(journal).cleaned(pending);
    verifyNoInteractions(remote);
  }

  @Test
  void remoteCommittedDuringRecoveryRequiresReconciliationWithoutDeletion() {
    var request = new RecoverRequest(identity, 0, UUID.randomUUID());
    var pending = pending("RECOVER", "RECOVERY_REQUESTED", codec.canonical(request), null);
    when(remote.recover(id, request))
        .thenReturn(
            new AttemptResult(id, "account", "COMMITTED", execution, "proof", null, "terminal"));
    dispatch(pending);
    verify(journal).reconcile(pending);
    verify(journal, never()).finished(any(), any());
    verifyNoInteractions(effects);
  }

  private AccountLifecycleJournal.Pending pending(
      String operation, String status, String body, String account) {
    return new AccountLifecycleJournal.Pending(
        new AccountLifecycleJournal.Attempt(
            id,
            identity,
            execution,
            execution.executionId(),
            account,
            account == null ? null : "proof",
            status,
            2),
        UUID.randomUUID(),
        operation,
        body);
  }

  @SuppressWarnings("unchecked")
  private void dispatch(AccountLifecycleJournal.Pending pending) {
    when(journal.withPending(eq(id), any()))
        .thenAnswer(
            invocation -> {
              Function<AccountLifecycleJournal.Pending, Object> work = invocation.getArgument(1);
              return work.apply(pending);
            });
    dispatcher.dispatch(id);
  }
}
