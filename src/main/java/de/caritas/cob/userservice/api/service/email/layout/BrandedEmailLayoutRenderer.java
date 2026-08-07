package de.caritas.cob.userservice.api.service.email.layout;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.NonNull;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Renders the canonical branded mail (ORISO-UserService#914).
 *
 * <p>This is the <em>single</em> implementation of the platform's mail markup. The skeleton and
 * every optional block live as resources under {@code classpath:email/layout/}; this class only
 * resolves branding-dependent values and fills placeholders. Nothing else in the platform —
 * including the Admin panel — re-implements the markup: the Admin renders a preview through {@code
 * /useradmin/invite-email-templates/preview}, which calls straight into this renderer.
 *
 * <p>Email-client constraints honoured by the skeleton:
 *
 * <ul>
 *   <li>tables only, no flexbox/grid, no floats; every colour inline <em>and</em> as a {@code
 *       bgcolor} attribute so Outlook's Word renderer and dark-mode colour inversion cannot leave a
 *       cell unpainted;
 *   <li>{@code max-width:600px} with a single {@code max-width:620px} media query for mobile — the
 *       layout is readable at 390px without it;
 *   <li>no external assets except the logo URL — no web fonts, no background images, no CSS files;
 *   <li>{@code color-scheme: light only} plus explicit foreground colours on every text cell, so a
 *       dark-mode client that ignores the hint still gets a legible mail instead of relying on
 *       {@code prefers-color-scheme} alone;
 *   <li>a plain-text alternative built from the same content (see {@link
 *       EmailContentSanitizer#toPlainText(String)}).
 * </ul>
 */
@Component
public class BrandedEmailLayoutRenderer {

  private static final String RESOURCE_ROOT = "email/layout/";
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");
  private static final int PREHEADER_LENGTH = 120;

  private final EmailContentSanitizer sanitizer;

  private final String htmlSkeleton;
  private final String textSkeleton;
  private final String headerLogo;
  private final String headerWordmark;
  private final String ctaHtml;
  private final String ctaText;
  private final String footerLink;

  public BrandedEmailLayoutRenderer(@NonNull EmailContentSanitizer sanitizer) {
    this.sanitizer = sanitizer;
    this.htmlSkeleton = loadResource("branded-email.html");
    this.textSkeleton = loadResource("branded-email.txt");
    this.headerLogo = loadResource("header-logo.html");
    this.headerWordmark = loadResource("header-wordmark.html");
    this.ctaHtml = loadResource("cta.html");
    this.ctaText = loadResource("cta.txt");
    this.footerLink = loadResource("footer-link.html");
  }

  /** Renders the branded HTML part and its plain-text alternative. */
  public BrandedEmail render(EmailBranding branding, BrandedEmailRequest request) {
    EmailBranding effectiveBranding = branding == null ? EmailBranding.neutral() : branding;
    Labels labels = Labels.forLanguage(request == null ? null : request.language());
    String subject = request == null || isBlank(request.subject()) ? "" : request.subject().trim();

    String contentHtml =
        sanitizer.toContentHtml(
            request == null ? null : request.authorBody(), effectiveBranding.linkColor());
    String contentText = sanitizer.toPlainText(contentHtml);

    String actionUrl = safeActionUrl(request == null ? null : request.primaryActionUrl());
    String actionLabel =
        request != null && !isBlank(request.primaryActionLabel())
            ? request.primaryActionLabel().trim()
            : labels.ctaLabel();

    return new BrandedEmail(
        subject,
        renderHtml(effectiveBranding, labels, subject, contentHtml, actionUrl, actionLabel),
        renderText(effectiveBranding, labels, subject, contentText, actionUrl, actionLabel));
  }

  private String renderHtml(
      EmailBranding branding,
      Labels labels,
      String subject,
      String contentHtml,
      String actionUrl,
      String actionLabel) {
    String linkColor = branding.linkColor();
    String header =
        branding.hasLogo()
            ? fill(
                headerLogo,
                Map.of(
                    "LOGO_URL", escape(branding.logoUrl()),
                    "BRAND_NAME", escape(branding.brandName())))
            : fill(
                headerWordmark,
                Map.of("WORDMARK_COLOR", linkColor, "BRAND_NAME", escape(branding.brandName())));

    String cta =
        actionUrl == null
            ? ""
            : fill(
                ctaHtml,
                Map.of(
                    "ACCENT_COLOR", branding.accentColor(),
                    "ACCENT_TEXT_COLOR", branding.accentTextColor(),
                    "CTA_BORDER_COLOR", branding.ctaBorderColor(),
                    "LINK_COLOR", linkColor,
                    "ACTION_URL", escape(actionUrl),
                    "ACTION_URL_TEXT", escape(actionUrl),
                    "ACTION_LABEL", escape(actionLabel),
                    "FALLBACK_HINT", escape(labels.fallbackHint())));

    Map<String, String> values = new LinkedHashMap<>();
    values.put("LANG", labels.language());
    values.put("SUBJECT", escape(subject));
    values.put("PREHEADER", escape(preheader(contentHtml)));
    values.put("ACCENT_COLOR", branding.accentColor());
    values.put("LINK_COLOR", linkColor);
    values.put("HEADER", header);
    values.put("CONTENT", contentHtml);
    values.put("CTA", cta);
    values.put("BRAND_NAME", escape(branding.brandName()));
    values.put("FOOTER_LINKS", footerLinksHtml(branding, labels, linkColor));
    values.put("FOOTER_NOTE", escape(labels.footerNote()));
    return fill(htmlSkeleton, values);
  }

  private String renderText(
      EmailBranding branding,
      Labels labels,
      String subject,
      String contentText,
      String actionUrl,
      String actionLabel) {
    String cta =
        actionUrl == null
            ? ""
            : fill(ctaText, Map.of("ACTION_LABEL", actionLabel, "ACTION_URL", actionUrl));
    List<String> footerLinks = new ArrayList<>();
    if (branding.imprintUrl() != null) {
      footerLinks.add(labels.imprint() + ": " + branding.imprintUrl());
    }
    if (branding.privacyUrl() != null) {
      footerLinks.add(labels.privacy() + ": " + branding.privacyUrl());
    }

    Map<String, String> values = new LinkedHashMap<>();
    values.put("BRAND_NAME", branding.brandName());
    values.put("SUBJECT", subject);
    values.put("CONTENT", contentText);
    values.put("CTA", cta);
    values.put("FOOTER_LINKS", String.join("\n", footerLinks));
    values.put("FOOTER_NOTE", labels.footerNote());
    return fill(textSkeleton, values).replaceAll("\n{3,}", "\n\n");
  }

  private String footerLinksHtml(EmailBranding branding, Labels labels, String linkColor) {
    List<String> links = new ArrayList<>();
    if (branding.imprintUrl() != null) {
      links.add(footerLink(branding.imprintUrl(), labels.imprint(), linkColor));
    }
    if (branding.privacyUrl() != null) {
      links.add(footerLink(branding.privacyUrl(), labels.privacy(), linkColor));
    }
    return links.isEmpty() ? "" : String.join("&nbsp;&middot;&nbsp;", links) + "<br>";
  }

  private String footerLink(String url, String label, String linkColor) {
    return fill(
            footerLink, Map.of("URL", escape(url), "LABEL", escape(label), "LINK_COLOR", linkColor))
        .trim();
  }

  /**
   * Only absolute {@code http(s)} URLs become a button. Anything else (relative paths, {@code
   * javascript:}, {@code data:}) is dropped rather than interpolated into an {@code href}.
   */
  static String safeActionUrl(String url) {
    if (isBlank(url)) {
      return null;
    }
    String trimmed = url.trim();
    String lower = trimmed.toLowerCase(Locale.ROOT);
    return lower.startsWith("http://") || lower.startsWith("https://") ? trimmed : null;
  }

  private String preheader(String contentHtml) {
    String text = sanitizer.toPlainText(contentHtml).replaceAll("\\s+", " ").trim();
    return text.length() <= PREHEADER_LENGTH ? text : text.substring(0, PREHEADER_LENGTH) + "…";
  }

  /**
   * Single-pass placeholder substitution. Single-pass matters: substituted values (author content,
   * tenant names) are never re-scanned, so no value can smuggle in another placeholder. Unknown
   * placeholders resolve to the empty string.
   */
  static String fill(String template, Map<String, String> values) {
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String replacement = values.getOrDefault(matcher.group(1), "");
      matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static String escape(String value) {
    return value == null ? "" : EmailContentSanitizer.escapeHtml(value);
  }

  private static String loadResource(String name) {
    try (InputStream stream = new ClassPathResource(RESOURCE_ROOT + name).getInputStream()) {
      return StreamUtils.copyToString(stream, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Branded e-mail layout resource " + RESOURCE_ROOT + name + " is missing", exception);
    }
  }

  /**
   * Frame wording. Only the frame is localised — the body itself is authored per language in the
   * {@code InviteEmailTemplate} rows. German is the platform default, matching the existing
   * password-reset and DPA mails.
   */
  record Labels(
      String language,
      String ctaLabel,
      String fallbackHint,
      String imprint,
      String privacy,
      String footerNote) {

    private static final Labels GERMAN =
        new Labels(
            "de",
            "Einladung annehmen",
            "Falls der Button nicht funktioniert, kopieren Sie diesen Link in Ihren Browser:",
            "Impressum",
            "Datenschutz",
            "Diese E-Mail wurde automatisch versendet. Bitte antworten Sie nicht darauf.");

    private static final Labels ENGLISH =
        new Labels(
            "en",
            "Accept invitation",
            "If the button does not work, copy this link into your browser:",
            "Imprint",
            "Privacy",
            "This is an automated message. Please do not reply to this e-mail.");

    static Labels forLanguage(String language) {
      if (language != null && language.trim().toLowerCase(Locale.ROOT).startsWith("en")) {
        return ENGLISH;
      }
      return GERMAN;
    }
  }
}
