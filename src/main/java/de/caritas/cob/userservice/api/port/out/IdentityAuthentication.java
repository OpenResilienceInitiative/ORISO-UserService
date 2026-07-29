package de.caritas.cob.userservice.api.port.out;

/** Provider-neutral identity authentication contract. */
public interface IdentityAuthentication {

  IdentityLogin login(String username, String password);

  boolean logout(String refreshToken);

  boolean verifyPasswordIgnoringSecondFactor(String username, String password);
}
