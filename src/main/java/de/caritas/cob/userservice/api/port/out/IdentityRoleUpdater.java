package de.caritas.cob.userservice.api.port.out;

import java.util.Collection;

/** Focused outbound contract for idempotent identity role assignment. */
public interface IdentityRoleUpdater {

  void ensureRoles(String userId, Collection<String> roleNames);

  default void ensureRoles(
      String userId,
      Collection<String> roleNames,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          readOrigin,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          roleOrigin) {
    throw new org.springframework.security.access.AccessDeniedException(
        "Explicit role-read and role-write capabilities are required");
  }
}
