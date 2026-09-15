package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class OrisoEmailBrandTest {

  private final de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver resolver =
      org.mockito.Mockito.mock(
          de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver.class);
  private final OrisoEmailBrand brand = new OrisoEmailBrand(resolver);

  @org.junit.jupiter.api.BeforeEach
  void configureBranding() {
    org.mockito.Mockito.when(resolver.resolve(org.mockito.ArgumentMatchers.any()))
        .thenReturn(de.caritas.cob.userservice.api.service.email.layout.EmailBranding.neutral());
  }

  @Test
  void usesTheExplicitTenantInsteadOfAmbientContextOrSmtpColour() {
    org.mockito.Mockito.when(resolver.resolve(7L))
        .thenReturn(
            new de.caritas.cob.userservice.api.service.email.layout.EmailBranding(
                "Tenant Seven",
                "https://app.example.org/service/tenant/public/branding/7/logo",
                "#1c4f8f",
                "https://app.example.org/legal",
                "https://app.example.org/privacy"));
    de.caritas.cob.userservice.api.tenant.TenantContext.setCurrentTenant(9L);
    try {
      var values = brand.valuesForTenant("https://app.example.org", 7L);
      assertThat(values)
          .containsEntry("orgName", "Tenant Seven")
          .containsEntry("primaryColor", "#1c4f8f")
          .containsEntry("logoUrl", "https://app.example.org/service/tenant/public/branding/7/logo")
          .containsEntry("privacyUrl", "https://app.example.org/privacy");
    } finally {
      de.caritas.cob.userservice.api.tenant.TenantContext.clear();
    }
  }

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
    var values = brand.values("https://app.example.org/", "#1c4f8f");

    assertThat(values.get("appUrl")).isEqualTo("https://app.example.org");
    assertThat(values.get("privacyUrl")).isEqualTo("https://app.example.org/datenschutz");
    assertThat(values.get("unsubscribeUrl"))
        .isEqualTo("https://app.example.org/profile/settings/notifications");
  }
}
