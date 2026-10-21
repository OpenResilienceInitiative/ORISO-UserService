package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Durable boundaries: a database transaction owns every transition, not an HTTP callback. */
public interface AccountLifecycleJournal {
  ExecutionRef begin(UUID id, CreationIdentity identity);

  void created(UUID id, CreationIdentity identity, ExecutionRef execution, AttemptResult receipt);

  void createUncertain(UUID id, ExecutionRef execution);

  <T> T inLocalSaga(UUID id, ExecutionRef execution, Function<Attempt, T> writes);

  void prepareCommitInSaga(UUID id, ExecutionRef execution);

  void requestRecovery(UUID id, ExecutionRef execution);

  List<UUID> pending(int limit);

  Optional<Attempt> findWorkflow(
      String originKind, String workflowId, Long tenantId, String accountId);

  void requireCommitted(UUID id);

  <T> T withPending(UUID id, Function<Pending, T> dispatch);

  void recovered(Pending pending, AttemptResult receipt);

  void cleaned(Pending pending);

  void finished(Pending pending, AttemptResult receipt);

  void reconcile(Pending pending);

  record Attempt(
      UUID id,
      CreationIdentity identity,
      ExecutionRef execution,
      UUID originalExecution,
      String accountId,
      String creationProof,
      String status,
      long version) {
    @Override
    public String toString() {
      return "Attempt[status=" + status + ",receipt=redacted]";
    }
  }

  record Pending(Attempt attempt, UUID outboxId, String operation, String body) {
    @Override
    public String toString() {
      return "Pending[operation=" + operation + ",body=redacted]";
    }
  }
}
