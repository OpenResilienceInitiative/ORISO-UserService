package de.caritas.cob.userservice.api.port.out;

import java.util.Collection;

/** Focused outbound identity realm-role write contract. */
public interface IdentityRoleUpdater {

  void ensureRoles(String userId, Collection<String> roleNames);
}
