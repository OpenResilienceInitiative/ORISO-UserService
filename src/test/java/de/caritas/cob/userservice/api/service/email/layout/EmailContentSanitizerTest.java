package de.caritas.cob.userservice.api.service.email.layout;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Template bodies are untrusted input for HTML purposes (ORISO-UserService#914). These tests pin
 * the documented allow-list, the authoring conveniences and the plain-text derivation.
 */
class EmailContentSanitizerTest {

  private static final String LINK_COLOR = "#a5000a";

  private final EmailContentSanitizer sanitizer = new EmailContentSanitizer();

  private String sanitize(String body) {
    return sanitizer.toContentHtml(body, LINK_COLOR);
  }

  @Test
  void toContentHtml_Should_returnEmpty_When_BodyIsNullOrBlank() {
    assertThat(sanitize(null)).isEmpty();
    assertThat(sanitize("   ")).isEmpty();
  }

  @Test
  void toContentHtml_Should_keepTheAllowedFormattingTags() {
    String html =
        sanitize("<p>Hallo <strong>Ada</strong> und <em>Grace</em></p><ul><li>eins</li></ul>");

    assertThat(html)
        .contains("<strong>Ada</strong>")
        .contains("<em>Grace</em>")
        .contains("<li>eins</li>");
  }

  @Test
  void toContentHtml_Should_dropScriptsAndEventHandlersAndInlineStyles() {
    String html =
        sanitize(
            "<p onclick=\"steal()\" style=\"position:fixed\">Hallo</p>"
                + "<script>alert('xss')</script>"
                + "<img src=\"https://tracker.example/pixel.gif\">");

    assertThat(html)
        .contains("Hallo")
        .doesNotContain("onclick")
        .doesNotContain("alert(")
        .doesNotContain("<script")
        .doesNotContain("<img")
        .doesNotContain("position:fixed");
  }

  /** An author must not be able to break out of the content cell and rebuild the frame. */
  @Test
  void toContentHtml_Should_stripLayoutBreakingElements() {
    String html = sanitize("</td></tr></table><table><tr><td>fake frame</td></tr></table>");

    assertThat(html).doesNotContain("<table").doesNotContain("</td>").contains("fake frame");
  }

  @Test
  void toContentHtml_Should_rejectJavascriptHrefs() {
    String html = sanitize("<a href=\"javascript:alert(1)\">klick</a>");

    assertThat(html).doesNotContain("javascript:").contains("klick");
  }

  @Test
  void toContentHtml_Should_keepHttpAndMailtoAnchorsAndStyleThemInline() {
    String html =
        sanitize("<a href=\"https://oriso.org\">Web</a> <a href=\"mailto:a@b.org\">Mail</a>");

    assertThat(html)
        .contains("href=\"https://oriso.org\"")
        .contains("href=\"mailto:a@b.org\"")
        .contains("color:" + LINK_COLOR)
        .contains("rel=\"noopener noreferrer\"");
  }

  /** #913: the bare onboarding URL in a plain-text template must become clickable. */
  @Test
  void toContentHtml_Should_linkifyBareUrls() {
    String html = sanitize("Bitte oeffnen: https://app.oriso.org/account-invite/abc123");

    assertThat(html)
        .contains("<a href=\"https://app.oriso.org/account-invite/abc123\"")
        .contains(">https://app.oriso.org/account-invite/abc123</a>");
  }

  @Test
  void toContentHtml_Should_notSwallowSentenceEndingPunctuationIntoTheUrl() {
    String html = sanitize("Siehe https://oriso.org/hilfe.");

    assertThat(html).contains("href=\"https://oriso.org/hilfe\"").endsWith(".</p>");
  }

  @Test
  void toContentHtml_Should_notDoubleWrapUrlsThatAreAlreadyAnchors() {
    String html = sanitize("<p><a href=\"https://oriso.org\">https://oriso.org</a></p>");

    assertThat(html.split("<a ", -1)).hasSize(2);
  }

  @Test
  void toContentHtml_Should_turnPlainTextLineBreaksIntoParagraphsAndBreaks() {
    String html = sanitize("Hallo Ada,\nschoen dass Sie da sind.\n\nIhr Team");

    assertThat(html).contains("Hallo Ada,<br>schoen dass Sie da sind.").contains("<p>Ihr Team</p>");
  }

  @Test
  void toContentHtml_Should_notReParagraphBodiesThatAlreadyUseBlockMarkup() {
    String html = sanitize("<p>eins</p>\n<p>zwei</p>");

    assertThat(html).doesNotContain("<p><p>");
  }

  @Test
  void toContentHtml_Should_escapeAngleBracketsWrittenAsText() {
    String html = sanitize("5 < 6 & 7 > 6");

    assertThat(html).contains("&amp;").doesNotContain("<6");
  }

  @Test
  void toPlainText_Should_renderListsBreaksAndAnchorTargets() {
    String plain =
        sanitizer.toPlainText(
            sanitize("Hallo,\n\nBitte: <a href=\"https://oriso.org/x\">hier klicken</a>"));

    assertThat(plain).contains("Hallo,").contains("hier klicken (https://oriso.org/x)");
  }

  @Test
  void toPlainText_Should_printBareUrlOnce_When_LabelEqualsHref() {
    String plain = sanitizer.toPlainText(sanitize("Link: https://oriso.org/x"));

    assertThat(plain).contains("https://oriso.org/x");
    assertThat(plain.split("https://oriso.org/x", -1)).hasSize(2);
  }

  @Test
  void toPlainText_Should_containNoMarkup() {
    String plain =
        sanitizer.toPlainText(sanitize("<p>Hallo <strong>Ada</strong></p><ul><li>eins</li></ul>"));

    assertThat(plain).doesNotContain("<").contains("Hallo Ada").contains("- eins");
  }
}
