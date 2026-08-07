package de.caritas.cob.userservice.api.service.email.layout;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.util.Locale;

/**
 * Colour arithmetic and the platform palette for the branded e-mail layout (ORISO-UserService#914).
 *
 * <p>Mail clients give us no cascade to fall back on: whatever colour pair we inline is what the
 * recipient sees. Tenants configure their theme colours freely — a tenant {@code primaryColor} of
 * {@code #f8e71c} is a yellow that renders white text effectively invisible. So every foreground
 * colour in the layout is *derived* from the configured accent instead of assumed:
 *
 * <ul>
 *   <li>{@link #readableTextColor(String)} picks the better of near-black / white by WCAG contrast
 *       ratio, so light accents get dark text automatically.
 *   <li>{@link #onLightBackground(String)} darkens accents that would disappear on the white
 *       content area (link text, wordmark) until they clear the 4.5:1 body-text threshold.
 *   <li>{@link #borderColor(String)} gives a nearly invisible button a visible outline.
 * </ul>
 *
 * <p>The neutral surfaces are <em>not</em> defined here — they are literals in {@code
 * classpath:email/layout/*.html}, taken one-for-one from the product's own tokens in {@code
 * ORISO-Admin/src/app.css}: {@code #e4e2e2} (--admin-workspace-background), {@code #ffffff}
 * (--m3-surface-container-lowest), {@code #f0edee} (--m3-surface-container), {@code #c4c7c8}
 * (--admin-field-outline), {@code #1b1b1c} (--m3-on-surface), {@code #444748}
 * (--m3-on-surface-variant) and {@code #747878} (--m3-outline).
 */
public final class EmailColors {

  /**
   * The product's own dark accent, {@code --oriso-app-accent-dark} in {@code
   * ORISO-Admin/src/app.css}. Used whenever a tenant configured no colour of its own.
   *
   * <p>Per the binding colour rule of #914 the <b>light</b> rendering uses the <b>dark</b> accent —
   * that is what {@code theming.primaryColor} is, a light-mode token. The mirrored constant for the
   * dark rendering (the light/rose accent) deliberately does not exist yet; see {@link
   * EmailBrandingResolver} for the single seam and for what unblocks it.
   */
  public static final String PLATFORM_ACCENT_DARK = "#a5000a";

  /** {@code --m3-on-surface}. */
  static final String DARK_TEXT = "#1b1b1c";

  static final String LIGHT_TEXT = "#ffffff";
  static final String WHITE = "#ffffff";

  /** WCAG AA for normal body text. */
  private static final double AA_NORMAL_TEXT = 4.5d;

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
   * Foreground colour to use on top of {@code background}: near-black or white, whichever has the
   * higher contrast ratio. This is the guard against "light text on a light background".
   */
  public static String readableTextColor(String background) {
    String normalized = normalize(background);
    if (normalized == null) {
      return LIGHT_TEXT;
    }
    return contrastRatio(normalized, DARK_TEXT) >= contrastRatio(normalized, LIGHT_TEXT)
        ? DARK_TEXT
        : LIGHT_TEXT;
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

  /**
   * Outline for a filled button: the accent itself when it is clearly distinguishable from the
   * white card, otherwise a darkened variant so a near-white accent still reads as a button.
   */
  public static String borderColor(String background) {
    String normalized = normalize(background);
    if (normalized == null) {
      return PLATFORM_ACCENT_DARK;
    }
    return contrastRatio(normalized, WHITE) >= 1.5d ? normalized : darken(normalized, 0.35d);
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
      return PLATFORM_ACCENT_DARK;
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
