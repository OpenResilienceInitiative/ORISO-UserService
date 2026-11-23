package de.caritas.cob.userservice.api.service.availability;

/** Signals that Redis could not complete a consultant availability read or state change. */
public class AvailabilityStoreException extends RuntimeException {

  public AvailabilityStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
