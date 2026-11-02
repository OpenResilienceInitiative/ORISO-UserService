package de.caritas.cob.userservice.api.port.out;

/** Deactivates an identity without exposing provider-specific transport details. */
public interface IdentityDeactivator {

  void deactivateUser(String userId);

  /**
   * Turns the login of a deactivated identity back on. It is the exact inverse of {@link
   * #deactivateUser}: nothing else about the identity changes, so credentials, the second factor
   * and required actions stay exactly as they were.
   */
  void activateUser(String userId);
}
