package de.caritas.cob.userservice.api.service.servicenotice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.ServiceNoticeCampaign;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer.Tone;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailRouteService;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeAudience.MailTarget;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Renders the real system-notice templates; only the tenant lookups are stubbed. */
class ServiceNoticeMailComposerTest {
  private final TenantService tenants = mock(TenantService.class);
  private final TenantTemplateSupplier tenantTemplates = mock(TenantTemplateSupplier.class);
  private final EmailBrandingResolver branding = mock(EmailBrandingResolver.class);
  private final TenantEmailBrandValues brandValues = mock(TenantEmailBrandValues.class);
  private final TenantSystemEmailRouteService routes = mock(TenantSystemEmailRouteService.class);
  private final TenantSystemEmailRouteService.Route route =
      new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
  private final ServiceNoticeMailComposer composer =
      new ServiceNoticeMailComposer(
          tenants, tenantTemplates, branding, brandValues, new OrisoEmailRenderer(true), routes);
  private final ServiceNoticeCampaign campaign = new ServiceNoticeCampaign();

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(composer, "multitenancyEnabled", true);
    ReflectionTestUtils.setField(composer, "singleDomainMultitenancy", false);
    ReflectionTestUtils.setField(composer, "applicationBaseUrl", "https://oriso.example");
    campaign.setCampaignKey("maintenance-1");
    campaign.setMaintenanceDate(LocalDate.of(2026, 10, 9));
    campaign.setMaintenanceStart(LocalTime.of(22, 0));
    campaign.setMaintenanceEnd(LocalTime.of(23, 30));
    campaign.setStatusUrl("https://status.operator.dev/maintenance");
    var tenant = new RestrictedTenantDTO().id(7L).subdomain("centre-a");
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    when(tenantTemplates.getTenantBaseUrl(tenant)).thenReturn("https://centre-a.oriso.example");
    when(routes.resolve(7L)).thenReturn(Optional.of(route));
    var brand = new EmailBranding("ORISO", null, "#123456", null, null);
    when(branding.resolveNotification(7L, "https://centre-a.oriso.example")).thenReturn(brand);
    var values = new HashMap<String, String>();
    values.put("platformName", "Independent Platform");
    values.put("orgName", "Example Charity");
    values.put("orgAddress", "Main Street 1");
    values.put("contactLine", "help@example.org");
    values.put("primaryColor", "#123456");
    values.put("accentColor", "#123456");
    values.put("logoUrl", "");
    values.put("imprintUrl", "https://centre-a.oriso.example/impressum");
    values.put("privacyUrl", "https://centre-a.oriso.example/datenschutz");
    when(brandValues.values(any(), eq(7L))).thenReturn(values);
  }

  @Test
  void rendersTheWindowStatusLinkAndUnsubscribeLinkInTheRecipientsLanguage() {
    var composed =
        composer.compose(campaign, new MailTarget("lead@centre-a.org", 7L, Tone.EN)).orElseThrow();

    assertThat(composed.tenantId()).isEqualTo(7L);
    assertThat(composed.route()).isEqualTo(route);
    assertThat(composed.recipient()).isEqualTo("lead@centre-a.org");
    assertThat(composed.email().subject()).contains("2026-10-09");
    assertThat(composed.email().text())
        .contains("A short maintenance break")
        .contains("22:00")
        .contains("23:30")
        .contains("https://status.operator.dev/maintenance")
        .contains("https://centre-a.oriso.example/profile/einstellungen/email")
        .doesNotContain("{{");
    assertThat(composed.email().html()).contains("https://status.operator.dev/maintenance");
  }

  @Test
  void theGermanMailUsesTheGermanCopy() {
    var composed =
        composer
            .compose(campaign, new MailTarget("lead@centre-a.org", 7L, Tone.DE_FORMAL))
            .orElseThrow();

    assertThat(composed.email().text()).doesNotContain("A short maintenance break");
    assertThat(composed.email().text()).contains("22:00");
  }

  @Test
  void aTenantThatSwitchedSystemMailOffGetsNoMail() {
    when(routes.resolve(7L)).thenReturn(Optional.empty());

    assertThat(composer.compose(campaign, new MailTarget("lead@centre-a.org", 7L, Tone.EN)))
        .isEmpty();
  }

  @Test
  void aTenantWithoutSubdomainFailsInsteadOfFallingBackToThePlatformAddress() {
    when(tenants.getRestrictedTenantDataFresh(7L)).thenReturn(new RestrictedTenantDTO().id(7L));

    assertThatThrownBy(
            () -> composer.compose(campaign, new MailTarget("lead@centre-a.org", 7L, Tone.EN)))
        .isInstanceOf(IllegalStateException.class);
  }
}
