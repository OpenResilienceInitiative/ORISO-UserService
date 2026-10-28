package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Restart-safe dispatch; receiver fencing precedes exact owned local/downstream cleanup. */
public class AccountLifecycleDispatcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(AccountLifecycleDispatcher.class);
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
          try {
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
                boolean originalFence =
                    request.expectedGeneration() < Long.MAX_VALUE
                        && result.execution().generation() == request.expectedGeneration() + 1
                        && result.execution().executionId().equals(request.recoveryId());
                boolean historicalTerminal =
                    java.util.Set.of("COMMITTED", "COMPENSATED").contains(result.state())
                        || ("ABANDONED".equals(result.state())
                            && !originalFence
                            && result.execution().generation() == request.expectedGeneration());
                if (historicalTerminal) {
                  var original = pending.attempt();
                  if (original.accountId() != null
                      && (!java.util.Objects.equals(original.accountId(), result.accountId())
                          || !java.util.Objects.equals(
                              original.creationProof(), result.creationProof()))) throw invalid();
                  journal.reconcile(pending);
                  return null; // authenticated history never authorizes a new cleanup or completion
                }
                if (!java.util.Set.of("RECOVERY_CLAIMED", "ABANDONED").contains(result.state())
                    || !originalFence) throw invalid();
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
                result =
                    commands.compensate(id, codec.read(pending.body(), CompensateRequest.class));
                requireReceipt(pending, result);
                if (!java.util.Set.of("COMPENSATED", "ABANDONED").contains(result.state())) {
                  journal.reconcile(pending);
                  return null;
                }
                journal.finished(pending, result);
              }
              default -> throw invalid();
            }
          } catch (KeycloakAccountLifecycleCommands.RemoteFailure failure) {
            if ((failure.status() == 403 || failure.status() == 409)
                && java.util.Set.of("COMMIT", "RECOVER", "COMPENSATE")
                    .contains(pending.operation())) {
              journal.reconcile(pending);
              LOGGER.warn(
                  "Account lifecycle dispatch stopped for reconciliation: attempt={}, operation={}, httpStatus={}",
                  id,
                  pending.operation(),
                  failure.status());
              return null;
            }
            throw failure;
          }
          return null;
        });
  }

  public void retryPending() {
    for (UUID id : journal.pending(100)) {
      try {
        dispatch(id);
      } catch (RuntimeException failure) {
        int status = 0;
        String reason = "UNKNOWN_OUTCOME";
        if (failure instanceof KeycloakAccountLifecycleCommands.RemoteFailure remote) {
          status = remote.status();
          reason = status >= 500 ? "REMOTE_UNAVAILABLE" : "REMOTE_REFUSAL";
        }
        LOGGER.warn(
            "Account lifecycle retry retained: attempt={}, reason={}, httpStatus={}",
            id,
            reason,
            status);
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
