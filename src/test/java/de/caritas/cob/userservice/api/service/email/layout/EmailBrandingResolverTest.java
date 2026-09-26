package de.caritas.cob.userservice.api.service.email.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Theming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpClientErrorException;

/** Branding resolution and its fallbacks (ORISO-UserService#914). */
@ExtendWith(MockitoExtension.class)
class EmailBrandingResolverTest {

  @Mock private TenantService tenantService;
  @Mock private TenantTemplateSupplier tenantTemplateSupplier;

  @BeforeEach
  void tenantUrls() {
    lenient().when(tenantTemplateSupplier.getTenantBaseUrl(any())).thenReturn("https://tenant.org");
  }

  private EmailBrandingResolver resolver(String platformLogoUrl) {
    return new EmailBrandingResolver(
        tenantService, tenantTemplateSupplier, "ORISO", platformLogoUrl, "https://app.oriso.org/");
  }

  private void givenNoTemplateAttributes() {
    lenient().when(tenantTemplateSupplier.getTenantBaseUrl(any())).thenReturn("https://tenant.org");
  }

  private static RestrictedTenantDTO tenant(String name, Theming theming) {
    RestrictedTenantDTO tenant = new RestrictedTenantDTO();
    tenant.setId(7L);
    tenant.setName(name);
    tenant.setTheming(theming);
    return tenant;
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

  /**
   * Even with a real subdomain (standard multitenancy), the id pins the tenant: the link neither
   * depends on the tenant's DNS name nor on host-based tenant resolution in TenantService.
   */
  @Test
  void resolve_Should_pinAStoredLogoToTheTenantId_Even_WhenTheTenantHasItsOwnSubdomain() {
    Theming base64Logo = new Theming();
    base64Logo.setLogo("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQ");
    base64Logo.setAssociationLogo("data:image/png;base64,iVBORw0KGgo=");
    RestrictedTenantDTO resolvedTenant = tenant("Nord", base64Logo);
    resolvedTenant.setId(12L);
    resolvedTenant.setSubdomain("nord");
    when(tenantService.getRestrictedTenantDataFresh(12L)).thenReturn(resolvedTenant);
    lenient()
        .when(tenantTemplateSupplier.getTenantBaseUrl(resolvedTenant))
        .thenReturn("https://nord.app.oriso.org");

    EmailBranding branding = resolver("").resolve(12L);

    assertThat(branding.logoUrl())
        .isEqualTo("https://app.oriso.org/service/tenant/public/branding/12/logo");
    assertThat(branding.hasLogo()).isTrue();
  }

  @Test
  void resolve_Should_pinThePlatformTenantsStoredLogoToItsId_When_NoTenantIsKnown() {
    givenNoTemplateAttributes();
    Theming platformTheming = new Theming();
    platformTheming.setLogo("data:image/png;base64,iVBORw0KGgo=");
    RestrictedTenantDTO platform = tenant("ORISO", platformTheming);
    platform.setId(1L);
    when(tenantService.getPlatformTenantDataFresh()).thenReturn(platform);

    EmailBranding branding = resolver("").resolve(null);

    assertThat(branding.logoUrl())
        .isEqualTo("https://app.oriso.org/service/tenant/public/branding/1/logo");
  }

  /**
   * Measured on staging 2026-09-21/22: Träger there have an empty subdomain, so the tenant base URL
   * degenerates to {@code https://.<host>}. The mail logo must still point at this tenant's own
   * image, pinned by id on the application origin.
   */
  @Test
  void resolve_Should_rejectAnEmptySubdomainWithoutAValidTenantUrl() {
    Theming stored = new Theming();
    stored.setLogo("data:image/png;base64,iVBORw0KGgo=");
    RestrictedTenantDTO tenant12 = tenant("Traeger Zwoelf", stored);
    tenant12.setId(12L);
    tenant12.setSubdomain("");
    when(tenantService.getRestrictedTenantDataFresh(12L)).thenReturn(tenant12);
    lenient()
        .when(tenantTemplateSupplier.getTenantBaseUrl(tenant12))
        .thenReturn("https://.online-beratung.neusta-integrate.de");

    assertThatThrownBy(() -> resolver("").resolve(12L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no valid base URL");
  }

  @Test
  void firstAbsoluteUrl_Should_rejectUrlsWithoutARealHost() {
    assertThat(EmailBrandingResolver.firstAbsoluteUrl("https://.example.org")).isNull();
    assertThat(EmailBrandingResolver.firstAbsoluteUrl("https://")).isNull();
    assertThat(EmailBrandingResolver.firstAbsoluteUrl("https:///x")).isNull();
    assertThat(
            EmailBrandingResolver.firstAbsoluteUrl(
                "https://.online-beratung.neusta-integrate.de/impressum"))
        .isNull();
    assertThat(EmailBrandingResolver.firstAbsoluteUrl("https://.example.org", "https://ok.org/a"))
        .isEqualTo("https://ok.org/a");
  }

  /** The empty-subdomain base URL must not leak into the footer either. */
  @Test
  void resolve_Should_fallBackToTheApplicationFooter_When_TheTenantBaseUrlHasNoHost() {
    RestrictedTenantDTO tenant12 = tenant("Traeger Zwoelf", null);
    tenant12.setId(12L);
    when(tenantService.getRestrictedTenantDataFresh(12L)).thenReturn(tenant12);
    when(tenantTemplateSupplier.getTenantBaseUrl(tenant12))
        .thenReturn("https://.online-beratung.neusta-integrate.de");

    assertThatThrownBy(() -> resolver("").resolve(12L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no valid base URL");
  }

  @Test
  void resolve_Should_keepTheConfiguredPlatformLogo_When_TheTenantHasNoLogoOfItsOwn() {
    givenNoTemplateAttributes();
    RestrictedTenantDTO tenant12 = tenant("Traeger Zwoelf", new Theming());
    tenant12.setId(12L);
    when(tenantService.getRestrictedTenantDataFresh(12L)).thenReturn(tenant12);

    assertThat(resolver("https://app.oriso.org/platform.png").resolve(12L).logoUrl())
        .isEqualTo("https://app.oriso.org/platform.png");
  }

  @Test
  void resolve_Should_dropAnExternalLogoInsteadOfMakingTheRecipientFetchIt() {
    Theming theming = new Theming();
    theming.setLogo("https://tracker.example/logo.png");
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", theming));

    assertThat(resolver("https://tracker.example/platform.png").resolve(7L).logoUrl()).isNull();
  }

  @Test
  void resolve_Should_shareOneFreshLookupWithinTheShortCache() {
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", null));
    EmailBrandingResolver cached =
        new EmailBrandingResolver(
            tenantService, tenantTemplateSupplier, "ORISO", "", "https://app.oriso.org", 10L);

    cached.resolve(7L);
    cached.resolve(7L);

    verify(tenantService, times(1)).getRestrictedTenantDataFresh(7L);
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://tracker.example/logo.png", ""})
  void resolve_Should_notLinkToAnAssetThePublicEndpointCannotServe(String primaryLogo) {
    givenNoTemplateAttributes();
    Theming theming = new Theming();
    theming.setLogo(primaryLogo);
    theming.setAssociationLogo("data:image/png;base64,iVBORw0KGgo=");
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", theming));

    assertThat(resolver("https://app.oriso.org/platform.png").resolve(7L).logoUrl())
        .isEqualTo("https://app.oriso.org/platform.png");
  }

  @Test
  void constructor_Should_limitBrandingCacheToTenSeconds() {
    EmailBrandingResolver cached =
        new EmailBrandingResolver(
            tenantService, tenantTemplateSupplier, "ORISO", "", "https://app.oriso.org", 300L);

    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(cached, "cacheTtlNanos"))
        .isEqualTo(10_000_000_000L);
  }

  @Test
  void constructor_Should_rejectAMissingApplicationUrl() {
    assertThatThrownBy(
            () -> new EmailBrandingResolver(tenantService, tenantTemplateSupplier, "ORISO", "", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.base.url");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ftp://app.oriso.org",
        "https://user:secret@app.oriso.org",
        "https://app.oriso.org/?mail=1",
        "https://app.oriso.org/#mail"
      })
  void constructor_Should_rejectApplicationUrlsThatCannotBeSafeMailOrigins(String url) {
    assertThatThrownBy(
            () ->
                new EmailBrandingResolver(tenantService, tenantTemplateSupplier, "ORISO", "", url))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.base.url");
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

  @Test
  void resolve_Should_rejectALightTenantPrimaryColorForAWhiteButtonLabel() {
    Theming light = new Theming();
    light.setPrimaryColor("#ffff00");
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", light));

    assertThat(resolver("").resolve(7L).accentColor()).isEqualTo(EmailColors.PLATFORM_ACCENT_DARK);
  }

  // --- degradation ----------------------------------------------------------------------

  /** Tenant-admin invites are sent before the tenant exists — a 404 is normal, not an error. */
  @Test
  void resolvePendingTenant_Should_usePlatformBranding_When_TenantDoesNotExistYet() {
    givenNoTemplateAttributes();
    when(tenantService.getRestrictedTenantDataFresh(anyLong()))
        .thenThrow(
            HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "nf", null, null, null));

    EmailBranding branding = resolver("").resolvePendingTenant(4711L);

    assertThat(branding.brandName()).isEqualTo("ORISO");
    assertThat(branding.accentColor()).isEqualTo(EmailColors.PLATFORM_ACCENT_DARK);
  }

  @Test
  void resolve_Should_rejectAnUnknownTenantForAnExistingAccount() {
    when(tenantService.getRestrictedTenantDataFresh(7L))
        .thenThrow(
            HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "nf", null, null, null));

    assertThatThrownBy(() -> resolver("").resolve(7L))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
  }

  @Test
  void resolve_Should_notReplaceATenantBrandAfterATenantServiceFailure() {
    when(tenantService.getRestrictedTenantDataFresh(7L))
        .thenThrow(new IllegalStateException("tenant service unavailable"));

    assertThatThrownBy(() -> resolver("").resolve(7L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tenant service unavailable");
  }

  @Test
  void resolve_ShouldLoadPlatformBranding_When_NoTenantIdIsKnown() {
    givenNoTemplateAttributes();

    resolver("").resolve(null);

    org.mockito.Mockito.verify(tenantService).getPlatformTenantDataFresh();
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

    when(tenantTemplateSupplier.getTenantBaseUrl(any())).thenReturn(null);
    assertThatThrownBy(() -> resolver("").resolve(7L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no valid base URL");
  }

  @Test
  void resolve_Should_fallBackToTheApplicationBaseUrl_When_NoTenantIdIsKnown() {
    givenNoTemplateAttributes();

    EmailBranding branding = resolver("").resolve(null);

    assertThat(branding.imprintUrl()).isEqualTo("https://app.oriso.org/impressum");
    assertThat(branding.privacyUrl()).isEqualTo("https://app.oriso.org/datenschutz");
  }
}
