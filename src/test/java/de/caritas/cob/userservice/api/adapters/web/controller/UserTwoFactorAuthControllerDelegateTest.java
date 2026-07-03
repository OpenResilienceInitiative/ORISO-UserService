package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.EmailDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.OneTimePasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class UserTwoFactorAuthControllerDelegateTest {

  private static final String USERNAME = "plain-user";
  private static final String ENCODED_USERNAME = "encoded-user";
  private static final String OTP = "123456";
  private static final String SECRET = "12345678901234567890123456789012";

  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private IdentityManaging identityManager;
  @Mock private AccountManaging accountManager;
  @Mock private UserDtoMapper userDtoMapper;
  @Mock private UsernameTranscoder usernameTranscoder;

  @InjectMocks private UserTwoFactorAuthControllerDelegate delegate;

  @Test
  void startTwoFactorAuthByEmailSetupShouldCreateOtpForNormalizedEmail() {
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(ENCODED_USERNAME);
    when(identityManager.isEmailAvailableOrOwn(ENCODED_USERNAME, "person@example.org"))
        .thenReturn(true);
    when(identityManager.setUpOneTimePassword(ENCODED_USERNAME, "person@example.org"))
        .thenReturn(Optional.empty());

    var response = delegate.startTwoFactorAuthByEmailSetup(new EmailDTO("PERSON@EXAMPLE.ORG"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(identityManager).setUpOneTimePassword(ENCODED_USERNAME, "person@example.org");
  }

  @Test
  void startTwoFactorAuthByEmailSetupShouldThrowProjectExceptionWhenOtpSetupFails() {
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(ENCODED_USERNAME);
    when(identityManager.isEmailAvailableOrOwn(ENCODED_USERNAME, "person@example.org"))
        .thenReturn(true);
    when(identityManager.setUpOneTimePassword(ENCODED_USERNAME, "person@example.org"))
        .thenReturn(Optional.of("OTP setup failed"));

    assertThatThrownBy(
            () -> delegate.startTwoFactorAuthByEmailSetup(new EmailDTO("PERSON@EXAMPLE.ORG")))
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessage("OTP setup failed");
  }

  @Test
  void startTwoFactorAuthByEmailSetupShouldReturnPreconditionFailedWhenEmailIsUnavailable() {
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(ENCODED_USERNAME);
    when(identityManager.isEmailAvailableOrOwn(ENCODED_USERNAME, "person@example.org"))
        .thenReturn(false);

    var response = delegate.startTwoFactorAuthByEmailSetup(new EmailDTO("PERSON@EXAMPLE.ORG"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
    verify(identityManager, never()).setUpOneTimePassword(ENCODED_USERNAME, "person@example.org");
  }

  @Test
  void finishTwoFactorAuthByEmailSetupShouldPatchUserWhenOtpWasCreated() {
    var patchMap = Map.<String, Object>of("email", "person@example.org");
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(ENCODED_USERNAME);
    when(identityManager.validateOneTimePassword(ENCODED_USERNAME, OTP))
        .thenReturn(Map.of("created", "true", "email", "person@example.org"));
    when(userDtoMapper.mapOf("person@example.org", authenticatedUser)).thenReturn(patchMap);

    var response = delegate.finishTwoFactorAuthByEmailSetup(OTP);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(accountManager).patchUser(patchMap);
  }

  @Test
  void finishTwoFactorAuthByEmailSetupShouldReturnBadRequestWhenAttemptsRemain() {
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(ENCODED_USERNAME);
    when(identityManager.validateOneTimePassword(ENCODED_USERNAME, OTP))
        .thenReturn(Map.of("created", "false", "attemptsLeft", "true"));

    var response = delegate.finishTwoFactorAuthByEmailSetup(OTP);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    verifyNoInteractions(accountManager);
  }

  @Test
  void finishTwoFactorAuthByEmailSetupShouldReturnPreconditionFailedWhenOtpAlreadyCreated() {
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(ENCODED_USERNAME);
    when(identityManager.validateOneTimePassword(ENCODED_USERNAME, OTP))
        .thenReturn(Map.of("created", "false", "attemptsLeft", "false", "createdBefore", "true"));

    var response = delegate.finishTwoFactorAuthByEmailSetup(OTP);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
    verifyNoInteractions(accountManager);
  }

  @Test
  void finishTwoFactorAuthByEmailSetupShouldReturnTooManyRequestsWhenAttemptsAreExhausted() {
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(ENCODED_USERNAME);
    when(identityManager.validateOneTimePassword(ENCODED_USERNAME, OTP))
        .thenReturn(Map.of("created", "false", "attemptsLeft", "false", "createdBefore", "false"));

    var response = delegate.finishTwoFactorAuthByEmailSetup(OTP);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    verifyNoInteractions(accountManager);
  }

  @Test
  void activateTwoFactorAuthByAppShouldRejectAdviceSeekerWhenOtpIsDisabledForUsers() {
    when(authenticatedUser.isAdviceSeeker()).thenReturn(true);
    when(identityClientConfig.getOtpAllowedForUsers()).thenReturn(false);

    assertThatThrownBy(() -> delegate.activateTwoFactorAuthByApp(oneTimePassword()))
        .isInstanceOf(ConflictException.class)
        .hasMessage("2FA is disabled for user role");

    verifyNoInteractions(identityManager);
  }

  @Test
  void activateTwoFactorAuthByAppShouldReturnOkWhenOtpIsAccepted() {
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(ENCODED_USERNAME);
    when(identityManager.setUpOneTimePassword(ENCODED_USERNAME, OTP, SECRET)).thenReturn(true);

    var response = delegate.activateTwoFactorAuthByApp(oneTimePassword());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void activateTwoFactorAuthByAppShouldReturnBadRequestWhenOtpIsRejected() {
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(ENCODED_USERNAME);
    when(identityManager.setUpOneTimePassword(ENCODED_USERNAME, OTP, SECRET)).thenReturn(false);

    var response = delegate.activateTwoFactorAuthByApp(oneTimePassword());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void deactivateTwoFactorAuthByAppShouldDeleteOtpForAuthenticatedUser() {
    when(authenticatedUser.getUsername()).thenReturn(USERNAME);
    when(usernameTranscoder.encodeUsername(USERNAME)).thenReturn(ENCODED_USERNAME);

    var response = delegate.deactivateTwoFactorAuthByApp();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(identityManager).deleteOneTimePassword(ENCODED_USERNAME);
  }

  private OneTimePasswordDTO oneTimePassword() {
    return new OneTimePasswordDTO(SECRET, OTP);
  }
}
