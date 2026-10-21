package de.caritas.cob.userservice.api.exception.identity;

/** Signals that Keycloak could not complete a safe asker-identity reactivation. */
public class IdentityReactivationUpstreamException extends RuntimeException {

  private static final long serialVersionUID = 6092197558621095899L;

  public IdentityReactivationUpstreamException(String message, Throwable cause) {
    super(message, cause);
  }
}
