package de.caritas.cob.userservice.api.service.notification;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CaseHandoverMailSenderTest {
  private final TenantSystemEmailRouteService routes = mock(TenantSystemEmailRouteService.class);
  private final TenantSystemEmailDelivery delivery = mock(TenantSystemEmailDelivery.class);
  private final TenantService tenants = mock(TenantService.class);
  private final TenantTemplateSupplier urls = mock(TenantTemplateSupplier.class);
  private final CaseHandoverMailComposer composer = mock(CaseHandoverMailComposer.class);
  private final CaseHandoverMailSender sender =
      new CaseHandoverMailSender(routes, delivery, tenants, urls, composer);

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
    when(tenants.getRestrictedTenantData(40L)).thenReturn(tenant);
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
    when(tenants.getRestrictedTenantData(40L)).thenReturn(otherTenant);
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
    when(tenants.getRestrictedTenantData(40L)).thenReturn(tenant);
    when(tenant.getId()).thenReturn(40L);
    when(urls.getTenantBaseUrl(tenant)).thenReturn("https://null.example.test");

    sender.send(mail(40L, CaseHandoverEmailNotification.Outcome.GRANTED, "incoming@example.test"));

    verifyNoInteractions(composer, delivery);
  }

  private static CaseHandoverEmailNotification.Mail mail(
      long tenantId, CaseHandoverEmailNotification.Outcome outcome, String recipient) {
    return new CaseHandoverEmailNotification.Mail(
        12L, 77L, "!room:example.test", outcome, tenantId, recipient, LanguageCode.en, null);
  }
}
