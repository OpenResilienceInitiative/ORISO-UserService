package de.caritas.cob.userservice.api.service.email.layout;

/**
 * A rendered mail: the branded HTML part and the plain-text alternative generated from the same
 * content. Both parts are always produced together so a multipart/alternative message can never
 * ship an empty or stale text part.
 */
public record BrandedEmail(
    String subject, String html, String plainText, BrandingSnapshot branding) {

  public BrandedEmail(String subject, String html, String plainText) {
    this(subject, html, plainText, null);
  }

  /** Public values used by this render; never SMTP settings or unvalidated tenant input. */
  public record BrandingSnapshot(
      String brandName,
      String logoUrl,
      String accentColor,
      String primaryColor,
      LogoRendering logoRendering) {}

  public enum LogoRendering {
    IMAGE,
    TEXT_WORDMARK
  }
}
