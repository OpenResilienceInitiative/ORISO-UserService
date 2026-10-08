package de.caritas.cob.userservice.api.service.email.layout;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.util.Locale;

/**
 * Colour rules of the branded e-mail layout: the same design-token logic as the web frontend
 * (ADR-026 amendment 2026-10-02, ORISO-UserService#1252, ORISO-Docs#143).
 *
 * <p>Mail clients give us no cascade to fall back on: whatever colour pair we inline is what the
 * recipient sees. A tenant's {@code theming.primaryColor} is therefore used <b>as configured</b>
 * for the header stripe and the button fill, and every foreground colour is derived from it the way
 * ORISO-Frontend's {@code computeOrisoPalette} does:
 *
 * <ul>
 *   <li>{@link #usablePrimary(String)} accepts a seed unless it is not a hex colour or is too pale
 *       (near-grey, HCT chroma below {@value #TOO_PALE_CHROMA}); mail and web app reject the same
 *       seeds. A light but chromatic colour such as yellow is accepted.
 *   <li>{@link #onPrimary(String)} is the button label: white if the seed reaches 4.5:1 against
 *       white, otherwise the same-hue dark tone 10 (the web app's {@code --m3-on-primary}).
 *   <li>{@link #onLightBackground(String)} darkens a colour until it clears 4.5:1 on the white
 *       content area, for link text; stripe and button keep the tenant colour.
 * </ul>
 *
 * <p>The HCT arithmetic lives in {@link Hct}. Parity with the web app is asserted against the
 * Frontend-owned golden fixture by {@code EmailColorsParityTest}. Neutral surface colours belong to
 * the canonical generated mail resources.
 */
public final class EmailColors {

  /** The web app's {@code TOO_PALE_CHROMA} (orisoTuning.ts): below this a seed is ignored. */
  static final double TOO_PALE_CHROMA = 12;

  /** {@code --m3-on-surface}. */
  static final String DARK_TEXT = "#1b1b1c";

  static final String WHITE = "#ffffff";

  /** WCAG AA for normal body text; the web app's {@code CONTRAST_AA}. */
  static final double AA_NORMAL_TEXT = 4.5d;

  private EmailColors() {}

  /**
   * Normalises a configured colour to a lower-case {@code #rrggbb} literal, accepting the {@code
   * #rgb} short form. Anything else (named colours, {@code rgb()}, CSS expressions, injection
   * attempts) is rejected with {@code null} — the layout must never interpolate unvalidated text
   * into a {@code style} attribute.
   */
  public static String normalize(String color) {
    if (isBlank(color)) {
      return null;
    }
    String value = color.trim().toLowerCase(Locale.ROOT);
    if (!value.startsWith("#")) {
      value = "#" + value;
    }
    if (value.matches("^#[0-9a-f]{3}$")) {
      return "#"
          + value.charAt(1)
          + value.charAt(1)
          + value.charAt(2)
          + value.charAt(2)
          + value.charAt(3)
          + value.charAt(3);
    }
    return value.matches("^#[0-9a-f]{6}$") ? value : null;
  }

  /** Returns the first syntactically valid colour of the given candidates, or {@code null}. */
  public static String firstValid(String... candidates) {
    if (candidates == null) {
      return null;
    }
    for (String candidate : candidates) {
      String normalized = normalize(candidate);
      if (normalized != null) {
        return normalized;
      }
    }
    return null;
  }

  /**
   * The seed a tenant colour yields, or {@code null} when the web app would ignore it as well: not
   * a hex colour, or too pale to carry a brand palette (see {@link #isTooPale(String)}). A light
   * chromatic colour is usable; the web app does not reject it either.
   */
  public static String usablePrimary(String color) {
    String normalized = normalize(color);
    if (normalized == null || isTooPale(normalized)) {
      return null;
    }
    return normalized;
  }

  /** Near-achromatic colours (black, white, grey) cannot carry a brand palette. */
  public static boolean isTooPale(String normalizedColor) {
    return Hct.fromHex(normalizedColor).chroma() < TOO_PALE_CHROMA;
  }

  /**
   * Label colour on a button filled with {@code primary}, derived like the web app's {@code
   * --m3-on-primary}: white if the colour reaches 4.5:1 against white, otherwise tone 10 of the
   * same hue and chroma, a dark tone of the brand colour.
   */
  public static String onPrimary(String primary) {
    String normalized = normalize(primary);
    if (normalized == null) {
      throw new IllegalArgumentException("A #rrggbb colour is required");
    }
    Hct hct = Hct.fromHex(normalized);
    return Hct.ratioOfTones(100, hct.tone()) >= AA_NORMAL_TEXT ? WHITE : hct.toneHex(10);
  }

  /**
   * Darkens {@code color} until it reaches AA contrast against the white content area, so accent
   * colours can safely be used for link text and the wordmark. Returns near-black if even full
   * darkening cannot get there (it always can, but the loop stays bounded).
   */
  public static String onLightBackground(String color) {
    String normalized = normalize(color);
    if (normalized == null) {
      return DARK_TEXT;
    }
    String candidate = normalized;
    for (int step = 0; step < 24; step++) {
      if (contrastRatio(candidate, WHITE) >= AA_NORMAL_TEXT) {
        return candidate;
      }
      candidate = darken(candidate, 0.12d);
    }
    return DARK_TEXT;
  }

  /** WCAG 2.x contrast ratio between two opaque colours; always {@code >= 1.0}. */
  public static double contrastRatio(String first, String second) {
    double a = relativeLuminance(first);
    double b = relativeLuminance(second);
    double lighter = Math.max(a, b);
    double darker = Math.min(a, b);
    return (lighter + 0.05d) / (darker + 0.05d);
  }

  /** WCAG relative luminance of a normalised colour. */
  public static double relativeLuminance(String color) {
    String normalized = normalize(color);
    if (normalized == null) {
      return 0d;
    }
    double r = channel(Integer.parseInt(normalized.substring(1, 3), 16));
    double g = channel(Integer.parseInt(normalized.substring(3, 5), 16));
    double b = channel(Integer.parseInt(normalized.substring(5, 7), 16));
    return 0.2126d * r + 0.7152d * g + 0.0722d * b;
  }

  static String darken(String color, double factor) {
    String normalized = normalize(color);
    if (normalized == null) {
      throw new IllegalArgumentException("A #rrggbb colour is required");
    }
    int r = scale(Integer.parseInt(normalized.substring(1, 3), 16), factor);
    int g = scale(Integer.parseInt(normalized.substring(3, 5), 16), factor);
    int b = scale(Integer.parseInt(normalized.substring(5, 7), 16), factor);
    return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
  }

  private static int scale(int component, double factor) {
    return Math.max(0, Math.min(255, (int) Math.round(component * (1d - factor))));
  }

  private static double channel(int component) {
    double value = component / 255d;
    return value <= 0.03928d ? value / 12.92d : Math.pow((value + 0.055d) / 1.055d, 2.4d);
  }
}
