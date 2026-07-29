package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityManagerTest {

  private static final String RAW_USERNAME = "consultant2fa";
  private static final String ENCODED_USERNAME = "enc.MNXW443VNR2GC3TUGJTGC...";
  private static final String EMAIL = "consultant@example.org";

  @Mock private IdentityClient identityClient;
  @Spy private UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  @InjectMocks private IdentityManager identityManager;

  @Test
  void validateOneTimePasswordShouldUseRawUsernameForKeycloakEmailUpdate() {
    var encodedUsername = usernameTranscoder.encodeUsername(RAW_USERNAME);
    var validationResult = Map.of("created", "true", "email", EMAIL);
    when(identityClient.finishEmailVerification(encodedUsername, "123456"))
        .thenReturn(validationResult);

    assertThat(identityManager.validateOneTimePassword(encodedUsername, "123456"))
        .isEqualTo(validationResult);

    verify(identityClient).finishEmailVerification(encodedUsername, "123456");
    verify(identityClient).changeEmailAddress(RAW_USERNAME, EMAIL);
  }

  @Test
  void validateOneTimePasswordShouldKeepAlreadyRawUsernameForKeycloakEmailUpdate() {
    var validationResult = Map.of("created", "true", "email", EMAIL);
    when(identityClient.finishEmailVerification(RAW_USERNAME, "123456"))
        .thenReturn(validationResult);

    identityManager.validateOneTimePassword(RAW_USERNAME, "123456");

    verify(identityClient).changeEmailAddress(RAW_USERNAME, EMAIL);
  }

  @Test
  void isEmailAvailableOrOwnShouldAcceptRawKeycloakUsernameForEncodedConsultant() {
    when(identityClient.findUserByEmail(EMAIL))
        .thenReturn(
            Map.of(
                "encodedUsername", RAW_USERNAME,
                "decodedUsername", RAW_USERNAME,
                "email", EMAIL));

    assertThat(identityManager.isEmailAvailableOrOwn(ENCODED_USERNAME, EMAIL)).isTrue();
  }

  @Test
  void isEmailAvailableOrOwnShouldAcceptEncodedKeycloakUsernameForEncodedConsultant() {
    when(identityClient.findUserByEmail(EMAIL))
        .thenReturn(
            Map.of(
                "encodedUsername", ENCODED_USERNAME,
                "decodedUsername", RAW_USERNAME,
                "email", EMAIL));

    assertThat(identityManager.isEmailAvailableOrOwn(ENCODED_USERNAME, EMAIL)).isTrue();
  }

  @Test
  void isEmailAvailableOrOwnShouldRejectEmailOwnedByDifferentUser() {
    when(identityClient.findUserByEmail(EMAIL))
        .thenReturn(
            Map.of(
                "encodedUsername", "other-user",
                "decodedUsername", "other-user",
                "email", EMAIL));

    assertThat(identityManager.isEmailAvailableOrOwn(ENCODED_USERNAME, EMAIL)).isFalse();
  }

  @Test
  void isEmailAvailableOrOwnShouldAcceptUnusedEmail() {
    when(identityClient.findUserByEmail(EMAIL)).thenReturn(Map.of());

    assertThat(identityManager.isEmailAvailableOrOwn(ENCODED_USERNAME, EMAIL)).isTrue();
  }

  @Test
  void isEmailAvailableOrOwnShouldRejectIncompleteOwnerDataWithoutThrowing() {
    when(identityClient.findUserByEmail(EMAIL)).thenReturn(Map.of("email", EMAIL));

    assertThat(identityManager.isEmailAvailableOrOwn(ENCODED_USERNAME, EMAIL)).isFalse();
  }

  @Test
  void isUsernameAvailableShouldDelegateToIdentityClient() {
    when(identityClient.isUsernameAvailable(RAW_USERNAME)).thenReturn(true);

    assertThat(identityManager.isUsernameAvailable(RAW_USERNAME)).isTrue();

    verify(identityClient).isUsernameAvailable(RAW_USERNAME);
  }
}
