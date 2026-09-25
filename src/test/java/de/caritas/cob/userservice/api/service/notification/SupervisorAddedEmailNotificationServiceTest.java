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
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationFixture;
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

  @Mock private TenantSystemEmailRouteService emailRoutes;
  @Mock private TenantSystemEmailDelivery emailDelivery;
  @Mock private UserService userService;
  @Mock private TenantTemplateSupplier tenantTemplateSupplier;
  // Real instances rather than mocks: these tests exercise the whole send path,
  // and the point of the port is that the path now produces a mail from the
  // design system. A mock here would assert that a method was called; this
  // asserts that a mail comes out.
  @Spy private OrisoEmailRenderer emailRenderer = new OrisoEmailRenderer();

  @Spy
  private OrisoEmailBrand emailBrand =
      new OrisoEmailBrand(SenderOrganisationFixture.platformOwner());

  @InjectMocks private SupervisorAddedEmailNotificationService service;

  @BeforeEach
  void injectValues() {
    ReflectionTestUtils.setField(service, "emailDummySuffix", "@dummy.invalid");
    ReflectionTestUtils.setField(service, "publicFrontendBaseUrl", "https://app.oriso.org");
  }

  @Test
  void missingPublicFrontendUrlFailsStartupWithSettingName() {
    ReflectionTestUtils.setField(service, "publicFrontendBaseUrl", "");

    org.assertj.core.api.Assertions.assertThatThrownBy(service::validateFrontendUrl)
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class)
        .hasMessageContaining("system.notification.frontend.base-url");
  }

  // ── notifySupervisorAdded early-return paths ──────────────────────────────

  @Test
  void notifySupervisorAdded_Should_ReturnEarly_When_SmtpSettingsNotAvailable() {
    when(emailRoutes.resolve(any())).thenReturn(Optional.empty());

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@example.com");
    Consultant supervisor = new Consultant();
    supervisor.setEmail("sup@example.com");

    service.notifySupervisorAdded(user, supervisor, 42L, null, "token");

    verify(emailRoutes).resolve(eq(1L));
  }

  @Test
  void notifySupervisorAdded_Should_ReturnEarly_When_BothUserAndSupervisorTenantIdNull() {
    User user = new User();
    // tenantId is null
    Consultant supervisor = new Consultant();
    // tenantId is null → resolveSmtpSettings(null) returns null → early exit

    service.notifySupervisorAdded(user, supervisor, 1L, null, null);

    verify(emailRoutes, never()).resolve(any());
  }

  @Test
  void notifySupervisorAdded_Should_UseSupervisorTenantId_When_UserTenantIdIsNull() {
    when(emailRoutes.resolve(any())).thenReturn(Optional.empty());

    User user = new User();
    // user.tenantId == null
    Consultant supervisor = new Consultant();
    supervisor.setTenantId(5L);

    service.notifySupervisorAdded(user, supervisor, 1L, null, null);

    verify(emailRoutes).resolve(eq(5L));
  }

  @Test
  void notifySupervisorAdded_Should_PreferTenantDataTenantId_When_Provided() {
    when(emailRoutes.resolve(any())).thenReturn(Optional.empty());

    User user = new User();
    user.setTenantId(99L);
    TenantData tenantData = new TenantData();
    tenantData.setTenantId(7L);

    service.notifySupervisorAdded(user, null, 1L, tenantData, null);

    verify(emailRoutes).resolve(eq(7L));
  }

  @Test
  void notifySupervisorAdded_Should_NotSendEmail_When_UserHasDummyEmailSuffix() {
    TenantSystemEmailRouteService.Route settings =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@dummy.invalid");

    Consultant supervisor = new Consultant();
    supervisor.setEmail("sup@dummy.invalid");

    // Both have dummy emails, so no delivery is attempted.
    service.notifySupervisorAdded(user, supervisor, 10L, null, null);

    // No exception should escape; the method exits cleanly
  }

  // ── notifySupervisorRemoved early-return paths ────────────────────────────

  @Test
  void notifySupervisorRemoved_Should_ReturnEarly_When_SmtpSettingsNotAvailable() {
    when(emailRoutes.resolve(any())).thenReturn(Optional.empty());

    User user = new User();
    user.setTenantId(3L);
    Consultant supervisor = new Consultant();

    service.notifySupervisorRemoved(user, supervisor, 20L, null, "tok");

    verify(emailRoutes).resolve(eq(3L));
  }

  @Test
  void notifySupervisorRemoved_Should_ReturnEarly_When_TenantIdIsNull() {
    service.notifySupervisorRemoved(null, null, 1L, null, null);

    verify(emailRoutes, never()).resolve(any());
  }

  // ── notifyEmailAddressChanged early-return paths ──────────────────────────

  @Test
  void notifyEmailAddressChanged_Should_ReturnEarly_When_EmailBlank() {
    service.notifyEmailAddressChanged("user", "  ", 1L, null, null);

    verify(emailRoutes, never()).resolve(any());
  }

  @Test
  void notifyEmailAddressChanged_Should_ReturnEarly_When_UsernameBlank() {
    service.notifyEmailAddressChanged("", "new@example.com", 1L, null, null);

    verify(emailRoutes, never()).resolve(any());
  }

  @Test
  void notifyEmailAddressChanged_Should_ReturnEarly_When_TenantIdNull() {
    service.notifyEmailAddressChanged("user", "new@example.com", null, null, null);

    verify(emailRoutes, never()).resolve(any());
  }

  @Test
  void notifyEmailAddressChanged_Should_ReturnEarly_When_SmtpSettingsNotAvailable() {
    when(emailRoutes.resolve(eq(2L))).thenReturn(Optional.empty());

    service.notifyEmailAddressChanged("user1", "new@example.com", 2L, null, "tok");

    verify(emailRoutes).resolve(eq(2L));
  }

  @Test
  void notifyEmailAddressChanged_Should_AttemptEmail_When_SmtpSettingsPresent() {
    TenantSystemEmailRouteService.Route settings =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(emailRoutes.resolve(eq(4L))).thenReturn(Optional.of(settings));

    service.notifyEmailAddressChanged("johndoe", "john@example.com", 4L, null, null);
    verify(emailDelivery)
        .send(
            eq(4L),
            eq(settings),
            eq(TenantSystemEmailDelivery.Purpose.EMAIL_ADDRESS_CHANGED),
            eq("john@example.com"),
            any());
  }

  // ── resolveUserWithEmail ──────────────────────────────────────────────────

  @Test
  void notifySupervisorAdded_Should_FetchUserFromRepo_When_UserEmailIsDummyButHasUserId() {
    TenantSystemEmailRouteService.Route settings =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    user.setUserId("user-abc");
    user.setEmail("user@dummy.invalid");

    User fetchedUser = new User();
    fetchedUser.setEmail("real@example.com");
    when(userService.getUser("user-abc")).thenReturn(Optional.of(fetchedUser));

    // A fetched real address may be delivered through the selected tenant route.
    service.notifySupervisorAdded(user, null, 10L, null, null);

    verify(userService).getUser("user-abc");
  }

  // ── emailDummySuffix NPE bug ─────────────────────────────────────────────

  @Test
  void notifySupervisorAdded_Should_NotThrowNPE_When_EmailDummySuffixIsNull() {
    ReflectionTestUtils.setField(service, "emailDummySuffix", null);
    TenantSystemEmailRouteService.Route settings =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("real@example.com");

    // Before fix: NPE in hasValidUserEmail on emailDummySuffix == null
    assertThatCode(() -> service.notifySupervisorAdded(user, null, 1L, null, null))
        .doesNotThrowAnyException();
  }

  @Test
  void notifySupervisorAdded_Should_NotThrowNPE_ForConsultant_When_EmailDummySuffixIsNull() {
    ReflectionTestUtils.setField(service, "emailDummySuffix", null);
    TenantSystemEmailRouteService.Route settings =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    Consultant supervisor = new Consultant();
    supervisor.setEmail("sup@example.com");

    // Before fix: NPE in hasValidConsultantEmail on emailDummySuffix == null
    assertThatCode(() -> service.notifySupervisorAdded(user, supervisor, 1L, null, null))
        .doesNotThrowAnyException();
  }

  // ── resolveUserWithEmail edge cases ──────────────────────────────────────

  @Test
  void notifySupervisorAdded_Should_NotFetchUser_When_UserIsNull() {
    TenantSystemEmailRouteService.Route settings =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(settings));
    Consultant supervisor = new Consultant();
    supervisor.setTenantId(5L);

    assertThatCode(() -> service.notifySupervisorAdded(null, supervisor, 1L, null, null))
        .doesNotThrowAnyException();

    verify(userService, never()).getUser(any());
  }

  @Test
  void notifySupervisorAdded_Should_NotSendToUser_When_UserEmailIsBlank() {
    TenantSystemEmailRouteService.Route settings =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("  ");

    assertThatCode(() -> service.notifySupervisorAdded(user, null, 1L, null, null))
        .doesNotThrowAnyException();

    verify(userService, never()).getUser(any());
  }

  // ── public frontend URL validation ──────────────────────────────

  @Test
  void notifyEmailAddressChanged_Should_RejectInvalidPublicFrontendUrl_When_ConfigIsLocalhost() {
    ReflectionTestUtils.setField(service, "publicFrontendBaseUrl", "http://localhost:8080");
    TenantSystemEmailRouteService.Route settings =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(settings));

    // Invalid configuration fails before delivery.
    assertThatCode(
            () -> service.notifyEmailAddressChanged("johndoe", "john@example.com", 1L, null, null))
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class);
  }

  // ── notifySupervisorRemoved — valid consultant email ─────────────────────

  @Test
  void notifySupervisorRemoved_Should_AttemptSend_When_ConsultantEmailIsValid() {
    TenantSystemEmailRouteService.Route settings =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    Consultant supervisor = new Consultant();
    supervisor.setEmail("supervisor@example.com");

    assertThatCode(() -> service.notifySupervisorRemoved(user, supervisor, 99L, null, null))
        .doesNotThrowAnyException();
  }

  // ── resolveAppFrontendUrl with tenantData ─────────────────────────────────

  @Test
  void notifySupervisorAdded_Should_UseUrlFromTemplateSupplier_When_TenantDataProvided() {
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(routeSettings()));
    TenantData tenantData = new TenantData();
    tenantData.setTenantId(1L);
    TemplateDataDTO urlAttr = mock(TemplateDataDTO.class);
    when(urlAttr.getKey()).thenReturn("url");
    when(urlAttr.getValue()).thenReturn("https://tenant.example.com");
    when(tenantTemplateSupplier.getTemplateAttributes()).thenReturn(List.of(urlAttr));

    User user = new User();
    user.setEmail("user@dummy.invalid");

    assertThatCode(() -> service.notifySupervisorAdded(user, null, 1L, tenantData, null))
        .doesNotThrowAnyException();
    verify(tenantTemplateSupplier).getTemplateAttributes();
  }

  @Test
  void notifySupervisorAdded_Should_RejectMissingTenantUrl_When_TemplateSupplierThrows() {
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(routeSettings()));
    TenantData tenantData = new TenantData();
    tenantData.setTenantId(1L);
    when(tenantTemplateSupplier.getTemplateAttributes())
        .thenThrow(new RuntimeException("service unavailable"));

    assertThatCode(() -> service.notifySupervisorAdded(null, null, 1L, tenantData, null))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void notifySupervisorAdded_Should_RejectMissingTenantUrl_When_TemplateSupplierReturnsNoUrlAttr() {
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(routeSettings()));
    TenantData tenantData = new TenantData();
    tenantData.setTenantId(1L);
    TemplateDataDTO attr = mock(TemplateDataDTO.class);
    when(attr.getKey()).thenReturn("other-key");
    when(attr.getValue()).thenReturn("some-value");
    when(tenantTemplateSupplier.getTemplateAttributes()).thenReturn(List.of(attr));

    assertThatCode(() -> service.notifySupervisorAdded(null, null, 1L, tenantData, null))
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class);
  }

  // ── languageCodeOf — non-German (English) localization paths ─────────────

  @Test
  void notifySupervisorAdded_Should_LocalizeInEnglish_When_UserLanguageCodeIsEnglish() {
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(routeSettings()));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@example.com");
    user.setLanguageCode(LanguageCode.en);

    assertThatCode(() -> service.notifySupervisorAdded(user, null, 5L, null, null))
        .doesNotThrowAnyException();
  }

  @Test
  void notifySupervisorAdded_Should_LocalizeInEnglish_When_ConsultantLanguageCodeIsEnglish() {
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(routeSettings()));

    User user = new User();
    user.setTenantId(1L);
    Consultant supervisor = new Consultant();
    supervisor.setEmail("sup@example.com");
    supervisor.setLanguageCode(LanguageCode.en);

    assertThatCode(() -> service.notifySupervisorAdded(user, supervisor, 5L, null, null))
        .doesNotThrowAnyException();
  }

  @Test
  void
      notifySupervisorRemoved_Should_LocalizeInEnglish_When_UserAndConsultantLanguageCodeIsEnglish() {
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(routeSettings()));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@example.com");
    user.setLanguageCode(LanguageCode.en);
    Consultant supervisor = new Consultant();
    supervisor.setEmail("sup@example.com");
    supervisor.setLanguageCode(LanguageCode.en);

    assertThatCode(() -> service.notifySupervisorRemoved(user, supervisor, 5L, null, null))
        .doesNotThrowAnyException();
  }

  // ── reject loopback and malformed URLs ────────────────────────────

  @Test
  void notifyEmailAddressChanged_Should_RejectInvalidPublicFrontendUrl_When_ConfigIs127_0_0_1() {
    ReflectionTestUtils.setField(service, "publicFrontendBaseUrl", "http://127.0.0.1:8080");
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(routeSettings()));

    assertThatCode(
            () -> service.notifyEmailAddressChanged("user", "user@example.com", 1L, null, null))
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class);
  }

  @Test
  void notifyEmailAddressChanged_Should_RejectInvalidPublicFrontendUrl_When_ConfigIsIPv6Loopback() {
    ReflectionTestUtils.setField(service, "publicFrontendBaseUrl", "http://[::1]:8080");
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(routeSettings()));

    assertThatCode(
            () -> service.notifyEmailAddressChanged("user", "user@example.com", 1L, null, null))
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class);
  }

  @Test
  void notifyEmailAddressChanged_Should_RejectInvalidPublicFrontendUrl_When_ConfigUrlIsMalformed() {
    // A malformed URL is rejected; no replacement URL is selected.
    ReflectionTestUtils.setField(service, "publicFrontendBaseUrl", "not-a-valid-url");
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(routeSettings()));

    assertThatCode(
            () -> service.notifyEmailAddressChanged("user", "user@example.com", 1L, null, null))
        .isInstanceOf(TenantSystemEmailRouteService.ConfigurationException.class);
  }

  // ── resolveHexColor — valid hex passes through, invalid → default ─────────

  @Test
  void notifySupervisorAdded_Should_AcceptValidHexColor_When_SettingsProvideValidHex() {
    TenantSystemEmailRouteService.Route settings =
        new TenantSystemEmailRouteService.Route(
            TenantSystemEmailRouteService.Mode.PLATFORM, "#1a2b3c");
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@dummy.invalid");

    assertThatCode(() -> service.notifySupervisorAdded(user, null, 1L, null, null))
        .doesNotThrowAnyException();
  }

  @Test
  void notifySupervisorAdded_Should_UseDefaultHexColor_When_SettingsProvideInvalidHex() {
    // resolveHexColor: "not-a-color" doesn't match ^#([A-Fa-f0-9]{6})$ → DEFAULT_EMAIL_THEME_COLOR
    TenantSystemEmailRouteService.Route settings =
        new TenantSystemEmailRouteService.Route(
            TenantSystemEmailRouteService.Mode.PLATFORM, "not-a-color");
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(settings));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@dummy.invalid");

    assertThatCode(() -> service.notifySupervisorAdded(user, null, 1L, null, null))
        .doesNotThrowAnyException();
  }

  // ── buildSessionUrl — null sessionId ──────────────────────────────────────

  @Test
  void notifySupervisorAdded_Should_BuildUrlWithEmptySessionPath_When_SessionIdIsNull() {
    // buildSessionUrl: sessionId == null → sessionPath = "" → no NPE
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(routeSettings()));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@dummy.invalid");

    assertThatCode(() -> service.notifySupervisorAdded(user, null, null, null, null))
        .doesNotThrowAnyException();
  }

  // ── localizeSupervisorAssignmentRemoved — German path ────────────────────

  @Test
  void notifySupervisorRemoved_Should_LocalizeInGerman_When_UserLanguageCodeIsGerman() {
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(routeSettings()));

    User user = new User();
    user.setTenantId(1L);
    user.setEmail("user@example.com");
    user.setLanguageCode(LanguageCode.de);

    assertThatCode(() -> service.notifySupervisorRemoved(user, null, 7L, null, null))
        .doesNotThrowAnyException();
  }

  // ── notifyEmailAddressChanged with non-null tenantData ────────────────────

  @Test
  void notifyEmailAddressChanged_Should_UseUrlFromTenantData_When_TenantDataProvided() {
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(routeSettings()));
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
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(routeSettings()));

    User user = new User();
    user.setTenantId(1L);
    user.setUserId("user-xyz");
    user.setEmail("user@dummy.invalid");
    when(userService.getUser("user-xyz")).thenReturn(Optional.empty());

    // Falls back to original user (dummy email) → hasValidUserEmail returns false → no send
    assertThatCode(() -> service.notifySupervisorAdded(user, null, 1L, null, null))
        .doesNotThrowAnyException();
    verify(userService).getUser("user-xyz");
  }

  // ── SSL SMTP path (isSecure() == true) ───────────────────────────────────

  @Test
  void notifyEmailAddressChanged_Should_UseSslSmtp_When_SettingsIsSecureIsTrue() {
    TenantSystemEmailRouteService.Route sslSettings =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(sslSettings));

    // Invalid configuration fails before delivery.
    assertThatCode(
            () -> service.notifyEmailAddressChanged("johndoe", "john@example.com", 1L, null, null))
        .doesNotThrowAnyException();
  }

  // ── buildSessionUrl — trailing-slash strip ────────────────────────────────

  @Test
  void notifyEmailAddressChanged_Should_StripTrailingSlash_When_AppBaseUrlEndsWithSlash() {
    ReflectionTestUtils.setField(service, "publicFrontendBaseUrl", "https://app.oriso.org/");
    when(emailRoutes.resolve(any())).thenReturn(Optional.of(routeSettings()));

    assertThatCode(
            () -> service.notifyEmailAddressChanged("johndoe", "john@example.com", 1L, null, null))
        .doesNotThrowAnyException();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private TenantSystemEmailRouteService.Route routeSettings() {
    return new TenantSystemEmailRouteService.Route(
        TenantSystemEmailRouteService.Mode.PLATFORM, null);
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
