package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;

import java.util.UUID;

/** Restart-safe dispatch; receiver fencing precedes exact owned local/downstream cleanup. */
public class AccountLifecycleDispatcher {
  private final IdentityProvisioningCommands commands;
  private final AccountLifecycleJournal journal;
  private final AccountLifecycleEffects effects;
  private final AccountLifecycleCodec codec;

  public AccountLifecycleDispatcher(
      IdentityProvisioningCommands commands,
      AccountLifecycleJournal journal,
      AccountLifecycleEffects effects,
      AccountLifecycleCodec codec) {
    this.commands = commands;
    this.journal = journal;
    this.effects = effects;
    this.codec = codec;
  }

  public void dispatch(UUID id) {
    journal.withPending(
        id,
        pending -> {
          AttemptResult result;
          switch (pending.operation()) {
            case "COMMIT" -> {
              result = commands.commit(id, codec.read(pending.body(), CommitRequest.class));
              requireReceipt(pending, result);
              if (!"COMMITTED".equals(result.state())) {
                journal.reconcile(pending);
                return null;
              }
              journal.finished(pending, result);
            }
            case "RECOVER" -> {
              var request = codec.read(pending.body(), RecoverRequest.class);
              result = commands.recover(id, request);
              if (result == null || !id.equals(result.attemptId())) throw invalid();
              if ("COMMITTED".equals(result.state())) {
                journal.reconcile(pending);
                return null; // never infer local success or delete committed user
              }
              if (!java.util.Set.of("RECOVERY_CLAIMED", "ABANDONED").contains(result.state())
                  || result.execution().generation() != request.expectedGeneration() + 1
                  || !result.execution().executionId().equals(request.recoveryId()))
                throw invalid();
              journal.recovered(
                  pending, result); // immutable receiver claim saved before any cleanup
            }
            case "CLEANUP" -> {
              effects.clean(
                  pending.attempt()); // ABANDONED still requires all owned effects cleaned
              journal.cleaned(
                  pending); // new durable compensation intent includes actual cleanup digest
            }
            case "COMPENSATE" -> {
              result = commands.compensate(id, codec.read(pending.body(), CompensateRequest.class));
              requireReceipt(pending, result);
              if (!java.util.Set.of("COMPENSATED", "ABANDONED").contains(result.state())) {
                journal.reconcile(pending);
                return null;
              }
              journal.finished(pending, result);
            }
            default -> throw invalid();
          }
          return null;
        });
  }

  public void retryPending() {
    for (UUID id : journal.pending(100)) {
      try {
        dispatch(id);
      } catch (RuntimeException failure) {
        /* pending intent retained, sanitized scheduler metrics only */
      }
    }
  }

  private static void requireReceipt(
      AccountLifecycleJournal.Pending pending, AttemptResult result) {
    var attempt = pending.attempt();
    if (result == null
        || !attempt.id().equals(result.attemptId())
        || !attempt.execution().equals(result.execution())
        || !java.util.Objects.equals(attempt.accountId(), result.accountId())
        || !java.util.Objects.equals(attempt.creationProof(), result.creationProof()))
      throw invalid();
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("Conflicting account lifecycle receipt");
  }
}
