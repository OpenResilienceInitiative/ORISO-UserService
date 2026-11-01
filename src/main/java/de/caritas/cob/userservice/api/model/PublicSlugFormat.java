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

  private PublicSlugFormat() {}
}
