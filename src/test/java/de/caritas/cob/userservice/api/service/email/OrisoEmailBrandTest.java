package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationFixture;
import org.junit.jupiter.api.Test;

class OrisoEmailBrandTest {

  private final OrisoEmailBrand brand =
      new OrisoEmailBrand(SenderOrganisationFixture.platformOwner());

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
    assertThat(values.get("settingsUrl"))
        .isEqualTo("https://app.example.org/profile/einstellungen");
    assertThat(values.get("privacyUrl")).isEqualTo("https://app.example.org/datenschutz");
    assertThat(values.get("unsubscribeUrl"))
        .isEqualTo("https://app.example.org/profile/einstellungen/email");
  }

  @Test
  void theSenderBlockIsThePlatformOwnersAdminMasterData() {
    var values = brand.values("https://app.example.org", null);

    assertThat(values)
        .containsEntry("orgName", "ORISO")
        .containsEntry("orgAddress", "Betreiberweg 1, 10115 Berlin")
        .containsEntry("contactLine", "info@betreiber.example");
  }

  /** Frank, 2026-09-23: nothing entered means nothing shown — no built-in sample organisation. */
  @Test
  void theSenderBlockStaysBlank_When_thePlatformOwnerEnteredNothing() {
    var values =
        new OrisoEmailBrand(SenderOrganisationFixture.nobody())
            .values("https://app.example.org", null);

    assertThat(values)
        .containsEntry("orgName", "")
        .containsEntry("orgAddress", "")
        .containsEntry("contactLine", "")
        .containsEntry("offeringName", values.get("platformName"));
  }
}
