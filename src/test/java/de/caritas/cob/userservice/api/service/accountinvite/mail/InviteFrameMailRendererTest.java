package de.caritas.cob.userservice.api.service.accountinvite.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer.Tone;
import de.caritas.cob.userservice.api.service.email.layout.BrandedEmail;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisation;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationFixture;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationResolver;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        .as("the tenant name brands the header")
        .contains("Träger Nord e.V.")
        .as("the tenant colour reaches the accent bar and the button")
        .contains("#1c4f8f")
        .as("tenant imprint and privacy pointers, not the platform's")
        .contains("https://nord.oriso.org/impressum")
        .contains("https://nord.oriso.org/datenschutz");
  }

  /**
   * The Träger brands the header, but X in the footer's "X ist ein Angebot von Y" stays the
   * platform name (Frank, 2026-09-23). Y is the sender organisation: the operator here, because
   * this Träger has no organisation data of its own — see the sender-block tests below for the
   * Träger case.
   */
  @Test
  void footerNamesThePlatformAndItsOperator_evenWhenATraegerBrandsTheHeader() {
    BrandedEmail mail =
        render(
            new EmailBranding("Caritasverband Musterstadt e.V.", null, "#1c4f8f", null, null),
            "Einladung",
            "Hallo",
            ACCEPT_URL);

    assertThat(mail.html())
        .contains(">Online-Beratung ist ein Angebot von ORISO.</div>")
        .doesNotContain("Musterstadt e.V. ist ein Angebot von");
    assertThat(mail.plainText())
        .contains("\nOnline-Beratung ist ein Angebot von ORISO.\n")
        .doesNotContain("Musterstadt e.V. ist ein Angebot von");
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

  /**
   * The same frame also carries mails that are not invitations and have no link at all — the
   * "contract signed" notice ({@code DpaSignedNoticeService}) is one. Its recipients must not be
   * told to keep a link to themselves that does not exist, nor that the mail belongs to an
   * invitation it has nothing to do with.
   */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "de | diesen Link | Einladung | Diese E-Mail wurde automatisch versendet. Bitte antworten Sie"
            + " nicht darauf.",
        "en | this link | invitation | This email was sent automatically. Please do not reply to it."
      })
  void withoutAnActionSaysNothingAboutALinkOrAnInvitation(
      String language, String linkSentence, String invitation, String neutralNote) {
    when(emailBrandingResolver.resolve(any())).thenReturn(EmailBranding.neutral());

    BrandedEmail mail =
        InviteFrameMailRendererFixture.inviteFrameMailRenderer(emailBrandingResolver)
            .render("Notice", "Der Vertrag ist unterschrieben.", null, 42L, language);

    assertNeutralFrame(mail, linkSentence, invitation, neutralNote);
  }

  /**
   * The informal German tone is in the catalogue too; no invite selects it yet, but its frame must
   * follow the same rule the day one does.
   */
  @Test
  void withoutAnActionTheInformalToneIsNeutralToo() {
    when(emailBrandingResolver.resolve(any())).thenReturn(EmailBranding.neutral());

    BrandedEmail mail =
        InviteFrameMailRendererFixture.inviteFrameMailRenderer(emailBrandingResolver)
            .render(
                "Hinweis",
                "Der Vertrag ist unterschrieben.",
                null,
                42L,
                InviteFrameMailRenderer.Labels.of(Tone.DE_INFORMAL));

    assertNeutralFrame(
        mail,
        "diesen Link",
        "Einladung",
        "Diese E-Mail wurde automatisch versendet. Bitte antworte nicht darauf.");
  }

  /** With an action the invitation frame is unchanged: security line plus invitation footer. */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "de | Wir fragen Sie nie per E-Mail nach Ihrem Passwort. Geben Sie diesen Link an niemanden"
            + " weiter. | Diese E-Mail gehört zu Ihrer Einladung und lässt sich nicht abbestellen."
            + " Bitte antworten Sie nicht darauf.",
        "en | We will never ask for your password by email. Do not pass this link on to anyone. |"
            + " This email is part of your invitation and cannot be unsubscribed from. Please do"
            + " not reply to it."
      })
  void withAnActionKeepsTheLinkSecurityLineAndTheInvitationFooter(
      String language, String securityLine, String invitationNote) {
    when(emailBrandingResolver.resolve(any())).thenReturn(EmailBranding.neutral());

    BrandedEmail mail =
        InviteFrameMailRendererFixture.inviteFrameMailRenderer(emailBrandingResolver)
            .render("Einladung", "Hallo", ACCEPT_URL, 42L, language);

    assertThat(mail.html()).contains(securityLine).contains(invitationNote).doesNotContain("{{");
    assertThat(mail.plainText())
        .contains(TEXT_RULE + "\n" + securityLine)
        .contains(invitationNote)
        .doesNotContain("{{");
  }

  /**
   * The plain-text divider that opens the fine print. It belongs to the fine print, as the HTML
   * divider does: a mail without the security line has no divider left over either.
   */
  private static final String TEXT_RULE = "-".repeat(64);

  private static void assertNeutralFrame(
      BrandedEmail mail, String linkSentence, String invitation, String neutralNote) {
    assertThat(mail.html())
        .contains("Der Vertrag ist unterschrieben.")
        .doesNotContain(linkSentence)
        .doesNotContain(invitation)
        .contains(neutralNote)
        .doesNotContain("{{");
    assertThat(mail.plainText())
        .contains("Der Vertrag ist unterschrieben.")
        .doesNotContain(linkSentence)
        .doesNotContain(invitation)
        .contains(neutralNote)
        .doesNotContain(TEXT_RULE)
        .doesNotContain("{{");
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

  // --- Sender block (Frank, 2026-09-23): Admin master data, Träger overrides field by field ---

  private static final long TRAEGER_ID = 42L;

  private BrandedEmail renderWithSenders(SenderOrganisationResolver senderOrganisations) {
    when(emailBrandingResolver.resolve(any()))
        .thenReturn(new EmailBranding("Träger Nord e.V.", null, "#1c4f8f", null, null));
    return InviteFrameMailRendererFixture.inviteFrameMailRenderer(
            emailBrandingResolver, senderOrganisations)
        .render("Einladung", "Hallo", ACCEPT_URL, TRAEGER_ID, "de");
  }

  @Test
  void footer_namesTheTraeger_withThePlatformOwnersAddress_When_theTraegerHasNone() {
    BrandedEmail mail =
        renderWithSenders(
            SenderOrganisationFixture.resolving(
                SenderOrganisationFixture.PLATFORM_OWNER,
                Map.of(TRAEGER_ID, new SenderOrganisation("Träger Nord e.V.", null, null))));

    assertThat(mail.html())
        .contains(">Träger Nord e.V.</div>")
        .contains(">Betreiberweg 1, 10115 Berlin</div>")
        .contains(">info@betreiber.example</div>")
        .contains(">Online-Beratung ist ein Angebot von ORISO.</div>")
        .doesNotContain("ist ein Angebot von Träger Nord");
    assertThat(mail.plainText())
        .contains("\nTräger Nord e.V.\nBetreiberweg 1, 10115 Berlin\ninfo@betreiber.example\n")
        .contains("\nOnline-Beratung ist ein Angebot von ORISO.\n")
        .doesNotContain("ist ein Angebot von Träger Nord");
  }

  @Test
  void footer_usesTheTraegersOwnAddress_When_itHasOne() {
    BrandedEmail mail =
        renderWithSenders(
            SenderOrganisationFixture.resolving(
                SenderOrganisationFixture.PLATFORM_OWNER,
                Map.of(
                    TRAEGER_ID,
                    new SenderOrganisation("Träger Nord e.V.", "Nordstraße 5, 24103 Kiel", null))));

    assertThat(mail.html())
        .contains(">Nordstraße 5, 24103 Kiel</div>")
        .doesNotContain("Betreiberweg");
    assertThat(mail.plainText())
        .contains("\nTräger Nord e.V.\nNordstraße 5, 24103 Kiel\ninfo@betreiber.example\n");
  }

  /**
   * "X ist ein Angebot von Y" names the platform operator (Admin → Dokument-Stammdaten →
   * Betreiber), never the Träger — even when the Träger's own name and address fill the sender
   * block above it (Frank, 2026-09-23).
   */
  @Test
  void offeredByLine_namesThePlatformOperator_When_theTraegerHasItsOwnNameAndAddress() {
    BrandedEmail mail =
        renderWithSenders(
            SenderOrganisationFixture.resolving(
                SenderOrganisationFixture.PLATFORM_OWNER,
                Map.of(
                    TRAEGER_ID,
                    new SenderOrganisation("Träger Nord e.V.", "Nordstraße 5, 24103 Kiel", null))));

    assertThat(mail.html())
        .contains(">Träger Nord e.V.</div>")
        .contains(">Online-Beratung ist ein Angebot von ORISO.</div>")
        .doesNotContain("ist ein Angebot von Träger Nord");
    assertThat(mail.plainText())
        .contains("\nOnline-Beratung ist ein Angebot von ORISO.\n")
        .doesNotContain("ist ein Angebot von Träger Nord");
  }

  /** Without an operator name the sentence goes; the Träger never stands in for the operator. */
  @Test
  void offeredByLine_isOmitted_notFilledWithTheTraeger_When_theOperatorHasNoName() {
    BrandedEmail mail =
        renderWithSenders(
            SenderOrganisationFixture.resolving(
                new SenderOrganisation(null, "Betreiberweg 1, 10115 Berlin", null),
                Map.of(TRAEGER_ID, new SenderOrganisation("Träger Nord e.V.", null, null))));

    for (String part : new String[] {mail.html(), mail.plainText()}) {
      assertThat(part)
          .contains("Träger Nord e.V.")
          .doesNotContain("ist ein Angebot von")
          .doesNotContain("{{");
    }
    assertThat(mail.plainText()).doesNotContain("\n\n\n");
  }

  @Test
  void footer_hasNoSenderLines_When_nobodyEnteredAny() {
    BrandedEmail mail = renderWithSenders(SenderOrganisationFixture.nobody());

    for (String part : new String[] {mail.html(), mail.plainText()}) {
      assertThat(part)
          .doesNotContain("Musterstraße")
          .doesNotContain("ist ein Angebot von")
          .doesNotContain("{{")
          .contains("Datenschutz")
          .contains("Impressum");
    }
    assertThat(mail.plainText()).doesNotContain("\n\n\n");
  }

  /**
   * End to end from what TenantService serves to what the recipient reads: the Träger's full legal
   * name is the sender, its own contact line replaces the platform owner's, and the address it did
   * not enter still comes from the platform owner.
   */
  @Test
  void footer_namesTheTraegersLegalNameAndContact_fromTheTenantServiceData() {
    BrandedEmail mail =
        renderWithSenders(
            SenderOrganisationFixture.resolvingTraegerFromTenantService(
                SenderOrganisationFixture.PLATFORM_OWNER,
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO()
                    .id(TRAEGER_ID)
                    .name("Caritas Nord")
                    .subdomain("nord")
                    .legalName("Caritasverband für die Erzdiözese Nord e.V.")
                    .contactEmail("beratung@caritas-nord.example")
                    .contactPhone("+49 431 123-0")));

    assertThat(mail.html())
        .contains(">Caritasverband für die Erzdiözese Nord e.V.</div>")
        .contains(">Betreiberweg 1, 10115 Berlin</div>")
        .contains(">beratung@caritas-nord.example · +49 431 123-0</div>")
        .doesNotContain("info@betreiber.example")
        // The Träger's legal name is the sender, never the operator in "X ist ein Angebot von Y".
        .contains(">Online-Beratung ist ein Angebot von ORISO.</div>")
        .doesNotContain("ist ein Angebot von Caritasverband");
    assertThat(mail.plainText())
        .contains(
            "\nCaritasverband für die Erzdiözese Nord e.V.\nBetreiberweg 1, 10115 Berlin\n"
                + "beratung@caritas-nord.example · +49 431 123-0\n")
        .doesNotContain("info@betreiber.example");
  }
}
