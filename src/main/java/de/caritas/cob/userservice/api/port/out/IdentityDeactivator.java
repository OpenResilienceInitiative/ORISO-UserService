package de.caritas.cob.userservice.api.port.out;

/** Deactivates an identity without exposing provider-specific transport details. */
public interface IdentityDeactivator {

  void deactivateUser(String userId);
}
