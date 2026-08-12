package de.caritas.cob.userservice.api.port.out;

public interface IdentityEmailAddressUpdater {

  void updateCurrentUserEmail(String emailAddress);

  void deleteCurrentUserEmail();

  void updateEmailByUsername(String username, String emailAddress);
}
