package de.caritas.cob.userservice.api.port.in;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.identity.IdentityEmailVerification;
import de.caritas.cob.userservice.api.identity.IdentityEmailVerificationStart;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;

public interface IdentityManaging {

  IdentityEmailVerificationStart setUpOneTimePassword(String username, String email);

  boolean setUpOneTimePassword(String username, String initialCode, String secret);

  IdentityEmailVerification validateOneTimePassword(String username, String code);

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  boolean validatePasswordIgnoring2fa(String username, String password);

  boolean changePassword(String userId, String password);

  void changeLanguage(String userId, String locale);

  void deleteOneTimePassword(String username);

  IdentityOtpCredential getOtpCredential(String username);

  boolean isUsernameAvailable(String username);

  boolean isEmailAvailableOrOwn(String username, String email);

  boolean hasRole(String userId, UserRole role);
}
