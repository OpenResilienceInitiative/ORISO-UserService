package de.caritas.cob.userservice.api.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordResetServiceTest {

  @Mock private UserService userService;
  @Mock private ConsultantService consultantService;
  @Mock private KeycloakService keycloakService;
  @Mock private RestTemplate restTemplate;

  @InjectMocks private PasswordResetService passwordResetService;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(passwordResetService, "emailDummySuffix", "@beratungcaritas.de");
    ReflectionTestUtils.setField(passwordResetService, "consultingTypeServiceApiUrl", "");
    ReflectionTestUtils.setField(
        passwordResetService, "passwordResetFrontendBaseUrl", "https://app.oriso.org");
  }

  // --- requestPasswordReset ---

  @Test
  void requestPasswordReset_Should_DoNothing_When_UsernameIsBlank() {
    assertThatCode(() -> passwordResetService.requestPasswordReset("  ", "de"))
        .doesNotThrowAnyException();
    verify(userService, never()).findUserByUsername(anyString());
  }

  @Test
  void requestPasswordReset_Should_DoNothing_When_UsernameIsNull() {
    assertThatCode(() -> passwordResetService.requestPasswordReset(null, "de"))
        .doesNotThrowAnyException();
  }

  @Test
  void requestPasswordReset_Should_CompleteSilently_When_AccountNotFound() {
    when(userService.findUserByUsername(anyString())).thenReturn(Optional.empty());
    when(consultantService.findConsultantByUsernameOrEmail(anyString(), anyString()))
        .thenReturn(Optional.empty());

    assertThatCode(() -> passwordResetService.requestPasswordReset("unknown-user", "de"))
        .doesNotThrowAnyException();
  }

  @Test
  void requestPasswordReset_Should_CompleteSilently_When_EmailIsBlank() {
    User user = new User();
    user.setUserId("u-1");
    user.setUsername("testuser");
    user.setEmail("  ");
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(user));

    assertThatCode(() -> passwordResetService.requestPasswordReset("testuser", "de"))
        .doesNotThrowAnyException();
  }

  @Test
  void requestPasswordReset_Should_CompleteSilently_When_EmailEndsWithDummySuffix() {
    User user = new User();
    user.setUserId("u-1");
    user.setUsername("testuser");
    user.setEmail("testuser@beratungcaritas.de");
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(user));

    assertThatCode(() -> passwordResetService.requestPasswordReset("testuser", "de"))
        .doesNotThrowAnyException();
  }

  @Test
  void requestPasswordReset_Should_CompleteSilently_When_SmtpNotConfigured() {
    User user = validUser();
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(user));

    // consultingTypeServiceApiUrl is blank (set in @BeforeEach) → SMTP not available
    assertThatCode(() -> passwordResetService.requestPasswordReset("testuser", "de"))
        .doesNotThrowAnyException();
  }

  @Test
  @SuppressWarnings("unchecked")
  void requestPasswordReset_Should_AttemptSmtpSend_When_ValidSmtpSettingsReturned() {
    ReflectionTestUtils.setField(passwordResetService, "consultingTypeServiceApiUrl", "http://cts");
    User user = validUser();
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(user));
    when(restTemplate.getForObject(anyString(), any()))
        .thenReturn(
            Map.of(
                "globalFeatureSystemNotificationEmailsEnabled", true,
                "globalSmtpEnabled", true,
                "globalSmtpHost", "smtp.invalid",
                "globalSmtpPort", 587,
                "globalSmtpUsername", "user",
                "globalSmtpPassword", "pass",
                "globalSmtpFrom", "noreply@example.com"));

    // Transport.send fails against smtp.invalid but the exception is caught, never rethrown.
    assertThatCode(() -> passwordResetService.requestPasswordReset("testuser", "en"))
        .doesNotThrowAnyException();
  }

  @Test
  @SuppressWarnings("unchecked")
  void requestPasswordReset_Should_FallBackToGerman_When_LocaleIsUnknown() {
    ReflectionTestUtils.setField(passwordResetService, "consultingTypeServiceApiUrl", "http://cts");
    User user = validUser();
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(user));
    when(restTemplate.getForObject(anyString(), any()))
        .thenReturn(
            Map.of(
                "globalFeatureSystemNotificationEmailsEnabled", true,
                "globalSmtpEnabled", true,
                "globalSmtpHost", "smtp.invalid",
                "globalSmtpPort", 587,
                "globalSmtpUsername", "user",
                "globalSmtpPassword", "pass",
                "globalSmtpFrom", "noreply@example.com"));

    assertThatCode(() -> passwordResetService.requestPasswordReset("testuser", "xx-unknown"))
        .doesNotThrowAnyException();
  }

  @Test
  void requestPasswordReset_Should_ResolveViaConsultant_When_UserNotFound() {
    when(userService.findUserByUsername(anyString())).thenReturn(Optional.empty());
    Consultant consultant = new Consultant();
    consultant.setId("c-1");
    consultant.setUsername("consultant1");
    consultant.setEmail("consultant@example.com");
    when(consultantService.findConsultantByUsernameOrEmail(anyString(), anyString()))
        .thenReturn(Optional.of(consultant));

    // SMTP not configured → completes silently, but proves consultant path was taken (no NPE)
    assertThatCode(() -> passwordResetService.requestPasswordReset("consultant1", "de"))
        .doesNotThrowAnyException();
  }

  // --- confirmPasswordReset ---

  @Test
  void confirmPasswordReset_Should_ReturnFalse_When_TokenIsBlank() {
    assertThat(passwordResetService.confirmPasswordReset("  ", "NewPassw0rd!")).isFalse();
    verify(keycloakService, never()).updatePassword(anyString(), anyString());
  }

  @Test
  void confirmPasswordReset_Should_ReturnFalse_When_NewPasswordIsBlank() {
    assertThat(passwordResetService.confirmPasswordReset("some-token", "  ")).isFalse();
  }

  @Test
  void confirmPasswordReset_Should_ReturnFalse_When_TokenNotFound() {
    assertThat(passwordResetService.confirmPasswordReset("non-existent-token", "NewPassw0rd!"))
        .isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void confirmPasswordReset_Should_ReturnTrue_And_UpdatePassword_When_TokenValid() {
    ConcurrentHashMap<String, Object> tokens = injectToken("valid-token", 900);

    boolean result = passwordResetService.confirmPasswordReset("valid-token", "NewPassw0rd!");

    assertThat(result).isTrue();
    verify(keycloakService).updatePassword("user-keycloak-id", "NewPassw0rd!");
    // Token must be single-use.
    assertThat(tokens).doesNotContainKey("valid-token");
  }

  @Test
  @SuppressWarnings("unchecked")
  void confirmPasswordReset_Should_KeepToken_When_KeycloakRejectsPassword() {
    // If Keycloak rejects the new password (e.g. policy violation), the token must survive so
    // the user can retry with a different password using the same emailed link — it must not be
    // burned on a failed attempt.
    ConcurrentHashMap<String, Object> tokens = injectToken("retry-token", 900);
    doThrow(new RuntimeException("password policy violation"))
        .when(keycloakService)
        .updatePassword("user-keycloak-id", "weak");

    assertThatThrownBy(() -> passwordResetService.confirmPasswordReset("retry-token", "weak"))
        .isInstanceOf(RuntimeException.class);

    assertThat(tokens).containsKey("retry-token");
  }

  @Test
  @SuppressWarnings("unchecked")
  void confirmPasswordReset_Should_ReturnFalse_When_TokenExpired() {
    injectToken("expired-token", -60);

    boolean result = passwordResetService.confirmPasswordReset("expired-token", "NewPassw0rd!");

    assertThat(result).isFalse();
    verify(keycloakService, never()).updatePassword(anyString(), anyString());
  }

  @Test
  @SuppressWarnings("unchecked")
  void confirmPasswordReset_Should_CleanupExpiredTokens_Before_Lookup() {
    ConcurrentHashMap<String, Object> tokens = injectToken("stale-token", -60);
    assertThat(tokens).containsKey("stale-token");

    passwordResetService.confirmPasswordReset("any-other-token", "NewPassw0rd!");

    assertThat(tokens).doesNotContainKey("stale-token");
  }

  private User validUser() {
    User user = new User();
    user.setUserId("u-1");
    user.setUsername("testuser");
    user.setEmail("real@example.com");
    return user;
  }

  @SuppressWarnings("unchecked")
  private ConcurrentHashMap<String, Object> injectToken(String token, long offsetSeconds) {
    ConcurrentHashMap<String, Object> tokens =
        (ConcurrentHashMap<String, Object>)
            ReflectionTestUtils.getField(passwordResetService, "resetTokens");
    for (Class<?> cls : PasswordResetService.class.getDeclaredClasses()) {
      if (cls.getSimpleName().equals("ResetTokenEntry")) {
        try {
          cls.getDeclaredConstructors()[0].setAccessible(true);
          Object entry =
              cls.getDeclaredConstructors()[0].newInstance(
                  "user-keycloak-id", Instant.now().plusSeconds(offsetSeconds));
          tokens.put(token, entry);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
    return tokens;
  }
}
