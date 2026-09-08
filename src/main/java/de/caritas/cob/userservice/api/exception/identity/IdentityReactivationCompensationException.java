package de.caritas.cob.userservice.api.exception.identity;

/** Signals a partial Keycloak reactivation whose fail-closed disable compensation also failed. */
public class IdentityReactivationCompensationException extends RuntimeException {

  private static final long serialVersionUID = -6143190495663338215L;

  public IdentityReactivationCompensationException(
      String message, Throwable reactivationFailure, Throwable compensationFailure) {
    super(message, compensationFailure);
    addSuppressed(reactivationFailure);
  }
}
