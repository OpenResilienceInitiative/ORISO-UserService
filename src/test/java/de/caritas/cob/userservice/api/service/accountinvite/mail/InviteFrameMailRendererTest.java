package de.caritas.cob.userservice.api.service.accountinvite.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.email.layout.BrandedEmail;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The invite mail now uses the ORISO e-mail design system instead of the hand-written layout, and
 * the operator's own subject and body are the content of that frame. These tests pin the two halves
 * that can silently go wrong: the authored text has to survive into the card, and the tenant's
 * branding has to reach the header.
 */
@ExtendWith(MockitoExtension.class)
class InviteFrameMailRendererTest {

  private static final String ACCEPT_URL = "https://admin.oriso.org/onboarding/accept?token=tok";

  @Mock private EmailBrandingResolver emailBrandingResolver;

  private BrandedEmail render(EmailBranding branding, String subject, String body, String action) {
    when(emailBrandingResolver.resolve(any())).thenReturn(branding);
    return InviteFrameMailRendererFixture.inviteFrameMailRenderer(emailBrandingResolver)
        .render(subject, body, action, 42L, "de");
  }

  /**
   * The point of the whole change: what the operator typed in the Admin panel — placeholders
   * already substituted by the caller — is what stands inside the ORISO card, paragraphs intact.
   */
  @Test
  void putsTheOperatorsSubjectAndBodyInsideTheOrisoFrame() {
    BrandedEmail mail =
        render(
            EmailBranding.neutral(),
            "Ihre Einladung zu ORISO",
            "Hallo Maren Muster,\n\nSie wurden eingeladen, ein Konto einzurichten.",
            ACCEPT_URL);

    assertThat(mail.subject()).isEqualTo("Ihre Einladung zu ORISO");
    assertThat(mail.html())
        .startsWith("<!DOCTYPE html>")
        .as("the ORISO frame, not the old hand-written layout")
        .contains("border-radius:24px")
        .doesNotContain("{{")
        .contains("Ihre Einladung zu ORISO")
        .contains("Hallo Maren Muster,")
        .contains("Sie wurden eingeladen, ein Konto einzurichten.")
        .as("paragraphs are preserved rather than collapsed into one run of text")
        .contains("<p>Hallo Maren Muster,</p>")
        .as("the call to action and its copy-paste fallback both carry the accept URL")
        .contains("Einladung annehmen")
        .contains("Falls der Button nicht funktioniert")
        .as("the footer names the invitation, not signing in — this mail is neither")
        .contains("Diese E-Mail gehört zu Ihrer Einladung und lässt sich nicht abbestellen.")
        .doesNotContain("gehört zur Anmeldung")
        .containsOnlyOnce("<!DOCTYPE html>");
    assertThat(countOccurrences(mail.html(), ACCEPT_URL)).isEqualTo(3);

    assertThat(mail.plainText())
        .doesNotContain("<table")
        .doesNotContain("{{")
        .contains("Ihre Einladung zu ORISO")
        .contains("Hallo Maren Muster,")
        .contains("Einladung annehmen:")
        .contains("Diese E-Mail gehört zu Ihrer Einladung und lässt sich nicht abbestellen.")
        .contains(ACCEPT_URL);
  }

  /** The operator writes content, not markup — an authored tag must not reach the document. */
  @Test
  void escapesMarkupTheOperatorTypedIntoTheBody() {
    BrandedEmail mail =
        render(
            EmailBranding.neutral(),
            "Einladung",
            "Hallo <script>alert(1)</script> Muster",
            ACCEPT_URL);

    assertThat(mail.html()).doesNotContain("<script>").contains("Hallo");
  }

