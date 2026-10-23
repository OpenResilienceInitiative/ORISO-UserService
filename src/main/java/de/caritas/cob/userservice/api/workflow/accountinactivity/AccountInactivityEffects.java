package de.caritas.cob.userservice.api.workflow.accountinactivity;

import java.util.Set;

/**
 * External effects must be idempotent and return true only after every effect is confirmed.
 * Implementations must recheck roles immediately before deletion and refuse deletion unless the
 * identity remains exclusively ASKER. Required recovery metadata must survive row deletion.
 */
public interface AccountInactivityEffects {
  enum Role {
    ASKER,
    CONSULTANT,
    OTHER,
    UNKNOWN
  }

  default Set<Role> currentRoles(AccountInactivityService.ClaimedWork work) {
    throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
  }

  default boolean suspend(AccountInactivityService.ClaimedWork work) {
    throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
  }

  default boolean reactivate(AccountInactivityService.ClaimedWork work) {
    throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
  }

  default boolean delete(AccountInactivityService.ClaimedWork work) {
    throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
  }

  Set<Role> currentRoles(String identityId);

  boolean delete(String identityId);

  boolean suspend(String identityId);

  boolean reactivate(String identityId);
}
