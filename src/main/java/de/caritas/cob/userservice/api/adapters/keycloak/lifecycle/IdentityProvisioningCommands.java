package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;

import java.util.UUID;

/** Exactly five ordinary lifecycle commands; no admin REST fallback, inventory or maintenance. */
public interface IdentityProvisioningCommands {
  AttemptResult create(UUID attemptId, CreateRequest request);

  AccountProjection ownedRead(UUID attemptId, OwnedReadRequest request);

  AttemptResult recover(UUID attemptId, RecoverRequest request);

  AttemptResult commit(UUID attemptId, CommitRequest request);

  AttemptResult compensate(UUID attemptId, CompensateRequest request);
}
