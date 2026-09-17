package de.caritas.cob.userservice.api.service.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GuestIdentityCatalogTest {
  @Test
  void fixedGermanFixtureKeepsTheChosenAnimalAndActualIdentity() {
    assertThat(GuestIdentityCatalog.identityFor("Biene", "bee.svg", "Rayan", 1234))
        .isEqualTo(new GuestIdentitySuggestion("biene_rayan_1234", "biene_rayan_1234", "bee.svg"));
  }

  @Test
  void umlautsAndAccentsHaveReadableAsciiCredentials() {
    assertThat(GuestIdentityCatalog.identityFor("Käfer", "bug.svg", "Émile", 1234).username())
        .isEqualTo("kaefer_emile_1234");
  }

  @Test
  void allSupportedLocalesReturnBoundedNamesAndLocalAssets() {
    var catalog = new GuestIdentityCatalog();
    for (String locale :
        new String[] {"de", "de@informal", "en-GB", "fr", "es", "ru", "uk", "tr", "ti"}) {
      for (int i = 0; i < 100; i++) {
        var identity = catalog.next(locale);
        assertThat(identity.username()).matches("[a-z0-9_]{3,30}");
        assertThat(identity.displayName()).isEqualTo(identity.username());
        assertThat(identity.avatarKey()).matches("[a-zA-Z0-9_-]+\\.svg");
      }
    }
  }
}
