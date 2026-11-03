package de.caritas.cob.userservice.api.model;

/**
 * The one definition of a consultant's public contact-link name (ORISO-UserService#1534): lowercase
 * letters and digits in segments joined by single hyphens, e.g. {@code qa-shazia-2810}. Save
 * validation, the anonymous route matcher and the tenant resolver all build on this, so a name that
 * can be saved can also be opened.
 */
public final class PublicSlugFormat {

  /** Non-capturing only: Spring's PathPattern rejects capture groups in {var:regex}. */
  public static final String REGEX = "[a-z0-9]+(?:-[a-z0-9]+)*";

  /**
   * Longest name that can be saved (ORISO-UserService#1543): readable and printable as a link or QR
   * code, and well under the 128-character columns, so the database never refuses a name. Only
   * saving is limited; the anonymous lookup route still opens any existing longer name.
   */
  public static final int MAX_LENGTH = 50;

  private PublicSlugFormat() {}
}
