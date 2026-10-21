package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

/**
 * Cleanup must acknowledge all exact owned effects, even if no local consultant/user row exists.
 */
public interface AccountLifecycleEffects {
  void clean(AccountLifecycleJournal.Attempt attempt);
}
