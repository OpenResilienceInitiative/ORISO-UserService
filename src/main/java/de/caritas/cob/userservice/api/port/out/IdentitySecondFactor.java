package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.identity.IdentityEmailVerification;
import de.caritas.cob.userservice.api.identity.IdentityEmailVerificationStart;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import java.util.List;

public interface IdentitySecondFactor {

  IdentityOtpCredential getOtpCredential(String username);

  boolean setUpOtpCredential(String username, String initialCode, String secret);

  void deleteOtpCredential(String username);

  IdentityEmailVerificationStart initiateEmailVerification(String username, String email);

  /**
   * Pending/active mail-OTP targets from non-secret credential metadata, for preflight policy
   * checks.
   */
  List<String> emailVerificationAddresses(String userId);

  IdentityEmailVerification finishEmailVerification(String username, String initialCode);
}
