package de.caritas.cob.userservice.api;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.identity.IdentityEmailVerification;
import de.caritas.cob.userservice.api.identity.IdentityEmailVerificationStart;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityEmailAddressUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityManager implements IdentityManaging {

  private final IdentityClient identityClient;
  private final IdentityEmailAddressUpdater identityEmailAddressUpdater;
  private final IdentityAuthentication identityAuthentication;
  private final IdentityEmailOwnerLookup identityEmailOwnerLookup;
  private final IdentityRoleLookup identityRoleLookup;
  private final IdentitySecondFactor identitySecondFactor;
  private final IdentityUsernameAvailability identityUsernameAvailability;
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
      identityEmailAddressUpdater.updateEmailByUsername(
          usernameTranscoder.decodeUsername(username), email);
    }

    return validationResult;
  }

  @Override
  public boolean validatePasswordIgnoring2fa(String username, String password) {
    return identityAuthentication.verifyPasswordIgnoringSecondFactor(username, password);
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
  public boolean isUsernameAvailable(String username) {
    return identityUsernameAvailability.isUsernameAvailable(username);
  }

  @Override
  public boolean isEmailAvailableOrOwn(String username, String email) {
    var owner = identityEmailOwnerLookup.findByEmail(email);
    if (owner.isEmpty()) {
      return true;
    }

    var ownerUsername = owner.orElseThrow().username();
    return ownerUsername != null
        && (username.equals(ownerUsername)
            || usernameTranscoder
                .decodeUsername(username)
                .equals(usernameTranscoder.decodeUsername(ownerUsername)));
  }

  @Override
  public boolean hasRole(String userId, UserRole role) {
    return identityRoleLookup.findAllByUserId(userId).stream().anyMatch(role.getValue()::equals);
  }
}
