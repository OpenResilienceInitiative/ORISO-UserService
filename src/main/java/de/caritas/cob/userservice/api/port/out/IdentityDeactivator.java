package de.caritas.cob.userservice.api.port.out;

/** Deactivates an identity without exposing provider-specific transport details. */
public interface IdentityDeactivator {

  void deactivateUser(String userId);

  default void deactivateUser(
      String userId,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          origin) {
    throw new org.springframework.security.access.AccessDeniedException(
        "Identity adapter must preserve explicit origin authorization");
  }
}
