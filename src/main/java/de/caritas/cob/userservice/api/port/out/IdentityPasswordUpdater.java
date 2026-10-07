package de.caritas.cob.userservice.api.port.out;

/** Updates an identity password without exposing provider-specific credential types. */
public interface IdentityPasswordUpdater {

  void updatePassword(String userId, String password);

  /** Sets an administrator-chosen password that must be replaced at first sign-in. */
  void updateTemporaryPassword(String userId, String password);

  default void updatePassword(
      String userId,
      String password,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          origin) {
    throw new org.springframework.security.access.AccessDeniedException(
        "Identity adapter must preserve explicit origin authorization");
  }
}
