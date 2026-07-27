package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwner;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import java.util.Map;
import java.util.Optional;
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
  @Mock private IdentityEmailOwnerLookup identityEmailOwnerLookup;
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
    when(identityEmailOwnerLookup.findByEmail(EMAIL))
        .thenReturn(Optional.of(new IdentityEmailOwner(RAW_USERNAME)));

    assertThat(identityManager.isEmailAvailableOrOwn(ENCODED_USERNAME, EMAIL)).isTrue();
  }

  @Test
  void isEmailAvailableOrOwnShouldAcceptEncodedKeycloakUsernameForEncodedConsultant() {
    when(identityEmailOwnerLookup.findByEmail(EMAIL))
        .thenReturn(Optional.of(new IdentityEmailOwner(ENCODED_USERNAME)));

    assertThat(identityManager.isEmailAvailableOrOwn(ENCODED_USERNAME, EMAIL)).isTrue();
  }

  @Test
  void isEmailAvailableOrOwnShouldRejectEmailOwnedByDifferentUser() {
    when(identityEmailOwnerLookup.findByEmail(EMAIL))
        .thenReturn(Optional.of(new IdentityEmailOwner("other-user")));

    assertThat(identityManager.isEmailAvailableOrOwn(ENCODED_USERNAME, EMAIL)).isFalse();
  }

  @Test
  void isEmailAvailableOrOwnShouldAcceptUnusedEmail() {
    when(identityEmailOwnerLookup.findByEmail(EMAIL)).thenReturn(Optional.empty());

    assertThat(identityManager.isEmailAvailableOrOwn(ENCODED_USERNAME, EMAIL)).isTrue();
  }

  @Test
  void isEmailAvailableOrOwnShouldRejectIncompleteOwnerDataWithoutThrowing() {
    when(identityEmailOwnerLookup.findByEmail(EMAIL))
        .thenReturn(Optional.of(new IdentityEmailOwner(null)));

    assertThat(identityManager.isEmailAvailableOrOwn(ENCODED_USERNAME, EMAIL)).isFalse();
  }

  @Test
  void isUsernameAvailableShouldDelegateToIdentityClient() {
    when(identityClient.isUsernameAvailable(RAW_USERNAME)).thenReturn(true);

    assertThat(identityManager.isUsernameAvailable(RAW_USERNAME)).isTrue();

    verify(identityClient).isUsernameAvailable(RAW_USERNAME);
  }

  @Test
  void hasRoleShouldDelegateTheApplicationRoleValueToIdentityClient() {
    when(identityClient.userHasRole("consultant-id", "group-chat-consultant")).thenReturn(true);

    assertThat(identityManager.hasRole("consultant-id", UserRole.GROUP_CHAT_CONSULTANT)).isTrue();

    verify(identityClient).userHasRole("consultant-id", "group-chat-consultant");
  }
}
