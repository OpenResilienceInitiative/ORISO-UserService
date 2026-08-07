package de.caritas.cob.userservice.api.port.out;

/** Updates an identity password without exposing provider-specific credential types. */
public interface IdentityPasswordUpdater {

  void updatePassword(String userId, String password);
}
