package de.caritas.cob.userservice.api.service.email;

import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.email.layout.EmailColors;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The brand placeholders of a design-system mail, with a tenant's branding laid over the platform
 * values.
 *
 * <p>{@link OrisoEmailBrand} is platform-level by contract (ADR-021); {@link EmailBrandingResolver}
 * resolves the tenant-varying half — name, absolute logo URL, accent colour, imprint and privacy
 * URLs — including "the tenant does not exist yet". Every tenant-branded mail builds its value map
 * here, so the invite and the DPA signing mail cannot brand the same tenant differently.
 */
@Component
public class TenantEmailBrandValues {

  private final OrisoEmailBrand orisoEmailBrand;
  private final String applicationBaseUrl;

  public TenantEmailBrandValues(
      @NonNull OrisoEmailBrand orisoEmailBrand,
      @Value("${app.base.url}") String applicationBaseUrl) {
    this.orisoEmailBrand = orisoEmailBrand;
    this.applicationBaseUrl = applicationBaseUrl;
  }

  /**
   * The platform value map with the tenant-varying values overlaid. Everything the resolver
   * produces is already validated: the logo is {@code null} or an absolute http(s) URL, the accent
   * is a {@code #rrggbb} literal, the footer URLs are absolute or {@code null}.
   *
   * @return a mutable map, so a caller can add its own content values
   */
  public Map<String, String> values(EmailBranding branding) {
    Map<String, String> values =
        new LinkedHashMap<>(orisoEmailBrand.values(applicationBaseUrl, branding.accentColor()));

    // Header wordmark and the "is a service provided by" line: the Träger's name, the platform's
    // operator. brandName already falls back to the configured platform name.
    values.put("platformName", branding.brandName());

    // Blank rather than absent: {{logoCell}} expands to nothing for a blank logo URL, and an
    // <img src=""> next to the wordmark is a broken-image icon in every mail client.
    values.put("logoUrl", branding.logoUrl() == null ? "" : branding.logoUrl());

    // The button fill is contrast-guarded (its label is white in the template); the 4px accent bar
    // only follows the tenant when the tenant actually configured a colour — otherwise the
    // platform's two-tone header (lighter bar, darker button) would collapse into one flat red.
    values.put("primaryColor", orisoEmailBrand.readablePrimary(branding.accentColor()));
    if (!EmailColors.PLATFORM_ACCENT_DARK.equals(branding.accentColor())) {
      values.put("accentColor", branding.accentColor());
    }

    if (branding.imprintUrl() != null) {
      values.put("imprintUrl", branding.imprintUrl());
    }
    if (branding.privacyUrl() != null) {
      values.put("privacyUrl", branding.privacyUrl());
    }
    return values;
  }
}
