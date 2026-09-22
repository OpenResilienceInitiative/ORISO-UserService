package de.caritas.cob.userservice.api.service.accountinvite.mail;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer.RenderedEmail;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer.Tone;
import de.caritas.cob.userservice.api.service.email.layout.BrandedEmail;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.layout.EmailColors;
import de.caritas.cob.userservice.api.service.email.layout.EmailContentSanitizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Renders an operator-authored invite mail inside the ORISO e-mail frame.
 *
 * <p>This replaces the hand-written layout under {@code classpath:email/layout/} for the invite
 * path: the frame is now the same design-system template every other ORISO mail uses ({@code
 * emails/{tone}/einladung-freitext.html}, ADR-020), and the operator's subject and body are the
 * only content in it. The order inside the card is header → subject → authored body → call to
 * action → copy-paste fallback line → footer.
 *
 * <p><b>Why the body is a fragment and not a value.</b> The authored body is not a string that can
 * be escaped into the document: it has paragraphs, it may carry the operator's own emphasis, and
 * its bare URLs must become anchors. {@link EmailContentSanitizer} already turns it into exactly
 * one safe thing — an allow-listed HTML fragment — so it is handed to the renderer as a fragment
 * and inserted verbatim. Sanitisation is unchanged, and so is the escaping of every other value.
 *
 * <p><b>Why the branding is an overlay.</b> {@link OrisoEmailBrand} is platform-level by contract
 * (ADR-021) and has no TenantService wiring; {@link EmailBrandingResolver} already resolves the
 * tenant-varying half — name, absolute logo URL, accent colour, imprint and privacy URLs — with
 * every fallback the invite path needs, including "the tenant does not exist yet". Overlaying the
 * resolver's five values onto the platform value map is therefore strictly smaller than teaching
 * {@code OrisoEmailBrand} to talk to TenantService, and it leaves exactly one implementation of
 * tenant branding resolution in the service rather than two that can disagree.
 */
@Component
public class InviteFrameMailRenderer {

  /** The generic catalogue template: frame plus an empty slot for the operator's text. */
  static final String TEMPLATE_ID = "einladung-freitext";

  private static final int PREHEADER_LENGTH = 120;

  private final EmailBrandingResolver emailBrandingResolver;
  private final EmailContentSanitizer sanitizer;
  private final OrisoEmailBrand orisoEmailBrand;
  private final OrisoEmailRenderer orisoEmailRenderer;
  private final String applicationBaseUrl;

  public InviteFrameMailRenderer(
      @NonNull EmailBrandingResolver emailBrandingResolver,
      @NonNull EmailContentSanitizer sanitizer,
      @NonNull OrisoEmailBrand orisoEmailBrand,
      @NonNull OrisoEmailRenderer orisoEmailRenderer,
      @Value("${app.base.url}") String applicationBaseUrl) {
    this.emailBrandingResolver = emailBrandingResolver;
    this.sanitizer = sanitizer;
    this.orisoEmailBrand = orisoEmailBrand;
    this.orisoEmailRenderer = orisoEmailRenderer;
    this.applicationBaseUrl = applicationBaseUrl;
  }

  /**
   * @param subject the operator's subject, placeholders already substituted
   * @param bodyContent the operator's body, placeholders already substituted; content, not markup
   * @param primaryActionUrl the accept URL rendered as a button plus a copy-paste fallback line, or
   *     {@code null} for a mail without an action
   * @param tenantId tenant whose branding the frame carries, or {@code null} for platform branding
   * @param language BCP-47 tag selecting the frame wording and tone; {@code null} means German
   */
  public BrandedEmail render(
      String subject, String bodyContent, String primaryActionUrl, Long tenantId, String language) {
    EmailBranding branding = emailBrandingResolver.resolve(tenantId);
    Labels labels = Labels.forLanguage(language);

    String safeSubject = isBlank(subject) ? "" : subject.trim();
    String bodyHtml = sanitizer.toContentHtml(bodyContent, branding.linkColor());
    String bodyText = sanitizer.toPlainText(bodyHtml);

    Map<String, String> values = brandValues(branding);
    values.put("subject", safeSubject);
    values.put("preheader", preheader(bodyText));
    values.put("linkColor", branding.linkColor());
    values.put("actionLabel", labels.ctaLabel());
    values.put("fallbackHint", labels.fallbackHint());
    String actionUrl = safeActionUrl(primaryActionUrl);
    if (actionUrl != null) {
      values.put("actionUrl", actionUrl);
    }

    RenderedEmail rendered =
        orisoEmailRenderer.render(
            TEMPLATE_ID, labels.tone(), values, Map.of("bodyHtml", bodyHtml, "bodyText", bodyText));

    // The catalogue subject of this template is {{subject}} itself, so the rendered subject is the
    // operator's, unchanged — the Admin preview and the sent mail therefore show the same line.
    return new BrandedEmail(
        rendered.subject(), rendered.html(), rendered.text().replaceAll("\n{3,}", "\n\n"));
  }

