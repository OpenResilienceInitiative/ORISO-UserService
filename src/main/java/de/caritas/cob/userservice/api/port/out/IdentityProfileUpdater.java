package de.caritas.cob.userservice.api.port.out;

public interface IdentityProfileUpdater {

  void updateProfile(String userId, IdentityProfileUpdate profile);
}
