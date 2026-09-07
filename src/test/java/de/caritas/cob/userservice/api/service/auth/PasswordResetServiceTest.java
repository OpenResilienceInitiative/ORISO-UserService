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
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsSmtpCredentialsDTO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

  @Mock private UserService userService;
  @Mock private ConsultantService consultantService;
  @Mock private AdminRepository adminRepository;
  @Mock private IdentityPasswordUpdater identityPasswordUpdater;
  @Mock private RestTemplate restTemplate;
  @Mock private OneTimeTokenStore oneTimeTokenStore;
  @Mock private ApplicationSettingsService applicationSettingsService;
  @Mock private OrisoEmailRenderer emailRenderer;
  @Mock private OrisoEmailBrand emailBrand;

  @InjectMocks private PasswordResetService passwordResetService;

  /** Captures every mail the service tries to send, without opening an SMTP socket. */
  private final List<SentMail> sentMails = new ArrayList<>();

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(passwordResetService, "emailDummySuffix", "@beratungcaritas.de");
    ReflectionTestUtils.setField(passwordResetService, "consultingTypeServiceApiUrl", "");
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
            sentMails.add(new SentMail(recipient, locale, resetUrl));
    ReflectionTestUtils.setField(passwordResetService, "mailSender", capturingSender);
  }

  @Test
  void requestPasswordReset_preservesRequestScopeAcrossWorkerAndClearsAfterFailure()
      throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    var observed = new CopyOnWriteArrayList<String>();
    var releaseWorker = new CountDownLatch(1);
    ReflectionTestUtils.setField(passwordResetService, "passwordResetExecutor", worker);
    when(userService.findUserByUsername(anyString()))
        .thenAnswer(
            invocation -> {
              observed.add(
                  TenantContext.getCurrentTenant()
                      + ":"
                      + (TenantContext.getCurrentTenantData() == null
                          ? null
                          : TenantContext.getCurrentTenantData().getSubdomain()));
              throw new IllegalStateException("synthetic lookup failure");
            });
    try {
      worker.submit(
          () -> {
            releaseWorker.await(5, TimeUnit.SECONDS);
            return null;
          });
      TenantContext.setCurrentTenantData(new TenantData(40L, "springfield"));
      passwordResetService.requestPasswordReset("test-consultant", "de");
      TenantContext.setCurrentTenant(1L);
      TenantContext.setCurrentSubdomain("main");
      releaseWorker.countDown();
      worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertThat(TenantContext.getCurrentTenant()).isEqualTo(1L);
      assertThat(worker.submit(TenantContext::getCurrentTenant).get(5, TimeUnit.SECONDS)).isNull();
      TenantContext.clear();
      passwordResetService.requestPasswordReset("test-consultant", "de");
      worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertThat(observed).containsExactly("40:springfield", "null:null");
      assertThat(worker.submit(TenantContext::getCurrentTenant).get(5, TimeUnit.SECONDS)).isNull();
    } finally {
      TenantContext.clear();
      worker.shutdownNow();
    }
  }

  @Test
  void requestPasswordReset_keepsEachRecipientsScopeThroughMailDispatch() throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    var deliveries = new CopyOnWriteArrayList<String>();
    ReflectionTestUtils.setField(passwordResetService, "passwordResetExecutor", worker);
    ReflectionTestUtils.setField(passwordResetService, "consultingTypeServiceApiUrl", "http://cts");
    when(userService.findUserByUsername(anyString()))
        .thenAnswer(
            invocation -> {
              User user = validUser();
              user.setEmail(invocation.getArgument(0) + "@example.com");
              return Optional.of(user);
            });
    when(restTemplate.getForObject(anyString(), any())).thenReturn(validSmtpSettings());
    when(applicationSettingsService.getGlobalSmtpCredentials())
        .thenReturn(Optional.of(smtpCredentials("smtp-user", "smtp-pass")));
    PasswordResetMailSender sender =
        (recipient, locale, resetUrl, smtp) ->
            deliveries.add(recipient + ":" + TenantContext.getCurrentTenant());
    ReflectionTestUtils.setField(passwordResetService, "mailSender", sender);
    try {
      TenantContext.setCurrentTenant(1L);
      passwordResetService.requestPasswordReset("main-actor", "de");
      TenantContext.setCurrentTenant(40L);
      passwordResetService.requestPasswordReset("springfield-actor", "de");
      worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertThat(deliveries)
          .containsExactly("main-actor@example.com:1", "springfield-actor@example.com:40");
      assertThat(worker.submit(TenantContext::getCurrentTenant).get(5, TimeUnit.SECONDS)).isNull();
    } finally {
      TenantContext.clear();
      worker.shutdownNow();
    }
  }

  private record SentMail(String recipient, String locale, String resetUrl) {}

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
  void
      requestPasswordReset_Should_SendMailWithProperRecipientLocaleAndResetUrl_When_SmtpConfigured() {
    ReflectionTestUtils.setField(passwordResetService, "consultingTypeServiceApiUrl", "http://cts");
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(validUser()));
    when(restTemplate.getForObject(anyString(), any())).thenReturn(validSmtpSettings());
    when(applicationSettingsService.getGlobalSmtpCredentials())
        .thenReturn(Optional.of(smtpCredentials("smtp-user", "smtp-pass")));

    passwordResetService.requestPasswordReset("testuser", "en");

    assertThat(sentMails).hasSize(1);
    SentMail mail = sentMails.get(0);
    assertThat(mail.recipient()).isEqualTo("real@example.com");
    assertThat(mail.locale()).isEqualTo("en");
    // Reset URL must be built from the configured base URL and carry a 64-hex-char one-time token.
    assertThat(mail.resetUrl())
        .startsWith("https://app.oriso.org/password-reset/confirm?token=")
        .matches("https://app\\.oriso\\.org/password-reset/confirm\\?token=[0-9a-f]{64}");
  }

  @Test
  void requestPasswordReset_Should_SendAdminMailToAdminFrontend_When_ApplicationIsAdmin() {
    ReflectionTestUtils.setField(passwordResetService, "consultingTypeServiceApiUrl", "http://cts");
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
    when(restTemplate.getForObject(anyString(), any())).thenReturn(validSmtpSettings());
    when(applicationSettingsService.getGlobalSmtpCredentials())
        .thenReturn(Optional.of(smtpCredentials("smtp-user", "smtp-pass")));

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
    ReflectionTestUtils.setField(passwordResetService, "consultingTypeServiceApiUrl", "http://cts");
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
    verify(restTemplate, never()).getForObject(anyString(), any());
  }

  @Test
  void requestPasswordReset_Should_FallBackToGerman_When_LocaleIsUnknown() {
    ReflectionTestUtils.setField(passwordResetService, "consultingTypeServiceApiUrl", "http://cts");
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(validUser()));
    when(restTemplate.getForObject(anyString(), any())).thenReturn(validSmtpSettings());
    when(applicationSettingsService.getGlobalSmtpCredentials())
        .thenReturn(Optional.of(smtpCredentials("smtp-user", "smtp-pass")));

    passwordResetService.requestPasswordReset("testuser", "xx-unknown");

    assertThat(sentMails).hasSize(1);
    assertThat(sentMails.get(0).locale()).isEqualTo("de");
  }

  @Test
  void requestPasswordReset_Should_NotSendMail_When_FrontendBaseUrlUnset() {
    ReflectionTestUtils.setField(passwordResetService, "passwordResetFrontendBaseUrl", "");
    ReflectionTestUtils.setField(passwordResetService, "consultingTypeServiceApiUrl", "http://cts");
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(validUser()));

    passwordResetService.requestPasswordReset("testuser", "en");

    // Fail closed: no base URL -> no mail, and restTemplate is never even consulted.
    assertThat(sentMails).isEmpty();
    verify(restTemplate, never()).getForObject(anyString(), any());
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

  // The public /settings payload deliberately omits globalSmtpUsername/globalSmtpPassword since the
  // CTS-C01 credential-leak fix. Credentials must therefore come from the authenticated source.
  @Test
  void
      requestPasswordReset_Should_SendMail_When_PublicSettingsOmitCredentialsButAuthenticatedSourceHasThem() {
    ReflectionTestUtils.setField(passwordResetService, "consultingTypeServiceApiUrl", "http://cts");
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(validUser()));
    when(restTemplate.getForObject(anyString(), any()))
        .thenReturn(publicSmtpSettingsWithoutCredentials());
    when(applicationSettingsService.getGlobalSmtpCredentials())
        .thenReturn(Optional.of(smtpCredentials("smtp-user", "smtp-pass")));

    passwordResetService.requestPasswordReset("testuser", "en");

    assertThat(sentMails).hasSize(1);
    assertThat(sentMails.get(0).recipient()).isEqualTo("real@example.com");
  }

  @Test
  void requestPasswordReset_Should_NotSendMail_When_AuthenticatedCredentialsAreUnavailable() {
    ReflectionTestUtils.setField(passwordResetService, "consultingTypeServiceApiUrl", "http://cts");
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(validUser()));
    when(restTemplate.getForObject(anyString(), any()))
        .thenReturn(publicSmtpSettingsWithoutCredentials());
    when(applicationSettingsService.getGlobalSmtpCredentials()).thenReturn(Optional.empty());

    passwordResetService.requestPasswordReset("testuser", "en");

    assertThat(sentMails).isEmpty();
  }

  // Password reset is unauthenticated and dispatched off the request thread, so no user token
  // exists and the super-admin-guarded credentials endpoint is unreachable. Operator-provided
  // SMTP credentials (env SMTP_USER / SMTP_PASSWORD) must therefore be enough on their own.
  @Test
  void requestPasswordReset_Should_SendMail_When_CredentialsComeFromOperatorConfiguration() {
    ReflectionTestUtils.setField(passwordResetService, "consultingTypeServiceApiUrl", "http://cts");
    ReflectionTestUtils.setField(passwordResetService, "configuredSmtpUsername", "env-user");
    ReflectionTestUtils.setField(passwordResetService, "configuredSmtpPassword", "env-pass");
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(validUser()));
    when(restTemplate.getForObject(anyString(), any()))
        .thenReturn(publicSmtpSettingsWithoutCredentials());

    passwordResetService.requestPasswordReset("testuser", "en");

    assertThat(sentMails).hasSize(1);
    verify(applicationSettingsService, never()).getGlobalSmtpCredentials();
  }

  private Map<String, Object> publicSmtpSettingsWithoutCredentials() {
    return Map.of(
        "globalFeatureSystemNotificationEmailsEnabled",
        true,
        "globalSmtpEnabled",
        true,
        "globalSmtpHost",
        "smtp.invalid",
        "globalSmtpPort",
        587,
        "globalSmtpFrom",
        "noreply@example.com");
  }

  private ApplicationSettingsSmtpCredentialsDTO smtpCredentials(String username, String password) {
    return new ApplicationSettingsSmtpCredentialsDTO()
        .globalSmtpUsername(username)
        .globalSmtpPassword(password);
  }

  private User validUser() {
    User user = new User();
    user.setUserId("u-1");
    user.setUsername("testuser");
    user.setEmail("real@example.com");
    return user;
  }

  private Map<String, Object> validSmtpSettings() {
    return publicSmtpSettingsWithoutCredentials();
  }

  private OneTimeTokenStore.TokenClaim validClaim() {
    return new OneTimeTokenStore.TokenClaim("user-keycloak-id", Instant.now().plusSeconds(900));
  }
}
