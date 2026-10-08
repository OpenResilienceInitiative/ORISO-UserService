package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import java.util.UUID;

/** Outbound atomic provisioning boundary; creator credentials have no general account deletion. */
public interface IdentityProvisioningCommands {
  KeycloakTaskCommands.CreationResult create(
      UUID attemptId,
      KeycloakTaskCommands.AccountCreation command,
      IdentityCommandAuthorization authorization);

  KeycloakTaskCommands.RecoveryResult recover(
      UUID attemptId, String registrationKind, IdentityCommandAuthorization authorization);

  default KeycloakTaskCommands.AccountProjection ownedRead(
      String id, IdentityCommandAuthorization authorization) {
    throw new org.springframework.security.access.AccessDeniedException(
        "Owned creation read is unsupported");
  }

  void commit(
      KeycloakTaskCommands.CreationResult receipt, IdentityCommandAuthorization authorization);

  void compensate(
      KeycloakTaskCommands.CreationResult receipt, IdentityCommandAuthorization authorization);
}
