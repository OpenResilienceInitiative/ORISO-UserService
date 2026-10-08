package de.caritas.cob.userservice.api.port.out;

import java.util.Optional;

/** Focused outbound identity profile read contract. */
public interface IdentityProfileLookup {

  Optional<IdentityProfile> findById(String userId);

  default java.util.Optional<IdentityProfile> findById(
      String userId,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          origin) {
    throw new org.springframework.security.access.AccessDeniedException(
        "Identity adapter must preserve explicit origin authorization");
  }
}
