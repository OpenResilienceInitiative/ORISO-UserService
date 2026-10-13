package de.caritas.cob.userservice.api.service.email.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** Contrast guards for the branded e-mail layout (ORISO-UserService#914). */
class EmailColorsTest {

  @Test
  void normalize_Should_acceptSixDigitAndShortHexAndAddMissingHash() {
    assertThat(EmailColors.normalize("#A1B2C3")).isEqualTo("#a1b2c3");
    assertThat(EmailColors.normalize("a1b2c3")).isEqualTo("#a1b2c3");
    assertThat(EmailColors.normalize("#abc")).isEqualTo("#aabbcc");
  }

  /** Unvalidated colour text must never reach a style attribute. */
  @Test
  void normalize_Should_rejectAnythingThatIsNotAHexLiteral() {
    assertThat(EmailColors.normalize(null)).isNull();
    assertThat(EmailColors.normalize("  ")).isNull();
    assertThat(EmailColors.normalize("red")).isNull();
    assertThat(EmailColors.normalize("rgb(1,2,3)")).isNull();
    assertThat(EmailColors.normalize("#fff\";background:url(x)")).isNull();
  }

  @Test
  void firstValid_Should_skipInvalidCandidates() {
    assertThat(EmailColors.firstValid(null, "not-a-color", "#0a0b0c", "#ffffff"))
        .isEqualTo("#0a0b0c");
    assertThat(EmailColors.firstValid("nope", "")).isNull();
  }

  /** The web app's rule: white label only when the seed reaches 4.5:1 against white. */
  @Test
  void onPrimary_Should_beWhiteOnADarkBrandColour() {
    assertThat(EmailColors.onPrimary("#1c4f8f")).isEqualTo("#ffffff");
    assertThat(EmailColors.onPrimary("#a5000a")).isEqualTo("#ffffff");
  }

  /** A light brand colour (the real Pre-Dev colour #f8e71c is a yellow) gets a dark label. */
  @Test
  void onPrimary_Should_beADarkToneOfTheSameHueOnALightBrandColour() {
    String label = EmailColors.onPrimary("#f8e71c");

    assertThat(label).isEqualTo("#1f1c00");
    assertThat(EmailColors.contrastRatio("#f8e71c", label)).isGreaterThanOrEqualTo(4.5d);
  }

  @Test
  void onPrimary_Should_alwaysReachAaContrastOnTheBrandColour() {
    for (String seed :
        new String[] {
          "#f8e71c", "#ffff00", "#a5000a", "#00ff00", "#123456", "#1e88e5", "#d32f2f"
        }) {
      assertThat(EmailColors.contrastRatio(seed, EmailColors.onPrimary(seed)))
          .as("label contrast on %s", seed)
          .isGreaterThanOrEqualTo(4.5d);
    }
  }

  @Test
  void onPrimary_Should_rejectANonColour() {
    assertThatThrownBy(() -> EmailColors.onPrimary("nonsense"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void usablePrimary_Should_keepLightChromaticColoursAndIgnoreNearGreyOnes() {
    assertThat(EmailColors.usablePrimary("#F8E71C")).isEqualTo("#f8e71c");
    assertThat(EmailColors.usablePrimary("#fc0")).isEqualTo("#ffcc00");
    assertThat(EmailColors.usablePrimary("#808080")).isNull();
    assertThat(EmailColors.usablePrimary("#000000")).isNull();
    assertThat(EmailColors.usablePrimary(null)).isNull();
    assertThat(EmailColors.usablePrimary("red")).isNull();
  }

  /** Link text and the wordmark sit on the white content card and must stay legible there. */
  @Test
  void onLightBackground_Should_darkenAccentsUntilTheyPassAaAgainstWhite() {
    String darkened = EmailColors.onLightBackground("#f8e71c");

    assertThat(EmailColors.contrastRatio(darkened, "#ffffff")).isGreaterThanOrEqualTo(4.5d);
  }

  @Test
  void onLightBackground_Should_keepAlreadyDarkAccentsUnchanged() {
    assertThat(EmailColors.onLightBackground("#a5000a")).isEqualTo("#a5000a");
  }

  @Test
  void onLightBackground_Should_returnDarkText_When_ColorIsInvalid() {
    assertThat(EmailColors.onLightBackground("nonsense")).isEqualTo(EmailColors.DARK_TEXT);
  }

  @Test
  void contrastRatio_Should_matchTheWcagReferenceValues() {
    assertThat(EmailColors.contrastRatio("#000000", "#ffffff")).isCloseTo(21.0d, within(0.01d));
    assertThat(EmailColors.contrastRatio("#ffffff", "#ffffff")).isCloseTo(1.0d, within(0.01d));
  }
}
