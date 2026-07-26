package de.caritas.cob.userservice.api.service.availability;

/** Signals that Redis could not acknowledge a consultant availability state change. */
public class AvailabilityStoreException extends RuntimeException {

  public AvailabilityStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
