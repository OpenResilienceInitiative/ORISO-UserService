package de.caritas.cob.userservice.api.service.email.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Theming;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class EmailLogoDimensionsTest {
  static String image(int width, int height) throws Exception {
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", bytes);
    return Base64.getEncoder().encodeToString(bytes.toByteArray());
  }

  @Test
  void readsLegacyAndDataUriHeaders() throws Exception {
    String logo = image(600, 100);
    assertThat(EmailLogoDimensions.read(logo)).isEqualTo(new EmailLogoDimensions(600, 100));
    assertThat(EmailLogoDimensions.read("data:image/png;base64," + logo))
        .isEqualTo(new EmailLogoDimensions(600, 100));
  }

  @Test
  void ignoresMalformedUnsupportedAndOversizedMetadata() {
    for (String value :
        new String[] {
          "broken",
          "https://app.example.org/logo.png",
          "data:image/svg+xml;base64,abcd",
          "data:image/png;base64,abcd",
          "A".repeat(2 * 1024 * 1024)
        }) {
      assertThat(EmailLogoDimensions.read(value)).isNull();
    }
  }

  @Test
  void matchesTheExactServedSourceInsteadOfBorrowingAssociationDimensions() throws Exception {
    Theming theme = new Theming();
    theme.setPrimaryColor("#a5000a");
    theme.setLogo(image(300, 100));
    theme.setAssociationLogo(image(600, 100));
    var service = mock(TenantService.class);
    var tenant = new RestrictedTenantDTO();
    tenant.setId(7L);
    tenant.setName("Tenant");
    tenant.setTheming(theme);
    when(service.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    var supplier = mock(TenantTemplateSupplier.class);
    org.mockito.Mockito.lenient()
        .when(supplier.getTenantBaseUrl(tenant))
        .thenReturn("https://app.example.org");
    var resolver =
        new EmailBrandingResolver(service, supplier, "Platform", "", "https://app.example.org");
    assertThat(resolver.resolve(7L).logoWidth()).isEqualTo(300);
    theme.setLogo(null);
    assertThat(resolver.resolve(7L).logoWidth()).isEqualTo(600);
    theme.setLogo("broken");
    var invalid = resolver.resolve(7L);
    assertThat(invalid.logoWidth()).isNull();
    assertThat(invalid.logoUrl()).endsWith("/branding/7/logo");
    theme.setLogo("https://app.example.org/explicit.png");
    assertThat(resolver.resolve(7L).logoWidth()).isNull();
    assertThat(resolver.resolve(7L).logoUrl()).endsWith("/explicit.png");
  }

  @Test
  void platformAndNotificationMailReadTheirSelectedLogoDimensions() throws Exception {
    Theming theme = new Theming();
    theme.setPrimaryColor("#a5000a");
    theme.setAssociationLogo("data:image/png;base64," + image(600, 100));
    var platform = new RestrictedTenantDTO();
    platform.setId(0L);
    platform.setTheming(theme);
    var tenant = new RestrictedTenantDTO();
    tenant.setId(7L);
    tenant.setTheming(theme);
    var service = mock(TenantService.class);
    when(service.getPlatformTenantDataFresh()).thenReturn(platform);
    when(service.getRestrictedTenantDataFresh(7L)).thenReturn(tenant);
    var supplier = mock(TenantTemplateSupplier.class);
    org.mockito.Mockito.lenient()
        .when(supplier.getTenantBaseUrl(tenant))
        .thenReturn("https://app.example.org");
    var resolver =
        new EmailBrandingResolver(service, supplier, "Platform", "", "https://app.example.org");
    assertThat(resolver.resolve(null).logoWidth()).isEqualTo(600);
    assertThat(resolver.resolveNotification(7L, "https://app.example.org").logoHeight())
        .isEqualTo(100);
  }

  @Test
  void invalidDimensionsCannotSurviveInResolvedBrand() {
    assertThat(
            new EmailBranding("Brand", "https://app.example.org/logo", "#a5000a", null, null, 0, 48)
                .logoWidth())
        .isNull();
    assertThat(new EmailBranding("Brand", "", "#a5000a", null, null, 600, 100).logoWidth())
        .isNull();
    assertThat(
            new EmailBranding("Brand", "https://app.example.org/logo", "#a5000a", null, null)
                .logoWidth())
        .isNull();
  }
}
