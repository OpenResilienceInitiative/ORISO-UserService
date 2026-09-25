package de.caritas.cob.userservice.api.service.email;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisation;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fills the brand placeholders every ORISO mail carries.
 *
 * <p>The target contract is ADR-021: seven brand values plus a sender identity, stored per Träger
 * in TenantService. The sender identity (organisation, address, contact line) is the platform
 * owner's master data from the Admin panel, see {@link SenderOrganisationResolver}; the colour is
 * the one value per tenant that exists today — {@code emailThemeColor} on the tenant SMTP settings.
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
  private static final String DEFAULT_ACCENT = "#cc1e1c";

  @Value("${email.brand.platform-name:Online-Beratung}")
  private String platformName;

  @Value("${email.brand.logo-url:}")
  private String logoUrl;

  private final SenderOrganisationResolver senderOrganisations;

  public OrisoEmailBrand(@NonNull SenderOrganisationResolver senderOrganisations) {
    this.senderOrganisations = senderOrganisations;
  }

  /**
   * @param appUrl absolute base URL of the app this mail links into
   * @param tenantThemeColor the tenant's {@code emailThemeColor}, or null
   */
  public Map<String, String> values(String appUrl, String tenantThemeColor) {
    if (!isNotBlank(appUrl)) {
      // Fail closed: a blank base turns every link in the mail into a bare path
      // (e.g. "/profile/settings") with no origin to resolve against. That is
      // not a degraded mail, it is a broken one, so this must not go out.
      throw new IllegalStateException(
          "appUrl must not be blank: every ORISO mail links back into the app");
    }
    String base = trimTrailingSlash(appUrl);
    Map<String, String> values = new LinkedHashMap<>();

    values.put("platformName", platformName);
    // The offered-by line describes the platform; unlike platformName, no sender brands it.
    values.put("offeringName", platformName);
    SenderOrganisation operator = senderOrganisations.platform();
    putSender(values, operator);
    // Y in "X ist ein Angebot von Y": always the platform operator, never a Träger that overlays
    // the sender block (Frank, 2026-09-23). Blank when not entered, so the line is dropped.
    values.put("operatorName", orBlank(operator.name()));
    values.put("logoUrl", logoUrl);
    values.put("primaryColor", readablePrimary(tenantThemeColor));
    values.put("accentColor", DEFAULT_ACCENT);

    values.put("appUrl", base);
    values.put("settingsUrl", base + "/profile/settings");
    values.put("privacyUrl", base + "/datenschutz");
    values.put("imprintUrl", base + "/impressum");
    values.put("unsubscribeUrl", base + "/profile/settings/notifications");

    return values;
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
