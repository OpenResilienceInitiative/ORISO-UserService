package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reuses historical durable-before-create / saga row-lock design with explicit fenced wire values.
 */
public final class AccountLifecycleCoordinator {
  private final IdentityProvisioningCommands remote;
  private final AccountLifecycleJournal journal;
  private final AccountLifecycleDispatcher dispatcher;
  private final AccountLifecycleCodec codec;

  public AccountLifecycleCoordinator(
      IdentityProvisioningCommands remote,
      AccountLifecycleJournal journal,
      AccountLifecycleDispatcher dispatcher,
      AccountLifecycleCodec codec) {
    this.remote = remote;
    this.journal = journal;
    this.dispatcher = dispatcher;
    this.codec = codec;
  }

  public AttemptResult create(
      UUID id, CreationScope verifiedScope, AccountCreation validatedAccount) {
    var identity = codec.identity(verifiedScope, validatedAccount);
    var execution = journal.begin(id, identity); // independently committed before any HTTP call
    try {
      var result = remote.create(id, new CreateRequest(identity, execution, validatedAccount));
      if (result == null
          || !id.equals(result.attemptId())
          || !execution.equals(result.execution())
          || !"OPEN".equals(result.state()))
        throw new IllegalStateException("Foreign creation receipt");
      journal.created(id, identity, execution, result);
      return result;
    } catch (RuntimeException failure) {
      try {
        journal.createUncertain(id, execution);
      } catch (RuntimeException journalFailure) {
        failure.addSuppressed(journalFailure);
      }
      if (failure instanceof KeycloakAccountLifecycleCommands.RemoteFailure remoteFailure
          && remoteFailure.status() == 409) {
        if ("USERNAME_NOT_AVAILABLE".equals(remoteFailure.code()))
          throw new de.caritas.cob.userservice.api.exception.httpresponses
              .CustomValidationHttpStatusException(
              de.caritas.cob.userservice.api.exception.httpresponses.customheader
                  .HttpStatusExceptionReason.USERNAME_NOT_AVAILABLE,
              org.springframework.http.HttpStatus.CONFLICT);
        if ("EMAIL_NOT_AVAILABLE".equals(remoteFailure.code()))
          throw new de.caritas.cob.userservice.api.exception.httpresponses
              .CustomValidationHttpStatusException(
              de.caritas.cob.userservice.api.exception.httpresponses.customheader
                  .HttpStatusExceptionReason.EMAIL_NOT_AVAILABLE,
              org.springframework.http.HttpStatus.CONFLICT);
      }
      throw failure;
    }
  }

  /**
   * The lock spans every local write and effect capture, so timeout recovery cannot steal a saga.
   */
  public <T> T localSaga(
      AttemptResult receipt, Function<AccountLifecycleJournal.Attempt, T> writes) {
    try {
      return journal.inLocalSaga(
          receipt.attemptId(),
          receipt.execution(),
          attempt -> {
            T result = writes.apply(attempt);
            completeAfterLocalSaga(attempt.id(), attempt.execution());
            return result;
          });
    } catch (RuntimeException failure) {
      if (TransactionSynchronizationManager.isActualTransactionActive()
          && TransactionSynchronizationManager.isSynchronizationActive()) {
        // The outer invitation transaction still owns the journal row. A REQUIRES_NEW recovery
        // here would wait on our own suspended row lock; wait for the actual rollback instead.
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK)
                  recoverSafely(receipt.attemptId(), receipt.execution());
              }
            });
      } else {
        recoverSafely(receipt.attemptId(), receipt.execution());
      }
      throw failure;
    }
  }

  public void retryFinalization(UUID id) {
    dispatcher.dispatch(id);
  }

  private void recoverSafely(UUID id, ExecutionRef execution) {
    try {
      journal.requestRecovery(id, execution);
    } catch (RuntimeException ignored) {
      /* durable OPEN/uncertain lease remains recoverable */
    }
  }

  public void completeAfterLocalSaga(UUID id, ExecutionRef execution) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Account activation requires a real local saga transaction");
    journal.prepareCommitInSaga(
        id, execution); // COMMIT_REQUESTED + immutable outbox in SAME transaction
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              dispatcher.dispatch(id);
            } catch (RuntimeException failure) {
              /* durable outbox retry owns uncertain remote finalization */
            }
          }

          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK) {
              try {
                journal.requestRecovery(id, execution);
              } catch (RuntimeException failure) {
                /* unchanged OPEN lease remains eligible for durable recovery */
              }
            }
          }
        });
  }
}