  /** A tenant logo is delivered as an absolute image link, as an {@code <img>} in the header. */
  @Test
  void rendersTheTenantLogoAsAnAbsoluteImage() {
    BrandedEmail mail =
        render(
            new EmailBranding(
                "Träger Nord e.V.",
                "https://nord.oriso.org/service/tenant/public/branding/logo",
                "#1c4f8f",
                "https://nord.oriso.org/impressum",
                "https://nord.oriso.org/datenschutz"),
            "Einladung",
            "Hallo",
            ACCEPT_URL);

    assertThat(mail.html())
        .contains("<img src=\"https://nord.oriso.org/service/tenant/public/branding/logo\"")
        .as("the tenant name brands the header and the footer")
        .contains("Träger Nord e.V.")
        .as("the tenant colour reaches the accent bar and the button")
        .contains("#1c4f8f")
        .as("tenant imprint and privacy pointers, not the platform's")
        .contains("https://nord.oriso.org/impressum")
        .contains("https://nord.oriso.org/datenschutz");
  }

  /** No logo means the text wordmark carries the header — never an {@code <img src="">}. */
  @Test
  void rendersNoImageAtAllWhenTheTenantHasNoLogo() {
    BrandedEmail mail =
        render(
            new EmailBranding("Träger Ohne Logo", null, "#1c4f8f", null, null),
            "Einladung",
            "Hallo",
            ACCEPT_URL);

    assertThat(mail.html()).doesNotContain("<img").contains("Träger Ohne Logo");
  }

  /**
   * A tenant colour that would make the white button label unreadable must not reach the button.
   * The guard lives in {@code OrisoEmailBrand}; this pins that the invite frame actually uses it.
   */
  @Test
  void refusesATenantColourThatWouldMakeTheButtonLabelUnreadable() {
    BrandedEmail mail =
        render(
            new EmailBranding("Träger Gelb", null, "#f8e71c", null, null),
            "Einladung",
            "Hallo",
            ACCEPT_URL);

    assertThat(mail.html())
        .as("the button keeps the readable platform primary")
        .contains("bgcolor=\"#a5000a\" style=\"background-color:#a5000a;border-radius:999px;\"")
        .as("the accent bar may keep the tenant colour — nothing is written on top of it")
        .contains("bgcolor=\"#f8e71c\" style=\"height:4px");
  }

  /** A mail without an action carries neither a button pointing nowhere nor a fallback line. */
  @Test
  void dropsTheCallToActionEntirelyWhenThereIsNoActionUrl() {
    BrandedEmail mail =
        render(EmailBranding.neutral(), "Hinweis", "Der Vertrag ist unterschrieben.", null);

    assertThat(mail.html())
        .doesNotContain("Einladung annehmen")
        .doesNotContain("Falls der Button nicht funktioniert")
        .doesNotContain("{{ctaBlock}}")
        .contains("Der Vertrag ist unterschrieben.");
    assertThat(mail.plainText()).doesNotContain("Einladung annehmen").doesNotContain("{{");
  }

  /** A relative or {@code javascript:} action is dropped, never interpolated into an href. */
  @Test
  void dropsAnActionUrlThatIsNotAbsoluteHttp() {
    BrandedEmail mail =
        render(EmailBranding.neutral(), "Einladung", "Hallo", "javascript:alert(1)");

    assertThat(mail.html()).doesNotContain("javascript:").doesNotContain("Einladung annehmen");
  }

  /** English selects the English tone folder and the English frame wording. */
  @Test
  void rendersTheEnglishToneForAnEnglishInvite() {
    when(emailBrandingResolver.resolve(any())).thenReturn(EmailBranding.neutral());

    BrandedEmail mail =
        InviteFrameMailRendererFixture.inviteFrameMailRenderer(emailBrandingResolver)
            .render("Your invitation", "Hello Maren", ACCEPT_URL, 42L, "en");

    assertThat(mail.html())
        .contains("<html lang=\"en\"")
        .contains("Accept invitation")
        .contains("If the button does not work")
        .contains("This email is part of your invitation and cannot be unsubscribed from.")
        .doesNotContain("part of signing in")
        .contains("Privacy")
        .contains("Imprint");
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
      count++;
    }
    return count;
  }
}
