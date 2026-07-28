package de.caritas.cob.userservice.api;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.identity.IdentityEmailVerification;
import de.caritas.cob.userservice.api.identity.IdentityEmailVerificationStart;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityManager implements IdentityManaging {

  private final IdentityClient identityClient;
  private final IdentitySecondFactor identitySecondFactor;
  private final UsernameTranscoder usernameTranscoder;

  @Override
  public IdentityEmailVerificationStart setUpOneTimePassword(String username, String email) {
    return identitySecondFactor.initiateEmailVerification(username, email);
  }

  @Override
  public boolean setUpOneTimePassword(String username, String initialCode, String secret) {
    return identitySecondFactor.setUpOtpCredential(username, initialCode, secret);
  }

  @Override
  public IdentityEmailVerification validateOneTimePassword(String username, String code) {
    var validationResult = identitySecondFactor.finishEmailVerification(username, code);
    if (validationResult.created()) {
      var email = validationResult.email();
      identityClient.changeEmailAddress(usernameTranscoder.decodeUsername(username), email);
    }

    return validationResult;
  }

  @Override
  public boolean validatePasswordIgnoring2fa(String username, String password) {
    return identityClient.verifyIgnoringOtp(username, password);
  }

  @Override
  public boolean changePassword(String userId, String password) {
    return identityClient.changePassword(userId, password);
  }

  @Override
  public void changeLanguage(String userId, String language) {
    identityClient.changeLanguage(userId, language);
  }

  @Override
  public void deleteOneTimePassword(String username) {
    identitySecondFactor.deleteOtpCredential(username);
  }

  @Override
  public IdentityOtpCredential getOtpCredential(String username) {
    return identitySecondFactor.getOtpCredential(username);
  }

  @Override
  public boolean isEmailAvailableOrOwn(String username, String email) {
    var user = identityClient.findUserByEmail(email);

    return user.isEmpty()
        || username.equals(user.get("encodedUsername"))
        || usernameTranscoder.decodeUsername(username).equals(user.get("decodedUsername"));
  }
}
