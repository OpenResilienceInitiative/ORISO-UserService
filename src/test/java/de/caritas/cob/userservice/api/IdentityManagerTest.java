package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.identity.IdentityEmailVerification;
import de.caritas.cob.userservice.api.identity.IdentityEmailVerificationStart;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwner;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import java.util.List;
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
  @Mock private IdentityAuthentication identityAuthentication;
  @Mock private IdentityEmailOwnerLookup identityEmailOwnerLookup;
  @Mock private IdentityRoleLookup identityRoleLookup;
  @Mock private IdentitySecondFactor identitySecondFactor;
  @Mock private IdentityUsernameAvailability identityUsernameAvailability;
  @Spy private UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  @InjectMocks private IdentityManager identityManager;

  @Test
  void validatePasswordIgnoring2faShouldPreserveEncodedUsernameForIdentityAuthentication() {
    when(identityAuthentication.verifyPasswordIgnoringSecondFactor(ENCODED_USERNAME, "password"))
        .thenReturn(true);

    assertThat(identityManager.validatePasswordIgnoring2fa(ENCODED_USERNAME, "password")).isTrue();

    verify(identityAuthentication).verifyPasswordIgnoringSecondFactor(ENCODED_USERNAME, "password");
  }

  @Test
  void validateOneTimePasswordShouldUseRawUsernameForKeycloakEmailUpdate() {
    var encodedUsername = usernameTranscoder.encodeUsername(RAW_USERNAME);
    var validationResult = new IdentityEmailVerification(true, false, false, EMAIL);
    when(identitySecondFactor.finishEmailVerification(encodedUsername, "123456"))
        .thenReturn(validationResult);

    assertThat(identityManager.validateOneTimePassword(encodedUsername, "123456"))
        .isEqualTo(validationResult);

    verify(identitySecondFactor).finishEmailVerification(encodedUsername, "123456");
    verify(identityClient).changeEmailAddress(RAW_USERNAME, EMAIL);
  }

  @Test
  void validateOneTimePasswordShouldKeepAlreadyRawUsernameForKeycloakEmailUpdate() {
    var validationResult = new IdentityEmailVerification(true, false, false, EMAIL);
    when(identitySecondFactor.finishEmailVerification(RAW_USERNAME, "123456"))
        .thenReturn(validationResult);

    identityManager.validateOneTimePassword(RAW_USERNAME, "123456");

    verify(identityClient).changeEmailAddress(RAW_USERNAME, EMAIL);
  }

  @Test
  void initiateEmailVerificationShouldPreserveEncodedUsernameForFocusedPort() {
    var start = IdentityEmailVerificationStart.success();
    when(identitySecondFactor.initiateEmailVerification(ENCODED_USERNAME, EMAIL)).thenReturn(start);

    assertThat(identityManager.setUpOneTimePassword(ENCODED_USERNAME, EMAIL)).isSameAs(start);

    verify(identitySecondFactor).initiateEmailVerification(ENCODED_USERNAME, EMAIL);
  }

  @Test
  void setupOtpShouldPreserveEncodedUsernameForFocusedPort() {
    when(identitySecondFactor.setUpOtpCredential(ENCODED_USERNAME, "123456", "secret"))
        .thenReturn(true);

    assertThat(identityManager.setUpOneTimePassword(ENCODED_USERNAME, "123456", "secret")).isTrue();

    verify(identitySecondFactor).setUpOtpCredential(ENCODED_USERNAME, "123456", "secret");
  }

  @Test
  void getOtpCredentialShouldPreserveEncodedUsernameForFocusedPort() {
    var credential = IdentityOtpCredential.empty();
    when(identitySecondFactor.getOtpCredential(ENCODED_USERNAME)).thenReturn(credential);

    assertThat(identityManager.getOtpCredential(ENCODED_USERNAME)).isSameAs(credential);

    verify(identitySecondFactor).getOtpCredential(ENCODED_USERNAME);
  }

  @Test
  void deleteOtpCredentialShouldPreserveEncodedUsernameForFocusedPort() {
    identityManager.deleteOneTimePassword(ENCODED_USERNAME);

    verify(identitySecondFactor).deleteOtpCredential(ENCODED_USERNAME);
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
  void isUsernameAvailableShouldDelegateToFocusedAvailabilityPort() {
    when(identityUsernameAvailability.isUsernameAvailable(RAW_USERNAME)).thenReturn(true);

    assertThat(identityManager.isUsernameAvailable(RAW_USERNAME)).isTrue();

    verify(identityUsernameAvailability).isUsernameAvailable(RAW_USERNAME);
  }

  @Test
  void hasRoleShouldReadAllRolesOnceAndEvaluateTheApplicationRoleInProcess() {
    when(identityRoleLookup.findAllByUserId("consultant-id"))
        .thenReturn(List.of("user", "group-chat-consultant"));

    assertThat(identityManager.hasRole("consultant-id", UserRole.GROUP_CHAT_CONSULTANT)).isTrue();

    verify(identityRoleLookup).findAllByUserId("consultant-id");
  }
}
