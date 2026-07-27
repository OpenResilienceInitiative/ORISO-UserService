package de.caritas.cob.userservice.api.port.out;

/** Updates the password of an identity without exposing provider-specific credential types. */
public interface IdentityPasswordUpdater {

  void updatePassword(String userId, String password);
}
