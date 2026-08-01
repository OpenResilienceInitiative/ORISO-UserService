package de.caritas.cob.userservice.api.service.email.layout;

/**
 * Input for {@link BrandedEmailLayoutRenderer}: what the author wrote plus the primary action the
 * platform wants the recipient to take.
 *
 * @param subject mail subject, also rendered as the headline of the content card
 * @param authorBody the stored template body — plain text or simple markup, always untrusted
 * @param primaryActionUrl the invite/onboarding link; {@code null} renders no button
 * @param primaryActionLabel button label; a blank value falls back to the layout default
 * @param language BCP-47 language tag for the {@code <html lang>} attribute
 */
public record BrandedEmailRequest(
    String subject,
    String authorBody,
    String primaryActionUrl,
    String primaryActionLabel,
    String language) {

  public static BrandedEmailRequest of(String subject, String authorBody, String primaryActionUrl) {
    return new BrandedEmailRequest(subject, authorBody, primaryActionUrl, null, null);
  }
}
