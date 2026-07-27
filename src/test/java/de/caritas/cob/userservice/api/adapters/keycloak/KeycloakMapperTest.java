package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.identity.IdentityEmailVerification;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.identity.IdentityOtpType;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.model.OtpType;
import de.caritas.cob.userservice.api.model.SuccessWithEmail;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;

class KeycloakMapperTest {

  private final KeycloakMapper mapper = new KeycloakMapper();

  @Test
  void identityOtpCredentialOfMapsGeneratedTransportToProviderNeutralValue() {
    var transport =
        new OtpInfoDTO()
            .otpSetup(true)
            .otpSecret("secret")
            .otpSecretQrCode("qr")
            .otpType(OtpType.APP);

    assertThat(mapper.identityOtpCredentialOf(transport))
        .isEqualTo(new IdentityOtpCredential(true, "secret", "qr", IdentityOtpType.APP));
  }

  @Test
  void identityOtpCredentialOfPreservesAbsentOptionalFields() {
    assertThat(mapper.identityOtpCredentialOf(new OtpInfoDTO()))
        .isEqualTo(IdentityOtpCredential.empty());
  }

  @Test
  void identityEmailVerificationOfMapsCreatedResponse() {
    var response =
        new ResponseEntity<>(
            new SuccessWithEmail().email("person@example.org"), HttpStatus.CREATED);

    assertThat(mapper.identityEmailVerificationOf(response))
        .isEqualTo(new IdentityEmailVerification(true, false, false, "person@example.org"));
  }

  @Test
  void identityEmailVerificationOfMapsRemainingAttemptsWithoutTransportMapKeys() {
    var exception = new HttpClientErrorException(HttpStatus.BAD_REQUEST);

    assertThat(mapper.identityEmailVerificationOf(exception))
        .isEqualTo(new IdentityEmailVerification(false, false, true, null));
  }

  @Test
  void identityEmailVerificationOfMapsExhaustedAttempts() {
    var exception = new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);

    assertThat(mapper.identityEmailVerificationOf(exception))
        .isEqualTo(new IdentityEmailVerification(false, false, false, null));
  }
}
