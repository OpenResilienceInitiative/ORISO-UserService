package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

/** A readonly exact selector. This port conveys no account projection or mutation authority. */
@FunctionalInterface
public interface ScopedUsernameAvailability {
  boolean available(String exactUsername);
}
