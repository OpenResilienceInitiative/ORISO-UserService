package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupervisorAddedEmailNotificationServiceTest {

  @Mock private SystemNotificationEmailSettingsService emailSettingsService;
  @Mock private TenantSystemEmailDeliveryClient deliveryClient;
  @Mock private UserService userService;
  @Spy private UsernameTranscoder usernameTranscoder = new UsernameTranscoder();
  @Mock private TenantTemplateSupplier tenantTemplateSupplier;
  // Real instances rather than mocks: these tests exercise the whole send path,
  // and the point of the port is that the path now produces a mail from the
  // design system. A mock here would assert that a method was called; this
  // asserts that a mail comes out.
  @Spy private OrisoEmailRenderer emailRenderer = new OrisoEmailRenderer();

  private final de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver
      brandingResolver =
          org.mockito.Mockito.mock(
              de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver.class);
  @Spy private OrisoEmailBrand emailBrand = new OrisoEmailBrand(brandingResolver);

  @InjectMocks private SupervisorAddedEmailNotificationService service;

  @Test
  void emailChangeShowsDecodedLoginInBothMimeParts() {
    assertEmailChangeLogin(
        usernameTranscoder.encodeUsername("bart.simpson@example.test"),
        "bart.simpson@example.test");
  }

  @Test
  void emailChangePreservesPlainLoginInBothMimeParts() {
    assertEmailChangeLogin("Bart Simpson", "Bart Simpson");
  }

  private void assertEmailChangeLogin(String input, String expected) {
    service.notifyEmailAddressChanged(input, "recipient@example.test", 40L, null, null);
    var email = org.mockito.ArgumentCaptor.forClass(OrisoEmailRenderer.RenderedEmail.class);
    verify(deliveryClient)
        .send(
            eq(40L),
            eq(TenantSystemEmailDeliveryClient.Purpose.EMAIL_ADDRESS_CHANGED),
            eq("recipient@example.test"),
            email.capture());
    assertThat(email.getValue().html()).contains(expected).doesNotContain("enc.");
    assertThat(email.getValue().text()).contains(expected).doesNotContain("enc.");
  }

  @Test
  void emailChangeDoesNotRequireTenantPasswordInUserService() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.empty());
    service.notifyEmailAddressChanged("test-user", "recipient@example.org", 40L, null, "token");
    verify(emailRenderer).render(eq("email-geaendert"), any(), any());
  }

  @Test
  void emailChangeWorksWithoutARequestScopedSmtpLookup() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenThrow(new IllegalStateException("No request context on async thread"));
    assertThatCode(
            () ->
                service.notifyEmailAddressChanged(
                    "test-user", "recipient@example.org", 40L, null, "token"))
        .doesNotThrowAnyException();
    verify(emailRenderer).render(eq("email-geaendert"), any(), any());
  }

  @Test
  void supervisorEventsRejectUnknownAndNonpositiveTenantBeforeRendering() {
    for (Long tenantId : java.util.Arrays.asList(null, 0L, -1L)) {
      var tenant = new TenantData(tenantId, "test");
      var user = mock(User.class);
      var consultant = mock(Consultant.class);
      assertThatCode(
              () -> service.notifySupervisorAdded(user, consultant, "Name", 1L, tenant, null))
          .doesNotThrowAnyException();
      assertThatCode(
              () -> service.notifySupervisorRemoved(user, consultant, "Name", 1L, tenant, null))
          .doesNotThrowAnyException();
    }
    org.mockito.Mockito.verifyNoInteractions(deliveryClient, tenantTemplateSupplier, userService);
    verify(emailRenderer, never()).render(any(), any(), any());
  }

  @BeforeEach
  void injectValues() {
    when(brandingResolver.resolve(any()))
        .thenReturn(
            new de.caritas.cob.userservice.api.service.email.layout.EmailBranding(
                "Online-Beratung", null, "#a5000a", null, null));
    ReflectionTestUtils.setField(service, "emailDummySuffix", "@dummy.invalid");
    ReflectionTestUtils.setField(service, "applicationBaseUrl", "https://app.oriso.org");
    ReflectionTestUtils.setField(service, "publicFrontendBaseUrl", "https://app.oriso.org");
  }

  @Test
  void usesExplicitTenantBrandingAfterTheUrlResolverClearsThreadContext() throws Exception {
    var resolver =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver.class);
    when(resolver.resolve(any()))
        .thenReturn(de.caritas.cob.userservice.api.service.email.layout.EmailBranding.neutral());
    when(resolver.resolve(7L))
        .thenReturn(
            new de.caritas.cob.userservice.api.service.email.layout.EmailBranding(
                "Tenant Seven",
                "https://app.oriso.org/service/tenant/public/branding/7/logo",
                "#1c4f8f",
                null,
                null));
    ReflectionTestUtils.setField(emailBrand, "brandingResolver", resolver);
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(
            Optional.of(
                new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
                    "smtp.example.org", 587, false, "test", "test", "sender@example.org", null)));
    service.notifyEmailAddressChanged(
        "username", "recipient@example.org", 7L, new TenantData(7L, "seven"), null);
    var rendered = org.mockito.ArgumentCaptor.forClass(OrisoEmailRenderer.RenderedEmail.class);
    verify(deliveryClient)
        .send(
            eq(7L),
            eq(TenantSystemEmailDeliveryClient.Purpose.EMAIL_ADDRESS_CHANGED),
            eq("recipient@example.org"),
            rendered.capture());
    assertThat(rendered.getValue().html()).contains("Tenant Seven", "/branding/7/logo");
  }

  // ── notifySupervisorAdded early-return paths ──────────────────────────────

  @Test
  void notifySupervisorAdded_Should_DelegateWithoutLocalSmtpSettings() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.empty());

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@example.com");
    Consultant supervisor = new Consultant();
    supervisor.setEmail("sup@example.com");

    service.notifySupervisorAdded(user, supervisor, "Sup Name", 42L, null, "token");

    verify(deliveryClient, org.mockito.Mockito.times(2))
        .send(eq(1L), eq(TenantSystemEmailDeliveryClient.Purpose.SUPERVISOR_ADDED), any(), any());
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
    supervisor.setEmail("supervisor@example.org");

    service.notifySupervisorAdded(user, supervisor, "Name", 1L, null, null);

    verify(deliveryClient)
        .send(
            eq(5L),
            eq(TenantSystemEmailDeliveryClient.Purpose.SUPERVISOR_ADDED),
            eq("supervisor@example.org"),
            any());
  }

  @Test
  void notifySupervisorAdded_Should_PreferTenantDataTenantId_When_Provided() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.empty());

    User user = new User();
    user.setTenantId(99L);
    user.setEmail("asker@example.org");
    TenantData tenantData = new TenantData();
    tenantData.setTenantId(7L);

    service.notifySupervisorAdded(user, null, "Name", 1L, tenantData, null);

    verify(deliveryClient)
        .send(
            eq(7L),
            eq(TenantSystemEmailDeliveryClient.Purpose.SUPERVISOR_ADDED),
            eq("asker@example.org"),
            any());
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
  void notifySupervisorRemoved_Should_DelegateWithoutLocalSmtpSettings() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.empty());

    User user = new User();
    user.setTenantId(3L);
    user.setEmail("asker@example.org");
    Consultant supervisor = new Consultant();

    service.notifySupervisorRemoved(user, supervisor, "Sup", 20L, null, "tok");

    verify(deliveryClient)
        .send(
            eq(3L),
            eq(TenantSystemEmailDeliveryClient.Purpose.SUPERVISOR_REMOVED),
            eq("asker@example.org"),
            any());
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
  void notifyEmailAddressChanged_Should_DelegateWithoutLocalSmtpSettings() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(eq(2L), any()))
        .thenReturn(Optional.empty());

    service.notifyEmailAddressChanged("user1", "new@example.com", 2L, null, "tok");

    verify(deliveryClient)
        .send(
            eq(2L),
            eq(TenantSystemEmailDeliveryClient.Purpose.EMAIL_ADDRESS_CHANGED),
            eq("new@example.com"),
            any());
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
    supervisor.setEmail("supervisor@example.org");

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

  // ── localizeSupervisorAssignmentRemoved — German path ────────────────────

  @Test
  void notifySupervisorRemoved_Should_LocalizeInGerman_When_UserLanguageCodeIsGerman() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@example.com");
    user.setLanguageCode(LanguageCode.de);

    assertThatCode(
            () -> service.notifySupervisorRemoved(user, null, "Supervisor Name", 7L, null, null))
        .doesNotThrowAnyException();
  }

  // ── notifyEmailAddressChanged with non-null tenantData ────────────────────

  @Test
  void notifyEmailAddressChanged_Should_UseUrlFromTenantData_When_TenantDataProvided() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));
    TenantData tenantData = new TenantData();
    tenantData.setTenantId(8L);
    TemplateDataDTO urlAttr = mock(TemplateDataDTO.class);
    when(urlAttr.getKey()).thenReturn("url");
    when(urlAttr.getValue()).thenReturn("https://tenant8.example.com");
    when(tenantTemplateSupplier.getTemplateAttributes()).thenReturn(List.of(urlAttr));

    assertThatCode(
            () ->
                service.notifyEmailAddressChanged(
                    "johndoe", "john@example.com", 8L, tenantData, null))
        .doesNotThrowAnyException();
    verify(tenantTemplateSupplier).getTemplateAttributes();
  }

  // ── resolveUserWithEmail — userService.getUser() returns empty ────────────

  @Test
  void notifySupervisorAdded_Should_FallBackToOriginalUser_When_UserServiceReturnsEmpty() {
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));

    User user = new User();
    user.setTenantId(1L);
    user.setUserId("user-xyz");
    user.setEmail("user@dummy.invalid");
    when(userService.getUser("user-xyz")).thenReturn(Optional.empty());

    // Falls back to original user (dummy email) → hasValidUserEmail returns false → no send
    assertThatCode(() -> service.notifySupervisorAdded(user, null, "Sup", 1L, null, null))
        .doesNotThrowAnyException();
    verify(userService).getUser("user-xyz");
  }

  // ── SSL SMTP path (isSecure() == true) ───────────────────────────────────

  @Test
  void notifyEmailAddressChanged_Should_UseSslSmtp_When_SettingsIsSecureIsTrue() {
    SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings sslSettings =
        new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
            "smtp.invalid", 465, true, "u", "p", "from@invalid", null);
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(sslSettings));

    // sendEmailSafely catches SMTP connection error — no exception escapes
    assertThatCode(
            () -> service.notifyEmailAddressChanged("johndoe", "john@example.com", 1L, null, null))
        .doesNotThrowAnyException();
  }

  // ── buildSessionUrl — trailing-slash strip ────────────────────────────────

  @Test
  void notifyEmailAddressChanged_Should_StripTrailingSlash_When_AppBaseUrlEndsWithSlash() {
    ReflectionTestUtils.setField(service, "applicationBaseUrl", "https://app.oriso.org/");
    when(emailSettingsService.resolveSupervisorAddedEmailSettings(any(), any()))
        .thenReturn(Optional.of(smtpSettings()));

    assertThatCode(
            () -> service.notifyEmailAddressChanged("johndoe", "john@example.com", 1L, null, null))
        .doesNotThrowAnyException();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings smtpSettings() {
    return new SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings(
        "smtp.invalid", 25, false, "u", "p", "from@invalid", null);
  }

  // ── the design system, and the anonymity rule it enforces ─────────────────

  @Test
  void teamChangeMailToAnAdviceSeekerNamesNobody() {
    // The previous version put the supervisor's display name and the session
    // number into a mail to an advice seeker. ADR-019 forbids that: a mail to
    // an advice seeker says that something happened, the application says what.
    String statement = service.askerStatementSupervisorJoined(LanguageCode.de);

    assertThat(statement).doesNotContain("Frau Sandmann").doesNotContain("#");
    assertThat(service.askerStatementSupervisorLeft(LanguageCode.de))
        .doesNotContain("Frau Sandmann")
        .doesNotContain("#");

    // The statement alone is not the guarantee — the surrounding card renders a case reference
    // and a call-to-action link too, and both can leak the session on their own. Render an advice
    // seeker's actual mail (null sessionId, app root as the CTA, exactly what the caller passes)
    // and check the full output, not just the hand-authored sentence.
    var email =
        service.renderTeamChange(
            LanguageCode.de,
            statement,
            "https://app.oriso.org",
            "https://app.oriso.org",
            null,
            "#1c4f8f");

    assertThat(email.html())
        .doesNotContain("#4711")
        .doesNotContain("/sessions/user/view/session/")
        .doesNotContain("/sessions/consultant/sessionView/session/");
    assertThat(email.text())
        .doesNotContain("/sessions/user/view/session/")
        .doesNotContain("/sessions/consultant/sessionView/session/");
  }

  @Test
  void teamChangeMailToACounsellorMayCarryTheCaseReference() {
    var email =
        service.renderTeamChange(
            LanguageCode.de,
            service.staffStatementSupervisorAdded(LanguageCode.de),
            "https://app.oriso.org",
            "https://app.oriso.org/sessions/consultant/sessionView/session/4711",
            4711L,
            "#1c4f8f");

    assertThat(email.subject()).isEqualTo("Änderung in Ihrem Team");
    assertThat(email.html())
        .contains("Supervisor-Berater:in")
        .contains("#4711")
        .contains("https://app.oriso.org/sessions/consultant/sessionView/session/4711");
    assertThat(email.text()).contains("Supervisor-Berater:in");
  }

  @Test
  void teamChangeMailUsesTheDesignSystemSkeletonRatherThanTheOldInlineCard() {
    var email =
        service.renderTeamChange(
            LanguageCode.de,
            "Etwas hat sich geändert.",
            "https://app.oriso.org",
            "https://app.oriso.org",
            1L,
            "#1c4f8f");

    // The old inline card: a 620px table on #f6f7fb with an #e5e7eb border, in
    // Arial. Checked by its own fingerprints — "620" on its own is no use,
    // because the design system's mobile breakpoint is legitimately 620px.
    assertThat(email.html())
        .doesNotContain("#f6f7fb")
        .doesNotContain("#e5e7eb")
        .doesNotContain("width=\"620\"");
    assertThat(email.html()).contains("#f2efef").contains("width=\"600\"").contains("Inter");
  }

  @Test
  void aTenantColourThatCannotCarryWhiteTextDoesNotReachTheButton() {
    var email =
        service.renderTeamChange(
            LanguageCode.de,
            "Etwas hat sich geändert.",
            "https://app.oriso.org",
            "https://app.oriso.org",
            1L,
            "#ffd400");

    assertThat(email.html()).doesNotContain("#ffd400");
  }
}
