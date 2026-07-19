package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
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
}
