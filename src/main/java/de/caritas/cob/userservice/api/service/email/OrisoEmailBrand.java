package de.caritas.cob.userservice.api.service.email;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisation;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationResolver;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fills the brand placeholders every ORISO mail carries.
 *
 * <p>ADR-026 resolves every mail brand from TenantService through {@link EmailBrandingResolver}.
 * SMTP settings do not supply design colours. The sender identity (organisation, address, contact
 * line) is the platform owner's master data from the Admin panel, see {@link
 * SenderOrganisationResolver}.
 *
 * <p>The sender block is never invented: what the platform owner has not entered stays out of the
 * mail (Frank, 2026-09-23). Filling the Dokument-Stammdaten is what puts a sender in the footer.
 */
@Slf4j
@Component
public class OrisoEmailBrand {

  private static final Pattern HEX = Pattern.compile("^#([A-Fa-f0-9]{6})$");

  /**
   * WCAG AA for body text. The button label is white on the primary colour, so a Träger that picks
   * a light brand colour would otherwise ship an unreadable button in every mail it sends.
   */
  private static final double MIN_CONTRAST = 4.5d;

  private static final String DEFAULT_PRIMARY = "#a5000a";

  private final SenderOrganisationResolver senderOrganisations;
  private final EmailBrandingResolver brandingResolver;

  public OrisoEmailBrand(
      @NonNull SenderOrganisationResolver senderOrganisations,
      @NonNull EmailBrandingResolver brandingResolver) {
    this.senderOrganisations = senderOrganisations;
    this.brandingResolver = brandingResolver;
  }

  /** Values for the catalogue renderer, resolved from the same tenant source as invitation mail. */
  public Map<String, String> valuesForTenant(String appUrl, Long tenantId) {
    return valuesForResolvedBrand(appUrl, brandingResolver.resolve(tenantId));
  }

  /** Adapts branding already resolved by an invitation or DPA sender without a second lookup. */
  public Map<String, String> valuesForResolvedBrand(String appUrl, EmailBranding branding) {
    String base = requireAbsoluteBaseUrl(appUrl);
    if (branding.imprintUrl() == null || branding.privacyUrl() == null) {
      throw new IllegalStateException("Resolved email branding has no legal footer URLs");
    }
    Map<String, String> values = new LinkedHashMap<>();
    values.put("platformName", branding.brandName());
    values.put("offeringName", brandingResolver.resolve(null).brandName());
    SenderOrganisation operator = senderOrganisations.platform();
    putSender(values, operator);
    values.put("operatorName", orBlank(operator.name()));
    values.put("logoUrl", orBlank(branding.logoUrl()));
    values.put("primaryColor", branding.accentColor());
    values.put("accentColor", branding.accentColor());
    values.put("appUrl", base);
    values.put("settingsUrl", base + "/profile/settings");
    values.put("privacyUrl", branding.privacyUrl());
    values.put("imprintUrl", branding.imprintUrl());
    values.put("unsubscribeUrl", base + "/profile/settings/notifications");
    return values;
  }

  private static String requireAbsoluteBaseUrl(String appUrl) {
    if (!isNotBlank(appUrl)) {
      throw new IllegalStateException("appUrl is required for email links");
    }
    String base = trimTrailingSlash(appUrl);
    try {
      URI uri = URI.create(base);
      String scheme = uri.getScheme();
      if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null) {
        return base;
      }
    } catch (IllegalArgumentException ignored) {
      // Report a configuration error without echoing a possibly sensitive URL.
    }
    throw new IllegalStateException("appUrl must be an absolute HTTP(S) URL for email links");
  }

  /**
   * The footer's sender block. A value nobody entered in the Admin panel goes in blank, and the
   * renderer then drops its line — there is no sample organisation to fall back on.
   */
  static void putSender(Map<String, String> values, SenderOrganisation sender) {
    values.put("orgName", orBlank(sender.name()));
    values.put("orgAddress", orBlank(sender.address()));
    values.put("contactLine", orBlank(sender.contactLine()));
  }

  private static String orBlank(String value) {
    return value == null ? "" : value;
  }

  /**
   * The tenant colour if white text stays readable on it, the ORISO default otherwise.
   *
   * <p>ADR-021 puts the same check in the Admin colour field, where a Träger can see the measured
   * ratio and pick a different shade. This is the guard behind it: a colour that slipped through
   * still must not produce a button nobody can read.
   */
  public String readablePrimary(String tenantThemeColor) {
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
