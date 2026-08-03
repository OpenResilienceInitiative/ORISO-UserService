package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neovisionaries.i18n.LanguageCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Renders against the templates this service actually ships, rather than against a fixture.
 *
 * <p>That is the point of the check: the templates are generated in ORISO-Frontend and copied in,
 * so the thing worth testing here is that the copy on the classpath is complete and that
 * substitution does not corrupt it.
 */
class OrisoEmailRendererTest {

  private final OrisoEmailRenderer renderer = new OrisoEmailRenderer();

  private static Map<String, String> brand() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("platformName", "Online-Beratung");
    values.put("orgName", "Caritasverband Mainz");
    values.put("orgAddress", "Bahnhofstraße 6, 55116 Mainz");
    values.put("contactLine", "kontakt@example.org");
    values.put("logoUrl", "https://cdn.example.org/logo.png");
    values.put("primaryColor", "#a5000a");
    values.put("accentColor", "#cc1e1c");
    values.put("privacyUrl", "https://example.org/datenschutz");
    values.put("imprintUrl", "https://example.org/impressum");
    values.put("settingsUrl", "https://example.org/settings");
    values.put("unsubscribeUrl", "https://example.org/settings/notifications");
    values.put("appUrl", "https://example.org");
    return values;
  }

  @Test
  void rendersBothMimePartsAndTheSubject() {
    Map<String, String> values = brand();
    values.put("loginUrl", "https://example.org/login?token=abc");
    values.put("expiryMinutes", "15");

    var email = renderer.render("anmeldelink", OrisoEmailRenderer.Tone.DE_FORMAL, values);

    assertThat(email.subject()).isEqualTo("Ihr Anmeldelink für Online-Beratung");
    assertThat(email.html()).startsWith("<!DOCTYPE html>").contains("Ihr Anmeldelink");
    assertThat(email.html()).contains("https://example.org/login?token=abc");
    assertThat(email.text()).contains("Ihr Anmeldelink").doesNotContain("<table");
  }

  @Test
  void leavesNoPlaceholderBehindWhenEveryValueIsSupplied() {
    Map<String, String> values = brand();
    values.put("loginUrl", "https://example.org/login");
    values.put("expiryMinutes", "15");

    var email = renderer.render("anmeldelink", OrisoEmailRenderer.Tone.DE_FORMAL, values);

    assertThat(email.html()).doesNotContain("{{");
    assertThat(email.text()).doesNotContain("{{");
  }

  @Test
  void keepsAnUnsuppliedPlaceholderVisibleRatherThanBlankingIt() {
    // A visible {{expiryMinutes}} in a sent mail is a bug report. A silent blank
    // is a mail that quietly says the link expires in "" minutes.
    var email = renderer.render("anmeldelink", OrisoEmailRenderer.Tone.DE_FORMAL, brand());

    assertThat(email.html()).contains("{{expiryMinutes}}");
  }

  @Test
  void escapesMarkupInTheHtmlPartButNotInTheTextPart() {
    Map<String, String> values = brand();
    values.put("username", "<b>Ann & Bob</b>");
    values.put("appUrl", "https://example.org");

    var email = renderer.render("email-geaendert", OrisoEmailRenderer.Tone.DE_FORMAL, values);

    assertThat(email.html()).contains("&lt;b&gt;Ann &amp; Bob&lt;/b&gt;").doesNotContain("<b>Ann");
    assertThat(email.text()).contains("<b>Ann & Bob</b>");
  }

  @Test
  void picksTheEnglishTemplateForEnglishSpeakers() {
    assertThat(OrisoEmailRenderer.Tone.of(LanguageCode.en)).isEqualTo(OrisoEmailRenderer.Tone.EN);
    assertThat(OrisoEmailRenderer.Tone.of(LanguageCode.de))
        .isEqualTo(OrisoEmailRenderer.Tone.DE_FORMAL);
    assertThat(OrisoEmailRenderer.Tone.of(null)).isEqualTo(OrisoEmailRenderer.Tone.DE_FORMAL);
  }

  @Test
  void knowsWhichOccasionsCarryNoUnsubscribeLink() {
    // ADR-019: a security or legal mail has no switch behind the link, so it
    // must not offer one.
    assertThat(renderer.isUnsubscribable("anmeldelink")).isFalse();
    assertThat(renderer.isUnsubscribable("einmalcode")).isFalse();
    assertThat(renderer.isUnsubscribable("avv-unterschrift")).isFalse();
    assertThat(renderer.isUnsubscribable("team-aenderung")).isTrue();
  }

  @Test
  void securityMailsDoNotLinkToASettingsScreenThatHasNoSwitch() {
    Map<String, String> values = brand();
    values.put("loginUrl", "https://example.org/login");
    values.put("expiryMinutes", "15");

    var email = renderer.render("anmeldelink", OrisoEmailRenderer.Tone.DE_FORMAL, values);

    assertThat(email.html()).doesNotContain("https://example.org/settings/notifications");
  }

  @Test
  void saysWhichTemplateIsMissingRatherThanFailingObscurely() {
    assertThatThrownBy(
            () -> renderer.render("no-such-mail", OrisoEmailRenderer.Tone.DE_FORMAL, brand()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no-such-mail");
  }

  @Test
  void everyTemplateInTheCatalogueRendersInEveryTone() {
    for (String id :
        new String[] {
          "anmeldelink",
          "einmalcode",
          "email-geaendert",
          "passwort-zuruecksetzen",
          "team-aenderung",
          "smtp-test",
          "avv-unterschrift"
        }) {
      for (OrisoEmailRenderer.Tone tone : OrisoEmailRenderer.Tone.values()) {
        var email = renderer.render(id, tone, brand());
        assertThat(email.subject()).as("subject of %s/%s", id, tone).isNotBlank();
        assertThat(email.html()).as("html of %s/%s", id, tone).contains("<!DOCTYPE html>");
        assertThat(email.text()).as("text of %s/%s", id, tone).isNotBlank();
      }
    }
  }
}
