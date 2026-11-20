package de.caritas.cob.userservice.api.admin.service;

/** Internal control signal proving Keycloak rejected reactivation before any identity mutation. */
public class IdentityReactivationUnmutatedException extends RuntimeException {

  private static final long serialVersionUID = -6669576270262809394L;

  public IdentityReactivationUnmutatedException(RuntimeException originalFailure) {
    super("Identity reactivation failed before mutation", originalFailure);
  }

  public RuntimeException originalFailure() {
    return (RuntimeException) getCause();
  }
}
