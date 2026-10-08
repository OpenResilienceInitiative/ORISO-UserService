package de.caritas.cob.userservice.api.service.notification;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggle;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseHandoverMailSenderTest {
  private final TenantSystemEmailRouteService routes = mock(TenantSystemEmailRouteService.class);
  private final TenantSystemEmailDelivery delivery = mock(TenantSystemEmailDelivery.class);
  private final TenantService tenants = mock(TenantService.class);
  private final TenantTemplateSupplier urls = mock(TenantTemplateSupplier.class);
  private final CaseHandoverMailComposer composer = mock(CaseHandoverMailComposer.class);
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final ReleaseToggleService toggles = mock(ReleaseToggleService.class);
  private final Session session = new Session();

  @BeforeEach
  void currentRecipient() {
    var user = new User();
    user.setEmail("asker@example.test");
    user.setNotificationsEnabled(true);
    user.setNotificationsSettings("{\"reassignmentNotificationEnabled\":true}");
    session.setUser(user);
    session.setTenantId(40L);
    session.setId(77L);
    when(sessions.findById(77L)).thenReturn(Optional.of(session));
    when(toggles.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS)).thenReturn(true);
  }

  private final CaseHandoverMailSender sender =
      new CaseHandoverMailSender(routes, delivery, tenants, urls, composer, sessions, toggles);

  @Test
  void sendsThroughTheTenantPlatformRouteAndCorrectOutcomePurpose() {
    var mail =
        mail(40L, CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED, "asker@example.test");
    var route =
        new TenantSystemEmailRouteService.Route(
            TenantSystemEmailRouteService.Mode.PLATFORM, "#123456");
    var tenant = mock(RestrictedTenantDTO.class);
    var content =
        new OrisoEmailRenderer.RenderedEmail("New notification", "<p>Sign in</p>", "Sign in");
    when(routes.resolve(40L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(40L)).thenReturn(tenant);
    when(tenant.getId()).thenReturn(40L);
    when(urls.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.test");
    when(composer.compose(mail, "https://tenant.example.test")).thenReturn(content);

    sender.send(mail);

    verify(delivery)
        .sendConfirmed(
            40L,
            route,
            TenantSystemEmailDelivery.Purpose.HANDOVER_REQUESTED,
            "asker@example.test",
            content);
  }

  @Test
  void disabledSystemMailDoesNotRenderOrSend() {
    when(routes.resolve(40L)).thenReturn(Optional.empty());
    sender.send(mail(40L, CaseHandoverEmailNotification.Outcome.GRANTED, "incoming@example.test"));
    verifyNoInteractions(tenants, urls, composer, delivery);
  }

  @Test
  void wrongTenantCannotComposeOrSend() {
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.OWN, null);
    var otherTenant = mock(RestrictedTenantDTO.class);
    when(routes.resolve(40L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(40L)).thenReturn(otherTenant);
    when(otherTenant.getId()).thenReturn(41L);
    sender.send(mail(40L, CaseHandoverEmailNotification.Outcome.GRANTED, "incoming@example.test"));
    verifyNoInteractions(urls, composer, delivery);
  }

  @Test
  void missingTenantSubdomainCannotGenerateARecipientLink() {
    var route =
        new TenantSystemEmailRouteService.Route(
            TenantSystemEmailRouteService.Mode.PLATFORM, "#123456");
    var tenant = mock(RestrictedTenantDTO.class);
    when(routes.resolve(40L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(40L)).thenReturn(tenant);
    when(tenant.getId()).thenReturn(40L);
    when(urls.getTenantBaseUrl(tenant)).thenReturn("https://null.example.test");

    sender.send(mail(40L, CaseHandoverEmailNotification.Outcome.GRANTED, "incoming@example.test"));

    verifyNoInteractions(composer, delivery);
  }

  @Test
  void newlyDisabledConversationEmailPolicyPreventsTheQueuedConsentEmail() {
    var route =
        new TenantSystemEmailRouteService.Route(
            TenantSystemEmailRouteService.Mode.PLATFORM, "#123456");
    var tenant =
        new RestrictedTenantDTO()
            .id(40L)
            .settings(new Settings().featureAskerEmailAgencyCounsellingEnabled(false));
    when(routes.resolve(40L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(40L)).thenReturn(tenant);
    when(urls.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.test");
    sender.send(
        mail(40L, CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED, "asker@example.test"));
    verifyNoInteractions(composer, delivery);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType.class)
  void currentAccountAndFreshOrganisationMustBothPermitConsentEmail(
      de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType type) {
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    var tenant =
        new RestrictedTenantDTO()
            .id(40L)
            .settings(new Settings().featureAskerEmailAgencyCounsellingEnabled(true));
    var content = new OrisoEmailRenderer.RenderedEmail("Notice", "<p>Sign in</p>", "Sign in");
    var mail =
        new CaseHandoverEmailNotification.Mail(
            12L,
            77L,
            "!room",
            CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED,
            40L,
            "asker@example.test",
            LanguageCode.en,
            null,
            type);
    when(routes.resolve(40L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(40L)).thenReturn(tenant);
    when(urls.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.test");
    when(composer.compose(mail, "https://tenant.example.test")).thenReturn(content);
    sender.send(mail);
    verify(delivery)
        .sendConfirmed(
            40L,
            route,
            TenantSystemEmailDelivery.Purpose.HANDOVER_REQUESTED,
            "asker@example.test",
            content);
    org.mockito.Mockito.clearInvocations(delivery, composer);
    session.getUser().setNotificationsEnabled(false);
    sender.send(mail);
    verifyNoInteractions(delivery, composer);
    session.getUser().setNotificationsEnabled(true);
    tenant.getSettings().setFeatureAskerEmailAgencyCounsellingEnabled(false);
    sender.send(mail);
    verifyNoInteractions(delivery, composer);
    tenant.getSettings().setFeatureAskerEmailAgencyCounsellingEnabled(true);
    session.getUser().setNotificationsSettings("{\"reassignmentNotificationEnabled\":false}");
    sender.send(mail);
    verifyNoInteractions(delivery, composer);
  }

  @Test
  void legacyAnonymousSessionUsesLiveChatDefaultsAndFreshExplicitOverride() {
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    var tenant = new RestrictedTenantDTO().id(40L);
    var mail =
        mail(40L, CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED, "asker@example.test");
    session.setRegistrationType(Session.RegistrationType.ANONYMOUS);
    when(routes.resolve(40L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(40L)).thenReturn(tenant);
    sender.send(mail);
    verifyNoInteractions(composer, delivery);
    tenant.setSettings(new Settings().featureAskerEmailLiveChatEnabled(true));
    when(urls.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.test");
    sender.send(mail);
    verify(composer).compose(mail, "https://tenant.example.test");
    org.mockito.Mockito.clearInvocations(composer, delivery);
    tenant.getSettings().setFeatureAskerEmailEnabled(false);
    sender.send(mail);
    verifyNoInteractions(composer, delivery);
  }

  private static CaseHandoverEmailNotification.Mail mail(
      long tenantId, CaseHandoverEmailNotification.Outcome outcome, String recipient) {
    return new CaseHandoverEmailNotification.Mail(
        12L, 77L, "!room:example.test", outcome, tenantId, recipient, LanguageCode.en, null);
  }
}
