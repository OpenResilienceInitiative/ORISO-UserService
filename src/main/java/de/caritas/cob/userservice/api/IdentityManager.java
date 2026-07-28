package de.caritas.cob.userservice.api;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityManager implements IdentityManaging {

  private final IdentityClient identityClient;
  private final IdentityEmailOwnerLookup identityEmailOwnerLookup;
  private final UsernameTranscoder usernameTranscoder;

  @Override
  public Optional<String> setUpOneTimePassword(String username, String email) {
    return identityClient.initiateEmailVerification(username, email);
  }

  @Override
  public boolean setUpOneTimePassword(String username, String initialCode, String secret) {
    return identityClient.setUpOtpCredential(username, initialCode, secret);
  }

  @Override
  public Map<String, String> validateOneTimePassword(String username, String code) {
    var validationResult = identityClient.finishEmailVerification(username, code);
    if (validationResult.get("created").equals("true")) {
      var email = validationResult.get("email");
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
    identityClient.deleteOtpCredential(username);
  }

  @Override
  public OtpInfoDTO getOtpCredential(String username) {
    return identityClient.getOtpCredential(username);
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
}
