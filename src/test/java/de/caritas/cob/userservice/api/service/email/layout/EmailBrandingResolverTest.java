package de.caritas.cob.userservice.api.service.email.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Theming;
import java.util.List;
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
    lenient().when(tenantTemplateSupplier.getTemplateAttributes()).thenReturn(List.of());
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
    theming.setLogo("https://cdn.example.org/tenant.png");
    theming.setAssociationLogo("https://cdn.example.org/association.png");
    when(tenantService.getRestrictedTenantData(7L)).thenReturn(tenant("Nord", theming));

    EmailBranding branding = resolver("https://cdn.example.org/platform.png").resolve(7L);

    assertThat(branding.logoUrl()).isEqualTo("https://cdn.example.org/tenant.png");
    assertThat(branding.brandName()).isEqualTo("Nord");
  }

  @Test
  void resolve_Should_fallBackToTheAssociationLogoThenThePlatformLogo() {
    givenNoTemplateAttributes();
    Theming associationOnly = new Theming();
    associationOnly.setAssociationLogo("https://cdn.example.org/association.png");
    when(tenantService.getRestrictedTenantData(7L)).thenReturn(tenant("Nord", associationOnly));

    assertThat(resolver("https://cdn.example.org/platform.png").resolve(7L).logoUrl())
        .isEqualTo("https://cdn.example.org/association.png");

    assertThat(resolver("https://cdn.example.org/platform.png").resolve(null).logoUrl())
        .isEqualTo("https://cdn.example.org/platform.png");
  }

  /**
   * Tenant theming may store an inline base64 image. {@code data:} URIs are blocked by Gmail and
   * Outlook, so they must degrade to the wordmark rather than produce a broken image.
   */
  @Test
  void resolve_Should_ignoreBase64AndDataUriLogosAndFallBackToTheWordmark() {
    givenNoTemplateAttributes();
    Theming base64Logo = new Theming();
    base64Logo.setLogo("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQ");
    base64Logo.setAssociationLogo("data:image/png;base64,iVBORw0KGgo=");
    when(tenantService.getRestrictedTenantData(7L)).thenReturn(tenant("Nord", base64Logo));

    EmailBranding branding = resolver("").resolve(7L);

    assertThat(branding.logoUrl()).isNull();
    assertThat(branding.hasLogo()).isFalse();
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
    when(tenantService.getRestrictedTenantData(7L)).thenReturn(tenant("Nord", primary));

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
    when(tenantService.getRestrictedTenantData(8L)).thenReturn(tenant("Sued", secondaryOnly));

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
    when(tenantService.getRestrictedTenantData(9L)).thenReturn(tenant("Ost", broken));

    assertThat(resolver("").resolve(9L).accentColor()).isEqualTo(EmailColors.PLATFORM_ACCENT_DARK);
  }

  // --- degradation ----------------------------------------------------------------------

  /** Tenant-admin invites are sent before the tenant exists — a 404 is normal, not an error. */
  @Test
  void resolve_Should_degradeToPlatformBranding_When_TenantLookupFails() {
    givenNoTemplateAttributes();
    when(tenantService.getRestrictedTenantData(anyLong()))
        .thenThrow(
            HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "nf", null, null, null));

    EmailBranding branding = resolver("").resolve(4711L);

    assertThat(branding.brandName()).isEqualTo("ORISO");
    assertThat(branding.accentColor()).isEqualTo(EmailColors.PLATFORM_ACCENT_DARK);
  }

  @Test
  void resolve_Should_notCallTenantService_When_NoTenantIdIsKnown() {
    givenNoTemplateAttributes();

    resolver("").resolve(null);

    verifyNoInteractions(tenantService);
  }

  // --- footer ---------------------------------------------------------------------------

  @Test
  void resolve_Should_reuseTheImprintAndPrivacyUrlsFromTheTenantTemplateSupplier() {
    when(tenantTemplateSupplier.getTemplateAttributes())
        .thenReturn(
            List.of(
                new TemplateDataDTO()
                    .key("tenant_urlimpressum")
                    .value("https://nord.org/impressum"),
                new TemplateDataDTO()
                    .key("tenant_urldatenschutz")
                    .value("https://nord.org/datenschutz")));

    EmailBranding branding = resolver("").resolve(null);

    assertThat(branding.imprintUrl()).isEqualTo("https://nord.org/impressum");
    assertThat(branding.privacyUrl()).isEqualTo("https://nord.org/datenschutz");
  }

  @Test
  void resolve_Should_fallBackToTheApplicationBaseUrl_When_TemplateSupplierFails() {
    when(tenantTemplateSupplier.getTemplateAttributes())
        .thenThrow(new IllegalStateException("no tenant context"));

    EmailBranding branding = resolver("").resolve(null);

    assertThat(branding.imprintUrl()).isEqualTo("https://app.oriso.org/impressum");
    assertThat(branding.privacyUrl()).isEqualTo("https://app.oriso.org/datenschutz");
  }
}
