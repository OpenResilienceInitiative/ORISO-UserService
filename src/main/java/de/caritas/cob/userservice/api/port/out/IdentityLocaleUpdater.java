package de.caritas.cob.userservice.api.port.out;

/** Updates an identity locale without exposing provider-specific profile representations. */
public interface IdentityLocaleUpdater {

  void updateLocale(String userId, String locale);
}
