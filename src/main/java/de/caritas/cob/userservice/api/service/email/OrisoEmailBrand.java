package de.caritas.cob.userservice.api.service.email;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisation;
import de.caritas.cob.userservice.api.service.email.sender.SenderOrganisationResolver;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
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

  private final SenderOrganisationResolver senderOrganisations;
  private final EmailBrandingResolver brandingResolver;

  public OrisoEmailBrand(
      @NonNull SenderOrganisationResolver senderOrganisations,
      @NonNull EmailBrandingResolver brandingResolver) {
    this.senderOrganisations = senderOrganisations;
    this.brandingResolver = brandingResolver;
  }

  /** The platform name has one configuration source for subjects and shared mail frames. */
  public String platformName() {
    return brandingResolver.platformName();
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
    values.put("offeringName", platformName());
    SenderOrganisation operator = senderOrganisations.platform();
    putSender(values, operator);
    values.put("operatorName", orBlank(operator.name()));
    values.put("logoUrl", orBlank(branding.logoUrl()));
    if (branding.logoWidth() != null && branding.logoHeight() != null) {
      values.put("logoWidth", branding.logoWidth().toString());
      values.put("logoHeight", branding.logoHeight().toString());
    }
    // ADR-026 amendment 2026-10-02: stripe and button keep the tenant colour as configured; the
    // button label and the text-link colour are derived from it like the web app's tokens.
    values.put("primaryColor", branding.accentColor());
    values.put("accentColor", branding.accentColor());
    values.put("primaryTextColor", branding.buttonLabelColor());
    values.put("primaryLinkColor", branding.linkColor());
    values.put("appUrl", base);
    values.put("settingsUrl", base + "/profile/einstellungen/sicherheit");
    values.put("privacyUrl", branding.privacyUrl());
    values.put("imprintUrl", branding.imprintUrl());
    values.put("unsubscribeUrl", base + "/profile/einstellungen/email");
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

  private static String trimTrailingSlash(String url) {
    if (!isNotBlank(url)) {
      return "";
    }
    String trimmed = url.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
