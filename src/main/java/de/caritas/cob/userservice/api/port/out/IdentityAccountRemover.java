package de.caritas.cob.userservice.api.port.out;

/** Removes identities with explicit strict-deletion and best-effort rollback semantics. */
public interface IdentityAccountRemover {

  void deleteUser(String userId);

  void rollbackUser(String userId);

  default void deleteUser(
      String userId,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          origin) {
    throw new org.springframework.security.access.AccessDeniedException(
        "Identity adapter must preserve explicit origin authorization");
  }
}
