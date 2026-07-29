package de.caritas.cob.userservice.api.service.email.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The canonical layout (ORISO-UserService#914) and the e-mail-client rules it must satisfy. */
class BrandedEmailLayoutRendererTest {

  private static final String ACCEPT_URL = "https://app.oriso.org/account-invite/tok-123";

  private final BrandedEmailLayoutRenderer renderer =
      new BrandedEmailLayoutRenderer(new EmailContentSanitizer());

  private BrandedEmail render(EmailBranding branding, String body, String actionUrl) {
    return renderer.render(branding, BrandedEmailRequest.of("Ihre Einladung", body, actionUrl));
  }

  private static EmailBranding tenantBranding() {
    return new EmailBranding(
        "Beratungsstelle Nord",
        "https://cdn.example.org/logo.png",
        "#a5000a",
        "https://nord.oriso.org/impressum",
        "https://nord.oriso.org/datenschutz");
  }

  // --- structure / client compatibility -------------------------------------------------

  @Test
  void render_Should_produceTableBasedMarkupWithoutModernLayoutPrimitives() {
    String html = render(tenantBranding(), "Hallo", ACCEPT_URL).html();

    assertThat(html).startsWith("<!doctype html>").contains("<table role=\"presentation\"");
    assertThat(html)
        .doesNotContain("display:flex")
        .doesNotContain("display:grid")
        .doesNotContain("float:");
  }

  @Test
  void render_Should_capTheContentWidthAtSixHundredPixels() {
    String html = render(tenantBranding(), "Hallo", ACCEPT_URL).html();

    assertThat(html).contains("max-width:600px");
  }

  /** No external assets except the logo — no web fonts, no CSS files, no tracking images. */
  @Test
  void render_Should_referenceNoRemoteAssetBesidesTheLogo() {
    String html = render(tenantBranding(), "Hallo", ACCEPT_URL).html();

    assertThat(html).doesNotContain("<link").doesNotContain("@import").doesNotContain("url(");
    assertThat(imageSources(html)).containsExactly("https://cdn.example.org/logo.png");
  }

  /**
   * Dark-mode safety: the layout does not rely on {@code prefers-color-scheme}. It opts out via
   * {@code color-scheme: light only} and paints every cell with an explicit {@code bgcolor}
   * attribute plus an inline background, so a client that inverts anyway still has a defined pair.
   */
  @Test
  void render_Should_beDarkModeSafeWithoutRelyingOnPrefersColorScheme() {
    String html = render(tenantBranding(), "Hallo", ACCEPT_URL).html();

    assertThat(html).contains("color-scheme: light only").contains("content=\"light only\"");
    assertThat(html).doesNotContain("prefers-color-scheme");
    assertThat(html).contains("bgcolor=\"#ffffff\"").contains("bgcolor=\"#e4e2e2\"");
  }

  /**
   * Every neutral surface is a product token from ORISO-Admin {@code src/app.css} — workspace
   * {@code #e4e2e2}, card {@code #ffffff}, footer {@code #f0edee}, outline {@code #c4c7c8}, text
   * {@code #1b1b1c}/{@code #444748}/{@code #747878} — not an invented grey ramp.
   */
  @Test
  void render_Should_useTheProductNeutralTokensForEverySurface() {
    String html = render(tenantBranding(), "Hallo", ACCEPT_URL).html();

    assertThat(html)
        .contains("background-color:#e4e2e2")
        .contains("background-color:#ffffff")
        .contains("background-color:#f0edee")
        .contains("#c4c7c8")
        .contains("color:#1b1b1c")
        .contains("color:#444748")
        .contains("color:#747878");
    assertThat(html)
        .as("no invented grey ramp")
        .doesNotContain("#f6f7fb")
        .doesNotContain("#111827")
        .doesNotContain("#374151")
        .doesNotContain("#e5e7eb")
        .doesNotContain("#f0f1f5")
        .doesNotContain("#4b5563")
        .doesNotContain("#6b7280");
  }

  // --- branding fallbacks ---------------------------------------------------------------

  @Test
  void render_Should_useTheTenantLogo_When_ALogoUrlIsConfigured() {
    String html = render(tenantBranding(), "Hallo", ACCEPT_URL).html();

    assertThat(html)
        .contains("src=\"https://cdn.example.org/logo.png\"")
        .contains("alt=\"Beratungsstelle Nord\"");
  }

  @Test
  void render_Should_fallBackToATextWordmark_When_NoLogoIsConfigured() {
    EmailBranding noLogo = new EmailBranding("Beratungsstelle Nord", null, "#a5000a", null, null);

    String html = render(noLogo, "Hallo", ACCEPT_URL).html();

    assertThat(imageSources(html)).isEmpty();
    assertThat(html).contains(">Beratungsstelle Nord<");
  }

  @Test
  void render_Should_produceACompleteNeutralMail_When_NoBrandingIsConfiguredAtAll() {
    BrandedEmail mail = render(EmailBranding.neutral(), "Hallo", ACCEPT_URL);

    assertThat(mail.html()).contains(EmailColors.PLATFORM_ACCENT_DARK).contains(">ORISO<");
    assertThat(mail.plainText()).contains("ORISO");
  }

  @Test
  void render_Should_fallBackToNeutralBranding_When_BrandingIsNull() {
    assertThat(renderer.render(null, BrandedEmailRequest.of("s", "b", null)).html())
        .contains(EmailColors.PLATFORM_ACCENT_DARK);
  }

  /** The Pre-Dev theme colour {@code #f8e71c} must never produce white-on-yellow button text. */
  @Test
  void render_Should_neverRenderLightTextOnALightAccent() {
    EmailBranding yellow = new EmailBranding("ORISO", null, "#f8e71c", null, null);

    String html = render(yellow, "Hallo", ACCEPT_URL).html();

    assertThat(html)
        .contains("background-color:#f8e71c")
        .contains("color:" + EmailColors.DARK_TEXT);
    assertThat(Pattern.compile("(?<!background-)color:#ffffff").matcher(html).find())
        .as("no white foreground anywhere while the accent is a light yellow")
        .isFalse();
  }

  @Test
  void render_Should_rejectAnInvalidAccentColorAndUseTheNeutralDefault() {
    EmailBranding broken =
        new EmailBranding("ORISO", null, "#fff\";background:url(evil)", null, null);

    assertThat(render(broken, "Hallo", null).html())
        .contains(EmailColors.PLATFORM_ACCENT_DARK)
        .doesNotContain("evil");
  }

  // --- content / call to action ---------------------------------------------------------

  @Test
  void render_Should_renderTheActionAsAButtonAndKeepTheFullUrlAsAFallbackLine() {
    String html = render(tenantBranding(), "Hallo", ACCEPT_URL).html();

    assertThat(html).contains("Einladung annehmen");
    assertThat(html).contains("Falls der Button nicht funktioniert");
    assertThat(html).contains(">" + ACCEPT_URL + "</a>");
    assertThat(html.split(Pattern.quote(ACCEPT_URL), -1).length - 1)
        .as("button href, fallback href and visible fallback text")
        .isGreaterThanOrEqualTo(3);
  }

  @Test
  void render_Should_omitTheCallToAction_When_NoActionUrlIsGiven() {
    BrandedEmail mail = render(tenantBranding(), "Nur eine Information.", null);

    assertThat(mail.html()).doesNotContain("Einladung annehmen");
    assertThat(mail.plainText()).doesNotContain("Einladung annehmen");
  }

  /** A relative or {@code javascript:} action never becomes an href. */
  @Test
  void render_Should_dropNonHttpActionUrls() {
    assertThat(render(tenantBranding(), "Hallo", "javascript:alert(1)").html())
        .doesNotContain("javascript:")
        .doesNotContain("Einladung annehmen");
    assertThat(BrandedEmailLayoutRenderer.safeActionUrl("/relative")).isNull();
    assertThat(BrandedEmailLayoutRenderer.safeActionUrl("https://ok.example")).isNotNull();
  }

  @Test
  void render_Should_placeTheAuthorContentIntoTheContentArea() {
    String html = render(tenantBranding(), "Hallo Ada,\n\nwillkommen!", ACCEPT_URL).html();

    assertThat(html).contains("<p>Hallo Ada,</p>").contains("<p>willkommen!</p>");
  }

  @Test
  void render_Should_escapeTheSubjectAndBrandName() {
    EmailBranding branding =
        new EmailBranding("<script>x</script>Nord", null, "#a5000a", null, null);

    String html =
        renderer
            .render(branding, BrandedEmailRequest.of("<img src=x onerror=y>", "Hallo", null))
            .html();

    assertThat(html)
        .doesNotContain("<script>x</script>")
        .doesNotContain("<img src=x")
        .contains("&lt;img src=x onerror=y&gt;")
        .contains("&lt;script&gt;x&lt;/script&gt;Nord");
  }

  @Test
  void render_Should_notLetContentIntroduceNewPlaceholders() {
    String html = render(tenantBranding(), "{{ACCENT_COLOR}} und {{CONTENT}}", null).html();

    assertThat(html).contains("{{ACCENT_COLOR}} und {{CONTENT}}");
  }

  // --- footer ---------------------------------------------------------------------------

  @Test
  void render_Should_renderTheLegalPointersInBothParts_When_Configured() {
    BrandedEmail mail = render(tenantBranding(), "Hallo", ACCEPT_URL);

    assertThat(mail.html())
        .contains("https://nord.oriso.org/impressum")
        .contains("https://nord.oriso.org/datenschutz");
    assertThat(mail.plainText())
        .contains("Impressum: https://nord.oriso.org/impressum")
        .contains("Datenschutz: https://nord.oriso.org/datenschutz");
  }

  @Test
  void render_Should_omitTheLegalPointers_When_NoneAreKnown() {
    BrandedEmail mail = render(EmailBranding.neutral(), "Hallo", ACCEPT_URL);

    assertThat(mail.html()).doesNotContain("Impressum");
  }

  // --- plain-text alternative -----------------------------------------------------------

  @Test
  void render_Should_alwaysProduceAPlainTextAlternativeFromTheSameContent() {
    BrandedEmail mail = render(tenantBranding(), "Hallo Ada,\n\nwillkommen!", ACCEPT_URL);

    assertThat(mail.plainText())
        .doesNotContain("<")
        .contains("Beratungsstelle Nord")
        .contains("Ihre Einladung")
        .contains("Hallo Ada,")
        .contains("willkommen!")
        .contains(ACCEPT_URL)
        .contains("Diese E-Mail wurde automatisch versendet.");
  }

  @Test
  void render_Should_localiseTheFrameWording_When_LanguageIsEnglish() {
    BrandedEmail mail =
        renderer.render(
            tenantBranding(),
            new BrandedEmailRequest("Your invitation", "Hello", ACCEPT_URL, null, "en"));

    assertThat(mail.html())
        .contains("lang=\"en\"")
        .contains("Accept invitation")
        .contains("If the button does not work");
    assertThat(mail.plainText())
        .contains("Accept invitation")
        .contains("This is an automated message");
  }

  @Test
  void render_Should_useAnExplicitActionLabel_When_Provided() {
    BrandedEmail mail =
        renderer.render(
            tenantBranding(),
            new BrandedEmailRequest("s", "b", ACCEPT_URL, "Konto einrichten", "de"));

    assertThat(mail.html()).contains("Konto einrichten");
    assertThat(mail.plainText()).contains("Konto einrichten");
  }

  // --- helpers --------------------------------------------------------------------------

  private static java.util.List<String> imageSources(String html) {
    Matcher matcher = Pattern.compile("<img[^>]+src=\"([^\"]+)\"").matcher(html);
    java.util.List<String> sources = new java.util.ArrayList<>();
    while (matcher.find()) {
      sources.add(matcher.group(1));
    }
    return sources;
  }
}
