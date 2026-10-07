package de.caritas.cob.userservice.api.port.out;

import java.util.List;

/** Focused outbound identity realm-role read contract. */
public interface IdentityRoleLookup {

  List<String> findAllByUserId(String userId);

  default List<String> findAllByUserId(
      String userId,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
          origin) {
    throw new org.springframework.security.access.AccessDeniedException(
        "Explicit account-read capability is required");
  }
}
