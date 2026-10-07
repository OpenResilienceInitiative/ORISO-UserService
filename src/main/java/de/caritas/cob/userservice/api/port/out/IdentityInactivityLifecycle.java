package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects;
import java.util.Set;

/** Exact-account lifecycle effects using persisted workflow and original access authority. */
public interface IdentityInactivityLifecycle {
  record State(boolean enabled, int sessionCount, Set<AccountInactivityEffects.Role> roles) {
    public State {
      roles = Set.copyOf(roles);
    }
  }

  de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
      authorizeDeletion(String identityId);

  State status(String identityId);

  void suspend(String identityId);

  void restoreOriginalAccess(String identityId);
}
