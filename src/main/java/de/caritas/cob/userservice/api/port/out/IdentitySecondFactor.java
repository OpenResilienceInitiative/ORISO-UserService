package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.identity.IdentityEmailVerification;
import de.caritas.cob.userservice.api.identity.IdentityEmailVerificationStart;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;

public interface IdentitySecondFactor {

  IdentityOtpCredential getOtpCredential(String username);

  boolean setUpOtpCredential(String username, String initialCode, String secret);

  void deleteOtpCredential(String username);

  IdentityEmailVerificationStart initiateEmailVerification(String username, String email);

  IdentityEmailVerification finishEmailVerification(String username, String initialCode);
}
