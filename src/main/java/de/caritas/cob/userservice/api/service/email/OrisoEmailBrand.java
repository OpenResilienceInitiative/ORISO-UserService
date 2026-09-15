package de.caritas.cob.userservice.api.service.email;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fills the brand placeholders every ORISO mail carries.
 *
 * <p>Uses the same effective tenant branding as invite and legal mails. SMTP transport settings
 * never select the product logo or colour. Explicit tenant IDs are available for async senders.
 */
@Slf4j
@Component
public class OrisoEmailBrand {
  private final EmailBrandingResolver brandingResolver;

  public OrisoEmailBrand(EmailBrandingResolver brandingResolver) {
    this.brandingResolver = brandingResolver;
  }

  private static final Pattern HEX = Pattern.compile("^#([A-Fa-f0-9]{6})$");

  /**
   * WCAG AA for body text. The button label is white on the primary colour, so a Träger that picks
   * a light brand colour would otherwise ship an unreadable button in every mail it sends.
   */
  private static final double MIN_CONTRAST = 4.5d;

  private static final String DEFAULT_PRIMARY = "#a5000a";
  private static final String DEFAULT_ACCENT = "#cc1e1c";

  @Value("${email.brand.org-address:}")
  private String orgAddress;

  @Value("${email.brand.contact-line:}")
  private String contactLine;

  /**
   * @param appUrl absolute base URL of the app this mail links into
   * @param tenantThemeColor retained for existing callers; SMTP transport never selects branding
   */
  public Map<String, String> values(String appUrl, String tenantThemeColor) {
    return valuesForTenant(appUrl, TenantContext.getCurrentTenant());
  }

  /** Explicit tenant identity survives asynchronous send paths and does not depend on cookies. */
  public Map<String, String> valuesForTenant(String appUrl, Long tenantId) {
    if (!isNotBlank(appUrl)) {
      // Fail closed: a blank base turns every link in the mail into a bare path
      // (e.g. "/profile/settings") with no origin to resolve against. That is
      // not a degraded mail, it is a broken one, so this must not go out.
      throw new IllegalStateException(
          "appUrl must not be blank: every ORISO mail links back into the app");
    }
    String base = trimTrailingSlash(appUrl);
    Map<String, String> values = new LinkedHashMap<>();

    var branding = brandingResolver.resolve(tenantId);
    values.put("platformName", branding.brandName());
    values.put("orgName", branding.brandName());
    values.put("orgAddress", orgAddress == null ? "" : orgAddress);
    values.put("contactLine", contactLine == null ? "" : contactLine);
    values.put("logoUrl", branding.logoUrl() == null ? "" : branding.logoUrl());
    values.put("primaryColor", readablePrimary(branding.accentColor()));
    values.put("accentColor", DEFAULT_ACCENT);

    values.put("appUrl", base);
    values.put("settingsUrl", base + "/profile/settings");
    values.put(
        "privacyUrl",
        branding.privacyUrl() == null ? base + "/datenschutz" : branding.privacyUrl());
    values.put(
        "imprintUrl", branding.imprintUrl() == null ? base + "/impressum" : branding.imprintUrl());
    values.put("unsubscribeUrl", base + "/profile/settings/notifications");

    return values;
  }

  /**
   * The tenant colour if white text stays readable on it, the ORISO default otherwise.
   *
   * <p>ADR-021 puts the same check in the Admin colour field, where a Träger can see the measured
   * ratio and pick a different shade. This is the guard behind it: a colour that slipped through
   * still must not produce a button nobody can read.
   */
  String readablePrimary(String tenantThemeColor) {
    if (!isNotBlank(tenantThemeColor) || !HEX.matcher(tenantThemeColor.trim()).matches()) {
      return DEFAULT_PRIMARY;
    }
    String colour = tenantThemeColor.trim();
    double contrast = contrastWithWhite(colour);
    if (contrast < MIN_CONTRAST) {
      log.warn(
          "Tenant e-mail colour {} gives a contrast of {} against white, below the {} needed for a"
              + " readable button label. Falling back to the ORISO primary.",
          colour,
          String.format("%.2f", contrast),
          MIN_CONTRAST);
      return DEFAULT_PRIMARY;
    }
    return colour;
  }

  static double contrastWithWhite(String hex) {
    double luminance = relativeLuminance(hex);
    return 1.05d / (luminance + 0.05d);
  }

  private static double relativeLuminance(String hex) {
    double r = channel(Integer.parseInt(hex.substring(1, 3), 16));
    double g = channel(Integer.parseInt(hex.substring(3, 5), 16));
    double b = channel(Integer.parseInt(hex.substring(5, 7), 16));
    return 0.2126d * r + 0.7152d * g + 0.0722d * b;
  }

  private static double channel(int value) {
    double c = value / 255d;
    return c <= 0.03928d ? c / 12.92d : Math.pow((c + 0.055d) / 1.055d, 2.4d);
  }

  private static String trimTrailingSlash(String url) {
    if (!isNotBlank(url)) {
      return "";
    }
    String trimmed = url.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
