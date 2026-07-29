package de.caritas.cob.userservice.api.service.email.layout;

import static org.assertj.core.api.Assertions.assertThat;
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

  /**
   * The real Pre-Dev value {@code globalSmtpEmailThemeColor = #f8e71c} is a light yellow: white
   * button text on it would be unreadable, so the layout must flip to dark text.
   */
  @Test
  void readableTextColor_Should_returnDarkText_When_BackgroundIsLight() {
    assertThat(EmailColors.readableTextColor("#f8e71c")).isEqualTo(EmailColors.DARK_TEXT);
    assertThat(EmailColors.readableTextColor("#ffffff")).isEqualTo(EmailColors.DARK_TEXT);
  }

  @Test
  void readableTextColor_Should_returnWhite_When_BackgroundIsDark() {
    assertThat(EmailColors.readableTextColor("#0f3b8f")).isEqualTo(EmailColors.LIGHT_TEXT);
    assertThat(EmailColors.readableTextColor("#000000")).isEqualTo(EmailColors.LIGHT_TEXT);
  }

  @Test
  void readableTextColor_Should_alwaysReachAaContrast() {
    for (String accent :
        new String[] {"#f8e71c", "#ffffff", "#0f3b8f", "#7f7f7f", "#00ff00", "#123456"}) {
      String text = EmailColors.readableTextColor(accent);
      assertThat(EmailColors.contrastRatio(accent, text))
          .as("contrast of %s on %s", text, accent)
          .isGreaterThanOrEqualTo(3.0d);
    }
  }

  /** Link text and the wordmark sit on the white content card and must stay legible there. */
  @Test
  void onLightBackground_Should_darkenAccentsUntilTheyPassAaAgainstWhite() {
    String darkened = EmailColors.onLightBackground("#f8e71c");

    assertThat(EmailColors.contrastRatio(darkened, "#ffffff")).isGreaterThanOrEqualTo(4.5d);
  }

  @Test
  void onLightBackground_Should_keepAlreadyDarkAccentsUnchanged() {
    assertThat(EmailColors.onLightBackground("#0f3b8f")).isEqualTo("#0f3b8f");
  }

  @Test
  void onLightBackground_Should_returnDarkText_When_ColorIsInvalid() {
    assertThat(EmailColors.onLightBackground("nonsense")).isEqualTo(EmailColors.DARK_TEXT);
  }

  /** A near-white accent would make the filled button vanish on the white card. */
  @Test
  void borderColor_Should_darkenNearWhiteAccentsOnly() {
    assertThat(EmailColors.borderColor("#0f3b8f")).isEqualTo("#0f3b8f");
    assertThat(EmailColors.borderColor("#fffdf5")).isNotEqualTo("#fffdf5");
  }

  @Test
  void contrastRatio_Should_matchTheWcagReferenceValues() {
    assertThat(EmailColors.contrastRatio("#000000", "#ffffff")).isCloseTo(21.0d, within(0.01d));
    assertThat(EmailColors.contrastRatio("#ffffff", "#ffffff")).isCloseTo(1.0d, within(0.01d));
  }
}
