package de.caritas.cob.userservice.api.adapters.keycloak;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.identity.IdentityEmailVerification;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.identity.IdentityOtpType;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.model.OtpSetupDTO;
import de.caritas.cob.userservice.api.model.SuccessWithEmail;
import java.util.Map;
import java.util.Objects;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

@Service
public class KeycloakMapper {

  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  public OtpSetupDTO otpSetupDtoOf(String initialCode, String secret, String email) {
    var otpSetupDTO = new OtpSetupDTO();
    otpSetupDTO.setSecret(secret);
    otpSetupDTO.setInitialCode(initialCode);
    otpSetupDTO.setEmail(email);

    return otpSetupDTO;
  }

  public UserRepresentation userRepresentationOf(String locale) {
    return new UserRepresentation().singleAttribute("locale", locale);
  }

  public IdentityOtpCredential identityOtpCredentialOf(OtpInfoDTO otpInfo) {
    var type =
        otpInfo.getOtpType() == null
            ? null
            : IdentityOtpType.valueOf(otpInfo.getOtpType().getValue());
    return new IdentityOtpCredential(
        otpInfo.getOtpSetup(), otpInfo.getOtpSecret(), otpInfo.getOtpSecretQrCode(), type);
  }

  public IdentityEmailVerification identityEmailVerificationOf(
      ResponseEntity<SuccessWithEmail> responseEntity) {
    var status = responseEntity.getStatusCode();
    var isCreated = status.equals(HttpStatus.CREATED);
    var hasBeenCreatedBefore = status.equals(HttpStatus.OK);
    var hasBeenTriedTooOften = status.equals(HttpStatus.TOO_MANY_REQUESTS);

    return new IdentityEmailVerification(
        isCreated,
        hasBeenCreatedBefore,
        !hasBeenTriedTooOften && !isCreated && !hasBeenCreatedBefore,
        Objects.requireNonNull(responseEntity.getBody()).getEmail());
  }

  public IdentityEmailVerification identityEmailVerificationOf(HttpClientErrorException exception) {
    var status = exception.getStatusCode();

    return new IdentityEmailVerification(
        false, false, !status.equals(HttpStatus.TOO_MANY_REQUESTS), null);
  }

  public Map<String, String> mapOf(UserRepresentation userRepresentation) {
    var username = userRepresentation.getUsername();

    return Map.of(
        "encodedUsername", username,
        "decodedUsername", usernameTranscoder.decodeUsername(username),
        "email", userRepresentation.getEmail());
  }
}
