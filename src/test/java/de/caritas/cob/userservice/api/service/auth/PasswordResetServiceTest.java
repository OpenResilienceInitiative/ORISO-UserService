package de.caritas.cob.userservice.api.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.PasswordResetApplication;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.auth.PasswordResetService.PasswordResetMailSender;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

  @Mock private UserService userService;
  @Mock private ConsultantService consultantService;
  @Mock private AdminRepository adminRepository;
  @Mock private IdentityPasswordUpdater identityPasswordUpdater;
  @Mock private OneTimeTokenStore oneTimeTokenStore;
  @Mock private OrisoEmailRenderer emailRenderer;
  @Mock private OrisoEmailBrand emailBrand;
  @Mock private PlatformSmtpSettingsProvider platformSmtpSettings;

  @InjectMocks private PasswordResetService passwordResetService;

  /** Captures every mail the service tries to send, without opening an SMTP socket. */
  private final List<SentMail> sentMails = new ArrayList<>();

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(passwordResetService, "emailDummySuffix", "@beratungcaritas.de");
    lenient()
        .when(platformSmtpSettings.requireConfigured())
        .thenReturn(
            new PlatformSmtpSettingsProvider.Settings(
                "deployment-smtp.example.org",
                587,
                false,
                "deployment-user",
                "deployment-pass",
                "noreply@example.org"));
    ReflectionTestUtils.setField(
        passwordResetService, "passwordResetFrontendBaseUrl", "https://app.oriso.org");
    ReflectionTestUtils.setField(
        passwordResetService, "passwordResetAdminFrontendBaseUrl", "https://admin.oriso.org/admin");
    // Run dispatch synchronously so request-flow assertions are deterministic.
    ReflectionTestUtils.setField(
        passwordResetService, "passwordResetExecutor", (Executor) Runnable::run);
    // Replace the real SMTP sender with a capturing seam — no network in tests.
    PasswordResetMailSender capturingSender =
        (recipient, locale, resetUrl, smtpSettings) ->
            sentMails.add(
                new SentMail(
                    recipient, locale, resetUrl, smtpSettings.host(), smtpSettings.from()));
    ReflectionTestUtils.setField(passwordResetService, "mailSender", capturingSender);
  }

  private record SentMail(
      String recipient, String locale, String resetUrl, String host, String from) {}

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
  void
      requestPasswordReset_Should_SendMailWithProperRecipientLocaleAndResetUrl_When_SmtpConfigured() {
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(validUser()));

    passwordResetService.requestPasswordReset("testuser", "en");

    assertThat(sentMails).hasSize(1);
    SentMail mail = sentMails.get(0);
    assertThat(mail.recipient()).isEqualTo("real@example.com");
    assertThat(mail.locale()).isEqualTo("en");
    assertThat(mail.host()).isEqualTo("deployment-smtp.example.org");
    assertThat(mail.from()).isEqualTo("noreply@example.org");
    // Reset URL must be built from the configured base URL and carry a 64-hex-char one-time token.
    assertThat(mail.resetUrl())
        .startsWith("https://app.oriso.org/password-reset/confirm?token=")
        .matches("https://app\\.oriso\\.org/password-reset/confirm\\?token=[0-9a-f]{64}");
  }

  @Test
  void requestPasswordReset_Should_SendAdminMailToAdminFrontend_When_ApplicationIsAdmin() {
    Admin admin =
        Admin.builder()
            .id("admin-keycloak-id")
            .username("admin@example.com")
            .firstName("Ada")
            .lastName("Admin")
            .email("admin@example.com")
            .type(Admin.AdminType.SUPER)
            .build();
    when(adminRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCase(
            "admin@example.com", "admin@example.com"))
        .thenReturn(Optional.of(admin));

    passwordResetService.requestPasswordReset(
        "admin@example.com", "en", PasswordResetApplication.ADMIN);

    assertThat(sentMails).hasSize(1);
    assertThat(sentMails.get(0).recipient()).isEqualTo("admin@example.com");
    assertThat(sentMails.get(0).resetUrl())
        .matches("https://admin\\.oriso\\.org/admin/password-reset/confirm\\?token=[0-9a-f]{64}");
    verify(userService, never()).findUserByUsername(anyString());
    verify(consultantService, never()).findConsultantByUsernameOrEmail(anyString(), anyString());
  }

  @Test
  void requestPasswordReset_Should_NotFallBackToAppAccounts_When_AdminIsUnknown() {
    when(adminRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCase("app-user", "app-user"))
        .thenReturn(Optional.empty());

    passwordResetService.requestPasswordReset("app-user", "de", PasswordResetApplication.ADMIN);

    assertThat(sentMails).isEmpty();
    verify(userService, never()).findUserByUsername(anyString());
    verify(consultantService, never()).findConsultantByUsernameOrEmail(anyString(), anyString());
  }

  @Test
  void requestPasswordReset_Should_NotSendAdminMail_When_AdminFrontendBaseUrlUnset() {
    ReflectionTestUtils.setField(passwordResetService, "passwordResetAdminFrontendBaseUrl", "");
    Admin admin =
        Admin.builder()
            .id("admin-keycloak-id")
            .username("admin")
            .firstName("Ada")
            .lastName("Admin")
            .email("admin@example.com")
            .type(Admin.AdminType.SUPER)
            .build();
    when(adminRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCase("admin", "admin"))
        .thenReturn(Optional.of(admin));

    passwordResetService.requestPasswordReset("admin", "de", PasswordResetApplication.ADMIN);

    assertThat(sentMails).isEmpty();
    verifyNoInteractions(platformSmtpSettings);
  }

  @Test
  void requestPasswordReset_Should_FallBackToGerman_When_LocaleIsUnknown() {
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(validUser()));

    passwordResetService.requestPasswordReset("testuser", "xx-unknown");

    assertThat(sentMails).hasSize(1);
    assertThat(sentMails.get(0).locale()).isEqualTo("de");
  }

  @Test
  void requestPasswordReset_Should_NotSendMail_When_FrontendBaseUrlUnset() {
    ReflectionTestUtils.setField(passwordResetService, "passwordResetFrontendBaseUrl", "");
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(validUser()));

    passwordResetService.requestPasswordReset("testuser", "en");

    // Fail closed: no base URL means no token or SMTP configuration lookup.
    assertThat(sentMails).isEmpty();
    verifyNoInteractions(platformSmtpSettings);
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

  @Test
  void requestPasswordReset_Should_NotIssueToken_When_DeploymentSmtpIsIncomplete() {
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(validUser()));
    when(platformSmtpSettings.requireConfigured())
        .thenThrow(
            new IllegalStateException(
                "Platform SMTP is not configured: smtp.password (SMTP_PASSWORD)"));

    passwordResetService.requestPasswordReset("testuser", "en");

    assertThat(sentMails).isEmpty();
    verify(oneTimeTokenStore, never())
        .store(anyString(), anyString(), anyString(), any(), anyBoolean());
  }

  // --- confirmPasswordReset ---

  @Test
  void confirmPasswordReset_Should_ReturnFalse_When_TokenIsBlank() {
    assertThat(passwordResetService.confirmPasswordReset("  ", "NewPassw0rd!")).isFalse();
    verify(identityPasswordUpdater, never()).updatePassword(anyString(), anyString());
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
  void confirmPasswordReset_Should_ReturnTrue_And_UpdatePassword_When_TokenValid() {
    OneTimeTokenStore.TokenClaim claim = validClaim();
    when(oneTimeTokenStore.claim("password-reset", "valid-token")).thenReturn(Optional.of(claim));

    boolean result = passwordResetService.confirmPasswordReset("valid-token", "NewPassw0rd!");

    assertThat(result).isTrue();
    verify(identityPasswordUpdater).updatePassword("user-keycloak-id", "NewPassw0rd!");
    verify(oneTimeTokenStore).claim("password-reset", "valid-token");
  }

  @Test
  void confirmPasswordReset_Should_KeepToken_When_KeycloakRejectsPasswordPolicy() {
    // Definitive policy rejection: Keycloak did NOT apply the password, so the token must
    // survive for a retry with a different password using the same emailed link.
    OneTimeTokenStore.TokenClaim claim = validClaim();
    when(oneTimeTokenStore.claim("password-reset", "retry-token")).thenReturn(Optional.of(claim));
    doThrow(
            new CustomValidationHttpStatusException(
                HttpStatusExceptionReason.PASSWORD_NOT_VALID, HttpStatus.BAD_REQUEST))
        .when(identityPasswordUpdater)
        .updatePassword("user-keycloak-id", "weak");

    assertThatThrownBy(() -> passwordResetService.confirmPasswordReset("retry-token", "weak"))
        .isInstanceOf(CustomValidationHttpStatusException.class);

    verify(oneTimeTokenStore).restore("password-reset", "retry-token", claim, true);
  }

  @Test
  void confirmPasswordReset_Should_ConsumeToken_When_UpdateFailsIndeterminately() {
    // A generic failure can occur AFTER Keycloak applied the password — the outcome is unknown,
    // so the token must stay consumed; restoring it could allow a second password change with an
    // already-used link.
    OneTimeTokenStore.TokenClaim claim = validClaim();
    when(oneTimeTokenStore.claim("password-reset", "indeterminate-token"))
        .thenReturn(Optional.of(claim));
    doThrow(new RuntimeException("connection reset"))
        .when(identityPasswordUpdater)
        .updatePassword("user-keycloak-id", "NewPassw0rd!");

    assertThatThrownBy(
            () -> passwordResetService.confirmPasswordReset("indeterminate-token", "NewPassw0rd!"))
        .isInstanceOf(RuntimeException.class);

    verify(oneTimeTokenStore, never())
        .restore("password-reset", "indeterminate-token", claim, true);
  }

  @Test
  void confirmPasswordReset_Should_ReturnFalse_When_TokenExpired() {
    when(oneTimeTokenStore.claim("password-reset", "expired-token")).thenReturn(Optional.empty());

    boolean result = passwordResetService.confirmPasswordReset("expired-token", "NewPassw0rd!");

    assertThat(result).isFalse();
    verify(identityPasswordUpdater, never()).updatePassword(anyString(), anyString());
  }

  @Test
  void confirmPasswordReset_Should_FailClosed_When_TokenStoreUnavailable() {
    when(oneTimeTokenStore.claim("password-reset", "token"))
        .thenThrow(new IllegalStateException("redis unavailable"));

    assertThat(passwordResetService.confirmPasswordReset("token", "NewPassw0rd!")).isFalse();
    verify(identityPasswordUpdater, never()).updatePassword(anyString(), anyString());
  }

  private User validUser() {
    User user = new User();
    user.setUserId("u-1");
    user.setUsername("testuser");
    user.setEmail("real@example.com");
    return user;
  }

  private OneTimeTokenStore.TokenClaim validClaim() {
    return new OneTimeTokenStore.TokenClaim("user-keycloak-id", Instant.now().plusSeconds(900));
  }
}
