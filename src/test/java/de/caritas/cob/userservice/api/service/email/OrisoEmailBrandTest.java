package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingFixture;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationFixture;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Theming;
import org.junit.jupiter.api.Test;

class OrisoEmailBrandTest {

  private final OrisoEmailBrand brand =
      new OrisoEmailBrand(
          SenderOrganisationFixture.platformOwner(),
          EmailBrandingFixture.platform("https://app.example.org"));

  @Test
  void keepsATenantColourThatCarriesWhiteText() {
    assertThat(brand.readablePrimary("#1c4f8f")).isEqualTo("#1c4f8f");
  }

  @Test
  void rejectsATenantColourThatWouldMakeTheButtonLabelUnreadable() {
    // A light brand colour is a perfectly good print colour and a terrible
    // button colour: the label is white.
    assertThat(brand.readablePrimary("#ffd400")).isEqualTo("#a5000a");
    assertThat(brand.readablePrimary("#9ad6ff")).isEqualTo("#a5000a");
  }

  @Test
  void fallsBackWhenTheColourIsMissingOrMalformed() {
    assertThat(brand.readablePrimary(null)).isEqualTo("#a5000a");
    assertThat(brand.readablePrimary("")).isEqualTo("#a5000a");
    assertThat(brand.readablePrimary("red")).isEqualTo("#a5000a");
    assertThat(brand.readablePrimary("#abc")).isEqualTo("#a5000a");
  }

  @Test
  void measuresContrastTheWayWcagDoes() {
    assertThat(OrisoEmailBrand.contrastWithWhite("#000000")).isCloseTo(21d, within(0.05d));
    assertThat(OrisoEmailBrand.contrastWithWhite("#ffffff")).isCloseTo(1d, within(0.01d));
    // The value that used to be hardcoded as the default in three senders.
    assertThat(OrisoEmailBrand.contrastWithWhite("#0f3b8f")).isGreaterThan(4.5d);
  }

  @Test
  void buildsFooterLinksFromTheAppUrlWithoutDoublingTheSlash() {
    var values = brand.valuesForTenant("https://app.example.org/", null);

    assertThat(values.get("appUrl")).isEqualTo("https://app.example.org");
    assertThat(values.get("privacyUrl")).isEqualTo("https://app.example.org/datenschutz");
    assertThat(values.get("unsubscribeUrl"))
        .isEqualTo("https://app.example.org/profile/settings/notifications");
  }

  @Test
  void theSenderBlockIsThePlatformOwnersAdminMasterData() {
    var values = brand.valuesForTenant("https://app.example.org", null);

    assertThat(values)
        .containsEntry("orgName", "ORISO")
        .containsEntry("orgAddress", "Betreiberweg 1, 10115 Berlin")
        .containsEntry("contactLine", "info@betreiber.example");
  }

  /** Frank, 2026-09-23: nothing entered means nothing shown — no built-in sample organisation. */
  @Test
  void theSenderBlockStaysBlank_When_thePlatformOwnerEnteredNothing() {
    var values =
        new OrisoEmailBrand(
                SenderOrganisationFixture.nobody(),
                EmailBrandingFixture.platform("https://app.example.org"))
            .valuesForTenant("https://app.example.org", null);

    assertThat(values)
        .containsEntry("orgName", "")
        .containsEntry("orgAddress", "")
        .containsEntry("contactLine", "")
        .containsEntry("offeringName", values.get("platformName"));
  }

  @Test
  void twoTenantsGetTheirOwnFreshBrandInCatalogueMail() {
    TenantService tenants = mock(TenantService.class);
    TenantTemplateSupplier urls = mock(TenantTemplateSupplier.class);
    RestrictedTenantDTO north =
        new RestrictedTenantDTO()
            .id(12L)
            .name("Nord")
            .theming(
                new Theming()
                    .logo("https://app.example.org/branding/nord.png")
                    .primaryColor("#123456"));
    RestrictedTenantDTO south =
        new RestrictedTenantDTO()
            .id(13L)
            .name("Süd")
            .theming(
                new Theming()
                    .logo("https://app.example.org/branding/sued.png")
                    .primaryColor("#654321"));
    when(tenants.getRestrictedTenantDataFresh(12L)).thenReturn(north);
    when(tenants.getRestrictedTenantDataFresh(13L)).thenReturn(south);
    when(tenants.getPlatformTenantDataFresh())
        .thenReturn(new RestrictedTenantDTO().id(0L).name("Online-Beratung"));
    when(urls.getTenantBaseUrl(north)).thenReturn("https://nord.example.org");
    when(urls.getTenantBaseUrl(south)).thenReturn("https://sued.example.org");
    OrisoEmailBrand realBrand =
        new OrisoEmailBrand(
            SenderOrganisationFixture.platformOwner(),
            new EmailBrandingResolver(
                tenants, urls, "Online-Beratung", "", "https://app.example.org"));

    var northMail = realBrand.valuesForTenant("https://app.example.org", 12L);
    var southMail = realBrand.valuesForTenant("https://app.example.org", 13L);

    assertThat(northMail)
        .containsEntry("platformName", "Nord")
        .containsEntry("logoUrl", "https://app.example.org/branding/nord.png")
        .containsEntry("primaryColor", "#123456")
        .containsEntry("privacyUrl", "https://nord.example.org/datenschutz");
    assertThat(southMail)
        .containsEntry("platformName", "Süd")
        .containsEntry("logoUrl", "https://app.example.org/branding/sued.png")
        .containsEntry("primaryColor", "#654321")
        .containsEntry("privacyUrl", "https://sued.example.org/datenschutz");
  }
}
