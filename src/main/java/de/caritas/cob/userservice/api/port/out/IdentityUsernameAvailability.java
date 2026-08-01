package de.caritas.cob.userservice.api.port.out;

/** Provider-neutral username availability contract. */
public interface IdentityUsernameAvailability {

  boolean isUsernameAvailable(String username);
}
