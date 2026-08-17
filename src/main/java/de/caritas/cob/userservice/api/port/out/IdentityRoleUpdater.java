package de.caritas.cob.userservice.api.port.out;

import java.util.Collection;

/** Focused outbound contract for idempotent identity role changes. */
public interface IdentityRoleUpdater {

  void assignRoles(String userId, Collection<String> roleNames);

  void ensureRoles(String userId, Collection<String> roleNames);

  void removeRolesIfPresent(String userId, Collection<String> roleNames);
}
