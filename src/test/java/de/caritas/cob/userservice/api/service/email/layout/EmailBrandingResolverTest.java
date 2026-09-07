package de.caritas.cob.userservice.api.service.email.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Theming;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpClientErrorException;

/** Branding resolution and its fallbacks (ORISO-UserService#914). */
@ExtendWith(MockitoExtension.class)
class EmailBrandingResolverTest {

  @Mock private TenantService tenantService;
  @Mock private TenantTemplateSupplier tenantTemplateSupplier;

  private EmailBrandingResolver resolver(String platformLogoUrl) {
    return new EmailBrandingResolver(
        tenantService, tenantTemplateSupplier, "ORISO", platformLogoUrl, "https://app.oriso.org/");
  }

  private void givenNoTemplateAttributes() {
    lenient().when(tenantTemplateSupplier.getTenantBaseUrl(any())).thenReturn(null);
  }

  private static RestrictedTenantDTO tenant(String name, Theming theming) {
    RestrictedTenantDTO tenant = new RestrictedTenantDTO();
    tenant.setId(7L);
    tenant.setName(name);
    tenant.setTheming(theming);
    return tenant;
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"https://app.oriso.org/%zz", "https://[broken", "https:///missing-host"})
  void malformedConfiguredBaseOmitsLogoWithoutAbortingMail(String base) {
    Theming stored = new Theming();
    stored.setLogo("data:image/png;base64,iVBORw0KGgo=");
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", stored));
    var branding =
        new EmailBrandingResolver(
                tenantService,
                tenantTemplateSupplier,
                "ORISO",
                "https://app.oriso.org/logo.png",
                base)
            .resolve(7L);
    assertThat(branding.logoUrl()).isNull();
    assertThat(branding.brandName()).isEqualTo("Nord");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "https://app.oriso.org,https://app.oriso.org:443/logo.png",
    "https://app.oriso.org:443,https://app.oriso.org/logo.png",
    "http://app.oriso.org,http://app.oriso.org:80/logo.png",
    "http://app.oriso.org:80,http://app.oriso.org/logo.png"
  })
  void acceptsSameOriginWhenDefaultPortIsExplicit(String base, String logo) {
    var branding =
        new EmailBrandingResolver(tenantService, tenantTemplateSupplier, "ORISO", logo, base)
            .resolve(null);
    assertThat(branding.logoUrl()).isEqualTo(logo);
  }

  @Test
  void stillRejectsDifferentPortAndScheme() {
    for (String logo :
        java.util.List.of("https://app.oriso.org:444/logo.png", "http://app.oriso.org/logo.png")) {
      assertThat(resolver(logo).resolve(null).logoUrl()).isNull();
    }
  }

  @Test
  void resolvesUpdatedAndRemovedBrandingWithoutWaitingForTenantCacheExpiry() {
    var staleTenant = tenant("Old name", new Theming());
    var updatedTheming = new Theming();
    updatedTheming.setLogo("data:image/png;base64,iVBORw0KGgo=");
    var updatedTenant = tenant("Springfield", updatedTheming);
    var restoredTenant = tenant("Springfield", new Theming());
    lenient().when(tenantService.getRestrictedTenantData(7L)).thenReturn(staleTenant);
    lenient()
        .when(tenantService.getRestrictedTenantDataFresh(7L))
        .thenReturn(updatedTenant, restoredTenant);

    var resolver = resolver("");
    var afterUpdate = resolver.resolve(7L);
    assertThat(afterUpdate.brandName()).isEqualTo("Springfield");
    assertThat(afterUpdate.logoUrl())
        .isEqualTo("https://app.oriso.org/service/tenant/public/branding/7/logo");
    assertThat(resolver.resolve(7L).logoUrl()).isNull();
  }

  // --- logo -----------------------------------------------------------------------------

  @Test
  void resolve_Should_preferTheTenantLogo() {
    givenNoTemplateAttributes();
    Theming theming = new Theming();
    theming.setLogo("https://app.oriso.org/tenant.png");
    theming.setAssociationLogo("https://app.oriso.org/association.png");
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", theming));

    EmailBranding branding = resolver("https://app.oriso.org/platform.png").resolve(7L);

    assertThat(branding.logoUrl()).isEqualTo("https://app.oriso.org/tenant.png");
    assertThat(branding.brandName()).isEqualTo("Nord");
  }

  @Test
  void resolve_Should_fallBackToTheAssociationLogoThenThePlatformLogo() {
    givenNoTemplateAttributes();
    Theming associationOnly = new Theming();
    associationOnly.setAssociationLogo("https://app.oriso.org/association.png");
    when(tenantService.getRestrictedTenantDataFresh(7L))
        .thenReturn(tenant("Nord", associationOnly));

    assertThat(resolver("https://app.oriso.org/platform.png").resolve(7L).logoUrl())
        .isEqualTo("https://app.oriso.org/association.png");

    assertThat(resolver("https://app.oriso.org/platform.png").resolve(null).logoUrl())
        .isEqualTo("https://app.oriso.org/platform.png");
  }

  @Test
  void resolve_ShouldProxyStoredLogosThroughTheTenantsPublicBrandingEndpoint() {
    Theming base64Logo = new Theming();
    base64Logo.setLogo("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQ");
    base64Logo.setAssociationLogo("data:image/png;base64,iVBORw0KGgo=");
    RestrictedTenantDTO resolvedTenant = tenant("Nord", base64Logo);
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(resolvedTenant);
    when(tenantTemplateSupplier.getTenantBaseUrl(resolvedTenant)).thenReturn("https://nord.org");

    EmailBranding branding = resolver("").resolve(7L);

    assertThat(branding.logoUrl())
        .isEqualTo("https://app.oriso.org/service/tenant/public/branding/7/logo");
    assertThat(branding.hasLogo()).isTrue();
  }

  @Test
  void resolve_ShouldUseThePlatformBrandingEndpointWhenNoTenantIsKnown() {
    givenNoTemplateAttributes();
    Theming platformTheming = new Theming();
    platformTheming.setLogo("data:image/png;base64,iVBORw0KGgo=");
    var platformTenant = tenant("ORISO", platformTheming);
    platformTenant.setId(40L);
    when(tenantTemplateSupplier.getPlatformTenantData()).thenReturn(platformTenant);

    EmailBranding branding = resolver("").resolve(null);

    assertThat(branding.logoUrl())
        .isEqualTo("https://app.oriso.org/service/tenant/public/branding/40/logo");
  }

  @Test
  void refusesThirdPartyLogoUrlsInsteadOfLeakingMailReads() {
    Theming theming = new Theming();
    theming.setLogo("https://tracking.example.org/pixel.png");
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", theming));
    assertThat(resolver("https://tracking.example.org/platform.png").resolve(7L).logoUrl())
        .isNull();
  }

  // --- colour ---------------------------------------------------------------------------

  /**
   * The mail follows the product colour rule and nothing else (#914, final decision): the light
   * rendering uses the dark accent, which on the tenant is {@code theming.primaryColor}.
   */
  @Test
  void resolve_Should_useTheTenantPrimaryColorAsTheLightRenderingAccent() {
    givenNoTemplateAttributes();
    Theming primary = new Theming();
    primary.setPrimaryColor("#123456");
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", primary));

    assertThat(resolver("").resolve(7L).accentColor()).isEqualTo("#123456");
  }

  /**
   * {@code secondaryColor} is dead weight and must not silently become the mail accent: ORISO-Admin
   * writes it as {@code null} on every theming save, so a chain step reading it can never resolve
   * and would only hide the real fallback.
   */
  @Test
  void resolve_Should_ignoreTheTenantSecondaryColor() {
    givenNoTemplateAttributes();
    Theming secondaryOnly = new Theming();
    secondaryOnly.setSecondaryColor("#654321");
    when(tenantService.getRestrictedTenantDataFresh(8L)).thenReturn(tenant("Sued", secondaryOnly));

    assertThat(resolver("").resolve(8L).accentColor()).isEqualTo(EmailColors.PLATFORM_ACCENT_DARK);
  }

  /** The platform fallback is the product's own dark accent, not an invented colour. */
  @Test
  void resolve_Should_fallBackToTheProductDarkAccent_When_NoTenantColorIsConfigured() {
    givenNoTemplateAttributes();

    EmailBranding branding = resolver("").resolve(null);

    assertThat(branding.accentColor()).isEqualTo("#a5000a");
    assertThat(branding.brandName()).isEqualTo("ORISO");
    assertThat(branding.logoUrl()).isNull();
  }

  @Test
  void resolve_Should_ignoreAMalformedTenantPrimaryColor() {
    givenNoTemplateAttributes();
    Theming broken = new Theming();
    broken.setPrimaryColor("not-a-color");
    when(tenantService.getRestrictedTenantDataFresh(9L)).thenReturn(tenant("Ost", broken));

    assertThat(resolver("").resolve(9L).accentColor()).isEqualTo(EmailColors.PLATFORM_ACCENT_DARK);
  }

  // --- degradation ----------------------------------------------------------------------

  /** Tenant-admin invites are sent before the tenant exists — a 404 is normal, not an error. */
  @Test
  void resolve_Should_degradeToPlatformBranding_When_TenantLookupFails() {
    givenNoTemplateAttributes();
    when(tenantService.getRestrictedTenantDataFresh(anyLong()))
        .thenThrow(
            HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "nf", null, null, null));

    EmailBranding branding = resolver("").resolve(4711L);

    assertThat(branding.brandName()).isEqualTo("ORISO");
    assertThat(branding.accentColor()).isEqualTo(EmailColors.PLATFORM_ACCENT_DARK);
  }

  @Test
  void resolve_ShouldLoadPlatformBranding_When_NoTenantIdIsKnown() {
    givenNoTemplateAttributes();

    resolver("").resolve(null);

    org.mockito.Mockito.verify(tenantTemplateSupplier).getPlatformTenantData();
  }

  // --- footer ---------------------------------------------------------------------------

  @Test
  void resolve_Should_buildTheImprintAndPrivacyUrlsFromTheResolvedTenantsOwnBaseUrl() {
    RestrictedTenantDTO resolvedTenant = tenant("Nord", null);
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(resolvedTenant);
    when(tenantTemplateSupplier.getTenantBaseUrl(resolvedTenant)).thenReturn("https://nord.org");

    EmailBranding branding = resolver("").resolve(7L);

    assertThat(branding.imprintUrl()).isEqualTo("https://nord.org/impressum");
    assertThat(branding.privacyUrl()).isEqualTo("https://nord.org/datenschutz");
  }

  /**
   * Reproduces ORISO-UserService#915's follow-up bug: a super-admin invites a counsellor for tenant
   * 42. The ambient {@link de.caritas.cob.userservice.api.tenant.TenantContext} stays in the
   * super-admin's own (tenant-less) context throughout, but the footer must still carry tenant 42's
   * own imprint/privacy — not the technical context's, and not another tenant's — because it is
   * rendered together with tenant 42's name, logo and accent.
   */
  @Test
  void resolve_Should_useTheRequestedTenantsFooter_Even_WhenTheAmbientContextIsTechnical() {
    RestrictedTenantDTO tenant42 = tenant("Tenant42", null);
    when(tenantService.getRestrictedTenantDataFresh(42L)).thenReturn(tenant42);
    when(tenantTemplateSupplier.getTenantBaseUrl(tenant42)).thenReturn("https://tenant42.org");

    EmailBranding branding = resolver("").resolve(42L);

    assertThat(branding.brandName()).isEqualTo("Tenant42");
    assertThat(branding.imprintUrl()).isEqualTo("https://tenant42.org/impressum");
    assertThat(branding.privacyUrl()).isEqualTo("https://tenant42.org/datenschutz");
  }

  @Test
  void resolve_Should_fallBackToTheApplicationBaseUrl_When_TheTenantHasNoOwnBaseUrl() {
    givenNoTemplateAttributes();
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", null));

    EmailBranding branding = resolver("").resolve(7L);

    assertThat(branding.imprintUrl()).isEqualTo("https://app.oriso.org/impressum");
    assertThat(branding.privacyUrl()).isEqualTo("https://app.oriso.org/datenschutz");
  }

  @Test
  void resolve_Should_fallBackToTheApplicationBaseUrl_When_NoTenantIdIsKnown() {
    givenNoTemplateAttributes();

    EmailBranding branding = resolver("").resolve(null);

    assertThat(branding.imprintUrl()).isEqualTo("https://app.oriso.org/impressum");
    assertThat(branding.privacyUrl()).isEqualTo("https://app.oriso.org/datenschutz");
  }
}
