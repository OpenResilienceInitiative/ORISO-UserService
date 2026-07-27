package de.caritas.cob.userservice.api.port.out;

public interface IdentityAccountSettingsUpdater {

  boolean changePassword(String userId, String password);

  void changePreferredLanguage(String userId, String language);
}
