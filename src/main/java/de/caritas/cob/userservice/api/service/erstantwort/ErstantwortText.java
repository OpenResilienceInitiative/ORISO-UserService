package de.caritas.cob.userservice.api.service.erstantwort;

/**
 * The one definition of "this Erstantwort text says nothing", shared by the resolver, the builder
 * and the localisation step (ORISO-UserService#1565). Three places that each decided for themselves
 * would disagree on exactly the inputs an editor produces: no-break spaces and zero-width
 * characters count as text to {@code isNotBlank}, and an empty Baustein would be published.
 */
final class ErstantwortText {

  /** No-break, figure and narrow no-break spaces become plain spaces. */
  private static final String NO_BREAK_SPACES = "[\\u00A0\\u2007\\u202F]";

  /** Zero-width space, non-joiner, joiner, word joiner and the byte-order mark. */
  private static final String ZERO_WIDTH = "[\\u200B\\u200C\\u200D\\u2060\\uFEFF]";

  private ErstantwortText() {}

  /** Plain spaces instead of no-break spaces, zero-width characters removed. */
  static String normalise(String value) {
    return value == null ? "" : value.replaceAll(NO_BREAK_SPACES, " ").replaceAll(ZERO_WIDTH, "");
  }

  static boolean hasText(String value) {
    return !normalise(value).isBlank();
  }

  static boolean anyText(String... values) {
    for (var value : values) if (hasText(value)) return true;
    return false;
  }
}