  /**
   * The platform value map with the tenant-varying values overlaid. Everything the resolver
   * produces is already validated: the logo is {@code null} or an absolute http(s) URL, the accent
   * is a {@code #rrggbb} literal, the footer URLs are absolute or {@code null}.
   */
  private Map<String, String> brandValues(EmailBranding branding) {
    Map<String, String> values =
        new LinkedHashMap<>(orisoEmailBrand.values(applicationBaseUrl, branding.accentColor()));

    // Header wordmark and the "is a service provided by" line: the Träger's name, the platform's
    // operator. brandName already falls back to the configured platform name.
    values.put("platformName", branding.brandName());

    // Blank rather than absent: {{logoCell}} expands to nothing for a blank logo URL, and an
    // <img src=""> next to the wordmark is a broken-image icon in every mail client.
    values.put("logoUrl", branding.logoUrl() == null ? "" : branding.logoUrl());

    // The button fill is contrast-guarded (its label is white in the template); the 4px accent bar
    // only follows the tenant when the tenant actually configured a colour — otherwise the
    // platform's two-tone header (lighter bar, darker button) would collapse into one flat red.
    values.put("primaryColor", orisoEmailBrand.readablePrimary(branding.accentColor()));
    if (!EmailColors.PLATFORM_ACCENT_DARK.equals(branding.accentColor())) {
      values.put("accentColor", branding.accentColor());
    }

    if (branding.imprintUrl() != null) {
      values.put("imprintUrl", branding.imprintUrl());
    }
    if (branding.privacyUrl() != null) {
      values.put("privacyUrl", branding.privacyUrl());
    }
    return values;
  }

  /** The hidden line mail clients show next to the subject in the inbox list. */
  private String preheader(String bodyText) {
    String text = bodyText == null ? "" : bodyText.replaceAll("\\s+", " ").trim();
    return text.length() <= PREHEADER_LENGTH ? text : text.substring(0, PREHEADER_LENGTH) + "…";
  }

  /**
   * Only absolute {@code http(s)} URLs become a button. Anything else (relative paths, {@code
   * javascript:}, {@code data:}) is dropped rather than interpolated into an {@code href} — the
   * same rule the previous layout applied.
   */
  static String safeActionUrl(String url) {
    if (isBlank(url)) {
      return null;
    }
    String trimmed = url.trim();
    String lower = trimmed.toLowerCase(Locale.ROOT);
    return lower.startsWith("http://") || lower.startsWith("https://") ? trimmed : null;
  }

  /**
   * Frame wording and tone. Only the frame is localised — the body itself is authored per language
   * in the {@code InviteEmailTemplate} rows. German is the platform default and resolves to the
   * formal tone, exactly as the previous layout did; the informal German templates exist in the
   * catalogue but no invite reaches them yet.
   */
  record Labels(Tone tone, String ctaLabel, String fallbackHint) {

    private static final Labels GERMAN =
        new Labels(
            Tone.DE_FORMAL,
            "Einladung annehmen",
            "Falls der Button nicht funktioniert, kopieren Sie diesen Link in Ihren Browser:");

    private static final Labels ENGLISH =
        new Labels(
            Tone.EN,
            "Accept invitation",
            "If the button does not work, copy this link into your browser:");

    static Labels forLanguage(String language) {
      if (language != null && language.trim().toLowerCase(Locale.ROOT).startsWith("en")) {
        return ENGLISH;
      }
      return GERMAN;
    }
  }
}
