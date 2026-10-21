package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;

/** Unknown bounded C read must not be reported as an available username. */
public final class ScopedAvailabilityUnavailableException extends ServiceUnavailableException {
  public ScopedAvailabilityUnavailableException() {
    super("Username availability could not be confirmed");
  }
}
