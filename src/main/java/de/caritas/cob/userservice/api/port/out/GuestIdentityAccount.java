package de.caritas.cob.userservice.api.port.out;

import java.util.Optional;

/** Create-only guest identity boundary; callers must durably bind the candidate before using it. */
public interface GuestIdentityAccount {
  String createOnly(String username, String password, Long tenantId, String ownershipMarker);

  /**
   * Assigns the existing user role and mandatory ID attribute only after ownership verification.
   */
  void completeOwned(String id, String username, Long tenantId, String ownershipMarker);

  /**
   * Exact read-only ownership reconciliation. Empty is a confirmed absent lookup, not permission to
   * replace a candidate after an ambiguous write. Conflicting or disabled accounts must not mutate.
   */
  Optional<String> findOwned(String username, Long tenantId, String ownershipMarker);

  /**
   * Removes a provisional identity only after exact ownership verification and confirms absence.
   * The caller must durably authorize compensation before local user/session finalization. Unknown
   * outcomes remain retryable for this same provider ID, never permission to replace it.
   */
  void deleteOwned(String id, String username, Long tenantId, String ownershipMarker);
}
