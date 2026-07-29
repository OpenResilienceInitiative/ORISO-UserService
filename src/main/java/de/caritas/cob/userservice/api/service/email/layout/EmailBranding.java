package de.caritas.cob.userservice.api.service.email.layout;

/**
 * Resolved branding for one outgoing mail (ORISO-UserService#914).
 *
 * <p>Everything in here is already validated and safe to interpolate into the layout: {@code
 * logoUrl} is either {@code null} or an absolute http(s) URL, the colours are {@code #rrggbb}
 * literals, the footer URLs are absolute http(s) URLs or {@code null}.
 *
 * @param brandName tenant name, falling back to the configured platform name
 * @param logoUrl absolute logo URL, or {@code null} to render the text wordmark instead
 * @param accentColor theme colour used for the top bar and the call-to-action button
 * @param imprintUrl legal/imprint pointer for the footer, or {@code null}
 * @param privacyUrl privacy pointer for the footer, or {@code null}
 */
public record EmailBranding(
    String brandName, String logoUrl, String accentColor, String imprintUrl, String privacyUrl) {

  public EmailBranding {
    brandName = brandName == null || brandName.isBlank() ? "ORISO" : brandName.trim();
    accentColor =
        accentColor == null ? EmailColors.DEFAULT_ACCENT : EmailColors.normalize(accentColor);
    if (accentColor == null) {
      accentColor = EmailColors.DEFAULT_ACCENT;
    }
  }

  /** Neutral branding for tenants (and technical contexts) without any theming configured. */
  public static EmailBranding neutral() {
    return new EmailBranding("ORISO", null, EmailColors.DEFAULT_ACCENT, null, null);
  }

  /** Foreground colour on the accent-filled call-to-action button — contrast-guarded. */
  public String accentTextColor() {
    return EmailColors.readableTextColor(accentColor);
  }

  /** Accent variant that stays legible as link text / wordmark on the white content area. */
  public String linkColor() {
    return EmailColors.onLightBackground(accentColor);
  }

  /** Outline keeping a near-white button visible against the white card. */
  public String ctaBorderColor() {
    return EmailColors.borderColor(accentColor);
  }

  public boolean hasLogo() {
    return logoUrl != null && !logoUrl.isBlank();
  }
}
