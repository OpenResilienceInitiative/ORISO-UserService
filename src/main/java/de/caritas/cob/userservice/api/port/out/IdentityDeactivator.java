package de.caritas.cob.userservice.api.port.out;

/** Deactivates an identity without exposing provider-specific representation types. */
public interface IdentityDeactivator {

  void deactivateUser(String userId);
}
