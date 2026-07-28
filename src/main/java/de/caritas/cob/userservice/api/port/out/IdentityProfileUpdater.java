package de.caritas.cob.userservice.api.port.out;

/** Focused outbound contract for identity profile mutation. */
public interface IdentityProfileUpdater {

  void updateProfile(String userId, IdentityProfileUpdate profile);
}
