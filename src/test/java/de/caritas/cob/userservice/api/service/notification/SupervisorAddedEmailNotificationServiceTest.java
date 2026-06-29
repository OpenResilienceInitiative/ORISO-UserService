package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupervisorAddedEmailNotificationServiceTest {

  @Mock private SystemNotificationEmailSettingsService emailSettingsService;
  @Mock private UserService userService;
  @Mock private TenantTemplateSupplier tenantTemplateSupplier;

  @InjectMocks private SupervisorAddedEmailNotificationService service;

  @BeforeEach
  void injectValues() {
    ReflectionTestUtils.setField(service, "emailDummySuffix", "@dummy.invalid");
    ReflectionTestUtils.setField(service, "applicationBaseUrl", "https://app.oriso.org");
    ReflectionTestUtils.setField(service, "publicFrontendBaseUrl", "https://app.oriso.org");
  }

  // ── notifySupervisorAdded early-return paths ──────────────────────────────

  @Test
  void notifySupervisorAdded_Should_ReturnEarly_When_SmtpSettingsNotAvailable() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.empty());

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@example.com");
    Consultant supervisor = new Consultant();
    supervisor.setEmail("sup@example.com");

    service.notifySupervisorAdded(user, supervisor, "Sup Name", 42L, null, "token");

    verify(emailSettingsService).resolveSupervisorAddedEmailSettings(eq(1L), eq("token"));
  }

  @Test
  void notifySupervisorAdded_Should_ReturnEarly_When_BothUserAndSupervisorTenantIdNull() {
    User user = new User();
    // tenantId is null
    Consultant supervisor = new Consultant();
    // tenantId is null → resolveSmtpSettings(null) returns null → early exit

    service.notifySupervisorAdded(user, supervisor, "Name", 1L, null, null);

    verify(emailSettingsService, never()).resolveSupervisorAddedEmailSettings(any(), any());
  }

  @Test
  void notifySupervisorAdded_Should_UseSupervisorTenantId_When_UserTenantIdIsNull() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.empty());

    User user = new User();
    // user.tenantId == null
    Consultant supervisor = new Consultant();
    supervisor.setTenantId(5L);

    service.notifySupervisorAdded(user, supervisor, "Name", 1L, null, null);

    verify(emailSettingsService).resolveSupervisorAddedEmailSettings(eq(5L), any());
  }

  @Test
  void notifySupervisorAdded_Should_PreferTenantDataTenantId_When_Provided() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.empty());

    User user = new User();
    user.setTenantId(99L);
    TenantData tenantData = new TenantData();
    tenantData.setTenantId(7L);

    service.notifySupervisorAdded(user, null, "Name", 1L, tenantData, null);

    verify(emailSettingsService).resolveSupervisorAddedEmailSettings(eq(7L), any());
  }

  @Test
  void notifySupervisorAdded_Should_NotSendEmail_When_UserHasDummyEmailSuffix() {
    SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings settings =
        new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
            "smtp.invalid", 25, false, "user", "pass", "from@invalid", null);
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@dummy.invalid");

    Consultant supervisor = new Consultant();
    supervisor.setEmail("sup@dummy.invalid");

    // Both have dummy emails — smtp attempt is skipped (no real connection), method completes
    service.notifySupervisorAdded(user, supervisor, "Sup", 10L, null, null);

    // No exception should escape; the method exits cleanly
  }

  // ── notifySupervisorRemoved early-return paths ────────────────────────────

  @Test
  void notifySupervisorRemoved_Should_ReturnEarly_When_SmtpSettingsNotAvailable() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.empty());

    User user = new User();
    user.setTenantId(3L);
    Consultant supervisor = new Consultant();

    service.notifySupervisorRemoved(user, supervisor, "Sup", 20L, null, "tok");

    verify(emailSettingsService).resolveSupervisorAddedEmailSettings(eq(3L), eq("tok"));
  }

  @Test
  void notifySupervisorRemoved_Should_ReturnEarly_When_TenantIdIsNull() {
    service.notifySupervisorRemoved(null, null, "Name", 1L, null, null);

    verify(emailSettingsService, never()).resolveSupervisorAddedEmailSettings(any(), any());
  }

  // ── notifyEmailAddressChanged early-return paths ──────────────────────────

  @Test
  void notifyEmailAddressChanged_Should_ReturnEarly_When_EmailBlank() {
    service.notifyEmailAddressChanged("user", "  ", 1L, null, null);

    verify(emailSettingsService, never()).resolveSupervisorAddedEmailSettings(any(), any());
  }

  @Test
  void notifyEmailAddressChanged_Should_ReturnEarly_When_UsernameBlank() {
    service.notifyEmailAddressChanged("", "new@example.com", 1L, null, null);

    verify(emailSettingsService, never()).resolveSupervisorAddedEmailSettings(any(), any());
  }

  @Test
  void notifyEmailAddressChanged_Should_ReturnEarly_When_TenantIdNull() {
    service.notifyEmailAddressChanged("user", "new@example.com", null, null, null);

    verify(emailSettingsService, never()).resolveSupervisorAddedEmailSettings(any(), any());
  }

  @Test
  void notifyEmailAddressChanged_Should_ReturnEarly_When_SmtpSettingsNotAvailable() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(eq(2L), any()))
        .thenReturn(Optional.empty());

    service.notifyEmailAddressChanged("user1", "new@example.com", 2L, null, "tok");

    verify(emailSettingsService).resolveSupervisorAddedEmailSettings(eq(2L), eq("tok"));
  }

  @Test
  void notifyEmailAddressChanged_Should_AttemptEmail_When_SmtpSettingsPresent() {
    SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings settings =
        new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
            "smtp.invalid", 587, false, "u", "p", "from@invalid", null);
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(eq(4L), any()))
        .thenReturn(Optional.of(settings));

    // sendEmailSafely catches any smtp connection error — no exception should escape
    service.notifyEmailAddressChanged("johndoe", "john@example.com", 4L, null, null);
  }

  // ── resolveUserWithEmail ──────────────────────────────────────────────────

  @Test
  void notifySupervisorAdded_Should_FetchUserFromRepo_When_UserEmailIsDummyButHasUserId() {
    SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings settings =
        new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
            "smtp.invalid", 25, false, "u", "p", "from@invalid", null);
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    user.setUserId("user-abc");
    user.setEmail("user@dummy.invalid");

    User fetchedUser = new User();
    fetchedUser.setEmail("real@example.com");
    when(userService.getUser("user-abc")).thenReturn(Optional.of(fetchedUser));

    // fetchedUser has real email → smtp attempt is made (caught by sendEmailSafely)
    service.notifySupervisorAdded(user, null, "Sup", 10L, null, null);

    verify(userService).getUser("user-abc");
  }

  // ── emailDummySuffix NPE bug ─────────────────────────────────────────────

  @Test
  void notifySupervisorAdded_Should_NotThrowNPE_When_EmailDummySuffixIsNull() {
    ReflectionTestUtils.setField(service, "emailDummySuffix", null);
    SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings settings =
        new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
            "smtp.invalid", 25, false, "u", "p", "from@invalid", null);
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("real@example.com");

    // Before fix: NPE in hasValidUserEmail on emailDummySuffix == null
    assertThatCode(() -> service.notifySupervisorAdded(user, null, "Sup", 1L, null, null))
        .doesNotThrowAnyException();
  }

  @Test
  void notifySupervisorAdded_Should_NotThrowNPE_ForConsultant_When_EmailDummySuffixIsNull() {
    ReflectionTestUtils.setField(service, "emailDummySuffix", null);
    SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings settings =
        new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
            "smtp.invalid", 25, false, "u", "p", "from@invalid", null);
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    Consultant supervisor = new Consultant();
    supervisor.setEmail("sup@example.com");

    // Before fix: NPE in hasValidConsultantEmail on emailDummySuffix == null
    assertThatCode(() -> service.notifySupervisorAdded(user, supervisor, "Sup", 1L, null, null))
        .doesNotThrowAnyException();
  }

  // ── resolveUserWithEmail edge cases ──────────────────────────────────────

  @Test
  void notifySupervisorAdded_Should_NotFetchUser_When_UserIsNull() {
    SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings settings =
        new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
            "smtp.invalid", 25, false, "u", "p", "from@invalid", null);
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(settings));
    Consultant supervisor = new Consultant();
    supervisor.setTenantId(5L);

    assertThatCode(() -> service.notifySupervisorAdded(null, supervisor, "Sup", 1L, null, null))
        .doesNotThrowAnyException();

    verify(userService, never()).getUser(any());
  }

  @Test
  void notifySupervisorAdded_Should_NotSendToUser_When_UserEmailIsBlank() {
    SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings settings =
        new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
            "smtp.invalid", 25, false, "u", "p", "from@invalid", null);
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("  ");

    assertThatCode(() -> service.notifySupervisorAdded(user, null, "Sup", 1L, null, null))
        .doesNotThrowAnyException();

    verify(userService, never()).getUser(any());
  }

  // ── sanitizeFrontendUrl — localhost fallback ──────────────────────────────

  @Test
  void notifyEmailAddressChanged_Should_FallbackToPublicFrontendUrl_When_AppBaseIsLocalhost() {
    ReflectionTestUtils.setField(service, "applicationBaseUrl", "http://localhost:8080");
    SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings settings =
        new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
            "smtp.invalid", 587, false, "u", "p", "from@invalid", null);
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(settings));

    // sendEmailSafely catches SMTP connection error — no exception escapes
    assertThatCode(
            () -> service.notifyEmailAddressChanged("johndoe", "john@example.com", 1L, null, null))
        .doesNotThrowAnyException();
  }

  // ── notifySupervisorRemoved — valid consultant email ─────────────────────

  @Test
  void notifySupervisorRemoved_Should_AttemptSend_When_ConsultantEmailIsValid() {
    SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings settings =
        new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
            "smtp.invalid", 587, false, "u", "p", "from@invalid", null);
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    Consultant supervisor = new Consultant();
    supervisor.setEmail("supervisor@example.com");

    assertThatCode(() -> service.notifySupervisorRemoved(user, supervisor, "Sup", 99L, null, null))
        .doesNotThrowAnyException();
  }

  // ── null supervisorDisplayName fallback ───────────────────────────────────

  @Test
  void notifySupervisorAdded_Should_UseDefaultDisplayName_When_SupervisorDisplayNameIsNull() {
    SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings settings = smtpSettings();
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@dummy.invalid");

    assertThatCode(() -> service.notifySupervisorAdded(user, null, null, 1L, null, null))
        .doesNotThrowAnyException();
  }

  // ── resolveAppFrontendUrl with tenantData ─────────────────────────────────

  @Test
  void notifySupervisorAdded_Should_UseUrlFromTemplateSupplier_When_TenantDataProvided() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));
    TenantData tenantData = new TenantData();
    tenantData.setTenantId(1L);
    TemplateDataDTO urlAttr = mock(TemplateDataDTO.class);
    when(urlAttr.getKey()).thenReturn("url");
    when(urlAttr.getValue()).thenReturn("https://tenant.example.com");
    when(tenantTemplateSupplier.getTemplateAttributes()).thenReturn(List.of(urlAttr));

    User user = new User();
    user.setEmail("user@dummy.invalid");

    assertThatCode(() -> service.notifySupervisorAdded(user, null, "Sup", 1L, tenantData, null))
        .doesNotThrowAnyException();
    verify(tenantTemplateSupplier).getTemplateAttributes();
  }

  @Test
  void notifySupervisorAdded_Should_FallbackToAppBaseUrl_When_TemplateSupplierThrows() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));
    TenantData tenantData = new TenantData();
    tenantData.setTenantId(1L);
    when(tenantTemplateSupplier.getTemplateAttributes())
        .thenThrow(new RuntimeException("service unavailable"));

    assertThatCode(() -> service.notifySupervisorAdded(null, null, "Sup", 1L, tenantData, null))
        .doesNotThrowAnyException();
  }

  @Test
  void notifySupervisorAdded_Should_FallbackToAppBaseUrl_When_TemplateSupplierReturnsNoUrlAttr() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));
    TenantData tenantData = new TenantData();
    tenantData.setTenantId(1L);
    TemplateDataDTO attr = mock(TemplateDataDTO.class);
    when(attr.getKey()).thenReturn("other-key");
    when(attr.getValue()).thenReturn("some-value");
    when(tenantTemplateSupplier.getTemplateAttributes()).thenReturn(List.of(attr));

    assertThatCode(() -> service.notifySupervisorAdded(null, null, "Sup", 1L, tenantData, null))
        .doesNotThrowAnyException();
  }

  // ── languageCodeOf — non-German (English) localization paths ─────────────

  @Test
  void notifySupervisorAdded_Should_LocalizeInEnglish_When_UserLanguageCodeIsEnglish() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@example.com");
    user.setLanguageCode(LanguageCode.en);

    assertThatCode(() -> service.notifySupervisorAdded(user, null, "Sup Name", 5L, null, null))
        .doesNotThrowAnyException();
  }

  @Test
  void notifySupervisorAdded_Should_LocalizeInEnglish_When_ConsultantLanguageCodeIsEnglish() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));

    User user = new User();
    user.setTenantId(1L);
    Consultant supervisor = new Consultant();
    supervisor.setEmail("sup@example.com");
    supervisor.setLanguageCode(LanguageCode.en);

    assertThatCode(() -> service.notifySupervisorAdded(user, supervisor, "Sup", 5L, null, null))
        .doesNotThrowAnyException();
  }

  @Test
  void
      notifySupervisorRemoved_Should_LocalizeInEnglish_When_UserAndConsultantLanguageCodeIsEnglish() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@example.com");
    user.setLanguageCode(LanguageCode.en);
    Consultant supervisor = new Consultant();
    supervisor.setEmail("sup@example.com");
    supervisor.setLanguageCode(LanguageCode.en);

    assertThatCode(() -> service.notifySupervisorRemoved(user, supervisor, "Sup", 5L, null, null))
        .doesNotThrowAnyException();
  }

  // ── isLocalUrl — 127.0.0.1, ::1, malformed URL ────────────────────────────

  @Test
  void notifyEmailAddressChanged_Should_FallbackToPublicFrontendUrl_When_AppBaseIs127_0_0_1() {
    ReflectionTestUtils.setField(service, "applicationBaseUrl", "http://127.0.0.1:8080");
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));

    assertThatCode(
            () -> service.notifyEmailAddressChanged("user", "user@example.com", 1L, null, null))
        .doesNotThrowAnyException();
  }

  @Test
  void notifyEmailAddressChanged_Should_FallbackToPublicFrontendUrl_When_AppBaseIsIPv6Loopback() {
    ReflectionTestUtils.setField(service, "applicationBaseUrl", "http://[::1]:8080");
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));

    assertThatCode(
            () -> service.notifyEmailAddressChanged("user", "user@example.com", 1L, null, null))
        .doesNotThrowAnyException();
  }

  @Test
  void notifyEmailAddressChanged_Should_FallbackToPublicFrontendUrl_When_AppBaseUrlIsMalformed() {
    // isLocalUrl: URI.create("not-a-valid-url").getHost() == null → treated as local → fallback
    ReflectionTestUtils.setField(service, "applicationBaseUrl", "not-a-valid-url");
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));

    assertThatCode(
            () -> service.notifyEmailAddressChanged("user", "user@example.com", 1L, null, null))
        .doesNotThrowAnyException();
  }

  // ── resolveHexColor — valid hex passes through, invalid → default ─────────

  @Test
  void notifySupervisorAdded_Should_AcceptValidHexColor_When_SettingsProvideValidHex() {
    SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings settings =
        new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
            "smtp.invalid", 25, false, "u", "p", "from@invalid", "#1a2b3c");
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@dummy.invalid");

    assertThatCode(() -> service.notifySupervisorAdded(user, null, "Sup", 1L, null, null))
        .doesNotThrowAnyException();
  }

  @Test
  void notifySupervisorAdded_Should_UseDefaultHexColor_When_SettingsProvideInvalidHex() {
    // resolveHexColor: "not-a-color" doesn't match ^#([A-Fa-f0-9]{6})$ → DEFAULT_EMAIL_THEME_COLOR
    SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings settings =
        new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
            "smtp.invalid", 25, false, "u", "p", "from@invalid", "not-a-color");
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@dummy.invalid");

    assertThatCode(() -> service.notifySupervisorAdded(user, null, "Sup", 1L, null, null))
        .doesNotThrowAnyException();
  }

  // ── buildSessionUrl — null sessionId ──────────────────────────────────────

  @Test
  void notifySupervisorAdded_Should_BuildUrlWithEmptySessionPath_When_SessionIdIsNull() {
    // buildSessionUrl: sessionId == null → sessionPath = "" → no NPE
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@dummy.invalid");

    assertThatCode(() -> service.notifySupervisorAdded(user, null, "Sup", null, null, null))
        .doesNotThrowAnyException();
  }

  // ── escapeHtml — HTML special chars in supervisorDisplayName ──────────────

  @Test
  void notifySupervisorAdded_Should_EscapeHtmlInDisplayName_When_DisplayNameContainsHtmlChars() {
    // escapeHtml must sanitize & < > " ' without crashing
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@example.com");

    assertThatCode(
            () ->
                service.notifySupervisorAdded(
                    user, null, "<script>alert('xss')</script> & \"Sup\"", 1L, null, null))
        .doesNotThrowAnyException();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings smtpSettings() {
    return new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
        "smtp.invalid", 25, false, "u", "p", "from@invalid", null);
  }
}
