package de.caritas.cob.userservice.api.port.out;

import java.util.Collection;

/** Focused outbound contract for idempotent identity role assignment. */
public interface IdentityRoleUpdater {

  void assignRoles(String userId, Collection<String> roleNames);

  void ensureRoles(String userId, Collection<String> roleNames);
}
