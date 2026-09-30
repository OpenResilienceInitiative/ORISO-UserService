package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationFixture;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Theming;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Produces reproducible two-tenant previews for the ADR-026 visual review when requested. */
class EmailBrandingPreviewExportTest {

  private static final String ORIGIN = "http://127.0.0.1:8765";
  private static final String[] OCCASIONS = {
    "anmeldelink", "passwort-zuruecksetzen", "willkommen", "team-aenderung"
  };

  @Test
  void fourCatalogueOccasionsRenderForTwoDistinctTenants() throws IOException {
    TenantService tenants = mock(TenantService.class);
    TenantTemplateSupplier urls = mock(TenantTemplateSupplier.class);
    RestrictedTenantDTO north = tenant(12L, "Nord", "nord", "#123456");
    RestrictedTenantDTO south = tenant(13L, "Süd", "sued", "#654321");
    when(tenants.getRestrictedTenantDataFresh(12L)).thenReturn(north);
    when(tenants.getRestrictedTenantDataFresh(13L)).thenReturn(south);
    when(tenants.getPlatformTenantDataFresh())
        .thenReturn(new RestrictedTenantDTO().id(0L).name("Online-Beratung"));
    when(urls.getTenantBaseUrl(north)).thenReturn(ORIGIN + "/nord");
    when(urls.getTenantBaseUrl(south)).thenReturn(ORIGIN + "/sued");
    OrisoEmailBrand brand =
        new OrisoEmailBrand(
            SenderOrganisationFixture.platformOwner(),
            new EmailBrandingResolver(tenants, urls, "Online-Beratung", "", ORIGIN));
    OrisoEmailRenderer renderer = new OrisoEmailRenderer();
    String output = System.getProperty("mail.preview.output");
    Path outputDir = output == null || output.isBlank() ? null : Path.of(output);
    if (outputDir != null) {
      Files.createDirectories(outputDir.resolve("logos"));
      writeLogo(outputDir, "nord", "#123456", "N");
      writeLogo(outputDir, "sued", "#654321", "S");
    }

    for (RestrictedTenantDTO tenant : new RestrictedTenantDTO[] {north, south}) {
      String slug = tenant.getId().equals(12L) ? "nord" : "sued";
      for (String occasion : OCCASIONS) {
        Map<String, String> values =
            new LinkedHashMap<>(brand.valuesForTenant(ORIGIN, tenant.getId()));
        addOccasionValues(values, occasion);
        var mail = renderer.render(occasion, OrisoEmailRenderer.Tone.DE_FORMAL, values);
        assertThat(mail.html())
            .contains(tenant.getName())
            .contains(ORIGIN + "/logos/" + slug + ".svg")
            .contains(tenant.getTheming().getPrimaryColor())
            .doesNotContain("{{");
        assertThat(mail.text()).doesNotContain("{{");
        if (outputDir != null) {
          Files.writeString(
              outputDir.resolve(slug + "-" + occasion + ".html"),
              mail.html(),
              StandardCharsets.UTF_8);
        }
      }
    }
  }

  private static RestrictedTenantDTO tenant(Long id, String name, String slug, String color) {
    return new RestrictedTenantDTO()
        .id(id)
        .name(name)
        .theming(new Theming().logo(ORIGIN + "/logos/" + slug + ".svg").primaryColor(color));
  }

  private static void addOccasionValues(Map<String, String> values, String occasion) {
    switch (occasion) {
      case "anmeldelink" -> {
        values.put("loginUrl", ORIGIN + "/login?magicToken=preview");
        values.put("expiryMinutes", "15");
      }
      case "passwort-zuruecksetzen" -> {
        values.put("resetUrl", ORIGIN + "/password-reset/confirm?token=preview");
        values.put("expiryHours", "1");
      }
      case "willkommen" -> {
        values.put("username", "beispiel-nutzer");
        values.put("loginUrl", ORIGIN);
      }
      case "team-aenderung" -> {
        values.put("teamChangeStatement", "Eine beratende Person wurde dem Team hinzugefügt.");
        values.put("caseReference", "#123");
        values.put("teamChangedAt", "26.09.2026, 19:00");
      }
      default -> throw new IllegalArgumentException("Unexpected preview occasion: " + occasion);
    }
  }

  private static void writeLogo(Path outputDir, String slug, String color, String initial)
      throws IOException {
    String svg =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"72\" height=\"72\" viewBox=\"0 0 72 72\">"
            + "<rect width=\"72\" height=\"72\" rx=\"12\" fill=\""
            + color
            + "\"/><text x=\"36\" y=\"49\" text-anchor=\"middle\" font-family=\"Arial\" font-size=\"38\" fill=\"white\">"
            + initial
            + "</text></svg>";
    Files.writeString(
        outputDir.resolve("logos").resolve(slug + ".svg"), svg, StandardCharsets.UTF_8);
  }
}
