package de.caritas.cob.userservice.api.service.email.layout;

/**
 * Resolved branding for one outgoing mail (ORISO-UserService#914).
 *
 * <p>Everything in here is already validated and safe to interpolate into the layout: {@code
 * logoUrl} is either {@code null} or an absolute http(s) URL, the colour is a {@code #rrggbb}
 * literal, the footer URLs are absolute http(s) URLs or {@code null}.
 *
 * @param brandName tenant name, falling back to the configured platform name
 * @param logoUrl absolute logo URL, or {@code null} to render the text wordmark instead
 * @param accentColor the tenant's primary colour as configured (ADR-026 amendment 2026-10-02): the
 *     header stripe and the button fill. When no tenant or platform colour is usable, {@link
 *     EmailBrandingResolver} supplies the neutral default {@code #000000}.
 * @param imprintUrl legal/imprint pointer for the footer, or {@code null}
 * @param privacyUrl privacy pointer for the footer, or {@code null}
 */
public record EmailBranding(
    String brandName, String logoUrl, String accentColor, String imprintUrl, String privacyUrl) {

  public EmailBranding {
    brandName = brandName == null || brandName.isBlank() ? "ORISO" : brandName.trim();
    String normalized = EmailColors.normalize(accentColor);
    if (normalized == null) {
      throw new IllegalArgumentException("Email branding needs a #rrggbb brand colour");
    }
    accentColor = normalized;
  }

  /** Label colour on the button: white or a dark tone of the same hue, like the web app. */
  public String buttonLabelColor() {
    return EmailColors.onPrimary(accentColor);
  }

  /** Brand colour that stays legible as link text / wordmark on the white content area. */
  public String linkColor() {
    return EmailColors.onLightBackground(accentColor);
  }

  public boolean hasLogo() {
    return logoUrl != null && !logoUrl.isBlank();
  }
}
