package de.caritas.cob.userservice.api.port.out;

/** Reads the identity provider's current first-password-change requirement. */
public interface IdentityPasswordChangeRequirement {

  /** Returns false for an existing account whose temporary credential has already been replaced. */
  boolean requiresPasswordChange(String userId);

  default boolean requiresPasswordChange(
      String userId,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          origin) {
    throw new org.springframework.security.access.AccessDeniedException(
        "Explicit account-read capability is required");
  }
}
